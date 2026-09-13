#!/usr/bin/env python3
"""Host half of Vessel's Venus + Wayland dma-buf bridge.

Venus SCM_RIGHTS objects are registered in UML once and retained here. Returned
object references from Debian are converted back to the original host fd. KWin
frame exports are forwarded by SCM_RIGHTS to the Android APK's native Vulkan
presenter without copying pixel payloads through Python or TCP.

Important: AF_UNIX SOCK_STREAM ancillary data is a barrier, not a record.  A
recvmsg() that receives SCM_RIGHTS may also return ordinary bytes that were sent
*before* the descriptor carrier byte.  Mesa Venus later calls read() for those
ordinary protocol bytes and recvmsg() for exactly the one-byte FD carrier.  We
therefore preserve that boundary explicitly across the TCP relay: prefix bytes
are forwarded as DATA and only the final carrier byte is forwarded with the FD.
"""
from __future__ import annotations

import argparse
import array
import os
import select
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
CTRL_MSG = struct.Struct("=IIQ")
CTRL_ACK = struct.Struct("=Ii")
CTRL_REGISTER = 0
CTRL_UNREGISTER = 1
UML_PAGE_SIZE = 16384
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
    return t, recvn(sock, n)


class FramedWriter:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.lock = threading.Lock()

    def send(self, t: int, payload: bytes = b"") -> None:
        with self.lock:
            self.sock.sendall(HDR.pack(t, len(payload)) + payload)


class Control:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.lock = threading.Lock()

    def _wait_ack(self, obj_id: int) -> int:
        ack_id, status = CTRL_ACK.unpack(recvn(self.sock, CTRL_ACK.size))
        if ack_id != obj_id:
            raise RuntimeError(f"UML ack id mismatch {ack_id} != {obj_id}")
        return status

    def register(self, obj_id: int, fd: int, size: int) -> None:
        meta = CTRL_MSG.pack(obj_id, CTRL_REGISTER, size)
        anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]).tobytes())]
        with self.lock:
            sent = self.sock.sendmsg([meta], anc)
            if sent != len(meta):
                raise RuntimeError(f"short UML control sendmsg {sent}/{len(meta)}")
            status = self._wait_ack(obj_id)
        if status != 0:
            raise RuntimeError(f"UML rejected shmem id={obj_id}: {status}")

    def unregister(self, obj_id: int) -> None:
        meta = CTRL_MSG.pack(obj_id, CTRL_UNREGISTER, 0)
        with self.lock:
            self.sock.sendall(meta)
            status = self._wait_ack(obj_id)
        if status not in (0, -2):
            raise RuntimeError(f"UML unregister id={obj_id} failed: {status}")


class AndroidPresenter:
    def __init__(self):
        self.sock: socket.socket | None = None
        self.lock = threading.Lock()

    def _connect_locked(self) -> socket.socket:
        if self.sock is not None:
            return self.sock
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(ANDROID_FRAME_SOCKET)
        self.sock = s
        print("[host-wayland] connected to Vessel Android Vulkan presenter", flush=True)
        return s

    def _drop_locked(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def send_import(self, message: bytes, fd: int) -> None:
        with self.lock:
            for attempt in range(2):
                try:
                    s = self._connect_locked()
                    anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]).tobytes())]
                    sent = s.sendmsg([message], anc)
                    if sent != len(message):
                        raise RuntimeError(f"short Android frame import send {sent}/{len(message)}")
                    return
                except (OSError, RuntimeError):
                    self._drop_locked()
                    if attempt:
                        raise
                    time.sleep(0.05)

    def send_frame(self, message: bytes) -> None:
        with self.lock:
            for attempt in range(2):
                try:
                    self._connect_locked().sendall(message)
                    return
                except OSError:
                    self._drop_locked()
                    if attempt:
                        raise
                    time.sleep(0.05)

    def close(self) -> None:
        with self.lock:
            self._drop_locked()


