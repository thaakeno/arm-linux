#!/usr/bin/env python3
"""Venus + KWin dma-buf relay for Vessel protocol 31.

Host Venus external objects are mapped into UML through /dev/umshm. If Mesa or
KWin later sends one of those file descriptions back over SCM_RIGHTS, kcmp()
identifies the original object and we send only its object id to the Android
host. Pixel data never crosses the TCP control transport.

AF_UNIX SOCK_STREAM ancillary data is a barrier rather than a message record.
When recvmsg() receives SCM_RIGHTS it can also return ordinary stream bytes that
precede the one-byte descriptor carrier.  Prefix bytes must remain plain stream
data or the receiving vtest side can consume and discard the descriptor before
its dedicated recvmsg() call.
"""
from __future__ import annotations

import argparse
import array
import ctypes
import os
import platform
import socket
import struct
import threading
import time

HDR = struct.Struct("!BI")
DATA_H2G = 1
DATA_G2H = 2
FD_DIRECT_H2G = 6
FD_SIGNAL_H2G = 7
SIGNAL_H2G = 8
FD_REF_G2H = 9
FRAME_IMPORT_G2H = 10
FRAME_NOTIFY_G2H = 11
FRAME_MSG = struct.Struct("<IIIIIIIIQQ")
FRAME_MAGIC = 0x31574656
FRAME_IMPORT = 1
FRAME_FRAME = 2
KCMP_FILE = 0
SYS_KCMP = 272
libc = ctypes.CDLL(None, use_errno=True)


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
    return t, recvn(sock, n)


class FramedWriter:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.lock = threading.Lock()

    def send(self, t: int, payload: bytes = b"") -> None:
        with self.lock:
            self.sock.sendall(HDR.pack(t, len(payload)) + payload)


