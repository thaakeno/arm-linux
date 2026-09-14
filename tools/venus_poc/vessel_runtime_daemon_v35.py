#!/usr/bin/env python3
'''Vessel protocol 35: Weston/libweston desktop -> Vessel transport -> Android.

Weston/libweston desktop-shell is the real compositor and window manager.
Vessel owns only the nested parent display, Android input bridge, and frame
transport. The runtime probes the Zink/Venus compositor path first. If that
driver path cannot produce a frame, it immediately restarts Weston with its
Pixman renderer and a persistent damage-only relay independent of Venus client
lifetime. Both paths remain event driven and Android presentation is
Vulkan/FIFO paced.
'''
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
PERSISTENT_FRAME_HELPER = "/usr/local/libexec/vessel-frame-damage-relay.py"
PERSISTENT_FRAME_MARKER = "/usr/local/lib/vessel-frame-damage-relay.sha256"
PARENT_DISPLAY = "vessel-host-0"
DESKTOP_DISPLAY = "vessel-desktop-0"


class WestonVesselRuntime(v34.VesselCompositorRuntime):
    def __init__(self) -> None:
        super().__init__()
        self.renderer_mode = "pending"

    def state(self) -> dict[str, Any]:
        state = super().state()
        if self.renderer_mode == "gpu":
            renderer = "Weston GL -> Zink/Venus/Adreno -> dma-buf -> Android Vulkan"
        elif self.renderer_mode == "pixman":
            renderer = "Weston Pixman damage compositor -> persistent SHM damage -> Android Vulkan"
        else:
            renderer = "Weston GPU probe with automatic persistent damage fallback"
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "Weston -> Vessel damage/dma-buf transport -> Android Vulkan Surface",
            "renderer": renderer,
            "rendererMode": self.renderer_mode,
            "compositor": "Weston/libweston desktop-shell (nested Wayland backend)",
            "framePolicy": "libweston damage/repaint scheduling + FIFO/vsync Android presentation",
            "seatPolicy": "Android -> parent wl_seat -> Weston",
            "windowPolicy": "Weston desktop-shell window management + XWayland",
            "clipboardPolicy": "Weston Wayland selection/DnD between Linux applications",
            "imePolicy": "Android InputConnection -> XKB key stream -> Weston focused client",
            "syncPolicy": "Wayland buffer release + Vulkan fences/semaphores",
            "softwareFallback": True,
            "shmTransportFallback": "persistent, damage-only, event-driven",
            "vncPort": -1,
            "desktopWorkerAlive": bool(self._desktop_worker and self._desktop_worker.is_alive()),
        })
        return state

    @staticmethod
    def _sha256(path: pathlib.Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def _prepare_venus_guest(self) -> None:
        super()._prepare_venus_guest()
        helper = POC / "tools/venus_poc/guest_frame_damage_relay.py"
        if not helper.exists():
            raise RuntimeError("persistent guest frame relay is missing")
        self._rpc_upload(helper, "/root/guest_frame_damage_relay.py")
        self.guest(
            "pkill -f '^python3 /root/guest_frame_damage_relay.py( |$)' 2>/dev/null || true; "
            "rm -f /tmp/vessel-shm-frame.sock /tmp/vessel-shm-frame.log; "
            "nohup python3 /root/guest_frame_damage_relay.py "
            "--host 10.0.2.2 --port 5003 --unix /tmp/vessel-shm-frame.sock "
            ">/tmp/vessel-shm-frame.log 2>&1 </dev/null &",
            20,
        )
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            out = self.guest(
                "test -S /tmp/vessel-shm-frame.sock && echo PERSISTENT_FRAME_READY || true",
                8,
            )
            if "PERSISTENT_FRAME_READY" in out:
                self.append("PERSISTENT_SHM_RELAY_READY\n")
                return
            time.sleep(.15)
        tail = self.guest("tail -120 /tmp/vessel-shm-frame.log 2>/dev/null || true", 8)
        raise RuntimeError("persistent SHM frame relay did not start:\n" + tail[-6000:])

    def _prepare_desktop_runtime(self) -> None:
        transport = POC / "tools/venus_poc/vessel_wayland_bridge/vessel_transport_host.c"
        input_helper = POC / "tools/venus_poc/guest_input_direct_v34.py"
        frame_helper = POC / "tools/venus_poc/guest_frame_damage_relay.py"
        if not transport.exists() or not input_helper.exists() or not frame_helper.exists():
            raise RuntimeError("Vessel Weston transport sources are missing")

        transport_hash = self._sha256(transport)
        input_hash = self._sha256(input_helper)
        frame_hash = self._sha256(frame_helper)

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
            f"test -f {PERSISTENT_FRAME_HELPER} && test -f {PERSISTENT_FRAME_MARKER} && "
            f"test \"$(cat {PERSISTENT_FRAME_MARKER} 2>/dev/null)\" = {shlex.quote(frame_hash)} && "
            "echo VESSEL_TRANSPORT_CACHE_HIT || true",
            12,
        )

        if "VESSEL_TRANSPORT_CACHE_HIT" not in cached:
            self.set_progress("transport_build", 65, "Updating cached native display transport")
            self._rpc_upload(transport, "/root/vessel_transport_host.c")
            self._rpc_upload(input_helper, "/root/vessel_input_direct.py")
            self._rpc_upload(frame_helper, "/root/vessel_frame_damage_relay.py")
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
install -m 0755 /root/vessel_input_direct.py {INPUT_HELPER}
install -m 0755 /root/vessel_frame_damage_relay.py {PERSISTENT_FRAME_HELPER}
printf '%s\n' {shlex.quote(transport_hash)} > {TRANSPORT_MARKER}
printf '%s\n' {shlex.quote(input_hash)} > {INPUT_MARKER}
printf '%s\n' {shlex.quote(frame_hash)} > {PERSISTENT_FRAME_MARKER}
test -x {TRANSPORT_BIN}
python3 -m py_compile {INPUT_HELPER} {PERSISTENT_FRAME_HELPER}
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
        self.guest(
            "if ! id -u vessel >/dev/null 2>&1; then useradd --create-home --shell /bin/bash vessel; fi; "
            "mkdir -p /home/vessel/.config; "
            f"printf '%s' {shlex.quote(ini64)} | base64 -d > /home/vessel/.config/weston.ini; "
            "chown -R vessel:vessel /home/vessel/.config",
            20,
        )

    def _launch_desktop_stack(self, width: int, height: int) -> None:
        self.set_progress("transport_start", 75, "Starting Vessel Android display transport")
        wrapper = f'''#!/bin/bash
set -euo pipefail
WESTON_PGID=""
TRANSPORT_PID=""
INPUT_PID=""

stop_children() {{
  set +e
  [ -n "$WESTON_PGID" ] && kill -TERM -- "-$WESTON_PGID" 2>/dev/null || true
  [ -n "$INPUT_PID" ] && kill -TERM "$INPUT_PID" 2>/dev/null || true
  [ -n "$TRANSPORT_PID" ] && kill -TERM "$TRANSPORT_PID" 2>/dev/null || true
  [ -n "$WESTON_PGID" ] && wait "$WESTON_PGID" 2>/dev/null || true
  [ -n "$INPUT_PID" ] && wait "$INPUT_PID" 2>/dev/null || true
  [ -n "$TRANSPORT_PID" ] && wait "$TRANSPORT_PID" 2>/dev/null || true
  WESTON_PGID=""
  INPUT_PID=""
  TRANSPORT_PID=""
}}

cleanup() {{
  stop_children
}}
trap cleanup EXIT INT TERM

rm -rf /tmp/vessel-runtime
mkdir -p /tmp/vessel-runtime
chown vessel:vessel /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
install -d -m 1777 /tmp/.X11-unix /tmp/.ICE-unix
rm -f /tmp/vessel-transport.log /tmp/vessel-weston.log /tmp/vessel-input-direct.log /tmp/vessel-renderer-mode
rm -f /tmp/vessel-input.sock

cp /root/virtio-wsi-test.json /tmp/vessel-virtio-wsi.json
chown vessel:vessel /tmp/vessel-virtio-wsi.json
chmod 0644 /tmp/vessel-virtio-wsi.json
chown vessel:vessel /tmp/.venus_test 2>/dev/null || true
chmod 0660 /tmp/.venus_test 2>/dev/null || true

export XDG_RUNTIME_DIR=/tmp/vessel-runtime

start_transport() {{
  local frame_socket="$1"
  rm -f /tmp/vessel-runtime/{PARENT_DISPLAY} /tmp/vessel-runtime/{PARENT_DISPLAY}.lock /tmp/vessel-input.sock
  : > /tmp/vessel-transport.log
  {TRANSPORT_BIN} \
    --socket {PARENT_DISPLAY} \
    --frame-socket "$frame_socket" \
    --input-socket /tmp/vessel-input.sock \
    --width {width} --height {height} --refresh 60 \
    >>/tmp/vessel-transport.log 2>&1 &
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

  VESSEL_WAYLAND_INPUT=/tmp/vessel-input.sock \
  VESSEL_INPUT_HOST=10.0.2.2 VESSEL_INPUT_PORT=47633 \
  python3 {INPUT_HELPER} >/tmp/vessel-input-direct-stdout.log 2>&1 &
  INPUT_PID=$!
  echo "$INPUT_PID" >/tmp/vessel-input-direct.pid
}}

launch_gpu() {{
  : > /tmp/vessel-weston.log
  setsid runuser -u vessel -- env \
    HOME=/home/vessel USER=vessel LOGNAME=vessel \
    XDG_RUNTIME_DIR=/tmp/vessel-runtime \
    WAYLAND_DISPLAY={PARENT_DISPLAY} \
    XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston \
    VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest \
    VK_DRIVER_FILES=/tmp/vessel-virtio-wsi.json \
    MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=0 \
    EGL_PLATFORM=wayland MOZ_ENABLE_WAYLAND=1 QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland,x11 \
    dbus-run-session -- weston \
      --backend=wayland-backend.so \
      --socket={DESKTOP_DISPLAY} \
      --display={PARENT_DISPLAY} \
      --width={width} --height={height} --scale=1 --fullscreen \
      --xwayland --idle-time=0 \
      --config=/home/vessel/.config/weston.ini \
      --log=/tmp/vessel-weston.log &
  WESTON_PGID=$!
  echo "$WESTON_PGID" >/tmp/vessel-weston.pgid
}}

launch_pixman() {{
  : > /tmp/vessel-weston.log
  setsid runuser -u vessel -- env \
    HOME=/home/vessel USER=vessel LOGNAME=vessel \
    XDG_RUNTIME_DIR=/tmp/vessel-runtime \
    WAYLAND_DISPLAY={PARENT_DISPLAY} \
    XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston \
    MOZ_ENABLE_WAYLAND=1 QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland,x11 \
    dbus-run-session -- weston \
      --backend=wayland-backend.so --use-pixman \
      --socket={DESKTOP_DISPLAY} \
      --display={PARENT_DISPLAY} \
      --width={width} --height={height} --scale=1 --fullscreen \
      --xwayland --idle-time=0 \
      --config=/home/vessel/.config/weston.ini \
      --log=/tmp/vessel-weston.log &
  WESTON_PGID=$!
  echo "$WESTON_PGID" >/tmp/vessel-weston.pgid
}}

# Fast GPU probe. A broken Venus compositor path must not become a long
# black-screen timeout.
start_transport /tmp/vessel-frame-export.sock
launch_gpu
GPU_OK=0
for i in $(seq 1 80); do
  if ! kill -0 "$WESTON_PGID" 2>/dev/null; then
    break
  fi
  if grep -Eq 'dmabuf-import|shm-damage' /tmp/vessel-transport.log 2>/dev/null; then
    GPU_OK=1
    break
  fi
  sleep .05
done

if [ "$GPU_OK" = 1 ]; then
  printf '%s\n' gpu >/tmp/vessel-renderer-mode
  echo VESSEL_RENDERER_GPU
  wait "$WESTON_PGID"
  exit $?
fi

echo "[vessel-session] GPU compositor probe failed; switching to persistent damage renderer" >&2
stop_children
rm -f /tmp/vessel-runtime/{DESKTOP_DISPLAY} /tmp/vessel-runtime/{DESKTOP_DISPLAY}.lock
start_transport /tmp/vessel-shm-frame.sock
launch_pixman
printf '%s\n' pixman >/tmp/vessel-renderer-mode
echo VESSEL_RENDERER_PIXMAN
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
        deadline = time.monotonic() + 22
        last = ""
        while time.monotonic() < deadline:
            last = self.guest(
                f"test -S /tmp/vessel-runtime/{PARENT_DISPLAY} && echo PARENT_READY || true; "
                f"test -S /tmp/vessel-runtime/{DESKTOP_DISPLAY} && echo WESTON_SOCKET_READY || true; "
                "p=$(cat /tmp/vessel-transport.pid 2>/dev/null || true); "
                "case \"$p\" in ''|*[!0-9]*) ;; *) kill -0 \"$p\" 2>/dev/null && echo TRANSPORT_ALIVE || true ;; esac; "
                "p=$(cat /tmp/vessel-weston.pgid 2>/dev/null || true); "
                "case \"$p\" in ''|*[!0-9]*) ;; *) kill -0 \"$p\" 2>/dev/null && echo WESTON_ALIVE || true ;; esac; "
                "printf 'MODE='; cat /tmp/vessel-renderer-mode 2>/dev/null || echo pending; "
                "echo '=== weston ==='; tail -100 /tmp/vessel-weston.log 2>/dev/null || true; "
                "echo '=== transport ==='; tail -80 /tmp/vessel-transport.log 2>/dev/null || true; "
                "echo '=== persistent-frame ==='; tail -40 /tmp/vessel-shm-frame.log 2>/dev/null || true",
                8,
            )
            frame_ready = "dmabuf-import" in last or "shm-damage" in last
            if (
                "PARENT_READY" in last
                and "WESTON_SOCKET_READY" in last
                and "TRANSPORT_ALIVE" in last
                and "WESTON_ALIVE" in last
                and frame_ready
            ):
                self.renderer_mode = "pixman" if "MODE=pixman" in last else "gpu"
                detail = (
                    "Weston live with persistent damage transport"
                    if self.renderer_mode == "pixman"
                    else "Weston live on Zink/Venus GPU transport"
                )
                self.set_progress("desktop_surface", 96, detail)
                return
            time.sleep(.15)

        tail = self.guest(
            "echo '=== session ==='; tail -160 /tmp/vessel-desktop-session.log 2>/dev/null || true; "
            "echo '=== weston ==='; tail -260 /tmp/vessel-weston.log 2>/dev/null || true; "
            "echo '=== transport ==='; tail -220 /tmp/vessel-transport.log 2>/dev/null || true; "
            "echo '=== persistent guest frame ==='; tail -160 /tmp/vessel-shm-frame.log 2>/dev/null || true; "
            "echo '=== guest relay ==='; tail -160 /tmp/vessel-guest-wayland.log 2>/dev/null || true",
            12,
        )
        raise RuntimeError("Weston desktop did not produce a display frame:\n" + tail[-18000:])

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
            "PERSISTENT_DAMAGE_RELAY_READY\n"
            "DIRECT_WAYLAND_INPUT_READY\n"
            "DAMAGE_DRIVEN_PRESENTATION_READY\n"
            "NO_DRM_NO_VT_NO_SEATD\n"
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
        if not command:
            return super().desktop_action(name)

        common = (
            f"HOME=/home/vessel USER=vessel LOGNAME=vessel XDG_RUNTIME_DIR=/tmp/vessel-runtime "
            f"WAYLAND_DISPLAY={DESKTOP_DISPLAY} XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston "
            "MOZ_ENABLE_WAYLAND=1 QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland,x11"
        )
        if self.renderer_mode == "gpu":
            common += (
                " VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest "
                "VK_DRIVER_FILES=/tmp/vessel-virtio-wsi.json "
                "MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=0"
            )
        qcmd = shlex.quote(command)
        log = shlex.quote(f"/tmp/vessel-app-{name}.log")
        self.guest(
            f"runuser -u vessel -- env {common} sh -lc {qcmd} >{log} 2>&1 </dev/null & true",
            8,
        )
        return self.state()

    def stop(self) -> dict[str, Any]:
        self.renderer_mode = "pending"
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
                    "p=$(cat \"$f\" 2>/dev/null || true); "
                    "case \"$p\" in ''|*[!0-9]*) ;; *) kill \"$p\" 2>/dev/null || true ;; esac; done; "
                    "pkill -f '^python3 /root/guest_frame_damage_relay.py( |$)' 2>/dev/null || true; "
                    "rm -f /tmp/vessel-desktop-session.pid /tmp/vessel-weston.pgid "
                    "/tmp/vessel-input-direct.pid /tmp/vessel-transport.pid /tmp/vessel-shm-frame.sock",
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
