#include <jni.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <sys/socket.h>
#include <sys/stat.h>
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
constexpr uint16_t BTN_LEFT = 0x110;
constexpr int TOUCH = 0;
constexpr int POINTER = 1;
constexpr int KEYBOARD = 2;

#pragma pack(push, 1)
struct WireEvent {
    uint16_t type;
    uint16_t code;
    uint32_t value;
};
#pragma pack(pop)
static_assert(sizeof(WireEvent) == 8);

uint32_t bits(int32_t v) { return static_cast<uint32_t>(v); }

struct CrosvmInputBridge {
    std::mutex lock;
    std::array<std::string, 3> paths;
    std::array<int, 3> servers{{-1, -1, -1}};
    std::array<int, 3> peers{{-1, -1, -1}};
    std::array<std::thread, 3> accept_threads;
    std::atomic<uint64_t> generation{1};
    std::string state = "crosvm-input-not-configured";

    ~CrosvmInputBridge() { stop(); }

    static int listen_path(const std::string& path) {
        if (path.empty()) return -1;
        sockaddr_un addr{};
        if (path.size() >= sizeof(addr.sun_path)) return -1;
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return -1;
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);
        unlink(path.c_str());
        if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 || listen(fd, 2) != 0) {
            close(fd);
            unlink(path.c_str());
            return -1;
        }
        chmod(path.c_str(), 0660);
        return fd;
    }

    void stop() {
        generation.fetch_add(1);
        std::array<int, 3> old_servers;
        {
            std::lock_guard<std::mutex> g(lock);
            old_servers = servers;
            for (int& fd : servers) fd = -1;
            for (int& fd : peers) {
                if (fd >= 0) {
                    shutdown(fd, SHUT_RDWR);
                    close(fd);
                }
                fd = -1;
            }
        }
        for (int fd : old_servers) {
            if (fd >= 0) {
                shutdown(fd, SHUT_RDWR);
                close(fd);
            }
        }
        for (auto& t : accept_threads) {
            if (t.joinable()) t.join();
        }
        for (const auto& path : paths) if (!path.empty()) unlink(path.c_str());
    }

    bool configure(std::array<std::string, 3> next_paths) {
        stop();
        paths = std::move(next_paths);
        const uint64_t gen = generation.load();
        {
            std::lock_guard<std::mutex> g(lock);
            for (int i = 0; i < 3; ++i) {
                servers[i] = listen_path(paths[i]);
                if (servers[i] < 0) {
                    state = "crosvm-input-listen-failed:" + paths[i] + ":" + std::strerror(errno);
                    return false;
                }
            }
            state = "crosvm-input-listening";
        }
        for (int which = 0; which < 3; ++which) {
            const int server = servers[which];
            accept_threads[which] = std::thread([this, which, server, gen] {
                while (generation.load() == gen) {
                    const int peer = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
                    if (peer < 0) {
                        if (generation.load() != gen) break;
                        if (errno == EINTR) continue;
                        break;
                    }
                    std::lock_guard<std::mutex> g(lock);
                    if (generation.load() != gen) {
                        close(peer);
                        break;
                    }
                    if (peers[which] >= 0) close(peers[which]);
                    peers[which] = peer;
                    state = "crosvm-input-connected";
                }
            });
        }
        return true;
    }

    bool send_events(int which, std::initializer_list<WireEvent> body) {
        std::vector<WireEvent> events(body);
        events.push_back(WireEvent{EV_SYN, SYN_REPORT, 0});
        std::lock_guard<std::mutex> g(lock);
        if (which < 0 || which >= 3 || peers[which] < 0) return false;
        const uint8_t* p = reinterpret_cast<const uint8_t*>(events.data());
        size_t left = events.size() * sizeof(WireEvent);
        while (left > 0) {
            const ssize_t n = send(peers[which], p, left, MSG_NOSIGNAL);
            if (n > 0) {
                p += n;
                left -= static_cast<size_t>(n);
                continue;
            }
            if (n < 0 && errno == EINTR) continue;
            close(peers[which]);
            peers[which] = -1;
            state = std::string("crosvm-input-send-failed:") + std::strerror(errno);
            return false;
        }
        return true;
    }

    bool absolute(int x, int y, bool down) {
        return send_events(TOUCH, {
            WireEvent{EV_ABS, ABS_X, bits(x)},
            WireEvent{EV_ABS, ABS_Y, bits(y)},
            WireEvent{EV_KEY, BTN_LEFT, down ? 1u : 0u},
        });
    }
    bool relative(int x, int y) {
        return send_events(POINTER, {
            WireEvent{EV_REL, REL_X, bits(x)},
            WireEvent{EV_REL, REL_Y, bits(y)},
        });
    }
    bool button(int code, bool down) {
        return send_events(POINTER, {WireEvent{EV_KEY, static_cast<uint16_t>(code), down ? 1u : 0u}});
    }
    bool scroll(int x, int y) {
        return send_events(POINTER, {
            WireEvent{EV_REL, REL_HWHEEL, bits(x)},
            WireEvent{EV_REL, REL_WHEEL, bits(y)},
        });
    }
    bool key(int code, bool down) {
        return send_events(KEYBOARD, {WireEvent{EV_KEY, static_cast<uint16_t>(code), down ? 1u : 0u}});
    }
};

CrosvmInputBridge bridge;

std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    std::string out = raw ? raw : "";
    if (raw) env->ReleaseStringUTFChars(value, raw);
    return out;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmConfigure(
    JNIEnv* env, jclass, jstring touch, jstring pointer, jstring keyboard) {
    return bridge.configure({
        from_jstring(env, touch),
        from_jstring(env, pointer),
        from_jstring(env, keyboard),
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmAbsolute(
    JNIEnv*, jclass, jint x, jint y, jboolean down) {
    return bridge.absolute(x, y, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmRelative(
    JNIEnv*, jclass, jint x, jint y) {
    return bridge.relative(x, y) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmButton(
    JNIEnv*, jclass, jint code, jboolean down) {
    return bridge.button(code, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmScroll(
    JNIEnv*, jclass, jint x, jint y) {
    return bridge.scroll(x, y) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmKey(
    JNIEnv*, jclass, jint code, jboolean down) {
    return bridge.key(code, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeCrosvmStatus(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(bridge.lock);
    return env->NewStringUTF(bridge.state.c_str());
}
