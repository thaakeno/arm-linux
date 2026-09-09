#!/usr/bin/env python3
import argparse
import array
import os
import select
import socket
import stat
import struct
import threading
import time

HDR = struct.Struct("!BI")
DATA_H2G = 1
DATA_G2H = 2
FD_DIRECT_H2G = 6
FD_SIGNAL_H2G = 7
SIGNAL_H2G = 8
CTRL_MSG = struct.Struct("=IIQ")
CTRL_ACK = struct.Struct("=Ii")
CTRL_REGISTER = 0
CTRL_UNREGISTER = 1
UML_PAGE_SIZE = 16384


def recvn(sock, n):
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("peer disconnected")
        out += chunk
    return bytes(out)


def recv_frame(sock):
    t, n = HDR.unpack(recvn(sock, HDR.size))
    return t, recvn(sock, n)


class FramedWriter:
    def __init__(self, sock):
        self.sock = sock
        self.lock = threading.Lock()

    def send(self, t, payload=b""):
        with self.lock:
            self.sock.sendall(HDR.pack(t, len(payload)) + payload)


class Control:
    def __init__(self, sock):
        self.sock = sock
        self.lock = threading.Lock()

    def _wait_ack(self, obj_id):
        ack_id, status = CTRL_ACK.unpack(recvn(self.sock, CTRL_ACK.size))
        if ack_id != obj_id:
            raise RuntimeError(f"UML ack id mismatch {ack_id} != {obj_id}")
        return status

    def register(self, obj_id, fd, size):
        meta = CTRL_MSG.pack(obj_id, CTRL_REGISTER, size)
        anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                array.array("i", [fd]).tobytes())]
        with self.lock:
            sent = self.sock.sendmsg([meta], anc)
            if sent != len(meta):
                raise RuntimeError(f"short UML control sendmsg {sent}/{len(meta)}")
            status = self._wait_ack(obj_id)
        if status != 0:
            raise RuntimeError(f"UML rejected shmem id={obj_id}: {status}")

    def unregister(self, obj_id):
        meta = CTRL_MSG.pack(obj_id, CTRL_UNREGISTER, 0)
        with self.lock:
            self.sock.sendall(meta)
            status = self._wait_ack(obj_id)
        if status not in (0, -2):  # -ENOENT is already clean
            raise RuntimeError(f"UML unregister id={obj_id} failed: {status}")


