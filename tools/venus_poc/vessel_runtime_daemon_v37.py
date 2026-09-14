#!/usr/bin/env python3
"""Vessel protocol 37: Weston 16 native Vulkan -> Venus -> Adreno -> Android.

There is deliberately no GL, Zink, Pixman or SHM display fallback in this
runtime. Weston 16's Vulkan renderer is the compositor renderer. The nested
Wayland parent exports only real compositor dma-bufs through Vessel's Venus/
umshm transport to the Android Vulkan presenter.
"""
from __future__ import annotations

import base64
import hashlib
import os
import pathlib
import shlex
import socket
import threading
import time
from typing import Any

import vessel_runtime_daemon_v34 as v34

PROTOCOL_VERSION = 37
DISPLAY_TRANSPORT = "weston16-vulkan-venus-android-surface-v3"
RUNTIME_REVISION = "v37-weston16-native-vulkan-gpu-only"
WESTON_VERSION = "16.0.0"
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))
WESTON_PREFIX = "/opt/vessel-weston16"
WESTON_BIN = f"{WESTON_PREFIX}/bin/weston"
TRANSPORT_BIN = "/usr/local/bin/vessel-transport-host"
TRANSPORT_MARKER = "/usr/local/lib/vessel-transport-host-v37.sha256"
INPUT_HELPER = "/usr/local/libexec/vessel-input-direct-v37.py"
INPUT_MARKER = "/usr/local/lib/vessel-input-direct-v37.sha256"
PARENT_DISPLAY = "vessel-host-0"
DESKTOP_DISPLAY = "vessel-desktop-0"


class Weston16VulkanRuntime(v34.VesselCompositorRuntime):
    def __init__(self) -> None:
        super().__init__()
        self.renderer_mode = "native-vulkan"

    @staticmethod
    def _sha256(path: pathlib.Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "runtimeRevision": RUNTIME_REVISION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "dma-buf -> Android Vulkan SurfaceFlinger",
            "renderer": "Weston 16 native Vulkan -> Venus -> Adreno -> dma-buf -> Android Vulkan",
            "rendererMode": self.renderer_mode,
            "compositor": "Weston 16.0.0 desktop-shell",
            "desktopName": "Weston 16 desktop-shell",
            "westonVersion": WESTON_VERSION,
            "framePolicy": "libweston damage/repaint scheduling + FIFO Android presentation",
            "seatPolicy": "Android direct input -> parent wl_seat -> Weston",
            "windowPolicy": "Weston desktop-shell + XWayland",
            "clipboardPolicy": "Weston Wayland selection/DnD",
            "syncPolicy": "Wayland buffer release + Vulkan synchronization",
            "translationLayer": "none",
            "softwareFallback": False,
            "gpuOnly": True,
            "vncPort": -1,
            "desktopWorkerAlive": bool(self._desktop_worker and self._desktop_worker.is_alive()),
        })
        return state

    def _prepare_desktop_runtime(self) -> None:
        transport = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_transport_host.c"
        input_helper = POC / "tools/venus_poc/guest_input_direct_v34.py"
        weston_builder = POC / "tools/venus_poc/build_weston16_vulkan.sh"
        if not transport.exists() or not input_helper.exists() or not weston_builder.exists():
            raise RuntimeError("protocol 37 native Vulkan runtime sources are missing")

        self.set_progress("desktop_deps", 58, "Checking Weston 16 native Vulkan runtime")
        self._rpc_upload(weston_builder, "/root/build_weston16_vulkan.sh")
        out = self.guest(
            f"test -x {WESTON_BIN} && test -f {WESTON_PREFIX}/.vessel-native-vulkan-{WESTON_VERSION} "
            "&& echo WESTON16_VULKAN_READY || true",
            15,
        )
        if "WESTON16_VULKAN_READY" not in out:
            self.set_progress("desktop_deps", 60, "First run: building Weston 16 native Vulkan")
            build_out = self.guest(
                "chmod +x /root/build_weston16_vulkan.sh; "
                "VESSEL_WESTON_PREFIX=/opt/vessel-weston16 VESSEL_BUILD_JOBS=3 /root/build_weston16_vulkan.sh",
                1800,
            )
            if "VESSEL_WESTON16_VULKAN_BUILT" not in build_out and "VESSEL_WESTON16_VULKAN_CACHE_HIT" not in build_out:
                raise RuntimeError("Weston 16 native Vulkan build did not complete:\n" + build_out[-12000:])
            self.append("WESTON16_VULKAN_BUILD_READY\n")
        else:
            self.append("WESTON16_VULKAN_CACHE_HIT\n")

        # Hard invariant: this installation may not contain Weston's GL renderer.
        gl_check = self.guest(
            f"if find {WESTON_PREFIX} -name gl-renderer.so -type f -print -quit | grep -q .; then echo FORBIDDEN_GL; fi; "
            f"find {WESTON_PREFIX} -name vulkan-renderer.so -type f -print -quit",
            12,
        )
        if "FORBIDDEN_GL" in gl_check or "vulkan-renderer.so" not in gl_check:
            raise RuntimeError("Weston 16 renderer invariant failed: Vulkan-only build required")

        transport_hash = self._sha256(transport)
        input_hash = self._sha256(input_helper)
        cached = self.guest(
            f"test -x {TRANSPORT_BIN} && test -f {TRANSPORT_MARKER} && "
            f"test \"$(cat {TRANSPORT_MARKER} 2>/dev/null)\" = {shlex.quote(transport_hash)} && "
            f"test -f {INPUT_HELPER} && test -f {INPUT_MARKER} && "
            f"test \"$(cat {INPUT_MARKER} 2>/dev/null)\" = {shlex.quote(input_hash)} && "
            "echo VESSEL_TRANSPORT_CACHE_HIT || true",
            12,
        )
        if "VESSEL_TRANSPORT_CACHE_HIT" not in cached:
            self.set_progress("transport_build", 66, "Updating native dma-buf display transport")
            self._rpc_upload(transport, "/root/vessel_transport_host.c")
            self._rpc_upload(input_helper, "/root/vessel_input_direct_v37.py")
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
cc -O2 -pipe -fno-plt -ffunction-sections -fdata-sections -std=gnu11 -Wall -Wextra \
  -Werror=implicit-function-declaration -I/root/vessel-transport-build \
  /root/vessel_transport_host.c xdg-shell-protocol.c linux-dmabuf-unstable-v1-protocol.c presentation-time-protocol.c \
  $(pkg-config --cflags --libs wayland-server xkbcommon) -Wl,--gc-sections -o {TRANSPORT_BIN}
