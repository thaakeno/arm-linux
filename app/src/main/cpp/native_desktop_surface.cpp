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
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

namespace {
constexpr int kPort = 47636;
std::mutex gMutex;
ANativeWindow* gWindow = nullptr;
std::vector<uint8_t> gFrame;
int gFrameW = 0, gFrameH = 0;
float gCursorX = .5f, gCursorY = .5f;
bool gCursorVisible = false;
std::atomic<bool> gRunning{false};

bool recvAll(int fd, void* dst, size_t len) {
    auto* p = static_cast<uint8_t*>(dst);
    while (len) {
        ssize_t n = recv(fd, p, len, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n; len -= static_cast<size_t>(n);
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
    std::string needle = std::string("\"") + key + "\":";
    auto p = s.find(needle);
    if (p == std::string::npos) return -1;
    p += needle.size();
    return std::atoi(s.c_str() + p);
}

void drawCursor(uint8_t* bits, int stridePx, int w, int h, int cx, int cy) {
    static const char* rows[] = {
        "##................", "#W#...............", "#WW#..............", "#WWW#.............",
        "#WWWW#............", "#WWWWW#...........", "#WWWWWW#..........", "#WWWWWWW#.........",
        "#WWWWWWWW#........", "#WWWW#####........", "#WWW#.............", "#WW#..............",
        "#W#...............", "##................", "..................", ".................."
    };
    for (int y=0; y<16; ++y) for (int x=0; x<18; ++x) {
        char v = rows[y][x]; if (v=='.') continue;
        int px=cx+x, py=cy+y; if(px<0||py<0||px>=w||py>=h) continue;
        uint8_t* d = bits + (static_cast<size_t>(py)*stridePx + px)*4;
        if (v=='W') { d[0]=255; d[1]=255; d[2]=255; d[3]=255; }
        else { d[0]=20; d[1]=20; d[2]=20; d[3]=255; }
    }
}

void postFrameLocked() {
    if (!gWindow || gFrame.empty() || gFrameW <= 0 || gFrameH <= 0) return;
    ANativeWindow_setBuffersGeometry(gWindow, gFrameW, gFrameH, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer b{};
    if (ANativeWindow_lock(gWindow, &b, nullptr) != 0 || !b.bits) return;
    const int copyW = std::min(gFrameW, b.width);
    const int copyH = std::min(gFrameH, b.height);
    auto* dst = static_cast<uint8_t*>(b.bits);
    for (int y=0; y<copyH; ++y) {
        std::memcpy(dst + static_cast<size_t>(y)*b.stride*4,
                    gFrame.data() + static_cast<size_t>(y)*gFrameW*4,
                    static_cast<size_t>(copyW)*4);
    }
    if (gCursorVisible) {
        int cx = std::clamp(static_cast<int>(gCursorX * std::max(1, copyW-1)), 0, std::max(0, copyW-1));
        int cy = std::clamp(static_cast<int>(gCursorY * std::max(1, copyH-1)), 0, std::max(0, copyH-1));
        drawCursor(dst, b.stride, copyW, copyH, cx, cy);
    }
    ANativeWindow_unlockAndPost(gWindow);
}

int connectFrames() {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) return -1;
    int one=1, sz=4*1024*1024;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(s, SOL_SOCKET, SO_RCVBUF, &sz, sizeof(sz));
    sockaddr_in a{}; a.sin_family=AF_INET; a.sin_port=htons(kPort); a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if (connect(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0) { close(s); return -1; }
    return s;
}

void streamLoop() {
    while (gRunning.load()) {
        int fd = connectFrames();
        if (fd < 0) { std::this_thread::sleep_for(std::chrono::milliseconds(25)); continue; }
        std::string line;
        if (!recvLine(fd, line) || line.find("\"magic\":\"VFRM2\"") == std::string::npos) { close(fd); continue; }
        int w=jsonInt(line,"width"), h=jsonInt(line,"height");
        if (w<320||h<240||w>7680||h>4320) { close(fd); continue; }
        {
            std::lock_guard<std::mutex> lk(gMutex);
            gFrameW=w; gFrameH=h; gFrame.assign(static_cast<size_t>(w)*h*4,0);
        }
        while (gRunning.load()) {
            uint8_t head[13]; if(!recvAll(fd,head,sizeof(head))) break;
            char kind=static_cast<char>(head[0]);
            uint32_t value; std::memcpy(&value,head+9,4); value=ntohl(value);
            if (kind=='F') {
                if (value != static_cast<uint32_t>(w*h*4)) break;
                std::vector<uint8_t> tmp(value); if(!recvAll(fd,tmp.data(),tmp.size())) break;
                std::lock_guard<std::mutex> lk(gMutex); gFrame.swap(tmp); postFrameLocked();
            } else if (kind=='D') {
                if (value>8192) break;
                bool bad=false;
                std::lock_guard<std::mutex> lk(gMutex);
                for(uint32_t i=0;i<value;i++) {
                    uint8_t th[12]; if(!recvAll(fd,th,sizeof(th))){bad=true;break;}
                    uint16_t x,y,tw,thh; uint32_t bytes;
                    std::memcpy(&x,th,2); std::memcpy(&y,th+2,2); std::memcpy(&tw,th+4,2); std::memcpy(&thh,th+6,2); std::memcpy(&bytes,th+8,4);
                    x=ntohs(x); y=ntohs(y); tw=ntohs(tw); thh=ntohs(thh); bytes=ntohl(bytes);
                    if(!tw||!thh||x+tw>w||y+thh>h||bytes!=static_cast<uint32_t>(tw)*thh*4){bad=true;break;}
                    std::vector<uint8_t> tile(bytes); if(!recvAll(fd,tile.data(),tile.size())){bad=true;break;}
                    for(uint16_t yy=0;yy<thh;yy++) std::memcpy(gFrame.data()+((static_cast<size_t>(y+yy)*w+x)*4),tile.data()+static_cast<size_t>(yy)*tw*4,static_cast<size_t>(tw)*4);
                }
                if(bad) break;
                postFrameLocked();
            } else break;
        }
        close(fd);
    }
}

void ensureThread() {
    bool expected=false;
    if (gRunning.compare_exchange_strong(expected,true)) std::thread(streamLoop).detach();
}
}

extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VncFramebufferView_nativeAttach(JNIEnv* env,jobject,jobject surface) {
    ANativeWindow* nw = ANativeWindow_fromSurface(env, surface);
    if (!nw) return;
    {
        std::lock_guard<std::mutex> lk(gMutex);
        if (gWindow) ANativeWindow_release(gWindow);
        gWindow=nw;
        postFrameLocked();
    }
    ensureThread();
}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VncFramebufferView_nativeDetach(JNIEnv*,jobject) {
    std::lock_guard<std::mutex> lk(gMutex);
    if(gWindow){ANativeWindow_release(gWindow);gWindow=nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VncFramebufferView_nativeSetCursorVisible(JNIEnv*,jobject,jboolean visible) {
    std::lock_guard<std::mutex> lk(gMutex); gCursorVisible=visible; postFrameLocked();
}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VncFramebufferView_nativeCursorAbsolute(JNIEnv*,jobject,jfloat x,jfloat y) {
    std::lock_guard<std::mutex> lk(gMutex); gCursorX=std::clamp(float(x),0.f,1.f); gCursorY=std::clamp(float(y),0.f,1.f); if(gCursorVisible) postFrameLocked();
}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VncFramebufferView_nativeCursorRelative(JNIEnv*,jobject,jfloat dx,jfloat dy) {
    std::lock_guard<std::mutex> lk(gMutex);
    if(gFrameW>0) gCursorX=std::clamp(gCursorX+float(dx)/gFrameW,0.f,1.f);
    if(gFrameH>0) gCursorY=std::clamp(gCursorY+float(dy)/gFrameH,0.f,1.f);
    if(gCursorVisible) postFrameLocked();
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }
