#include <kwineffects.h>
#include <kwinglutils.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace KWin
{
namespace
{
constexpr uint32_t kMagic = 0x31574656u; // VFW1
constexpr uint32_t kImport = 1;
constexpr uint32_t kFrame = 2;
constexpr const char *kSocketPath = "/tmp/vessel-frame-export.sock";
constexpr const char *kEffectLog = "/tmp/vessel-output-effect.log";

#pragma pack(push, 1)
struct FrameMessage {
    uint32_t magic;
    uint32_t type;
    uint32_t width;
    uint32_t height;
    uint32_t fourcc;
    uint32_t stride;
    uint32_t offset;
    uint32_t reserved;
    uint64_t modifier;
    uint64_t serial;
};
#pragma pack(pop)

void appendLog(const std::string &line)
{
    const int fd = open(kEffectLog, O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) {
        return;
    }
    const std::string text = line + "\n";
    (void)write(fd, text.data(), text.size());
    close(fd);
}

bool sendAll(int fd, const void *ptr, size_t len)
{
    const auto *p = static_cast<const uint8_t *>(ptr);
    while (len) {
        const ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n <= 0) {
            return false;
        }
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}
}

class VesselOutputEffect final : public Effect
{
    Q_OBJECT
public:
    VesselOutputEffect()
    {
        appendLog("effect-constructed");
        // Construction itself must not be gated on KWin reporting OpenGL
        // compositing yet. KWin's plugin loader may query the factory before
        // the virtual backend has fully established its Zink/EGL compositor.
        // The paint callback is the correct place to wait for a current EGL
        // context and report the exact export capability that is missing.
        effects->addRepaintFull();
    }

    ~VesselOutputEffect() override
    {
        appendLog("effect-destroyed");
        destroyExport();
        if (m_socket >= 0) {
            close(m_socket);
        }
    }

    void postPaintScreen() override
    {
        effects->postPaintScreen();
        if (!m_seenPaint) {
            m_seenPaint = true;
            appendLog("postPaintScreen-active");
        }
        if (!ensureExport()) {
            // Retry only while the output bridge is being established. Once the
            // dmabuf is exported, normal KWin scene damage drives every frame.
            effects->addRepaintFull();
            return;
        }

        glBindTexture(GL_TEXTURE_2D, m_texture);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, m_width, m_height);
        glBindTexture(GL_TEXTURE_2D, 0);

        // Correctness first: this guarantees producer completion before Android
        // consumes the shared dma-buf. No pixels cross the CPU. The Android side
        // is independently pipelined so it does not queue-wait after every frame.
        glFinish();

        FrameMessage msg{};
        msg.magic = kMagic;
        msg.type = kFrame;
        msg.width = static_cast<uint32_t>(m_width);
        msg.height = static_cast<uint32_t>(m_height);
        msg.fourcc = m_fourcc;
        msg.stride = static_cast<uint32_t>(m_stride);
        msg.offset = static_cast<uint32_t>(m_offset);
        msg.modifier = m_modifier;
        msg.serial = ++m_serial;
        if (!sendFrame(msg, -1)) {
            noteFailure("frame-notify-send-failed");
            disconnectSocket();
            effects->addRepaintFull();
        } else if (m_serial <= 3 || (m_serial % 120) == 0) {
            appendLog("frame serial=" + std::to_string(m_serial));
        }
    }

private:
    EGLDisplay m_display = EGL_NO_DISPLAY;
    PFNEGLCREATEIMAGEKHRPROC m_createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC m_destroyImage = nullptr;
    PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC m_exportQuery = nullptr;
    PFNEGLEXPORTDMABUFIMAGEMESAPROC m_export = nullptr;
    EGLImageKHR m_image = EGL_NO_IMAGE_KHR;
    GLuint m_texture = 0;
    int m_socket = -1;
    int m_width = 0;
    int m_height = 0;
    int m_stride = 0;
    int m_offset = 0;
    uint32_t m_fourcc = 0;
    uint64_t m_modifier = 0;
    uint64_t m_serial = 0;
    bool m_seenPaint = false;
    std::string m_lastFailure;

    void noteFailure(const std::string &reason)
    {
        if (reason == m_lastFailure) {
            return;
        }
        m_lastFailure = reason;
        appendLog("waiting: " + reason);
    }

    void clearFailure()
    {
        if (!m_lastFailure.empty()) {
            appendLog("recovered-from: " + m_lastFailure);
            m_lastFailure.clear();
        }
    }

    bool initEglExport()
    {
        if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
            noteFailure("no-current-egl-context");
            return false;
        }

        const EGLDisplay display = eglGetCurrentDisplay();
        if (display == EGL_NO_DISPLAY) {
            noteFailure("no-current-egl-display");
            return false;
        }

        if (m_display != EGL_NO_DISPLAY && m_display != display) {
            destroyExport();
            m_createImage = nullptr;
            m_destroyImage = nullptr;
            m_exportQuery = nullptr;
            m_export = nullptr;
        }
        m_display = display;

        const char *extensions = eglQueryString(m_display, EGL_EXTENSIONS);
        if (!extensions) {
            noteFailure("egl-extension-query-failed");
            return false;
        }
        if (std::strstr(extensions, "EGL_MESA_image_dma_buf_export") == nullptr) {
            noteFailure("EGL_MESA_image_dma_buf_export-missing");
            return false;
        }

        if (!m_createImage) {
            m_createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
            m_destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
            m_exportQuery = reinterpret_cast<PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC>(eglGetProcAddress("eglExportDMABUFImageQueryMESA"));
            m_export = reinterpret_cast<PFNEGLEXPORTDMABUFIMAGEMESAPROC>(eglGetProcAddress("eglExportDMABUFImageMESA"));
        }
        if (!(m_createImage && m_destroyImage && m_exportQuery && m_export)) {
            noteFailure("egl-dmabuf-export-entrypoint-missing");
            return false;
        }
        clearFailure();
        return true;
    }

    bool connectSocket()
    {
        if (m_socket >= 0) {
            return true;
        }
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            noteFailure("frame-socket-create-failed");
            return false;
        }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, kSocketPath, sizeof(addr.sun_path) - 1);
        if (::connect(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
            close(fd);
            noteFailure("frame-relay-not-listening");
            return false;
        }
        m_socket = fd;
        appendLog("frame-relay-connected");
        return true;
    }

    void disconnectSocket()
    {
        if (m_socket >= 0) {
            close(m_socket);
            m_socket = -1;
        }
    }

    bool sendFrame(const FrameMessage &msg, int fd)
    {
        if (!connectSocket()) {
            return false;
        }
        if (fd < 0) {
            return sendAll(m_socket, &msg, sizeof(msg));
        }
        iovec iov{const_cast<FrameMessage *>(&msg), sizeof(msg)};
        std::array<char, CMSG_SPACE(sizeof(int))> control{};
        msghdr hdr{};
        hdr.msg_iov = &iov;
        hdr.msg_iovlen = 1;
        hdr.msg_control = control.data();
        hdr.msg_controllen = control.size();
        cmsghdr *cmsg = CMSG_FIRSTHDR(&hdr);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        std::memcpy(CMSG_DATA(cmsg), &fd, sizeof(fd));
        const ssize_t sent = sendmsg(m_socket, &hdr, MSG_NOSIGNAL);
        return sent == static_cast<ssize_t>(sizeof(msg));
    }

    void destroyExport()
    {
        if (m_image != EGL_NO_IMAGE_KHR) {
            if (m_destroyImage && m_display != EGL_NO_DISPLAY) {
                m_destroyImage(m_display, m_image);
            }
            m_image = EGL_NO_IMAGE_KHR;
        }
        if (m_texture) {
            glDeleteTextures(1, &m_texture);
            m_texture = 0;
        }
        m_width = m_height = 0;
    }

    bool ensureExport()
    {
        if (!initEglExport()) {
            return false;
        }

        const QSize size = effects->virtualScreenSize();
        if (size.isEmpty()) {
            noteFailure("virtual-screen-size-empty");
            return false;
        }
        if (m_texture && size.width() == m_width && size.height() == m_height) {
            return true;
        }

        destroyExport();
        m_width = size.width();
        m_height = size.height();

        glGenTextures(1, &m_texture);
        glBindTexture(GL_TEXTURE_2D, m_texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, m_width, m_height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindTexture(GL_TEXTURE_2D, 0);
        if (!m_texture) {
            noteFailure("gl-export-texture-create-failed");
            return false;
        }

        const EGLint attrs[] = {EGL_GL_TEXTURE_LEVEL_KHR, 0, EGL_NONE};
        m_image = m_createImage(m_display, eglGetCurrentContext(), EGL_GL_TEXTURE_2D_KHR,
                                reinterpret_cast<EGLClientBuffer>(static_cast<uintptr_t>(m_texture)), attrs);
        if (m_image == EGL_NO_IMAGE_KHR) {
            noteFailure("eglCreateImageKHR-failed-" + std::to_string(static_cast<int>(eglGetError())));
            destroyExport();
            return false;
        }

        int fourcc = 0;
        int planes = 0;
        EGLuint64KHR modifier = 0;
        if (!m_exportQuery(m_display, m_image, &fourcc, &planes, &modifier)) {
            noteFailure("eglExportDMABUFImageQueryMESA-failed-" + std::to_string(static_cast<int>(eglGetError())));
            destroyExport();
            return false;
        }
        if (planes != 1) {
            noteFailure("multi-plane-export-unsupported-planes=" + std::to_string(planes));
            destroyExport();
            return false;
        }
        int fd = -1;
        EGLint stride = 0;
        EGLint offset = 0;
        if (!m_export(m_display, m_image, &fd, &stride, &offset) || fd < 0) {
            noteFailure("eglExportDMABUFImageMESA-failed-" + std::to_string(static_cast<int>(eglGetError())));
            destroyExport();
            return false;
        }

        m_fourcc = static_cast<uint32_t>(fourcc);
        m_stride = stride;
        m_offset = offset;
        m_modifier = static_cast<uint64_t>(modifier);

        FrameMessage msg{};
        msg.magic = kMagic;
        msg.type = kImport;
        msg.width = static_cast<uint32_t>(m_width);
        msg.height = static_cast<uint32_t>(m_height);
        msg.fourcc = m_fourcc;
        msg.stride = static_cast<uint32_t>(m_stride);
        msg.offset = static_cast<uint32_t>(m_offset);
        msg.modifier = m_modifier;
        msg.serial = m_serial;
        const bool ok = sendFrame(msg, fd);
        close(fd);
        if (!ok) {
            noteFailure("dmabuf-import-message-send-failed");
            disconnectSocket();
            destroyExport();
            return false;
        }
        clearFailure();
        appendLog(
            "dmabuf-export-ready " + std::to_string(m_width) + "x" + std::to_string(m_height) +
            " stride=" + std::to_string(m_stride) + " fourcc=" + std::to_string(m_fourcc) +
            " modifier=" + std::to_string(m_modifier));
        return true;
    }
};

// Do not use the SUPPORTED factory variant here. KWin's plugin loader calls
// isSupported() before creating the effect; on the virtual backend that check
// can happen before OpenGL/Zink is fully established, which silently prevents
// construction even though the compositor becomes OpenGL-backed moments later.
// The effect itself performs capability checks at the first paint with a
// guaranteed-current context and reports the precise EGL/dma-buf failure.
KWIN_EFFECT_FACTORY(VesselOutputEffect, "vesseloutput.json")

} // namespace KWin

#include "vesseloutput.moc"
