#!/usr/bin/env python3
"""Persistent low-latency Android -> UML guest input bridge for Vessel.

Android connects to 127.0.0.1:47634. The guest command agent connects back to
10.0.2.2:47633 through umnet/passt. JSON input lines are forwarded verbatim.
No frame/image data ever passes through this bridge.
"""
from __future__ import annotations

import argparse
import socket
import threading
import time


def make_listener(host: str, port: int) -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((host, port))
    s.listen(2)
    return s


class Bridge:
    def __init__(self, android_port: int, guest_port: int) -> None:
        self.android_listener = make_listener("127.0.0.1", android_port)
        self.guest_listener = make_listener("127.0.0.1", guest_port)
        self.lock = threading.Lock()
        self.android: socket.socket | None = None
        self.guest: socket.socket | None = None
        self.generation = 0

    @staticmethod
    def close(sock: socket.socket | None) -> None:
        if sock is None:
            return
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass

    def set_peer(self, kind: str, conn: socket.socket) -> None:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        with self.lock:
            old = self.android if kind == "android" else self.guest
            if kind == "android":
                self.android = conn
            else:
                self.guest = conn
            self.generation += 1
            generation = self.generation
        self.close(old)
        print(f"[input-bridge] {kind} connected generation={generation}", flush=True)
        if kind == "android":
            threading.Thread(target=self.forward_android, args=(conn, generation), daemon=True).start()

    def forward_android(self, source: socket.socket, generation: int) -> None:
        pending = bytearray()
        try:
            while True:
                chunk = source.recv(65536)
                if not chunk:
                    return
                pending.extend(chunk)
                while True:
                    pos = pending.find(b"\n")
                    if pos < 0:
                        if len(pending) > 1_000_000:
                            pending.clear()
                        break
                    line = bytes(pending[:pos + 1])
                    del pending[:pos + 1]
                    # Drop stale pointer events instead of blocking the UI if the
                    # guest input endpoint is temporarily reconnecting.
                    with self.lock:
                        if generation != self.generation and self.android is not source:
                            return
                        guest = self.guest
                    if guest is None:
                        continue
                    try:
                        guest.sendall(line)
                    except OSError:
                        with self.lock:
                            if self.guest is guest:
                                self.guest = None
                        self.close(guest)
        finally:
            with self.lock:
                if self.android is source:
                    self.android = None
            self.close(source)
            print("[input-bridge] android disconnected", flush=True)

    def accept_loop(self, kind: str, listener: socket.socket) -> None:
        while True:
            try:
                conn, _ = listener.accept()
                self.set_peer(kind, conn)
            except OSError as exc:
                print(f"[input-bridge] {kind} accept error: {exc}", flush=True)
                time.sleep(0.1)

    def run(self) -> None:
        print("[input-bridge] listening Android=127.0.0.1:47634 guest=127.0.0.1:47633", flush=True)
        t = threading.Thread(target=self.accept_loop, args=("android", self.android_listener), daemon=True)
        t.start()
        self.accept_loop("guest", self.guest_listener)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--android-port", type=int, default=47634)
    ap.add_argument("--guest-port", type=int, default=47633)
    args = ap.parse_args()
    Bridge(args.android_port, args.guest_port).run()


if __name__ == "__main__":
    main()