strip --strip-unneeded {TRANSPORT_BIN} 2>/dev/null || true
install -m 0755 /root/vessel_input_direct_v37.py {INPUT_HELPER}
printf '%s\n' {shlex.quote(transport_hash)} > {TRANSPORT_MARKER}
printf '%s\n' {shlex.quote(input_hash)} > {INPUT_MARKER}
python3 -m py_compile {INPUT_HELPER}
echo VESSEL_TRANSPORT_BUILT
'''
            built = self.guest(build, 240)
            if "VESSEL_TRANSPORT_BUILT" not in built:
                raise RuntimeError("Vessel native dma-buf transport did not build")
            self.append("VESSEL_TRANSPORT_CACHE_UPDATED\n")
        else:
            self.append("VESSEL_TRANSPORT_CACHE_HIT\n")

        self.set_progress("desktop_config", 71, "Configuring Weston 16 desktop-shell")
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
        self.guest(
            "if ! id -u vessel >/dev/null 2>&1; then useradd --create-home --shell /bin/bash vessel; fi; "
            "mkdir -p /home/vessel/.config; "
            f"printf '%s' {shlex.quote(ini64)} | base64 -d > /home/vessel/.config/weston.ini; "
            "chown -R vessel:vessel /home/vessel/.config",
            20,
        )

    def _launch_desktop_stack(self, width: int, height: int) -> None:
        self.set_progress("transport_start", 75, "Starting GPU-only Android dma-buf transport")
        wrapper = f'''#!/bin/bash
set -euo pipefail
WESTON_PGID=""
TRANSPORT_PID=""
INPUT_PID=""
RUNTIME=/tmp/vessel-runtime
WESTON_LOG="$RUNTIME/weston16-vulkan.log"
VULKAN_LOG="$RUNTIME/vulkan-probe.log"

cleanup() {{
  set +e
  [ -n "$WESTON_PGID" ] && kill -TERM -- "-$WESTON_PGID" 2>/dev/null || true
  [ -n "$INPUT_PID" ] && kill -TERM "$INPUT_PID" 2>/dev/null || true
  [ -n "$TRANSPORT_PID" ] && kill -TERM "$TRANSPORT_PID" 2>/dev/null || true
  [ -n "$WESTON_PGID" ] && wait "$WESTON_PGID" 2>/dev/null || true
  [ -n "$INPUT_PID" ] && wait "$INPUT_PID" 2>/dev/null || true
  [ -n "$TRANSPORT_PID" ] && wait "$TRANSPORT_PID" 2>/dev/null || true
}}
trap cleanup EXIT INT TERM

rm -rf "$RUNTIME"
mkdir -p "$RUNTIME"
chown vessel:vessel "$RUNTIME"
chmod 700 "$RUNTIME"
install -d -m 1777 /tmp/.X11-unix /tmp/.ICE-unix
rm -f /tmp/vessel-input.sock /tmp/vessel-transport.log /tmp/vessel-input-direct.log
install -o vessel -g vessel -m 0600 /dev/null "$WESTON_LOG"
install -o vessel -g vessel -m 0600 /dev/null "$VULKAN_LOG"

