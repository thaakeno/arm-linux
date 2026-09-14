#!/usr/bin/env python3
"""Vessel protocol 35: Weston/libweston desktop -> Vessel transport -> Android.

Weston is the actual desktop compositor/window manager. It runs with its Wayland
backend nested on Vessel's tiny parent transport, so UML never needs DRM/KMS,
a VT, seatd, libinput, /dev/dri or /dev/input. Weston owns the real desktop:
window management, focus, subsurfaces/popups, clipboard/DnD, XWayland, damage
tracking and repaint scheduling. The parent transport only turns Weston's one
nested output into dma-buf (preferred) or bounded wl_shm damage messages for the
Android Vulkan presenter and turns Android input into a parent wl_seat.

The parent binary and direct input helper are content-addressed and cached in the
persistent Debian image. Normal boots therefore do not compile C or run
wayland-scanner; those steps happen only on first install or when their source
actually changes.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import pathlib
import shlex
import socket
import threading
import time
from typing import Any

import vessel_runtime_daemon_v34 as v34

PROTOCOL_VERSION = 35
DISPLAY_TRANSPORT = "weston-nested-vessel-transport-venus-android-surface-v1"
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))

TRANSPORT_BIN = "/usr/local/bin/vessel-transport-host"
TRANSPORT_MARKER = "/usr/local/lib/vessel-transport-host.sha256"
INPUT_HELPER = "/usr/local/libexec/vessel-input-direct.py"
INPUT_MARKER = "/usr/local/lib/vessel-input-direct.sha256"
PARENT_DISPLAY = "vessel-host-0"
DESKTOP_DISPLAY = "vessel-desktop-0"


class WestonVesselRuntime(v34.VesselCompositorRuntime):
    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "Weston -> Vessel damage/dma-buf transport -> Android Vulkan Surface",
            "renderer": "Weston GL -> Zink/Venus/Adreno; damage-only SHM transport fallback",
            "compositor": "Weston/libweston desktop-shell (nested Wayland backend)",
            "framePolicy": "libweston damage/repaint scheduling + FIFO/vsync Android presentation",
            "seatPolicy": "Android -> parent wl_seat -> Weston (no libinput/uinput/seatd)",
            "windowPolicy": "Weston desktop-shell window management + XWayland",
            "clipboardPolicy": "Weston native Wayland selection/DnD between Linux applications",
            "imePolicy": "Android InputConnection -> XKB key stream -> Weston focused client",
            "syncPolicy": "Wayland buffer release/implicit dma-buf fencing + Vulkan fences/semaphores",
            "softwareFallback": False,
            "shmTransportFallback": "damage-only, event-driven",
            "vncPort": -1,
            "desktopWorkerAlive": bool(self._desktop_worker and self._desktop_worker.is_alive()),
        })
        return state

    @staticmethod
    def _sha256(path: pathlib.Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def _prepare_desktop_runtime(self) -> None:
        transport = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_transport_host.c"
        input_helper = POC / "tools/venus_poc/guest_input_direct_v34.py"
        if not transport.exists() or not input_helper.exists():
            raise RuntimeError("Vessel Weston transport sources are missing")

        transport_hash = self._sha256(transport)
        input_hash = self._sha256(input_helper)

        self.set_progress("desktop_deps", 59, "Checking Weston desktop runtime")
        have = self.guest(
            "command -v weston >/dev/null && command -v foot >/dev/null && "
            "command -v Xwayland >/dev/null && command -v dbus-run-session >/dev/null && "
            "command -v runuser >/dev/null && command -v useradd >/dev/null && "
            "command -v wayland-scanner >/dev/null && "
            "pkg-config --exists wayland-server xkbcommon && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && echo WESTON_DEPS_READY || true",
            20,
        )
        if "WESTON_DEPS_READY" not in have:
            self.set_progress("desktop_deps", 61, "First run: installing Weston desktop runtime")
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && "
                "apt-get install -y --no-install-recommends "
                "weston foot xwayland dbus-x11 passwd util-linux fonts-dejavu-core "
                "libgl1-mesa-dri mesa-utils vulkan-tools build-essential pkg-config "
                "libwayland-dev wayland-protocols libxkbcommon-dev libxkbcommon0",
                1200,
            )

        cached = self.guest(
            f"test -x {TRANSPORT_BIN} && test -f {TRANSPORT_MARKER} && "
            f"test \"$(cat {TRANSPORT_MARKER} 2>/dev/null)\" = {shlex.quote(transport_hash)} && "
            f"test -f {INPUT_HELPER} && test -f {INPUT_MARKER} && "
            f"test \"$(cat {INPUT_MARKER} 2>/dev/null)\" = {shlex.quote(input_hash)} && "
            "echo VESSEL_TRANSPORT_CACHE_HIT || true",
            12,
        )

        if "VESSEL_TRANSPORT_CACHE_HIT" not in cached:
            self.set_progress("transport_build", 65, "Updating cached native display transport")
            self._rpc_upload(transport, "/root/vessel_transport_host.c")
            self._rpc_upload(input_helper, "/root/vessel_input_direct.py")
            build = f'''set -eu
rm -rf /root/vessel-transport-build
mkdir -p /root/vessel-transport-build /usr/local/lib /usr/local/libexec
cd /root/vessel-transport-build
wayland-scanner server-header /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/stable/presentation-time/presentation-time.xml presentation-time-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/presentation-time/presentation-time.xml presentation-time-protocol.c
cc -O2 -pipe -fno-plt -ffunction-sections -fdata-sections -std=gnu11 -Wall -Wextra \\
  -Werror=implicit-function-declaration -I/root/vessel-transport-build \\
  /root/vessel_transport_host.c xdg-shell-protocol.c linux-dmabuf-unstable-v1-protocol.c presentation-time-protocol.c \\
  $(pkg-config --cflags --libs wayland-server xkbcommon) -Wl,--gc-sections -o {TRANSPORT_BIN}
strip --strip-unneeded {TRANSPORT_BIN} 2>/dev/null || true
install -m 0755 /root/vessel_input_direct.py {INPUT_HELPER}
printf '%s\\n' {shlex.quote(transport_hash)} > {TRANSPORT_MARKER}
printf '%s\\n' {shlex.quote(input_hash)} > {INPUT_MARKER}
test -x {TRANSPORT_BIN}
python3 -m py_compile {INPUT_HELPER}
echo VESSEL_TRANSPORT_BUILT
'''
            out = self.guest(build, 240)
            if "VESSEL_TRANSPORT_BUILT" not in out:
                raise RuntimeError("Vessel Weston transport did not build")
            self.append("VESSEL_TRANSPORT_CACHE_UPDATED\n")
        else:
            self.append("VESSEL_TRANSPORT_CACHE_HIT\n")

        self.set_progress("desktop_config", 70, "Configuring optimized Weston desktop")
        weston_ini = r'''[core]
shell=desktop-shell.so
xwayland=true
repaint-window=7
idle-time=0

[shell]
background-color=0xff08100d
panel-color=0xe8121b17
panel-position=top
locking=false
animation=fade
close-animation=fade
startup-animation=fade
focus-animation=none
binding-modifier=super
num-workspaces=2
cursor-size=24
clock-format=minutes-24h

[keyboard]
keymap_rules=evdev
keymap_model=pc105
keymap_layout=us
repeat-rate=40
repeat-delay=400
vt-switching=false

[xwayland]
path=/usr/bin/Xwayland

[autolaunch]
path=/usr/bin/foot
watch=false
'''
        ini64 = base64.b64encode(weston_ini.encode()).decode()
        configure = (
            "if ! id -u vessel >/dev/null 2>&1; then useradd --create-home --shell /bin/bash vessel; fi; "
            "mkdir -p /home/vessel/.config; "
            f"printf '%s' {shlex.quote(ini64)} | base64 -d > /home/vessel/.config/weston.ini; "
            "chown -R vessel:vessel /home/vessel/.config"
        )
        self.guest(configure, 20)

    def _launch_desktop_stack(self, width: int, height: int) -> None:
        self.set_progress("transport_start", 75, "Starting Vessel Android display transport")
        wrapper = f'''#!/bin/bash
set -euo pipefail
WESTON_PGID=""
TRANSPORT_PID=""
INPUT_PID=""
cleanup() {{
  set +e
  [ -n "$WESTON_PGID" ] && kill -TERM -- "-$WESTON_PGID" 2>/dev/null || true
  [ -n "$INPUT_PID" ] && kill -TERM "$INPUT_PID" 2>/dev/null || true
  [ -n "$TRANSPORT_PID" ] && kill -TERM "$TRANSPORT_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}}
trap cleanup EXIT INT TERM

rm -rf /tmp/vessel-runtime
mkdir -p /tmp/vessel-runtime
chown vessel:vessel /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
rm -f /tmp/vessel-transport.log /tmp/vessel-weston.log /tmp/vessel-input-direct.log
rm -f /tmp/vessel-input.sock

cp /root/virtio-wsi-test.json /tmp/vessel-virtio-wsi.json
chown vessel:vessel /tmp/vessel-virtio-wsi.json
chmod 0644 /tmp/vessel-virtio-wsi.json
chown vessel:vessel /tmp/.venus_test 2>/dev/null || true
chmod 0660 /tmp/.venus_test 2>/dev/null || true

export XDG_RUNTIME_DIR=/tmp/vessel-runtime
{TRANSPORT_BIN} \\
  --socket {PARENT_DISPLAY} \\
  --frame-socket /tmp/vessel-frame-export.sock \\
  --input-socket /tmp/vessel-input.sock \\
  --width {width} --height {height} --refresh 60 \\
  >/tmp/vessel-transport.log 2>&1 &
TRANSPORT_PID=$!
echo "$TRANSPORT_PID" >/tmp/vessel-transport.pid

for i in $(seq 1 100); do
  [ -S /tmp/vessel-runtime/{PARENT_DISPLAY} ] && [ -S /tmp/vessel-input.sock ] && break
  kill -0 "$TRANSPORT_PID" 2>/dev/null || {{ cat /tmp/vessel-transport.log; exit 71; }}
  sleep .05
done
[ -S /tmp/vessel-runtime/{PARENT_DISPLAY} ] || exit 72
[ -S /tmp/vessel-input.sock ] || exit 73
chown vessel:vessel /tmp/vessel-runtime/{PARENT_DISPLAY} /tmp/vessel-runtime/{PARENT_DISPLAY}.lock 2>/dev/null || true
chmod 0660 /tmp/vessel-runtime/{PARENT_DISPLAY} /tmp/vessel-runtime/{PARENT_DISPLAY}.lock 2>/dev/null || true

VESSEL_WAYLAND_INPUT=/tmp/vessel-input.sock \\
VESSEL_INPUT_HOST=10.0.2.2 VESSEL_INPUT_PORT=47633 \\
python3 {INPUT_HELPER} >/tmp/vessel-input-direct-stdout.log 2>&1 &
INPUT_PID=$!
echo "$INPUT_PID" >/tmp/vessel-input-direct.pid

setsid runuser -u vessel -- env \\
  HOME=/home/vessel USER=vessel LOGNAME=vessel \\
  XDG_RUNTIME_DIR=/tmp/vessel-runtime \\
  WAYLAND_DISPLAY={PARENT_DISPLAY} \\
  XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston \\
  VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest \\
  VK_DRIVER_FILES=/tmp/vessel-virtio-wsi.json \\
  MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=0 \\
  EGL_PLATFORM=wayland MOZ_ENABLE_WAYLAND=1 QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland,x11 \\
  dbus-run-session -- weston \\
    --backend=wayland-backend.so \\
    --socket={DESKTOP_DISPLAY} \\
    --display={PARENT_DISPLAY} \\
    --width={width} --height={height} --scale=1 --fullscreen \\
    --xwayland --idle-time=0 \\
    --config=/home/vessel/.config/weston.ini \\
    --log=/tmp/vessel-weston.log &
WESTON_PGID=$!
echo "$WESTON_PGID" >/tmp/vessel-weston.pgid

wait "$WESTON_PGID"
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "if [ -s /tmp/vessel-desktop-session.pid ]; then "
            "p=$(cat /tmp/vessel-desktop-session.pid 2>/dev/null || true); "
            "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; fi; "
            "rm -f /tmp/vessel-desktop-session.pid /tmp/vessel-desktop-session.sh; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-desktop-session.sh; "
            "chmod +x /root/vessel-desktop-session.sh; "
            "nohup /root/vessel-desktop-session.sh >/tmp/vessel-desktop-session.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-desktop-session.pid"
        )
        self.guest(launch, 15)

        self.set_progress("weston_start", 82, "Starting Weston/libweston desktop compositor")
        deadline = time.monotonic() + 35
        last = ""
        while time.monotonic() < deadline:
            last = self.guest(
                f"test -S /tmp/vessel-runtime/{PARENT_DISPLAY} && echo PARENT_READY || true; "
                f"test -S /tmp/vessel-runtime/{DESKTOP_DISPLAY} && echo WESTON_SOCKET_READY || true; "
                "p=$(cat /tmp/vessel-transport.pid 2>/dev/null || true); case \"$p\" in ''|*[!0-9]*) ;; *) kill -0 \"$p\" 2>/dev/null && echo TRANSPORT_ALIVE || true ;; esac; "
                "p=$(cat /tmp/vessel-weston.pgid 2>/dev/null || true); case \"$p\" in ''|*[!0-9]*) ;; *) kill -0 \"$p\" 2>/dev/null && echo WESTON_ALIVE || true ;; esac; "
                "echo '=== weston ==='; tail -90 /tmp/vessel-weston.log 2>/dev/null || true; "
                "echo '=== transport ==='; tail -50 /tmp/vessel-transport.log 2>/dev/null || true; "
                "echo '=== input ==='; tail -20 /tmp/vessel-input-direct.log 2>/dev/null || true",
                8,
            )
            low = last.lower()
            if any(bad in low for bad in (
                "fatal: failed", "failed to create compositor", "failed to load backend",
                "failed to initialize egl", "llvmpipe", "softpipe", "using pixman renderer",
            )):
                raise RuntimeError("Weston entered a forbidden software/broken renderer path:\n" + last[-12000:])
            if (
                "PARENT_READY" in last
                and "WESTON_SOCKET_READY" in last
                and "TRANSPORT_ALIVE" in last
                and "WESTON_ALIVE" in last
                and ("GL renderer" in last or "gl-renderer.so" in last)
                and ("dmabuf-import" in last or "shm-damage" in last)
            ):
                self.set_progress("desktop_surface", 96, "Weston frame reached Android transport")
                return
            time.sleep(.2)

        tail = self.guest(
            "echo '=== session ==='; tail -120 /tmp/vessel-desktop-session.log 2>/dev/null || true; "
            "echo '=== weston ==='; tail -240 /tmp/vessel-weston.log 2>/dev/null || true; "
            "echo '=== transport ==='; tail -200 /tmp/vessel-transport.log 2>/dev/null || true; "
            "echo '=== guest relay ==='; tail -180 /tmp/vessel-guest-wayland.log 2>/dev/null || true; "
            "echo '=== input ==='; tail -100 /tmp/vessel-input-direct.log 2>/dev/null || true",
            12,
        )
        raise RuntimeError("Weston desktop did not produce a GPU/damage frame:\n" + tail[-18000:])

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._prepare_desktop_runtime()
        self._launch_desktop_stack(width, height)

        self.desktop_ready = True
        self.last_error = ""
        self.append(
            "WESTON_DESKTOP_READY\n"
            "VESSEL_TRANSPORT_READY\n"
            "DIRECT_WAYLAND_INPUT_READY\n"
            "DAMAGE_DRIVEN_PRESENTATION_READY\n"
            "NO_DRM_NO_VT_NO_SEATD_NO_LIBINPUT\n"
        )
        self.set_progress("desktop_ready", 100, "Weston desktop live on Android Surface")
        return self.state()

    def desktop_action(self, name: str) -> dict[str, Any]:
        commands = {
            "terminal": "foot",
            "firefox": "sh -lc 'command -v firefox-esr >/dev/null && exec firefox-esr || command -v firefox >/dev/null && exec firefox || exec foot -T Firefox-not-installed'",
            "files": "sh -lc 'command -v dolphin >/dev/null && exec dolphin || command -v thunar >/dev/null && exec thunar || exec foot -T Files-not-installed'",
            "dolphin": "sh -lc 'command -v dolphin >/dev/null && exec dolphin || command -v thunar >/dev/null && exec thunar || exec foot -T Files-not-installed'",
            "kate": "sh -lc 'command -v kate >/dev/null && exec kate || exec foot -T Kate-not-installed'",
        }
        command = commands.get(name)
        if command:
            env = (
                f"HOME=/home/vessel USER=vessel LOGNAME=vessel XDG_RUNTIME_DIR=/tmp/vessel-runtime "
                f"WAYLAND_DISPLAY={DESKTOP_DISPLAY} XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston "
                "VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/tmp/vessel-virtio-wsi.json "
                "MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=0 "
                "MOZ_ENABLE_WAYLAND=1 QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland,x11"
            )
            qcmd = shlex.quote(command)
            log = shlex.quote(f"/tmp/vessel-app-{name}.log")
            self.guest(
                f"runuser -u vessel -- env {env} sh -lc {qcmd} >{log} 2>&1 </dev/null & true",
                8,
            )
            return self.state()
        return super().desktop_action(name)

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest(
                    "if [ -s /tmp/vessel-desktop-session.pid ]; then "
                    "p=$(cat /tmp/vessel-desktop-session.pid 2>/dev/null || true); "
                    "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; fi; "
                    "if [ -s /tmp/vessel-weston.pgid ]; then "
                    "p=$(cat /tmp/vessel-weston.pgid 2>/dev/null || true); "
                    "case \"$p\" in ''|*[!0-9]*) ;; *) kill -TERM -- \"-$p\" 2>/dev/null || true ;; esac; fi; "
                    "for f in /tmp/vessel-input-direct.pid /tmp/vessel-transport.pid; do "
                    "p=$(cat \"$f\" 2>/dev/null || true); case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; done; "
                    "rm -f /tmp/vessel-desktop-session.pid /tmp/vessel-weston.pgid /tmp/vessel-input-direct.pid /tmp/vessel-transport.pid",
                    10,
                )
            except Exception:
                pass
        return super().stop()


runtime = WestonVesselRuntime()


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
            return runtime.ensure_desktop(
                int(req.get("width", 1600)), int(req.get("height", 720)), int(req.get("dpi", 120))
            )
        if action == "desktopAsync":
            return runtime.start_desktop_async(
                int(req.get("width", 1600)), int(req.get("height", 720)), int(req.get("dpi", 120))
            )
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
