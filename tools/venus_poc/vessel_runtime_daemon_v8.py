#!/usr/bin/env python3
"""Protocol-8 Vessel runtime entrypoint.

Uses a persistent guest command agent over the UML PTY in raw mode. Commands are
length-prefixed binary frames, so correctness no longer depends on canonical TTY
line buffering, shell prompts, newline delivery, or base64 chunk reconstruction.
The command child also gets stdin=/dev/null so it can never consume protocol
bytes intended for the agent.
"""
from __future__ import annotations

import base64
import os
import re
import struct
import threading
import time

import vessel_runtime_daemon as core

PROTOCOL_VERSION = 8
core.PROTOCOL_VERSION = PROTOCOL_VERSION

AGENT_READY = "__VESSEL_AGENT_READY_V8__"
MAGIC = b"VSL8"
HEADER = struct.Struct("!4sI16s")

AGENT_SOURCE = r'''#!/usr/bin/env python3
import struct, subprocess, sys
MAGIC=b"VSL8"
HEADER=struct.Struct("!4sI16s")

def read_exact(n):
    out=bytearray()
    src=sys.stdin.buffer
    while len(out)<n:
        part=src.read(n-len(out))
        if not part:
            raise EOFError("guest transport closed")
        out.extend(part)
    return bytes(out)

print("__VESSEL_AGENT_READY_V8__", flush=True)
while True:
    try:
        raw=read_exact(HEADER.size)
        magic,size,nonce_b=HEADER.unpack(raw)
        if magic != MAGIC:
            raise ValueError("bad frame magic")
        if size > 16*1024*1024:
            raise ValueError("command frame too large")
        nonce=nonce_b.decode("ascii")
        cmd=read_exact(size).decode("utf-8")
        print(f"__VESSEL_BEGIN_{nonce}__", flush=True)
        p=subprocess.Popen(
            ["/bin/bash","-lc",cmd],
            stdin=subprocess.DEVNULL,
            stdout=sys.stdout,
            stderr=subprocess.STDOUT,
        )
        rc=p.wait()
        print(f"__VESSEL_DONE_{nonce}__:{rc}", flush=True)
    except EOFError:
        raise
    except Exception as exc:
        print(f"__VESSEL_AGENT_ERROR__:{type(exc).__name__}:{exc}", flush=True)
'''
AGENT_B64 = base64.b64encode(AGENT_SOURCE.encode()).decode()

core.runtime.lifecycle_lock = threading.RLock()
core.runtime.agent_ready = False

_original_start = core.Runtime.start
_original_desktop = core.Runtime.ensure_desktop
_original_stop = core.Runtime.stop
_original_prepare = core.Runtime._prepare_venus_guest


def write_bytes(self: core.Runtime, data: bytes) -> None:
    if self.master is None:
        raise RuntimeError("UML console is not open")
    view = memoryview(data)
    sent = 0
    while sent < len(view):
        n = os.write(self.master, view[sent:])
        if n <= 0:
            raise OSError("short write to UML console")
        sent += n


def full_write(self: core.Runtime, text: str) -> None:
    write_bytes(self, text.encode())


def _ensure_agent(self: core.Runtime) -> None:
    if getattr(self, "agent_ready", False):
        return
    # Bootstrap is the only line-oriented command. It is intentionally short.
    # `stty raw` removes canonical line buffering before the framed protocol starts.
    handoff = (
        "stty raw -echo 2>/dev/null || stty -icanon -echo min 1 time 0 2>/dev/null; "
        f"printf '%s' '{AGENT_B64}' | base64 -d > /root/vessel_guest_agent_v8.py; "
        "chmod 700 /root/vessel_guest_agent_v8.py; "
        "exec python3 -u /root/vessel_guest_agent_v8.py\n"
    )
    if len(handoff.encode()) >= 3500:
        raise RuntimeError("guest-agent bootstrap unexpectedly exceeds safe tty line size")
    with self.lock:
        before = len(self.console_text)
    full_write(self, handoff)
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        with self.lock:
            text = self.console_text
        start = before if before <= len(text) else 0
        if AGENT_READY in text[start:]:
            self.agent_ready = True
            return
        if self.proc is None or self.proc.poll() is not None:
            raise RuntimeError("UML exited while starting Vessel guest agent v8")
        time.sleep(0.02)
    with self.lock:
        tail = self.console_text[-3000:]
    raise TimeoutError("Vessel guest command agent v8 did not start. Console tail:\n" + tail)


def framed_guest(self: core.Runtime, command: str, timeout: float = 45.0) -> str:
    if not command.strip():
        return ""
    if not self.guest_ready:
        raise RuntimeError("Debian is not ready")

    with self.command_lock:
        _ensure_agent(self)
        nonce = f"{time.time_ns() & 0xffffffffffffffff:016x}"
        payload = command.encode("utf-8")
        frame = HEADER.pack(MAGIC, len(payload), nonce.encode("ascii")) + payload
        begin_marker = f"__VESSEL_BEGIN_{nonce}__"
        done_re = re.compile(re.escape(f"__VESSEL_DONE_{nonce}__") + r":([0-9]+)")
        agent_error = "__VESSEL_AGENT_ERROR__:"
        with self.lock:
            start_len = len(self.console_text)

        write_bytes(self, frame)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.lock:
                text = self.console_text
            region_start = start_len if start_len <= len(text) else 0
            region = text[region_start:]
            match = done_re.search(region)
            if match is not None:
                rc = int(match.group(1))
                before_done = region[:match.start()]
                begin = before_done.find(begin_marker)
                output = before_done[begin + len(begin_marker):] if begin >= 0 else before_done
                output = output.lstrip("\r\n")
                if rc != 0:
                    raise RuntimeError(f"guest command failed rc={rc}: {output[-5000:]}")
                return output
            err_pos = region.rfind(agent_error)
            if err_pos >= 0:
                err_line = region[err_pos:].splitlines()[0]
                raise RuntimeError(f"guest command transport failed: {err_line}")
            if self.proc is None or self.proc.poll() is not None:
                raise RuntimeError("UML exited while running guest command")
            time.sleep(0.02)

        with self.lock:
            tail = self.console_text[-4000:]
        raise TimeoutError(
            f"Guest command transport timed out after {timeout:.1f}s; "
            f"framed agent did not finish nonce {nonce}. Console tail:\n{tail}"
        )


def prepare_with_agent(self: core.Runtime) -> None:
    _ensure_agent(self)
    _original_prepare(self)


def serialized_start(self: core.Runtime, timeout: float = 75.0):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock(); lock = self.lifecycle_lock
    with lock:
        return _original_start(self, timeout)


def serialized_desktop(self: core.Runtime, width: int = 1920, height: int = 1080, dpi: int = 144):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock(); lock = self.lifecycle_lock
    with lock:
        return _original_desktop(self, width, height, dpi)


def serialized_stop(self: core.Runtime):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock(); lock = self.lifecycle_lock
    with lock:
        try:
            return _original_stop(self)
        finally:
            self.agent_ready = False


core.Runtime._write = full_write
core.Runtime.guest = framed_guest
core.Runtime._prepare_venus_guest = prepare_with_agent
core.Runtime.start = serialized_start
core.Runtime.ensure_desktop = serialized_desktop
core.Runtime.stop = serialized_stop

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
