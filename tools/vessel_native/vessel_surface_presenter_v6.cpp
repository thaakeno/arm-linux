#include <jni.h>
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
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <poll.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
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
constexpr uint32_t FRAME_SLOTS = 5;
constexpr size_t MAX_QUEUED_FRAMES = 2;
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
using DupNativeFence = PFNEGLDUPNATIVEFENCEFDANDROIDPROC;

struct SourceSlot {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    GLuint read_fbo = 0;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct FrameJob {
    AhbMessage msg{};
    int producer_fence_fd = -1;
    int ack_fd = -1;
    AHardwareBuffer* incoming = nullptr;
};

struct PendingAck {
    int fence_fd = -1;
    int ack_fd = -1;
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
        epoll_fd_ = epoll_create1(EPOLL_CLOEXEC);
        wake_fd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        if (epoll_fd_ < 0 || wake_fd_ < 0) {
            status_ = "presenter-error:epoll-create";
            if (epoll_fd_ >= 0) close(epoll_fd_);
            if (wake_fd_ >= 0) close(wake_fd_);
            epoll_fd_ = wake_fd_ = -1;
            return;
        }
        epoll_event ev{};
        ev.events = EPOLLIN;
        ev.data.fd = wake_fd_;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, wake_fd_, &ev) != 0) {
            status_ = "presenter-error:epoll-wake";
            close(epoll_fd_); close(wake_fd_);
            epoll_fd_ = wake_fd_ = -1;
            return;
        }
        reaper_ = std::thread([this] { reaper_loop(); });
        renderer_ = std::thread([this] { renderer_loop(); });
        server_ = std::thread([this] { server_loop(); });
    }

    void stop() {
        stop_ = true;
        queue_cv_.notify_all();
        wake_reaper();
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
        if (renderer_.joinable()) renderer_.join();
        if (reaper_.joinable()) reaper_.join();
        if (epoll_fd_ >= 0) close(epoll_fd_);
        if (wake_fd_ >= 0) close(wake_fd_);
        epoll_fd_ = wake_fd_ = -1;
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
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            if (requested_window_) ANativeWindow_release(requested_window_);
            requested_window_ = next;
            requested_width_ = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(next)));
            requested_height_ = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(next)));
            window_change_ = true;
            status_ = "surface-attached-pending";
        }
        queue_cv_.notify_all();
    }

    void surface_changed(uint32_t width, uint32_t height) {
        if (!width || !height) return;
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            requested_width_ = width;
            requested_height_ = height;
            surface_size_change_ = true;
        }
        queue_cv_.notify_all();
    }

    void detach() {
        {
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
        queue_cv_.notify_all();
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
    std::atomic<bool> renderer_ready_{false};
    std::thread server_;
    std::thread renderer_;
    std::thread reaper_;
    std::mutex state_lock_;
    std::mutex send_lock_;
    std::mutex queue_lock_;
    std::mutex pending_lock_;
    std::condition_variable queue_cv_;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    int epoll_fd_ = -1;
    int wake_fd_ = -1;
    ANativeWindow* requested_window_ = nullptr;
    uint32_t requested_width_ = 0;
    uint32_t requested_height_ = 0;
    bool window_change_ = false;
    bool surface_size_change_ = false;
    std::string status_ = "not-started";
    std::deque<FrameJob> queue_;
    std::unordered_map<int, PendingAck> pending_acks_;
    std::atomic<uint64_t> dropped_{0};

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
    DupNativeFence dup_native_fence_ = nullptr;

    std::array<SourceSlot, FRAME_SLOTS> sources_{};
    GLuint retained_texture_ = 0;
    GLuint retained_fbo_ = 0;
    uint32_t retained_width_ = 0;
    uint32_t retained_height_ = 0;
    bool retained_valid_ = false;

    void set_status(const std::string& value) {
        std::lock_guard<std::mutex> guard(state_lock_);
        status_ = value;
    }

    bool send_ack_safe(int fd, uint32_t scanout, uint32_t slot, uint32_t serial, bool ok) {
        if (fd < 0 || serial == 0) return true;
        const AckMessage ack{MAGIC, VERSION, scanout, slot, serial, ok ? 1u : 0u};
        std::lock_guard<std::mutex> guard(send_lock_);
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

    void dispose_job(FrameJob& job, bool ack) {
        if (job.producer_fence_fd >= 0) close(job.producer_fence_fd);
        if (job.incoming) AHardwareBuffer_release(job.incoming);
        if (job.ack_fd >= 0) {
            if (ack) send_ack_safe(job.ack_fd, job.msg.scanout_id, job.msg.slot, job.msg.serial, true);
            close(job.ack_fd);
        }
        job = {};
    }

    void wake_reaper() {
        if (wake_fd_ < 0) return;
        uint64_t one = 1;
        (void)write(wake_fd_, &one, sizeof(one));
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
        dup_native_fence_ = reinterpret_cast<DupNativeFence>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
        if (!get_native_client_buffer_ || !create_image_ || !destroy_image_ || !image_target_texture_ || !create_sync_ || !destroy_sync_ || !wait_sync_ || !dup_native_fence_) {
            return fail("presenter-error:egl-native-fence-extensions");
        }
        const char* egl_ext = eglQueryString(display_, EGL_EXTENSIONS);
        if (!egl_ext || !std::strstr(egl_ext, "EGL_ANDROID_native_fence_sync") || !std::strstr(egl_ext, "EGL_KHR_wait_sync") || !std::strstr(egl_ext, "EGL_ANDROID_image_native_buffer")) {
            return fail("presenter-error:required-egl-extensions");
        }
        const char* vendor = eglQueryString(display_, EGL_VENDOR);
        logi(std::string("renderer thread EGL ready vendor=") + (vendor ? vendor : "unknown") + " slots=5 asyncAck=epoll swapInterval=0");
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
            close(fence_fd);
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
        set_status("presenting-native-surface-v6");
        return true;
    }

    bool enqueue_async_ack(FrameJob& job) {
        const EGLint attrs[] = {EGL_NONE};
        EGLSyncKHR sync = create_sync_(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
        if (sync == EGL_NO_SYNC_KHR) return false;
        glFlush();
        const int fence_fd = dup_native_fence_(display_, sync);
        destroy_sync_(display_, sync);
        if (fence_fd < 0) return false;

        epoll_event ev{};
        ev.events = EPOLLIN | EPOLLERR | EPOLLHUP;
        ev.data.fd = fence_fd;
        {
            std::lock_guard<std::mutex> guard(pending_lock_);
            pending_acks_.emplace(fence_fd, PendingAck{fence_fd, job.ack_fd, job.msg.scanout_id, job.msg.slot, job.msg.serial});
        }
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fence_fd, &ev) != 0) {
            std::lock_guard<std::mutex> guard(pending_lock_);
            pending_acks_.erase(fence_fd);
            close(fence_fd);
            return false;
        }
        job.ack_fd = -1;
        wake_reaper();
        return true;
    }

    void immediate_ack(FrameJob& job, bool ok) {
        if (job.ack_fd >= 0) {
            send_ack_safe(job.ack_fd, job.msg.scanout_id, job.msg.slot, job.msg.serial, ok);
            close(job.ack_fd);
            job.ack_fd = -1;
        }
    }

    void process_job(FrameJob job) {
        if (job.msg.type == MSG_DISABLE) {
            clear_sources();
            clear_retained();
            immediate_ack(job, true);
            return;
        }

        if (job.msg.slot >= FRAME_SLOTS) {
            immediate_ack(job, false);
            dispose_job(job, false);
            return;
        }
        if (job.incoming) {
            AHardwareBuffer* incoming = job.incoming;
            job.incoming = nullptr;
            if (!register_source(job.msg.slot, incoming, job.msg.width, job.msg.height)) {
                AHardwareBuffer_release(incoming);
                if (job.producer_fence_fd >= 0) close(job.producer_fence_fd);
                job.producer_fence_fd = -1;
                immediate_ack(job, false);
                return;
            }
        }
        if (!sources_[job.msg.slot].buffer) {
            if (job.producer_fence_fd >= 0) close(job.producer_fence_fd);
            job.producer_fence_fd = -1;
            immediate_ack(job, false);
            return;
        }
        const int producer_fd = job.producer_fence_fd;
        job.producer_fence_fd = -1;
        if (!wait_producer_fence(producer_fd)) {
            immediate_ack(job, false);
            return;
        }

        SourceSlot& source = sources_[job.msg.slot];
        bool ok = true;
        if (window_surface_ != EGL_NO_SURFACE) {
            blit_letterboxed(source.read_fbo, source.width, source.height);
            ok = glGetError() == GL_NO_ERROR;
        } else {
            ok = copy_to_retained(source);
        }
        if (!ok) {
            immediate_ack(job, false);
            fail("presenter-error:surface-gpu-blit");
            return;
        }

        if (!enqueue_async_ack(job)) {
            // Error-only fallback: wait on this renderer thread, never on the socket/vhost thread.
            glFinish();
            immediate_ack(job, true);
        }

        if (window_surface_ != EGL_NO_SURFACE) {
            if (eglSwapBuffers(display_, window_surface_) != EGL_TRUE) {
                fail("presenter-error:surface-swap");
                return;
            }
            set_status("presenting-native-surface-v6");
        } else {
            set_status("frame-ready-waiting-for-surface");
        }
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
            // Let SurfaceFlinger pace independently. The renderer/socket path must never wait for VSYNC.
            eglSwapInterval(display_, 0);
            set_status("surface-attached-v6");
            present_retained();
            logi("Android native Surface attached " + std::to_string(surface_width_) + "x" + std::to_string(surface_height_) + " swapInterval=0 asyncAck=1");
        } else if (window_surface_ != EGL_NO_SURFACE) {
            surface_width_ = next_w;
            surface_height_ = next_h;
            present_retained();
        }
    }

    bool queue_job(FrameJob job) {
        std::unique_lock<std::mutex> lock(queue_lock_);
        if (job.msg.type == MSG_DISABLE) {
            while (!queue_.empty()) {
                FrameJob old = std::move(queue_.front());
                queue_.pop_front();
                lock.unlock();
                dispose_job(old, true);
                lock.lock();
            }
        }
        if (queue_.size() >= MAX_QUEUED_FRAMES) {
            if (job.msg.type == MSG_REGISTER_FRAME) {
                auto it = std::find_if(queue_.begin(), queue_.end(), [](const FrameJob& q) { return q.msg.type == MSG_FRAME; });
                if (it != queue_.end()) {
                    FrameJob old = std::move(*it);
                    queue_.erase(it);
                    lock.unlock();
                    dispose_job(old, true);
                    dropped_.fetch_add(1);
                    lock.lock();
                }
            }
            if (queue_.size() >= MAX_QUEUED_FRAMES) {
                lock.unlock();
                dispose_job(job, job.msg.type == MSG_FRAME);
                if (job.msg.type == MSG_FRAME) dropped_.fetch_add(1);
                return job.msg.type == MSG_FRAME;
            }
        }
        queue_.push_back(std::move(job));
        lock.unlock();
        queue_cv_.notify_one();
        return true;
    }

    bool handle_message(int fd, const AhbMessage& msg) {
        if (msg.magic != MAGIC || msg.version != VERSION || msg.scanout_id != 0) {
            send_ack_safe(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return false;
        }
        FrameJob job{};
        job.msg = msg;
        job.ack_fd = dup(fd);
        if (job.ack_fd < 0) return false;
        if (msg.type == MSG_REGISTER_FRAME) {
            if (msg.slot >= FRAME_SLOTS) { dispose_job(job, false); return false; }
            if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &job.incoming) != 0 || !job.incoming) { dispose_job(job, false); return false; }
            job.producer_fence_fd = recv_fence_fd(fd);
            if (job.producer_fence_fd < 0) { dispose_job(job, false); return false; }
            return queue_job(std::move(job));
        }
        if (msg.type == MSG_FRAME) {
            job.producer_fence_fd = recv_fence_fd(fd);
            if (job.producer_fence_fd < 0) { dispose_job(job, false); return false; }
            return queue_job(std::move(job));
        }
        if (msg.type == MSG_DISABLE) return queue_job(std::move(job));
        dispose_job(job, false);
        return false;
    }

    void handle_client(int fd) {
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            client_fd_ = fd;
        }
        while (!stop_) {
            pollfd pfd{fd, POLLIN, 0};
            const int rc = poll(&pfd, 1, 50);
            if (rc < 0 && errno == EINTR) continue;
            if (rc < 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (rc == 0) continue;
            AhbMessage msg{};
            if (!recv_all(fd, &msg, sizeof(msg)) || !handle_message(fd, msg)) break;
        }
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            if (client_fd_ == fd) client_fd_ = -1;
        }
    }

    void reaper_loop() {
        std::array<epoll_event, 16> events{};
        while (!stop_) {
            const int count = epoll_wait(epoll_fd_, events.data(), static_cast<int>(events.size()), 250);
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) break;
            for (int i = 0; i < count; ++i) {
                const int fd = events[static_cast<size_t>(i)].data.fd;
                if (fd == wake_fd_) {
                    uint64_t value;
                    while (read(wake_fd_, &value, sizeof(value)) > 0) {}
                    continue;
                }
                PendingAck ack{};
                bool found = false;
                {
                    std::lock_guard<std::mutex> guard(pending_lock_);
                    auto it = pending_acks_.find(fd);
                    if (it != pending_acks_.end()) {
                        ack = it->second;
                        pending_acks_.erase(it);
                        found = true;
                    }
                }
                if (!found) continue;
                epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, fd, nullptr);
                send_ack_safe(ack.ack_fd, ack.scanout, ack.slot, ack.serial, true);
                close(ack.ack_fd);
                close(ack.fence_fd);
            }
        }
        std::vector<PendingAck> leftovers;
        {
            std::lock_guard<std::mutex> guard(pending_lock_);
            for (auto& [_, ack] : pending_acks_) leftovers.push_back(ack);
            pending_acks_.clear();
        }
        for (auto& ack : leftovers) {
            epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, ack.fence_fd, nullptr);
            close(ack.fence_fd);
            send_ack_safe(ack.ack_fd, ack.scanout, ack.slot, ack.serial, false);
            close(ack.ack_fd);
        }
    }

    void destroy_egl() {
        if (display_ == EGL_NO_DISPLAY) return;
        if (context_ != EGL_NO_CONTEXT) eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
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

    void renderer_loop() {
        if (!init_egl()) return;
        renderer_ready_ = true;
        set_status("native-surface-renderer-v6-ready");
        while (!stop_) {
            apply_window_change();
            FrameJob job{};
            bool have_job = false;
            {
                std::unique_lock<std::mutex> lock(queue_lock_);
                if (queue_.empty()) queue_cv_.wait_for(lock, std::chrono::milliseconds(2));
                if (!queue_.empty()) {
                    job = std::move(queue_.front());
                    queue_.pop_front();
                    have_job = true;
                }
            }
            if (have_job) process_job(std::move(job));
        }
        std::deque<FrameJob> leftovers;
        {
            std::lock_guard<std::mutex> guard(queue_lock_);
            leftovers.swap(queue_);
        }
        for (auto& job : leftovers) dispose_job(job, false);
        destroy_egl();
        renderer_ready_ = false;
    }

    void server_loop() {
        for (int i = 0; i < 400 && !stop_ && !renderer_ready_.load(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        if (!renderer_ready_.load()) { fail("presenter-error:renderer-not-ready"); return; }
        const std::string name = "vessel-ahb-" + std::to_string(getuid());
        const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (server < 0) { fail("presenter-error:surface-socket-create"); return; }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) { close(server); fail("presenter-error:surface-socket-name"); return; }
        addr.sun_path[0] = '\0';
        std::memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (bind(server, reinterpret_cast<sockaddr*>(&addr), len) != 0 || listen(server, 2) != 0) {
            close(server); fail("presenter-error:surface-bind"); return;
        }
        {
            std::lock_guard<std::mutex> guard(state_lock_);
            listen_fd_ = server;
            status_ = "native-surface-socket-ready-v6";
        }
        logi("Vessel v6 presenter ready: socket ingestion decoupled from SurfaceFlinger, slots=5, bounded queue=5, epoll fence retirement, drop-on-backpressure");
        while (!stop_) {
            pollfd pfd{server, POLLIN, 0};
            const int prc = poll(&pfd, 1, 50);
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
        logi("presenter socket stopped dropped=" + std::to_string(dropped_.load()));
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
