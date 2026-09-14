#!/usr/bin/env python3
"""Android input -> Vessel parent wl_seat for nested Weston.

Android sends newline-delimited JSON to Termux's host input bridge. This helper
runs in the UML guest, converts it to fixed-size binary events, and forwards the
events over SOCK_SEQPACKET to Vessel's parent Wayland transport. Weston receives
the resulting wl_pointer/wl_keyboard/wl_touch events from its Wayland backend,
so no /dev/input, uinput, libinput, seatd or VT is involved.

Android IME commitText is converted to normal XKB/evdev key events for the
common UTF-8/ASCII typing path. This keeps the hot input path allocation-light
and works in terminals, GTK/Qt applications and XWayland without a fake kernel
input device.
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
LOG = "/tmp/vessel-input-direct.log"

MAGIC = 0x31534956
ABS, REL, BUTTON, SCROLL, KEY = 1, 2, 3, 4, 5
PACKET = struct.Struct("<IIiiii")
SHIFT = 42

# Linux evdev keycodes for a US pc105 map. Weston receives the matching XKB
# keymap from Vessel's parent seat, so Android IME commits can use the exact same
# path as physical keyboard events.
BASE_KEYS: dict[str, int] = {
    "a": 30, "b": 48, "c": 46, "d": 32, "e": 18, "f": 33,
    "g": 34, "h": 35, "i": 23, "j": 36, "k": 37, "l": 38,
    "m": 50, "n": 49, "o": 24, "p": 25, "q": 16, "r": 19,
    "s": 31, "t": 20, "u": 22, "v": 47, "w": 17, "x": 45,
    "y": 21, "z": 44,
    "1": 2, "2": 3, "3": 4, "4": 5, "5": 6,
    "6": 7, "7": 8, "8": 9, "9": 10, "0": 11,
    "-": 12, "=": 13, "[": 26, "]": 27, ";": 39, "'": 40,
    "`": 41, "\\": 43, ",": 51, ".": 52, "/": 53,
    " ": 57, "\t": 15, "\n": 28, "\r": 28,
}
SHIFTED_KEYS: dict[str, str] = {
    "!": "1", "@": "2", "#": "3", "$": "4", "%": "5", "^": "6",
    "&": "7", "*": "8", "(": "9", ")": "0", "_": "-", "+": "=",
    "{": "[", "}": "]", ":": ";", '"': "'", "~": "`", "|": "\\",
    "<": ",", ">": ".", "?": "/",
}


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
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 128 * 1024)
            s.connect(WAYLAND_INPUT)
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
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 128 * 1024)
            s.connect((HOST, PORT))
            return s
        except OSError:
            s.close()
            time.sleep(0.08)


def send_packet(sock: socket.socket, typ: int, a: int = 0, b: int = 0,
                c: int = 0, d: int = 0) -> None:
    payload = PACKET.pack(MAGIC, typ, int(a), int(b), int(c), int(d))
    sent = sock.send(payload)
    if sent != len(payload):
        raise OSError(f"short Wayland input packet {sent}/{len(payload)}")


def tap_key(sock: socket.socket, code: int, shift: bool = False) -> None:
    if shift:
        send_packet(sock, KEY, SHIFT, 1)
    send_packet(sock, KEY, code, 1)
    send_packet(sock, KEY, code, 0)
    if shift:
        send_packet(sock, KEY, SHIFT, 0)


def type_text(sock: socket.socket, text: str) -> None:
    unsupported = 0
    for ch in text:
        lower = ch.lower()
        if len(lower) == 1 and lower in BASE_KEYS:
            tap_key(sock, BASE_KEYS[lower], shift=(ch.isalpha() and ch.isupper()))
            continue
        base = SHIFTED_KEYS.get(ch)
        if base is not None:
            tap_key(sock, BASE_KEYS[base], shift=True)
            continue
        # Backspace is useful for IMEs which commit corrections as text.
        if ch == "\b":
            tap_key(sock, 14)
            continue
        unsupported += 1
    if unsupported:
        log(f"IME skipped {unsupported} non-US-keymap codepoint(s)")


def decode_ime_text(msg: dict) -> str:
    encoded = msg.get("b64")
    if isinstance(encoded, str) and encoded:
        try:
            return base64.b64decode(encoded, validate=True).decode("utf-8", "replace")
        except (ValueError, UnicodeError):
            return ""
    value = msg.get("text", "")
    return str(value) if value is not None else ""


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
        type_text(compositor, decode_ime_text(msg))
    elif typ == "delete":
        for _ in range(min(64, max(0, int(msg.get("before", 0))))):
            tap_key(compositor, 14)
        for _ in range(min(64, max(0, int(msg.get("after", 0))))):
            tap_key(compositor, 111)


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
                            log("discarding oversized input line")
                            pending.clear()
                        break
                    raw = bytes(pending[:pos])
                    del pending[:pos + 1]
                    if not raw:
                        continue
                    try:
                        obj = json.loads(raw.decode("utf-8"))
                    except (UnicodeError, json.JSONDecodeError):
                        continue
                    if isinstance(obj, dict):
                        dispatch(compositor, obj)
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