class Relay:
    def __init__(self, tcp: socket.socket, local: socket.socket, device: str, frame_path: str):
        self.tcp = tcp
        self.local = local
        self.device = device
        self.frame_path = frame_path
        self.writer = FramedWriter(tcp)
        self.stop = threading.Event()
        self.error: Exception | None = None
        self.sync_writers: dict[int, int] = {}
        self.sync_lock = threading.Lock()
        self.object_fds: dict[int, int] = {}
        self.object_lock = threading.Lock()
        self.frame_listener: socket.socket | None = None

    def _worker(self, fn, label: str) -> None:
        try:
            fn()
        except (EOFError, BrokenPipeError, ConnectionResetError, OSError) as exc:
            if not self.stop.is_set():
                print(f"[guest-wayland] {label} closed: {exc}", flush=True)
        except Exception as exc:
            self.error = exc
            print(f"[guest-wayland] {label} error: {exc}", flush=True)
        finally:
            self.stop.set()

    @staticmethod
    def _same_open_file(a: int, b: int) -> bool:
        if a == b:
            return True
        rc = libc.syscall(SYS_KCMP, os.getpid(), os.getpid(), KCMP_FILE, a, b)
        if rc == 0:
            return True
        if rc < 0:
            err = ctypes.get_errno()
            if err in (1, 13):
                raise RuntimeError(f"kcmp(KCMP_FILE) blocked by guest kernel errno={err}")
        return False

    def resolve_object(self, fd: int) -> int:
        with self.object_lock:
            items = list(self.object_fds.items())
        for obj_id, known in items:
            if self._same_open_file(fd, known):
                return obj_id
        raise RuntimeError("SCM_RIGHTS fd does not map to a retained /dev/umshm Venus object")

    def remember_object(self, obj_id: int, fd: int) -> None:
        retained = os.dup(fd)
        with self.object_lock:
            old = self.object_fds.pop(obj_id, None)
            if old is not None:
                os.close(old)
            self.object_fds[obj_id] = retained

    @staticmethod
    def _split_fd_carrier(data: bytes) -> tuple[bytes, bytes]:
        if not data:
            raise RuntimeError("SCM_RIGHTS message missing carrier byte")
        return data[:-1], data[-1:]

    def local_to_host(self) -> None:
        ancbuf = socket.CMSG_SPACE(16 * struct.calcsize("i"))
        while not self.stop.is_set():
            data, anc, flags, _ = self.local.recvmsg(65536, ancbuf)
            if flags & getattr(socket, "MSG_CTRUNC", 0):
                raise RuntimeError("guest SCM_RIGHTS control message truncated")
            if not data and not anc:
                raise EOFError("Mesa client closed")
            fds: list[int] = []
            for level, ctype, cdata in anc:
                if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                    a = array.array("i")
                    usable = len(cdata) - (len(cdata) % a.itemsize)
                    a.frombytes(cdata[:usable])
                    fds.extend(a.tolist())
            if not fds:
                if data:
                    self.writer.send(DATA_G2H, data)
                continue
            if len(fds) != 1:
                for fd in fds:
                    os.close(fd)
                raise RuntimeError(f"expected one guest external fd, got {len(fds)}")

            prefix, carrier = self._split_fd_carrier(data)
            if prefix:
                self.writer.send(DATA_G2H, prefix)

            fd = fds[0]
            try:
                obj_id = self.resolve_object(fd)
                self.writer.send(FD_REF_G2H, struct.pack("!II", obj_id, len(carrier)) + carrier)
                print(
                    f"[guest-wayland] returned external fd as object id={obj_id} with exact 1-byte carrier",
                    flush=True,
                )
            finally:
                os.close(fd)

    def make_umshm_fd(self, obj_id: int) -> int:
        fd = os.open(self.device, os.O_RDWR | getattr(os, "O_CLOEXEC", 0))
        try:
            n = os.write(fd, struct.pack("=I", obj_id))
            if n != 4:
                raise RuntimeError(f"short /dev/umshm bind {n}/4 id={obj_id}")
            self.remember_object(obj_id, fd)
            return fd
        except Exception:
            os.close(fd)
            raise

    def pass_fd_to_mesa(self, fd: int, data: bytes, label: str) -> None:
        if len(data) != 1:
            raise RuntimeError(f"{label}: SCM_RIGHTS carrier must be exactly one byte, got {len(data)}")
        anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]).tobytes())]
        sent = self.local.sendmsg([data], anc)
        if sent != len(data):
            raise RuntimeError(f"short SCM_RIGHTS sendmsg {sent}/{len(data)} ({label})")

    def make_sync_pipe(self, sync_id: int, data: bytes) -> None:
        flags = getattr(os, "O_CLOEXEC", 0)
        read_fd, write_fd = os.pipe2(flags) if hasattr(os, "pipe2") else os.pipe()
        try:
            self.pass_fd_to_mesa(read_fd, data, f"sync id={sync_id}")
            with self.sync_lock:
                old = self.sync_writers.pop(sync_id, None)
                if old is not None:
                    os.close(old)
                self.sync_writers[sync_id] = write_fd
            write_fd = -1
        finally:
            os.close(read_fd)
            if write_fd >= 0:
                os.close(write_fd)

    def signal_sync(self, sync_id: int) -> None:
        with self.sync_lock:
            write_fd = self.sync_writers.pop(sync_id, None)
        if write_fd is None:
            if not self.stop.is_set():
                raise RuntimeError(f"signal for unknown sync id={sync_id}")
            return
        try:
            os.write(write_fd, b"\x01")
        finally:
            os.close(write_fd)

    def host_to_local(self) -> None:
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t == DATA_H2G:
                self.local.sendall(payload)
            elif t == SIGNAL_H2G:
                if len(payload) != 4:
                    raise RuntimeError("bad SIGNAL_H2G frame")
                self.signal_sync(struct.unpack("!I", payload)[0])
            elif t == FD_SIGNAL_H2G:
                if len(payload) < 8:
                    raise RuntimeError("short FD_SIGNAL_H2G")
                sync_id, data_len = struct.unpack("!II", payload[:8])
                data = payload[8:8 + data_len]
                if len(data) != data_len or len(data) != 1:
                    raise RuntimeError("sync fd missing one-byte carrier")
                self.make_sync_pipe(sync_id, data)
            elif t == FD_DIRECT_H2G:
                if len(payload) < 16:
                    raise RuntimeError("short FD_DIRECT_H2G")
                obj_id, size, data_len = struct.unpack("!IQI", payload[:16])
                data = payload[16:16 + data_len]
                if len(data) != data_len or len(data) != 1:
                    raise RuntimeError("external fd missing one-byte carrier")
                fd = self.make_umshm_fd(obj_id)
                try:
                    self.pass_fd_to_mesa(fd, data, f"umshm id={obj_id}")
                    print(f"[guest-wayland] mapped host Venus object id={obj_id} size={size}", flush=True)
                finally:
                    os.close(fd)
            else:
                raise RuntimeError(f"unexpected host frame type {t}")

    @staticmethod
    def _recv_frame_message(conn: socket.socket) -> tuple[bytes, int]:
        control = bytearray(socket.CMSG_SPACE(4 * struct.calcsize("i")))
        data, anc, _flags, _ = conn.recvmsg(FRAME_MSG.size, len(control), socket.MSG_WAITALL)
        if len(data) != FRAME_MSG.size:
            raise EOFError("short KWin frame message")
        passed = -1
        for level, ctype, cdata in anc:
            if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                a = array.array("i")
                usable = len(cdata) - len(cdata) % a.itemsize
                a.frombytes(cdata[:usable])
                if a:
                    passed = a[0]
                    for extra in a[1:]:
                        os.close(extra)
        return data, passed

    def frame_export_loop(self) -> None:
        try:
            os.unlink(self.frame_path)
        except FileNotFoundError:
            pass
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(self.frame_path)
        os.chmod(self.frame_path, 0o600)
        listener.listen(2)
        listener.settimeout(0.5)
        self.frame_listener = listener
        print(f"[guest-wayland] KWin frame export socket {self.frame_path}", flush=True)
        try:
            while not self.stop.is_set():
                try:
                    conn, _ = listener.accept()
                except socket.timeout:
                    continue
                with conn:
                    while not self.stop.is_set():
                        try:
                            raw, fd = self._recv_frame_message(conn)
                        except EOFError:
                            break
                        fields = FRAME_MSG.unpack(raw)
                        magic, msg_type = fields[0], fields[1]
                        if magic != FRAME_MAGIC:
                            if fd >= 0:
                                os.close(fd)
                            raise RuntimeError("bad KWin frame magic")
                        if msg_type == FRAME_IMPORT:
                            if fd < 0:
                                raise RuntimeError("KWin import missing dma-buf fd")
                            try:
                                obj_id = self.resolve_object(fd)
                                self.writer.send(FRAME_IMPORT_G2H, struct.pack("!I", obj_id) + raw)
                                print(f"[guest-wayland] KWin dma-buf -> host object id={obj_id}", flush=True)
                            finally:
                                os.close(fd)
                        elif msg_type == FRAME_FRAME:
                            if fd >= 0:
                                os.close(fd)
                            self.writer.send(FRAME_NOTIFY_G2H, raw)
                        else:
                            if fd >= 0:
                                os.close(fd)
                            raise RuntimeError(f"bad KWin frame type {msg_type}")
        finally:
            listener.close()
            self.frame_listener = None
            try:
                os.unlink(self.frame_path)
            except FileNotFoundError:
                pass

    def cleanup(self) -> None:
        if self.frame_listener is not None:
            try:
                self.frame_listener.close()
            except OSError:
                pass
        with self.sync_lock:
            syncs = list(self.sync_writers.values())
            self.sync_writers.clear()
        for fd in syncs:
            try:
                os.close(fd)
            except OSError:
                pass
        with self.object_lock:
            objects = list(self.object_fds.values())
            self.object_fds.clear()
        for fd in objects:
            try:
                os.close(fd)
            except OSError:
                pass

    def run(self) -> None:
        workers = [
            threading.Thread(target=self._worker, args=(self.local_to_host, "Mesa->host"), daemon=True),
            threading.Thread(target=self._worker, args=(self.host_to_local, "host->Mesa"), daemon=True),
            threading.Thread(target=self._worker, args=(self.frame_export_loop, "KWin-frame"), daemon=True),
        ]
        for t in workers:
            t.start()
        while not self.stop.is_set() and all(t.is_alive() for t in workers):
            time.sleep(0.05)
        self.stop.set()
        for s in (self.local, self.tcp):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        for t in workers:
            t.join(timeout=1)
        self.cleanup()
        if self.error:
            raise self.error


