#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
extern "C" {
#include <virgl/virglrenderer.h>
}

#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
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

// Internal diagnostic return codes. These are intentionally distinct so the
// Rust vhost log tells us which exact bridge stage failed even if stderr is
// truncated by the Android UI.
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

void loge(const std::string& message) {
    emit_log(ANDROID_LOG_ERROR, "E", message);
}

void logw(const std::string& message) {
    emit_log(ANDROID_LOG_WARN, "W", message);
}

void logi(const std::string& message) {
    emit_log(ANDROID_LOG_INFO, "I", message);
}

void clear_gl_errors() {
    for (int i = 0; i < 16; ++i) {
        if (glGetError() == GL_NO_ERROR) return;
    }
}

void clear_egl_errors() {
    for (int i = 0; i < 16; ++i) {
        if (eglGetError() == EGL_SUCCESS) return;
    }
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

bool recv_ack(int fd) {
    uint8_t ack = 0;
    while (true) {
        const ssize_t n = recv(fd, &ack, sizeof(ack), MSG_WAITALL);
        if (n < 0 && errno == EINTR) continue;
        return n == static_cast<ssize_t>(sizeof(ack)) && ack == 1;
    }
}

void close_socket() {
    if (g_socket >= 0) close(g_socket);
    g_socket = -1;
    for (auto& scanout : g_scanouts) scanout.handle_sent = false;
}

bool connect_socket() {
    if (g_socket >= 0) return true;

    const std::string name = "vessel-ahb-" + std::to_string(getuid());
    int last_errno = 0;
    for (int attempt = 0; attempt < 100; ++attempt) {
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            loge("socket(AF_UNIX) failed errno=" + std::to_string(errno));
            return false;
        }

        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) {
            close(fd);
            loge("AHB socket name is too long");
            return false;
        }
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (connect(fd, reinterpret_cast<sockaddr*>(&addr), len) == 0) {
            g_socket = fd;
            logi("connected presenter side channel name=" + name);
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

bool load_extensions() {
    if (g_get_native_client_buffer && g_create_image && g_destroy_image && g_image_target_texture) return true;
    g_get_native_client_buffer = reinterpret_cast<GetNativeClientBuffer>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_create_image = reinterpret_cast<CreateImage>(eglGetProcAddress("eglCreateImageKHR"));
    g_destroy_image = reinterpret_cast<DestroyImage>(eglGetProcAddress("eglDestroyImageKHR"));
    g_image_target_texture = reinterpret_cast<ImageTargetTexture>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!g_get_native_client_buffer || !g_create_image || !g_destroy_image || !g_image_target_texture) {
        loge(
            "missing EGL/AHB entry point: getNative=" + std::to_string(g_get_native_client_buffer != nullptr) +
            " createImage=" + std::to_string(g_create_image != nullptr) +
            " destroyImage=" + std::to_string(g_destroy_image != nullptr) +
            " imageTarget=" + std::to_string(g_image_target_texture != nullptr));
        return false;
    }
    logi("loaded EGL_ANDROID_image_native_buffer entry points");
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

int allocate_scanout(ScanoutState& state, uint32_t width, uint32_t height) {
    if (state.buffer && state.width == width && state.height == height) return 0;
    destroy_scanout(state);

    logi("allocate scanout begin size=" + std::to_string(width) + "x" + std::to_string(height));
    if (!load_extensions()) return RC_EXTENSIONS;

    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLContext context = eglGetCurrentContext();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) {
        loge(
            "VirGL context is not current while creating AHB: display=" +
            std::to_string(display != EGL_NO_DISPLAY) + " context=" +
            std::to_string(context != EGL_NO_CONTEXT));
        return RC_CONTEXT;
    }
    logi("VirGL EGL display/context are current");

    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;

    const int supported = AHardwareBuffer_isSupported(&desc);
    logi(
        "AHB descriptor format=RGBA8 usage=" + std::to_string(desc.usage) +
        " supported=" + std::to_string(supported));

    const int alloc_rc = AHardwareBuffer_allocate(&desc, &state.buffer);
    if (alloc_rc != 0 || !state.buffer) {
        loge("AHardwareBuffer_allocate failed rc=" + std::to_string(alloc_rc));
        state.buffer = nullptr;
        return RC_AHB_ALLOC;
    }

    AHardwareBuffer_Desc actual{};
    AHardwareBuffer_describe(state.buffer, &actual);
    logi(
        "AHB allocated width=" + std::to_string(actual.width) +
        " height=" + std::to_string(actual.height) +
        " stride=" + std::to_string(actual.stride) +
        " format=" + std::to_string(actual.format) +
        " usage=" + std::to_string(actual.usage));

    EGLClientBuffer client = g_get_native_client_buffer(state.buffer);
    if (!client) {
        loge("eglGetNativeClientBufferANDROID returned null");
        destroy_scanout(state);
        return RC_AHB_CLIENT;
    }
    logi("eglGetNativeClientBufferANDROID OK");

    clear_egl_errors();
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    state.image = g_create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (state.image == EGL_NO_IMAGE_KHR) {
        const EGLint egl_error = eglGetError();
        loge("eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID) failed eglError=" + hex_value(static_cast<uint32_t>(egl_error)));
        destroy_scanout(state);
        return RC_EGL_IMAGE;
    }
    logi("eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID) OK");

    GLint old_texture = 0;
    GLint old_draw = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_texture);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw);

    clear_gl_errors();
    glGenTextures(1, &state.texture);
    glBindTexture(GL_TEXTURE_2D, state.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    g_image_target_texture(GL_TEXTURE_2D, state.image);
    const GLenum image_target_error = glGetError();
    if (image_target_error != GL_NO_ERROR) {
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(old_texture));
        loge("glEGLImageTargetTexture2DOES failed glError=" + hex_value(image_target_error));
        destroy_scanout(state);
        return RC_AHB_TEXTURE;
    }
    logi("AHB EGLImage bound to GLES texture id=" + std::to_string(state.texture));

    glGenFramebuffers(1, &state.draw_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.draw_fbo);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, state.texture, 0);
    const GLenum fbo_status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
    const GLenum fbo_error = glGetError();
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(old_draw));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(old_texture));
    if (fbo_status != GL_FRAMEBUFFER_COMPLETE || fbo_error != GL_NO_ERROR) {
        loge(
            "AHB destination FBO failed status=" + hex_value(fbo_status) +
            " glError=" + hex_value(fbo_error));
        destroy_scanout(state);
        return RC_DST_FBO;
    }

    state.width = width;
    state.height = height;
    state.handle_sent = false;
    logi("AHB destination framebuffer complete fbo=" + std::to_string(state.draw_fbo));
    return 0;
}

