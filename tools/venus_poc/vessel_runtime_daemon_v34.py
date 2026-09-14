#!/usr/bin/env python3
"""Vessel protocol 34: direct Wayland compositor -> dma-buf -> Android Surface.

This runtime deliberately does not use Sway, wlroots session backends, seatd,
libinput, DRM/KMS or a host VT.  The guest-side Vessel compositor is a tiny
libwayland-server compositor tailored to UML: xdg-shell + linux-dmabuf in,
Vessel's frame relay out.  GPU buffers remain on the Venus/umshm path.
"""
from __future__ import annotations

import base64
import os
import pathlib
import shlex
import threading
import time
from typing import Any

import vessel_runtime_daemon_v33 as v33

PROTOCOL_VERSION = 34
DISPLAY_TRANSPORT = "vessel-compositor-dmabuf-venus-android-surface-v2"
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))


class VesselCompositorRuntime(v33.WlrootsRuntime):
    def __init__(self) -> None:
        super().__init__()
        self._desktop_worker: threading.Thread | None = None
        self._worker_lock = threading.Lock()

    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "Vessel compositor -> dma-buf -> Android native Surface",
            "renderer": "client Zink/Venus -> dma-buf -> host Adreno GPU",
            "compositor": "Vessel compositor (libwayland-server, no DRM/VT/seatd)",
            "framePolicy": "Wayland surface commit/frame-callback driven",
            "softwareFallback": False,
            "vncPort": -1,
            "desktopWorkerAlive": bool(self._desktop_worker and self._desktop_worker.is_alive()),
        })
        return state

    def _prepare_runtime(self) -> None:
        source = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_wayland_bridge.c"
        if not source.exists():
            raise RuntimeError(f"Vessel compositor source missing: {source}")

        self.set_progress("compositor_prepare", 60, "Preparing Vessel Wayland compositor")
        self._rpc_upload(source, "/root/vessel_compositor.c")

        have = self.guest(
            "command -v wayland-scanner >/dev/null && pkg-config --exists wayland-server && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && echo VESSEL_COMPOSITOR_DEPS_READY || true",
            20,
        )
        if "VESSEL_COMPOSITOR_DEPS_READY" not in have:
            self.set_progress("compositor_deps", 63, "Installing minimal Wayland runtime")
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && "
                "apt-get install -y --no-install-recommends "
                "libgl1-mesa-dri mesa-utils vulkan-tools build-essential pkg-config "
                "libwayland-dev wayland-protocols",
                1200,
            )

        build = r'''set -eu
rm -rf /root/vessel-compositor-build
mkdir -p /root/vessel-compositor-build
cd /root/vessel-compositor-build
wayland-scanner server-header /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-protocol.c
wayland-scanner server-header /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-server-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml linux-dmabuf-unstable-v1-protocol.c
cc -O2 -pipe -std=gnu11 -Wall -Wextra -Werror=implicit-function-declaration \
  -I/root/vessel-compositor-build /root/vessel_compositor.c \
  xdg-shell-protocol.c linux-dmabuf-unstable-v1-protocol.c \
  $(pkg-config --cflags --libs wayland-server) -o /usr/local/bin/vessel-compositor
test -x /usr/local/bin/vessel-compositor
echo VESSEL_COMPOSITOR_BUILT
'''
        out = self.guest(build, 180)
        if "VESSEL_COMPOSITOR_BUILT" not in out:
            raise RuntimeError("Vessel compositor did not build")

    def _launch_compositor(self, width: int, height: int) -> None:
        self.set_progress("compositor_start", 78, "Starting native Vessel compositor")
        wrapper = f'''#!/bin/bash
set -euo pipefail
rm -rf /tmp/vessel-runtime
mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
rm -f /tmp/vessel-compositor.log /tmp/vessel-frame-export.sock
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
exec /usr/local/bin/vessel-compositor \
  --socket vessel-0 \
  --frame-socket /tmp/vessel-frame-export.sock \
  --width {width} --height {height} --refresh 60
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "if [ -s /tmp/vessel-compositor.pid ]; then "
            "oldpid=$(cat /tmp/vessel-compositor.pid 2>/dev/null || true); "
            "case \"$oldpid\" in ''|*[!0-9]*) ;; *) kill \"$oldpid\" 2>/dev/null || true ;; esac; "
            "fi; "
            "rm -f /tmp/vessel-compositor-session.sh /tmp/vessel-compositor.pid; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-compositor-session.sh; "
            "chmod +x /root/vessel-compositor-session.sh; "
            "nohup /root/vessel-compositor-session.sh >/tmp/vessel-compositor.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-compositor.pid"
        )
        self.guest(launch, 20)

        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            status = self.guest(
                "test -S /tmp/vessel-runtime/vessel-0 && echo COMPOSITOR_SOCKET_READY || true; "
                "pid=$(cat /tmp/vessel-compositor.pid 2>/dev/null || true); "
                "case \"$pid\" in ''|*[!0-9]*) ;; *) kill -0 \"$pid\" 2>/dev/null && echo COMPOSITOR_ALIVE || true ;; esac; "
                "tail -40 /tmp/vessel-compositor.log 2>/dev/null || true",
                8,
            )
            if "COMPOSITOR_SOCKET_READY" in status and "COMPOSITOR_ALIVE" in status:
                self.set_progress("compositor_ready", 92, "Vessel compositor ready for GPU Wayland clients")
                return
            time.sleep(.15)
        tail = self.guest("tail -160 /tmp/vessel-compositor.log 2>/dev/null || true", 10)
        raise RuntimeError("Vessel compositor did not become ready:\n" + tail[-9000:])

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._prepare_runtime()
        self._launch_compositor(width, height)

        self.desktop_ready = True
        self.last_error = ""
        self.append("VESSEL_COMPOSITOR_READY\nNO_DRM_NO_VT_NO_SEATD\n")
        self.set_progress("desktop_ready", 100, "Vessel compositor live on Android Surface")
        return self.state()

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
            self.set_progress("queued", 1, "Starting Vessel Linux")
            self._desktop_worker = threading.Thread(target=worker, name="vessel-desktop", daemon=True)
            self._desktop_worker.start()
        return self.state()

    def desktop_action(self, name: str) -> dict[str, Any]:
        display_env = "export XDG_RUNTIME_DIR=/tmp/vessel-runtime WAYLAND_DISPLAY=vessel-0; "
        if name == "terminal":
            self.guest(display_env + "command -v foot >/dev/null && nohup foot >/tmp/vessel-terminal.log 2>&1 </dev/null & true", 8)
            return self.state()
        return super().desktop_action(name)

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest(
                    "if [ -s /tmp/vessel-compositor.pid ]; then "
                    "pid=$(cat /tmp/vessel-compositor.pid 2>/dev/null || true); "
                    "case \"$pid\" in ''|*[!0-9]*) ;; *) kill \"$pid\" 2>/dev/null || true ;; esac; fi; "
                    "rm -f /tmp/vessel-compositor.pid",
                    8,
                )
            except Exception:
                pass
        return super().stop()


runtime = VesselCompositorRuntime()


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
    import json
    import socket

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
