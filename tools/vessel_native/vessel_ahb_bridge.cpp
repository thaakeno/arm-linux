#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
extern "C" {
#include <virgl/virglrenderer.h>
}

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VesselAhbBridge";
constexpr uint32_t MAGIC = 0x42484156u;
constexpr uint32_t VERSION = 3;
constexpr uint32_t MSG_REGISTER_FRAME = 1;
constexpr uint32_t MSG_FRAME = 2;
constexpr uint32_t MSG_DISABLE = 3;
constexpr size_t MAX_SCANOUTS = 16;
constexpr size_t FRAME_SLOTS = 3;
constexpr uint8_t FENCE_TAG = 0xF3;

constexpr int RC_EXTENSIONS = 101;
constexpr int RC_CONTEXT = 102;
constexpr int RC_AHB_ALLOC = 103;
constexpr int RC_AHB_CLIENT = 104;
constexpr int RC_EGL_IMAGE = 105;
constexpr int RC_AHB_TEXTURE = 106;
constexpr int RC_DST_FBO = 107;
constexpr int RC_RESOURCE_INFO = 108;
constexpr int RC_SRC_FBO = 109;
constexpr int RC_GPU_BLIT = 110;
constexpr int RC_SOCKET = 111;
constexpr int RC_RENDER_SYNC = 112;
constexpr int RC_NATIVE_FENCE = 113;

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

struct DamageRect {
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool valid = false;
};

struct BufferSlot {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    GLuint draw_fbo = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool registered = false;
    bool busy = false;
    bool content_valid = false;
    uint32_t serial = 0;
    DamageRect pending_damage{};
};

struct ScanoutState {
    std::array<BufferSlot, FRAME_SLOTS> slots{};
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t next_slot = 0;
    uint32_t next_serial = 1;
};

std::mutex g_lock;
std::array<ScanoutState, MAX_SCANOUTS> g_scanouts{};
std::unordered_map<uint32_t, GLsync> g_context_fences;
int g_socket = -1;
uint64_t g_frame_count = 0;
uint64_t g_full_copy_count = 0;
uint64_t g_partial_copy_count = 0;
auto g_rate_started = std::chrono::steady_clock::now();

using GetNativeClientBuffer = PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC;
using CreateImage = PFNEGLCREATEIMAGEKHRPROC;
using DestroyImage = PFNEGLDESTROYIMAGEKHRPROC;
using ImageTargetTexture = PFNGLEGLIMAGETARGETTEXTURE2DOESPROC;
using CreateSync = PFNEGLCREATESYNCKHRPROC;
using DestroySync = PFNEGLDESTROYSYNCKHRPROC;
using DupNativeFenceFd = PFNEGLDUPNATIVEFENCEFDANDROIDPROC;

GetNativeClientBuffer g_get_native_client_buffer = nullptr;
CreateImage g_create_image = nullptr;
DestroyImage g_destroy_image = nullptr;
ImageTargetTexture g_image_target_texture = nullptr;
CreateSync g_create_sync = nullptr;
DestroySync g_destroy_sync = nullptr;
DupNativeFenceFd g_dup_native_fence_fd = nullptr;

std::string hex_value(uint32_t value) {
    char out[16]{};
    std::snprintf(out, sizeof(out), "0x%08x", value);
    return out;
}

void emit_log(int priority, const char* level, const std::string& message) {
    __android_log_print(priority, TAG, "%s", message.c_str());
    std::fprintf(stderr, "[AHB/%s] %s\n", level, message.c_str());
    std::fflush(stderr);
}
void loge(const std::string& message) { emit_log(ANDROID_LOG_ERROR, "E", message); }
void logw(const std::string& message) { emit_log(ANDROID_LOG_WARN, "W", message); }
void logi(const std::string& message) { emit_log(ANDROID_LOG_INFO, "I", message); }

void clear_gl_errors() { for (int i = 0; i < 16; ++i) if (glGetError() == GL_NO_ERROR) return; }
void clear_egl_errors() { for (int i = 0; i < 16; ++i) if (eglGetError() == EGL_SUCCESS) return; }

DamageRect full_damage(const ScanoutState& state) {
    return DamageRect{0, 0, state.width, state.height, state.width != 0 && state.height != 0};
}

DamageRect full_damage(uint32_t width, uint32_t height) {
    return DamageRect{0, 0, width, height, width != 0 && height != 0};
}

