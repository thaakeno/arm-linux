#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>
#include "vessel_proroot_surfacecontrol.h"

#include <android/hardware_buffer.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

constexpr const char* TAG = "VesselProrootDisplay";
constexpr uint32_t CTRL_PRODUCER_HELLO = 2;
constexpr uint32_t CTRL_SCREEN_INFO = 7;
constexpr uint32_t CTRL_PICKUP_FDS = 9;
constexpr uint32_t CTRL_FDS_READY = 10;

constexpr uint32_t DATA_INPUT_EVENT = 102;
constexpr uint32_t DATA_OUTPUT_EVENT = 103;
constexpr uint32_t DATA_BUFS_READY = 200;

constexpr uint32_t INPUT_TOUCH = 1;
constexpr uint32_t INPUT_KEY = 2;
constexpr uint32_t INPUT_POINTER_MOTION = 3;
constexpr uint32_t INPUT_POINTER_BUTTON = 4;
constexpr uint32_t INPUT_POINTER_AXIS = 5;
constexpr uint32_t INPUT_TOUCH_FRAME = 6;
constexpr uint32_t INPUT_DISPLAY_REFRESH = 7;
constexpr uint32_t INPUT_CLIPBOARD = 8;
constexpr uint32_t INPUT_TEXT = 9;

constexpr uint32_t OUTPUT_CLIPBOARD = 1;

constexpr uint32_t SURFACE_MAGIC = 0x42484156u;
constexpr uint32_t SURFACE_VERSION = 3;
constexpr uint32_t SURFACE_REGISTER = 1;
constexpr uint32_t SURFACE_FRAME = 2;
constexpr uint8_t SURFACE_FENCE_TAG = 0xF3;

constexpr uint32_t PIXEL_FORMAT_RGBA_8888 = 1;
constexpr int BUFFER_COUNT = 3;
constexpr size_t MAX_CLIPBOARD = 1024 * 1024;

#pragma pack(push, 1)
struct CtrlMsg {
    uint32_t type;
    uint32_t size;
};
struct DataMsg {
    uint32_t type;
    uint32_t size;
};
struct ScreenInfo {
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t refresh;
};
struct BufInfo {
    uint32_t stride;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint64_t modifier;
    uint32_t offset;
};
struct InputEvent {
    uint32_t type;
    union {
        struct { int32_t action; float x; float y; int32_t pointer_id; } touch;
        struct { int32_t action; int32_t keycode; int32_t pad[2]; } key;
        struct { float x; float y; float dx; float dy; } pointer_motion;
        struct { uint32_t button; int32_t pressed; int32_t pad[2]; } pointer_button;
        struct { uint32_t axis; float value; int32_t discrete; int32_t pad; } pointer_axis;
        struct { uint32_t refresh_mhz; uint32_t pad[3]; } display;
        struct { uint32_t size; uint32_t pad[3]; } clipboard;
        struct { uint32_t size; uint32_t pad[3]; } text_input;
        uint32_t padding[4];
    };
};
struct OutputEvent {
    uint32_t type;
    union {
        struct { uint32_t size; uint32_t pad[3]; } clipboard;
        uint32_t padding[4];
    };
};
struct SurfaceMsg {
    uint32_t magic;
    uint32_t version;
    uint32_t type;
    uint32_t scanout_id;
    uint32_t slot;
    uint32_t serial;
    uint32_t width;
    uint32_t height;
};
struct SurfaceAck {
    uint32_t magic;
    uint32_t version;
    uint32_t scanout_id;
    uint32_t slot;
    uint32_t serial;
    uint32_t ok;
};
#pragma pack(pop)

static_assert(sizeof(InputEvent) == 20, "Anland-compatible InputEvent ABI changed");
static_assert(sizeof(BufInfo) == 28, "Anland-compatible BufInfo ABI changed");

enum class SlotState : uint8_t {
    FREE,
    RENDERING,
    PRESENTED,
};

struct ReleaseEvent {
    uint32_t slot = 0;
    uint64_t generation = 0;
    int fence_fd = -1;
};

void logi(const std::string& s) { __android_log_print(ANDROID_LOG_INFO, TAG, "%s", s.c_str()); }
void loge(const std::string& s) { __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", s.c_str()); }

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

bool recv_all(int fd, void* data, size_t size) {
    auto* p = static_cast<uint8_t*>(data);
    while (size > 0) {
        const ssize_t n = recv(fd, p, size, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += static_cast<size_t>(n);
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool send_fds(int fd, const void* data, size_t size, const int* fds, size_t count) {
    iovec io{const_cast<void*>(data), size};
    std::array<char, CMSG_SPACE(sizeof(int) * 8)> control{};
    msghdr msg{};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    if (count > 0) {
        if (count > 8) return false;
        msg.msg_control = control.data();
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * count);
        cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int) * count);
        std::memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * count);
    }
    ssize_t n;
    do {
        n = sendmsg(fd, &msg, MSG_NOSIGNAL);
    } while (n < 0 && errno == EINTR);
    return n == static_cast<ssize_t>(size);
}

int recv_one_fd_with_byte(int fd) {
    uint8_t byte = 0;
    iovec io{&byte, 1};
    std::array<char, CMSG_SPACE(sizeof(int))> control{};
    msghdr msg{};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control.data();
    msg.msg_controllen = control.size();
    ssize_t n;
    do {
        n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
    } while (n < 0 && errno == EINTR);
    if (n != 1) return -2;
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET &&
            cmsg->cmsg_type == SCM_RIGHTS &&
            cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int result = -1;
            std::memcpy(&result, CMSG_DATA(cmsg), sizeof(result));
            return result;
        }
    }
    return -1; // valid render-done message with no native fence
}