class Relay:
    def __init__(self, venus_path, tcp, control, alloc_id):
        self.venus_path = venus_path
        self.tcp = tcp
        self.control = control
        self.alloc_id = alloc_id
        self.writer = FramedWriter(tcp)
        self.vsock = None
        self.stop = threading.Event()
        self.error = None
        self.registered_ids = []
        self.sync_fds = set()
        self.sync_lock = threading.Lock()

    def connect_venus(self):
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(self.venus_path)
        self.vsock = s
        print(f"[host-direct] connected to Venus {self.venus_path}", flush=True)

    def _worker(self, fn, label):
        try:
            fn()
        except (EOFError, BrokenPipeError, ConnectionResetError, OSError) as e:
            if not self.stop.is_set():
                print(f"[host-direct] {label} closed: {e}", flush=True)
        except Exception as e:
            self.error = e
            print(f"[host-direct] {label} error: {e}", flush=True)
        finally:
            self.stop.set()
            for s in (self.tcp, self.vsock):
                if s is None:
                    continue
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def register_fd_with_uml(self, obj_id, fd, size):
        self.control.register(obj_id, fd, size)
        self.registered_ids.append(obj_id)
        print(f"[host-direct] UML registered id={obj_id} fd={fd} size={size}", flush=True)

    @staticmethod
    def pad_memfd(fd, size):
        rounded = (size + UML_PAGE_SIZE - 1) & ~(UML_PAGE_SIZE - 1)
        if rounded != size:
            os.ftruncate(fd, rounded)
            print(
                f"[host-direct] padded Venus memfd backing {size}->{rounded} "
                f"for 16K UML pages",
                flush=True,
            )
        return rounded

    def _watch_sync_fd(self, sync_id, fd):
        try:
            poller = select.poll()
            poller.register(fd, select.POLLIN | select.POLLERR | select.POLLHUP)
            while not self.stop.is_set():
                events = poller.poll(250)
                if not events:
                    continue
                # A vtest sync-wait fd is a one-shot readiness notification.
                # Preserve that semantic by signalling a guest-local pipe.
                self.writer.send(SIGNAL_H2G, struct.pack("!I", sync_id))
                print(f"[host-direct] sync fd signalled id={sync_id}", flush=True)
                return
        except (BrokenPipeError, ConnectionResetError, OSError):
            return
        finally:
            with self.sync_lock:
                self.sync_fds.discard(fd)
            try:
                os.close(fd)
            except OSError:
                pass

    def _forward_sync_fd(self, fd, data):
        sync_id = self.alloc_id()
        with self.sync_lock:
            self.sync_fds.add(fd)
        payload = struct.pack("!II", sync_id, len(data)) + data
        self.writer.send(FD_SIGNAL_H2G, payload)
        print(
            f"[host-direct] proxied pollable sync fd id={sync_id} carrier={len(data)}",
            flush=True,
        )
        threading.Thread(
            target=self._watch_sync_fd,
            args=(sync_id, fd),
            name=f"venus-sync-{sync_id}",
            daemon=True,
        ).start()

    def venus_to_guest(self):
        ancbuf = socket.CMSG_SPACE(16 * struct.calcsize("i"))
        while not self.stop.is_set():
            data, anc, flags, _ = self.vsock.recvmsg(1, ancbuf)
            if not data and not anc:
                raise EOFError("Venus socket closed")

            fds = []
            for level, ctype, cdata in anc:
                if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                    a = array.array("i")
                    usable = len(cdata) - (len(cdata) % a.itemsize)
                    a.frombytes(cdata[:usable])
                    fds.extend(a.tolist())

            if not fds:
                if data:
                    self.writer.send(DATA_H2G, data)
                continue

            if len(fds) != 1:
                for fd in fds:
                    os.close(fd)
                raise RuntimeError(f"expected one external Venus FD, got {len(fds)}")
            if not data:
                os.close(fds[0])
                raise RuntimeError("SCM_RIGHTS arrived without carrier byte")

            fd = fds[0]
            keep_fd = False
            try:
                st = os.fstat(fd)
                size = st.st_size

                # Venus vtest sends two fundamentally different FD classes:
                # mapped blob/memfd storage (non-zero size), and one-shot
                # pollable sync-wait FDs (normally anon-inode, size zero).
                # The former must share memory with UML; the latter only need
                # readiness semantics, so proxy them with a guest-local pipe.
                if size == 0:
                    self._forward_sync_fd(fd, data)
                    keep_fd = True  # watcher owns/ closes the host FD
                    continue

                if size < 0 or not stat.S_ISREG(st.st_mode):
                    raise RuntimeError(
                        f"unsupported Venus fd mode={oct(st.st_mode)} size={size}"
                    )

                self.pad_memfd(fd, size)
                obj_id = self.alloc_id()
                self.register_fd_with_uml(obj_id, fd, size)
                payload = struct.pack("!IQI", obj_id, size, len(data)) + data
                self.writer.send(FD_DIRECT_H2G, payload)
                print(
                    f"[host-direct] forwarded SCM_RIGHTS id={obj_id} "
                    f"size={size} carrier={len(data)}",
                    flush=True,
                )
            finally:
                if not keep_fd:
                    os.close(fd)

    def guest_to_venus(self):
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t != DATA_G2H:
                raise RuntimeError(f"unexpected guest frame type {t}")
            self.vsock.sendall(payload)

    def cleanup_maps(self):
        for obj_id in reversed(self.registered_ids):
            try:
                self.control.unregister(obj_id)
                print(f"[host-direct] unregister requested id={obj_id}", flush=True)
            except Exception as e:
                print(f"[host-direct] unregister id={obj_id} warning: {e}", flush=True)
        self.registered_ids.clear()

        with self.sync_lock:
            fds = list(self.sync_fds)
            self.sync_fds.clear()
        for fd in fds:
            try:
                os.close(fd)
            except OSError:
                pass

    def run(self):
        self.connect_venus()
        a = threading.Thread(target=self._worker, args=(self.venus_to_guest, "Venus->guest"), daemon=True)
        b = threading.Thread(target=self._worker, args=(self.guest_to_venus, "guest->Venus"), daemon=True)
        a.start()
        b.start()
        while a.is_alive() and b.is_alive():
            time.sleep(0.05)
        self.stop.set()
        a.join(timeout=1)
        b.join(timeout=1)
        self.cleanup_maps()
        if self.error:
            raise self.error


def unix_listener(path):
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(path)
    s.listen(1)
    return s


def main():
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

    print(f"[host-direct] waiting for patched UML on {args.uml_control}", flush=True)
    ctrl, _ = ctrl_ls.accept()
    control = Control(ctrl)
    print("[host-direct] patched UML control connected", flush=True)

    next_id = 1
    id_lock = threading.Lock()

    def alloc_id():
        nonlocal next_id
        with id_lock:
            obj_id = next_id
            next_id += 1
            if next_id > 0xffffffff:
                next_id = 1
            return obj_id

    try:
        while True:
            print(f"[host-direct] waiting for Debian relay on {args.listen}:{args.port}", flush=True)
            tcp, addr = tcp_ls.accept()
            print(f"[host-direct] Debian relay connected from {addr}", flush=True)
            try:
                Relay(args.venus_unix, tcp, control, alloc_id).run()
            except Exception as e:
                print(f"[host-direct] session ended with error: {e}", flush=True)
            finally:
                try:
                    tcp.close()
                except OSError:
                    pass
            print("[host-direct] session finished; ready for next Debian client", flush=True)
    except (KeyboardInterrupt, EOFError, BrokenPipeError, ConnectionResetError):
        print("[host-direct] stopping", flush=True)
    finally:
        ctrl.close()
        tcp_ls.close()
        ctrl_ls.close()
        try:
            os.unlink(args.uml_control)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