DamageRect union_damage(const DamageRect& a, const DamageRect& b) {
    if (!a.valid) return b;
    if (!b.valid) return a;
    const uint64_t x0 = std::min<uint64_t>(a.x, b.x);
    const uint64_t y0 = std::min<uint64_t>(a.y, b.y);
    const uint64_t x1 = std::max<uint64_t>(static_cast<uint64_t>(a.x) + a.width, static_cast<uint64_t>(b.x) + b.width);
    const uint64_t y1 = std::max<uint64_t>(static_cast<uint64_t>(a.y) + a.height, static_cast<uint64_t>(b.y) + b.height);
    return DamageRect{
        static_cast<uint32_t>(x0),
        static_cast<uint32_t>(y0),
        static_cast<uint32_t>(x1 - x0),
        static_cast<uint32_t>(y1 - y0),
        true,
    };
}

DamageRect clip_damage(const ScanoutState& state, uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    if (!state.width || !state.height) return {};
    if (!width || !height) return full_damage(state);
    const uint64_t sx0 = state.x;
    const uint64_t sy0 = state.y;
    const uint64_t sx1 = sx0 + state.width;
    const uint64_t sy1 = sy0 + state.height;
    const uint64_t dx0 = x;
    const uint64_t dy0 = y;
    const uint64_t dx1 = dx0 + width;
    const uint64_t dy1 = dy0 + height;
    const uint64_t ix0 = std::max(sx0, dx0);
    const uint64_t iy0 = std::max(sy0, dy0);
    const uint64_t ix1 = std::min(sx1, dx1);
    const uint64_t iy1 = std::min(sy1, dy1);
    if (ix0 >= ix1 || iy0 >= iy1) return {};
    return DamageRect{
        static_cast<uint32_t>(ix0 - sx0),
        static_cast<uint32_t>(iy0 - sy0),
        static_cast<uint32_t>(ix1 - ix0),
        static_cast<uint32_t>(iy1 - iy0),
        true,
    };
}

bool send_all(int fd, const void* data, size_t size) {
    const auto* p = static_cast<const uint8_t*>(data);
    while (size) {
        const ssize_t n = send(fd, p, size, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += static_cast<size_t>(n);
        size -= static_cast<size_t>(n);
    }
    return true;
}

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

bool send_fence_fd(int socket_fd, int fence_fd) {
    uint8_t tag = FENCE_TAG;
    iovec io{&tag, sizeof(tag)};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr msg{};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(cmsg), &fence_fd, sizeof(int));
    msg.msg_controllen = CMSG_SPACE(sizeof(int));
    for (;;) {
        const ssize_t n = sendmsg(socket_fd, &msg, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        return n == static_cast<ssize_t>(sizeof(tag));
    }
}

void mark_transport_reset() {
    for (auto& scanout : g_scanouts) {
        for (auto& slot : scanout.slots) {
            slot.registered = false;
            slot.busy = false;
            slot.serial = 0;
        }
    }
}

void close_socket() {
    if (g_socket >= 0) close(g_socket);
    g_socket = -1;
    mark_transport_reset();
}

bool connect_socket() {
    if (g_socket >= 0) return true;
    const std::string name = "vessel-ahb-" + std::to_string(getuid());
    int last_errno = 0;
    for (int attempt = 0; attempt < 100; ++attempt) {
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) { loge("socket(AF_UNIX) failed errno=" + std::to_string(errno)); return false; }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) { close(fd); loge("AHB socket name too long"); return false; }
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (connect(fd, reinterpret_cast<sockaddr*>(&addr), len) == 0) {
            g_socket = fd;
            mark_transport_reset();
            logi("connected presenter side channel name=" + name + " protocol=3 slots=3 syncfd=1 damage=1 resourceSync=1");
            return true;
        }
        last_errno = errno;
        close(fd);
        if (last_errno != ENOENT && last_errno != ECONNREFUSED) break;
        usleep(10'000);
    }
    loge("presenter side-channel connect failed errno=" + std::to_string(last_errno));
    return false;
}

bool handle_ack(const AckMessage& ack) {
    if (ack.magic != MAGIC || ack.version != VERSION || ack.scanout_id >= MAX_SCANOUTS) {
        loge("invalid presenter ACK header");
        return false;
    }
    if (ack.slot >= FRAME_SLOTS) return ack.ok != 0;
    auto& slot = g_scanouts[ack.scanout_id].slots[ack.slot];
    if (slot.busy && slot.serial == ack.serial) {
        slot.busy = false;
        slot.serial = 0;
    }
    if (!ack.ok) loge("presenter rejected frame serial=" + std::to_string(ack.serial));
    return ack.ok != 0;
}

