#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def run(*args: str) -> None:
    print('+', ' '.join(args), flush=True)
    subprocess.run(args, cwd=ROOT, check=True)


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one anchor, got {count}')
    path.write_text(text.replace(old, new, 1))


def replace_optional(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old in text:
        path.write_text(text.replace(old, new))


# ---------------------------------------------------------------------------
# 1) Materialize the currently shipping alpha transforms ONCE into the actual
#    tracked source tree. The scripts are deleted at the end of this migration.
# ---------------------------------------------------------------------------
for script in (
    'alpha4_experiment_lab.py',
    'alpha5_async_presenter.py',
    'alpha6_wayland_overhaul.py',
    'alpha7_live_update.py',
    'alpha8_wayland_bootstrap_fix.py',
):
    run(sys.executable, str(ROOT / 'tools/vessel_native' / script), str(ROOT))

# Stop mutating first-party source during every native build.
rebuild = ROOT / 'tools/vessel_native/rebuild_vhost_gpu_ahb.sh'
r = rebuild.read_text()
r = re.sub(r'^python3 "\$ROOT/tools/vessel_native/alpha[^\n]+\n', '', r, flags=re.M)
if 'alpha4_experiment_lab.py' in r or 'alpha8_wayland_bootstrap_fix.py' in r:
    raise SystemExit('alpha patcher invocation survived rebuild script cleanup')
rebuild.write_text(r)

# Bump the product version now that the real source is authoritative.
gradle = ROOT / 'app/build.gradle.kts'
g = gradle.read_text()
g = re.sub(r'versionName = "2\.1\.0-alpha13"', 'versionName = "2.1.0-alpha14"', g, count=1)
gradle.write_text(g)

# ---------------------------------------------------------------------------
# 2) Input: remove UI-thread blocking from pointer/touch/key delivery.
#    Motion is coalesced; all socket I/O happens on one native worker thread.
# ---------------------------------------------------------------------------
input_cpp = ROOT / 'app/src/main/cpp/vessel_virtio_input.cpp'
input_cpp.write_text(r'''#include <jni.h>

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
''')

# ---------------------------------------------------------------------------
# 3) Trackpad cursor: draw a short-lived host prediction immediately, while
#    KWin catches up through virtio-input. The authoritative guest cursor wins
#    again after ~50 ms, so there is no long-term drift.
# ---------------------------------------------------------------------------
view = ROOT / 'app/src/main/java/com/example/dreamlinux/LinuxDesktopView.kt'
v = view.read_text()
field_anchor = '    private var movedBeyondTap = false\n'
if field_anchor not in v:
    raise SystemExit('LinuxDesktopView cursor field anchor missing')
v = v.replace(field_anchor, field_anchor + '''    private var predictedCursorX = Float.NaN\n    private var predictedCursorY = Float.NaN\n    private var cursorPredictionUntil = 0L\n''', 1)

old_xy = '''            val x = ox + (VesselWaylandPresenter.cursorX() - VesselWaylandPresenter.cursorHotX()) * scale\n            val y = oy + (VesselWaylandPresenter.cursorY() - VesselWaylandPresenter.cursorHotY()) * scale\n'''
new_xy = '''            val predictionActive = SystemClock.uptimeMillis() <= cursorPredictionUntil && predictedCursorX.isFinite() && predictedCursorY.isFinite()\n            val cursorX = if (predictionActive) predictedCursorX else VesselWaylandPresenter.cursorX().toFloat()\n            val cursorY = if (predictionActive) predictedCursorY else VesselWaylandPresenter.cursorY().toFloat()\n            val x = ox + (cursorX - VesselWaylandPresenter.cursorHotX()) * scale\n            val y = oy + (cursorY - VesselWaylandPresenter.cursorHotY()) * scale\n'''
if old_xy not in v:
    raise SystemExit('LinuxDesktopView cursor draw anchor missing')
v = v.replace(old_xy, new_xy, 1)

helper_anchor = '    private fun touch(e: MotionEvent): Boolean {\n'
helpers = '''    private fun beginCursorPrediction() {\n        predictedCursorX = VesselWaylandPresenter.cursorX().toFloat()\n        predictedCursorY = VesselWaylandPresenter.cursorY().toFloat()\n        cursorPredictionUntil = SystemClock.uptimeMillis() + 50L\n    }\n\n    private fun predictCursorRelative(dx: Float, dy: Float) {\n        if (!predictedCursorX.isFinite() || !predictedCursorY.isFinite() || SystemClock.uptimeMillis() > cursorPredictionUntil) {\n            beginCursorPrediction()\n        }\n        val gw = VesselWaylandPresenter.guestWidth().coerceAtLeast(1)\n        val gh = VesselWaylandPresenter.guestHeight().coerceAtLeast(1)\n        val scale = min(width.toFloat() / gw, height.toFloat() / gh).coerceAtLeast(0.0001f)\n        predictedCursorX = (predictedCursorX + (dx * 1.25f) / scale).coerceIn(0f, gw.toFloat())\n        predictedCursorY = (predictedCursorY + (dy * 1.25f) / scale).coerceIn(0f, gh.toFloat())\n        cursorPredictionUntil = SystemClock.uptimeMillis() + 50L\n        cursorView.invalidate()\n    }\n\n'''
if helper_anchor not in v:
    raise SystemExit('LinuxDesktopView touch anchor missing')
v = v.replace(helper_anchor, helpers + helper_anchor, 1)

down_anchor = '''                scrollX = e.x\n                scrollY = e.y\n'''
if down_anchor not in v:
    raise SystemExit('LinuxDesktopView ACTION_DOWN anchor missing')
v = v.replace(down_anchor, down_anchor + '                beginCursorPrediction()\n', 1)

move_anchor = '''                    val dx = e.x - lastX\n                    val dy = e.y - lastY\n'''
if move_anchor not in v:
    raise SystemExit('LinuxDesktopView ACTION_MOVE anchor missing')
v = v.replace(move_anchor, move_anchor + '                    predictCursorRelative(dx, dy)\n', 1)

up_anchor = '''            MotionEvent.ACTION_UP -> {\n                if (dragging) {\n'''
if up_anchor not in v:
    raise SystemExit('LinuxDesktopView ACTION_UP anchor missing')
v = v.replace(up_anchor, '''            MotionEvent.ACTION_UP -> {\n                cursorPredictionUntil = SystemClock.uptimeMillis() + 50L\n                if (dragging) {\n''', 1)
view.write_text(v)

# ---------------------------------------------------------------------------
# 4) Firefox: keep hardware WebRender, but stop force-enabling the native
#    Wayland compositor path which is unnecessary and known to be crash-prone
#    on some compositor/driver combinations.
# ---------------------------------------------------------------------------
controller = ROOT / 'app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt'
c = controller.read_text()
c = c.replace('            user_pref("gfx.webrender.compositor", true);\n', '            user_pref("gfx.webrender.compositor", false);\n')
c = c.replace('            user_pref("gfx.webrender.compositor.force-enabled", true);\n', '')
c = c.replace('            user_pref("layers.acceleration.force-enabled", true);\n', '')
if 'user_pref("gfx.webrender.all", true);' not in c or 'user_pref("gl.require-hardware", true);' not in c:
    raise SystemExit('Firefox hardware WebRender anchors missing after alpha integration')

# ---------------------------------------------------------------------------
# 5) Guest networking: make vec0 configuration explicit and add real DNS/TCP
#    probes. Failure is logged but does not make an otherwise-offline desktop
#    unbootable.
# ---------------------------------------------------------------------------
network_method = r'''
    private fun ensureGuestNetworkBlocking() {
        val command = """
            set +e
            iface=${'$'}(ip -o link show 2>/dev/null | awk -F': ' '${'$'}2 == "vec0" {print ${'$'}2; exit}')
            [ -n "${'$'}iface" ] || iface=${'$'}(ip -o link show 2>/dev/null | awk -F': ' '${'$'}2 ~ /^vec/ {print ${'$'}2; exit}')
            if [ -z "${'$'}iface" ]; then echo VESSEL_NET_FAIL=no-vector-interface; exit 21; fi
            ip link set "${'$'}iface" up || true
            ip addr replace 10.0.2.15/24 dev "${'$'}iface" || { echo VESSEL_NET_FAIL=address; exit 22; }
            ip route replace default via 10.0.2.2 dev "${'$'}iface" || { echo VESSEL_NET_FAIL=route; exit 23; }
            cat >/etc/resolv.conf <<'VESSEL_RESOLV'
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            options timeout:2 attempts:2
            VESSEL_RESOLV
            getent ahosts deb.debian.org >/tmp/vessel-net-dns.txt 2>&1 || { echo VESSEL_NET_FAIL=dns; cat /tmp/vessel-net-dns.txt; exit 24; }
            timeout 7 /bin/bash -lc 'exec 3<>/dev/tcp/deb.debian.org/443' >/tmp/vessel-net-tcp.txt 2>&1 || { echo VESSEL_NET_FAIL=tcp443; cat /tmp/vessel-net-tcp.txt; exit 25; }
            echo VESSEL_NETWORK_READY iface=${'$'}iface route=${'$'}(ip route show default | head -1)
        """.trimIndent()
        val result = guestBlocking(command, 30)
        if (result.first == 0) append("[network] ${result.second.lineSequence().lastOrNull { it.contains("VESSEL_NETWORK_READY") } ?: "external connectivity ready"}\\n")
        else append("[network] external connectivity probe failed rc=${result.first}: ${result.second.takeLast(3000)}\\n")
    }

'''
method_anchor = '    private fun ensureGuestFilesystemCapacity() {\n'
if method_anchor not in c:
    raise SystemExit('network helper insertion anchor missing')
c = c.replace(method_anchor, network_method + method_anchor, 1)

call_pattern = re.compile(r'(^\s+awaitGuestShell\([^\n]+\)\n)', re.M)
m = call_pattern.search(c)
if not m:
    raise SystemExit('awaitGuestShell call anchor missing')
c = c[:m.end()] + re.match(r'^\s*', m.group(1)).group(0) + 'ensureGuestNetworkBlocking()\n' + c[m.end():]

# ---------------------------------------------------------------------------
# 6) Discover/PackageKit: install the actual Discover metadata/icon stack,
#    refresh AppStream after apt-config-icons becomes active, and grant ONLY
#    PackageKit actions to the local vessel desktop user without a password.
# ---------------------------------------------------------------------------
package_validation_old = 'fonts-noto-color-emoji; do '
package_validation_new = 'fonts-noto-color-emoji packagekit packagekit-tools policykit-1 plasma-discover apt-config-icons apt-config-icons-large apt-config-icons-hidpi librsvg2-bin; do '
if package_validation_old in c:
    c = c.replace(package_validation_old, package_validation_new, 1)
else:
    raise SystemExit('plasma package validation anchor missing')

pkg_anchor = 'menu appstream python3-yaml '
pkg_replacement = 'menu appstream apt-config-icons apt-config-icons-large apt-config-icons-hidpi packagekit packagekit-tools policykit-1 plasma-discover librsvg2-bin python3-yaml '
if pkg_anchor not in c:
    raise SystemExit('Plasma package install anchor missing')
c = c.replace(pkg_anchor, pkg_replacement, 1)

verify_anchor = '        val verify = guestBlocking(plasmaReadyCommand(), 30)\n'
metadata_refresh = '''        val metadataRefresh = guestBlocking(\n            "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update >/tmp/vessel-apt-icons.log 2>&1; appstreamcli refresh-cache --force >/tmp/vessel-appstream-refresh.log 2>&1 || true",\n            300,\n        )\n        if (metadataRefresh.first != 0) append("[apps] metadata/icon refresh rc=${metadataRefresh.first}: ${metadataRefresh.second.takeLast(2000)}\\n")\n'''
if verify_anchor not in c:
    raise SystemExit('AppStream refresh insertion anchor missing')
c = c.replace(verify_anchor, metadata_refresh + verify_anchor, 1)

polkit_anchor = '            usermod -a -G video,render,input vessel\n'
polkit_block = r'''            usermod -a -G video,render,input vessel
            install -d -m 755 /etc/polkit-1/rules.d
            cat >/etc/polkit-1/rules.d/49-vessel-packagekit.rules <<'VESSEL_POLKIT'
            polkit.addRule(function(action, subject) {
                if (subject.user == "vessel" && action.id.indexOf("org.freedesktop.packagekit.") == 0) {
                    return polkit.Result.YES;
                }
            });
            VESSEL_POLKIT
            chmod 0644 /etc/polkit-1/rules.d/49-vessel-packagekit.rules
            if [ -x /usr/lib/polkit-1/polkitd ] && ! pgrep -x polkitd >/dev/null 2>&1; then
              /usr/lib/polkit-1/polkitd --no-debug >/tmp/vessel-polkit.log 2>&1 &
            fi
            appstreamcli refresh-cache --force >/tmp/vessel-appstream-refresh.log 2>&1 || true
            pkcon get-roles >/tmp/vessel-packagekit.log 2>&1 || true
'''
if polkit_anchor not in c:
    raise SystemExit('polkit prep anchor missing')
c = c.replace(polkit_anchor, polkit_block, 1)

# Lightweight desktop tuning: avoid indexing churn and prefer lower KWin
# latency without disabling compositing or animations completely.
tuning_anchor = '            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config\n'
tuning_block = '''            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config\n            cat >/home/vessel/.config/baloofilerc <<'VESSEL_BALOO'\n            [Basic]\n            Indexing-Enabled=false\n            VESSEL_BALOO\n            chown vessel:vessel /home/vessel/.config/baloofilerc\n            su -l vessel -c 'kwriteconfig5 --file kwinrc --group Compositing --key LatencyPolicy Low' 2>/dev/null || true\n            su -l vessel -c 'kwriteconfig5 --file kdeglobals --group KDE --key AnimationDurationFactor 0.7' 2>/dev/null || true\n'''
if tuning_anchor not in c:
    raise SystemExit('desktop tuning anchor missing')
c = c.replace(tuning_anchor, tuning_block, 1)
controller.write_text(c)

# Keep the Android-side workstation contract aware of the packages required by
# Discover so an old persistent guest is repaired instead of silently accepted.
service = ROOT / 'app/src/main/java/com/example/dreamlinux/VmSessionService.kt'
s = service.read_text()
service_pkg_anchor = '"desktop-file-utils", "xdg-user-dirs", "shared-mime-info", "menu", "appstream", "python3-yaml",'
service_pkg_new = '"desktop-file-utils", "xdg-user-dirs", "shared-mime-info", "menu", "appstream", "apt-config-icons", "apt-config-icons-large", "apt-config-icons-hidpi", "packagekit", "packagekit-tools", "policykit-1", "plasma-discover", "librsvg2-bin", "python3-yaml",'
if service_pkg_anchor in s:
    s = s.replace(service_pkg_anchor, service_pkg_new, 1)
else:
    # Alpha6/10 may have reformatted the list but keeps appstream/python3-yaml adjacent.
    if '"appstream", "python3-yaml"' not in s:
        raise SystemExit('service package contract anchor missing')
    s = s.replace('"appstream", "python3-yaml"', '"appstream", "apt-config-icons", "apt-config-icons-large", "apt-config-icons-hidpi", "packagekit", "packagekit-tools", "policykit-1", "plasma-discover", "librsvg2-bin", "python3-yaml"', 1)
service.write_text(s)

# ---------------------------------------------------------------------------
# 7) Vessel Apps icon helper: actually accept SVG AppStream icons by rendering
#    them once into the existing cache instead of discovering then rejecting
#    every non-PNG icon.
# ---------------------------------------------------------------------------
discovery = ROOT / 'app/src/main/assets/vessel/app_discovery_v3.py'
d = discovery.read_text()
if 'import hashlib\n' not in d:
    d = d.replace('import gzip\n', 'import gzip\nimport hashlib\n', 1)
old_icon = r'''def icon_file(icon):
    if not icon: return ""
    name = os.path.basename(icon)
    names = [name] if name.lower().endswith((".png", ".svg", ".xpm")) else [name, name + ".png", name + ".svg"]
    for n in names:
        for pattern in (f"/var/cache/app-info/icons/*/64x64/{n}", f"/var/cache/app-info/icons/*/128x128/{n}",
                        f"/var/cache/swcatalog/icons/*/64x64/{n}", f"/var/cache/swcatalog/icons/*/128x128/{n}",
                        f"/usr/share/pixmaps/{n}", f"/usr/share/icons/hicolor/*/apps/{n}", f"/usr/share/icons/breeze/*/apps/{n}"):
            for path in glob.glob(pattern):
                if os.path.isfile(path) and path.lower().endswith(".png") and os.path.getsize(path) <= 256 * 1024:
                    return path
    return ""
'''
new_icon = r'''def icon_file(icon):
    if not icon: return ""
    name = os.path.basename(icon)
    names = [name] if name.lower().endswith((".png", ".svg", ".xpm")) else [name, name + ".png", name + ".svg"]
    patterns = (
        "/var/cache/app-info/icons/*/64x64/{name}", "/var/cache/app-info/icons/*/128x128/{name}",
        "/var/cache/app-info/icons/*/128x128@2/{name}", "/var/cache/swcatalog/icons/*/64x64/{name}",
        "/var/cache/swcatalog/icons/*/128x128/{name}", "/var/cache/swcatalog/icons/*/128x128@2/{name}",
        "/usr/share/pixmaps/{name}", "/usr/share/icons/hicolor/*/apps/{name}",
        "/usr/share/icons/breeze/*/apps/{name}",
    )
    for n in names:
        for template in patterns:
            for path in glob.glob(template.format(name=n)):
                if not os.path.isfile(path) or os.path.getsize(path) > 512 * 1024:
                    continue
                lower = path.lower()
                if lower.endswith(".png"):
                    return path
                if lower.endswith(".svg"):
                    os.makedirs("/var/cache/vessel/icons", exist_ok=True)
                    stamp = f"{path}:{os.path.getmtime(path)}:{os.path.getsize(path)}".encode()
                    out = "/var/cache/vessel/icons/" + hashlib.sha256(stamp).hexdigest()[:24] + ".png"
                    if os.path.isfile(out) and os.path.getsize(out) > 0:
                        return out
                    try:
                        subprocess.run(["rsvg-convert", "-w", "128", "-h", "128", "-o", out, path],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8, check=False)
                        if os.path.isfile(out) and 0 < os.path.getsize(out) <= 512 * 1024:
                            return out
                    except Exception:
                        pass
    return ""
'''
if old_icon not in d:
    raise SystemExit('app_discovery_v3 icon function anchor missing')
d = d.replace(old_icon, new_icon, 1)
discovery.write_text(d)

# ---------------------------------------------------------------------------
# 8) Final cleanup: remove the entire alpha first-party patch stack and the
#    one-time migration workflow/script. Third-party rust-vmm integration stays
#    pinned and explicit; first-party APK source is now normal tracked source.
# ---------------------------------------------------------------------------
for path in (ROOT / 'tools/vessel_native').glob('alpha*.py'):
    path.unlink()

migration_workflow = ROOT / '.github/workflows/vessel-integrate-source.yml'
if migration_workflow.exists():
    migration_workflow.unlink()

# Delete this script last; Python keeps the current process alive after unlink.
Path(__file__).unlink()

print('[integrate] Vessel alpha transforms materialized; latency/network/Discover/Firefox fixes applied; patch stack removed')
