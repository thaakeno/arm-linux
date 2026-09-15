#include <jni.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include <mutex>
#include <string>
#include <vector>

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

#pragma pack(push, 1)
struct WireEvent {
    uint16_t type;
    uint16_t code;
    uint32_t value;
};
#pragma pack(pop)
static_assert(sizeof(WireEvent) == 8);

struct InputSender {
    std::mutex lock;
    std::array<std::string, 3> paths;
    std::array<int, 3> fds{{-1, -1, -1}};
    std::string status = "not-configured";

    ~InputSender() { closeAll(); }

    void closeAll() {
        for (int& fd : fds) {
            if (fd >= 0) close(fd);
            fd = -1;
        }
    }

    void configure(std::string touch, std::string pointer, std::string keyboard) {
        std::lock_guard<std::mutex> g(lock);
        closeAll();
        paths[TOUCH] = std::move(touch);
        paths[POINTER] = std::move(pointer);
        paths[KEYBOARD] = std::move(keyboard);
        status = "configured";
    }

    bool connectOne(int which) {
        if (which < 0 || which >= static_cast<int>(fds.size()) || paths[which].empty()) return false;
        if (fds[which] >= 0) return true;
        int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
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
        status = "virtio-input-ready";
        return true;
    }

    bool sendPacket(int which, const WireEvent* events, size_t count) {
        std::lock_guard<std::mutex> g(lock);
        if (!connectOne(which)) return false;
        const size_t bytes = count * sizeof(WireEvent);
        ssize_t sent = send(fds[which], events, bytes, MSG_NOSIGNAL);
        if (sent == static_cast<ssize_t>(bytes)) return true;
        if (fds[which] >= 0) close(fds[which]);
        fds[which] = -1;
        if (!connectOne(which)) return false;
        sent = send(fds[which], events, bytes, MSG_NOSIGNAL);
        if (sent != static_cast<ssize_t>(bytes)) {
            status = std::string("send-failed:") + std::strerror(errno);
            if (fds[which] >= 0) close(fds[which]);
            fds[which] = -1;
            return false;
        }
        return true;
    }

    bool send(int which, std::initializer_list<WireEvent> body) {
        std::vector<WireEvent> packet(body);
        packet.push_back(WireEvent{EV_SYN, SYN_REPORT, 0});
        return sendPacket(which, packet.data(), packet.size());
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

uint32_t bits(jint value) { return static_cast<uint32_t>(static_cast<int32_t>(value)); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeConfigure(
    JNIEnv* env, jclass, jstring touch, jstring pointer, jstring keyboard) {
    gInput.configure(fromJString(env, touch), fromJString(env, pointer), fromJString(env, keyboard));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeAbsolute(
    JNIEnv*, jclass, jint x, jint y, jboolean down) {
    return gInput.send(TOUCH, {
        WireEvent{EV_ABS, ABS_X, bits(x)},
        WireEvent{EV_ABS, ABS_Y, bits(y)},
        WireEvent{EV_KEY, 0x14a, down ? 1u : 0u},
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeRelative(
    JNIEnv*, jclass, jint dx, jint dy) {
    return gInput.send(POINTER, {
        WireEvent{EV_REL, REL_X, bits(dx)},
        WireEvent{EV_REL, REL_Y, bits(dy)},
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeButton(
    JNIEnv*, jclass, jint code, jboolean down) {
    return gInput.send(POINTER, {
        WireEvent{EV_KEY, static_cast<uint16_t>(code), down ? 1u : 0u},
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeScroll(
    JNIEnv*, jclass, jint x, jint y) {
    return gInput.send(POINTER, {
        WireEvent{EV_REL, REL_HWHEEL, bits(x)},
        WireEvent{EV_REL, REL_WHEEL, bits(y)},
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeKey(
    JNIEnv*, jclass, jint code, jboolean down) {
    return gInput.send(KEYBOARD, {
        WireEvent{EV_KEY, static_cast<uint16_t>(code), down ? 1u : 0u},
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselVirtioInput_nativeStatus(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(gInput.lock);
    return env->NewStringUTF(gInput.status.c_str());
}
