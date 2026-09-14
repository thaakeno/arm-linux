#!/usr/bin/env python3
'''Vessel protocol 36: robust Weston compositor startup with GPU probe + persistent damage fallback.

Protocol 36 keeps protocol 35's full Weston/libweston desktop stack, persistent
SHM damage relay, direct Android seat bridge and Vulkan/FIFO presenter. It fixes
two startup bugs found on-device: the Weston log is now created with vessel
ownership, and GPU readiness only accepts real frame records instead of matching
the transport capability text ``shm-damage=1``. A failed/crashed Venus renderer
therefore falls through to the persistent Pixman damage path automatically.
'''
from __future__ import annotations

import base64
import json
import shlex
import socket
import threading
import time
from typing import Any

import vessel_runtime_daemon_v35 as v35

PROTOCOL_VERSION = 36
DISPLAY_TRANSPORT = "weston-nested-vessel-transport-venus-android-surface-v2"
RUNTIME_REVISION = "v36-owned-weston-log-exact-frame-probe"
PARENT_DISPLAY = v35.PARENT_DISPLAY
DESKTOP_DISPLAY = v35.DESKTOP_DISPLAY
TRANSPORT_BIN = v35.TRANSPORT_BIN
INPUT_HELPER = v35.INPUT_HELPER


class WestonVesselRuntime(v35.WestonVesselRuntime):
    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "runtimeRevision": RUNTIME_REVISION,
        })
        return state

    def _launch_desktop_stack(self, width: int, height: int) -> None:
        self.set_progress("transport_start", 75, "Starting Vessel Android display transport")
        weston_log = "/tmp/vessel-runtime/weston.log"
        wrapper = f'''#!/bin/bash
set -euo pipefail
WESTON_PGID=""
TRANSPORT_PID=""
INPUT_PID=""
WESTON_LOG={shlex.quote(weston_log)}

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
rm -f /tmp/vessel-transport.log /tmp/vessel-input-direct.log /tmp/vessel-renderer-mode
rm -f /tmp/vessel-input.sock /tmp/vessel-weston-gpu-failed.log
install -o vessel -g vessel -m 0600 /dev/null "$WESTON_LOG"

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

prepare_weston_log() {{
  : > "$WESTON_LOG"
  chown vessel:vessel "$WESTON_LOG"
  chmod 0600 "$WESTON_LOG"
}}

launch_gpu() {{
  prepare_weston_log
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
      --log="$WESTON_LOG" &
  WESTON_PGID=$!
  echo "$WESTON_PGID" >/tmp/vessel-weston.pgid
}}

launch_pixman() {{
  prepare_weston_log
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
      --log="$WESTON_LOG" &
  WESTON_PGID=$!
  echo "$WESTON_PGID" >/tmp/vessel-weston.pgid
}}

real_frame_seen() {{
  grep -Eq '^\[vessel-host\] (dmabuf-import|shm-damage) ' /tmp/vessel-transport.log 2>/dev/null
}}

# Probe the preferred GPU path briefly. The READY capability line includes the
# text "shm-damage=1" and MUST NOT count as a rendered frame.
start_transport /tmp/vessel-frame-export.sock
launch_gpu
GPU_OK=0
for i in $(seq 1 100); do
  if ! kill -0 "$WESTON_PGID" 2>/dev/null; then
    break
  fi
  if real_frame_seen; then
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

cp "$WESTON_LOG" /tmp/vessel-weston-gpu-failed.log 2>/dev/null || true
echo "[vessel-session] GPU compositor probe produced no real frame; switching to persistent damage renderer" >&2
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
        deadline = time.monotonic() + 32
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
                f"echo '=== weston ==='; tail -120 {shlex.quote(weston_log)} 2>/dev/null || true; "
                "echo '=== gpu-failed ==='; tail -80 /tmp/vessel-weston-gpu-failed.log 2>/dev/null || true; "
                "echo '=== transport ==='; tail -100 /tmp/vessel-transport.log 2>/dev/null || true; "
                "echo '=== persistent-frame ==='; tail -60 /tmp/vessel-shm-frame.log 2>/dev/null || true",
                8,
            )
            frame_ready = (
                "[vessel-host] dmabuf-import " in last
                or "[vessel-host] shm-damage " in last
            )
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
            "echo '=== session ==='; tail -180 /tmp/vessel-desktop-session.log 2>/dev/null || true; "
            f"echo '=== weston ==='; tail -280 {shlex.quote(weston_log)} 2>/dev/null || true; "
            "echo '=== gpu-failed ==='; tail -180 /tmp/vessel-weston-gpu-failed.log 2>/dev/null || true; "
            "echo '=== transport ==='; tail -240 /tmp/vessel-transport.log 2>/dev/null || true; "
            "echo '=== persistent guest frame ==='; tail -180 /tmp/vessel-shm-frame.log 2>/dev/null || true; "
            "echo '=== guest relay ==='; tail -180 /tmp/vessel-guest-wayland.log 2>/dev/null || true",
            12,
        )
        raise RuntimeError("Weston desktop did not produce a display frame:\n" + tail[-20000:])


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