int extract_first_dmabuf(AHardwareBuffer* buffer) {
    int sv[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sv) != 0) return -1;

    int result = -1;
    if (AHardwareBuffer_sendHandleToUnixSocket(buffer, sv[0]) == 0) {
        std::array<uint8_t, 16384> payload{};
        std::array<char, CMSG_SPACE(sizeof(int) * 16)> control{};
        iovec io{payload.data(), payload.size()};
        msghdr msg{};
        msg.msg_iov = &io;
        msg.msg_iovlen = 1;
        msg.msg_control = control.data();
        msg.msg_controllen = control.size();
        const ssize_t n = recvmsg(sv[1], &msg, MSG_CMSG_CLOEXEC);
        if (n > 0) {
            for (cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
                if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
                const size_t bytes = cmsg->cmsg_len - CMSG_LEN(0);
                const size_t count = bytes / sizeof(int);
                const int* fds = reinterpret_cast<const int*>(CMSG_DATA(cmsg));
                for (size_t i = 0; i < count; ++i) {
                    if (result < 0) result = fds[i];
                    else close(fds[i]);
                }
            }
        }
    }
    close(sv[0]);
    close(sv[1]);
    return result;
}

int connect_abstract_presenter() {
    const std::string name = "vessel-ahb-" + std::to_string(getuid());
    for (int attempt = 0; attempt < 40; ++attempt) {
        const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return -1;
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) {
            close(fd);
            return -1;
        }
        addr.sun_path[0] = '\0';
        std::memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (connect(fd, reinterpret_cast<sockaddr*>(&addr), len) == 0) return fd;
        close(fd);
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
    }
    return -1;
}

bool send_surface_fence(int fd, int fence_fd) {
    uint8_t tag = SURFACE_FENCE_TAG;
    if (fence_fd < 0) return send_all(fd, &tag, 1);
    return send_fds(fd, &tag, 1, &fence_fd, 1);
}

class Bridge {
public:
    bool start(JNIEnv* env, jobject callback, const std::string& socket_path,
               uint32_t width, uint32_t height, float refresh) {
        stop(env);
        if (socket_path.empty() || width < 320 || height < 240 || refresh < 1.0f) return false;
        if (env->GetJavaVM(&jvm_) != JNI_OK) return false;
        callback_ = env->NewGlobalRef(callback);
        if (!callback_) return false;
        socket_path_ = socket_path;
        width_.store(width);
        height_.store(height);
        refresh_mhz_.store(static_cast<uint32_t>(refresh * 1000.0f + 0.5f));
        producer_connected_.store(false);
        producer_generation_.store(0);
        frames_presented_.store(0);
        frames_released_.store(0);
        last_frame_presented_ms_.store(0);
        set_disconnect_reason("none");
        zero_copy_ = vessel_proroot_surfacecontrol_available();
        if (zero_copy_) {
            AHardwareBuffer_Desc probe{};
            probe.width = width_.load();
            probe.height = height_.load();
            probe.layers = 1;
            probe.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            probe.usage =
                AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
            zero_copy_ = AHardwareBuffer_isSupported(&probe);
        }
        if (zero_copy_) {
            release_event_fd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
            if (release_event_fd_ < 0) {
                env->DeleteGlobalRef(callback_);
                callback_ = nullptr;
                return false;
            }
        }
        running_.store(true);
        thread_ = std::thread([this] { loop(); });
        return true;
    }

