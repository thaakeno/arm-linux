#!/usr/bin/env python3
import os
import socket
import sys
import time

HOST = os.environ.get("VESSEL_AUDIO_HOST", "10.0.2.2")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 0
RATE = int(sys.argv[2]) if len(sys.argv) > 2 else 48000
CHANNELS = int(sys.argv[3]) if len(sys.argv) > 3 else 2
BITS = int(sys.argv[4]) if len(sys.argv) > 4 else 16
FORMAT = sys.argv[5] if len(sys.argv) > 5 else "S16_LE"


def connect():
    deadline = time.monotonic() + 30.0
    last = None
    while time.monotonic() < deadline:
        try:
            s = socket.create_connection((HOST, PORT), timeout=2)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            return s
        except OSError as exc:
            last = exc
            time.sleep(0.25)
    raise last or ConnectionError("Vessel Android audio bridge unavailable")


def main():
    if PORT <= 0:
        return 2
    sock = connect()
    sock.sendall(f"VESSELAUDIO/1 {RATE} {CHANNELS} {BITS} {FORMAT}\n".encode("ascii"))
    try:
        while True:
            data = os.read(0, 32768)
            if not data:
                break
            sock.sendall(data)
    finally:
        try:
            sock.shutdown(socket.SHUT_WR)
        except OSError:
            pass
        sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
