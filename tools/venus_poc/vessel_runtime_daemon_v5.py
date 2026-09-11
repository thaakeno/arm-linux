#!/usr/bin/env python3
"""Protocol-5 Vessel runtime entrypoint.

Hardens UML console command execution by serializing lifecycle operations and by
waiting for a fresh shell prompt after every completion marker before another
command may be injected.  The UML stdio console can otherwise lose a line when
back-to-back commands are written immediately after printf's completion marker.
"""
from __future__ import annotations

import re
import threading
import time

import vessel_runtime_daemon as core

PROTOCOL_VERSION = 5
core.PROTOCOL_VERSION = PROTOCOL_VERSION

# One re-entrant lifecycle lock per runtime.  start()/desktop()/stop() are
# allowed to call one another on the same thread, but concurrent control
# requests may not interleave boot, Venus preparation and desktop setup.
core.runtime.lifecycle_lock = threading.RLock()

_original_start = core.Runtime.start
_original_desktop = core.Runtime.ensure_desktop
_original_stop = core.Runtime.stop


def serialized_start(self: core.Runtime, timeout: float = 75.0):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock
    with lock:
        # A second request that arrives while boot is in progress must not treat
        # an alive run_venus_uml.sh process as a fully initialized guest.
        if self.proc is not None and self.proc.poll() is None and not self.guest_ready:
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if self.proc is None or self.proc.poll() is not None:
                    break
                if self.guest_ready:
                    # The first start request may still be preparing Venus.  Its
                    # lifecycle lock prevents us from reaching here concurrently.
                    break
                time.sleep(0.05)
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
        return _original_stop(self)


def robust_guest(self: core.Runtime, command: str, timeout: float = 45.0) -> str:
    if not command.strip():
        return ""
    if not self.guest_ready:
        raise RuntimeError("Debian is not ready")

    nonce = "%x" % time.time_ns()
    marker = f"__VESSEL_DONE_{nonce}__"
    result_re = re.compile(re.escape(marker) + r":([0-9]+)")
    # Leading newline makes sure any half-consumed console line is terminated.
    wrapped = f"\n{command}\nrc=$?\nprintf '\\n{marker}:%d\\n' \"$rc\"\n"

    with self.command_lock:
        # Do not inject a new command until the shell has visibly returned to a
        # prompt from whatever happened before this call.  This matters on the
        # UML fd console, which is not as forgiving as a normal terminal PTY.
        pre_deadline = time.monotonic() + min(2.0, timeout)
        while time.monotonic() < pre_deadline:
            with self.lock:
                tail = self.console_text[-2048:]
            if core.PROMPT in tail:
                break
            time.sleep(0.02)

        with self.lock:
            start_len = len(self.console_text)
        self._write(wrapped)
        deadline = time.monotonic() + timeout

        while time.monotonic() < deadline:
            with self.lock:
                text = self.console_text
            # Search only at/after this command's write point when the rolling
            # buffer has not wrapped; otherwise the nonce still makes a global
            # search unambiguous.
            region_start = start_len if start_len <= len(text) else 0
            match = result_re.search(text, region_start)
            if match is not None:
                rc = int(match.group(1))
                output = text[region_start:match.start()]

                # Crucial: wait for a *fresh* prompt after the completion marker
                # before releasing command_lock.  Previously the next command
                # could be written in the tiny marker->prompt window and vanish
                # on the UML console, producing exactly the timeout seen on-device.
                prompt_deadline = min(deadline, time.monotonic() + 1.5)
                while time.monotonic() < prompt_deadline:
                    with self.lock:
                        after = self.console_text[match.end():]
                    if core.PROMPT in after:
                        break
                    time.sleep(0.015)

                if rc != 0:
                    raise RuntimeError(f"guest command failed rc={rc}: {output[-5000:]}")
                return output

            if self.proc is None or self.proc.poll() is not None:
                raise RuntimeError("UML exited while running guest command")
            time.sleep(0.02)

        with self.lock:
            tail = self.console_text[-6000:]
        raise TimeoutError(
            f"Guest command timed out after {timeout:.1f}s; completion marker {marker} was not observed. "
            f"The command stream is serialized and prompt-gated. Console tail:\n{tail}"
        )


core.Runtime.guest = robust_guest
core.Runtime.start = serialized_start
core.Runtime.ensure_desktop = serialized_desktop
core.Runtime.stop = serialized_stop

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
