#!/usr/bin/env python3
"""Protocol-28 Vessel runtime.

Protocol 28 keeps the reconnectable protocol-27 command channel, runs the
VFRM2 damage source only as a compatibility producer, always starts the native
input endpoint, and presents through Android's ANativeWindow Surface path.
No VNC, RFB, Termux:X11 or runtime monkey-patching is used.
"""
from __future__ import annotations

import json
import socket
import threading
from typing import Any

import vessel_runtime_daemon_v27 as v27

PROTOCOL_VERSION = 28


class NativeRuntimeV28(v27.NativeRuntimeV27):
    def state(self) -> dict[str, Any]:
        state = super().state()
        state["protocolVersion"] = PROTOCOL_VERSION
        state["displayTransport"] = "android-surface-v1"
        state["presenter"] = "ANativeWindow"
        state["inputMode"] = "native-uinput-or-xtest"
        state["vncPort"] = -1
        state["targetFrameRate"] = 120
        return state

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        # Build/start the stable VFRM2 damage source, but Android no longer
        # decodes it through Kotlin/GL. The APK's native ANativeWindow presenter
        # owns the Surface and keeps a persistent shadow frame across lifecycle
        # recreation.
        v27.v25.v24.NativeRuntime.ensure_desktop(self, width, height, dpi)
        self.set_progress("input_start", 97, "Starting native Linux input")
        self._ensure_input_agent()
        self.native_display_ready = True
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live on Android Surface")
        return self.state()


input_bridge = v27.v25.v24.InputBridge()
frame_bridge = v27.v25.v24.FrameBridge()
runtime = NativeRuntimeV28(input_bridge, frame_bridge)


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 80)))
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
    srv.bind(("127.0.0.1", 47631)); srv.listen(16)
    print("[vessel-daemon] protocol=28 display=ANativeWindow input=native command=socket-v27", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try: serve()
    finally: runtime.stop()