    void stop(JNIEnv* env) {
        running_.store(false);
        producer_connected_.store(false);
        surface_attached_.store(false);
        if (zero_copy_) vessel_proroot_surfacecontrol_detach();
        const int listen = listen_fd_.exchange(-1);
        if (listen >= 0) close(listen);
        const int ctrl = ctrl_fd_.exchange(-1);
        if (ctrl >= 0) shutdown(ctrl, SHUT_RDWR);
        wake_release_loop();
        if (thread_.joinable()) thread_.join();
        cleanup_resources();
        if (release_event_fd_ >= 0) {
            close(release_event_fd_);
            release_event_fd_ = -1;
        }
        unlink_socket();
        if (env && callback_) {
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
        set_status("stopped");
    }

    void configure(uint32_t width, uint32_t height, float refresh) {
        const uint32_t next_width = std::clamp(width, 320u, 4096u);
        const uint32_t next_height = std::clamp(height, 240u, 4096u);
        const bool size_changed =
            next_width != width_.load() || next_height != height_.load();

        // Never tear down a live Anland producer merely because Android recreated
        // or resized its SurfaceView. KWin treats a consumer disconnect as a
        // backend failure and may enter a permanent fallback/black-screen state.
        // The Linux monitor mode remains stable for the lifetime of the producer;
        // Android SurfaceControl scales that stable buffer to the current view.
        if (size_changed && !producer_connected_.load()) {
            width_.store(next_width);
            height_.store(next_height);
        }

        refresh_mhz_.store(
            static_cast<uint32_t>(std::clamp(refresh, 1.0f, 240.0f) * 1000.0f + 0.5f));
        if (zero_copy_) {
            vessel_proroot_surfacecontrol_configure(
                width_.load(),
                height_.load(),
                refresh_mhz_.load() / 1000.0f);
        }
        send_refresh();
    }

    bool zero_copy_available() const {
        return zero_copy_.load();
    }

    bool attach_surface(JNIEnv* env, jobject surface) {
        if (!zero_copy_ || !surface) return false;
        const bool ok = vessel_proroot_surfacecontrol_attach(
            env,
            surface,
            width_.load(),
            height_.load(),
            refresh_mhz_.load() / 1000.0f);
        if (!ok) {
            // Capability was present but this concrete Surface could not host the
            // child layer. Permanently downgrade this run to the Phase-4 GPU path
            // and reconnect KWin after Java starts the fallback presenter.
            zero_copy_ = false;
            surface_attached_.store(false);
            set_disconnect_reason("surfacecontrol-downgrade-reconnect");
            const int ctrl = ctrl_fd_.load();
            if (ctrl >= 0) shutdown(ctrl, SHUT_RDWR);
            return false;
        }
        surface_attached_.store(true);
        (void)request_next_frame();
        set_status("zero-copy-surface-attached");
        return true;
    }

    void detach_surface() {
        if (!zero_copy_) return;
        surface_attached_.store(false);
        vessel_proroot_surfacecontrol_detach();
        // Surface lifecycle is independent from the KWin producer lifecycle.
        // Keep the producer socket/resources alive; attach_surface() will request
        // a fresh frame when Android supplies the next Surface.
        set_status("zero-copy-surface-detached");
    }

    void set_refresh(float refresh) {
        const float clamped = std::clamp(refresh, 1.0f, 240.0f);
        const uint32_t next = static_cast<uint32_t>(clamped * 1000.0f + 0.5f);
        if (refresh_mhz_.exchange(next) == next) return;
        if (zero_copy_) {
            vessel_proroot_surfacecontrol_configure(
                width_.load(),
                height_.load(),
                clamped);
        }
        send_refresh();
    }

    uint64_t frames_presented() const {
        return frames_presented_.load();
    }

    uint64_t frames_released() const {
        return frames_released_.load();
    }

    bool producer_connected() const {
        return producer_connected_.load();
    }

    uint64_t producer_generation() const {
        return producer_generation_.load();
    }

    bool surface_attached() const {
        return surface_attached_.load();
    }

    uint64_t last_frame_presented_ms() const {
        return last_frame_presented_ms_.load();
    }

    std::string disconnect_reason() const {
        std::lock_guard<std::mutex> guard(status_lock_);
        return disconnect_reason_;
    }

    float effective_refresh() const {
        return refresh_mhz_.load() / 1000.0f;
    }

    bool touch(int action, float x, float y, int pointer_id) {
        InputEvent ev{};
        ev.type = INPUT_TOUCH;
        ev.touch.action = action;
        ev.touch.x = x;
        ev.touch.y = y;
        ev.touch.pointer_id = pointer_id;
        if (!send_input(ev)) return false;
        InputEvent frame{};
        frame.type = INPUT_TOUCH_FRAME;
        return send_input(frame);
    }

    bool pointer(float x, float y, float dx, float dy) {
        InputEvent ev{};
        ev.type = INPUT_POINTER_MOTION;
        ev.pointer_motion = {x, y, dx, dy};
        return send_input(ev);
    }

    bool button(uint32_t code, bool down) {
        InputEvent ev{};
        ev.type = INPUT_POINTER_BUTTON;
        ev.pointer_button.button = code;
        ev.pointer_button.pressed = down ? 1 : 0;
        return send_input(ev);
    }

    bool scroll(uint32_t axis, float value) {
        InputEvent ev{};
        ev.type = INPUT_POINTER_AXIS;
        ev.pointer_axis.axis = axis;
        ev.pointer_axis.value = value;
        ev.pointer_axis.discrete = 0;
        return send_input(ev);
    }

    bool key(int code, bool down) {
        InputEvent ev{};
        ev.type = INPUT_KEY;
        ev.key.action = down ? 0 : 1;
        ev.key.keycode = code;
        return send_input(ev);
    }

    bool text(const std::string& value) {
        if (value.empty()) return true;
        InputEvent ev{};
        ev.type = INPUT_TEXT;
        ev.text_input.size = static_cast<uint32_t>(value.size());
        return send_input(ev, value.data(), value.size());
    }

    bool clipboard(const std::string& value) {
        if (value.empty() || value.size() > MAX_CLIPBOARD) return false;
        InputEvent ev{};
        ev.type = INPUT_CLIPBOARD;
        ev.clipboard.size = static_cast<uint32_t>(value.size());
        return send_input(ev, value.data(), value.size());
    }

    std::string status() const {
        std::lock_guard<std::mutex> guard(status_lock_);
        return status_;
    }

private:
    std::atomic<bool> running_{false};
    std::thread thread_;
    std::string socket_path_;
    std::atomic<uint32_t> width_{1280};
    std::atomic<uint32_t> height_{720};
    std::atomic<uint32_t> refresh_mhz_{60000};
    std::atomic<int> listen_fd_{-1};
    std::atomic<int> ctrl_fd_{-1};

    std::mutex resource_lock_;
    int data_fd_ = -1;
    int fence_fd_ = -1;
    int buf_ready_fd_ = -1;
    int shm_fd_ = -1;
    int audio_fd_ = -1;
    uint32_t* selected_ = nullptr;
    std::array<AHardwareBuffer*, BUFFER_COUNT> buffers_{};
    std::array<int, BUFFER_COUNT> dmabuf_fds_{{-1, -1, -1}};

    // API 36+ zero-copy ownership. KWin may render only into FREE slots.
    std::array<SlotState, BUFFER_COUNT> slot_states_{{
        SlotState::FREE, SlotState::FREE, SlotState::FREE,
    }};
    std::array<int, BUFFER_COUNT> release_fds_{{-1, -1, -1}};
    std::mutex release_queue_lock_;
    std::deque<ReleaseEvent> release_queue_;
    int release_event_fd_ = -1;
    std::atomic<uint64_t> generation_{1};
    bool render_inflight_ = false;
    std::atomic<bool> zero_copy_{false};
    std::atomic<bool> surface_attached_{false};
    std::atomic<uint64_t> frames_presented_{0};
    std::atomic<uint64_t> frames_released_{0};
    std::atomic<bool> producer_connected_{false};
    std::atomic<uint64_t> producer_generation_{0};
    std::atomic<uint64_t> last_frame_presented_ms_{0};

    // Android 30-35 compatibility fallback keeps the Phase-4 GPU-blit presenter.
    std::array<bool, BUFFER_COUNT> presenter_registered_{{false, false, false}};
    int presenter_fd_ = -1;
    uint32_t serial_ = 1;
    uint32_t next_slot_ = 0;
    bool resources_ready_ = false;
    std::mutex data_write_lock_;

    JavaVM* jvm_ = nullptr;
    jobject callback_ = nullptr;

    mutable std::mutex status_lock_;
    std::string status_ = "idle";
    std::string disconnect_reason_ = "none";

    static uint64_t monotonic_ms() {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
    }

    void set_status(const std::string& value) {
        std::lock_guard<std::mutex> guard(status_lock_);
        status_ = value;
    }

    void set_disconnect_reason(const std::string& value) {
        std::lock_guard<std::mutex> guard(status_lock_);
        disconnect_reason_ = value;
    }

    void unlink_socket() {
        if (!socket_path_.empty()) unlink(socket_path_.c_str());
    }

    static void surface_release_callback(
        void* opaque,
        uint32_t slot,
        uint64_t generation,
        int release_fence_fd) {
        auto* bridge = static_cast<Bridge*>(opaque);
        if (!bridge) {
            if (release_fence_fd >= 0) close(release_fence_fd);
            return;
        }
        bridge->queue_release(slot, generation, release_fence_fd);
    }

    void queue_release(uint32_t slot, uint64_t generation, int fence_fd) {
        if (slot >= BUFFER_COUNT || generation != generation_.load()) {
            if (fence_fd >= 0) close(fence_fd);
            return;
        }
        {
            std::lock_guard<std::mutex> guard(release_queue_lock_);
            // Recheck after acquiring the queue lock so cleanup cannot retire the
            // generation between the fast check and insertion.
            if (generation != generation_.load()) {
                if (fence_fd >= 0) close(fence_fd);
                return;
            }
            release_queue_.push_back(ReleaseEvent{slot, generation, fence_fd});
        }
        wake_release_loop();
    }

    void wake_release_loop() {
        if (release_event_fd_ < 0) return;
        uint64_t one = 1;
        (void)write(release_event_fd_, &one, sizeof(one));
    }

    void drain_release_notifications() {
        if (release_event_fd_ >= 0) {
            uint64_t count = 0;
            while (read(release_event_fd_, &count, sizeof(count)) > 0) {}
        }

        std::deque<ReleaseEvent> events;
        {
            std::lock_guard<std::mutex> guard(release_queue_lock_);
            events.swap(release_queue_);
        }

        bool can_schedule = false;
        {
            std::lock_guard<std::mutex> guard(resource_lock_);
            const uint64_t current_generation = generation_.load();
            for (auto& event : events) {
                if (event.generation != current_generation ||
                    event.slot >= BUFFER_COUNT) {
                    if (event.fence_fd >= 0) close(event.fence_fd);
                    continue;
                }
                if (release_fds_[event.slot] >= 0) {
                    close(release_fds_[event.slot]);
                    release_fds_[event.slot] = -1;
                }
                if (event.fence_fd < 0) {
                    slot_states_[event.slot] = SlotState::FREE;
                    frames_released_.fetch_add(1);
                    can_schedule = true;
                } else {
                    release_fds_[event.slot] = event.fence_fd;
                }
            }
        }
        if (can_schedule) (void)request_next_frame();
    }

    void release_signaled_slot(uint32_t slot, int expected_fd) {
        bool released = false;
        {
            std::lock_guard<std::mutex> guard(resource_lock_);
            if (slot >= BUFFER_COUNT || release_fds_[slot] != expected_fd) return;
            close(release_fds_[slot]);
            release_fds_[slot] = -1;
            slot_states_[slot] = SlotState::FREE;
            frames_released_.fetch_add(1);
            released = true;
        }
        if (released) (void)request_next_frame();
    }

    void loop() {
        unlink_socket();
        const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (server < 0) {
            set_status("error:socket");
            return;
        }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (socket_path_.size() >= sizeof(addr.sun_path)) {
            close(server);
            set_status("error:socket-path-too-long");
            return;
        }
        std::memcpy(addr.sun_path, socket_path_.c_str(), socket_path_.size() + 1);
        if (bind(server, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
            listen(server, 2) != 0) {
            close(server);
            set_status("error:bind");
            return;
        }
        chmod(socket_path_.c_str(), 0600);
        listen_fd_.store(server);
        set_status("waiting-for-kwin");

        while (running_.load()) {
            pollfd pfd{server, POLLIN, 0};
            const int rc = poll(&pfd, 1, 250);
            if (!running_.load()) break;
            if (rc < 0 && errno == EINTR) continue;
            if (rc <= 0) continue;
            const int client = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
            if (client < 0) continue;
            ctrl_fd_.store(client);
            serve_producer(client);
            producer_connected_.store(false);
            ctrl_fd_.store(-1);
            close(client);
            cleanup_resources();
            if (running_.load()) set_status("waiting-for-kwin");
        }

        const int expected = server;
        int current = listen_fd_.load();
        if (current == expected) listen_fd_.store(-1);
        close(server);
        unlink_socket();
    }

    void serve_producer(int ctrl) {
        auto disconnected = [this](const char* reason) {
            producer_connected_.store(false);
            std::lock_guard<std::mutex> guard(status_lock_);
            if (disconnect_reason_ == "none") disconnect_reason_ = reason;
        };

        CtrlMsg hello{};
        if (!recv_all(ctrl, &hello, sizeof(hello)) || hello.type != CTRL_PRODUCER_HELLO) {
            set_status("error:producer-hello");
            disconnected("producer-hello-failed");
            return;
        }
        if (!send_screen(ctrl)) {
            set_status("error:screen-info");
            disconnected("screen-info-send-failed");
            return;
        }
        producer_connected_.store(true);
        producer_generation_.fetch_add(1);
        set_disconnect_reason("none");
        set_status("kwin-connected");

        while (running_.load()) {
            std::array<pollfd, 4 + BUFFER_COUNT> pfds{};
            pfds[0] = {ctrl, POLLIN | POLLHUP | POLLERR, 0};
            {
                std::lock_guard<std::mutex> guard(resource_lock_);
                pfds[1] = {fence_fd_, POLLIN | POLLHUP | POLLERR, 0};
                pfds[2] = {data_fd_, POLLIN | POLLHUP | POLLERR, 0};
                pfds[3] = {release_event_fd_, POLLIN | POLLERR, 0};
                for (int i = 0; i < BUFFER_COUNT; ++i) {
                    pfds[4 + i] = {
                        release_fds_[i],
                        POLLIN | POLLHUP | POLLERR,
                        0,
                    };
                }
            }
            const int rc = poll(pfds.data(), pfds.size(), 250);
            if (rc < 0 && errno == EINTR) continue;
            if (rc < 0) {
                disconnected("producer-poll-error");
                return;
            }

            if (pfds[0].revents & (POLLHUP | POLLERR)) {
                disconnected("producer-control-hangup");
                return;
            }
            if (pfds[0].revents & POLLIN) {
                CtrlMsg msg{};
                if (!recv_all(ctrl, &msg, sizeof(msg))) {
                    disconnected("producer-control-eof");
                    return;
                }
                if (msg.size > 0) {
                    std::vector<uint8_t> discard(msg.size);
                    if (!recv_all(ctrl, discard.data(), discard.size())) {
                        disconnected("producer-control-payload-eof");
                        return;
                    }
                }
                if (msg.type == CTRL_PICKUP_FDS) {
                    if (!setup_resources(ctrl)) {
                        set_status("error:consumer-resources");
                        disconnected("consumer-resource-setup-failed");
                        return;
                    }
                }
            }

            if (pfds[1].fd >= 0 && (pfds[1].revents & (POLLHUP | POLLERR))) {
                disconnected("frame-fence-hangup");
                return;
            }
            if (pfds[1].fd >= 0 && (pfds[1].revents & POLLIN)) {
                if (!handle_frame_done()) {
                    disconnected("frame-processing-failed");
                    return;
                }
            }

            if (pfds[2].fd >= 0 && (pfds[2].revents & (POLLHUP | POLLERR))) {
                disconnected("data-channel-hangup");
                return;
            }
            if (pfds[2].fd >= 0 && (pfds[2].revents & POLLIN)) {
                if (!handle_output_event()) {
                    disconnected("output-event-failed");
                    return;
                }
            }

            if (pfds[3].fd >= 0 && (pfds[3].revents & (POLLIN | POLLERR))) {
                drain_release_notifications();
            }
            for (int i = 0; i < BUFFER_COUNT; ++i) {
                if (pfds[4 + i].fd >= 0 &&
                    (pfds[4 + i].revents & (POLLIN | POLLHUP | POLLERR))) {
                    release_signaled_slot(
                        static_cast<uint32_t>(i),
                        pfds[4 + i].fd);
                }
            }
        }
    }

    bool send_screen(int ctrl) {
        struct {
            CtrlMsg hdr;
            ScreenInfo info;
        } packet{};
        packet.hdr = {CTRL_SCREEN_INFO, sizeof(ScreenInfo)};
        packet.info = {
            width_.load(),
            height_.load(),
            PIXEL_FORMAT_RGBA_8888,
            refresh_mhz_.load(),
        };
        return send_all(ctrl, &packet, sizeof(packet));
    }

    bool allocate_buffers() {
        for (int i = 0; i < BUFFER_COUNT; ++i) {
            AHardwareBuffer_Desc desc{};
            desc.width = width_.load();
            desc.height = height_.load();
            desc.layers = 1;
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            desc.usage =
                AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                (zero_copy_ ? AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY : 0);
            if (!AHardwareBuffer_isSupported(&desc) ||
                AHardwareBuffer_allocate(&desc, &buffers_[i]) != 0 ||
                !buffers_[i]) {
                return false;
            }
            dmabuf_fds_[i] = extract_first_dmabuf(buffers_[i]);
            if (dmabuf_fds_[i] < 0) return false;
        }
        return true;
    }

    bool setup_resources(int ctrl) {
        cleanup_resources();

        int fence_pair[2] = {-1, -1};
        int data_pair[2] = {-1, -1};
        int audio_pair[2] = {-1, -1};
        int shm = -1;
        uint32_t* selected = nullptr;

        buf_ready_fd_ = eventfd(0, EFD_CLOEXEC);
        if (buf_ready_fd_ < 0 ||
            socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, fence_pair) != 0 ||
            socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, data_pair) != 0 ||
            socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, audio_pair) != 0) {
            cleanup_resources();
            return false;
        }

