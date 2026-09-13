#!/usr/bin/env python3
"""Protocol-15 Vessel runtime.

Protocol 14 made VNC presentation persistent, but its broker launcher used
`pkill -f` in the same shell command that also contained the broker script
path. Because `-f` matches the full command line, the launcher shell could
match its own command line and SIGTERM itself. That produced rc=-15 after
Plasma was already ready and made the reconnect layer repeatedly create new
command agents.

Protocol 15 fixes the lifecycle boundary:
* the VNC broker is detected with an anchored pgrep and reused;
* no broker startup path uses pkill -f;
* ordinary guest command failures no longer destroy a healthy command socket;
* SIGTERM of a child command is retried without needlessly re-bootstraping the
  command agent;
* an already-running Xtigervnc/KWin/plasmashell session is adopted immediately,
  so reopening the Desktop page is a warm attach instead of a desktop restart.
"""
from __future__ import annotations

import socket
import struct
import threading
import time

import vessel_runtime_daemon_v14 as v14

core = v14.core
v11 = v14.v11
v10 = v11.v10
v9 = v10.v9
PROTOCOL_VERSION = 15
core.PROTOCOL_VERSION = PROTOCOL_VERSION


class GuestCommandError(RuntimeError):
    """The transport worked; the command itself returned a non-zero status."""


def socket_guest_v15(self: core.Runtime, command: str, timeout: float = 45.0) -> str:
    if not command.strip():
        return ""
    if self.proc is None or self.proc.poll() is not None:
        raise RuntimeError("Debian UML is not running")

    with self.command_lock:
        if not self.guest_ready:
            raise RuntimeError("Debian is not ready")
        v9._ensure_agent(self)
        sock = getattr(self, "agent_socket", None)
        if sock is None:
            raise RuntimeError("guest command socket is unavailable")

        payload = command.encode("utf-8")
        if len(payload) > v9.MAX_COMMAND:
            raise RuntimeError("guest command is too large")
        deadline = time.monotonic() + timeout
        output = bytearray()

        try:
            sock.sendall(struct.pack("!I", len(payload)) + payload)
            while True:
                header = v9.recv_exact(sock, 5, deadline)
                kind = header[:1]
                size = struct.unpack("!I", header[1:])[0]
                if size > v9.MAX_RECORD:
                    raise RuntimeError(f"guest agent record too large: {size}")
                body = v9.recv_exact(sock, size, deadline) if size else b""

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
                        # This is a command result, not a broken transport.
                        raise GuestCommandError(f"guest command failed rc={rc}: {text[-6000:]}")
                    return text
                if kind == b"E":
                    raise GuestCommandError(
                        "guest command agent error: " + body.decode("utf-8", "replace")
                    )
                raise RuntimeError(f"unknown guest agent record type: {kind!r}")
        except GuestCommandError:
            # Keep the healthy agent socket. Protocol 9 used to close it for
            # every non-zero child exit, turning one command error into a storm
            # of PTY bootstraps.
            raise
        except Exception as exc:
            v9.close_agent(self)
            raise RuntimeError(
                f"guest command socket failed; channel will reconnect: {exc}"
            ) from exc


def resilient_guest_v15(
    self: core.Runtime,
    command: str,
    timeout: float = 45.0,
    attempts: int = 8,
) -> str:
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            out = socket_guest_v15(self, command, timeout)
            if self.last_error and v11._transient(RuntimeError(self.last_error)):
                self.last_error = ""
            return out
        except GuestCommandError as exc:
            last = exc
            text = str(exc).lower()
            # SIGTERM is retryable for the short idempotent control operations
            # used by the desktop setup, but the command channel itself is fine.
            if "rc=-15" not in text and "signal 15" not in text and "sigterm" not in text:
                raise
            if attempt + 1 < attempts:
                time.sleep(min(0.8, 0.10 * (attempt + 1)))
                continue
            raise
        except Exception as exc:
            last = exc
            text = str(exc).lower()
            transport = any(t in text for t in (
                "socket", "channel", "connection", "broken pipe", "timed out", "timeout"
            ))
            if not transport:
                raise
            v9.close_agent(self)
            if self.proc is not None and self.proc.poll() is None:
                self.guest_ready = True
            if attempt + 1 < attempts:
                time.sleep(min(1.0, 0.15 * (attempt + 1)))
                continue
            raise
    assert last is not None
    raise last


