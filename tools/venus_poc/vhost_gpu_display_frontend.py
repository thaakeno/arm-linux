#!/usr/bin/env python3
"""Vessel host-side vhost-user-gpu display frontend.

The virtio-gpu backend owns rendering. This process plays QEMU's display-server
role for the vhost-user-gpu side channel and forwards standard RGB scanout
updates over Android loopback TCP to Vessel. The Android app bridges that TCP
stream into its native Vulkan presenter from inside the Vessel UID.

3D rendering remains guest Mesa/VirGL -> host virglrenderer -> ANGLE/Vulkan ->
Adreno. Only the final cross-app presentation hop is copied, because Android
SELinux blocks SCM_RIGHTS directly from the Termux app UID to Vessel.
"""
from __future__ import annotations

import argparse
import array
import os
import socket
import struct
import sys
from dataclasses import dataclass

GPU_HDR = struct.Struct("=III")
GPU_REPLY = 0x4
GPU_GET_PROTOCOL_FEATURES = 1
GPU_SET_PROTOCOL_FEATURES = 2
GPU_GET_DISPLAY_INFO = 3
GPU_CURSOR_POS = 4
GPU_CURSOR_POS_HIDE = 5
GPU_CURSOR_UPDATE = 6
GPU_SCANOUT = 7
GPU_UPDATE = 8
GPU_DMABUF_SCANOUT = 9
GPU_DMABUF_UPDATE = 10
GPU_GET_EDID = 11
GPU_DMABUF_SCANOUT2 = 12

GPU_F_EDID = 1 << 0
GPU_F_DMABUF2 = 1 << 1
GPU_FEATURES = GPU_F_EDID | GPU_F_DMABUF2

U64 = struct.Struct("=Q")
UPDATE = struct.Struct("=IIIII")
SCANOUT = struct.Struct("=III")
DMABUF = struct.Struct("=IIIIIIIIII")
DMABUF2 = struct.Struct("=IIIIIIIIIIQ")
VIRTIO_CTRL = struct.Struct("=IIQIB3s")
DISPLAY_ONE = struct.Struct("=IIIIII")
MAX_SCANOUTS = 16
DISPLAY_INFO_SIZE = VIRTIO_CTRL.size + DISPLAY_ONE.size * MAX_SCANOUTS
EDID_SIZE = VIRTIO_CTRL.size + 8 + 1024

VESSEL_FRAME_MAGIC = 0x31574656
VESSEL_IMPORT = 1
VESSEL_FRAME = 2
VESSEL_SHM_DAMAGE = 4
VESSEL_FRAME_MSG = struct.Struct("=IIIIIIIIQQ")
ANDROID_FRAME_HOST = "127.0.0.1"
ANDROID_FRAME_PORT = 47635


def recvn(sock: socket.socket, n: int) -> bytes:
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("peer disconnected")
        out += chunk
    return bytes(out)


def recv_gpu_message(sock: socket.socket) -> tuple[int, int, bytes, list[int]]:
    header = bytearray()
    fds: list[int] = []
    ancbuf = socket.CMSG_SPACE(8 * array.array("i").itemsize)
    while len(header) < GPU_HDR.size:
        data, anc, flags, _ = sock.recvmsg(GPU_HDR.size - len(header), ancbuf)
        if not data and not anc:
            raise EOFError("GPU display socket closed")
        if flags & getattr(socket, "MSG_CTRUNC", 0):
            raise RuntimeError("truncated GPU SCM_RIGHTS message")
        header += data
        for level, ctype, cdata in anc:
            if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                ints = array.array("i")
                usable = len(cdata) - len(cdata) % ints.itemsize
                ints.frombytes(cdata[:usable])
                fds.extend(ints.tolist())
    request, msg_flags, size = GPU_HDR.unpack(header)
    payload = recvn(sock, size) if size else b""
    return request, msg_flags, payload, fds


def send_reply(sock: socket.socket, request: int, payload: bytes = b"") -> None:
    sock.sendall(GPU_HDR.pack(request, GPU_REPLY, len(payload)) + payload)


def close_fds(fds: list[int]) -> None:
    for fd in fds:
        try:
            os.close(fd)
        except OSError:
            pass


