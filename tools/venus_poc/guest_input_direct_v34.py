#!/usr/bin/env python3
"""Protocol 34 Android input -> Vessel parent seat + Weston input method.

Pointer/touch/key events are converted from the compact Android JSON stream to
a fixed binary packet consumed directly by the parent Wayland seat. Text and
composition events go to the Weston input-method helper, so soft-keyboard text
never has to pretend to be evdev/uinput.
"""
from __future__ import annotations

import base64
import json
import os
import socket
import struct
import sys
import time

HOST = os.environ.get("VESSEL_INPUT_HOST", "10.0.2.2")
PORT = int(os.environ.get("VESSEL_INPUT_PORT", "47633"))
WAYLAND_INPUT = os.environ.get("VESSEL_WAYLAND_INPUT", "/tmp/vessel-input.sock")
IME_SOCKET = os.environ.get("VESSEL_IME_SOCKET", "/tmp/vessel-ime.sock")
LOG = "/tmp/vessel-input-direct.log"
MAGIC = 0x31534956
ABS, REL, BUTTON, SCROLL, KEY = 1, 2, 3, 4, 5
PACKET = struct.Struct("<IIiiii")


def log(msg: str) -> None:
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(f"{time.monotonic():.3f} {msg}\n")
    except OSError:
        pass


def connect_wayland_input() -> socket.socket:
    while True:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        try:
            s.connect(WAYLAND_INPUT)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 128 * 1024)
            return s
        except OSError:
            s.close()
            time.sleep(0.04)


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


def send_packet(sock: socket.socket, typ: int, a: int = 0, b: int = 0,
                c: int = 0, d: int = 0) -> None:
    sock.send(PACKET.pack(MAGIC, typ, int(a), int(b), int(c), int(d)))


def encode_text(value: object) -> str:
    raw = str(value or "").encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def send_ime(command: str) -> bool:
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.settimeout(0.25)
        s.connect(IME_SOCKET)
        s.sendall(command.encode("utf-8") + b"\n")
        return True
    except OSError as exc:
        log(f"IME unavailable: {exc}")
        return False
    finally:
        s.close()


def dispatch(compositor: socket.socket, msg: dict) -> None:
    typ = msg.get("t")
    if typ == "abs":
        send_packet(compositor, ABS, msg.get("x", 0), msg.get("y", 0), 1 if msg.get("down") else 0)
    elif typ == "rel":
        send_packet(compositor, REL, msg.get("dx", 0), msg.get("dy", 0))
    elif typ == "btn":
        send_packet(compositor, BUTTON, msg.get("code", 0), 1 if msg.get("down") else 0)
    elif typ == "scroll":
        send_packet(compositor, SCROLL, msg.get("x", 0), msg.get("y", 0))
    elif typ == "key":
        send_packet(compositor, KEY, msg.get("code", 0), 1 if msg.get("down") else 0)
    elif typ == "text":
        send_ime("C\t" + encode_text(msg.get("text", "")))
    elif typ == "preedit":
        send_ime("P\t" + encode_text(msg.get("text", "")))
    elif typ == "finish":
        send_ime("F")
    elif typ == "delete":
        before = max(0, int(msg.get("before", 0)))
        after = max(0, int(msg.get("after", 0)))
        send_ime(f"D\t{before}\t{after}")


def main() -> None:
    try:
        os.unlink(LOG)
    except FileNotFoundError:
        pass
    while True:
        compositor = connect_wayland_input()
        host = connect_host()
        log(f"connected host={HOST}:{PORT} seat={WAYLAND_INPUT}")
        pending = bytearray()
        try:
            while True:
                chunk = host.recv(65536)
                if not chunk:
                    raise EOFError("host input bridge closed")
                pending.extend(chunk)
                while True:
                    pos = pending.find(b"\n")
                    if pos < 0:
                        if len(pending) > 1_000_000:
                            pending.clear()
                        break
                    raw = bytes(pending[:pos])
                    del pending[:pos + 1]
                    if not raw:
                        continue
                    try:
                        dispatch(compositor, json.loads(raw.decode("utf-8")))
                    except (ValueError, TypeError, json.JSONDecodeError):
                        continue
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
