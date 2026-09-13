#!/usr/bin/env python3
"""Protocol-4 Vessel runtime entrypoint with hardened UML console command parsing."""
from __future__ import annotations

import re
import time

import vessel_runtime_daemon as core


PROTOCOL_VERSION = 4
core.PROTOCOL_VERSION = PROTOCOL_VERSION


def robust_guest(self: core.Runtime, command: str, timeout: float = 45.0) -> str:
    """Run one command in the UML guest and wait for an unambiguous completion marker.

    The old implementation sliced console_text using a saved string index. PTY output can
    be asynchronously rewritten/truncated, so a completion marker that visibly reached
    the log could still fall outside that slice and time out. Search the bounded rolling
    console by a per-command nonce instead; the nonce is unique and the regex requires a
    numeric status, so an echoed printf command cannot be mistaken for completion.
    """
    if not command.strip():
        return ""
    if not self.guest_ready:
        raise RuntimeError("Debian is not ready")

    nonce = "%x" % time.time_ns()
    marker = f"__VESSEL_DONE_{nonce}__"
    result_re = re.compile(re.escape(marker) + r":([0-9]+)")
    wrapped = f"{command}\nrc=$?\nprintf '\\n{marker}:%d\\n' \"$rc\"\n"

    with self.command_lock:
        with self.lock:
            start_len = len(self.console_text)
        self._write(wrapped)
        deadline = time.monotonic() + timeout

        while time.monotonic() < deadline:
            with self.lock:
                text = self.console_text
            match = result_re.search(text)
            if match is not None:
                rc = int(match.group(1))
                # Preserve only output produced after this command when possible.
                begin = start_len if start_len <= match.start() else 0
                output = text[begin:match.start()]
                if rc != 0:
                    raise RuntimeError(
                        f"guest command failed rc={rc}: {output[-5000:]}"
                    )
                return output
            if self.proc is None or self.proc.poll() is not None:
                raise RuntimeError("UML exited while running guest command")
            time.sleep(0.03)

        with self.lock:
            tail = self.console_text[-5000:]
        raise TimeoutError(
            f"Guest command timed out after {timeout:.1f}s; completion marker {marker} "
            f"was not observed by the parser. Console tail:\n{tail}"
        )


core.Runtime.guest = robust_guest


if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
