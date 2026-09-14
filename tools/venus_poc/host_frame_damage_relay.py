#!/usr/bin/env python3
"""Persistent host half of Vessel's SHM-damage relay.

Runs independently of Venus/vtest sessions. It accepts framed damage updates
from the Debian guest and forwards them directly to the Android Vulkan
presenter. This keeps the desktop output alive when no Vulkan client exists or
when the compositor falls back to Pixman.
"""
from __future__ import annotations

import argparse
import socket
import struct
import threading
import time

HDR = struct.Struct("!BI")
FRAME_SHM_G2H = 12
FRAME_MSG = struct.Struct("<IIIIIIIIQQ")
FRAME_MAGIC = 0x31574656
FRAME_SHM_DAMAGE = 4
MAX_SHM_PAYLOAD = 64 * 1024 * 1024
ANDROID_FRAME_SOCKET = "\0vessel-wayland-v1"


def recvn(sock: socket.socket, n: int) -> bytes:
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("peer disconnected")
        out += chunk
    return bytes(out)


def recv_frame(sock: socket.socket) -> tuple[int, bytes]:
    t, n = HDR.unpack(recvn(sock, HDR.size))
    if n > FRAME_MSG.size + MAX_SHM_PAYLOAD:
        raise RuntimeError(f"oversized frame payload {n}")
    return t, recvn(sock, n)


class AndroidPresenter:
    def __init__(self) -> None:
        self.sock: socket.socket | None = None
        self.lock = threading.Lock()

    def _drop_locked(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def _connect_locked(self) -> socket.socket:
        if self.sock is not None:
            return self.sock
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(ANDROID_FRAME_SOCKET)
        self.sock = s
        print("[host-frame] connected Android Vulkan presenter", flush=True)
        return s

    def send(self, payload: bytes) -> None:
        with self.lock:
            for attempt in range(2):
                try:
                    self._connect_locked().sendall(payload)
                    return
                except OSError:
                    self._drop_locked()
                    if attempt:
                        raise
                    time.sleep(0.05)

    def close(self) -> None:
        with self.lock:
            self._drop_locked()


def validate_shm(payload: bytes) -> None:
    if len(payload) < FRAME_MSG.size:
        raise RuntimeError("short SHM frame")
    fields = FRAME_MSG.unpack(payload[: FRAME_MSG.size])
    if fields[0] != FRAME_MAGIC or fields[1] != FRAME_SHM_DAMAGE:
        raise RuntimeError("invalid SHM frame header")
    damage_h = fields[8] >> 32
    expected = FRAME_MSG.size + fields[5] * damage_h
    if expected != len(payload) or expected > FRAME_MSG.size + MAX_SHM_PAYLOAD:
        raise RuntimeError(f"bad SHM payload length {len(payload)} expected={expected}")


def handle(conn: socket.socket, presenter: AndroidPresenter) -> None:
    while True:
        t, payload = recv_frame(conn)
        if t != FRAME_SHM_G2H:
            raise RuntimeError(f"unexpected persistent frame type {t}")
        validate_shm(payload)
        presenter.send(payload)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5003)
    args = ap.parse_args()

    ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ls.bind((args.listen, args.port))
    ls.listen(4)
    presenter = AndroidPresenter()
    print(f"[host-frame] READY {args.listen}:{args.port}", flush=True)
    try:
        while True:
            conn, addr = ls.accept()
            print(f"[host-frame] Debian frame relay connected {addr}", flush=True)
            try:
                with conn:
                    handle(conn, presenter)
            except (EOFError, BrokenPipeError, ConnectionResetError, OSError) as exc:
                print(f"[host-frame] frame relay disconnected: {exc}", flush=True)
            except Exception as exc:
                print(f"[host-frame] frame relay error: {exc}", flush=True)
    finally:
        presenter.close()
        ls.close()


if __name__ == "__main__":
    main()