def start_persistent_broker_v15(self: core.Runtime) -> None:
    """Reuse or launch exactly one broker without matching our own shell."""
    # Anchoring the regex to python3 means it cannot match `/bin/bash -lc ...`
    # even though that shell's command text contains the broker path.
    probe = (
        "p=$(pgrep -f '^python3 -u /root/vessel_vnc_reverse_persistent.py ' | head -n1 || true); "
        "if [ -n \"$p\" ] && kill -0 \"$p\" 2>/dev/null; then "
        "echo BROKER_READY:$p; "
        "else "
        "setsid python3 -u /root/vessel_vnc_reverse_persistent.py "
        f"10.0.2.2 {core.VNC_REVERSE_PORT} {v14.VNC_UNIX} "
        ">/tmp/vessel-vnc-broker.log 2>&1 </dev/null & "
        "p=$!; echo $p >/tmp/vessel-vnc-broker.pid; echo BROKER_LAUNCHED:$p; "
        "fi"
    )
    out = resilient_guest_v15(self, probe, 8.0)
    if "BROKER_READY:" not in out and "BROKER_LAUNCHED:" not in out:
        raise RuntimeError("Could not launch persistent VNC reverse broker")

    deadline = time.monotonic() + 6.0
    while time.monotonic() < deadline:
        chk = resilient_guest_v15(
            self,
            "p=$(pgrep -f '^python3 -u /root/vessel_vnc_reverse_persistent.py ' | head -n1 || true); "
            "[ -n \"$p\" ] && kill -0 \"$p\" 2>/dev/null && echo BROKER_READY || true",
            4.0,
            attempts=3,
        )
        if "BROKER_READY" in chk:
            return
        time.sleep(0.15)
    tail = resilient_guest_v15(
        self, "tail -n 80 /tmp/vessel-vnc-broker.log 2>/dev/null || true", 5.0, attempts=3
    )
    raise RuntimeError("Persistent VNC reverse broker did not stay running: " + tail[-3000:])


def _ensure_reverse_proxy(self: core.Runtime) -> None:
    if self.vnc_proxy is None:
        self.vnc_proxy = core.ReverseVncProxy(self)
        self.vnc_proxy.start()


def _warm_desktop_ready(self: core.Runtime) -> bool:
    try:
        out = resilient_guest_v15(
            self,
            "x=$(pgrep -f '[X]tigervnc.*:1' || true); "
            "k=$(pgrep -f '[k]win_x11' || true); "
            "p=$(pgrep -f '[p]lasmashell' || true); "
            f"[ -S {v14.VNC_UNIX} ] && [ -n \"$x\" ] && [ -n \"$k\" ] && [ -n \"$p\" ] && echo WARM_DESKTOP_READY || true",
            5.0,
            attempts=3,
        )
        return "WARM_DESKTOP_READY" in out
    except Exception:
        return False


def desktop_v15(self: core.Runtime, width: int = 1280, height: int = 800, dpi: int = 120):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock

    with lock:
        self.start()
        self.last_error = ""

        # Opening the Desktop page or retrying after an Android UI recreation
        # should not restart X11/Plasma. Adopt the existing session immediately.
        if _warm_desktop_ready(self):
            self.set_progress("desktop_attach", 96, "Attaching to running KDE Plasma")
            _ensure_reverse_proxy(self)
            start_persistent_broker_v15(self)
            self.desktop_ready = True
            self.last_error = ""
            self.set_progress("desktop_ready", 100, "KDE Plasma is live")
            return self.state()

        # Start the host listener before cold desktop setup. The persistent guest
        # broker can connect as soon as it launches, eliminating another race.
        _ensure_reverse_proxy(self)
        result = v14.desktop_v14(self, width, height, dpi)
        return result


# Patch the inherited protocol stack at the dynamic call sites used by v14.
v9.socket_guest = socket_guest_v15
v11.resilient_guest = resilient_guest_v15
v14.start_persistent_broker = start_persistent_broker_v15
core.Runtime.ensure_desktop = desktop_v15

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
