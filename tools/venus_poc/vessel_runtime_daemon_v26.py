#!/usr/bin/env python3
"""Protocol-26 Vessel runtime.

Keeps protocol 25's native Android frame path, but removes the fragile PTY
command execution path. Guest commands now use a dedicated reconnectable TCP
agent after the shell boots. No runtime monkey patching is used.
"""
from __future__ import annotations

import json
import os
import shlex
import socket
import struct
import threading
import time
from typing import Any

import vessel_runtime_daemon_v25 as v25

PROTOCOL_VERSION = 26
AGENT_HOST = "127.0.0.1"
AGENT_PORT = 47632
GUEST_HOST_GATEWAY = "10.0.2.2"
READY = b"VSL26READY"
MAX_COMMAND = 16 * 1024 * 1024
MAX_RECORD = 16 * 1024 * 1024

AGENT_SOURCE = r'''import socket,struct,subprocess
s=globals()["s"]
def rx(n):
    out=bytearray()
    while len(out)<n:
        part=s.recv(n-len(out))
        if not part: raise EOFError("host command socket closed")
        out.extend(part)
    return bytes(out)
def tx(kind,payload=b""):
    s.sendall(kind+struct.pack("!I",len(payload))+payload)
s.sendall(b"VSL26READY")
while True:
    try:
        size=struct.unpack("!I",rx(4))[0]
        if size>16*1024*1024: raise ValueError("command too large")
        cmd=rx(size).decode("utf-8")
        p=subprocess.Popen(["/bin/bash","-lc",cmd],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=0)
        assert p.stdout is not None
        while True:
            chunk=p.stdout.read(16384)
            if not chunk: break
            tx(b"O",chunk)
        rc=p.wait(); tx(b"D",struct.pack("!i",rc))
    except EOFError:
        break
    except Exception as exc:
        try: tx(b"E",f"{type(exc).__name__}:{exc}".encode("utf-8","replace"))
        except Exception: break
'''
AGENT_BYTES = AGENT_SOURCE.encode("utf-8")


def recv_exact(sock: socket.socket, n: int, deadline: float) -> bytes:
    out = bytearray()
    while len(out) < n:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"guest command socket timed out waiting for {n} bytes")
        sock.settimeout(min(1.0, remaining))
        try:
            part = sock.recv(n - len(out))
        except socket.timeout:
            continue
        if not part:
            raise ConnectionError("guest command socket closed")
        out.extend(part)
    return bytes(out)


