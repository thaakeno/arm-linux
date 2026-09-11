#!/usr/bin/env python3
"""Protocol-9 Vessel runtime entrypoint.

The UML PTY is used only to boot Debian and launch one tiny bootstrap command.
The bootstrap opens an outbound TCP connection through passt to the Termux host
(guest gateway 10.0.2.2 maps to host loopback), downloads the real command
agent, and keeps that socket for all later commands.

All command framing and output therefore bypass the terminal line discipline.
This removes the failure class seen in protocols 3-8: canonical line limits,
raw/cooked tty transitions, control-character interpretation, prompt races,
partial PTY frames, and child processes consuming command bytes.
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

# This source is transferred over TCP, never through the tty.
AGENT_SOURCE = r'''import socket,struct,subprocess
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
    if sock is None:
        return
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    try:
        sock.close()
    except OSError:
        pass


def _ensure_agent(self: core.Runtime) -> None:
    if getattr(self, "agent_socket", None) is not None:
        return

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        listener.bind((AGENT_HOST, AGENT_PORT))
    except OSError as exc:
        listener.close()
        raise RuntimeError(f"cannot bind guest command channel {AGENT_HOST}:{AGENT_PORT}: {exc}") from exc
    listener.listen(1)
    listener.settimeout(15.0)

    # The one tty command is <1 KiB. socket.MSG_WAITALL makes the bootstrap's
    # length/source reads exact; the real protocol starts only after TCP setup.
    bootstrap_py = (
        "import socket,struct;"
        f"s=socket.create_connection(({GUEST_HOST_GATEWAY!r},{AGENT_PORT}),10);"
        "h=s.recv(4,socket.MSG_WAITALL);"
        "n=struct.unpack('!I',h)[0];"
        "src=s.recv(n,socket.MSG_WAITALL);"
        "exec(compile(src,'<vessel-agent>','exec'),{'s':s})"
    )
    command = "stty -echo 2>/dev/null || true; exec python3 -u -c " + shlex.quote(bootstrap_py) + "\n"
    if len(command.encode("utf-8")) >= 1024:
        listener.close()
        raise RuntimeError("guest socket bootstrap unexpectedly exceeds safe tty size")

    try:
        full_write(self, command)
        conn, _ = listener.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.sendall(struct.pack("!I", len(AGENT_BYTES)) + AGENT_BYTES)
        ready = recv_exact(conn, len(READY), time.monotonic() + 10.0)
        if ready != READY:
            conn.close()
            raise RuntimeError(f"bad guest command-agent handshake: {ready!r}")
        conn.settimeout(None)
        self.agent_socket = conn
        self.append("\n[vessel-agent] dedicated socket command channel ready\n")
    except Exception as exc:
        close_agent(self)
        with self.lock:
            tail = self.console_text[-2500:]
        raise RuntimeError(f"guest socket command channel failed: {exc}. Console tail:\n{tail}") from exc
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
        except Exception as exc:
            close_agent(self)
            # Once the exec'd socket agent exits there is deliberately no shell
            # left to fall back to. Mark this boot unusable instead of silently
            # attempting another PTY protocol and creating another hang.
            self.guest_ready = False
            raise RuntimeError(f"guest command socket failed; restart Debian runtime: {exc}") from exc


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
        # Flush the ext4 guest before the base implementation terminates UML.
        if getattr(self, "agent_socket", None) is not None and self.guest_ready:
            try:
                socket_guest(self, "sync", 8.0)
            except Exception:
                pass
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