bool receive_one_ack(bool block) {
    if (g_socket < 0) return false;
    pollfd pfd{g_socket, POLLIN, 0};
    const int rc = poll(&pfd, 1, block ? -1 : 0);
    if (rc < 0 && errno == EINTR) return receive_one_ack(block);
    if (rc <= 0) return false;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) { close_socket(); return false; }
    AckMessage ack{};
    if (!recv_all(g_socket, &ack, sizeof(ack))) { close_socket(); return false; }
    return handle_ack(ack);
}

void drain_acks() {
    while (g_socket >= 0) {
        pollfd pfd{g_socket, POLLIN, 0};
        const int rc = poll(&pfd, 1, 0);
        if (rc <= 0 || !(pfd.revents & POLLIN)) break;
        AckMessage ack{};
        if (!recv_all(g_socket, &ack, sizeof(ack)) || !handle_ack(ack)) {
            if (ack.ok == 0) logw("frame ACK reported failure");
            if (g_socket < 0) break;
        }
    }
}

bool load_extensions() {
    if (g_get_native_client_buffer && g_create_image && g_destroy_image && g_image_target_texture &&
        g_create_sync && g_destroy_sync && g_dup_native_fence_fd) return true;
    g_get_native_client_buffer = reinterpret_cast<GetNativeClientBuffer>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_create_image = reinterpret_cast<CreateImage>(eglGetProcAddress("eglCreateImageKHR"));
    g_destroy_image = reinterpret_cast<DestroyImage>(eglGetProcAddress("eglDestroyImageKHR"));
    g_image_target_texture = reinterpret_cast<ImageTargetTexture>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    g_create_sync = reinterpret_cast<CreateSync>(eglGetProcAddress("eglCreateSyncKHR"));
    g_destroy_sync = reinterpret_cast<DestroySync>(eglGetProcAddress("eglDestroySyncKHR"));
    g_dup_native_fence_fd = reinterpret_cast<DupNativeFenceFd>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    if (!g_get_native_client_buffer || !g_create_image || !g_destroy_image || !g_image_target_texture ||
        !g_create_sync || !g_destroy_sync || !g_dup_native_fence_fd) {
        loge("missing EGL/AHardwareBuffer/native-fence entry point");
        return false;
    }
    logi("loaded EGL AHardwareBuffer + Android native-fence entry points");
    return true;
}

void destroy_slot(BufferSlot& slot) {
    const EGLDisplay display = eglGetCurrentDisplay();
    if (slot.draw_fbo) glDeleteFramebuffers(1, &slot.draw_fbo);
    if (slot.texture) glDeleteTextures(1, &slot.texture);
    if (slot.image != EGL_NO_IMAGE_KHR && display != EGL_NO_DISPLAY && g_destroy_image) g_destroy_image(display, slot.image);
    if (slot.buffer) AHardwareBuffer_release(slot.buffer);
    slot = {};
}

void destroy_scanout(ScanoutState& state) {
    for (auto& slot : state.slots) destroy_slot(slot);
    state = {};
}