def make_edid(width: int, height: int) -> bytes:
    edid = bytearray(128)
    edid[0:8] = b"\x00\xff\xff\xff\xff\xff\xff\x00"
    edid[8:10] = (0x5A73).to_bytes(2, "big")
    edid[10:12] = (1).to_bytes(2, "little")
    edid[16] = 1
    edid[17] = 4
    edid[18] = 1
    edid[19] = 4
    edid[20] = 0xA5
    edid[21] = max(1, min(255, round(width / 40)))
    edid[22] = max(1, min(255, round(height / 40)))
    edid[23] = 120
    edid[24] = 0x78
    hblank = max(160, width // 5)
    vblank = max(30, height // 20)
    pixel_clock_10khz = min(65535, max(2500, int((width + hblank) * (height + vblank) * 60 / 10000)))
    d = 54
    edid[d:d+2] = pixel_clock_10khz.to_bytes(2, "little")
    edid[d+2] = width & 0xFF
    edid[d+3] = hblank & 0xFF
    edid[d+4] = ((width >> 8) << 4) | ((hblank >> 8) & 0xF)
    edid[d+5] = height & 0xFF
    edid[d+6] = vblank & 0xFF
    edid[d+7] = ((height >> 8) << 4) | ((vblank >> 8) & 0xF)
    edid[d+17] = 0x1A
    n = 72
    edid[n:n+5] = b"\x00\x00\x00\xfc\x00"
    name = b"Vessel GPU\n"
    edid[n+5:n+5+len(name)] = name
    edid[126] = 0
    edid[127] = (-sum(edid[:127])) & 0xFF
    return bytes(edid)


class AndroidPresenter:
    def __init__(self, required: bool):
        self.required = required
        self.sock: socket.socket | None = None

    def connect(self) -> bool:
        if self.sock is not None:
            return True
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            s.connect((ANDROID_FRAME_HOST, ANDROID_FRAME_PORT))
        except OSError:
            s.close()
            if self.required:
                raise
            return False
        self.sock = s
        print("[vugpu-display] connected Vessel Android frame bridge 127.0.0.1:47635", flush=True)
        return True

    def drop(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def import_dmabuf(self, message: bytes, fd: int) -> bool:
        del message, fd
        # Cross-app TCP cannot carry SCM_RIGHTS. Vessel's v38 backend is patched
        # to use standard GPU_UPDATE RGB messages instead of this path.
        return False

    def frame(self, message: bytes) -> bool:
        for attempt in range(2):
            try:
                if not self.connect():
                    return False
                assert self.sock is not None
                self.sock.sendall(message)
                return True
            except OSError:
                self.drop()
                if attempt or not self.required:
                    if self.required:
                        raise
                    return False
        return False

    def shm_damage(self, message: bytes, pixels: bytes) -> bool:
        return self.frame(message + pixels)


@dataclass
class ScanoutState:
    width: int = 0
    height: int = 0
    fd_width: int = 0
    fd_height: int = 0
    stride: int = 0
    fourcc: int = 0
    modifier: int = 0
    serial: int = 0
    imported: bool = False


class Frontend:
    def __init__(self, width: int, height: int, presenter: AndroidPresenter):
        self.width = width
        self.height = height
        self.presenter = presenter
        self.protocol_features = 0
        self.serial = 1
        self.scanouts: dict[int, ScanoutState] = {}

    def display_info(self) -> bytes:
        out = bytearray(DISPLAY_INFO_SIZE)
        first = VIRTIO_CTRL.size
        out[first:first + DISPLAY_ONE.size] = DISPLAY_ONE.pack(0, 0, self.width, self.height, 1, 0)
        return bytes(out)

    def edid_reply(self) -> bytes:
        blob = make_edid(self.width, self.height)
        out = bytearray(EDID_SIZE)
        out[VIRTIO_CTRL.size:VIRTIO_CTRL.size + 8] = struct.pack("=II", len(blob), 0)
        out[VIRTIO_CTRL.size + 8:VIRTIO_CTRL.size + 8 + len(blob)] = blob
        return bytes(out)

    def next_serial(self) -> int:
        s = self.serial
        self.serial += 1
        if self.serial >= (1 << 63):
            self.serial = 1
        return s

    def handle_dmabuf(self, request: int, payload: bytes, fds: list[int]) -> None:
        if request == GPU_DMABUF_SCANOUT2:
            if len(payload) != DMABUF2.size:
                raise RuntimeError(f"bad DMABUF_SCANOUT2 size {len(payload)}")
            fields = DMABUF2.unpack(payload)
            modifier = fields[-1]
            vals = fields[:-1]
        else:
            if len(payload) != DMABUF.size:
                raise RuntimeError(f"bad DMABUF_SCANOUT size {len(payload)}")
            vals = DMABUF.unpack(payload)
            modifier = 0
        (scanout_id, x, y, width, height, fd_width, fd_height, stride, fd_flags, fourcc) = vals
        del x, y, fd_flags
        if width == 0 or height == 0:
            self.scanouts.pop(scanout_id, None)
            close_fds(fds)
            return
        if len(fds) != 1:
            close_fds(fds)
            raise RuntimeError(f"scanout {scanout_id} expected one dma-buf fd, got {len(fds)}")
        fd = fds[0]
        serial = self.next_serial()
        state = ScanoutState(width, height, fd_width, fd_height, stride, fourcc, modifier, serial, False)
        self.scanouts[scanout_id] = state
        try:
            os.close(fd)
        finally:
            pass
        print(
            f"[vugpu-display] unexpected dmabuf scanout={scanout_id} modifier={modifier:x}; "
            "v38 raw-scanout backend is required",
            flush=True,
        )

    def handle_dmabuf_update(self, payload: bytes) -> None:
        if len(payload) != UPDATE.size:
            raise RuntimeError("bad DMABUF_UPDATE size")
        scanout_id, x, y, width, height = UPDATE.unpack(payload)
        print(f"[vugpu-display] ignored unexpected dmabuf update scanout={scanout_id} {width}x{height}+{x}+{y}", flush=True)

    def handle_raw_update(self, payload: bytes) -> None:
        if len(payload) < UPDATE.size:
            raise RuntimeError("short GPU_UPDATE")
        scanout_id, x, y, width, height = UPDATE.unpack(payload[:UPDATE.size])
        pixels = payload[UPDATE.size:]
        state = self.scanouts.get(scanout_id)
        full_width = state.width if state and state.width else self.width
        full_height = state.height if state and state.height else self.height
        expected = width * height * 4
        if len(pixels) != expected:
            raise RuntimeError(f"raw update bytes={len(pixels)} expected={expected}")
        msg = VESSEL_FRAME_MSG.pack(
            VESSEL_FRAME_MAGIC,
            VESSEL_SHM_DAMAGE,
            full_width,
            full_height,
            0x34325258,
            width * 4,
            x,
            y,
            ((height & 0xFFFFFFFF) << 32) | (width & 0xFFFFFFFF),
            0,
        )
        presented = self.presenter.shm_damage(msg, pixels)
        print(
            f"[vugpu-display] raw frame scanout={scanout_id} damage={width}x{height}+{x}+{y} "
            f"presenter={'yes' if presented else 'not-connected'}",
            flush=True,
        )

    def run(self, conn: socket.socket) -> None:
        while True:
            request, flags, payload, fds = recv_gpu_message(conn)
            if flags & GPU_REPLY:
                close_fds(fds)
                raise RuntimeError(f"unexpected reply from GPU backend request={request}")
            if request == GPU_GET_PROTOCOL_FEATURES:
                close_fds(fds)
                send_reply(conn, request, U64.pack(GPU_FEATURES))
            elif request == GPU_SET_PROTOCOL_FEATURES:
                close_fds(fds)
                if len(payload) != U64.size:
                    raise RuntimeError("bad SET_PROTOCOL_FEATURES payload")
                (self.protocol_features,) = U64.unpack(payload)
                print(f"[vugpu-display] protocol features=0x{self.protocol_features:x}", flush=True)
            elif request == GPU_GET_DISPLAY_INFO:
                close_fds(fds)
                send_reply(conn, request, self.display_info())
            elif request == GPU_GET_EDID:
                close_fds(fds)
                send_reply(conn, request, self.edid_reply())
            elif request in (GPU_DMABUF_SCANOUT, GPU_DMABUF_SCANOUT2):
                self.handle_dmabuf(request, payload, fds)
            elif request == GPU_DMABUF_UPDATE:
                close_fds(fds)
                self.handle_dmabuf_update(payload)
                send_reply(conn, request)
            elif request == GPU_SCANOUT:
                close_fds(fds)
                if len(payload) != SCANOUT.size:
                    raise RuntimeError("bad SCANOUT payload")
                scanout_id, width, height = SCANOUT.unpack(payload)
                self.scanouts[scanout_id] = ScanoutState(width=width, height=height)
                print(f"[vugpu-display] software scanout={scanout_id} {width}x{height}", flush=True)
            elif request == GPU_UPDATE:
                close_fds(fds)
                self.handle_raw_update(payload)
            elif request in (GPU_CURSOR_POS, GPU_CURSOR_POS_HIDE, GPU_CURSOR_UPDATE):
                close_fds(fds)
            else:
                close_fds(fds)
                raise RuntimeError(f"unsupported vhost-user-gpu request {request}")


def make_listener(path: str) -> socket.socket:
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(path)
    s.listen(1)
    return s


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--socket", required=True)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--require-android-presenter", action="store_true")
    args = ap.parse_args()
    if args.width <= 0 or args.height <= 0:
        raise SystemExit("width/height must be positive")

    listener = make_listener(args.socket)
    presenter = AndroidPresenter(args.require_android_presenter)
    print(
        f"[vugpu-display] listening {args.socket} mode={args.width}x{args.height} "
        f"android=127.0.0.1:{ANDROID_FRAME_PORT}",
        flush=True,
    )
    try:
        while True:
            conn, _ = listener.accept()
            print("[vugpu-display] GPU backend display channel connected", flush=True)
            try:
                Frontend(args.width, args.height, presenter).run(conn)
            except (EOFError, ConnectionResetError, BrokenPipeError) as exc:
                print(f"[vugpu-display] display channel closed: {exc}", flush=True)
            except Exception as exc:
                print(f"[vugpu-display] display channel error: {exc}", file=sys.stderr, flush=True)
            finally:
                conn.close()
                presenter.drop()
    finally:
        listener.close()
        try:
            os.unlink(args.socket)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
