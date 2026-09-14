#!/usr/bin/env python3
"""Vessel protocol 33: Linux apps -> labwc/wlroots -> dma-buf -> Android Surface.

The Android app, UML guest, persistent Debian rootfs, Venus GPU relay, networking,
input bridge, controls and Vessel UI are unchanged. KWin and the vesseloutput
plugin are not part of this runtime. labwc is the only Linux compositor.

Because Vessel intentionally keeps Linux inside UML, AHardwareBuffer objects
cannot be allocated directly inside the guest process. The wlroots compositor
therefore renders GPU buffers through Zink/Venus; the existing zero-CPU-copy
fd relay imports the compositor dma-buf on Android and presents it to Vessel's
Surface. This preserves the proven wlroots compositor architecture without
replacing Vessel with another frontend or distro manager.
"""
from __future__ import annotations

import base64
import os
import pathlib
import shlex
import time
from typing import Any

import vessel_runtime_daemon_v31 as v31

PROTOCOL_VERSION = 33
DISPLAY_TRANSPORT = "wlroots-labwc-dmabuf-venus-android-surface-v1"
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))


class WlrootsRuntime(v31.WaylandRuntime):
    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "labwc/wlroots -> dma-buf -> Android native Surface",
            "renderer": "wlroots GLES2 -> Zink -> Venus -> host Adreno GPU",
            "compositor": "labwc (wlroots)",
            "framePolicy": "Wayland commit/frame-callback driven",
            "softwareFallback": False,
            "vncPort": -1,
        })
        return state

    def _prepare_bridge(self) -> None:
        source = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_wayland_bridge.c"
        if not source.exists():
            raise RuntimeError(f"native Wayland bridge source missing: {source}")
        self.set_progress("wayland_bridge", 61, "Preparing Vessel native Wayland output")
        self._rpc_upload(source, "/root/vessel_wayland_bridge.c")

        have = self.guest(
            "command -v labwc >/dev/null && command -v wayland-scanner >/dev/null && "
            "pkg-config --exists wayland-server && "
            "test -f /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml && "
            "test -f /usr/share/wayland-protocols/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && echo WLROOTS_DEPS_READY || true",
            20,
        )
        if "WLROOTS_DEPS_READY" not in have:
            self.set_progress("wayland_deps", 64, "Installing wlroots compositor runtime")
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends "
                "labwc waybar swaybg foot xwayland dbus-x11 "
                "libgl1-mesa-dri mesa-utils vulkan-tools "
                "build-essential pkg-config libwayland-dev wayland-protocols",
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
test -x /usr/local/bin/vessel-wayland-bridge
echo VESSEL_WAYLAND_PARENT_READY
'''
        out = self.guest(build, 180)
        if "VESSEL_WAYLAND_PARENT_READY" not in out:
            raise RuntimeError("Vessel native Wayland parent did not build")

    def _configure_labwc(self) -> None:
        config = r'''mkdir -p /root/.config/labwc /root/.config/waybar
cat > /root/.config/labwc/environment <<'EOF'
MESA_LOADER_DRIVER_OVERRIDE=zink
GALLIUM_DRIVER=zink
LIBGL_ALWAYS_SOFTWARE=0
WLR_RENDERER=gles2
WLR_BACKENDS=wayland,libinput
WLR_DRM_NO_ATOMIC=1
EOF
cat > /root/.config/labwc/autostart <<'EOF'
(swaybg -c '#101010' >/tmp/vessel-swaybg.log 2>&1 &) 
(waybar >/tmp/vessel-waybar.log 2>&1 &)
(foot --server >/tmp/vessel-foot.log 2>&1 &)
EOF
chmod +x /root/.config/labwc/autostart
cat > /root/.config/waybar/config <<'EOF'
{
  "layer": "top",
  "position": "top",
  "height": 30,
  "modules-left": ["custom/vessel"],
  "modules-right": ["cpu", "memory", "clock"],
  "custom/vessel": {"format": "Vessel Debian"},
  "clock": {"format": "{:%H:%M}"}
}
EOF
cat > /root/.config/waybar/style.css <<'EOF'
* { font-family: sans-serif; font-size: 13px; }
window#waybar { background: rgba(20,20,20,0.96); color: #f2f2f2; }
#custom-vessel, #cpu, #memory, #clock { padding: 0 12px; }
EOF
'''
        self.guest(config, 30)

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._prepare_bridge()
        self._configure_labwc()

        self.set_progress("wayland_start", 80, "Starting wlroots compositor")
        env = self._gpu_env()
        wrapper = f'''#!/bin/bash
set -euo pipefail
rm -rf /tmp/vessel-runtime
mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true
{env}
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=labwc
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export LIBGL_ALWAYS_SOFTWARE=0
export WLR_RENDERER=gles2
export WLR_BACKENDS=wayland,libinput
rm -f /tmp/vessel-native-wayland.log /tmp/vessel-labwc.log /tmp/vessel-waybar.log

/usr/local/bin/vessel-wayland-bridge --socket vessel-host-0 --frame-socket /tmp/vessel-frame-export.sock --width {width} --height {height} --refresh 60 >/tmp/vessel-native-wayland.log 2>&1 &
BRIDGE_PID=$!
echo "$BRIDGE_PID" >/tmp/vessel-native-wayland.pid
for i in $(seq 1 120); do
  [ -S /tmp/vessel-runtime/vessel-host-0 ] && break
  kill -0 "$BRIDGE_PID" 2>/dev/null || {{ cat /tmp/vessel-native-wayland.log; exit 51; }}
  sleep .05
