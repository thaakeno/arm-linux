#!/usr/bin/env python3
"""CI consumer for Vessel's compositor dma-buf stream.

It requires a real dma-buf import followed by a frame notification. For linear
buffers it also maps the exported image and verifies that the compositor output
contains non-uniform/non-black pixels. The probe intentionally rejects SHM
frames so CI cannot silently validate a software fallback.
"""
from __future__ import annotations

import argparse
import array
import mmap
import os
import socket
import struct
import time

MSG = struct.Struct("<IIIIIIIIQQ")
MAGIC = 0x31574656
IMPORT = 1
FRAME = 2
RESET = 3
SHM = 4
DRM_FORMAT_MOD_LINEAR = 0
DRM_FORMAT_MOD_INVALID = 0x00FFFFFFFFFFFFFF


def recv_header(conn: socket.socket):
    control = socket.CMSG_SPACE(4 * struct.calcsize("i"))
    data, anc, flags, _ = conn.recvmsg(MSG.size, control, socket.MSG_WAITALL)
    if not data:
        raise EOFError("frame producer closed")
    if len(data) != MSG.size:
        raise RuntimeError(f"short frame header {len(data)}/{MSG.size}")
    fd = -1
    for level, ctype, cdata in anc:
        if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
            fds = array.array("i")
            usable = len(cdata) - len(cdata) % fds.itemsize
            fds.frombytes(cdata[:usable])
            if fds:
                fd = fds[0]
                for extra in fds[1:]:
                    os.close(extra)
    fields = MSG.unpack(data)
    if fields[0] != MAGIC:
        if fd >= 0:
            os.close(fd)
        raise RuntimeError(f"bad frame magic {fields[0]:08x}")
    return fields, fd


def check_pixels(fd: int, width: int, height: int, stride: int, offset: int, modifier: int) -> tuple[int, int]:
    if modifier not in (DRM_FORMAT_MOD_LINEAR, DRM_FORMAT_MOD_INVALID):
        raise RuntimeError(f"CI screen probe requires a mappable linear dma-buf, modifier=0x{modifier:x}")
    length = offset + stride * height
    if length <= 0:
        raise RuntimeError("invalid dma-buf geometry")
    mm = mmap.mmap(fd, length, flags=mmap.MAP_SHARED, prot=mmap.PROT_READ)
    try:
        view = memoryview(mm)[offset:offset + stride * height]
        # Sample across the full output. We deliberately ignore alpha bytes in
        # the statistical test because an all-black opaque screen has 0xff alpha.
        rgb_values = []
        y_step = max(1, height // 64)
        x_step = max(1, width // 64)
        for y in range(0, height, y_step):
            row = y * stride
            for x in range(0, width, x_step):
                p = row + x * 4
                if p + 2 < len(view):
                    rgb_values.extend((view[p], view[p + 1], view[p + 2]))
        if not rgb_values:
            raise RuntimeError("no pixel samples")
        lo, hi = min(rgb_values), max(rgb_values)
        nonzero = sum(v != 0 for v in rgb_values)
        unique = len(set(rgb_values))
        if nonzero < max(24, len(rgb_values) // 100) or unique < 3 or hi == lo:
            raise RuntimeError(
                f"compositor dma-buf looks blank: min={lo} max={hi} unique={unique} nonzero={nonzero}/{len(rgb_values)}"
            )
        return unique, nonzero
    finally:
        try:
            view.release()  # type: ignore[name-defined]
        except Exception:
            pass
        mm.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--socket", required=True)
    ap.add_argument("--timeout", type=float, default=25.0)
    args = ap.parse_args()

    try:
        os.unlink(args.socket)
    except FileNotFoundError:
        pass
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(args.socket)
    server.listen(1)
    server.settimeout(args.timeout)
    print(f"SCREEN_PROBE_READY socket={args.socket}", flush=True)

    conn, _ = server.accept()
    conn.settimeout(args.timeout)
    latest = None
    latest_fd = -1
    deadline = time.monotonic() + args.timeout
    try:
        while time.monotonic() < deadline:
            fields, fd = recv_header(conn)
            _magic, kind, width, height, fourcc, stride, offset, _reserved, modifier, serial = fields
            if kind == SHM:
                if fd >= 0:
                    os.close(fd)
                raise RuntimeError("GPU-only CI received forbidden SHM frame")
            if kind == IMPORT:
                if fd < 0:
                    raise RuntimeError("dma-buf import missing fd")
                if latest_fd >= 0:
                    os.close(latest_fd)
                latest_fd = fd
                latest = fields
                print(
                    f"DMABUF_IMPORT serial={serial} size={width}x{height} stride={stride} fourcc=0x{fourcc:08x} modifier=0x{modifier:x}",
                    flush=True,
                )
                continue
            if fd >= 0:
                os.close(fd)
            if kind == RESET:
                if latest_fd >= 0:
                    os.close(latest_fd)
                latest_fd = -1
                latest = None
                continue
            if kind != FRAME or latest is None or latest_fd < 0:
                continue
            _, _, iw, ih, _ifourcc, istride, ioffset, _, imod, iserial = latest
            if serial != iserial:
                continue
            unique, nonzero = check_pixels(latest_fd, iw, ih, istride, ioffset, imod)
            print(
                f"GPU_SCREEN_OK serial={serial} size={iw}x{ih} unique_rgb_values={unique} nonzero_rgb_samples={nonzero}",
                flush=True,
            )
            return
        raise TimeoutError("no verifiable native Vulkan dma-buf frame arrived")
    finally:
        if latest_fd >= 0:
            os.close(latest_fd)
        conn.close()
        server.close()
        try:
            os.unlink(args.socket)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