int copy_resource(uint32_t resource_id, ScanoutState& state, bool verbose) {
    virgl_renderer_resource_info info{};
    const int info_rc = virgl_renderer_resource_get_info(static_cast<int>(resource_id), &info);

    // virgl_renderer_resource_get_info fills tex_id before asking the EGL winsys
    // for DRM-fourcc/export metadata. ANGLE can reject that metadata query even
    // though the GL texture itself is completely valid. This AHB path only needs
    // tex_id, so a non-zero metadata return is non-fatal when tex_id is present.
    if (info.tex_id == 0) {
        loge(
            "resource info has no GL texture resource=" + std::to_string(resource_id) +
            " infoRc=" + std::to_string(info_rc));
        return RC_RESOURCE_INFO;
    }
    if (info_rc != 0) {
        logw(
            "resource metadata query returned rc=" + std::to_string(info_rc) +
            " but tex_id=" + std::to_string(info.tex_id) +
            " is valid; ignoring winsys/export metadata failure");
    }
    if (verbose) {
        logi(
            "VirGL resource=" + std::to_string(resource_id) +
            " tex_id=" + std::to_string(info.tex_id) +
            " size=" + std::to_string(info.width) + "x" + std::to_string(info.height) +
            " format=" + std::to_string(info.virgl_format) +
            " drm_fourcc=" + std::to_string(info.drm_fourcc) +
            " infoRc=" + std::to_string(info_rc));
    }

    GLint old_read = 0;
    GLint old_draw = 0;
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
        glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(old_read));
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(old_draw));
        glDeleteFramebuffers(1, &read_fbo);
        loge(
            "VirGL source FBO failed resource=" + std::to_string(resource_id) +
            " tex_id=" + std::to_string(info.tex_id) +
            " status=" + hex_value(read_status) +
            " glError=" + hex_value(read_error));
        return RC_SRC_FBO;
    }
    if (verbose) logi("VirGL source framebuffer complete");

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.draw_fbo);
    const GLenum draw_buffer = GL_COLOR_ATTACHMENT0;
    glDrawBuffers(1, &draw_buffer);
    clear_gl_errors();
    glBlitFramebuffer(
        static_cast<GLint>(state.x), static_cast<GLint>(state.y),
        static_cast<GLint>(state.x + state.width), static_cast<GLint>(state.y + state.height),
        0, 0, static_cast<GLint>(state.width), static_cast<GLint>(state.height),
        GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glFinish();
    const GLenum blit_error = glGetError();

    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(old_read));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(old_draw));
    glDeleteFramebuffers(1, &read_fbo);

    if (blit_error != GL_NO_ERROR) {
        loge(
            "GPU blit VirGL->AHB failed resource=" + std::to_string(resource_id) +
            " glError=" + hex_value(blit_error));
        return RC_GPU_BLIT;
    }
    if (verbose) logi("GPU blit VirGL->AHB complete");
    return 0;
}