        shm = memfd_create("vessel-buffer-select", MFD_CLOEXEC);
        if (shm < 0 || ftruncate(shm, sizeof(uint32_t)) != 0) {
            if (shm >= 0) close(shm);
            close(fence_pair[0]); close(fence_pair[1]);
            close(data_pair[0]); close(data_pair[1]);
            close(audio_pair[0]); close(audio_pair[1]);
            cleanup_resources();
            return false;
        }
        selected = static_cast<uint32_t*>(
            mmap(nullptr, sizeof(uint32_t), PROT_READ | PROT_WRITE, MAP_SHARED, shm, 0));
        if (selected == MAP_FAILED) {
            close(shm);
            close(fence_pair[0]); close(fence_pair[1]);
            close(data_pair[0]); close(data_pair[1]);
            close(audio_pair[0]); close(audio_pair[1]);
            cleanup_resources();
            return false;
        }
        *selected = 0;

        fence_fd_ = fence_pair[0];
        data_fd_ = data_pair[0];
        audio_fd_ = audio_pair[0];
        shm_fd_ = shm;
        selected_ = selected;

        if (!allocate_buffers()) {
            close(fence_pair[1]);
            close(data_pair[1]);
            close(audio_pair[1]);
            cleanup_resources();
            return false;
        }