cp /root/virtio-wsi-test.json /tmp/vessel-virtio-wsi.json
chown vessel:vessel /tmp/vessel-virtio-wsi.json
chmod 0644 /tmp/vessel-virtio-wsi.json
chown vessel:vessel /tmp/.venus_test 2>/dev/null || true
chmod 0660 /tmp/.venus_test 2>/dev/null || true

export XDG_RUNTIME_DIR="$RUNTIME"
: >/tmp/vessel-transport.log
{TRANSPORT_BIN} \
  --socket {PARENT_DISPLAY} \
  --frame-socket /tmp/vessel-frame-export.sock \
  --input-socket /tmp/vessel-input.sock \
  --width {width} --height {height} --refresh 60 \
  >>/tmp/vessel-transport.log 2>&1 &
TRANSPORT_PID=$!
echo "$TRANSPORT_PID" >/tmp/vessel-transport.pid
for i in $(seq 1 120); do
  [ -S "$RUNTIME/{PARENT_DISPLAY}" ] && [ -S /tmp/vessel-input.sock ] && break
  kill -0 "$TRANSPORT_PID" 2>/dev/null || {{ cat /tmp/vessel-transport.log; exit 71; }}
  sleep .05
done
[ -S "$RUNTIME/{PARENT_DISPLAY}" ] || exit 72
[ -S /tmp/vessel-input.sock ] || exit 73
chown vessel:vessel "$RUNTIME/{PARENT_DISPLAY}" "$RUNTIME/{PARENT_DISPLAY}.lock" 2>/dev/null || true
chmod 0660 "$RUNTIME/{PARENT_DISPLAY}" "$RUNTIME/{PARENT_DISPLAY}.lock" 2>/dev/null || true

VESSEL_WAYLAND_INPUT=/tmp/vessel-input.sock \
VESSEL_INPUT_HOST=10.0.2.2 VESSEL_INPUT_PORT=47633 \
python3 {INPUT_HELPER} >/tmp/vessel-input-direct.log 2>&1 &
INPUT_PID=$!
echo "$INPUT_PID" >/tmp/vessel-input-direct.pid

# Verify the Venus Vulkan ICD itself before giving it to Weston. There is no
# EGL/GLES/Zink environment here by design.
runuser -u vessel -- env \
  HOME=/home/vessel USER=vessel LOGNAME=vessel \
  VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest \
  VK_DRIVER_FILES=/tmp/vessel-virtio-wsi.json \
  vulkaninfo --summary >"$VULKAN_LOG" 2>&1

echo '[vessel-session] launching Weston 16 native Vulkan renderer' >&2
setsid runuser -u vessel -- env \
  HOME=/home/vessel USER=vessel LOGNAME=vessel \
  XDG_RUNTIME_DIR="$RUNTIME" WAYLAND_DISPLAY={PARENT_DISPLAY} \
  XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston \
  VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest \
  VK_DRIVER_FILES=/tmp/vessel-virtio-wsi.json \
  MOZ_ENABLE_WAYLAND=1 QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland,x11 \
  dbus-run-session -- {WESTON_BIN} \
    --backend=wayland --renderer=vulkan \
    --socket={DESKTOP_DISPLAY} --display={PARENT_DISPLAY} \
    --width={width} --height={height} --scale=1 --fullscreen \
    --xwayland --idle-time=0 \
    --config=/home/vessel/.config/weston.ini \
    --log="$WESTON_LOG" &