def connect_host(host: str, port: int) -> socket.socket:
    while True:
        try:
            return socket.create_connection((host, port), timeout=5)
        except OSError as exc:
            print(f"[guest-wayland] host not ready ({exc}); retrying", flush=True)
            time.sleep(1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="10.0.2.2")
    ap.add_argument("--port", type=int, default=5002)
    ap.add_argument("--unix", default="/tmp/.venus_test")
    ap.add_argument("--device", default="/dev/umshm")
    ap.add_argument("--frame-unix", default="/tmp/vessel-frame-export.sock")
    args = ap.parse_args()
    if platform.machine() not in ("aarch64", "arm64"):
        raise SystemExit("guest_relay_wayland currently targets Debian arm64")
    if not os.path.exists(args.device):
        raise SystemExit(f"{args.device} missing")
    try:
        os.unlink(args.unix)
    except FileNotFoundError:
        pass
    ls = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    ls.bind(args.unix)
    ls.listen(4)
    print(f"[guest-wayland] Venus relay listening {args.unix}", flush=True)
    try:
        while True:
            local, _ = ls.accept()
            tcp = connect_host(args.host, args.port)
            tcp.settimeout(None)
            try:
                Relay(tcp, local, args.device, args.frame_unix).run()
            except Exception as exc:
                print(f"[guest-wayland] session ended: {exc}", flush=True)
            finally:
                local.close()
                tcp.close()
    finally:
        ls.close()
        try:
            os.unlink(args.unix)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
