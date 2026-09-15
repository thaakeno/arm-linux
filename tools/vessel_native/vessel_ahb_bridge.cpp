#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <virgl/virglrenderer.h>

#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VesselAhbBridge";
constexpr uint32_t MAGIC = 0x42484156u; // "VAHB" little-endian
constexpr uint32_t VERSION = 1;
constexpr uint32_t MSG_SCANOUT = 1;
constexpr uint32_t MSG_UPDATE = 2;
constexpr uint32_t MSG_DISABLE = 3;
constexpr size_t MAX_SCANOUTS = 16;

#pragma pack(push, 1)
struct AhbMessage {
    uint32_t magic;
    uint32_t version;
    uint32_t type;
    uint32_t scanout_id;
    uint32_t width;
    uint32_t height;
};
#pragma pack(pop)

struct ScanoutState {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    GLuint draw_fbo = 0;
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool handle_sent = false;
};

std::mutex g_lock;
std::array<ScanoutState, MAX_SCANOUTS> g_scanouts{};
int g_socket = -1;

using GetNativeClientBuffer = PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC;
using CreateImage = PFNEGLCREATEIMAGEKHRPROC;
using DestroyImage = PFNEGLDESTROYIMAGEKHRPROC;
using ImageTargetTexture = PFNGLEGLIMAGETARGETTEXTURE2DOESPROC;

GetNativeClientBuffer g_get_native_client_buffer = nullptr;
CreateImage g_create_image = nullptr;
DestroyImage g_destroy_image = nullptr;
ImageTargetTexture g_image_target_texture = nullptr;

void loge(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

void logi(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
}

bool send_all(int fd, const void* data, size_t size) {
    const auto* p = static_cast<const uint8_t*>(data);
    while (size > 0) {
        const ssize_t n = send(fd, p, size, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += static_cast<size_t>(n);
        size -= static_cast<size_t>(n);
    }
    return true;
}

void close_socket() {
    if (g_socket >= 0) close(g_socket);
    g_socket = -1;
    for (auto& scanout : g_scanouts) scanout.handle_sent = false;
}

bool connect_socket() {
    if (g_socket >= 0) return true;

    const std::string name = "vessel-ahb-" + std::to_string(getuid());
    for (int attempt = 0; attempt < 100; ++attempt) {
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return false;

        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) {
            close(fd);
            return false;
        }
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (connect(fd, reinterpret_cast<sockaddr*>(&addr), len) == 0) {
            g_socket = fd;
            logi("connected Android HardwareBuffer side channel " + name);
            return true;
        }
        const int saved = errno;
        close(fd);
        if (saved != ENOENT && saved != ECONNREFUSED) break;
        usleep(10'000);
    }
    return false;
}

bool load_extensions() {
    if (g_get_native_client_buffer && g_create_image && g_destroy_image && g_image_target_texture) return true;
    g_get_native_client_buffer = reinterpret_cast<GetNativeClientBuffer>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_create_image = reinterpret_cast<CreateImage>(eglGetProcAddress("eglCreateImageKHR"));
    g_destroy_image = reinterpret_cast<DestroyImage>(eglGetProcAddress("eglDestroyImageKHR"));
    g_image_target_texture = reinterpret_cast<ImageTargetTexture>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!g_get_native_client_buffer || !g_create_image || !g_destroy_image || !g_image_target_texture) {
        loge("ANGLE is missing Android native-buffer EGL entry points");
        return false;
    }
    return true;
}

void destroy_scanout(ScanoutState& state) {
    const EGLDisplay display = eglGetCurrentDisplay();
    if (state.draw_fbo != 0) glDeleteFramebuffers(1, &state.draw_fbo);
    if (state.texture != 0) glDeleteTextures(1, &state.texture);
    if (state.image != EGL_NO_IMAGE_KHR && display != EGL_NO_DISPLAY && g_destroy_image) {
        g_destroy_image(display, state.image);
    }
    if (state.buffer) AHardwareBuffer_release(state.buffer);
    state = {};
}

bool allocate_scanout(ScanoutState& state, uint32_t width, uint32_t height) {
    if (state.buffer && state.width == width && state.height == height) return true;
    destroy_scanout(state);

    if (!load_extensions()) return false;
    const EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) {
        loge("VirGL context is not current while creating AHardwareBuffer scanout");
        return false;
    }

    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (AHardwareBuffer_allocate(&desc, &state.buffer) != 0 || !state.buffer) {
        loge("AHardwareBuffer_allocate failed");
        state.buffer = nullptr;
        return false;
    }

    EGLClientBuffer client = g_get_native_client_buffer(state.buffer);
    if (!client) {
        loge("eglGetNativeClientBufferANDROID failed");
        destroy_scanout(state);
        return false;
    }
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    state.image = g_create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (state.image == EGL_NO_IMAGE_KHR) {
        loge("eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID) failed");
        destroy_scanout(state);
        return false;
    }

    GLint old_texture = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_texture);
    glGenTextures(1, &state.texture);
    glBindTexture(GL_TEXTURE_2D, state.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    g_image_target_texture(GL_TEXTURE_2D, state.image);
    glGenFramebuffers(1, &state.draw_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.draw_fbo);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, state.texture, 0);
    const GLenum fbo_status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(old_texture));
    if (fbo_status != GL_FRAMEBUFFER_COMPLETE) {
        loge("AHardwareBuffer framebuffer is incomplete: " + std::to_string(fbo_status));
        destroy_scanout(state);
        return false;
    }

    state.width = width;
    state.height = height;
    state.handle_sent = false;
    return true;
}