done
[ -S /tmp/vessel-runtime/vessel-host-0 ] || exit 52

# labwc is the actual desktop compositor. wlroots' Wayland backend creates one
# nested output on Vessel's parent and renders it with GLES2/Zink/Venus.
export WAYLAND_DISPLAY=vessel-host-0
labwc >/tmp/vessel-labwc.log 2>&1 &
LABWC_PID=$!
echo "$LABWC_PID" >/tmp/vessel-labwc.pid

# The nested compositor chooses its own server socket (normally wayland-0).
for i in $(seq 1 240); do
  CHILD=$(find /tmp/vessel-runtime -maxdepth 1 -type s -name 'wayland-*' ! -name 'vessel-host-0' -printf '%f\n' 2>/dev/null | head -1)
  [ -n "$CHILD" ] && break
  kill -0 "$LABWC_PID" 2>/dev/null || {{ cat /tmp/vessel-labwc.log; exit 53; }}
  sleep .1
done
[ -n "${{CHILD:-}}" ] || exit 54
echo "$CHILD" >/tmp/vessel-child-wayland
wait "$LABWC_PID"
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "pkill -x labwc 2>/dev/null || true; pkill -x waybar 2>/dev/null || true; "
            "pkill -x swaybg 2>/dev/null || true; pkill -f '[v]essel-wayland-bridge' 2>/dev/null || true; "
            "rm -f /tmp/vessel-wayland-session.sh /tmp/vessel-native-wayland.pid /tmp/vessel-labwc.pid /tmp/vessel-child-wayland; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-wayland-session.sh; "
            "chmod +x /root/vessel-wayland-session.sh; "
            "export XDG_RUNTIME_DIR=/tmp/vessel-runtime; "
            "nohup dbus-run-session -- /root/vessel-wayland-session.sh >/tmp/vessel-wayland-session.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-wayland-session.pid"
        )
        self.guest(launch, 30)

        self.set_progress("wayland_present", 91, "Waiting for wlroots GPU output on Android Surface")
        deadline = time.monotonic() + 60
        relay_log = v31.base.RUNTIME / "vessel-relay.log"
        while time.monotonic() < deadline:
            status = self.guest(
                "test -S /tmp/vessel-runtime/vessel-host-0 && echo PARENT_READY || true; "
                "test -s /tmp/vessel-child-wayland && echo CHILD_READY || true; "
                "pgrep -x labwc >/dev/null && echo LABWC_ALIVE || true; "
                "tail -80 /tmp/vessel-labwc.log 2>/dev/null || true; "
                "tail -50 /tmp/vessel-native-wayland.log 2>/dev/null || true",
                10,
            )
            low = status.lower()
            if "llvmpipe" in low or "softpipe" in low or "pixman" in low:
                raise RuntimeError("wlroots fell back to software rendering:\n" + status[-7000:])
            relay = ""
            try:
                relay = relay_log.read_text(errors="replace")[-20000:]
            except Exception:
                pass
            if (
                "PARENT_READY" in status
                and "CHILD_READY" in status
                and "LABWC_ALIVE" in status
                and "Android imported compositor object" in relay
            ):
                self.desktop_ready = True
                self.last_error = ""
                self.append("WLROOTS_LABWC_READY\nVENUS_DMABUF_TO_ANDROID_READY\n")
                self.set_progress("desktop_ready", 100, "Desktop live: wlroots -> GPU dma-buf -> Android Surface")
                return self.state()
            time.sleep(.25)

        guest_tail = self.guest(
            "echo '=== parent ==='; tail -240 /tmp/vessel-native-wayland.log 2>/dev/null || true; "
            "echo '=== labwc ==='; tail -300 /tmp/vessel-labwc.log 2>/dev/null || true; "
            "echo '=== session ==='; tail -160 /tmp/vessel-wayland-session.log 2>/dev/null || true; "
            "echo '=== waybar ==='; tail -120 /tmp/vessel-waybar.log 2>/dev/null || true; "
            "echo '=== sockets ==='; ls -l /tmp/.venus_test /tmp/vessel-frame-export.sock /tmp/vessel-runtime 2>&1 || true",
            15,
        )
        host_tail = ""
        try:
            host_tail = relay_log.read_text(errors="replace")[-16000:]
        except Exception:
            pass
        raise RuntimeError(
            "wlroots native presentation did not become ready:\n"
            + guest_tail[-22000:]
            + "\n=== host relay ===\n"
            + host_tail
        )

    def desktop_action(self, name: str) -> dict[str, Any]:
        if name == "terminal":
            child = self.guest("cat /tmp/vessel-child-wayland 2>/dev/null || true", 5).strip().splitlines()
            display = child[-1] if child else "wayland-0"
            self.guest(
                f"export XDG_RUNTIME_DIR=/tmp/vessel-runtime WAYLAND_DISPLAY={shlex.quote(display)}; "
                "nohup foot >/tmp/vessel-terminal.log 2>&1 </dev/null &",
                8,
            )
            return self.state()
        return super().desktop_action(name)

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest(
                    "pkill -x labwc 2>/dev/null || true; pkill -x waybar 2>/dev/null || true; "
                    "pkill -x swaybg 2>/dev/null || true; pkill -x foot 2>/dev/null || true; "
                    "pkill -f '[v]essel-wayland-bridge' 2>/dev/null || true",
                    10,
                )
            except Exception:
                pass
        return super().stop()


runtime = WlrootsRuntime()


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
                int(req.get("width", 1600)),
                int(req.get("height", 720)),
                int(req.get("dpi", 120)),
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
        serve_connection(conn)
