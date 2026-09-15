#!/usr/bin/env python3
"""Vessel protocol 38: Debian UML + VirtIO GPU + direct-DRM KDE Plasma.

Protocol 38 r3 deliberately avoids requiring logind/elogind in the minimal UML
guest. The guest already contains KDE Plasma. Xorg owns the virtio-gpu DRM/KMS
device directly as root, uses the modesetting/glamor path with Mesa VirGL, and
the normal Plasma X11 session runs unmodified KWin/plasmashell as the vessel
user. The host vhost-user GPU continues through virglrenderer -> ANGLE/Vulkan
-> Adreno. Final scanout reaches the Android Vulkan SurfaceView through
Vessel's RGB loopback bridge.

No kmscube, VNC, screenshots, nested Weston, Termux:X11, guest Venus, software
renderer, elogind, or custom KWin output plugin is used.
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
RUNTIME_REVISION = "v38-virtio-gpu-plasma-r3"
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
            "renderer": "KDE Plasma/KWin X11 -> Xorg modesetting/glamor -> Mesa VirGL -> virglrenderer -> ANGLE/Vulkan -> Adreno",
            "rendererMode": "virgl-opengl",
            "compositor": "KWin X11 on Xorg direct DRM/KMS",
            "desktopName": "KDE Plasma X11 (direct DRM)",
            "translationLayer": "VirGL",
            "inputMode": "Linux uinput -> evdev/libinput -> Xorg/KWin",
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
        # Name retained only because Runtime.start() calls this virtual hook.
        # Protocol 38 deliberately does not configure or use guest Venus.
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
        # Plasma itself already exists on upgraded Vessel disks. r3 only needs
        # the direct-DRM Xorg/KWin X11 pieces and libinput. Never swap the init
        # stack: this UML guest intentionally boots with /umarm-init, so adding
        # elogind/libpam-elogind conflicts with the existing systemd packages.
        have = self.guest(
            "command -v startplasma-x11 >/dev/null && "
            "command -v kwin_x11 >/dev/null && "
            "command -v Xorg >/dev/null && "
            "command -v dbus-run-session >/dev/null && "
            "command -v udevadm >/dev/null && "
            "command -v libinput >/dev/null && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so && "
            "echo VESSEL_PLASMA_DRM_READY || true",
            15,
        )
        if "VESSEL_PLASMA_DRM_READY" not in have:
            self.set_progress("plasma_deps", 60, "Preparing KDE Plasma direct-DRM session")
            out = self.guest(
                "export DEBIAN_FRONTEND=noninteractive; "
                "apt-get update && "
                "apt-get install -y -t bookworm --no-install-recommends "
                "plasma-workspace plasma-desktop kwin-x11 "
                "xserver-xorg-core xserver-xorg-input-libinput "
                "dbus dbus-x11 udev libinput-tools mesa-utils",
                1800,
            )
            check = self.guest(
                "command -v startplasma-x11 >/dev/null && "
                "command -v kwin_x11 >/dev/null && "
                "command -v Xorg >/dev/null && "
                "command -v dbus-run-session >/dev/null && "
                "command -v udevadm >/dev/null && "
                "command -v libinput >/dev/null && "
                "test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so && "
                "echo VESSEL_PLASMA_DRM_READY || true",
                20,
            )
            if "VESSEL_PLASMA_DRM_READY" not in check:
                raise RuntimeError("KDE Plasma direct-DRM prerequisites are missing:\n" + out[-10000:])

        self.set_progress("plasma_session_services", 68, "Preparing udev, D-Bus, Xorg DRM and native input")
        prep = self.guest(
            "set -e; "
            "mkdir -p /run/dbus /run/user /etc/X11/xorg.conf.d /tmp/.X11-unix; "
            "dbus-uuidgen --ensure=/etc/machine-id; "
            "(pgrep -x systemd-udevd >/dev/null || "
            "(/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udevd-start.log || "
            "/usr/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udevd-start.log)); "
            "udevadm control --reload-rules || true; "
            "udevadm trigger --action=add || true; "
            "udevadm settle --timeout=10 || true; "
            "test -S /run/dbus/system_bus_socket || dbus-daemon --system --fork; "
            "id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel; "
            "for g in video render input; do getent group \"$g\" >/dev/null || groupadd \"$g\"; done; "
            "usermod -a -G video,render,input vessel; "
            "uid=$(id -u vessel); gid=$(id -g vessel); "
            "mkdir -p /run/user/$uid; chown $uid:$gid /run/user/$uid; chmod 700 /run/user/$uid; "
            "test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1; "
            "udevadm trigger --subsystem-match=input --action=add || true; "
            "udevadm settle --timeout=10 || true; "
            "names=$(cat /sys/class/input/event*/device/name 2>/dev/null || true); "
            "printf '%s\\n' \"$names\" | grep -Fx 'Vessel Touchscreen' >/dev/null; "
            "printf '%s\\n' \"$names\" | grep -Fx 'Vessel Trackpad' >/dev/null; "
            "printf '%s\\n' \"$names\" | grep -Fx 'Vessel Keyboard' >/dev/null; "
            "cat >/etc/X11/xorg.conf.d/99-vessel-gpu.conf <<'EOF'\n"
            "Section \"ServerFlags\"\n"
            "    Option \"AutoAddDevices\" \"true\"\n"
            "    Option \"DontVTSwitch\" \"true\"\n"
            "EndSection\n"
            "Section \"Device\"\n"
            "    Identifier \"Vessel VirtIO GPU\"\n"
            "    Driver \"modesetting\"\n"
            "    Option \"kmsdev\" \"/dev/dri/card0\"\n"
            "    Option \"AccelMethod\" \"glamor\"\n"
            "EndSection\n"
            "EOF\n"
            "echo VESSEL_DIRECT_DRM_INPUT_READY",
            40,
        )
        if "VESSEL_DIRECT_DRM_INPUT_READY" not in prep:
            diag = self.guest(
                "echo '=== dri ==='; ls -l /dev/dri 2>&1 || true; "
                "echo '=== input devices ==='; "
                "for f in /sys/class/input/event*/device/name; do printf '%s: ' \"$f\"; cat \"$f\" 2>/dev/null; done; "
                "echo '=== udev ==='; pgrep -a systemd-udevd || true; "
                "tail -100 /tmp/vessel-udevd-start.log 2>/dev/null || true",
                12,
            )
            raise RuntimeError("Vessel direct DRM/input preparation failed:\n" + diag[-10000:])

    @staticmethod
    def _plasma_session_script() -> str:
        return r"""#!/bin/bash