        const int producer_fds[5] = {
            buf_ready_fd_,
            fence_pair[1],
            data_pair[1],
            shm_fd_,
            audio_pair[1],
        };
        const CtrlMsg ready{CTRL_FDS_READY, 0};
        const bool sent_fds = send_fds(ctrl, &ready, sizeof(ready), producer_fds, 5);
        close(fence_pair[1]);
        close(data_pair[1]);
        close(audio_pair[1]);
        // Vessel keeps its existing Pulse/ALSA -> Android AudioTrack bridge.
        // Close the unused Anland audio peer immediately so KWin's optional
        // PipeWire transport cannot accumulate PCM in an unread socket buffer.
        if (audio_fd_ >= 0) {
            close(audio_fd_);
            audio_fd_ = -1;
        }
        if (!sent_fds || !send_buffer_set()) {
            cleanup_resources();
            return false;
        }

        presenter_registered_.fill(false);
        serial_ = 1;
        next_slot_ = 0;
        render_inflight_ = false;
        slot_states_.fill(SlotState::FREE);
        resources_ready_ = true;

        if (!zero_copy_) {
            presenter_fd_ = connect_abstract_presenter();
            if (presenter_fd_ < 0) {
                cleanup_resources();
                set_status("error:presenter-connect");
                return false;
            }
        }

