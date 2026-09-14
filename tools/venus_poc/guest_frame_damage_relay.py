#!/usr/bin/env python3
"""Persistent SHM-damage relay for Vessel's nested Weston fallback path.

This relay is intentionally independent of the Venus client lifetime. It owns
one Unix socket for the parent Wayland transport for the entire Debian session
and forwards bounded damage messages to the Termux host over a dedicated TCP
channel. GPU dma-buf traffic continues to use guest_relay_wayland.py.
"""
from __future__ import annotations

import argparse
import array
import os
import socket
import struct
import time

HDR = struct.Struct("!BI")
FRAME_SHM_G2H = 12
FRAME_MSG = struct.Struct("<IIIIIIIIQQ")
FRAME_MAGIC = 0x31574656
FRAME_SHM_DAMAGE = 4
MAX_SHM_PAYLOAD = 64 * 1024 * 1024


def recvn(sock: socket.socket, n: int) -> bytes:
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("peer disconnected")
        out += chunk
    return bytes(out)


def recv_local_frame(conn: socket.socket) -> bytes:
    ancbuf = socket.CMSG_SPACE(4 * struct.calcsize("i"))
    data, anc, flags, _ = conn.recvmsg(FRAME_MSG.size, ancbuf, socket.MSG_WAITALL)
    if not data:
        raise EOFError("transport disconnected")
    if len(data) != FRAME_MSG.size:
        raise EOFError(f"short frame header {len(data)}/{FRAME_MSG.size}")
    if flags & getattr(socket, "MSG_CTRUNC", 0):
        raise RuntimeError("truncated ancillary data")
    for level, ctype, cdata in anc:
        if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
            a = array.array("i")
            usable = len(cdata) - len(cdata) % a.itemsize
            a.frombytes(cdata[:usable])
            for fd in a:
                os.close(fd)
    fields = FRAME_MSG.unpack(data)
    if fields[0] != FRAME_MAGIC:
        raise RuntimeError("bad Vessel frame magic")
    if fields[1] != FRAME_SHM_DAMAGE:
        raise RuntimeError(
            f"persistent damage relay only accepts SHM damage, got type={fields[1]}"
        )
    damage_h = fields[8] >> 32
    payload_len = fields[5] * damage_h
    if payload_len <= 0 or payload_len > MAX_SHM_PAYLOAD:
        raise RuntimeError(f"invalid SHM damage payload {payload_len}")
    return data + recvn(conn, payload_len)


class HostLink:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.sock: socket.socket | None = None

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def connect(self) -> socket.socket:
        if self.sock is not None:
            return self.sock
        while True:
            try:
                s = socket.create_connection((self.host, self.port), timeout=5)
                s.settimeout(None)
                self.sock = s
                print(
                    f"[guest-frame] connected persistent frame host {self.host}:{self.port}",
                    flush=True,
                )
                return s
            except OSError as exc:
                print(f"[guest-frame] host not ready ({exc}); retrying", flush=True)
                time.sleep(0.5)

    def send(self, payload: bytes) -> None:
        packet = HDR.pack(FRAME_SHM_G2H, len(payload)) + payload
        for attempt in range(2):
            try:
                self.connect().sendall(packet)
                return
            except (OSError, EOFError):
                self.close()
                if attempt:
                    raise
                time.sleep(0.05)


def unix_listener(path: str) -> socket.socket:
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(path)
    os.chmod(path, 0o666)
    listener.listen(4)
    return listener


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="10.0.2.2")
    ap.add_argument("--port", type=int, default=5003)
    ap.add_argument("--unix", default="/tmp/vessel-shm-frame.sock")
    args = ap.parse_args()

    listener = unix_listener(args.unix)
    host = HostLink(args.host, args.port)
    print(f"[guest-frame] READY unix={args.unix}", flush=True)
    try:
        while True:
            conn, _ = listener.accept()
            print("[guest-frame] parent transport connected", flush=True)
            try:
                with conn:
                    while True:
                        payload = recv_local_frame(conn)
                        host.send(payload)
            except (EOFError, BrokenPipeError, ConnectionResetError) as exc:
                print(f"[guest-frame] parent transport closed: {exc}", flush=True)
            except Exception as exc:
                print(f"[guest-frame] frame session error: {exc}", flush=True)
    finally:
        host.close()
        listener.close()
        try:
            os.unlink(args.unix)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
