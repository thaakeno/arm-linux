#!/usr/bin/env python3
"""Vessel protocol 35: advanced mobile Wayland compositor runtime.

The compositor is purpose-built for UML rather than pretending the guest owns
DRM/KMS or a VT. GPU clients use Zink/Venus + dma-buf; wl_shm clients use bounded
damage-only updates into the Android Vulkan presenter. Android input goes
straight to the Wayland seat, and the compositor is cached after the first build
so normal boots do not run a compiler or wayland-scanner.
"""
from __future__ import annotations

import base64
import hashlib
import os
import pathlib
import shlex
import threading
import time
from typing import Any

import vessel_runtime_daemon_v34 as v34

PROTOCOL_VERSION = 35
DISPLAY_TRANSPORT = "vessel-compositor-v35-dmabuf-shm-venus-android-surface-v3"
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))


class AdvancedVesselRuntime(v34.VesselCompositorRuntime):
    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "Vessel WM -> dma-buf/SHM damage -> Android Vulkan Surface",
            "renderer": "Zink/Venus dma-buf zero-copy + damage-only SHM Vulkan staging",
            "compositor": "Vessel Mobile Compositor v35",
            "framePolicy": "damage-driven, refresh-throttled, idle when clients are idle",
            "seatPolicy": "direct Android -> wl_seat (no libinput/uinput/seatd)",
            "windowPolicy": "single-output mobile WM with activation/focus + popup/subsurface roles",
            "clipboardPolicy": "native Wayland data-device selection forwarding",
            "imePolicy": "text-input-v3 bridged from Android InputConnection",
            "presentationPolicy": "presentation-time + FIFO Android Vulkan present",
            "softwareFallback": False,
            "shmClientFallback": "damage-only",
            "vncPort": -1,
        })
        return state

    @staticmethod
    def _source_hash(source: pathlib.Path) -> str:
        return hashlib.sha256(source.read_bytes()).hexdigest()

    def _prepare_runtime(self) -> None:
        source = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_compositor_v35.c"
        if not source.exists():
            raise RuntimeError(f"advanced Vessel compositor source missing: {source}")
        digest = self._source_hash(source)
        marker = "/usr/local/lib/vessel-compositor-v35.sha256"
        binary = "/usr/local/bin/vessel-compositor-v35"

        self.set_progress("compositor_cache", 60, "Checking cached Vessel compositor")
        cached = self.guest(
            f"test -x {binary} && test -f {marker} && "
            f"test \"$(cat {marker} 2>/dev/null)\" = {shlex.quote(digest)} && "
            "command -v foot >/dev/null && echo VESSEL_V35_CACHE_HIT || true",
            12,
        )
        if "VESSEL_V35_CACHE_HIT" in cached:
            self.append("VESSEL_COMPOSITOR_CACHE_HIT\n")
            self.set_progress("compositor_cached", 70, "Cached compositor ready")
            return

        self.set_progress("compositor_deps", 62, "Preparing compositor dependencies (first run only)")
        have = self.guest(
            "command -v wayland-scanner >/dev/null && command -v foot >/dev/null && "
            "pkg-config --exists wayland-server xkbcommon && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && echo V35_DEPS_READY || true",
            20,
        )
        if "V35_DEPS_READY" not in have:
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && "
                "apt-get install -y --no-install-recommends "
                "foot libgl1-mesa-dri mesa-utils vulkan-tools build-essential pkg-config "
                "libwayland-dev wayland-protocols libxkbcommon-dev libxkbcommon0",
                1200,
            )

        self.set_progress("compositor_build", 66, "Building optimized compositor once")
        self._rpc_upload(source, "/root/vessel_compositor_v35.c")
        build = f'''set -eu
rm -rf /root/vessel-compositor-v35-build
mkdir -p /root/vessel-compositor-v35-build /usr/local/lib
cd /root/vessel-compositor-v35-build
wayland-scanner server-header /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/unstable/text-input/text-input-unstable-v3.xml text-input-unstable-v3-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/unstable/text-input/text-input-unstable-v3.xml text-input-unstable-v3-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/stable/presentation-time/presentation-time.xml presentation-time-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/presentation-time/presentation-time.xml presentation-time-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/unstable/xdg-decoration/xdg-decoration-unstable-v1.xml xdg-decoration-unstable-v1-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/unstable/xdg-decoration/xdg-decoration-unstable-v1.xml xdg-decoration-unstable-v1-protocol.c
cc -O2 -pipe -fno-plt -ffunction-sections -fdata-sections -std=gnu11 -Wall -Wextra \
  -Werror=implicit-function-declaration -I/root/vessel-compositor-v35-build \
  /root/vessel_compositor_v35.c xdg-shell-protocol.c linux-dmabuf-unstable-v1-protocol.c \
  text-input-unstable-v3-protocol.c presentation-time-protocol.c xdg-decoration-unstable-v1-protocol.c \
  $(pkg-config --cflags --libs wayland-server xkbcommon) -Wl,--gc-sections -o {binary}
strip --strip-unneeded {binary} 2>/dev/null || true
printf '%s\\n' {shlex.quote(digest)} > {marker}
test -x {binary}
echo VESSEL_V35_BUILT
'''
        out = self.guest(build, 240)
        if "VESSEL_V35_BUILT" not in out:
            raise RuntimeError("advanced Vessel compositor did not build")
        self.append("VESSEL_COMPOSITOR_CACHE_WRITTEN\n")

    def _gpu_env(self) -> str:
        return r'''export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
export VK_DRIVER_FILES=/root/virtio-wsi-test.json
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export LIBGL_ALWAYS_SOFTWARE=0
export EGL_PLATFORM=wayland
'''

    def _launch_compositor(self, width: int, height: int) -> None:
        self.set_progress("compositor_start", 76, "Starting Vessel Mobile Compositor")
        wrapper = f'''#!/bin/bash
set -euo pipefail
rm -rf /tmp/vessel-runtime
mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
rm -f /tmp/vessel-compositor.log
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
exec /usr/local/bin/vessel-compositor-v35 \
  --socket vessel-0 \
  --frame-socket /tmp/vessel-frame-export.sock \
  --input-host 10.0.2.2 --input-port 47633 \
  --width {width} --height {height} --refresh 60
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "if [ -s /tmp/vessel-compositor.pid ]; then "
            "oldpid=$(cat /tmp/vessel-compositor.pid 2>/dev/null || true); "
            "case \"$oldpid\" in ''|*[!0-9]*) ;; *) kill \"$oldpid\" 2>/dev/null || true ;; esac; fi; "
            "rm -f /tmp/vessel-compositor-session.sh /tmp/vessel-compositor.pid; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-compositor-session.sh; "
            "chmod +x /root/vessel-compositor-session.sh; "
            "nohup /root/vessel-compositor-session.sh >/tmp/vessel-compositor.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-compositor.pid"
        )
        self.guest(launch, 15)

        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            status = self.guest(
                "test -S /tmp/vessel-runtime/vessel-0 && echo SOCKET_READY || true; "
                "pid=$(cat /tmp/vessel-compositor.pid 2>/dev/null || true); "
                "case \"$pid\" in ''|*[!0-9]*) ;; *) kill -0 \"$pid\" 2>/dev/null && echo COMPOSITOR_ALIVE || true ;; esac; "
                "tail -50 /tmp/vessel-compositor.log 2>/dev/null || true",
                8,
            )
            if "SOCKET_READY" in status and "COMPOSITOR_ALIVE" in status and "READY v35" in status:
                self.set_progress("compositor_ready", 84, "Wayland compositor + direct input ready")
                return
            time.sleep(.12)
        tail = self.guest("tail -200 /tmp/vessel-compositor.log 2>/dev/null || true", 10)
        raise RuntimeError("Vessel v35 compositor did not become ready:\n" + tail[-9000:])

    def _launch_session(self) -> None:
        self.set_progress("session_start", 88, "Launching first Wayland workspace")
        env = self._gpu_env()
        cmd = f'''set -eu
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
export WAYLAND_DISPLAY=vessel-0
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=Vessel
{env}
pkill -x foot 2>/dev/null || true
rm -f /tmp/vessel-terminal.log
nohup foot --title='Vessel Terminal' >/tmp/vessel-terminal.log 2>&1 </dev/null &
echo $! >/tmp/vessel-session-client.pid
'''
        self.guest(cmd, 12)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            out = self.guest(
                "pid=$(cat /tmp/vessel-session-client.pid 2>/dev/null || true); "
                "case \"$pid\" in ''|*[!0-9]*) ;; *) kill -0 \"$pid\" 2>/dev/null && echo CLIENT_ALIVE || true ;; esac; "
                "grep -F 'frame transport connected' /tmp/vessel-compositor.log 2>/dev/null | tail -1 || true; "
                "tail -30 /tmp/vessel-terminal.log 2>/dev/null || true",
                8,
            )
            if "CLIENT_ALIVE" in out and "frame transport connected" in out:
                self.set_progress("surface_live", 96, "First client frame reached the Android relay")
                return
            time.sleep(.15)
        tail = self.guest(
            "echo '=== compositor ==='; tail -120 /tmp/vessel-compositor.log 2>/dev/null || true; "
            "echo '=== foot ==='; tail -120 /tmp/vessel-terminal.log 2>/dev/null || true; "
            "echo '=== guest relay ==='; tail -120 /tmp/vessel-guest-wayland.log 2>/dev/null || true",
            10,
        )
        raise RuntimeError("Wayland session client did not produce a frame:\n" + tail[-12000:])

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._prepare_runtime()
        self._launch_compositor(width, height)
        self._launch_session()
        self.desktop_ready = True
        self.last_error = ""
        self.append(
            "VESSEL_DESKTOP_V35_READY\n"
            "DIRECT_WAYLAND_INPUT_READY\n"
            "DMABUF_ZERO_COPY_PLUS_SHM_DAMAGE_READY\n"
            "NO_DRM_NO_VT_NO_SEATD_NO_LIBINPUT\n"
        )
        self.set_progress("desktop_ready", 100, "Vessel Desktop live")
        return self.state()

    def desktop_action(self, name: str) -> dict[str, Any]:
        env = self._gpu_env()
        display = f"export XDG_RUNTIME_DIR=/tmp/vessel-runtime WAYLAND_DISPLAY=vessel-0 XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Vessel; {env} "
        commands = {
            "terminal": "foot --title='Vessel Terminal'",
            "firefox": "sh -lc 'command -v firefox-esr >/dev/null && exec firefox-esr || command -v firefox >/dev/null && exec firefox || exec foot -T Firefox-not-installed'",
            "files": "sh -lc 'command -v dolphin >/dev/null && exec dolphin || command -v thunar >/dev/null && exec thunar || exec foot -T Files-not-installed'",
            "kate": "sh -lc 'command -v kate >/dev/null && exec kate || exec foot -T Kate-not-installed'",
        }
        command = commands.get(name)
        if command:
            self.guest(display + f"nohup {command} >/tmp/vessel-app-{shlex.quote(name)}.log 2>&1 </dev/null &", 8)
            return self.state()
        return super().desktop_action(name)


runtime = AdvancedVesselRuntime()


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
            return runtime.ensure_desktop(int(req.get("width", 1600)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAsync":
            return runtime.start_desktop_async(int(req.get("width", 1600)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAction":
            return runtime.desktop_action(str(req.get("name", "")))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state(); result["output"] = output; return result
        if action == "logs":
            return runtime.state()
        return {"ok": False, "error": f"unknown action: {action}"}
    except Exception as exc:
        runtime.last_error = f"{type(exc).__name__}: {exc}"
        runtime.set_progress("error", -1, runtime.last_error)
        result = runtime.state(); result.update({"ok": False, "error": runtime.last_error}); return result


if __name__ == "__main__":
    import json
    import socket

    def serve_connection(conn: socket.socket) -> None:
        with conn:
            try:
                conn.settimeout(5)
                data = b""
                while b"\n" not in data and len(data) < 1_000_000:
                    chunk = conn.recv(65536)
                    if not chunk: break
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
    srv.bind(("127.0.0.1", 47631)); srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True).start()