        send_refresh();
        if (!request_next_frame()) {
            cleanup_resources();
            return false;
        }
        set_status(zero_copy_
            ? (surface_attached_.load()
                ? "presenting-proroot-surfacecontrol-zero-copy"
                : "zero-copy-ready-waiting-for-surface")
            : "presenting-proroot-gpu-blit-fallback");
        return true;
    }

    bool send_buffer_set() {
        std::array<BufInfo, BUFFER_COUNT> infos{};
        std::array<int, BUFFER_COUNT> fds{};
        for (int i = 0; i < BUFFER_COUNT; ++i) {
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffers_[i], &desc);
            infos[i] = {
                desc.stride * 4u,
                desc.width,
                desc.height,
                PIXEL_FORMAT_RGBA_8888,
                0,
                0,
            };
            fds[i] = dmabuf_fds_[i];
        }
        const DataMsg header{DATA_BUFS_READY, static_cast<uint32_t>(sizeof(infos))};
        std::lock_guard<std::mutex> send_guard(data_write_lock_);
        return send_fds(data_fd_, &header, sizeof(header), fds.data(), fds.size()) &&
            send_all(data_fd_, infos.data(), sizeof(infos));
    }

    bool request_next_frame() {
        std::lock_guard<std::mutex> guard(resource_lock_);
        if (!resources_ready_ || !selected_ || buf_ready_fd_ < 0) return false;

        if (zero_copy_) {
            if (!surface_attached_.load()) return true;
            if (render_inflight_) return true;

            int chosen = -1;
            for (int step = 0; step < BUFFER_COUNT; ++step) {
                const int candidate =
                    static_cast<int>((next_slot_ + step) % BUFFER_COUNT);
                if (slot_states_[candidate] == SlotState::FREE) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen < 0) return true;

            *selected_ = static_cast<uint32_t>(chosen);
            next_slot_ = static_cast<uint32_t>((chosen + 1) % BUFFER_COUNT);
            slot_states_[chosen] = SlotState::RENDERING;
            render_inflight_ = true;
        } else {
            *selected_ = next_slot_ % BUFFER_COUNT;
            ++next_slot_;
        }

        uint64_t one = 1;
        if (write(buf_ready_fd_, &one, sizeof(one)) ==
            static_cast<ssize_t>(sizeof(one))) {
            return true;
        }
        if (zero_copy_) {
            const uint32_t slot = *selected_;
            if (slot < BUFFER_COUNT) slot_states_[slot] = SlotState::FREE;
            render_inflight_ = false;
        }
        return false;
    }

    bool handle_frame_done() {
        const int fence = recv_one_fd_with_byte(fence_fd_);
        if (fence == -2) return false;

        uint32_t slot = 0;
        {
            std::lock_guard<std::mutex> guard(resource_lock_);
            if (!selected_) {
                if (fence >= 0) close(fence);
                return false;
            }
            slot = *selected_;
            if (slot >= BUFFER_COUNT) {
                if (fence >= 0) close(fence);
                return false;
            }
            if (zero_copy_) {
                if (!render_inflight_ ||
                    slot_states_[slot] != SlotState::RENDERING) {
                    if (fence >= 0) close(fence);
                    return false;
                }
                render_inflight_ = false;
                slot_states_[slot] = SlotState::PRESENTED;
            }
        }

        if (zero_copy_) {
            const uint64_t generation = generation_.load();
            const bool ok = surface_attached_.load() &&
                vessel_proroot_surfacecontrol_present(
                    buffers_[slot],
                    fence,
                    slot,
                    generation,
                    &Bridge::surface_release_callback,
                    this);
            if (!ok) {
                if (fence >= 0) close(fence);
                {
                    std::lock_guard<std::mutex> guard(resource_lock_);
                    if (generation == generation_.load()) {
                        slot_states_[slot] = SlotState::FREE;
                    }
                }
                set_status("error:surfacecontrol-submit");
                return false;
            }
            // ASurfaceTransaction owns the acquire fence after a successful
            // setBufferWithRelease call. Reuse waits for its release callback.
            frames_presented_.fetch_add(1);
            last_frame_presented_ms_.store(monotonic_ms());
            set_status("presenting-proroot-surfacecontrol-zero-copy");
            return request_next_frame();
        }

        const bool ok = submit_surface(slot, fence);
        if (fence >= 0) close(fence);
        if (!ok) {
            set_status("error:surface-submit");
            return false;
        }
        frames_presented_.fetch_add(1);
        last_frame_presented_ms_.store(monotonic_ms());
        return request_next_frame();
    }

    bool submit_surface(uint32_t slot, int fence) {
        if (presenter_fd_ < 0) return false;
        SurfaceMsg msg{};
        msg.magic = SURFACE_MAGIC;
        msg.version = SURFACE_VERSION;
        msg.type = presenter_registered_[slot] ? SURFACE_FRAME : SURFACE_REGISTER;
        msg.scanout_id = 0;
        msg.slot = slot;
        msg.serial = serial_++;
        msg.width = width_.load();
        msg.height = height_.load();

        if (!send_all(presenter_fd_, &msg, sizeof(msg))) return false;
        if (!presenter_registered_[slot]) {
            if (AHardwareBuffer_sendHandleToUnixSocket(buffers_[slot], presenter_fd_) != 0) {
                return false;
            }
        }
        if (!send_surface_fence(presenter_fd_, fence)) return false;

        SurfaceAck ack{};
        if (!recv_all(presenter_fd_, &ack, sizeof(ack))) return false;
        if (ack.magic != SURFACE_MAGIC ||
            ack.version != SURFACE_VERSION ||
            ack.slot != slot ||
            ack.serial != msg.serial ||
            ack.ok == 0) {
            return false;
        }
        presenter_registered_[slot] = true;
        return true;
    }

    bool handle_output_event() {
        DataMsg header{};
        if (!recv_all(data_fd_, &header, sizeof(header))) return false;
        if (header.type != DATA_OUTPUT_EVENT || header.size != sizeof(OutputEvent)) {
            if (header.size > 0 && header.size <= MAX_CLIPBOARD) {
                std::vector<uint8_t> discard(header.size);
                return recv_all(data_fd_, discard.data(), discard.size());
            }
            return false;
        }

        OutputEvent event{};
        if (!recv_all(data_fd_, &event, sizeof(event))) return false;
        if (event.type == OUTPUT_CLIPBOARD && event.clipboard.size > 0 &&
            event.clipboard.size <= MAX_CLIPBOARD) {
            std::string value(event.clipboard.size, '\0');
            if (!recv_all(data_fd_, value.data(), value.size())) return false;
            notify_clipboard(value);
        }
        return true;
    }

    void notify_clipboard(const std::string& value) {
        if (!jvm_ || !callback_) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (jvm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        }
        jclass cls = env->GetObjectClass(callback_);
        jmethodID method = env->GetMethodID(cls, "onGuestClipboardFromNative", "([B)V");
        if (method) {
            jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
            if (bytes) {
                env->SetByteArrayRegion(
                    bytes,
                    0,
                    static_cast<jsize>(value.size()),
                    reinterpret_cast<const jbyte*>(value.data()));
                env->CallVoidMethod(callback_, method, bytes);
                env->DeleteLocalRef(bytes);
            }
        }
        env->DeleteLocalRef(cls);
        if (attached) jvm_->DetachCurrentThread();
    }

    bool send_input(const InputEvent& event, const void* payload = nullptr, size_t payload_size = 0) {
        std::lock_guard<std::mutex> resource_guard(resource_lock_);
        if (!resources_ready_ || data_fd_ < 0) return false;
        if (payload_size > MAX_CLIPBOARD) return false;
        const DataMsg header{DATA_INPUT_EVENT, sizeof(InputEvent)};
        std::lock_guard<std::mutex> send_guard(data_write_lock_);
        if (!send_all(data_fd_, &header, sizeof(header)) ||
            !send_all(data_fd_, &event, sizeof(event))) {
            return false;
        }
        return payload_size == 0 || send_all(data_fd_, payload, payload_size);
    }

    void send_refresh() {
        InputEvent ev{};
        ev.type = INPUT_DISPLAY_REFRESH;
        ev.display.refresh_mhz = refresh_mhz_.load();
        (void)send_input(ev);
    }

    void cleanup_resources() {
        const uint64_t retired_generation = generation_.fetch_add(1);
        (void)retired_generation;

        std::deque<ReleaseEvent> stale_events;
        {
            std::lock_guard<std::mutex> queue_guard(release_queue_lock_);
            stale_events.swap(release_queue_);
        }
        for (auto& event : stale_events) {
            if (event.fence_fd >= 0) close(event.fence_fd);
        }

        std::lock_guard<std::mutex> guard(resource_lock_);
        resources_ready_ = false;
        render_inflight_ = false;
        if (selected_ && selected_ != MAP_FAILED) munmap(selected_, sizeof(uint32_t));
        selected_ = nullptr;
        if (presenter_fd_ >= 0) close(presenter_fd_);
        if (data_fd_ >= 0) close(data_fd_);
        if (fence_fd_ >= 0) close(fence_fd_);
        if (buf_ready_fd_ >= 0) close(buf_ready_fd_);
        if (shm_fd_ >= 0) close(shm_fd_);
        if (audio_fd_ >= 0) close(audio_fd_);
        presenter_fd_ = data_fd_ = fence_fd_ = buf_ready_fd_ = shm_fd_ = audio_fd_ = -1;
        for (int i = 0; i < BUFFER_COUNT; ++i) {
            if (release_fds_[i] >= 0) close(release_fds_[i]);
            release_fds_[i] = -1;
            slot_states_[i] = SlotState::FREE;
            if (dmabuf_fds_[i] >= 0) close(dmabuf_fds_[i]);
            dmabuf_fds_[i] = -1;
            if (buffers_[i]) AHardwareBuffer_release(buffers_[i]);
            buffers_[i] = nullptr;
        }
        presenter_registered_.fill(false);
    }
};

