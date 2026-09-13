#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

namespace {
constexpr int kPort = 47636;
std::mutex gMutex;
std::condition_variable gCv;
ANativeWindow* gWindow = nullptr;
std::vector<uint8_t> gFrame;
int gFrameW = 0, gFrameH = 0;
bool gDirty = false;
std::atomic<bool> gRunning{false};

bool recvAll(int fd, void* dst, size_t len) {
    auto* p = static_cast<uint8_t*>(dst);
    while (len) {
        ssize_t n = recv(fd, p, len, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

bool recvLine(int fd, std::string& out) {
    out.clear();
    char c;
    while (out.size() < 4096) {
        if (!recvAll(fd, &c, 1)) return false;
        if (c == '\n') return true;
        out.push_back(c);
    }
    return false;
}

int jsonInt(const std::string& s, const char* key) {
    const std::string needle = std::string("\"") + key + "\":";
    auto p = s.find(needle);
    if (p == std::string::npos) return -1;
    p += needle.size();
    return std::atoi(s.c_str() + p);
}

void postFrameLocked() {
    if (!gWindow || gFrame.empty() || gFrameW <= 0 || gFrameH <= 0) return;

    ANativeWindow_Buffer b{};
    if (ANativeWindow_lock(gWindow, &b, nullptr) != 0 || !b.bits) return;

    auto* dst = static_cast<uint8_t*>(b.bits);
    const int outW = b.width;
    const int outH = b.height;
    if (outW <= 0 || outH <= 0) {
        ANativeWindow_unlockAndPost(gWindow);
        return;
    }

    // The SurfaceView can be tall in the in-app desktop page. Keep the Linux
    // desktop's native aspect ratio instead of stretching it to the view.
    const double sx = static_cast<double>(outW) / static_cast<double>(gFrameW);
    const double sy = static_cast<double>(outH) / static_cast<double>(gFrameH);
    const double scale = std::min(sx, sy);
    const int drawW = std::max(1, static_cast<int>(gFrameW * scale));
    const int drawH = std::max(1, static_cast<int>(gFrameH * scale));
    const int left = (outW - drawW) / 2;
    const int top = (outH - drawH) / 2;

    // Clear letterbox regions.
    for (int y = 0; y < outH; ++y) {
        std::memset(dst + static_cast<size_t>(y) * b.stride * 4, 0, static_cast<size_t>(outW) * 4);
    }

    // Scale directly into the BufferQueue buffer. The source frame stays in a
    // persistent native shadow buffer, so Surface recreation does not blank the
    // desktop while waiting for a new full guest frame.
    for (int y = 0; y < drawH; ++y) {
        const int srcY = std::min(gFrameH - 1, static_cast<int>((static_cast<int64_t>(y) * gFrameH) / drawH));
        uint8_t* out = dst + (static_cast<size_t>(top + y) * b.stride + left) * 4;
        const uint8_t* srcRow = gFrame.data() + static_cast<size_t>(srcY) * gFrameW * 4;
        for (int x = 0; x < drawW; ++x) {
            const int srcX = std::min(gFrameW - 1, static_cast<int>((static_cast<int64_t>(x) * gFrameW) / drawW));
            std::memcpy(out + static_cast<size_t>(x) * 4, srcRow + static_cast<size_t>(srcX) * 4, 4);
        }
    }

    ANativeWindow_unlockAndPost(gWindow);
}

int connectFrames() {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) return -1;
    int one = 1;
    int sz = 4 * 1024 * 1024;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(s, SOL_SOCKET, SO_RCVBUF, &sz, sizeof(sz));
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(kPort);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0) {
        close(s);
        return -1;
    }
    return s;
}

void streamLoop() {
    while (gRunning.load()) {
        int fd = connectFrames();
        if (fd < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
            continue;
        }

        std::string line;
        if (!recvLine(fd, line) || line.find("\"magic\":\"VFRM2\"") == std::string::npos) {
            close(fd);
            continue;
        }
        const int w = jsonInt(line, "width");
        const int h = jsonInt(line, "height");
        if (w < 320 || h < 240 || w > 7680 || h > 4320) {
            close(fd);
            continue;
        }

        {
            std::lock_guard<std::mutex> lk(gMutex);
            gFrameW = w;
            gFrameH = h;
            gFrame.assign(static_cast<size_t>(w) * h * 4, 0);
        }

        while (gRunning.load()) {
            uint8_t head[13];
            if (!recvAll(fd, head, sizeof(head))) break;
            const char kind = static_cast<char>(head[0]);
            uint32_t value;
            std::memcpy(&value, head + 9, 4);
            value = ntohl(value);

            if (kind == 'F') {
                if (value != static_cast<uint32_t>(w * h * 4)) break;
                std::vector<uint8_t> tmp(value);
                if (!recvAll(fd, tmp.data(), tmp.size())) break;
                std::lock_guard<std::mutex> lk(gMutex);
                gFrame.swap(tmp);
                gDirty = true;
                gCv.notify_one();
            } else if (kind == 'D') {
                if (value > 8192) break;
                bool bad = false;
                std::lock_guard<std::mutex> lk(gMutex);
                for (uint32_t i = 0; i < value; ++i) {
                    uint8_t th[12];
                    if (!recvAll(fd, th, sizeof(th))) {
                        bad = true;
                        break;
                    }
                    uint16_t x, y, tw, thh;
                    uint32_t bytes;
                    std::memcpy(&x, th, 2);
                    std::memcpy(&y, th + 2, 2);
                    std::memcpy(&tw, th + 4, 2);
                    std::memcpy(&thh, th + 6, 2);
                    std::memcpy(&bytes, th + 8, 4);
                    x = ntohs(x);
                    y = ntohs(y);
                    tw = ntohs(tw);
                    thh = ntohs(thh);
                    bytes = ntohl(bytes);
                    if (!tw || !thh || x + tw > w || y + thh > h || bytes != static_cast<uint32_t>(tw) * thh * 4) {
                        bad = true;
                        break;
                    }
                    std::vector<uint8_t> tile(bytes);
                    if (!recvAll(fd, tile.data(), tile.size())) {
                        bad = true;
                        break;
                    }
                    for (uint16_t yy = 0; yy < thh; ++yy) {
                        std::memcpy(
                            gFrame.data() + ((static_cast<size_t>(y + yy) * w + x) * 4),
                            tile.data() + static_cast<size_t>(yy) * tw * 4,
                            static_cast<size_t>(tw) * 4
                        );
                    }
                }
                if (bad) break;
                gDirty = true;
                gCv.notify_one();
            } else {
                break;
            }
        }
        close(fd);
    }
}

void renderLoop() {
    using namespace std::chrono_literals;
    std::unique_lock<std::mutex> lk(gMutex);
    while (gRunning.load()) {
        gCv.wait_for(lk, 8ms, [] { return gDirty || !gRunning.load(); });
        if (!gRunning.load()) break;
        if (!gDirty) continue;
        gDirty = false;
        postFrameLocked();
    }
}

void ensureThreads() {
    bool expected = false;
    if (gRunning.compare_exchange_strong(expected, true)) {
        std::thread(streamLoop).detach();
        std::thread(renderLoop).detach();
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VncFramebufferView_nativeAttach(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* nw = ANativeWindow_fromSurface(env, surface);
    if (!nw) return;
    {
        std::lock_guard<std::mutex> lk(gMutex);
        if (gWindow) ANativeWindow_release(gWindow);
        gWindow = nw;
        // Keep the Java SurfaceView's real dimensions; only request RGBA.
        ANativeWindow_setBuffersGeometry(gWindow, 0, 0, WINDOW_FORMAT_RGBA_8888);
        gDirty = true;
        gCv.notify_one();
    }
    ensureThreads();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VncFramebufferView_nativeDetach(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gMutex);
    if (gWindow) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