set -euo pipefail
exec >/tmp/vessel-plasma-session.log 2>&1

export DISPLAY=:0
export XDG_SESSION_TYPE=x11
export XDG_SESSION_DESKTOP=KDE
export XDG_CURRENT_DESKTOP=KDE
export DESKTOP_SESSION=plasma
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=5
export LIBGL_ALWAYS_SOFTWARE=0
export QT_XCB_GL_INTEGRATION=xcb_glx
export GALLIUM_DRIVER=virgl
unset WAYLAND_DISPLAY WAYLAND_SOCKET

UID_NOW=$(id -u)
export XDG_RUNTIME_DIR=/run/user/$UID_NOW
test -d "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

echo "display=$DISPLAY runtime=$XDG_RUNTIME_DIR"
exec startplasma-x11
"""

    def _launch_plasma(self) -> None:
        script = self._plasma_session_script()
        encoded = base64.b64encode(script.encode()).decode()
        self.guest(
            "pkill -u vessel -x plasmashell 2>/dev/null || true; "
            "pkill -u vessel -x plasma_session 2>/dev/null || true; "
            "pkill -u vessel -x ksmserver 2>/dev/null || true; "
            "pkill -u vessel -x kded5 2>/dev/null || true; "
            "pkill -u vessel -x kwin_x11 2>/dev/null || true; "
            "pkill -u vessel -f '[s]tartplasma-x11' 2>/dev/null || true; "
            "pkill -f '[X]org :0' 2>/dev/null || true; "
            "rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 "
            "/tmp/vessel-xorg.log /tmp/vessel-plasma-session.log "
            "/tmp/vessel-plasma-launch.log /tmp/vessel-xorg.pid /tmp/vessel-plasma-launch.pid; "
            f"printf '%s' '{encoded}' | base64 -d >/usr/local/bin/vessel-plasma-session; "
            "chmod 755 /usr/local/bin/vessel-plasma-session; "
            "nohup setsid sh -c '"
            "exec </dev/tty1 >/dev/tty1 2>&1; "
            "exec env LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl "
            "Xorg :0 -ac -noreset -nolisten tcp -novtswitch -sharevts vt1"
            "' >/tmp/vessel-xorg.log 2>&1 & "
            "echo $! >/tmp/vessel-xorg.pid; "
            "for i in $(seq 1 160); do "
            "  test -S /tmp/.X11-unix/X0 && break; "
            "  kill -0 $(cat /tmp/vessel-xorg.pid) 2>/dev/null || break; "
            "  sleep .1; "
            "done; "
            "test -S /tmp/.X11-unix/X0 || { "
            "  echo XORG_FAILED; tail -160 /tmp/vessel-xorg.log 2>/dev/null; exit 71; "
            "}; "
            "DISPLAY=:0 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl glxinfo -B >/tmp/vessel-glxinfo.log 2>&1 || { "
            "  echo GLX_PROBE_FAILED; cat /tmp/vessel-glxinfo.log; exit 72; "
            "}; "
            "! grep -Eqi 'llvmpipe|softpipe|swrast|software rasterizer' /tmp/vessel-glxinfo.log || { "
            "  echo SOFTWARE_RENDERER_REJECTED; cat /tmp/vessel-glxinfo.log; exit 73; "
            "}; "
            "grep -Eqi 'virgl|virtio|Mesa' /tmp/vessel-glxinfo.log || { "
            "  echo VIRGL_RENDERER_NOT_CONFIRMED; cat /tmp/vessel-glxinfo.log; exit 74; "
            "}; "
            "nohup su -l vessel -c "
            "\"DISPLAY=:0 XDG_RUNTIME_DIR=/run/user/$(id -u vessel) "
            "dbus-run-session -- /usr/local/bin/vessel-plasma-session\" "
            ">/tmp/vessel-plasma-launch.log 2>&1 & "
            "echo $! >/tmp/vessel-plasma-launch.pid",
            40,
        )

    def ensure_desktop(self, width: int = 1280, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        # The virtual monitor mode is supplied by the already-proven protocol-38
        # VirtIO GPU frontend. Dynamic mode/DPI changes are a later milestone.
        del width, height, dpi
        self.start()
        self._ensure_plasma()

        self.desktop_ready = False
        self.set_progress("plasma_start", 76, "Starting KDE Plasma on direct VirtIO DRM/KMS")
        self._launch_plasma()

        self.set_progress("plasma_frame", 88, "Waiting for KDE Plasma GPU scanout")
        started = time.monotonic()
        deadline = started + 120
        process_ready = False
        while time.monotonic() < deadline:
            guest = self.guest(
                "echo '=== processes ==='; "
                "pgrep -x Xorg >/dev/null && echo XORG_ALIVE || true; "
                "pgrep -u vessel -x kwin_x11 >/dev/null && echo KWIN_ALIVE || true; "
                "pgrep -u vessel -x plasmashell >/dev/null && echo PLASMA_ALIVE || true; "
                "pgrep -u vessel -x plasma_session >/dev/null && echo PLASMA_SESSION_ALIVE || true; "
                "echo '=== glx ==='; tail -30 /tmp/vessel-glxinfo.log 2>/dev/null || true; "
                "echo '=== libinput ==='; "
                "libinput list-devices 2>/dev/null | grep -E '^Device:.*Vessel (Touchscreen|Trackpad|Keyboard)' || true",
                12,
            )

            if "XORG_ALIVE" in guest and "KWIN_ALIVE" in guest and "PLASMA_ALIVE" in guest:
                process_ready = True
            elif time.monotonic() - started > 15:
                failure_log = self.guest(
                    "echo '=== xorg ==='; tail -260 /tmp/vessel-xorg.log 2>/dev/null || true; "
                    "echo '=== plasma session ==='; tail -260 /tmp/vessel-plasma-session.log 2>/dev/null || true; "
                    "echo '=== launch ==='; tail -120 /tmp/vessel-plasma-launch.log 2>/dev/null || true; "
                    "echo '=== glx ==='; cat /tmp/vessel-glxinfo.log 2>/dev/null || true; "
                    "echo '=== processes ==='; ps -ef | grep -E 'Xorg|startplasma|plasma_session|kwin_x11|plasmashell|ksmserver' | grep -v grep || true",
                    15,
                )
                raise RuntimeError("KDE Plasma direct-DRM session failed before scanout:\n" + failure_log[-18000:])

            display = self._tail(DISPLAY_LOG, 14000)
            # Raw-scanout v2 never forwards an all-black readback. Requiring
            # live Xorg+KWin+plasmashell plus a forwarded non-black frame proves
            # a real Plasma frame traversed DRM/VirGL and the Android bridge.
            if process_ready and "raw frame scanout=" in display and "presenter=yes" in display:
                input_check = self.guest(
                    "libinput list-devices 2>/dev/null | "
                    "grep -E '^Device:.*Vessel (Touchscreen|Trackpad|Keyboard)' | head -10 || true",
                    10,
                )
                if "Vessel Touchscreen" not in input_check or "Vessel Trackpad" not in input_check or "Vessel Keyboard" not in input_check:
                    raise RuntimeError(
                        "Plasma is visible but Linux libinput did not enumerate all Vessel input devices:\n"
                        + input_check
                    )
                self.desktop_ready = True
                self.last_error = ""
                self.append("KWIN_X11_DRM_PLASMA_READY\n")
                self.append("VIRTIO_GPU_VALIDATED_FRAME_REACHED_VESSEL\n")
                self.append("VESSEL_LIBINPUT_DEVICES=" + input_check.replace("\n", " | ") + "\n")
                self.set_progress(
                    "plasma_frame_validated",
                    96,
                    "Validated KDE Plasma direct-DRM GPU frame + libinput devices reached Vessel",
                )
                return self.state()
            time.sleep(.25)

        failure_log = self.guest(
            "echo '=== xorg ==='; tail -320 /tmp/vessel-xorg.log 2>/dev/null || true; "
            "echo '=== plasma session ==='; tail -300 /tmp/vessel-plasma-session.log 2>/dev/null || true; "
            "echo '=== launch ==='; tail -140 /tmp/vessel-plasma-launch.log 2>/dev/null || true; "
            "echo '=== glx ==='; cat /tmp/vessel-glxinfo.log 2>/dev/null || true; "
            "echo '=== processes ==='; ps -ef | grep -E 'Xorg|startplasma|plasma_session|kwin_x11|plasmashell|ksmserver' | grep -v grep || true; "
            "echo '=== input ==='; libinput list-devices 2>/dev/null | grep -A12 -B2 Vessel || true",
            15,
        )
        raise RuntimeError(
            "No validated KDE Plasma VirtIO-GPU frame reached Vessel within 120s.\n"
            + "=== guest ===\n" + failure_log[-20000:]
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
                    "pkill -u vessel -x plasma_session 2>/dev/null || true; "
                    "pkill -u vessel -x ksmserver 2>/dev/null || true; "
                    "pkill -u vessel -x kded5 2>/dev/null || true; "
                    "pkill -u vessel -x kwin_x11 2>/dev/null || true; "
                    "pkill -u vessel -f '[s]tartplasma-x11' 2>/dev/null || true; "
                    "pkill -f '[X]org :0' 2>/dev/null || true; "
                    "rm -f /tmp/.X0-lock /tmp/.X11-unix/X0",
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