class Relay:
    def __init__(self, venus_path: str, tcp: socket.socket, control: Control, alloc_id, presenter: AndroidPresenter):
        self.venus_path = venus_path
        self.tcp = tcp
        self.control = control
        self.alloc_id = alloc_id
        self.presenter = presenter
        self.writer = FramedWriter(tcp)
        self.vsock: socket.socket | None = None
        self.stop = threading.Event()
        self.error: Exception | None = None
        self.object_fds: dict[int, int] = {}
        self.registered_ids: list[int] = []
        self.sync_fds: set[int] = set()
        self.sync_lock = threading.Lock()

    def connect_venus(self) -> None:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(self.venus_path)
        self.vsock = s
        print(f"[host-wayland] connected Venus {self.venus_path}", flush=True)

    def _worker(self, fn, label: str) -> None:
        try:
            fn()
        except (EOFError, BrokenPipeError, ConnectionResetError, OSError) as exc:
            if not self.stop.is_set():
                print(f"[host-wayland] {label} closed: {exc}", flush=True)
        except Exception as exc:
            self.error = exc
            print(f"[host-wayland] {label} error: {exc}", flush=True)
        finally:
            self.stop.set()
            for s in (self.tcp, self.vsock):
                if s is None:
                    continue
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    @staticmethod
    def pad_memfd(fd: int, size: int) -> int:
        rounded = (size + UML_PAGE_SIZE - 1) & ~(UML_PAGE_SIZE - 1)
        try:
            target = os.readlink(f"/proc/self/fd/{fd}")
        except OSError:
            target = ""
        if "dmabuf:" in target:
            return size
        if rounded != size:
            os.ftruncate(fd, rounded)
        return rounded

    def register_object(self, obj_id: int, fd: int, size: int) -> None:
        self.control.register(obj_id, fd, size)
        self.registered_ids.append(obj_id)
        old = self.object_fds.pop(obj_id, None)
        if old is not None:
            os.close(old)
        self.object_fds[obj_id] = os.dup(fd)
        print(f"[host-wayland] retained Venus object id={obj_id} size={size}", flush=True)

    def _watch_sync_fd(self, sync_id: int, fd: int) -> None:
        try:
            poller = select.poll()
            poller.register(fd, select.POLLIN | select.POLLERR | select.POLLHUP)
            while not self.stop.is_set():
                if poller.poll(250):
                    self.writer.send(SIGNAL_H2G, struct.pack("!I", sync_id))
                    return
        except OSError:
            return
        finally:
            with self.sync_lock:
                self.sync_fds.discard(fd)
            try:
                os.close(fd)
            except OSError:
                pass

    def _forward_sync_fd(self, fd: int, carrier: bytes) -> None:
        sync_id = self.alloc_id()
        with self.sync_lock:
            self.sync_fds.add(fd)
        self.writer.send(FD_SIGNAL_H2G, struct.pack("!II", sync_id, len(carrier)) + carrier)
        threading.Thread(target=self._watch_sync_fd, args=(sync_id, fd), daemon=True).start()

    @staticmethod
    def _split_fd_carrier(data: bytes) -> tuple[bytes, bytes]:
        """Preserve Linux AF_UNIX SCM_RIGHTS stream-barrier semantics.

        recvmsg() may return ordinary stream bytes that precede the byte carrying
        ancillary data.  For Mesa vtest the FD carrier itself is exactly one
        dummy byte.  Re-attaching SCM_RIGHTS to the first byte of the whole
        chunk makes Mesa's earlier read() consume and discard the control
        message.  Keep the prefix as plain data and the final byte as carrier.
        """
        if not data:
            raise RuntimeError("SCM_RIGHTS message missing carrier byte")
        return data[:-1], data[-1:]

    def venus_to_guest(self) -> None:
        assert self.vsock is not None
        ancbuf = socket.CMSG_SPACE(16 * struct.calcsize("i"))
        while not self.stop.is_set():
            data, anc, flags, _ = self.vsock.recvmsg(65536, ancbuf)
            if flags & getattr(socket, "MSG_CTRUNC", 0):
                raise RuntimeError("Venus SCM_RIGHTS control message truncated")
            if not data and not anc:
                raise EOFError("Venus socket closed")
            fds: list[int] = []
            for level, ctype, cdata in anc:
                if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                    a = array.array("i")
                    usable = len(cdata) - len(cdata) % a.itemsize
                    a.frombytes(cdata[:usable])
                    fds.extend(a.tolist())
            if not fds:
                if data:
                    self.writer.send(DATA_H2G, data)
                continue
            if len(fds) != 1 or not data:
                for fd in fds:
                    os.close(fd)
                raise RuntimeError(f"unexpected Venus SCM_RIGHTS count={len(fds)} data={len(data)}")

            prefix, carrier = self._split_fd_carrier(data)
            if prefix:
                self.writer.send(DATA_H2G, prefix)

            fd = fds[0]
            keep_fd = False
            try:
                st = os.fstat(fd)
                size = st.st_size
                if size == 0:
                    self._forward_sync_fd(fd, carrier)
                    keep_fd = True
                    continue
                backing = self.pad_memfd(fd, size)
                obj_id = self.alloc_id()
                self.register_object(obj_id, fd, backing)
                self.writer.send(FD_DIRECT_H2G, struct.pack("!IQI", obj_id, size, len(carrier)) + carrier)
                print(
                    f"[host-wayland] forwarded Venus object id={obj_id} with exact 1-byte SCM_RIGHTS carrier",
                    flush=True,
                )
            finally:
                if not keep_fd:
                    os.close(fd)

    def _send_fd_to_venus(self, obj_id: int, data: bytes) -> None:
        assert self.vsock is not None
        fd = self.object_fds.get(obj_id)
        if fd is None:
            raise RuntimeError(f"guest referenced unknown host Venus object id={obj_id}")
        if len(data) != 1:
            raise RuntimeError(f"Venus fd return requires one-byte carrier, got {len(data)}")
        anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]).tobytes())]
        sent = self.vsock.sendmsg([data], anc)
        if sent != len(data):
            raise RuntimeError(f"short fd return to Venus {sent}/{len(data)}")

    def guest_to_venus(self) -> None:
        assert self.vsock is not None
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t == DATA_G2H:
                self.vsock.sendall(payload)
            elif t == FD_REF_G2H:
                if len(payload) < 8:
                    raise RuntimeError("short FD_REF_G2H")
                obj_id, data_len = struct.unpack("!II", payload[:8])
                data = payload[8:8 + data_len]
                if len(data) != data_len or len(data) != 1:
                    raise RuntimeError("bad returned fd carrier")
                self._send_fd_to_venus(obj_id, data)
            elif t == FRAME_IMPORT_G2H:
                if len(payload) < 5:
                    raise RuntimeError("short FRAME_IMPORT_G2H")
                obj_id = struct.unpack("!I", payload[:4])[0]
                message = payload[4:]
                fd = self.object_fds.get(obj_id)
                if fd is None:
                    raise RuntimeError(f"KWin referenced unknown host object id={obj_id}")
                self.presenter.send_import(message, fd)
                print(f"[host-wayland] Android imported KWin object id={obj_id}", flush=True)
            elif t == FRAME_NOTIFY_G2H:
                self.presenter.send_frame(payload)
            else:
                raise RuntimeError(f"unexpected guest frame type {t}")

    def cleanup(self) -> None:
        for obj_id in reversed(self.registered_ids):
            try:
                self.control.unregister(obj_id)
            except Exception as exc:
                print(f"[host-wayland] unregister {obj_id}: {exc}", flush=True)
        self.registered_ids.clear()
        for fd in self.object_fds.values():
            try:
                os.close(fd)
            except OSError:
                pass
        self.object_fds.clear()
        with self.sync_lock:
            fds = list(self.sync_fds)
            self.sync_fds.clear()
        for fd in fds:
            try:
                os.close(fd)
            except OSError:
                pass

    def run(self) -> None:
        self.connect_venus()
        a = threading.Thread(target=self._worker, args=(self.venus_to_guest, "Venus->guest"), daemon=True)
        b = threading.Thread(target=self._worker, args=(self.guest_to_venus, "guest->Venus"), daemon=True)
        a.start(); b.start()
        while a.is_alive() and b.is_alive():
            time.sleep(0.05)
        self.stop.set()
        a.join(timeout=1); b.join(timeout=1)
        self.cleanup()
        if self.error:
            raise self.error