int send_scanout(uint32_t scanout_id, ScanoutState& state) {
    if (!connect_socket()) return RC_SOCKET;
    const AhbMessage message{MAGIC, VERSION, MSG_SCANOUT, scanout_id, state.width, state.height};
    if (!send_all(g_socket, &message, sizeof(message))) {
        loge("failed sending MSG_SCANOUT header errno=" + std::to_string(errno));
        close_socket();
        return RC_SOCKET;
    }
    const int handle_rc = AHardwareBuffer_sendHandleToUnixSocket(state.buffer, g_socket);
    if (handle_rc != 0) {
        loge("AHardwareBuffer_sendHandleToUnixSocket failed rc=" + std::to_string(handle_rc));
        close_socket();
        return RC_SOCKET;
    }
    if (!recv_ack(g_socket)) {
        loge("presenter rejected or disconnected during MSG_SCANOUT ack errno=" + std::to_string(errno));
        close_socket();
        return RC_SOCKET;
    }
    state.handle_sent = true;
    logi(
        "AHB handle delivered to presenter scanout=" + std::to_string(scanout_id) +
        " size=" + std::to_string(state.width) + "x" + std::to_string(state.height));
    return 0;
}

int send_update(uint32_t scanout_id, ScanoutState& state) {
    if (!connect_socket()) return RC_SOCKET;
    if (!state.handle_sent) return send_scanout(scanout_id, state);
    const AhbMessage message{MAGIC, VERSION, MSG_UPDATE, scanout_id, state.width, state.height};
    if (!send_all(g_socket, &message, sizeof(message)) || !recv_ack(g_socket)) {
        logw("MSG_UPDATE failed; reconnecting presenter side channel");
        close_socket();
        if (!connect_socket()) return RC_SOCKET;
        return send_scanout(scanout_id, state);
    }
    return 0;
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
    if (scanout_id >= MAX_SCANOUTS || width == 0 || height == 0) {
        loge(
            "invalid SET_SCANOUT resource=" + std::to_string(resource_id) +
            " scanout=" + std::to_string(scanout_id) +
            " rect=" + std::to_string(x) + "," + std::to_string(y) +
            " " + std::to_string(width) + "x" + std::to_string(height));
        return EINVAL;
    }

    logi(
        "SET_SCANOUT resource=" + std::to_string(resource_id) +
        " scanout=" + std::to_string(scanout_id) +
        " rect=" + std::to_string(x) + "," + std::to_string(y) +
        " " + std::to_string(width) + "x" + std::to_string(height));

    auto& state = g_scanouts[scanout_id];
    const int alloc_rc = allocate_scanout(state, width, height);
    if (alloc_rc != 0) {
        loge("SET_SCANOUT failed in allocate_scanout rc=" + std::to_string(alloc_rc));
        return alloc_rc;
    }

    state.x = x;
    state.y = y;
    const int copy_rc = copy_resource(resource_id, state, true);
    if (copy_rc != 0) {
        loge("SET_SCANOUT failed in copy_resource rc=" + std::to_string(copy_rc));
        return copy_rc;
    }

    const int send_rc = send_scanout(scanout_id, state);
    if (send_rc != 0) {
        loge("SET_SCANOUT failed delivering AHB rc=" + std::to_string(send_rc));
        return send_rc;
    }

    logi("SET_SCANOUT complete resource=" + std::to_string(resource_id));
    return 0;
}

extern "C" int vessel_ahb_update(uint32_t resource_id, uint32_t scanout_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS) return EINVAL;
    auto& state = g_scanouts[scanout_id];
    if (!state.buffer || state.width == 0 || state.height == 0) return ENOENT;

    const int copy_rc = copy_resource(resource_id, state, false);
    if (copy_rc != 0) {
        loge(
            "UPDATE copy failed resource=" + std::to_string(resource_id) +
            " scanout=" + std::to_string(scanout_id) +
            " rc=" + std::to_string(copy_rc));
        return copy_rc;
    }
    const int send_rc = send_update(scanout_id, state);
    if (send_rc != 0) {
        loge(
            "UPDATE send failed resource=" + std::to_string(resource_id) +
            " scanout=" + std::to_string(scanout_id) +
            " rc=" + std::to_string(send_rc));
    }
    return send_rc;
}

extern "C" int vessel_ahb_disable(uint32_t scanout_id) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (scanout_id >= MAX_SCANOUTS) return EINVAL;
    logi("DISABLE scanout=" + std::to_string(scanout_id));
    if (connect_socket()) {
        const AhbMessage message{MAGIC, VERSION, MSG_DISABLE, scanout_id, 0, 0};
        if (!send_all(g_socket, &message, sizeof(message)) || !recv_ack(g_socket)) {
            logw("DISABLE presenter ack failed");
            close_socket();
        }
    }
    destroy_scanout(g_scanouts[scanout_id]);
    return 0;
}