int allocate_slot(BufferSlot& slot, uint32_t width, uint32_t height, uint32_t index) {
    if (slot.buffer && slot.width == width && slot.height == height) return 0;
    destroy_slot(slot);
    if (!load_extensions()) return RC_EXTENSIONS;
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLContext context = eglGetCurrentContext();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) {
        loge("VirGL EGL context not current while allocating slot=" + std::to_string(index));
        return RC_CONTEXT;
    }

    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (!AHardwareBuffer_isSupported(&desc)) { loge("AHB descriptor unsupported"); return RC_AHB_ALLOC; }
    const int alloc_rc = AHardwareBuffer_allocate(&desc, &slot.buffer);
    if (alloc_rc != 0 || !slot.buffer) { loge("AHardwareBuffer_allocate failed rc=" + std::to_string(alloc_rc)); return RC_AHB_ALLOC; }

    EGLClientBuffer client = g_get_native_client_buffer(slot.buffer);
    if (!client) { destroy_slot(slot); return RC_AHB_CLIENT; }
    clear_egl_errors();
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    slot.image = g_create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (slot.image == EGL_NO_IMAGE_KHR) {
        loge("eglCreateImageKHR failed eglError=" + hex_value(static_cast<uint32_t>(eglGetError())));
        destroy_slot(slot);
        return RC_EGL_IMAGE;
    }

    GLint old_texture = 0, old_draw = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_texture);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw);
    clear_gl_errors();
    glGenTextures(1, &slot.texture);
    glBindTexture(GL_TEXTURE_2D, slot.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    g_image_target_texture(GL_TEXTURE_2D, slot.image);
    if (glGetError() != GL_NO_ERROR) { glBindTexture(GL_TEXTURE_2D, old_texture); destroy_slot(slot); return RC_AHB_TEXTURE; }
    glGenFramebuffers(1, &slot.draw_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, slot.draw_fbo);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, slot.texture, 0);
    const GLenum status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
    const GLenum err = glGetError();
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, old_draw);
    glBindTexture(GL_TEXTURE_2D, old_texture);
    if (status != GL_FRAMEBUFFER_COMPLETE || err != GL_NO_ERROR) {
        loge("AHB destination FBO failed status=" + hex_value(status) + " glError=" + hex_value(err));
        destroy_slot(slot);
        return RC_DST_FBO;
    }
    slot.width = width;
    slot.height = height;
    slot.content_valid = false;
    slot.pending_damage = full_damage(width, height);
    logi("allocated pipeline slot=" + std::to_string(index) + " size=" + std::to_string(width) + "x" + std::to_string(height));
    return 0;
}

int copy_resource(uint32_t resource_id, BufferSlot& slot, uint32_t source_x, uint32_t source_y,
                  const DamageRect& damage, int* out_fence_fd) {
    *out_fence_fd = -1;
    if (!damage.valid || !damage.width || !damage.height) return 0;

    virgl_renderer_resource_info info{};
    const int info_rc = virgl_renderer_resource_get_info(static_cast<int>(resource_id), &info);
    if (!info.tex_id) { loge("resource has no GL texture id=" + std::to_string(resource_id)); return RC_RESOURCE_INFO; }
    if (info_rc != 0) logw("resource metadata rc=" + std::to_string(info_rc) + " ignored; tex_id is valid");

    GLint old_read = 0, old_draw = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_read);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw);
    clear_gl_errors();
    GLuint read_fbo = 0;
    glGenFramebuffers(1, &read_fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read_fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, info.tex_id, 0);
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    const GLenum read_status = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
    const GLenum read_error = glGetError();
    if (read_status != GL_FRAMEBUFFER_COMPLETE || read_error != GL_NO_ERROR) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, old_read);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, old_draw);
        glDeleteFramebuffers(1, &read_fbo);
        loge("VirGL source FBO failed status=" + hex_value(read_status) + " glError=" + hex_value(read_error));
        return RC_SRC_FBO;
    }

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, slot.draw_fbo);
    const GLenum draw_buffer = GL_COLOR_ATTACHMENT0;
    glDrawBuffers(1, &draw_buffer);
    clear_gl_errors();
    const GLint src_x0 = static_cast<GLint>(source_x + damage.x);
    const GLint src_y0 = static_cast<GLint>(source_y + damage.y);
    const GLint src_x1 = static_cast<GLint>(source_x + damage.x + damage.width);
    const GLint src_y1 = static_cast<GLint>(source_y + damage.y + damage.height);
    const GLint dst_x0 = static_cast<GLint>(damage.x);
    const GLint dst_y0 = static_cast<GLint>(damage.y);
    const GLint dst_x1 = static_cast<GLint>(damage.x + damage.width);
    const GLint dst_y1 = static_cast<GLint>(damage.y + damage.height);
    glBlitFramebuffer(src_x0, src_y0, src_x1, src_y1,
                      dst_x0, dst_y0, dst_x1, dst_y1,
                      GL_COLOR_BUFFER_BIT, GL_NEAREST);
    const GLenum blit_error = glGetError();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, old_read);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, old_draw);
    glDeleteFramebuffers(1, &read_fbo);
    if (blit_error != GL_NO_ERROR) { loge("GPU blit failed glError=" + hex_value(blit_error)); return RC_GPU_BLIT; }

    const EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || !load_extensions()) return RC_CONTEXT;
    clear_egl_errors();
    const EGLint sync_attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE};
    EGLSyncKHR sync = g_create_sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, sync_attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        loge("eglCreateSyncKHR(native fence) failed eglError=" + hex_value(static_cast<uint32_t>(eglGetError())));
        return RC_NATIVE_FENCE;
    }
    glFlush();
    const int fence_fd = g_dup_native_fence_fd(display, sync);
    const EGLint dup_error = eglGetError();
    g_destroy_sync(display, sync);
    if (fence_fd < 0) {
        loge("eglDupNativeFenceFDANDROID failed fd=" + std::to_string(fence_fd) + " eglError=" + hex_value(static_cast<uint32_t>(dup_error)));
        return RC_NATIVE_FENCE;
    }
    *out_fence_fd = fence_fd;
    return 0;
}

