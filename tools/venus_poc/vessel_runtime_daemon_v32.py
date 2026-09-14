#!/usr/bin/env python3
"""Vessel protocol 32: stock KWin nested Wayland -> dma-buf -> Android Surface.

This keeps Vessel's existing UML/Debian, Venus/umshm GPU transport, Android UI,
input bridge and control API.  The protocol-31 KWin post-processing effect is
not used.  KWin renders through its built-in nested Wayland backend into a tiny
Vessel parent compositor which forwards the compositor's dma-buf wl_buffers to
the existing Android Vulkan/SurfaceFlinger presenter without a CPU pixel copy.
"""
from __future__ import annotations

import base64
import os
import pathlib
import shlex
import time
from typing import Any

import vessel_runtime_daemon_v31 as v31

PROTOCOL_VERSION = 32
DISPLAY_TRANSPORT = "nested-wayland-dmabuf-venus-android-surface-v1"
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))


class NativeWaylandRuntime(v31.WaylandRuntime):
    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "stock KWin nested Wayland -> dma-buf -> Android Vulkan Surface",
            "renderer": "KWin/Plasma + built-in Wayland backend + Zink/Venus/Adreno",
            "framePolicy": "Wayland wl_surface commit/frame-callback driven",
            "softwareFallback": False,
            "vncPort": -1,
        })
        return state

    def _prepare_bridge(self) -> None:
        source = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_wayland_bridge.c"
        if not source.exists():
            raise RuntimeError(f"native Wayland bridge source missing: {source}")
        self.set_progress("wayland_bridge", 63, "Preparing native Wayland output backend")
        self._rpc_upload(source, "/root/vessel_wayland_bridge.c")

        have = self.guest(
            "command -v kwin_wayland >/dev/null && command -v wayland-scanner >/dev/null && "
            "pkg-config --exists wayland-server && test -f /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml && "
            "test -f /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && echo NATIVE_WAYLAND_DEPS_READY || true",
            20,
        )
        if "NATIVE_WAYLAND_DEPS_READY" not in have:
            self.set_progress("wayland_deps", 66, "Installing stock Plasma + Wayland development runtime")
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends "
                "kwin-wayland plasma-workspace plasma-desktop dbus-x11 qtwayland5 "
                "libgl1-mesa-dri mesa-utils build-essential pkg-config libwayland-dev wayland-protocols",
                1200,
            )

        build = r'''set -eu
rm -rf /root/vessel-wayland-build
mkdir -p /root/vessel-wayland-build
cd /root/vessel-wayland-build
wayland-scanner server-header /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-protocol.c
cc -O2 -pipe -std=gnu11 -Wall -Wextra -Werror=implicit-function-declaration \
  -I/root/vessel-wayland-build /root/vessel_wayland_bridge.c \
  xdg-shell-protocol.c linux-dmabuf-unstable-v1-protocol.c \
  $(pkg-config --cflags --libs wayland-server) -o /usr/local/bin/vessel-wayland-bridge
/usr/local/bin/vessel-wayland-bridge --help >/dev/null 2>&1 || true
test -x /usr/local/bin/vessel-wayland-bridge
echo VESSEL_NATIVE_WAYLAND_BRIDGE_READY
'''
        out = self.guest(build, 180)
        if "VESSEL_NATIVE_WAYLAND_BRIDGE_READY" not in out:
            raise RuntimeError("native Wayland bridge did not build")

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._prepare_bridge()

        self.set_progress("wayland_start", 82, "Starting stock KWin on Vessel's native Wayland backend")
        env = self._gpu_env()
        wrapper = f'''#!/bin/bash
set -euo pipefail
rm -rf /tmp/vessel-runtime
mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true
{env}
rm -f /tmp/vessel-native-wayland.log /tmp/vessel-kwin.log /tmp/vessel-kded.log /tmp/vessel-plasmashell.log
/usr/local/bin/vessel-wayland-bridge --socket vessel-host-0 --frame-socket /tmp/vessel-frame-export.sock --width {width} --height {height} --refresh 60 >/tmp/vessel-native-wayland.log 2>&1 &
BRIDGE_PID=$!
echo "$BRIDGE_PID" >/tmp/vessel-native-wayland.pid
for i in $(seq 1 100); do
  [ -S /tmp/vessel-runtime/vessel-host-0 ] && break
  kill -0 "$BRIDGE_PID" 2>/dev/null || {{ cat /tmp/vessel-native-wayland.log; exit 51; }}
  sleep .05
done
[ -S /tmp/vessel-runtime/vessel-host-0 ] || exit 52

export WAYLAND_DISPLAY=vessel-host-0
kwin_wayland --wayland-display vessel-host-0 --width {width} --height {height} --scale 1 --socket wayland-0 >/tmp/vessel-kwin.log 2>&1 &
KWIN_PID=$!
echo "$KWIN_PID" >/tmp/vessel-kwin.pid
for i in $(seq 1 200); do
  [ -S /tmp/vessel-runtime/wayland-0 ] && break
  kill -0 "$KWIN_PID" 2>/dev/null || {{ cat /tmp/vessel-kwin.log; exit 53; }}
  sleep .1
done
[ -S /tmp/vessel-runtime/wayland-0 ] || exit 54

export WAYLAND_DISPLAY=wayland-0
kded5 >/tmp/vessel-kded.log 2>&1 &
plasmashell >/tmp/vessel-plasmashell.log 2>&1 &
wait "$KWIN_PID"
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "pkill -x plasmashell 2>/dev/null || true; pkill -x kwin_wayland 2>/dev/null || true; "
            "pkill -f '[v]essel-wayland-bridge' 2>/dev/null || true; "
            "rm -f /tmp/vessel-wayland-session.sh /tmp/vessel-native-wayland.pid /tmp/vessel-kwin.pid; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-wayland-session.sh; "
            "chmod +x /root/vessel-wayland-session.sh; "
            "export XDG_RUNTIME_DIR=/tmp/vessel-runtime; "
            "nohup dbus-run-session -- /root/vessel-wayland-session.sh >/tmp/vessel-wayland-session.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-wayland-session.pid"
        )
        self.guest(launch, 30)

        self.set_progress("wayland_present", 91, "Waiting for native compositor buffer on Android Surface")
        deadline = time.monotonic() + 45
        relay_log = v31.base.RUNTIME / "vessel-relay.log"
        while time.monotonic() < deadline:
            status = self.guest(
                "test -S /tmp/vessel-runtime/vessel-host-0 && echo BRIDGE_SOCKET_READY || true; "
                "test -S /tmp/vessel-runtime/wayland-0 && echo KWIN_SOCKET_READY || true; "
                "pgrep -x kwin_wayland >/dev/null && echo KWIN_ALIVE || true; "
                "pgrep -x plasmashell >/dev/null && echo PLASMA_ALIVE || true; "
                "tail -40 /tmp/vessel-native-wayland.log 2>/dev/null || true; "
                "tail -30 /tmp/vessel-kwin.log 2>/dev/null || true",
                10,
            )
            low = status.lower()
            if "llvmpipe" in low or "softpipe" in low:
                raise RuntimeError("native Wayland compositor fell back to software rendering:\n" + status[-5000:])
            relay = ""
            try:
                relay = relay_log.read_text(errors="replace")[-16000:]
            except Exception:
                pass
            if ("BRIDGE_SOCKET_READY" in status and "KWIN_SOCKET_READY" in status and
                    "KWIN_ALIVE" in status and "PLASMA_ALIVE" in status and
                    "Android imported KWin object" in relay):
                self.desktop_ready = True
                self.last_error = ""
                self.append("NATIVE_WAYLAND_BRIDGE_READY\nKWIN_NESTED_WAYLAND_READY\nVENUS_DMABUF_TO_ANDROID_READY\n")
                self.set_progress("desktop_ready", 100, "Plasma live: stock Wayland backend -> GPU dma-buf -> Android Surface")
                return self.state()
            time.sleep(.25)

        guest_tail = self.guest(
            "echo '=== native bridge ==='; tail -240 /tmp/vessel-native-wayland.log 2>/dev/null || true; "
            "echo '=== kwin ==='; tail -260 /tmp/vessel-kwin.log 2>/dev/null || true; "
            "echo '=== session ==='; tail -120 /tmp/vessel-wayland-session.log 2>/dev/null || true; "
            "echo '=== plasma ==='; tail -120 /tmp/vessel-plasmashell.log 2>/dev/null || true; "
            "echo '=== sockets ==='; ls -l /tmp/.venus_test /tmp/vessel-frame-export.sock /tmp/vessel-runtime/vessel-host-0 /tmp/vessel-runtime/wayland-0 2>&1 || true",
            15,
        )
        host_tail = ""
        try:
            host_tail = relay_log.read_text(errors="replace")[-12000:]
        except Exception:
            pass
        raise RuntimeError("Native Wayland presentation did not become ready:\n" + guest_tail[-20000:] + "\n=== host relay ===\n" + host_tail)

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest(
                    "pkill -x plasmashell 2>/dev/null || true; pkill -x kwin_wayland 2>/dev/null || true; "
                    "pkill -f '[v]essel-wayland-bridge' 2>/dev/null || true",
                    10,
                )
            except Exception:
                pass
        return super().stop()


runtime = NativeWaylandRuntime()


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 100)))
        if action == "stop": return runtime.stop()
        if action == "desktop": return runtime.ensure_desktop(int(req.get("width", 1600)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAction": return runtime.desktop_action(str(req.get("name", "")))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state(); result["output"] = output; return result
        if action == "logs": return runtime.state()
        return {"ok": False, "error": f"unknown action: {action}"}
    except Exception as exc:
        runtime.last_error = f"{type(exc).__name__}: {exc}"
        runtime.set_progress("error", -1, runtime.last_error)
        result = runtime.state(); result.update({"ok": False, "error": runtime.last_error}); return result


if __name__ == "__main__":
    # Reuse protocol 31's robust line-framed control server, but make it call
    # this module's runtime/handler rather than its old global runtime.
    import json
    import socket

    def serve_connection(conn: socket.socket) -> None:
        with conn:
            try:
                conn.settimeout(5); data = b""
                while b"\n" not in data and len(data) < 1_000_000:
                    chunk = conn.recv(65536)
                    if not chunk: break
                    data += chunk
                req = json.loads(data.split(b"\n", 1)[0].decode() or "{}")
                reply = handle(req)
            except Exception as exc:
                reply = {"ok": False, "error": f"protocol: {type(exc).__name__}: {exc}"}
            try: conn.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode())
            except OSError: pass

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 47631)); srv.listen(16)
    while True:
        conn, _ = srv.accept()
        serve_connection(conn)
