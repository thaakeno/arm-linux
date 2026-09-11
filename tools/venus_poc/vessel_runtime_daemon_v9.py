#!/usr/bin/env python3
"""Protocol-9 Vessel runtime entrypoint.

The UML PTY is now used only for bootstrapping the guest. Once Debian reaches a
shell, a tiny Python bootstrap connects back to the Termux host through passt
(guest gateway 10.0.2.2 -> host loopback) and receives the real command agent.
All subsequent commands and output use this dedicated TCP socket, not the tty.

This removes the remaining failure class from protocols 3-8: canonical/raw tty
line discipline, terminal control characters, partial PTY framing, prompt races,
and child processes accidentally consuming console bytes.
"""
from __future__ import annotations

import os
import shlex
import socket
import struct
import threading
import time

import vessel_runtime_daemon as core

PROTOCOL_VERSION = 9
core.PROTOCOL_VERSION = PROTOCOL_VERSION

AGENT_HOST = "127.0.0.1"
AGENT_PORT = int(os.environ.get("VESSEL_GUEST_AGENT_PORT", "47632"))
GUEST_HOST_GATEWAY = os.environ.get("VESSEL_GUEST_HOST_GATEWAY", "10.0.2.2")
READY = b"VSL9READY"
MAX_COMMAND = 16 * 1024 * 1024
MAX_RECORD = 16 * 1024 * 1024

# Guest-side program is transferred over the socket itself, so the PTY bootstrap
# remains tiny and never carries a large encoded payload.
AGENT_SOURCE = r'''import socket,struct,subprocess,sys
s=globals()["s"]

def rx(n):
    out=bytearray()
    while len(out)<n:
        part=s.recv(n-len(out))
        if not part:
            raise EOFError("host command socket closed")
        out.extend(part)
    return bytes(out)

def tx(kind,payload=b""):
    s.sendall(kind+struct.pack("!I",len(payload))+payload)

s.sendall(b"VSL9READY")
while True:
    try:
        size=struct.unpack("!I",rx(4))[0]
        if size>16*1024*1024:
            raise ValueError("command too large")
        cmd=rx(size).decode("utf-8")
        p=subprocess.Popen(
            ["/bin/bash","-lc",cmd],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
        )
        assert p.stdout is not None
        while True:
            chunk=p.stdout.read(16384)
            if not chunk:
                break
            tx(b"O",chunk)
        rc=p.wait()
        tx(b"D",struct.pack("!i",rc))
    except EOFError:
        break
    except Exception as exc:
        try:
            tx(b"E",f"{type(exc).__name__}:{exc}".encode("utf-8","replace"))
        except Exception:
            break
'''
AGENT_BYTES = AGENT_SOURCE.encode("utf-8")

core.runtime.lifecycle_lock = threading.RLock()
core.runtime.agent_socket = None

_original_start = core.Runtime.start
_original_desktop = core.Runtime.ensure_desktop
_original_stop = core.Runtime.stop
_original_prepare = core.Runtime._prepare_venus_guest


def full_write(self: core.Runtime, text: str) -> None:
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


def close_agent(self: core.Runtime) -> None:
    sock = getattr(self, "agent_socket", None)
    self.agent_socket = None
    if sock is not None:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass


def _ensure_agent(self: core.Runtime) -> None:
    sock = getattr(self, "agent_socket", None)
    if sock is not None:
        return

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((AGENT_HOST, AGENT_PORT))
    listener.listen(1)
    listener.settimeout(12.0)

    # This is the only command sent through the interactive UML tty. It is kept
    # deliberately short. It connects to the mapped host-loopback gateway,
    # downloads a length-prefixed Python agent, and execs it on the same socket.
    bootstrap_py = (
        "import socket,struct;"
        f"s=socket.create_connection(({GUEST_HOST_GATEWAY!r},{AGENT_PORT}),8);"
        "r=lambda n:(lambda b:b)(b'');"
        "n=struct.unpack('!I',s.recv(4))[0];"
        "b=bytearray();"
        "exec('while len(b)<n: b.extend(s.recv(n-len(b)))');"
        "exec(compile(bytes(b),'<vessel-agent>','exec'),{'s':s})"
    )
    command = "stty -echo 2>/dev/null || true; exec python3 -u -c " + shlex.quote(bootstrap_py) + "\n"
    if len(command.encode("utf-8")) > 1200:
        listener.close()
        raise RuntimeError("guest socket bootstrap unexpectedly too large")

    try:
        full_write(self, command)
        conn, _ = listener.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.sendall(struct.pack("!I", len(AGENT_BYTES)) + AGENT_BYTES)
        ready = recv_exact(conn, len(READY), time.monotonic() + 8.0)
        if ready != READY:
            conn.close()
            raise RuntimeError(f"bad guest agent handshake: {ready!r}")
        self.agent_socket = conn
        self.append("\n[vessel-agent] socket command channel ready\n")
    except Exception:
        close_agent(self)
        raise
    finally:
        listener.close()


def socket_guest(self: core.Runtime, command: str, timeout: float = 45.0) -> str:
    if not command.strip():
        return ""
    if not self.guest_ready:
        raise RuntimeError("Debian is not ready")

    with self.command_lock:
        _ensure_agent(self)
        sock = getattr(self, "agent_socket", None)
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
                    text = body.decode("utf-8", "replace")
                    self.append(text)
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
        except Exception:
            # A broken stream must never be reused. The next request will create
            # a fresh reverse connection rather than inheriting desynchronised bytes.
            close_agent(self)
            raise


def prepare_with_socket_agent(self: core.Runtime) -> None:
    _ensure_agent(self)
    _original_prepare(self)


def serialized_start(self: core.Runtime, timeout: float = 75.0):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock
    with lock:
        return _original_start(self, timeout)


def serialized_desktop(self: core.Runtime, width: int = 1920, height: int = 1080, dpi: int = 144):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock
    with lock:
        return _original_desktop(self, width, height, dpi)


def serialized_stop(self: core.Runtime):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock
    with lock:
        close_agent(self)
        return _original_stop(self)


core.Runtime._write = full_write
core.Runtime.guest = socket_guest
core.Runtime._prepare_venus_guest = prepare_with_socket_agent
core.Runtime.start = serialized_start
core.Runtime.ensure_desktop = serialized_desktop
core.Runtime.stop = serialized_stop

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