int wait_for_free_slot(uint32_t scanout_id, ScanoutState& state) {
    for (;;) {
        drain_acks();
        for (size_t n = 0; n < FRAME_SLOTS; ++n) {
            const uint32_t idx = (state.next_slot + static_cast<uint32_t>(n)) % FRAME_SLOTS;
            if (!state.slots[idx].busy) { state.next_slot = (idx + 1) % FRAME_SLOTS; return static_cast<int>(idx); }
        }
        if (!connect_socket() || !receive_one_ack(true)) {
            if (g_socket < 0) continue;
            loge("failed waiting for free AHB slot scanout=" + std::to_string(scanout_id));
            return -1;
        }
    }
}

bool wait_scanout_idle(ScanoutState& state) {
    for (;;) {
        drain_acks();
        bool busy = false;
        for (const auto& slot : state.slots) busy = busy || slot.busy;
        if (!busy) return true;
        if (!connect_socket() || !receive_one_ack(true)) {
            if (g_socket < 0) return true;
            return false;
        }
    }
}

int send_frame(uint32_t scanout_id, uint32_t slot_index, ScanoutState& state, int fence_fd) {
    if (!connect_socket()) { close(fence_fd); return RC_SOCKET; }
    auto& slot = state.slots[slot_index];
    const uint32_t serial = state.next_serial++;
    const bool needs_handle = !slot.registered;
    AhbMessage msg{MAGIC, VERSION, needs_handle ? MSG_REGISTER_FRAME : MSG_FRAME, scanout_id, slot_index, serial, state.width, state.height};
    if (!send_all(g_socket, &msg, sizeof(msg))) { close(fence_fd); close_socket(); return RC_SOCKET; }
    if (needs_handle) {
        const int rc = AHardwareBuffer_sendHandleToUnixSocket(slot.buffer, g_socket);
        if (rc != 0) { loge("AHardwareBuffer_sendHandleToUnixSocket failed rc=" + std::to_string(rc)); close(fence_fd); close_socket(); return RC_SOCKET; }
        slot.registered = true;
        logi("registered AHB pipeline slot=" + std::to_string(slot_index) + " size=" + std::to_string(state.width) + "x" + std::to_string(state.height));
    }
    if (!send_fence_fd(g_socket, fence_fd)) {
        loge("failed to send native producer fence fd");
        close(fence_fd);
        close_socket();
        return RC_SOCKET;
    }
    close(fence_fd);
    slot.busy = true;
    slot.serial = serial;

    ++g_frame_count;
    const auto now = std::chrono::steady_clock::now();
    const double elapsed = std::chrono::duration<double>(now - g_rate_started).count();
    if (elapsed >= 2.0) {
        logi("producer=" + std::to_string(static_cast<int>(g_frame_count / elapsed)) +
             " fps in_flight<=3 syncfd=1 full=" + std::to_string(g_full_copy_count) +
             " partial=" + std::to_string(g_partial_copy_count));
        g_frame_count = 0;
        g_full_copy_count = 0;
        g_partial_copy_count = 0;
        g_rate_started = now;
    }
    return 0;
}