Bridge g_bridge;

std::string jstring_utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

std::string jbytes(JNIEnv* env, jbyteArray value) {
    if (!value) return {};
    const jsize length = env->GetArrayLength(value);
    if (length <= 0) return {};
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeStart(
    JNIEnv* env, jobject thiz, jstring path, jint width, jint height, jfloat refresh) {
    return g_bridge.start(
        env,
        thiz,
        jstring_utf8(env, path),
        static_cast<uint32_t>(std::max(1, width)),
        static_cast<uint32_t>(std::max(1, height)),
        refresh) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeStop(
    JNIEnv* env, jobject) {
    g_bridge.stop(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeConfigure(
    JNIEnv*, jobject, jint width, jint height, jfloat refresh) {
    g_bridge.configure(
        static_cast<uint32_t>(std::max(1, width)),
        static_cast<uint32_t>(std::max(1, height)),
        refresh);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeZeroCopyAvailable(
    JNIEnv*, jobject) {
    return g_bridge.zero_copy_available() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeAttachSurface(
    JNIEnv* env, jobject, jobject surface) {
    return g_bridge.attach_surface(env, surface) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeDetachSurface(
    JNIEnv*, jobject) {
    g_bridge.detach_surface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeSetEffectiveRefresh(
    JNIEnv*, jobject, jfloat refresh) {
    g_bridge.set_refresh(refresh);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeFramesPresented(
    JNIEnv*, jobject) {
    return static_cast<jlong>(g_bridge.frames_presented());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeFramesReleased(
    JNIEnv*, jobject) {
    return static_cast<jlong>(g_bridge.frames_released());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeProducerConnected(
    JNIEnv*, jobject) {
    return g_bridge.producer_connected() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeProducerGeneration(
    JNIEnv*, jobject) {
    return static_cast<jlong>(g_bridge.producer_generation());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeSurfaceAttached(
    JNIEnv*, jobject) {
    return g_bridge.surface_attached() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeLastFramePresentedMs(
    JNIEnv*, jobject) {
    return static_cast<jlong>(g_bridge.last_frame_presented_ms());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeDisconnectReason(
    JNIEnv* env, jobject) {
    const std::string value = g_bridge.disconnect_reason();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeEffectiveRefresh(
    JNIEnv*, jobject) {
    return g_bridge.effective_refresh();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeStatus(
    JNIEnv* env, jobject) {
    const std::string value = g_bridge.status();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeTouch(
    JNIEnv*, jobject, jint action, jfloat x, jfloat y, jint pointer_id) {
    return g_bridge.touch(action, x, y, pointer_id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativePointer(
    JNIEnv*, jobject, jfloat x, jfloat y, jfloat dx, jfloat dy) {
    return g_bridge.pointer(x, y, dx, dy) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeButton(
    JNIEnv*, jobject, jint code, jboolean down) {
    return g_bridge.button(static_cast<uint32_t>(code), down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeScroll(
    JNIEnv*, jobject, jint axis, jfloat value) {
    return g_bridge.scroll(static_cast<uint32_t>(axis), value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeKey(
    JNIEnv*, jobject, jint code, jboolean down) {
    return g_bridge.key(code, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeText(
    JNIEnv* env, jobject, jbyteArray value) {
    return g_bridge.text(jbytes(env, value)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_dreamlinux_VesselProrootDisplayBridge_nativeClipboard(
    JNIEnv* env, jobject, jbyteArray value) {
    return g_bridge.clipboard(jbytes(env, value)) ? JNI_TRUE : JNI_FALSE;
}
