#!/usr/bin/env python3
"""Vessel guest framebuffer streamer.

Reads Xvfb's mmap-backed XWD framebuffer and sends changed frames to the
Termux-side native display bridge. This is a dedicated Vessel transport, not
RFB/VNC, and it never injects input.
"""
from __future__ import annotations

import json
import os
import socket
import struct
import sys
import time
import zlib

XWD_VERSION = 7
ZPIXMAP = 2


def read_exact(f, n: int) -> bytes:
    out = bytearray()
    while len(out) < n:
        chunk = f.read(n - len(out))
        if not chunk:
            raise EOFError("framebuffer truncated")
        out += chunk
    return bytes(out)


def parse_xwd(path: str):
    f = open(path, "rb", buffering=0)
    head = read_exact(f, 100)
    chosen = None
    for endian in (">", "<"):
        values = struct.unpack(endian + "25I", head)
        header_size, version, pixmap_format, depth, width, height = values[:6]
        bpp = values[11]
        stride = values[12]
        ncolors = values[19]
        if (
            version == XWD_VERSION
            and pixmap_format == ZPIXMAP
            and 320 <= width <= 7680
            and 240 <= height <= 4320
            and bpp in (24, 32)
            and stride >= width * (bpp // 8)
            and 100 <= header_size <= 65536
            and ncolors <= 4096
        ):
            chosen = (endian, values)
            break
    if chosen is None:
        f.close()
        raise RuntimeError("unrecognized Xvfb XWD framebuffer header")
    endian, v = chosen
    header_size, _, _, _, width, height = v[:6]
    byte_order = v[7]
    bpp = v[11]
    stride = v[12]
    red_mask, green_mask, blue_mask = v[14], v[15], v[16]
    ncolors = v[19]
    offset = header_size + ncolors * 12
    return f, {
        "endian": endian,
        "width": width,
        "height": height,
        "byte_order": byte_order,
        "bpp": bpp,
        "stride": stride,
        "red_mask": red_mask,
        "green_mask": green_mask,
        "blue_mask": blue_mask,
        "offset": offset,
    }


def normalize_bgra(raw: bytes, meta: dict) -> bytes:
    width = meta["width"]
    height = meta["height"]
    stride = meta["stride"]
    bpp = meta["bpp"]
    byte_order = meta["byte_order"]
    red = meta["red_mask"]
    green = meta["green_mask"]
    blue = meta["blue_mask"]
    if bpp != 32:
        raise RuntimeError(f"Vessel native display requires 32bpp Xvfb, got {bpp}")

    packed = bytearray(width * height * 4)
    dst = 0
    for y in range(height):
        row = raw[y * stride:y * stride + width * 4]
        if byte_order == 0 and red == 0x00FF0000 and green == 0x0000FF00 and blue == 0x000000FF:
            packed[dst:dst + width * 4] = row
            packed[dst + 3:dst + width * 4:4] = b"\xff" * width
            dst += width * 4
            continue
        # Generic 32-bit XWD conversion. XWD byte_order 0 is LSBFirst.
        order = "little" if byte_order == 0 else "big"
        for x in range(width):
            p = int.from_bytes(row[x * 4:x * 4 + 4], order)
            rv = (p & red) >> ((red & -red).bit_length() - 1 if red else 0)
            gv = (p & green) >> ((green & -green).bit_length() - 1 if green else 0)
            bv = (p & blue) >> ((blue & -blue).bit_length() - 1 if blue else 0)
            packed[dst:dst + 4] = bytes((bv & 255, gv & 255, rv & 255, 255))
            dst += 4
    return bytes(packed)


def connect(host: str, port: int):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    s.connect((host, port))
    return s


def run(path: str, host: str, port: int, fps: int = 30):
    frame_file, meta = parse_xwd(path)
    width, height = meta["width"], meta["height"]
    raw_size = meta["stride"] * height
    header = {
        "magic": "VFRM1",
        "width": width,
        "height": height,
        "stride": width * 4,
        "format": "BGRA8888",
    }
    seq = 0
    previous_crc = -1
    period = 1.0 / max(5, min(int(fps), 60))
    sock = None
    try:
        while True:
            if sock is None:
                try:
                    sock = connect(host, port)
                    sock.sendall((json.dumps(header, separators=(",", ":")) + "\n").encode())
                except OSError:
                    if sock:
                        try: sock.close()
                        except OSError: pass
                    sock = None
                    time.sleep(0.15)
                    continue
            started = time.monotonic()
            frame_file.seek(meta["offset"])
            raw = read_exact(frame_file, raw_size)
            crc = zlib.crc32(raw)
            if crc != previous_crc:
                previous_crc = crc
                bgra = normalize_bgra(raw, meta)
                payload = zlib.compress(bgra, 1)
                seq += 1
                packet = struct.pack("!QII", seq, len(payload), len(bgra)) + payload
                try:
                    sock.sendall(packet)
                except OSError:
                    try: sock.close()
                    except OSError: pass
                    sock = None
            delay = period - (time.monotonic() - started)
            if delay > 0:
                time.sleep(delay)
    finally:
        frame_file.close()
        if sock:
            try: sock.close()
            except OSError: pass


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/vessel-fb/Xvfb_screen0"
    host = sys.argv[2] if len(sys.argv) > 2 else "10.0.2.2"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 47637
    fps = int(sys.argv[4]) if len(sys.argv) > 4 else 30
    run(path, host, port, fps)
