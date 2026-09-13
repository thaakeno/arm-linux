#!/usr/bin/env python3
"""Protocol-25 Vessel runtime.

Hard cut-over to the native frame bridge. This runtime never starts TigerVNC or
Termux:X11 and does not monkey-patch older runtime classes. It reuses protocol
24's native Xvfb/frame transport but removes the broken release-kernel download
requirement and accepts a direct X11 input backend when UML /dev/uinput is not
available on the installed kernel.
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
        # Do not download/replace the UML kernel at runtime. The already proven
        # SMP kernel boots normally; input can use uinput when present or XTest
        # against the guest-local Xvfb display when it is not.
        v24.base.Runtime.start(self, timeout)
        self.last_error = ""
        return self.state()

    def _prepare_venus_guest(self) -> None:
        """Prepare the guest relay without process-name pkill self-matches."""
        self.set_progress("venus", 40, "Preparing Mesa Venus relay")
        source = v24.base.GUEST_RELAY_SOURCE
        if not source.exists():
            raise RuntimeError(f"missing guest relay source: {source}")
        payload = base64.b64encode(source.read_bytes()).decode()
        self.guest(f"printf '%s' {shlex.quote(payload)} | base64 -d > /root/guest_relay_direct.py", 15)
        check = self.guest(
            "test -s /opt/mesa-venus-26.2.2/lib/aarch64-linux-gnu/libvulkan_virtio.so && "
            "test -f /root/virtio-wsi-test.json && echo VENUS_READY",
            10,
        )
        if "VENUS_READY" not in check:
            raise RuntimeError("Mesa Venus 26.2.2 is not installed in this guest image")

        # Do not use `pkill -f guest_relay_direct.py` here. The current
        # `bash -lc` command itself contains that text and can kill itself with
        # SIGTERM (rc=-15). Track the actual helper PID instead.
        launch = (
            "if [ -r /tmp/vessel-guest-relay.pid ]; then "
            "old=$(cat /tmp/vessel-guest-relay.pid 2>/dev/null || true); "
            "[ -n \"$old\" ] && kill \"$old\" 2>/dev/null || true; fi; "
            "rm -f /tmp/vessel-guest-relay.pid /tmp/.venus_test /tmp/vessel-guest-relay.log; "
            "nohup python3 /root/guest_relay_direct.py --host 10.0.2.2 --port 5002 --unix /tmp/.venus_test "
            ">/tmp/vessel-guest-relay.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-guest-relay.pid; echo RELAY_LAUNCHED"
        )
        out = self.guest(launch, 10)
        if "RELAY_LAUNCHED" not in out:
            raise RuntimeError("Venus guest relay failed to launch")
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            out = self.guest("test -S /tmp/.venus_test && echo RELAY_READY || true", 3)
            if "RELAY_READY" in out:
                self.set_progress("venus", 55, "Venus relay ready")
                return
            time.sleep(0.2)
        tail = self.guest("tail -100 /tmp/vessel-guest-relay.log 2>/dev/null || true", 5)
        raise RuntimeError("Venus guest relay did not create /tmp/.venus_test: " + tail[-3000:])

    def _ensure_input_agent(self) -> None:
        source = pathlib.Path(__file__).resolve().parent / "guest_input_agent_v25.py"
        payload = base64.b64encode(source.read_bytes()).decode()
        command = (
            f"printf '%s' {shlex.quote(payload)} | base64 -d >/root/vessel_input_agent.py; "
            "chmod 700 /root/vessel_input_agent.py; "
            "if [ -r /tmp/vessel-input.pid ]; then old=$(cat /tmp/vessel-input.pid 2>/dev/null || true); "
            "[ -n \"$old\" ] && kill \"$old\" 2>/dev/null || true; fi; "
            "rm -f /tmp/vessel-input.pid; "
            f"setsid -f env DISPLAY=:1 python3 /root/vessel_input_agent.py 10.0.2.2 {v24.INPUT_GUEST_PORT} "
            ">/tmp/vessel-input.log 2>&1 </dev/null; sleep .15; "
            "pid=$(pgrep -f '^python3 /root/vessel_input_agent.py' | head -n1); "
            "[ -n \"$pid\" ] && echo $pid >/tmp/vessel-input.pid && echo INPUT_READY"
        )
        out = self.guest(command, 10)
        if "INPUT_READY" not in out:
            self.direct_input_ready = False
            raise RuntimeError("Direct input agent failed to start")
        self.direct_input_ready = True
        self.append("\n[vessel-input] native Android input bridge ready\n")

    def ensure_desktop(self, width: int = 1152, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        # Protocol 24's desktop path is already native Xvfb -> VFRM1 -> Android
        # and contains no VNC/RFB/Termux:X11. Start it first, then bring input up
        # once :1 exists so the XTest fallback can attach immediately.
        v24.NativeRuntime.ensure_desktop(self, width, height, dpi)
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
        return state


input_bridge = v24.InputBridge()
frame_bridge = v24.FrameBridge()
runtime = NativeRuntimeV25(input_bridge, frame_bridge)


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status":
            return runtime.state()
        if action == "start":
            return runtime.start(float(req.get("timeout", 80)))
        if action == "stop":
            return runtime.stop()
        if action == "desktop":
            return runtime.ensure_desktop(int(req.get("width", 1152)), int(req.get("height", 720)), int(req.get("dpi", 120)))
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


def serve() -> None:
    input_bridge.start()
    frame_bridge.start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((CONTROL_HOST, CONTROL_PORT))
    srv.listen(16)
    print(
        f"[vessel-daemon] protocol={PROTOCOL_VERSION} native-frame={v24.FRAME_CLIENT_PORT}/{v24.FRAME_GUEST_PORT} "
        f"input={v24.INPUT_CLIENT_PORT}/{v24.INPUT_GUEST_PORT} runtime={v24.base.RUNTIME}",
        flush=True,
    )
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try:
        serve()
    finally:
        runtime.stop()