int submit_resource(uint32_t resource_id, uint32_t scanout_id, uint32_t x, uint32_t y,
                    uint32_t width, uint32_t height, const DamageRect& incoming_damage) {
    auto& state = g_scanouts[scanout_id];
    if (state.width != width || state.height != height) {
        if (!wait_scanout_idle(state)) return RC_SOCKET;
        destroy_scanout(state);
        state.width = width;
        state.height = height;
        state.x = x;
        state.y = y;
        logi("scanout resized to stable frame " + std::to_string(width) + "x" + std::to_string(height));
    } else {
        state.x = x;
        state.y = y;
    }

    if (!incoming_damage.valid) return 0;
    for (auto& slot : state.slots) {
        if (slot.buffer && slot.content_valid) {
            slot.pending_damage = union_damage(slot.pending_damage, incoming_damage);
        }
    }

    const int idx = wait_for_free_slot(scanout_id, state);
    if (idx < 0) return RC_SOCKET;
    auto& slot = state.slots[static_cast<size_t>(idx)];
    const int alloc_rc = allocate_slot(slot, width, height, static_cast<uint32_t>(idx));
    if (alloc_rc) return alloc_rc;

    DamageRect copy_damage = slot.content_valid ? slot.pending_damage : full_damage(state);
    if (!copy_damage.valid) copy_damage = incoming_damage;
    const bool full = copy_damage.x == 0 && copy_damage.y == 0 && copy_damage.width == width && copy_damage.height == height;
    if (full) ++g_full_copy_count; else ++g_partial_copy_count;

    int fence_fd = -1;
    const int copy_rc = copy_resource(resource_id, slot, state.x, state.y, copy_damage, &fence_fd);
    if (copy_rc) return copy_rc;
    slot.content_valid = true;
    slot.pending_damage = {};
    return send_frame(scanout_id, static_cast<uint32_t>(idx), state, fence_fd);
}

} // namespace

extern "C" int vessel_ahb_note_submit(uint32_t ctx_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
        loge("VirGL submit completed without a current EGL context ctx=" + std::to_string(ctx_id));
        return RC_CONTEXT;
    }
    clear_gl_errors();
    GLsync next = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (!next) {
        loge("glFenceSync failed after VirGL submit ctx=" + std::to_string(ctx_id) + " glError=" + hex_value(glGetError()));
        return RC_RENDER_SYNC;
    }
    glFlush();
    auto it = g_context_fences.find(ctx_id);
    if (it != g_context_fences.end() && it->second) glDeleteSync(it->second);
    g_context_fences[ctx_id] = next;
    return 0;
}

extern "C" int vessel_ahb_wait_context(uint32_t ctx_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) return RC_CONTEXT;
    auto it = g_context_fences.find(ctx_id);
    if (it == g_context_fences.end() || !it->second) return 0;
    clear_gl_errors();
    glWaitSync(it->second, 0, GL_TIMEOUT_IGNORED);
    glDeleteSync(it->second);
    g_context_fences.erase(it);
    const GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        loge("resource producer GPU wait failed ctx=" + std::to_string(ctx_id) + " glError=" + hex_value(err));
        return RC_RENDER_SYNC;
    }
    return 0;
}

extern "C" int vessel_ahb_set_scanout(uint32_t resource_id, uint32_t scanout_id, uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS || !width || !height) return EINVAL;
    const DamageRect full = full_damage(width, height);
    const int rc = submit_resource(resource_id, scanout_id, x, y, width, height, full);
    if (rc) loge("SET_SCANOUT failed resource=" + std::to_string(resource_id) + " rc=" + std::to_string(rc));
    return rc;
}

extern "C" int vessel_ahb_update(uint32_t resource_id, uint32_t scanout_id,
                                  uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS) return EINVAL;
    auto& state = g_scanouts[scanout_id];
    if (!state.width || !state.height) return ENOENT;
    const DamageRect damage = clip_damage(state, x, y, width, height);
    if (!damage.valid) return 0;
    const int rc = submit_resource(resource_id, scanout_id, state.x, state.y, state.width, state.height, damage);
    if (rc) loge("UPDATE failed resource=" + std::to_string(resource_id) + " rc=" + std::to_string(rc));
    return rc;
}

extern "C" int vessel_ahb_disable(uint32_t scanout_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS) return EINVAL;
    auto& state = g_scanouts[scanout_id];
    wait_scanout_idle(state);
    if (connect_socket()) {
        const uint32_t serial = state.next_serial++;
        const AhbMessage msg{MAGIC, VERSION, MSG_DISABLE, scanout_id, UINT32_MAX, serial, 0, 0};
        if (send_all(g_socket, &msg, sizeof(msg))) {
            for (;;) {
                AckMessage ack{};
                if (!recv_all(g_socket, &ack, sizeof(ack))) { close_socket(); break; }
                if (ack.magic == MAGIC && ack.version == VERSION && ack.serial == serial && ack.slot == UINT32_MAX) break;
                handle_ack(ack);
            }
        } else close_socket();
    }
    destroy_scanout(state);
    return 0;
}
