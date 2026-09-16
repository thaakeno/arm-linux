#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def p(rel: str) -> Path:
    return ROOT / rel


def read(rel: str) -> str:
    return p(rel).read_text()


def write(rel: str, text: str) -> None:
    path = p(rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def replace_once(rel: str, old: str, new: str) -> None:
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one occurrence, got {count}: {old[:180]!r}")
    write(rel, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# 1. Replace the app-side Vulkan swapchain importer with an EGL native-Surface
#    presenter. AHardwareBuffer remains only as the unavoidable same-UID
#    cross-process GPU handoff from vhost-device-gpu; Android's BufferQueue /
#    SurfaceFlinger owns the final presentation path and pacing.
# ---------------------------------------------------------------------------
surface_cpp = r'''#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VesselSurfacePresenter";
constexpr uint32_t MAGIC = 0x42484156u;
constexpr uint32_t VERSION = 3;
constexpr uint32_t MSG_REGISTER_FRAME = 1;
constexpr uint32_t MSG_FRAME = 2;
constexpr uint32_t MSG_DISABLE = 3;
constexpr uint32_t FRAME_SLOTS = 3;
constexpr uint8_t FENCE_TAG = 0xF3;

#pragma pack(push, 1)
struct AhbMessage {
    uint32_t magic;
    uint32_t version;
    uint32_t type;
    uint32_t scanout_id;
    uint32_t slot;
    uint32_t serial;
    uint32_t width;
    uint32_t height;
};
struct AckMessage {
    uint32_t magic;
    uint32_t version;
    uint32_t scanout_id;
    uint32_t slot;
    uint32_t serial;
    uint32_t ok;
};
#pragma pack(pop)

using GetNativeClientBuffer = PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC;
using CreateImage = PFNEGLCREATEIMAGEKHRPROC;
using DestroyImage = PFNEGLDESTROYIMAGEKHRPROC;
using ImageTargetTexture = PFNGLEGLIMAGETARGETTEXTURE2DOESPROC;
using CreateSync = PFNEGLCREATESYNCKHRPROC;
using DestroySync = PFNEGLDESTROYSYNCKHRPROC;
using WaitSync = PFNEGLWAITSYNCKHRPROC;

struct SourceSlot {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    GLuint read_fbo = 0;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct PendingAck {
    GLsync sync = nullptr;
    uint32_t scanout = 0;
    uint32_t slot = 0;
    uint32_t serial = 0;
};

void emit_log(int priority, const char* level, const std::string& message) {
    __android_log_print(priority, TAG, "%s", message.c_str());
    std::fprintf(stderr, "[SURFACE/%s] %s\n", level, message.c_str());
    std::fflush(stderr);
}
void logi(const std::string& message) { emit_log(ANDROID_LOG_INFO, "I", message); }
void loge(const std::string& message) { emit_log(ANDROID_LOG_ERROR, "E", message); }

bool recv_all(int fd, void* data, size_t size) {
    auto* p = static_cast<uint8_t*>(data);
    while (size) {
        const ssize_t n = recv(fd, p, size, MSG_WAITALL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += static_cast<size_t>(n);
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool send_ack(int fd, uint32_t scanout, uint32_t slot, uint32_t serial, bool ok) {
    if (fd < 0 || serial == 0) return true;
    const AckMessage ack{MAGIC, VERSION, scanout, slot, serial, ok ? 1u : 0u};
    const auto* ptr = reinterpret_cast<const uint8_t*>(&ack);
    size_t left = sizeof(ack);
    while (left) {
        const ssize_t n = send(fd, ptr + sizeof(ack) - left, left, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        left -= static_cast<size_t>(n);
    }
    return true;
}

int recv_fence_fd(int socket_fd) {
    uint8_t tag = 0;
    iovec io{&tag, sizeof(tag)};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr msg{};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n;
    do { n = recvmsg(socket_fd, &msg, MSG_CMSG_CLOEXEC); } while (n < 0 && errno == EINTR);
    if (n != static_cast<ssize_t>(sizeof(tag)) || tag != FENCE_TAG) return -1;
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS && cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int fd = -1;
            std::memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
            return fd;
        }
    }
    return -1;
}

class NativeSurfacePresenter {
public:
    void configure(uint32_t width, uint32_t height) {
        preferred_width_.store(std::clamp(width, 640u, 3840u));
        preferred_height_.store(std::clamp(height, 480u, 2160u));
    }

    void start() {
        std::lock_guard<std::mutex> guard(state_lock_);
        if (server_.joinable()) return;
        stop_ = false;
        server_ = std::thread([this] { server_loop(); });
    }

    void stop() {
        stop_ = true;
        int listen_fd = -1;
        int client_fd = -1;
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            listen_fd = listen_fd_;
            client_fd = client_fd_;
        }
        if (client_fd >= 0) shutdown(client_fd, SHUT_RDWR);
        if (listen_fd >= 0) shutdown(listen_fd, SHUT_RDWR);
        if (server_.joinable()) server_.join();
        std::lock_guard<std::mutex> guard(state_lock_);
        if (requested_window_) {
            ANativeWindow_release(requested_window_);
            requested_window_ = nullptr;
        }
        status_ = "stopped";
    }

    void attach(JNIEnv* env, jobject surface) {
        ANativeWindow* next = ANativeWindow_fromSurface(env, surface);
        if (!next) {
            set_status("presenter-error:native-surface");
            return;
        }
        std::lock_guard<std::mutex> guard(state_lock_);
        if (requested_window_) ANativeWindow_release(requested_window_);
        requested_window_ = next;
        requested_width_ = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(next)));
        requested_height_ = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(next)));
        window_change_ = true;
        status_ = "surface-attached-pending";
    }

    void surface_changed(uint32_t width, uint32_t height) {
        if (!width || !height) return;
        std::lock_guard<std::mutex> guard(state_lock_);
        requested_width_ = width;
        requested_height_ = height;
        surface_size_change_ = true;
    }

    void detach() {
        std::lock_guard<std::mutex> guard(state_lock_);
        if (requested_window_) {
            ANativeWindow_release(requested_window_);
            requested_window_ = nullptr;
        }
        window_change_ = true;
        requested_width_ = 0;
        requested_height_ = 0;
        status_ = latest_width_.load() ? "frame-ready-waiting-for-surface" : "surface-detached";
    }

    std::string status() {
        std::lock_guard<std::mutex> guard(state_lock_);
        return status_;
    }

    uint32_t width() const {
        const uint32_t value = latest_width_.load();
        return value ? value : preferred_width_.load();
    }
    uint32_t height() const {
        const uint32_t value = latest_height_.load();
        return value ? value : preferred_height_.load();
    }

private:
    std::atomic<bool> stop_{false};
    std::thread server_;
    std::mutex state_lock_;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    ANativeWindow* requested_window_ = nullptr;
    uint32_t requested_width_ = 0;
    uint32_t requested_height_ = 0;
    bool window_change_ = false;
    bool surface_size_change_ = false;
    std::string status_ = "not-started";

    std::atomic<uint32_t> preferred_width_{1920};
    std::atomic<uint32_t> preferred_height_{1080};
    std::atomic<uint32_t> latest_width_{0};
    std::atomic<uint32_t> latest_height_{0};

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_surface_ = EGL_NO_SURFACE;
    ANativeWindow* window_ = nullptr;
    uint32_t surface_width_ = 0;
    uint32_t surface_height_ = 0;

    GetNativeClientBuffer get_native_client_buffer_ = nullptr;
    CreateImage create_image_ = nullptr;
    DestroyImage destroy_image_ = nullptr;
    ImageTargetTexture image_target_texture_ = nullptr;
    CreateSync create_sync_ = nullptr;
    DestroySync destroy_sync_ = nullptr;
    WaitSync wait_sync_ = nullptr;

    std::array<SourceSlot, FRAME_SLOTS> sources_{};
    std::vector<PendingAck> pending_;
    GLuint retained_texture_ = 0;
    GLuint retained_fbo_ = 0;
    uint32_t retained_width_ = 0;
    uint32_t retained_height_ = 0;
    bool retained_valid_ = false;

    void set_status(const std::string& value) {
        std::lock_guard<std::mutex> guard(state_lock_);
        status_ = value;
    }

    bool init_egl() {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) return fail("presenter-error:egl-initialize");
        if (!eglBindAPI(EGL_OPENGL_ES_API)) return fail("presenter-error:egl-api");
        const EGLint config_attrs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE,
        };
        EGLint count = 0;
        if (!eglChooseConfig(display_, config_attrs, &config_, 1, &count) || count != 1) return fail("presenter-error:egl-config");
        const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctx_attrs);
        if (context_ == EGL_NO_CONTEXT) return fail("presenter-error:egl-context");
        const EGLint pb_attrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        pbuffer_ = eglCreatePbufferSurface(display_, config_, pb_attrs);
        if (pbuffer_ == EGL_NO_SURFACE || !eglMakeCurrent(display_, pbuffer_, pbuffer_, context_)) return fail("presenter-error:egl-pbuffer");

        get_native_client_buffer_ = reinterpret_cast<GetNativeClientBuffer>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        create_image_ = reinterpret_cast<CreateImage>(eglGetProcAddress("eglCreateImageKHR"));
        destroy_image_ = reinterpret_cast<DestroyImage>(eglGetProcAddress("eglDestroyImageKHR"));
        image_target_texture_ = reinterpret_cast<ImageTargetTexture>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        create_sync_ = reinterpret_cast<CreateSync>(eglGetProcAddress("eglCreateSyncKHR"));
        destroy_sync_ = reinterpret_cast<DestroySync>(eglGetProcAddress("eglDestroySyncKHR"));
        wait_sync_ = reinterpret_cast<WaitSync>(eglGetProcAddress("eglWaitSyncKHR"));
        if (!get_native_client_buffer_ || !create_image_ || !destroy_image_ || !image_target_texture_ || !create_sync_ || !destroy_sync_ || !wait_sync_) {
            return fail("presenter-error:egl-native-fence-extensions");
        }
        const char* egl_ext = eglQueryString(display_, EGL_EXTENSIONS);
        if (!egl_ext || !std::strstr(egl_ext, "EGL_ANDROID_native_fence_sync") || !std::strstr(egl_ext, "EGL_KHR_wait_sync") || !std::strstr(egl_ext, "EGL_ANDROID_image_native_buffer")) {
            return fail("presenter-error:required-egl-extensions");
        }
        set_status("native-surface-socket-starting");
        return true;
    }

    bool fail(const std::string& reason) {
        set_status(reason);
        loge(reason);
        return false;
    }

    void destroy_source(SourceSlot& source) {
        if (source.read_fbo) glDeleteFramebuffers(1, &source.read_fbo);
        if (source.texture) glDeleteTextures(1, &source.texture);
        if (source.image != EGL_NO_IMAGE_KHR && destroy_image_) destroy_image_(display_, source.image);
        if (source.buffer) AHardwareBuffer_release(source.buffer);
        source = {};
    }

    void clear_sources() {
        for (auto& source : sources_) destroy_source(source);
        latest_width_ = 0;
        latest_height_ = 0;
    }

    void clear_retained() {
        if (retained_fbo_) glDeleteFramebuffers(1, &retained_fbo_);
        if (retained_texture_) glDeleteTextures(1, &retained_texture_);
        retained_fbo_ = 0;
        retained_texture_ = 0;
        retained_width_ = 0;
        retained_height_ = 0;
        retained_valid_ = false;
    }

    void finish_pending(int fd, bool force) {
        for (size_t i = 0; i < pending_.size();) {
            GLenum rc = GL_TIMEOUT_EXPIRED;
            if (force) {
                rc = glClientWaitSync(pending_[i].sync, GL_SYNC_FLUSH_COMMANDS_BIT, 2'000'000'000ULL);
            } else {
                rc = glClientWaitSync(pending_[i].sync, 0, 0);
            }
            if (rc == GL_ALREADY_SIGNALED || rc == GL_CONDITION_SATISFIED || rc == GL_WAIT_FAILED || force) {
                const bool ok = rc != GL_WAIT_FAILED;
                send_ack(fd, pending_[i].scanout, pending_[i].slot, pending_[i].serial, ok);
                glDeleteSync(pending_[i].sync);
                pending_.erase(pending_.begin() + static_cast<long>(i));
            } else {
                ++i;
            }
        }
    }

    bool register_source(uint32_t slot_index, AHardwareBuffer* incoming, uint32_t width, uint32_t height) {
        if (slot_index >= FRAME_SLOTS || !incoming) return false;
        SourceSlot& source = sources_[slot_index];
        destroy_source(source);
        source.buffer = incoming;
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(incoming, &desc);
        source.width = desc.width ? desc.width : width;
        source.height = desc.height ? desc.height : height;
        EGLClientBuffer client = get_native_client_buffer_(incoming);
        if (!client) return false;
        const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        source.image = create_image_(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
        if (source.image == EGL_NO_IMAGE_KHR) return false;
        glGenTextures(1, &source.texture);
        glBindTexture(GL_TEXTURE_2D, source.texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        image_target_texture_(GL_TEXTURE_2D, source.image);
        glGenFramebuffers(1, &source.read_fbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source.read_fbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source.texture, 0);
        const bool ok = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        latest_width_ = source.width;
        latest_height_ = source.height;
        if (!ok) return false;
        logi("registered GPU source slot=" + std::to_string(slot_index) + " size=" + std::to_string(source.width) + "x" + std::to_string(source.height));
        return true;
    }

    bool wait_producer_fence(int fence_fd) {
        if (fence_fd < 0) return false;
        const EGLint attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fence_fd, EGL_NONE};
        EGLSyncKHR sync = create_sync_(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
        if (sync == EGL_NO_SYNC_KHR) {
            close(fence_fd); // ownership transfers only on successful create
            return fail("presenter-error:producer-fence-import");
        }
        const EGLBoolean ok = wait_sync_(display_, sync, 0);
        destroy_sync_(display_, sync);
        if (ok != EGL_TRUE) return fail("presenter-error:producer-fence-gpu-wait");
        return true;
    }

    bool ensure_retained(uint32_t width, uint32_t height) {
        if (retained_texture_ && retained_width_ == width && retained_height_ == height) return true;
        clear_retained();
        glGenTextures(1, &retained_texture_);
        glBindTexture(GL_TEXTURE_2D, retained_texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(width), static_cast<GLsizei>(height), 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glGenFramebuffers(1, &retained_fbo_);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, retained_fbo_);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, retained_texture_, 0);
        const bool ok = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        retained_width_ = width;
        retained_height_ = height;
        return ok;
    }

    void blit_letterboxed(GLuint read_fbo, uint32_t src_w, uint32_t src_h) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read_fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, static_cast<GLsizei>(surface_width_), static_cast<GLsizei>(surface_height_));
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        const double sx = static_cast<double>(surface_width_) / std::max(1u, src_w);
        const double sy = static_cast<double>(surface_height_) / std::max(1u, src_h);
        const double scale = std::min(sx, sy);
        const int dw = std::max(1, static_cast<int>(src_w * scale));
        const int dh = std::max(1, static_cast<int>(src_h * scale));
        const int dx = (static_cast<int>(surface_width_) - dw) / 2;
        const int dy = (static_cast<int>(surface_height_) - dh) / 2;
        glBlitFramebuffer(0, 0, static_cast<GLint>(src_w), static_cast<GLint>(src_h), dx, dy, dx + dw, dy + dh, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    }

    bool copy_to_retained(const SourceSlot& source) {
        if (!ensure_retained(source.width, source.height)) return fail("presenter-error:retained-fbo");
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source.read_fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, retained_fbo_);
        glBlitFramebuffer(0, 0, static_cast<GLint>(source.width), static_cast<GLint>(source.height), 0, 0, static_cast<GLint>(source.width), static_cast<GLint>(source.height), GL_COLOR_BUFFER_BIT, GL_NEAREST);
        retained_valid_ = glGetError() == GL_NO_ERROR;
        return retained_valid_;
    }

    bool present_retained() {
        if (!retained_valid_ || window_surface_ == EGL_NO_SURFACE) return true;
        blit_letterboxed(retained_fbo_, retained_width_, retained_height_);
        if (glGetError() != GL_NO_ERROR || eglSwapBuffers(display_, window_surface_) != EGL_TRUE) return fail("presenter-error:surface-swap-retained");
        set_status("presenting-native-surface");
        return true;
    }

    bool render_frame(int fd, const AhbMessage& msg, int fence_fd) {
        if (msg.slot >= FRAME_SLOTS || !sources_[msg.slot].buffer) {
            if (fence_fd >= 0) close(fence_fd);
            send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return false;
        }
        if (!wait_producer_fence(fence_fd)) {
            send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return false;
        }
        SourceSlot& source = sources_[msg.slot];
        bool ok = true;
        if (window_surface_ != EGL_NO_SURFACE) {
            blit_letterboxed(source.read_fbo, source.width, source.height);
            ok = glGetError() == GL_NO_ERROR;
        } else {
            ok = copy_to_retained(source);
        }
        if (!ok) {
            send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return fail("presenter-error:surface-gpu-blit");
        }
        GLsync consumed = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (!consumed) {
            send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return fail("presenter-error:consumer-fence");
        }
        glFlush();
        pending_.push_back(PendingAck{consumed, msg.scanout_id, msg.slot, msg.serial});
        if (window_surface_ != EGL_NO_SURFACE) {
            if (eglSwapBuffers(display_, window_surface_) != EGL_TRUE) return fail("presenter-error:surface-swap");
            set_status("presenting-native-surface");
        } else {
            set_status("frame-ready-waiting-for-surface");
        }
        return true;
    }

    void apply_window_change() {
        ANativeWindow* next = nullptr;
        uint32_t next_w = 0, next_h = 0;
        bool changed = false;
        bool size_changed = false;
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            changed = window_change_;
            size_changed = surface_size_change_;
            if (changed) {
                next = requested_window_;
                requested_window_ = nullptr;
                next_w = requested_width_;
                next_h = requested_height_;
                window_change_ = false;
            } else if (size_changed) {
                next_w = requested_width_;
                next_h = requested_height_;
            }
            surface_size_change_ = false;
        }
        if (!changed && !size_changed) return;
        if (changed) {
            if (window_surface_ != EGL_NO_SURFACE) {
                eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
                eglDestroySurface(display_, window_surface_);
                window_surface_ = EGL_NO_SURFACE;
            }
            if (window_) ANativeWindow_release(window_);
            window_ = next;
            if (!window_) {
                surface_width_ = surface_height_ = 0;
                set_status(latest_width_.load() ? "frame-ready-waiting-for-surface" : "surface-detached");
                return;
            }
            surface_width_ = next_w ? next_w : static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
            surface_height_ = next_h ? next_h : static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
            window_surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
            if (window_surface_ == EGL_NO_SURFACE || !eglMakeCurrent(display_, window_surface_, window_surface_, context_)) {
                fail("presenter-error:egl-window-surface");
                return;
            }
            eglSwapInterval(display_, 1);
            set_status("surface-attached");
            present_retained();
            logi("Android native Surface attached " + std::to_string(surface_width_) + "x" + std::to_string(surface_height_) + " swapInterval=1");
        } else if (window_surface_ != EGL_NO_SURFACE) {
            surface_width_ = next_w;
            surface_height_ = next_h;
            present_retained();
        }
    }

    bool handle_message(int fd, const AhbMessage& msg) {
        if (msg.magic != MAGIC || msg.version != VERSION || msg.scanout_id != 0) {
            send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return false;
        }
        if (msg.type == MSG_REGISTER_FRAME) {
            if (msg.slot >= FRAME_SLOTS) return false;
            AHardwareBuffer* incoming = nullptr;
            if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &incoming) != 0 || !incoming) return false;
            const int fence_fd = recv_fence_fd(fd);
            if (fence_fd < 0) { AHardwareBuffer_release(incoming); return false; }
            if (!register_source(msg.slot, incoming, msg.width, msg.height)) {
                AHardwareBuffer_release(incoming);
                close(fence_fd);
                send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
                return false;
            }
            return render_frame(fd, msg, fence_fd);
        }
        if (msg.type == MSG_FRAME) {
            const int fence_fd = recv_fence_fd(fd);
            if (fence_fd < 0) return false;
            return render_frame(fd, msg, fence_fd);
        }
        if (msg.type == MSG_DISABLE) {
            finish_pending(fd, true);
            clear_sources();
            clear_retained();
            set_status(window_surface_ != EGL_NO_SURFACE ? "surface-attached" : "surface-detached");
            return send_ack(fd, msg.scanout_id, UINT32_MAX, msg.serial, true);
        }
        return false;
    }

    void handle_client(int fd) {
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            client_fd_ = fd;
        }
        while (!stop_) {
            apply_window_change();
            finish_pending(fd, false);
            pollfd pfd{fd, POLLIN, 0};
            const int rc = poll(&pfd, 1, 2);
            if (rc < 0 && errno == EINTR) continue;
            if (rc < 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (rc == 0) continue;
            AhbMessage msg{};
            if (!recv_all(fd, &msg, sizeof(msg)) || !handle_message(fd, msg)) break;
        }
        finish_pending(fd, true);
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            if (client_fd_ == fd) client_fd_ = -1;
        }
    }

    void destroy_egl() {
        if (display_ == EGL_NO_DISPLAY) return;
        if (context_ != EGL_NO_CONTEXT) eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        for (auto& pending : pending_) if (pending.sync) glDeleteSync(pending.sync);
        pending_.clear();
        clear_sources();
        clear_retained();
        if (window_surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, window_surface_);
        if (window_) ANativeWindow_release(window_);
        window_ = nullptr;
        window_surface_ = EGL_NO_SURFACE;
        if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
        pbuffer_ = EGL_NO_SURFACE;
        context_ = EGL_NO_CONTEXT;
    }

    void server_loop() {
        if (!init_egl()) return;
        const std::string name = "vessel-ahb-" + std::to_string(getuid());
        const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (server < 0) { fail("presenter-error:surface-socket-create"); destroy_egl(); return; }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) { close(server); fail("presenter-error:surface-socket-name"); destroy_egl(); return; }
        addr.sun_path[0] = '\0';
        std::memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (bind(server, reinterpret_cast<sockaddr*>(&addr), len) != 0 || listen(server, 2) != 0) {
            close(server); fail("presenter-error:surface-bind"); destroy_egl(); return;
        }
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            listen_fd_ = server;
            status_ = "native-surface-socket-ready";
        }
        logi("DroidVM-style Android Surface presentation ready: AHB handoff -> EGL/GLES -> BufferQueue/SurfaceFlinger");
        while (!stop_) {
            apply_window_change();
            pollfd pfd{server, POLLIN, 0};
            const int prc = poll(&pfd, 1, 10);
            if (prc < 0 && errno == EINTR) continue;
            if (prc < 0 || stop_) break;
            if (prc == 0) continue;
            const int client = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
            if (client < 0) continue;
            handle_client(client);
            close(client);
        }
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            listen_fd_ = -1;
            client_fd_ = -1;
        }
        close(server);
        destroy_egl();
    }
};

NativeSurfacePresenter g_surface;
} // namespace

extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbConfigure(JNIEnv*, jclass, jint width, jint height) { g_surface.configure(static_cast<uint32_t>(width), static_cast<uint32_t>(height)); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStart(JNIEnv*, jclass) { g_surface.start(); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStop(JNIEnv*, jclass) { g_surface.stop(); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbAttachSurface(JNIEnv* env, jclass, jobject surface) { g_surface.attach(env, surface); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbSurfaceChanged(JNIEnv*, jclass, jint width, jint height) { g_surface.surface_changed(static_cast<uint32_t>(width), static_cast<uint32_t>(height)); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbDetachSurface(JNIEnv*, jclass) { g_surface.detach(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStatus(JNIEnv* env, jclass) { const std::string status = g_surface.status(); return env->NewStringUTF(status.c_str()); }
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbGuestWidth(JNIEnv*, jclass) { return static_cast<jint>(g_surface.width()); }
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbGuestHeight(JNIEnv*, jclass) { return static_cast<jint>(g_surface.height()); }
'''
write("app/src/main/cpp/vessel_surface_presenter.cpp", surface_cpp)

replace_once(
    "app/src/main/cpp/CMakeLists.txt",
    "    vessel_ahb_presenter.cpp\n",
    "    vessel_surface_presenter.cpp\n",
)
replace_once(
    "app/src/main/cpp/CMakeLists.txt",
    "target_compile_definitions(vessel_wayland_presenter PRIVATE VK_USE_PLATFORM_ANDROID_KHR)\n",
    "",
)
replace_once(
    "app/src/main/cpp/CMakeLists.txt",
    "target_link_libraries(vessel_wayland_presenter PRIVATE android log vulkan)\n",
    "target_link_libraries(vessel_wayland_presenter PRIVATE android log EGL GLESv3)\n",
)

# Surface lifetime follows DroidVM's invariant: deliver once on surfaceCreated;
# a layout-driven surfaceChanged only updates geometry and never re-attaches the
# same BufferQueue producer.
replace_once(
    "app/src/main/java/com/example/dreamlinux/NativeLinuxSurfaceView.kt",
    "    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {\n        VesselWaylandPresenter.attach(holder.surface)\n    }\n",
    "    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {\n        VesselWaylandPresenter.surfaceChanged(width, height)\n    }\n",
)

presenter = "app/src/main/java/com/example/dreamlinux/VesselWaylandPresenter.kt"
text = read(presenter)
text = text.replace(
    " * Same-UID graphics frontend. Standard vhost-user-gpu remains responsible for\n * EDID/cursor control; scanout pixels stay on the GPU and are shared through\n * Android HardwareBuffer into the Vulkan presenter.\n",
    " * Same-UID graphics frontend. Standard vhost-user-gpu owns EDID/cursor control.\n * AHardwareBuffer is only the cross-process GPU handoff; the final frame is drawn\n * straight into the Android Surface BufferQueue with EGL/GLES and SurfaceFlinger.\n",
)
text = text.replace('ahb == "presenting-ahardwarebuffer" -> "presenting-ahardwarebuffer"', 'ahb == "presenting-native-surface" -> "presenting-native-surface"')
text = text.replace('if (s.startsWith("presenting-ahardwarebuffer")) everPresented = true', 'if (s.startsWith("presenting-native-surface")) everPresented = true')
write(presenter, text)

# ---------------------------------------------------------------------------
# 2. Product/version/runtime wording.
# ---------------------------------------------------------------------------
replace_once("app/build.gradle.kts", 'versionName = "2.1.0-alpha2"', 'versionName = "2.1.0-alpha3"')

rt = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
text = read(rt)
text = text.replace("const val PROTOCOL = 39", "const val PROTOCOL = 40")
text = text.replace('const val REVISION = "v39-self-contained-ahb-syncfd-virtio-input-r7"', 'const val REVISION = "v40-native-surface-egl-virtio-input-r1"')
text = text.replace('const val DISPLAY_TRANSPORT = "vhost-user-gpu-ahardwarebuffer-syncfd-v2"', 'const val DISPLAY_TRANSPORT = "vhost-user-gpu-ahb-native-surface-v3"')
text = text.replace('status.startsWith("presenting-ahardwarebuffer") || status.startsWith("presenting-retained") || status.startsWith("presenting-dmabuf")', 'status.startsWith("presenting-native-surface") || status.startsWith("presenting-retained")')
text = text.replace('progress("frame", 88, "Waiting for synchronized AHardwareBuffer scanout")', 'progress("frame", 88, "Waiting for Android native Surface frame")')
text = text.replace('progress("ready", 100, "Plasma visible through synchronized AHardwareBuffer")', 'progress("ready", 100, "Plasma visible through Android native Surface")')
text = text.replace('lastError = "Desktop is running, but no synchronized AHardwareBuffer frame has arrived yet; presenter=$ps"', 'lastError = "Desktop is running, but no Android native Surface frame has arrived yet; presenter=$ps"')
write(rt, text)

svc = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
text = read(svc)
text = text.replace('val graphics: String = "VirtIO GPU · VirGL · ANGLE · AHardwareBuffer · Vulkan · Adreno"', 'val graphics: String = "VirtIO GPU · VirGL · ANGLE · Android Surface · Adreno"')
text = text.replace('val displayTransport: String = "vhost-user-gpu-ahardwarebuffer-syncfd-v2"', 'val displayTransport: String = "vhost-user-gpu-ahb-native-surface-v3"')
text = text.replace('val runtimeRevision: String = "v39-self-contained-ahb-syncfd-virtio-input-r7"', 'val runtimeRevision: String = "v40-native-surface-egl-virtio-input-r1"')
text = text.replace('runtimeRevision = "v39-self-contained-ahb-syncfd-virtio-input-r7"', 'runtimeRevision = "v40-native-surface-egl-virtio-input-r1"')
text = text.replace('displayTransport = "vhost-user-gpu-ahardwarebuffer-syncfd-v2"', 'displayTransport = "vhost-user-gpu-ahb-native-surface-v3"')
text = text.replace('val presented = presenter.startsWith("presenting-dmabuf") || presenter.startsWith("presenting-ahardwarebuffer") || presenter.startsWith("presenting-retained")', 'val presented = presenter.startsWith("presenting-native-surface") || presenter.startsWith("presenting-retained")')
text = text.replace('graphics = "KDE Plasma/Xorg → Mesa VirGL → vhost-device-gpu → virglrenderer → ANGLE/Vulkan → Adreno"', 'graphics = "KDE Plasma/Xorg → Mesa VirGL → virglrenderer → ANGLE → Android Surface → Adreno"')
text = text.replace('presented -> "Plasma visible · synchronized AHardwareBuffer GPU path"', 'presented -> "Plasma visible · Android native Surface GPU path"')
write(svc, text)

activity = "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
text = read(activity)
text = text.replace('Rootless ARM64 Linux · VirtIO GPU · AHardwareBuffer · Adreno', 'Rootless ARM64 Linux · VirtIO GPU · Native Surface · Adreno')
write(activity, text)

# ---------------------------------------------------------------------------
# 3. Complete the Plasma runtime modules proven missing in the physical log.
# ---------------------------------------------------------------------------
svc_text = read(svc)
svc_text = svc_text.replace(
    '"qml-module-qtquick-window2", "qml-module-qtquick2", "qml-module-qtquick-templates2", "qml-module-qtgraphicaleffects",\n            "plasma-integration"',
    '"qml-module-qtquick-window2", "qml-module-qtquick2", "qml-module-qtquick-templates2", "qml-module-qtgraphicaleffects", "qml-module-qt-labs-platform",\n            "plasma-integration", "plasma-pa", "kactivitymanagerd"',
)
write(svc, svc_text)

rt_text = read(rt)
rt_text = rt_text.replace(
    'qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data; do ',
    'qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects qml-module-qt-labs-platform plasma-integration plasma-pa kactivitymanagerd libkf5service-data; do ',
)
rt_text = rt_text.replace(
    'qml-module-qtquick-controls qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data " +',
    'qml-module-qtquick-controls qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects qml-module-qt-labs-platform plasma-integration plasma-pa kactivitymanagerd libkf5service-data " +',
)
write(rt, rt_text)

# Add explicit profile checks so a successful marker actually means the QML
# modules that failed on-device exist.
svc_text = read(svc)
needle = '            test -f "${\'$\'}qml/org/kde/kirigami.2/qmldir"\n'
insert = needle + '            test -f "${\'$\'}qml/Qt/labs/platform/qmldir"\n            test -f "${\'$\'}qml/org/kde/plasma/private/volume/qmldir"\n'
if needle not in svc_text:
    raise SystemExit("VmSessionService profile QML anchor missing")
svc_text = svc_text.replace(needle, insert, 1)
write(svc, svc_text)

# ---------------------------------------------------------------------------
# 4. App Store is a guest package/catalog service, not a Plasma-profile service.
#    It may load whenever Debian itself is ready and no guest command owns the
#    serialized console. This also lets users recover/install packages if a
#    non-fatal desktop profile check ever fails again.
# ---------------------------------------------------------------------------
activity_text = read(activity)
activity_text = activity_text.replace(
    'if (state.guestReady && !state.busy && state.stage == "ready" && store.apps.isEmpty() && !store.loading) {',
    'if (state.guestReady && !state.busy && store.apps.isEmpty() && !store.loading) {',
)
write(activity, activity_text)

svc_text = read(svc)
svc_text = svc_text.replace(
    '        if (state.value.busy || state.value.stage != "ready") {\n            appStore.value = appStore.value.copy(loading = false, error = "Finish workstation setup before browsing apps")\n            return\n        }',
    '        if (state.value.busy) {\n            appStore.value = appStore.value.copy(loading = false, error = "Wait for the current Debian operation to finish")\n            return\n        }',
)
write(svc, svc_text)

# ---------------------------------------------------------------------------
# 5. Avoid the partially clipped fourth control on narrow portrait devices.
#    Keep the exact same functions but use a compact two-row layout.
# ---------------------------------------------------------------------------
activity_text = read(activity)
old_controls = '''        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = mode == LinuxDesktopView.PointerMode.DIRECT, onClick = { setMode(LinuxDesktopView.PointerMode.DIRECT) }, label = { Text("Touch") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) })
            FilterChip(selected = mode == LinuxDesktopView.PointerMode.TRACKPAD, onClick = { setMode(LinuxDesktopView.PointerMode.TRACKPAD) }, label = { Text("Trackpad") }, leadingIcon = { Icon(Icons.Default.Mouse, null) })
            AssistChip(onClick = { LinuxDesktopView.active?.showKeyboard() }, label = { Text("Keyboard") }, leadingIcon = { Icon(Icons.Default.Keyboard, null) })
            AssistChip(onClick = fullscreen, label = { Text("Fullscreen") }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) })
        }
'''
new_controls = '''        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(modifier = Modifier.weight(1f), selected = mode == LinuxDesktopView.PointerMode.DIRECT, onClick = { setMode(LinuxDesktopView.PointerMode.DIRECT) }, label = { Text("Touch") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) })
                FilterChip(modifier = Modifier.weight(1f), selected = mode == LinuxDesktopView.PointerMode.TRACKPAD, onClick = { setMode(LinuxDesktopView.PointerMode.TRACKPAD) }, label = { Text("Trackpad") }, leadingIcon = { Icon(Icons.Default.Mouse, null) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AssistChip(modifier = Modifier.weight(1f), onClick = { LinuxDesktopView.active?.showKeyboard() }, label = { Text("Keyboard") }, leadingIcon = { Icon(Icons.Default.Keyboard, null) })
                AssistChip(modifier = Modifier.weight(1f), onClick = fullscreen, label = { Text("Fullscreen") }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) })
            }
        }
'''
if old_controls not in activity_text:
    raise SystemExit("DesktopControls anchor missing")
activity_text = activity_text.replace(old_controls, new_controls, 1)
write(activity, activity_text)

# ---------------------------------------------------------------------------
# 6. Runtime manifest accurately describes the new final presentation stage.
# ---------------------------------------------------------------------------
rebuild = "tools/vessel_native/rebuild_vhost_gpu_ahb.sh"
rebuild_text = read(rebuild)
rebuild_text = rebuild_text.replace('echo protocol=39', 'echo protocol=40')
rebuild_text = rebuild_text.replace('echo runtime=v39-self-contained-ahb-syncfd-virtio-input-r7', 'echo runtime=v40-native-surface-egl-virtio-input-r1')
rebuild_text = rebuild_text.replace('echo display_bridge=android-hardware-buffer-syncfd-v2', 'echo display_bridge=ahb-cross-process-native-surface-egl-v3')
write(rebuild, rebuild_text)

print("Vessel 2.1.0-alpha3 native Surface + Plasma/AppStore migration applied")
