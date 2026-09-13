#!/usr/bin/env python3
"""Vessel guest framebuffer streamer.

Reads Xvfb's mmap-backed XWD framebuffer and sends changed frames to the
Termux-side native display bridge. This is a dedicated Vessel transport, not
RFB/VNC, and it never injects input.
"""
from __future__ import annotations

import json
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


def normalize_rgba(raw: bytes, meta: dict) -> bytes:
    """Convert Xvfb's XWD pixels to the byte order Android Bitmap expects.

    Xvfb's common little-endian 0x00RRGGBB visual is stored as B,G,R,X bytes.
    The previous bridge labelled those bytes BGRA but fed them directly into an
    Android ARGB_8888 bitmap, visibly swapping red and blue. Emit explicit RGBA
    bytes instead. Extended-slice copies keep the hot path in C rather than a
    Python per-pixel loop.
    """
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
    row_bytes = width * 4
    common = byte_order == 0 and red == 0x00FF0000 and green == 0x0000FF00 and blue == 0x000000FF
    for y in range(height):
        row = raw[y * stride:y * stride + row_bytes]
        out = memoryview(packed)[dst:dst + row_bytes]
        if common:
            out[0::4] = row[2::4]  # R
            out[1::4] = row[1::4]  # G
            out[2::4] = row[0::4]  # B
            out[3::4] = b"\xff" * width
            dst += row_bytes
            continue
        order = "little" if byte_order == 0 else "big"
        for x in range(width):
            p = int.from_bytes(row[x * 4:x * 4 + 4], order)
            rs = ((red & -red).bit_length() - 1) if red else 0
            gs = ((green & -green).bit_length() - 1) if green else 0
            bs = ((blue & -blue).bit_length() - 1) if blue else 0
            rv = (p & red) >> rs
            gv = (p & green) >> gs
            bv = (p & blue) >> bs
            packed[dst:dst + 4] = bytes((rv & 255, gv & 255, bv & 255, 255))
            dst += 4
    return bytes(packed)


def connect(host: str, port: int):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    s.connect((host, port))
    return s


def run(path: str, host: str, port: int, fps: int = 60):
    frame_file, meta = parse_xwd(path)
    width, height = meta["width"], meta["height"]
    raw_size = meta["stride"] * height
    header = {
        "magic": "VFRM1",
        "width": width,
        "height": height,
        "stride": width * 4,
        "format": "RGBA8888",
        "fps": max(5, min(int(fps), 120)),
    }
    seq = 0
    previous_crc = -1
    target_fps = max(5, min(int(fps), 120))
    period = 1.0 / target_fps
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
                    time.sleep(0.05)
                    continue
            started = time.monotonic()
            frame_file.seek(meta["offset"])
            raw = read_exact(frame_file, raw_size)
            crc = zlib.crc32(raw)
            if crc != previous_crc:
                previous_crc = crc
                rgba = normalize_rgba(raw, meta)
                # Level 1 is deliberately used: on a local UML link latency is
                # more important than squeezing the last bytes out of a frame.
                payload = zlib.compress(rgba, 1)
                seq += 1
                packet = struct.pack("!QII", seq, len(payload), len(rgba)) + payload
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
    fps = int(sys.argv[4]) if len(sys.argv) > 4 else 60
    run(path, host, port, fps)
