#!/usr/bin/env python3
"""Vessel protocol 38: Debian UML + VirtIO GPU + KDE Plasma Wayland/KWin.

The guest owns a normal Linux virtio-gpu DRM device. KWin runs directly on
/dev/dri/card0 and Plasma renders through Mesa VirGL; the host vhost-user GPU
continues through virglrenderer -> ANGLE/Vulkan -> Adreno. Final scanout reaches
the Android Vulkan SurfaceView through Vessel's RGB loopback bridge.

No VNC, screenshots, nested Weston, Termux:X11, custom KWin output plugin, or
software renderer is used.
"""
from __future__ import annotations

import base64
import json
import pathlib
import socket
import threading
import time
from typing import Any

import vessel_runtime_daemon_v33 as v33

PROTOCOL_VERSION = 38
RUNTIME_REVISION = "v38-virtio-gpu-plasma-r1"
DISPLAY_TRANSPORT = "virtio-gpu-rgb-loopback-android-vulkan-v1"
POC = pathlib.Path.home() / "vessel-poc-runtime"
if "VESSEL_POC_DIR" in __import__("os").environ:
    POC = pathlib.Path(__import__("os").environ["VESSEL_POC_DIR"])

# Reuse the mature PTY + reverse JSON-RPC controller, but point it at the
# standard VirtIO-GPU/VirGL runner.
v33.v31.base.RUNNER = POC / "tools/venus_poc/run_vessel_virtio_gpu.sh"
DISPLAY_LOG = v33.v31.base.RUNTIME / "vessel-vhost-gpu-display.log"
GPU_LOG = v33.v31.base.RUNTIME / "vessel-vhost-gpu.log"


