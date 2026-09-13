#!/usr/bin/env python3
"""Protocol-25 Vessel runtime.

Native Android frame path with guest-local Xvfb and native input. This runtime
never starts TigerVNC or Termux:X11 and does not monkey-patch older runtimes.
"""
from __future__ import annotations

import base64
import json
import pathlib
import shlex
import socket
import threading
import time
from typing import Any

import vessel_runtime_daemon_v24 as v24

PROTOCOL_VERSION = 25
CONTROL_HOST = "127.0.0.1"
CONTROL_PORT = 47631


class NativeRuntimeV25(v24.NativeRuntime):
    def start(self, timeout: float = 75.0) -> dict[str, Any]:
        v24.base.Runtime.start(self, timeout)
        self.last_error = ""
        return self.state()

    def _prepare_venus_guest(self) -> None:
        self.set_progress("venus", 40, "Preparing Mesa Venus relay")
        source = v24.base.GUEST_RELAY_SOURCE
        if not source.exists():
            raise RuntimeError(f"missing guest relay source: {source}")
        payload = base64.b64encode(source.read_bytes()).decode()
        self.guest(f"printf '%s' {shlex.quote(payload)} | base64 -d > /root/guest_relay_direct.py", 15)
        check = self.guest(
            "test -s /opt/mesa-venus-26.2.2/lib/aarch64-linux-gnu/libvulkan_virtio.so && "
            "test -f /root/virtio-wsi-test.json && echo VENUS_READY", 10,
        )
        if "VENUS_READY" not in check:
            raise RuntimeError("Mesa Venus 26.2.2 is not installed in this guest image")
        launch = (
            "if [ -s /tmp/vessel-guest-relay.pid ]; then kill $(cat /tmp/vessel-guest-relay.pid) 2>/dev/null || true; fi; "
            "rm -f /tmp/vessel-guest-relay.pid /tmp/.venus_test /tmp/vessel-guest-relay.log; "
            "nohup python3 /root/guest_relay_direct.py --host 10.0.2.2 --port 5002 --unix /tmp/.venus_test "
            ">/tmp/vessel-guest-relay.log 2>&1 </dev/null & echo $! >/tmp/vessel-guest-relay.pid; echo RELAY_LAUNCHED"
        )
        out = self.guest(launch, 8)
        if "RELAY_LAUNCHED" not in out:
            raise RuntimeError("Venus guest relay failed to launch")
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if "RELAY_READY" in self.guest("test -S /tmp/.venus_test && echo RELAY_READY || true", 2):
                self.set_progress("venus", 55, "Venus relay ready")
                return
            time.sleep(0.08)
        tail = self.guest("tail -100 /tmp/vessel-guest-relay.log 2>/dev/null || true", 3)
        raise RuntimeError("Venus guest relay did not create /tmp/.venus_test: " + tail[-3000:])

    def _ensure_input_agent(self) -> None:
        source = pathlib.Path(__file__).resolve().parent / "guest_input_agent_v25.py"
        payload = base64.b64encode(source.read_bytes()).decode()
        inner = f"echo $$ >/tmp/vessel-input.pid; exec env DISPLAY=:1 python3 /root/vessel_input_agent.py 10.0.2.2 {v24.INPUT_GUEST_PORT}"
        command = (
            f"printf '%s' {shlex.quote(payload)} | base64 -d >/root/vessel_input_agent.py; chmod 700 /root/vessel_input_agent.py; "
            "if [ -s /tmp/vessel-input.pid ]; then kill $(cat /tmp/vessel-input.pid) 2>/dev/null || true; fi; rm -f /tmp/vessel-input.pid; "
            "setsid -f sh -c " + shlex.quote(inner) + " >/tmp/vessel-input.log 2>&1 </dev/null; "
            "for i in 1 2 3 4 5 6 7 8 9 10; do [ -s /tmp/vessel-input.pid ] && kill -0 $(cat /tmp/vessel-input.pid) 2>/dev/null && { echo INPUT_READY; break; }; sleep .03; done"
        )
        out = self.guest(command, 6)
        if "INPUT_READY" not in out:
            self.direct_input_ready = False
            tail = self.guest("tail -80 /tmp/vessel-input.log 2>/dev/null || true", 3)
            raise RuntimeError("Native input agent failed to start: " + tail[-3000:])
        self.direct_input_ready = True
        self.append("\n[vessel-input] native Android input bridge ready\n")

    def _hide_x_cursor_safe(self) -> None:
        helper = r'''import ctypes,time
x11=ctypes.CDLL('libX11.so.6'); xf=ctypes.CDLL('libXfixes.so.3')
x11.XOpenDisplay.restype=ctypes.c_void_p
D=x11.XOpenDisplay(b':1')
if D:
    x11.XDefaultRootWindow.restype=ctypes.c_ulong
    root=x11.XDefaultRootWindow(ctypes.c_void_p(D))
    xf.XFixesHideCursor(ctypes.c_void_p(D),ctypes.c_ulong(root))
    x11.XFlush(ctypes.c_void_p(D)); time.sleep(10**8)
'''
        encoded = base64.b64encode(helper.encode()).decode()
        inner = "echo $$ >/tmp/vessel-cursor.pid; exec env DISPLAY=:1 python3 /root/vessel_hide_cursor.py"
        self.guest(
            f"printf '%s' {shlex.quote(encoded)} | base64 -d >/root/vessel_hide_cursor.py; "
            "if [ -s /tmp/vessel-cursor.pid ]; then kill $(cat /tmp/vessel-cursor.pid) 2>/dev/null || true; fi; rm -f /tmp/vessel-cursor.pid; "
            "setsid -f sh -c " + shlex.quote(inner) + " >/tmp/vessel-cursor.log 2>&1 </dev/null",
            4,
        )

    def ensure_desktop(self, width: int = 1280, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        self.start()
        width = max(960, min(int(width), 1920))
        height = max(540, min(int(height), 1080))
        dpi = max(96, min(int(dpi), 180))
        self.set_progress("desktop_check", 60, "Checking native KDE Plasma display")
        self._install_desktop_packages()
        self._install_frame_streamer()

        self.set_progress("desktop_config", 76, "Preparing native Linux display")
        cleanup = r'''for f in /tmp/vessel-frame.pid /tmp/vessel-cursor.pid /tmp/vessel-input.pid /tmp/vessel-plasma.pid /tmp/vessel-Xvfb.pid; do
  if [ -s "$f" ]; then p=$(cat "$f" 2>/dev/null || true); [ -n "$p" ] && kill "$p" 2>/dev/null || true; fi
  rm -f "$f"
done
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
rm -rf /tmp/vessel-fb /tmp/vessel-runtime
mkdir -p /tmp/vessel-fb /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
'''
        self.guest(cleanup, 6)

        xinner = f"echo $$ >/tmp/vessel-Xvfb.pid; exec Xvfb :1 -screen 0 {width}x{height}x24 -fbdir /tmp/vessel-fb -nolisten tcp -ac -dpi {dpi}"
        launch_x = "setsid -f sh -c " + shlex.quote(xinner) + " >/tmp/vessel-Xvfb.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
        self.guest(launch_x, 4)
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            if "XSERVER_READY" in self.guest("DISPLAY=:1 xdpyinfo >/dev/null 2>&1 && test -f /tmp/vessel-fb/Xvfb_screen0 && echo XSERVER_READY || true", 2):
                self.append("XSERVER_READY\n")
                break
            time.sleep(0.06)
        else:
            tail = self.guest("tail -100 /tmp/vessel-Xvfb.log 2>/dev/null || true", 3)
            raise RuntimeError("native X server failed: " + tail[-4000:])

        self.set_progress("desktop_start", 88, "Starting KDE Plasma")
        plasma_inner = (
            "echo $$ >/tmp/vessel-plasma.pid; [ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; "
            "export DISPLAY=:1; export XDG_RUNTIME_DIR=/tmp/vessel-runtime; mkdir -p $XDG_RUNTIME_DIR; chmod 700 $XDG_RUNTIME_DIR; "
            "export VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json; "
            "kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true; "
            "kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true; "
            "kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true; "
            "balooctl disable >/dev/null 2>&1 || true; exec dbus-run-session -- startplasma-x11"
        )
        profile = "setsid -f sh -lc " + shlex.quote(plasma_inner) + " >/tmp/vessel-plasma.log 2>&1 </dev/null; echo PLASMA_LAUNCHED"
        self.guest(profile, 4)
        deadline = time.monotonic() + 16
        while time.monotonic() < deadline:
            if "PLASMA_READY" in self.guest("pgrep -x plasmashell >/dev/null && echo PLASMA_READY || true", 2):
                self.append("PLASMA_READY\n")
                break
            time.sleep(0.12)
        else:
            tail = self.guest("tail -120 /tmp/vessel-plasma.log 2>/dev/null || true", 3)
            raise RuntimeError("KDE Plasma failed to start: " + tail[-6000:])

        self._hide_x_cursor_safe()
        self.set_progress("display_start", 94, "Starting low-latency Android display bridge")
        frame_inner = f"echo $$ >/tmp/vessel-frame.pid; exec python3 /root/vessel_frame_streamer.py /tmp/vessel-fb/Xvfb_screen0 10.0.2.2 {v24.FRAME_GUEST_PORT} 60"
        streamer = (
            "if [ -s /tmp/vessel-frame.pid ]; then kill $(cat /tmp/vessel-frame.pid) 2>/dev/null || true; fi; rm -f /tmp/vessel-frame.pid; "
            "setsid -f sh -c " + shlex.quote(frame_inner) + " >/tmp/vessel-frame.log 2>&1 </dev/null; echo FRAME_STREAMER_LAUNCHED"
        )
        self.guest(streamer, 4)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.frame_bridge.guest_connected and self.frame_bridge.frames > 0:
                break
            time.sleep(0.04)
        else:
            tail = self.guest("tail -100 /tmp/vessel-frame.log 2>/dev/null || true", 3)
            raise RuntimeError("native frame bridge did not produce a frame: " + tail[-4000:])

        self._ensure_input_agent()
        self.native_display_ready = True
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live on native Android surface")
        return self.state()

    def state(self) -> dict[str, Any]:
        state = super().state()
        state["protocolVersion"] = PROTOCOL_VERSION
        state["vncPort"] = -1
        state["displayTransport"] = "native-frame-v1"
        state["inputMode"] = "native"
        state["targetFrameRate"] = 60
        return state


input_bridge = v24.InputBridge()
frame_bridge = v24.FrameBridge()
runtime = NativeRuntimeV25(input_bridge, frame_bridge)


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 80)))
        if action == "stop": return runtime.stop()
        if action == "desktop": return runtime.ensure_desktop(int(req.get("width", 1280)), int(req.get("height", 720)), int(req.get("dpi", 120)))
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
        try: conn.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode())
        except OSError: pass


def serve() -> None:
    input_bridge.start(); frame_bridge.start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((CONTROL_HOST, CONTROL_PORT)); srv.listen(16)
    print(f"[vessel-daemon] protocol={PROTOCOL_VERSION} native-frame={v24.FRAME_CLIENT_PORT}/{v24.FRAME_GUEST_PORT} input={v24.INPUT_CLIENT_PORT}/{v24.INPUT_GUEST_PORT} runtime={v24.base.RUNTIME}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try: serve()
    finally: runtime.stop()