bool copy_resource(uint32_t resource_id, ScanoutState& state) {
    virgl_renderer_resource_info info{};
    if (virgl_renderer_resource_get_info(static_cast<int>(resource_id), &info) != 0 || info.tex_id == 0) {
        loge("virgl_renderer_resource_get_info failed for resource " + std::to_string(resource_id));
        return false;
    }

    GLint old_read = 0;
    GLint old_draw = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_read);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw);

    GLuint read_fbo = 0;
    glGenFramebuffers(1, &read_fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read_fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, info.tex_id, 0);
    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(old_read));
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(old_draw));
        glDeleteFramebuffers(1, &read_fbo);
        loge("VirGL scanout texture is not framebuffer-complete");
        return false;
    }

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.draw_fbo);
    glBlitFramebuffer(
        static_cast<GLint>(state.x), static_cast<GLint>(state.y),
        static_cast<GLint>(state.x + state.width), static_cast<GLint>(state.y + state.height),
        0, 0, static_cast<GLint>(state.width), static_cast<GLint>(state.height),
        GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glFinish();

    const GLenum error = glGetError();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(old_read));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(old_draw));
    glDeleteFramebuffers(1, &read_fbo);
    if (error != GL_NO_ERROR) {
        loge("GPU copy into AHardwareBuffer failed: GL error " + std::to_string(error));
        return false;
    }
    return true;
}

bool send_scanout(uint32_t scanout_id, ScanoutState& state) {
    if (!connect_socket()) return false;
    const AhbMessage message{MAGIC, VERSION, MSG_SCANOUT, scanout_id, state.width, state.height};
    if (!send_all(g_socket, &message, sizeof(message)) ||
        AHardwareBuffer_sendHandleToUnixSocket(state.buffer, g_socket) != 0) {
        close_socket();
        return false;
    }
    state.handle_sent = true;
    return true;
}

bool send_update(uint32_t scanout_id, ScanoutState& state) {
    if (!connect_socket()) return false;
    if (!state.handle_sent) return send_scanout(scanout_id, state);
    const AhbMessage message{MAGIC, VERSION, MSG_UPDATE, scanout_id, state.width, state.height};
    if (!send_all(g_socket, &message, sizeof(message))) {
        close_socket();
        if (!connect_socket()) return false;
        return send_scanout(scanout_id, state);
    }
    return true;
}

} // namespace

extern "C" int vessel_ahb_set_scanout(
    uint32_t resource_id,
    uint32_t scanout_id,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS || width == 0 || height == 0) return EINVAL;
    auto& state = g_scanouts[scanout_id];
    if (!allocate_scanout(state, width, height)) return EIO;
    state.x = x;
    state.y = y;
    if (!copy_resource(resource_id, state)) return EIO;
    return send_scanout(scanout_id, state) ? 0 : EPIPE;
}

extern "C" int vessel_ahb_update(uint32_t resource_id, uint32_t scanout_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS) return EINVAL;
    auto& state = g_scanouts[scanout_id];
    if (!state.buffer || state.width == 0 || state.height == 0) return ENOENT;
    if (!copy_resource(resource_id, state)) return EIO;
    return send_update(scanout_id, state) ? 0 : EPIPE;
}

extern "C" int vessel_ahb_disable(uint32_t scanout_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS) return EINVAL;
    if (connect_socket()) {
        const AhbMessage message{MAGIC, VERSION, MSG_DISABLE, scanout_id, 0, 0};
        if (!send_all(g_socket, &message, sizeof(message))) close_socket();
    }
    destroy_scanout(g_scanouts[scanout_id]);
    return 0;
}
