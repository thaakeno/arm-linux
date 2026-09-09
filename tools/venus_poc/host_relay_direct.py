#!/usr/bin/env python3
import argparse
import array
import os
import socket
import struct
import threading
import time

HDR = struct.Struct("!BI")
DATA_H2G = 1
DATA_G2H = 2
FD_DIRECT_H2G = 6
CTRL_MSG = struct.Struct("=IIQ")
CTRL_ACK = struct.Struct("=Ii")


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


class Relay:
    def __init__(self, venus_path, tcp, ctrl):
        self.venus_path = venus_path
        self.tcp = tcp
        self.ctrl = ctrl
        self.writer = FramedWriter(tcp)
        self.vsock = None
        self.next_id = 1
        self.stop = threading.Event()
        self.ctrl_lock = threading.Lock()

    def connect_venus(self):
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(self.venus_path)
        self.vsock = s
        print(f"[host-direct] connected to Venus {self.venus_path}", flush=True)

    def register_fd_with_uml(self, obj_id, fd, size):
        meta = CTRL_MSG.pack(obj_id, 0, size)
        anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                array.array("i", [fd]).tobytes())]
        with self.ctrl_lock:
            sent = self.ctrl.sendmsg([meta], anc)
            if sent != len(meta):
                raise RuntimeError(f"short UML control sendmsg {sent}/{len(meta)}")
            ack_id, status = CTRL_ACK.unpack(recvn(self.ctrl, CTRL_ACK.size))
        if ack_id != obj_id:
            raise RuntimeError(f"UML ack id mismatch {ack_id} != {obj_id}")
        if status != 0:
            raise RuntimeError(f"UML rejected shmem id={obj_id}: {status}")
        print(f"[host-direct] UML registered id={obj_id} fd={fd} size={size}", flush=True)

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
                raise RuntimeError(f"expected one external Venus FD, got {len(fds)}")
            if not data:
                raise RuntimeError("SCM_RIGHTS arrived without carrier byte")

            fd = fds[0]
            try:
                size = os.fstat(fd).st_size
                if size <= 0:
                    raise RuntimeError(f"non-mappable Venus fd size={size}")
                obj_id = self.next_id
                self.next_id += 1
                self.register_fd_with_uml(obj_id, fd, size)
                payload = struct.pack("!IQI", obj_id, size, len(data)) + data
                self.writer.send(FD_DIRECT_H2G, payload)
                print(
                    f"[host-direct] forwarded SCM_RIGHTS id={obj_id} "
                    f"size={size} carrier={len(data)}",
                    flush=True,
                )
            finally:
                os.close(fd)

    def guest_to_venus(self):
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t != DATA_G2H:
                raise RuntimeError(f"unexpected guest frame type {t}")
            self.vsock.sendall(payload)

    def run(self):
        self.connect_venus()
        a = threading.Thread(target=self.venus_to_guest, daemon=True)
        b = threading.Thread(target=self.guest_to_venus, daemon=True)
        a.start(); b.start()
        while a.is_alive() and b.is_alive():
            time.sleep(0.1)
        self.stop.set()


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
    tcp_ls.listen(1)

    print(f"[host-direct] waiting for patched UML on {args.uml_control}", flush=True)
    ctrl, _ = ctrl_ls.accept()
    print("[host-direct] patched UML control connected", flush=True)

    print(f"[host-direct] waiting for Debian relay on {args.listen}:{args.port}", flush=True)
    tcp, addr = tcp_ls.accept()
    print(f"[host-direct] Debian relay connected from {addr}", flush=True)

    try:
        Relay(args.venus_unix, tcp, ctrl).run()
    finally:
        tcp.close()
        ctrl.close()
        tcp_ls.close()
        ctrl_ls.close()
        try:
            os.unlink(args.uml_control)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
