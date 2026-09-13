#!/usr/bin/env python3
"""Protocol-7 Vessel runtime entrypoint.

Uses a persistent guest command agent, but sends command payloads as small
base64 chunks instead of one long tty line. The UML fd console uses the Linux
tty line discipline; a long canonical input line can be truncated near 4 KiB,
which produced `base64.Error: Incorrect padding` in protocol 6. Chunking keeps
every injected line comfortably below that limit and a full-write helper avoids
partial PTY writes.
"""
from __future__ import annotations

import base64
import os
import re
import threading
import time

import vessel_runtime_daemon as core

PROTOCOL_VERSION = 7
core.PROTOCOL_VERSION = PROTOCOL_VERSION

AGENT_READY = "__VESSEL_AGENT_READY__"
CHUNK_SIZE = 1024
AGENT_SOURCE = r'''#!/usr/bin/env python3
import base64, subprocess, sys
chunks = {}
print("__VESSEL_AGENT_READY__", flush=True)
for raw in sys.stdin:
    raw = raw.rstrip("\r\n")
    if not raw.startswith("__VESSEL_CHUNK__ "):
        continue
    try:
        _, nonce, idx_s, total_s, piece = raw.split(" ", 4)
        idx = int(idx_s); total = int(total_s)
        if total < 1 or idx < 0 or idx >= total:
            raise ValueError("bad chunk index")
        entry = chunks.setdefault(nonce, {"total": total, "parts": {}})
        if entry["total"] != total:
            raise ValueError("chunk total changed")
        entry["parts"][idx] = piece
        if len(entry["parts"]) != total:
            continue
        payload = "".join(entry["parts"][i] for i in range(total))
        del chunks[nonce]
        cmd = base64.b64decode(payload.encode("ascii"), validate=True).decode("utf-8")
        print(f"__VESSEL_BEGIN_{nonce}__", flush=True)
        p = subprocess.Popen(["/bin/bash", "-lc", cmd], stdout=sys.stdout, stderr=subprocess.STDOUT)
        rc = p.wait()
        print(f"__VESSEL_DONE_{nonce}__:{rc}", flush=True)
    except Exception as exc:
        chunks.pop(locals().get("nonce", ""), None)
        print(f"__VESSEL_AGENT_ERROR__:{type(exc).__name__}:{exc}", flush=True)
'''
AGENT_B64 = base64.b64encode(AGENT_SOURCE.encode()).decode()

core.runtime.lifecycle_lock = threading.RLock()
core.runtime.agent_ready = False

_original_start = core.Runtime.start
_original_desktop = core.Runtime.ensure_desktop
_original_stop = core.Runtime.stop
_original_prepare = core.Runtime._prepare_venus_guest


def full_write(self: core.Runtime, text: str) -> None:
    if self.master is None:
        raise RuntimeError("UML console is not open")
    data = text.encode()
    view = memoryview(data)
    sent = 0
    while sent < len(view):
        n = os.write(self.master, view[sent:])
        if n <= 0:
            raise OSError("short write to UML console")
        sent += n


def _ensure_agent(self: core.Runtime) -> None:
    if getattr(self, "agent_ready", False):
        return
    # This one bootstrap line is deliberately kept below the tty canonical line
    # limit. All later, potentially huge commands use the chunk protocol.
    handoff = (
        "stty -echo 2>/dev/null || true; "
        f"printf '%s' '{AGENT_B64}' | base64 -d > /root/vessel_guest_agent.py; "
        "chmod 700 /root/vessel_guest_agent.py; "
        "exec python3 -u /root/vessel_guest_agent.py\n"
    )
    if len(handoff.encode()) >= 3500:
        raise RuntimeError("guest-agent bootstrap unexpectedly exceeds safe tty line size")
    with self.lock:
        before = len(self.console_text)
    self._write(handoff)
    deadline = time.monotonic() + 8.0
    while time.monotonic() < deadline:
        with self.lock:
            text = self.console_text
        start = before if before <= len(text) else 0
        if AGENT_READY in text[start:]:
            self.agent_ready = True
            return
        if self.proc is None or self.proc.poll() is not None:
            raise RuntimeError("UML exited while starting Vessel guest agent")
        time.sleep(0.02)
    with self.lock:
        tail = self.console_text[-3000:]
    raise TimeoutError("Vessel guest command agent did not start. Console tail:\n" + tail)


def agent_guest(self: core.Runtime, command: str, timeout: float = 45.0) -> str:
    if not command.strip():
        return ""
    if not self.guest_ready:
        raise RuntimeError("Debian is not ready")

    with self.command_lock:
        _ensure_agent(self)
        nonce = "%x" % time.time_ns()
        payload = base64.b64encode(command.encode()).decode()
        pieces = [payload[i:i + CHUNK_SIZE] for i in range(0, len(payload), CHUNK_SIZE)] or [""]
        begin_marker = f"__VESSEL_BEGIN_{nonce}__"
        done_re = re.compile(re.escape(f"__VESSEL_DONE_{nonce}__") + r":([0-9]+)")
        agent_error = "__VESSEL_AGENT_ERROR__:"
        with self.lock:
            start_len = len(self.console_text)

        total = len(pieces)
        for idx, piece in enumerate(pieces):
            self._write(f"__VESSEL_CHUNK__ {nonce} {idx} {total} {piece}\n")

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
            f"agent did not finish nonce {nonce} ({total} chunks). Console tail:\n{tail}"
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
core.Runtime.guest = agent_guest
core.Runtime._prepare_venus_guest = prepare_with_agent
core.Runtime.start = serialized_start
core.Runtime.ensure_desktop = serialized_desktop
core.Runtime.stop = serialized_stop

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
