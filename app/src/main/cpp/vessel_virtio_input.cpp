#include <jni.h>

#include <array>
#include <cerrno>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <initializer_list>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr uint16_t EV_SYN = 0;
constexpr uint16_t EV_KEY = 1;
constexpr uint16_t EV_REL = 2;
constexpr uint16_t EV_ABS = 3;
constexpr uint16_t SYN_REPORT = 0;
constexpr uint16_t REL_X = 0;
constexpr uint16_t REL_Y = 1;
constexpr uint16_t REL_HWHEEL = 6;
constexpr uint16_t REL_WHEEL = 8;
constexpr uint16_t ABS_X = 0;
constexpr uint16_t ABS_Y = 1;
constexpr int TOUCH = 0;
constexpr int POINTER = 1;
constexpr int KEYBOARD = 2;
constexpr size_t MAX_QUEUE = 256;

#pragma pack(push, 1)
struct WireEvent {
    uint16_t type;
    uint16_t code;
    uint32_t value;
};
#pragma pack(pop)
static_assert(sizeof(WireEvent) == 8);

uint32_t bits(int32_t value) { return static_cast<uint32_t>(value); }
int32_t signed_value(uint32_t value) { return static_cast<int32_t>(value); }

enum class PacketKind { Generic, Relative, Scroll, AbsoluteMove };
struct Packet {
    int which = POINTER;
    PacketKind kind = PacketKind::Generic;
    bool touch_down = false;
    std::vector<WireEvent> events;
};

struct InputSender {
    std::mutex queue_lock;
    std::condition_variable cv;
    std::deque<Packet> queue;
    bool stopping = false;
    std::thread worker;

    std::mutex socket_lock;
    std::array<std::string, 3> paths;
    std::array<int, 3> fds{{-1, -1, -1}};
    std::string status = "not-configured";

    InputSender() : worker([this] { loop(); }) {}
    ~InputSender() {
        {
            std::lock_guard<std::mutex> g(queue_lock);
            stopping = true;
        }
        cv.notify_all();
        if (worker.joinable()) worker.join();
        std::lock_guard<std::mutex> g(socket_lock);
        closeAllLocked();
    }

    void closeAllLocked() {
        for (int& fd : fds) {
            if (fd >= 0) close(fd);
            fd = -1;
        }
    }

    void configure(std::string touch, std::string pointer, std::string keyboard) {
        {
            std::lock_guard<std::mutex> g(socket_lock);
            closeAllLocked();
            paths[TOUCH] = std::move(touch);
            paths[POINTER] = std::move(pointer);
            paths[KEYBOARD] = std::move(keyboard);
            status = "configured-async";
        }
        {
            std::lock_guard<std::mutex> g(queue_lock);
            queue.clear();
        }
    }

    bool configured(int which) {
        std::lock_guard<std::mutex> g(socket_lock);
        return which >= 0 && which < static_cast<int>(paths.size()) && !paths[which].empty();
    }

    static std::vector<WireEvent> withSync(std::initializer_list<WireEvent> body) {
        std::vector<WireEvent> out(body);
        out.push_back(WireEvent{EV_SYN, SYN_REPORT, 0});
        return out;
    }

    bool enqueue(Packet packet) {
        if (!configured(packet.which)) return false;
        {
            std::lock_guard<std::mutex> g(queue_lock);
            if (!queue.empty()) {
                Packet& last = queue.back();
                if (packet.kind == PacketKind::Relative && last.kind == PacketKind::Relative &&
                    last.which == packet.which && last.events.size() >= 3 && packet.events.size() >= 3) {
                    const int32_t dx = signed_value(last.events[0].value) + signed_value(packet.events[0].value);
                    const int32_t dy = signed_value(last.events[1].value) + signed_value(packet.events[1].value);
                    last.events[0].value = bits(dx);
                    last.events[1].value = bits(dy);
                    cv.notify_one();
                    return true;
                }
                if (packet.kind == PacketKind::Scroll && last.kind == PacketKind::Scroll &&
                    last.which == packet.which && last.events.size() >= 3 && packet.events.size() >= 3) {
                    const int32_t x = signed_value(last.events[0].value) + signed_value(packet.events[0].value);
                    const int32_t y = signed_value(last.events[1].value) + signed_value(packet.events[1].value);
                    last.events[0].value = bits(x);
                    last.events[1].value = bits(y);
                    cv.notify_one();
                    return true;
                }
                if (packet.kind == PacketKind::AbsoluteMove && packet.touch_down &&
                    last.kind == PacketKind::AbsoluteMove && last.touch_down && last.which == packet.which) {
                    last = std::move(packet);
                    cv.notify_one();
                    return true;
                }
            }
            if (queue.size() >= MAX_QUEUE) {
                auto it = queue.begin();
                while (it != queue.end() && it->kind == PacketKind::Generic) ++it;
                if (it != queue.end()) queue.erase(it);
                else return false;
            }
            queue.push_back(std::move(packet));
        }
        cv.notify_one();
        return true;
    }

