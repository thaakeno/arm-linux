#!/usr/bin/env python3
"""Protocol-6 Vessel runtime entrypoint.

Replaces fragile interactive-bash prompt/marker command injection with a tiny
persistent command agent inside the UML guest. The agent reads one base64-framed
command per line from stdin, executes it with /bin/bash -lc, streams output back
to the UML console, and emits an unambiguous numeric completion marker.
"""
from __future__ import annotations

import base64
import re
import threading
import time

import vessel_runtime_daemon as core

PROTOCOL_VERSION = 6
core.PROTOCOL_VERSION = PROTOCOL_VERSION

AGENT_READY = "__VESSEL_AGENT_READY__"
AGENT_SOURCE = r'''#!/usr/bin/env python3
import base64, subprocess, sys
print("__VESSEL_AGENT_READY__", flush=True)
for raw in sys.stdin:
    raw = raw.strip()
    if not raw.startswith("__VESSEL_CMD__ "):
        continue
    try:
        _, nonce, payload = raw.split(" ", 2)
        cmd = base64.b64decode(payload.encode("ascii")).decode("utf-8")
        print(f"__VESSEL_BEGIN_{nonce}__", flush=True)
        p = subprocess.Popen(["/bin/bash", "-lc", cmd], stdout=sys.stdout, stderr=subprocess.STDOUT)
        rc = p.wait()
        print(f"__VESSEL_DONE_{nonce}__:{rc}", flush=True)
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


def _ensure_agent(self: core.Runtime) -> None:
    if getattr(self, "agent_ready", False):
        return
    # One raw shell handoff after the login prompt. After exec, readline/bash is
    # completely out of the control path, eliminating bracketed-paste/prompt races.
    handoff = (
        "stty -echo 2>/dev/null || true; "
        f"printf '%s' '{AGENT_B64}' | base64 -d > /root/vessel_guest_agent.py; "
        "chmod 700 /root/vessel_guest_agent.py; "
        "exec python3 -u /root/vessel_guest_agent.py\n"
    )
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
        begin_marker = f"__VESSEL_BEGIN_{nonce}__"
        done_re = re.compile(re.escape(f"__VESSEL_DONE_{nonce}__") + r":([0-9]+)")
        with self.lock:
            start_len = len(self.console_text)
        self._write(f"__VESSEL_CMD__ {nonce} {payload}\n")
        deadline = time.monotonic() + timeout

        while time.monotonic() < deadline:
            with self.lock:
                text = self.console_text
            region_start = start_len if start_len <= len(text) else 0
            match = done_re.search(text, region_start)
            if match is not None:
                rc = int(match.group(1))
                region = text[region_start:match.start()]
                begin = region.find(begin_marker)
                output = region[begin + len(begin_marker):] if begin >= 0 else region
                output = output.lstrip("\r\n")
                if rc != 0:
                    raise RuntimeError(f"guest command failed rc={rc}: {output[-5000:]}")
                return output
            if self.proc is None or self.proc.poll() is not None:
                raise RuntimeError("UML exited while running guest command")
            time.sleep(0.02)

        with self.lock:
            tail = self.console_text[-4000:]
        raise TimeoutError(
            f"Guest command transport timed out after {timeout:.1f}s; agent marker for nonce {nonce} was not returned. "
            f"Console tail:\n{tail}"
        )


def prepare_with_agent(self: core.Runtime) -> None:
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
        try:
            return _original_stop(self)
        finally:
            self.agent_ready = False


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
