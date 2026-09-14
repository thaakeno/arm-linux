#!/usr/bin/env python3
"""Protocol 34 direct Android -> Vessel compositor input bridge.

The existing Android/Termux bridge already delivers compact JSON input on the
UML guest side. Protocol 34 has no /dev/input or /dev/uinput, so forwarding the
stream into the compositor is both lower latency and avoids inventing a fake
Linux input stack just so Wayland can read it back again.
"""
from __future__ import annotations

import os
import socket
import sys
import time

HOST = os.environ.get("VESSEL_INPUT_HOST", "10.0.2.2")
PORT = int(os.environ.get("VESSEL_INPUT_PORT", "47633"))
WAYLAND_INPUT = os.environ.get("VESSEL_WAYLAND_INPUT", "/tmp/vessel-input.sock")
LOG = "/tmp/vessel-input-direct.log"


def log(msg: str) -> None:
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(f"{time.monotonic():.3f} {msg}\n")
    except OSError:
        pass


def connect_unix() -> socket.socket:
    while True:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            s.connect(WAYLAND_INPUT)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 128 * 1024)
            return s
        except OSError:
            s.close()
            time.sleep(0.05)


def connect_host() -> socket.socket:
    while True:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            s.connect((HOST, PORT))
            return s
        except OSError:
            s.close()
            time.sleep(0.08)


def main() -> None:
    try:
        os.unlink(LOG)
    except FileNotFoundError:
        pass
    while True:
        compositor = connect_unix()
        host = connect_host()
        log(f"connected host={HOST}:{PORT} compositor={WAYLAND_INPUT}")
        try:
            while True:
                chunk = host.recv(65536)
                if not chunk:
                    raise EOFError("host input bridge closed")
                compositor.sendall(chunk)
        except (OSError, EOFError) as exc:
            log(f"reconnect: {exc}")
        finally:
            for s in (host, compositor):
                try:
                    s.close()
                except OSError:
                    pass
            time.sleep(0.05)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
