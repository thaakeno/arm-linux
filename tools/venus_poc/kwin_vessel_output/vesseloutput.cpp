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
        m_display = eglGetCurrentDisplay();
        m_exportQuery = reinterpret_cast<PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC>(
            eglGetProcAddress("eglExportDMABUFImageQueryMESA"));
        m_export = reinterpret_cast<PFNEGLEXPORTDMABUFIMAGEMESAPROC>(
            eglGetProcAddress("eglExportDMABUFImageMESA"));
        effects->addRepaintFull();
    }

    ~VesselOutputEffect() override
    {
        destroyExport();
        if (m_socket >= 0) {
            close(m_socket);
        }
    }

    static bool supported()
    {
        const char *extensions = eglQueryString(eglGetCurrentDisplay(), EGL_EXTENSIONS);
        return effects->isOpenGLCompositing() && extensions &&
            std::strstr(extensions, "EGL_MESA_image_dma_buf_export") != nullptr;
    }

    void postPaintScreen() override
    {
        effects->postPaintScreen();
        if (!ensureExport()) {
            effects->addRepaintFull();
            return;
        }

        glBindTexture(GL_TEXTURE_2D, m_texture);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, m_width, m_height);
        glBindTexture(GL_TEXTURE_2D, 0);

        // Correctness-first explicit producer completion. No pixels touch the CPU;
        // this is replaced by sync_fd handoff once all deployed Mesa builds expose it.
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
            disconnectSocket();
        }
        effects->addRepaintFull();
    }

private:
    EGLDisplay m_display = EGL_NO_DISPLAY;
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

    bool connectSocket()
    {
        if (m_socket >= 0) {
            return true;
        }
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            return false;
        }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, kSocketPath, sizeof(addr.sun_path) - 1);
        if (connect(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
            close(fd);
            return false;
        }
        m_socket = fd;
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
            eglDestroyImageKHR(m_display, m_image);
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
        if (!m_exportQuery || !m_export || m_display == EGL_NO_DISPLAY) {
            return false;
        }
        const QSize size = effects->virtualScreenSize();
        if (size.isEmpty()) {
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

        const EGLint attrs[] = {EGL_GL_TEXTURE_LEVEL_KHR, 0, EGL_NONE};
        m_image = eglCreateImageKHR(m_display, eglGetCurrentContext(), EGL_GL_TEXTURE_2D_KHR,
                                    reinterpret_cast<EGLClientBuffer>(static_cast<uintptr_t>(m_texture)), attrs);
        if (m_image == EGL_NO_IMAGE_KHR) {
            destroyExport();
            return false;
        }

        int fourcc = 0;
        int planes = 0;
        EGLuint64KHR modifier = 0;
        if (!m_exportQuery(m_display, m_image, &fourcc, &planes, &modifier) || planes != 1) {
            destroyExport();
            return false;
        }
        int fd = -1;
        EGLint stride = 0;
        EGLint offset = 0;
        if (!m_export(m_display, m_image, &fd, &stride, &offset) || fd < 0) {
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
            disconnectSocket();
            destroyExport();
            return false;
        }
        return true;
    }
};

// Debian 12 ships KWin 5.27, where this macro takes four arguments:
// effect class, metadata JSON, supported body, enabled-by-default body.
KWIN_EFFECT_FACTORY_SUPPORTED_ENABLED(VesselOutputEffect,
                                      "vesseloutput.json",
                                      return VesselOutputEffect::supported();,
                                      return true;)

} // namespace KWin

#include "vesseloutput.moc"