class NativeRuntimeV26(v25.NativeRuntimeV25):
    def __init__(self, input_bridge: v25.v24.InputBridge, frame_bridge: v25.v24.FrameBridge) -> None:
        super().__init__(input_bridge, frame_bridge)
        self.agent_socket: socket.socket | None = None

    def state(self) -> dict[str, Any]:
        state = super().state()
        state["protocolVersion"] = PROTOCOL_VERSION
        state["commandTransport"] = "socket-v26"
        return state

    def _write(self, text: str) -> None:
        if self.master is None:
            raise RuntimeError("UML console is not open")
        data = text.encode("utf-8")
        view = memoryview(data)
        sent = 0
        while sent < len(view):
            n = os.write(self.master, view[sent:])
            if n <= 0:
                raise OSError("short write to UML console")
            sent += n

    def _close_agent(self) -> None:
        sock = self.agent_socket
        self.agent_socket = None
        if sock is None:
            return
        try: sock.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        try: sock.close()
        except OSError: pass

    def _ensure_agent(self) -> None:
        if self.agent_socket is not None:
            return
        if self.proc is None or self.proc.poll() is not None:
            raise RuntimeError("Debian UML is not running")
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind((AGENT_HOST, AGENT_PORT))
        except OSError as exc:
            listener.close()
            raise RuntimeError(f"cannot bind guest command channel {AGENT_HOST}:{AGENT_PORT}: {exc}") from exc
        listener.listen(1)
        listener.settimeout(15.0)
        bootstrap_py = (
            "import socket,struct;"
            f"s=socket.create_connection(({GUEST_HOST_GATEWAY!r},{AGENT_PORT}),10);"
            "h=s.recv(4,socket.MSG_WAITALL);"
            "n=struct.unpack('!I',h)[0];"
            "src=s.recv(n,socket.MSG_WAITALL);"
            "exec(compile(src,'<vessel-agent>','exec'),{'s':s})"
        )
        command = (
            "stty -echo 2>/dev/null || true; "
            "nohup python3 -u -c " + shlex.quote(bootstrap_py) +
            " </dev/null >/tmp/vessel-agent-bootstrap.log 2>&1 &\n"
        )
        try:
            self._write(command)
            conn, _ = listener.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.sendall(struct.pack("!I", len(AGENT_BYTES)) + AGENT_BYTES)
            ready = recv_exact(conn, len(READY), time.monotonic() + 10.0)
            if ready != READY:
                conn.close()
                raise RuntimeError(f"bad guest command-agent handshake: {ready!r}")
            conn.settimeout(None)
            self.agent_socket = conn
            self.append("\n[vessel-agent] protocol-26 socket command channel ready\n")
        except Exception:
            self._close_agent()
            raise
        finally:
            listener.close()

    def guest(self, command: str, timeout: float = 45.0) -> str:
        if not command.strip():
            return ""
        if self.proc is None or self.proc.poll() is not None:
            raise RuntimeError("Debian UML is not running")
        with self.command_lock:
            if not self.guest_ready:
                raise RuntimeError("Debian is not ready")
            self._ensure_agent()
            sock = self.agent_socket
            if sock is None:
                raise RuntimeError("guest command socket is unavailable")
            payload = command.encode("utf-8")
            if len(payload) > MAX_COMMAND:
                raise RuntimeError("guest command is too large")
            deadline = time.monotonic() + timeout
            output = bytearray()
            try:
                sock.sendall(struct.pack("!I", len(payload)) + payload)
                while True:
                    header = recv_exact(sock, 5, deadline)
                    kind = header[:1]
                    size = struct.unpack("!I", header[1:])[0]
                    if size > MAX_RECORD:
                        raise RuntimeError(f"guest agent record too large: {size}")
                    body = recv_exact(sock, size, deadline) if size else b""
                    if kind == b"O":
                        self.append(body.decode("utf-8", "replace"))
                        output.extend(body)
                        if len(output) > 2_000_000:
                            del output[:-1_000_000]
                        continue
                    if kind == b"D":
                        if len(body) != 4:
                            raise RuntimeError("malformed guest completion record")
                        rc = struct.unpack("!i", body)[0]
                        text = output.decode("utf-8", "replace")
                        if rc != 0:
                            raise RuntimeError(f"guest command failed rc={rc}: {text[-6000:]}")
                        return text
                    if kind == b"E":
                        raise RuntimeError("guest command agent error: " + body.decode("utf-8", "replace"))
                    raise RuntimeError(f"unknown guest agent record type: {kind!r}")
            except Exception as exc:
                self._close_agent()
                raise RuntimeError(f"guest command socket failed; channel will reconnect: {exc}") from exc

    def stop(self) -> dict[str, Any]:
        self._close_agent()
        return super().stop()


input_bridge = v25.v24.InputBridge()
frame_bridge = v25.v24.FrameBridge()
runtime = NativeRuntimeV26(input_bridge, frame_bridge)


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 80)))
        if action == "stop": return runtime.stop()
        if action == "desktop": return runtime.ensure_desktop(int(req.get("width", 1152)), int(req.get("height", 720)), int(req.get("dpi", 120)))
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
    print(f"[vessel-daemon] protocol={PROTOCOL_VERSION} command=socket-v26 native-frame={v25.v24.FRAME_CLIENT_PORT}/{v25.v24.FRAME_GUEST_PORT} input={v25.v24.INPUT_CLIENT_PORT}/{v25.v24.INPUT_GUEST_PORT}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try: serve()
    finally: runtime.stop()