def unix_listener(path: str) -> socket.socket:
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
    ap.add_argument("--venus-unix", required=True)
    ap.add_argument("--uml-control", required=True)
    ap.add_argument("--listen", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5002)
    args = ap.parse_args()
    ctrl_ls = unix_listener(args.uml_control)
    tcp_ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp_ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp_ls.bind((args.listen, args.port))
    tcp_ls.listen(4)
    print(f"[host-wayland] waiting UML control {args.uml_control}", flush=True)
    ctrl, _ = ctrl_ls.accept()
    control = Control(ctrl)
    presenter = AndroidPresenter()
    next_id = 1
    id_lock = threading.Lock()

    def alloc_id() -> int:
        nonlocal next_id
        with id_lock:
            obj_id = next_id
            next_id = 1 if next_id >= 0xFFFFFFFF else next_id + 1
            return obj_id

    try:
        while True:
            print(f"[host-wayland] waiting Debian relay {args.listen}:{args.port}", flush=True)
            tcp, addr = tcp_ls.accept()
            print(f"[host-wayland] Debian relay connected {addr}", flush=True)
            try:
                Relay(args.venus_unix, tcp, control, alloc_id, presenter).run()
            except Exception as exc:
                print(f"[host-wayland] session ended: {exc}", flush=True)
            finally:
                tcp.close()
    finally:
        presenter.close()
        ctrl.close(); tcp_ls.close(); ctrl_ls.close()
        try:
            os.unlink(args.uml_control)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