class VirtioGpuRuntime(v33.WlrootsRuntime):
    def __init__(self) -> None:
        super().__init__()
        self._desktop_worker: threading.Thread | None = None
        self._worker_lock = threading.Lock()

    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "runtimeRevision": RUNTIME_REVISION,
            "displayTransport": DISPLAY_TRANSPORT,
            "backend": "UML_VIRTIO_GPU",
            "presenter": "vhost-user-gpu RGB scanout -> Android Vulkan SurfaceView",
            "renderer": "KDE Plasma/KWin -> Mesa VirGL -> virglrenderer -> ANGLE/Vulkan -> Adreno",
            "rendererMode": "virgl-opengl",
            "compositor": "KWin Wayland direct DRM/KMS",
            "desktopName": "KDE Plasma Wayland",
            "translationLayer": "VirGL",
            "inputMode": "Linux uinput -> evdev/libinput -> KWin",
            "gpuOnly": True,
            "softwareFallback": False,
            "vncPort": -1,
            "frameContentValidated": bool(self.desktop_ready),
            "desktopWorkerAlive": bool(self._desktop_worker and self._desktop_worker.is_alive()),
            "displayLog": self._tail(DISPLAY_LOG, 7000),
            "gpuLog": self._tail(GPU_LOG, 7000),
        })
        return state

    @staticmethod
    def _tail(path: pathlib.Path, amount: int) -> str:
        try:
            return path.read_text(errors="replace")[-amount:]
        except Exception:
            return ""

    def _prepare_venus_guest(self) -> None:
        # Name retained because Runtime.start() calls this virtual hook. Protocol
        # 38 does not configure or use guest Venus.
        self.set_progress("command_agent", 36, "Starting guest control + native input channel")
        agent = POC / "tools/venus_poc/guest_command_agent_v38.py"
        if not agent.exists():
            raise RuntimeError(f"guest command agent missing: {agent}")
        self._bootstrap_upload(agent, "/root/vessel_guest_command_agent_v38.py")
        self._pty_guest(
            f"VESSEL_COMMAND_PORT={self.command_port} "
            "nohup python3 /root/vessel_guest_command_agent_v38.py "
            ">/tmp/vessel-command-agent.log 2>&1 </dev/null &",
            20,
        )
        if not self._wait_rpc(20):
            tail = self._pty_guest("tail -120 /tmp/vessel-command-agent.log 2>/dev/null || true", 20)
            raise RuntimeError("guest command agent did not connect: " + tail[-6000:])

        self.set_progress("gpu_probe", 48, "Checking VirtIO GPU / VirGL DRM")
        probe = self.guest(
            "test -c /dev/dri/card0 && test -c /dev/dri/renderD128 && "
            "echo VIRTIO_DRM_READY; "
            "EGL_PLATFORM=surfaceless eglinfo 2>&1 | "
            "grep -E 'EGL driver name: virtio_gpu|Device #0|EGL vendor string' | head -20 || true",
            25,
        )
        if "VIRTIO_DRM_READY" not in probe or "virtio_gpu" not in probe:
            raise RuntimeError("virtio-gpu DRM renderer did not initialize:\n" + probe[-8000:])
        self.append("VIRTIO_GPU_DRM_READY\n")
        self.set_progress("debian_ready", 55, "Debian + VirtIO GPU ready")

    def _ensure_plasma(self) -> None:
        have = self.guest(
            "command -v kwin_wayland >/dev/null && "
            "command -v plasmashell >/dev/null && "
            "command -v dbus-run-session >/dev/null && "
            "command -v udevadm >/dev/null && "
            "command -v libinput >/dev/null && "
            "test -x /lib/elogind/elogind && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so && "
            "echo VESSEL_PLASMA_READY || true",
            15,
        )
        if "VESSEL_PLASMA_READY" not in have:
            self.set_progress("display_deps", 60, "Installing KDE Plasma Wayland + session/input services")
            out = self.guest(
                "export DEBIAN_FRONTEND=noninteractive; "
                "apt-get update && "
                "apt-get install -y --no-install-recommends "
                "kwin-wayland plasma-workspace plasma-workspace-wayland plasma-desktop "
                "qtwayland5 xwayland dbus dbus-x11 elogind libpam-elogind "
                "udev libinput-tools mesa-utils",
                1800,
            )
            check = self.guest(
                "command -v kwin_wayland >/dev/null && "
                "command -v plasmashell >/dev/null && "
                "command -v dbus-run-session >/dev/null && "
                "command -v udevadm >/dev/null && "
                "command -v libinput >/dev/null && "
                "test -x /lib/elogind/elogind && "
                "echo VESSEL_PLASMA_READY || true",
                20,
            )
            if "VESSEL_PLASMA_READY" not in check:
                raise RuntimeError("KDE Plasma Wayland installation failed:\n" + out[-10000:])

        # KWin 5.27's DRM backend acquires DRM/input through a login1 session.
        # This guest intentionally has a tiny non-systemd init, so provide the
        # standard org.freedesktop.login1 API with elogind instead of faking or
        # patching KWin.
        self.set_progress("session_services", 68, "Starting udev, D-Bus and elogind")
        services = self.guest(
            "set -e; "
            "mkdir -p /run/dbus /run/elogind /run/user; "
            "dbus-uuidgen --ensure=/etc/machine-id; "
            "(pgrep -x systemd-udevd >/dev/null || "
            "(/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udevd-start.log || "
            "/usr/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udevd-start.log)); "
            "udevadm control --reload-rules || true; "
            "udevadm trigger --action=add || true; "
            "udevadm settle --timeout=10 || true; "
            "test -S /run/dbus/system_bus_socket || dbus-daemon --system --fork; "
            "pgrep -x elogind >/dev/null || /lib/elogind/elogind --daemon; "
            "for i in $(seq 1 80); do "
            "  loginctl list-sessions >/dev/null 2>&1 && break; sleep .1; "
            "done; "
            "loginctl list-sessions >/dev/null 2>&1 && echo LOGIN1_READY",
            35,
        )
        if "LOGIN1_READY" not in services:
            diag = self.guest(
                "echo '=== dbus ==='; ls -l /run/dbus/system_bus_socket 2>&1 || true; "
                "echo '=== elogind ==='; pgrep -a elogind || true; "
                "echo '=== udev ==='; pgrep -a systemd-udevd || true; "
                "echo '=== loginctl ==='; loginctl list-sessions 2>&1 || true; "
                "tail -80 /tmp/vessel-udevd-start.log 2>/dev/null || true",
                15,
            )
            raise RuntimeError("elogind/udev session services failed:\n" + diag[-10000:])

        # Ensure su(1) opens an elogind PAM session. A real VT-backed PAM
        # session is what lets unmodified KWin 5.27 own /dev/dri/card0 and the
        # evdev devices through TakeControl/TakeDevice.
        prep = self.guest(
            "set -e; "
            "id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel; "
            "for g in video render input; do getent group \"$g\" >/dev/null || groupadd \"$g\"; done; "
            "usermod -a -G video,render,input vessel; "
            "grep -q 'pam_elogind\\.so' /etc/pam.d/common-session || "
            "printf '\\nsession optional pam_elogind.so\\n' >>/etc/pam.d/common-session; "
            "test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1; "
            "udevadm trigger --subsystem-match=input --action=add || true; "
            "udevadm settle --timeout=10 || true; "
            "names=$(cat /sys/class/input/event*/device/name 2>/dev/null || true); "
            "printf '%s\\n' \"$names\" | grep -Fx 'Vessel Touchscreen' >/dev/null; "
            "printf '%s\\n' \"$names\" | grep -Fx 'Vessel Trackpad' >/dev/null; "
            "printf '%s\\n' \"$names\" | grep -Fx 'Vessel Keyboard' >/dev/null; "
            "echo VESSEL_INPUT_DEVICES_READY",
            30,
        )
        if "VESSEL_INPUT_DEVICES_READY" not in prep:
            diag = self.guest(
                "echo '=== input devices ==='; "
                "for f in /sys/class/input/event*/device/name; do printf '%s: ' \"$f\"; cat \"$f\" 2>/dev/null; done; "
                "echo '=== input agent ==='; tail -120 /tmp/vessel-input.log 2>/dev/null || true",
                12,
            )
            raise RuntimeError("Vessel Linux input devices are missing:\n" + diag[-10000:])

    @staticmethod
    def _plasma_session_script() -> str:
        return r"""#!/bin/bash
set -euo pipefail
exec >/tmp/vessel-plasma-session.log 2>&1

export XDG_SESSION_TYPE=wayland
export XDG_SESSION_DESKTOP=KDE
export XDG_CURRENT_DESKTOP=KDE
export DESKTOP_SESSION=plasmawayland
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=5
export KWIN_DRM_DEVICES=/dev/dri/card0
export KWIN_DRM_USE_MODIFIERS=0
export KWIN_DRM_NO_DIRECT_SCANOUT=1
export LIBGL_ALWAYS_SOFTWARE=0
export QT_QUICK_BACKEND=opengl
export MOZ_ENABLE_WAYLAND=1
unset DISPLAY WAYLAND_DISPLAY

UID_NOW=$(id -u)
export XDG_RUNTIME_DIR=/run/user/$UID_NOW
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

rm -f "$XDG_RUNTIME_DIR"/wayland-* /tmp/vessel-kwin.log /tmp/vessel-plasmashell.log /tmp/vessel-kded.log
echo "session=$XDG_SESSION_ID runtime=$XDG_RUNTIME_DIR tty=$(tty || true)"
loginctl show-session "$XDG_SESSION_ID" -p Active -p Seat -p TTY -p Type -p Class -p State || true

kwin_wayland --drm --xwayland --no-lockscreen --socket wayland-0 >/tmp/vessel-kwin.log 2>&1 &
KWIN_PID=$!

for i in $(seq 1 300); do
    if [ -S "$XDG_RUNTIME_DIR/wayland-0" ]; then
        break
    fi
    kill -0 "$KWIN_PID" 2>/dev/null || {
        wait "$KWIN_PID" || true
        echo "KWin exited before creating wayland-0"
        exit 41
    }
    sleep .05
done

[ -S "$XDG_RUNTIME_DIR/wayland-0" ] || {
    echo "KWin did not create a Wayland socket"
    kill "$KWIN_PID" 2>/dev/null || true
    exit 42
}

export WAYLAND_DISPLAY=wayland-0
export QT_QPA_PLATFORM=wayland
kded5 >/tmp/vessel-kded.log 2>&1 &
plasmashell >/tmp/vessel-plasmashell.log 2>&1 &
PLASMA_PID=$!

for i in $(seq 1 200); do
    kill -0 "$KWIN_PID" 2>/dev/null || { wait "$KWIN_PID" || true; exit 43; }
    kill -0 "$PLASMA_PID" 2>/dev/null && {
        echo VESSEL_PLASMA_PROCESS_READY
        break
    }
    sleep .05
done

kill -0 "$PLASMA_PID" 2>/dev/null || {
    echo "plasmashell exited during startup"
    exit 44
}

wait "$KWIN_PID"
"""

    def _launch_plasma(self) -> None:
        script = self._plasma_session_script()
        encoded = base64.b64encode(script.encode()).decode()
        self.guest(
            "pkill -u vessel -x plasmashell 2>/dev/null || true; "
            "pkill -u vessel -x kwin_wayland 2>/dev/null || true; "
            "pkill -f '[s]u -l vessel -c /usr/local/bin/vessel-plasma-session' 2>/dev/null || true; "
            "rm -f /tmp/vessel-plasma-session.log /tmp/vessel-kwin.log "
            "/tmp/vessel-plasmashell.log /tmp/vessel-kded.log /tmp/vessel-plasma-launch.pid; "
            f"printf '%s' '{encoded}' | base64 -d >/usr/local/bin/vessel-plasma-session; "
            "chmod 755 /usr/local/bin/vessel-plasma-session; "
            # setsid + opening tty1 gives su/PAM a real VT so pam_elogind
            # registers a seat0 graphical session. The session itself stays an
            # ordinary, unmodified KDE/KWin Wayland stack.
            "nohup setsid sh -c '"
            "exec </dev/tty1 >/dev/tty1 2>&1; "
            "exec su -l vessel -c \"dbus-run-session -- /usr/local/bin/vessel-plasma-session\""
            "' >/tmp/vessel-plasma-launch.log 2>&1 & "
            "echo $! >/tmp/vessel-plasma-launch.pid",
            25,
        )

    def ensure_desktop(self, width: int = 1280, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del width, height, dpi
        self.start()
        self._ensure_plasma()

        self.desktop_ready = False
        self.set_progress("display_start", 76, "Starting KDE Plasma / KWin on /dev/dri/card0")
        self._launch_plasma()

        self.set_progress("display_frame", 88, "Waiting for KDE Plasma GPU scanout")
        deadline = time.monotonic() + 120
        process_ready = False
        while time.monotonic() < deadline:
            guest = self.guest(
                "echo '=== processes ==='; "
                "pgrep -u vessel -x kwin_wayland >/dev/null && echo KWIN_ALIVE || true; "
                "pgrep -u vessel -x plasmashell >/dev/null && echo PLASMA_ALIVE || true; "
                "echo '=== sessions ==='; loginctl list-sessions --no-legend 2>/dev/null || true; "
                "echo '=== libinput ==='; "
                "libinput list-devices 2>/dev/null | grep -E '^Device:.*Vessel (Touchscreen|Trackpad|Keyboard)' || true",
                12,
            )
            if "KWIN_ALIVE" not in guest:
                failure_log = self.guest(
                    "echo '=== plasma session ==='; tail -160 /tmp/vessel-plasma-session.log 2>/dev/null || true; "
                    "echo '=== kwin ==='; tail -220 /tmp/vessel-kwin.log 2>/dev/null || true; "
                    "echo '=== launch ==='; tail -100 /tmp/vessel-plasma-launch.log 2>/dev/null || true; "
                    "echo '=== loginctl ==='; loginctl list-sessions 2>&1 || true",
                    12,
                )
                raise RuntimeError("KWin exited before Plasma reached scanout:\n" + failure_log[-14000:])

            if "PLASMA_ALIVE" in guest:
                process_ready = True

            display = self._tail(DISPLAY_LOG, 14000)
            # Raw-scanout v2 does not forward all-black readback. Requiring both
            # live KWin+plasmashell and a forwarded non-black scanout proves the
            # desktop, renderer, DRM path and Android bridge are all active.
            if process_ready and "raw frame scanout=" in display and "presenter=yes" in display:
                input_check = self.guest(
                    "libinput list-devices 2>/dev/null | "
                    "grep -E '^Device:.*Vessel (Touchscreen|Trackpad|Keyboard)' | head -10 || true",
                    10,
                )
                self.desktop_ready = True
                self.last_error = ""
                self.append("KWIN_DRM_PLASMA_READY\n")
                self.append("VIRTIO_GPU_VALIDATED_FRAME_REACHED_VESSEL\n")
                self.append("VESSEL_LIBINPUT_DEVICES=" + input_check.replace("\n", " | ") + "\n")
                self.set_progress("frame_validated", 96, "Validated KDE Plasma GPU frame reached Vessel")
                return self.state()
            time.sleep(.25)

        failure_log = self.guest(
            "echo '=== plasma session ==='; tail -180 /tmp/vessel-plasma-session.log 2>/dev/null || true; "
            "echo '=== kwin ==='; tail -240 /tmp/vessel-kwin.log 2>/dev/null || true; "
            "echo '=== plasmashell ==='; tail -140 /tmp/vessel-plasmashell.log 2>/dev/null || true; "
            "echo '=== input ==='; libinput list-devices 2>/dev/null | grep -A12 -B2 Vessel || true",
            15,
        )
        raise RuntimeError(
            "No validated KDE Plasma VirtIO-GPU frame reached Vessel within 120s.\n"
            + "=== guest ===\n" + failure_log[-16000:]
            + "\n=== display ===\n" + self._tail(DISPLAY_LOG, 12000)
            + "\n=== gpu ===\n" + self._tail(GPU_LOG, 12000)
        )

    def start_desktop_async(self, width: int, height: int, dpi: int) -> dict[str, Any]:
        with self._worker_lock:
            if self._desktop_worker and self._desktop_worker.is_alive():
                return self.state()

            def worker() -> None:
                try:
                    self.ensure_desktop(width, height, dpi)
                except Exception as exc:
                    self.last_error = f"{type(exc).__name__}: {exc}"
                    self.set_progress("error", -1, self.last_error)

            self.last_error = ""
            self.set_progress("queued", 1, "Starting Vessel KDE Plasma runtime")
            self._desktop_worker = threading.Thread(target=worker, name="vessel-v38-plasma", daemon=True)
            self._desktop_worker.start()
        return self.state()

    def desktop_action(self, name: str) -> dict[str, Any]:
        if name == "gpu-test":
            return self.ensure_desktop(1280, 720, 120)
        return self.state()

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest(
                    "pkill -u vessel -x plasmashell 2>/dev/null || true; "
                    "pkill -u vessel -x kded5 2>/dev/null || true; "
                    "pkill -u vessel -x kwin_wayland 2>/dev/null || true; "
                    "pkill -f '[s]u -l vessel -c .*vessel-plasma-session' 2>/dev/null || true",
                    10,
                )
            except Exception:
                pass
        return super().stop()


runtime = VirtioGpuRuntime()


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status":
            return runtime.state()
        if action == "start":
            return runtime.start(float(req.get("timeout", 100)))
        if action == "stop":
            return runtime.stop()
        if action == "desktop":
            return runtime.ensure_desktop(int(req.get("width", 1280)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAsync":
            return runtime.start_desktop_async(int(req.get("width", 1280)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAction":
            return runtime.desktop_action(str(req.get("name", "")))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state()
            result["output"] = output
            return result
        if action == "logs":
            return runtime.state()
        return {"ok": False, "error": f"unknown action: {action}"}
    except Exception as exc:
        runtime.last_error = f"{type(exc).__name__}: {exc}"
        runtime.set_progress("error", -1, runtime.last_error)
        result = runtime.state()
        result.update({"ok": False, "error": runtime.last_error})
        return result


if __name__ == "__main__":
    def serve_connection(conn: socket.socket) -> None:
        with conn:
            try:
                conn.settimeout(5)
                data = b""
                while b"\n" not in data and len(data) < 1_000_000:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    data += chunk
                req = json.loads(data.split(b"\n", 1)[0].decode() or "{}")
                reply = handle(req)
            except Exception as exc:
                reply = {"ok": False, "error": f"protocol: {type(exc).__name__}: {exc}"}
            try:
                conn.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode())
            except OSError:
                pass

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 47631))
    srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True).start()