    bool connectOneLocked(int which) {
        if (which < 0 || which >= static_cast<int>(fds.size()) || paths[which].empty()) return false;
        if (fds[which] >= 0) return true;
        int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (fd < 0) {
            status = std::string("socket-failed:") + std::strerror(errno);
            return false;
        }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (paths[which].size() >= sizeof(addr.sun_path)) {
            close(fd);
            status = "control-path-too-long";
            return false;
        }
        std::strncpy(addr.sun_path, paths[which].c_str(), sizeof(addr.sun_path) - 1);
        if (connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
            status = std::string("connect-failed:") + std::strerror(errno);
            close(fd);
            return false;
        }
        fds[which] = fd;
        status = "virtio-input-ready-async";
        return true;
    }

    bool sendOnceLocked(int which, const std::vector<WireEvent>& events) {
        if (!connectOneLocked(which)) return false;
        const size_t bytes = events.size() * sizeof(WireEvent);
        for (int attempt = 0; attempt < 3; ++attempt) {
            const ssize_t sent = ::send(fds[which], events.data(), bytes, MSG_NOSIGNAL | MSG_DONTWAIT);
            if (sent == static_cast<ssize_t>(bytes)) return true;
            if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                pollfd pfd{fds[which], POLLOUT, 0};
                (void)poll(&pfd, 1, 4);
                continue;
            }
            break;
        }
        if (fds[which] >= 0) close(fds[which]);
        fds[which] = -1;
        if (!connectOneLocked(which)) return false;
        const ssize_t sent = ::send(fds[which], events.data(), bytes, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (sent != static_cast<ssize_t>(bytes)) {
            status = std::string("send-failed:") + std::strerror(errno);
            if (fds[which] >= 0) close(fds[which]);
            fds[which] = -1;
            return false;
        }
        return true;
    }

    void loop() {
        for (;;) {
            Packet packet;
            {
                std::unique_lock<std::mutex> lk(queue_lock);
                cv.wait(lk, [this] { return stopping || !queue.empty(); });
                if (stopping && queue.empty()) return;
                packet = std::move(queue.front());
                queue.pop_front();
            }
            std::lock_guard<std::mutex> sockets(socket_lock);
            (void)sendOnceLocked(packet.which, packet.events);
        }
    }

    bool relative(int dx, int dy) {
        return enqueue(Packet{POINTER, PacketKind::Relative, false,
            withSync({WireEvent{EV_REL, REL_X, bits(dx)}, WireEvent{EV_REL, REL_Y, bits(dy)}})});
    }
    bool scroll(int x, int y) {
        return enqueue(Packet{POINTER, PacketKind::Scroll, false,
            withSync({WireEvent{EV_REL, REL_HWHEEL, bits(x)}, WireEvent{EV_REL, REL_WHEEL, bits(y)}})});
    }
    bool absolute(int x, int y, bool down) {
        return enqueue(Packet{TOUCH, PacketKind::AbsoluteMove, down,
            withSync({WireEvent{EV_ABS, ABS_X, bits(x)}, WireEvent{EV_ABS, ABS_Y, bits(y)},
                      WireEvent{EV_KEY, 0x14a, down ? 1u : 0u}})});
    }
    bool generic(int which, std::initializer_list<WireEvent> body) {
        return enqueue(Packet{which, PacketKind::Generic, false, withSync(body)});
    }
};

InputSender gInput;

std::string fromJString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeConfigure(
    JNIEnv* env, jclass, jstring touch, jstring pointer, jstring keyboard) {
    gInput.configure(fromJString(env, touch), fromJString(env, pointer), fromJString(env, keyboard));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeAbsolute(
    JNIEnv*, jclass, jint x, jint y, jboolean down) {
    return gInput.absolute(x, y, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeRelative(
    JNIEnv*, jclass, jint dx, jint dy) {
    return gInput.relative(dx, dy) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeButton(
    JNIEnv*, jclass, jint code, jboolean down) {
    return gInput.generic(POINTER, {WireEvent{EV_KEY, static_cast<uint16_t>(code), down ? 1u : 0u}}) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeScroll(
    JNIEnv*, jclass, jint x, jint y) {
    return gInput.scroll(x, y) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeKey(
    JNIEnv*, jclass, jint code, jboolean down) {
    return gInput.generic(KEYBOARD, {WireEvent{EV_KEY, static_cast<uint16_t>(code), down ? 1u : 0u}}) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeStatus(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(gInput.socket_lock);
    return env->NewStringUTF(gInput.status.c_str());
}