WESTON_PGID=$!
echo "$WESTON_PGID" >/tmp/vessel-weston.pgid
wait "$WESTON_PGID"
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "if [ -s /tmp/vessel-desktop-session.pid ]; then "
            "p=$(cat /tmp/vessel-desktop-session.pid 2>/dev/null || true); "
            "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; fi; "
            "rm -f /tmp/vessel-desktop-session.pid /tmp/vessel-desktop-session.log /tmp/vessel-desktop-session.sh; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-desktop-session.sh; "
            "chmod +x /root/vessel-desktop-session.sh; "
            "nohup /root/vessel-desktop-session.sh >/tmp/vessel-desktop-session.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-desktop-session.pid"
        )
        self.guest(launch, 15)

        self.set_progress("weston_start", 83, "Starting Weston 16 native Vulkan compositor")
        deadline = time.monotonic() + 35
        last = ""
        while time.monotonic() < deadline:
            last = self.guest(
                f"test -S /tmp/vessel-runtime/{PARENT_DISPLAY} && echo PARENT_READY || true; "
                f"test -S /tmp/vessel-runtime/{DESKTOP_DISPLAY} && echo WESTON_SOCKET_READY || true; "
                "p=$(cat /tmp/vessel-transport.pid 2>/dev/null || true); "
                "case \"$p\" in ''|*[!0-9]*) ;; *) kill -0 \"$p\" 2>/dev/null && echo TRANSPORT_ALIVE || true ;; esac; "
                "p=$(cat /tmp/vessel-weston.pgid 2>/dev/null || true); "
                "case \"$p\" in ''|*[!0-9]*) ;; *) kill -0 \"$p\" 2>/dev/null && echo WESTON_ALIVE || true ;; esac; "
                "echo '=== weston16-vulkan ==='; tail -100 /tmp/vessel-runtime/weston16-vulkan.log 2>/dev/null || true; "
                "echo '=== transport ==='; tail -80 /tmp/vessel-transport.log 2>/dev/null || true",
                8,
            )
            if "shm-damage" in last:
                raise RuntimeError("GPU-only invariant violated: compositor output used wl_shm instead of dma-buf")
            ready = all(token in last for token in (
                "PARENT_READY", "WESTON_SOCKET_READY", "TRANSPORT_ALIVE", "WESTON_ALIVE", "[vessel-host] dmabuf-import "
            ))
            if ready:
                self.renderer_mode = "native-vulkan"
                self.set_progress("desktop_surface", 96, "Native Vulkan dma-buf reached Android transport")
                self.append(
                    "WESTON16_NATIVE_VULKAN_READY\n"
                    "VESSEL_DMABUF_GPU_PATH_READY\n"
                    "NO_GL_NO_ZINK_NO_PIXMAN\n"
                    "DIRECT_WAYLAND_INPUT_READY\n"
                    "NO_DRM_NO_VT_NO_SEATD\n"
                )
                return
            if "WESTON_ALIVE" not in last and "WESTON_SOCKET_READY" not in last and "=== weston16-vulkan ===" in last:
                session = self.guest("tail -160 /tmp/vessel-desktop-session.log 2>/dev/null || true", 8)
                if "launching Weston 16" in session:
                    break
            time.sleep(.15)

        tail = self.guest(
            "echo '=== session ==='; tail -220 /tmp/vessel-desktop-session.log 2>/dev/null || true; "
            "echo '=== Weston 16 native Vulkan ==='; tail -320 /tmp/vessel-runtime/weston16-vulkan.log 2>/dev/null || true; "
            "echo '=== Vulkan ICD probe ==='; tail -220 /tmp/vessel-runtime/vulkan-probe.log 2>/dev/null || true; "
            "echo '=== Vessel parent transport ==='; tail -260 /tmp/vessel-transport.log 2>/dev/null || true; "
            "echo '=== guest Venus relay ==='; tail -220 /tmp/vessel-guest-wayland.log 2>/dev/null || true; "
            "echo '=== direct input ==='; tail -160 /tmp/vessel-input-direct.log 2>/dev/null || true; "
            "echo '=== kernel graphics faults ==='; dmesg 2>/dev/null | grep -Ei 'weston|vulkan|venus|virtio|segfault|fault' | tail -120 || true",
            12,
        )
        self.append("\n[Vessel protocol 37 GPU failure]\n" + tail[-24000:] + "\n")
        raise RuntimeError("Weston 16 native Vulkan did not produce a dma-buf frame:\n" + tail[-20000:])

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._prepare_desktop_runtime()
        self._launch_desktop_stack(width, height)
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "Weston 16 native Vulkan desktop live")
        self.append("WESTON_DESKTOP_READY renderer=native-vulkan version=16.0.0\n")
        return self.state()

    def desktop_action(self, name: str) -> dict[str, Any]:
        env = f"export XDG_RUNTIME_DIR=/tmp/vessel-runtime WAYLAND_DISPLAY={DESKTOP_DISPLAY}; "
        if name == "terminal":
            self.guest(env + "runuser -u vessel -- env XDG_RUNTIME_DIR=/tmp/vessel-runtime WAYLAND_DISPLAY=" + DESKTOP_DISPLAY + " nohup foot >/tmp/vessel-foot.log 2>&1 </dev/null & true", 8)
            return self.state()
        return super().desktop_action(name)

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest(
                    "if [ -s /tmp/vessel-desktop-session.pid ]; then "
                    "p=$(cat /tmp/vessel-desktop-session.pid 2>/dev/null || true); "
                    "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; fi; "
                    "rm -f /tmp/vessel-desktop-session.pid",
                    8,
                )
            except Exception:
                pass
        return super().stop()


runtime = Weston16VulkanRuntime()


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
                req = __import__("json").loads(data.split(b"\n", 1)[0].decode() or "{}")
                reply = handle(req)
            except Exception as exc:
                reply = {"ok": False, "error": f"protocol: {type(exc).__name__}: {exc}"}
            try:
                conn.sendall((__import__("json").dumps(reply, separators=(",", ":")) + "\n").encode())
            except OSError:
                pass

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 47631))
    srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True).start()
