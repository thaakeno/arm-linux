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
    def __init__(self, tcp, local, device):
        self.tcp = tcp
        self.local = local
        self.device = device
        self.writer = FramedWriter(tcp)
        self.stop = threading.Event()
        self.bound_fds = []

    def local_to_host(self):
        ancbuf = socket.CMSG_SPACE(16 * struct.calcsize("i"))
        while not self.stop.is_set():
            data, anc, flags, _ = self.local.recvmsg(1, ancbuf)
            if not data and not anc:
                raise EOFError("Mesa client closed")

            fds = []
            for level, ctype, cdata in anc:
                if level == socket.SOL_SOCKET and ctype == socket.SCM_RIGHTS:
                    a = array.array("i")
                    usable = len(cdata) - (len(cdata) % a.itemsize)
                    a.frombytes(cdata[:usable])
                    fds.extend(a.tolist())

            if fds:
                raise RuntimeError(
                    f"direct POC does not yet support guest->host SCM_RIGHTS ({len(fds)} fds)"
                )
            if data:
                self.writer.send(DATA_G2H, data)

    def make_umshm_fd(self, obj_id):
        fd = os.open(self.device, os.O_RDWR)
        try:
            n = os.write(fd, struct.pack("=I", obj_id))
            if n != 4:
                raise RuntimeError(f"short bind write {n}/4 for id={obj_id}")
            self.bound_fds.append(fd)
            return fd
        except Exception:
            os.close(fd)
            raise

    def host_to_local(self):
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t == DATA_H2G:
                self.local.sendall(payload)
                continue

            if t != FD_DIRECT_H2G:
                raise RuntimeError(f"unexpected host frame type {t}")

            if len(payload) < 16:
                raise RuntimeError("short FD_DIRECT_H2G frame")
            obj_id, size, data_len = struct.unpack("!IQI", payload[:16])
            data = payload[16:16 + data_len]
            if len(data) != data_len or not data:
                raise RuntimeError("direct FD frame missing carrier byte")

            fd = self.make_umshm_fd(obj_id)
            anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                    array.array("i", [fd]).tobytes())]
            sent = self.local.sendmsg([data], anc)
            if sent != len(data):
                raise RuntimeError(f"short SCM_RIGHTS sendmsg {sent}/{len(data)}")

            print(
                f"[guest-direct] passed /dev/umshm fd={fd} id={obj_id} "
                f"size={size} carrier={data_len}",
                flush=True,
            )

    def run(self):
        a = threading.Thread(target=self.local_to_host, daemon=True)
        b = threading.Thread(target=self.host_to_local, daemon=True)
        a.start(); b.start()
        while a.is_alive() and b.is_alive():
            time.sleep(0.1)
        self.stop.set()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="10.0.2.2")
    ap.add_argument("--port", type=int, default=5002)
    ap.add_argument("--unix", default="/tmp/.venus_test")
    ap.add_argument("--device", default="/dev/umshm")
    args = ap.parse_args()

    if not os.path.exists(args.device):
        raise SystemExit(
            f"{args.device} does not exist; boot the patched UML kernel with umshm_sock=..."
        )

    try:
        os.unlink(args.unix)
    except FileNotFoundError:
        pass

    tcp = socket.create_connection((args.host, args.port))
    print(f"[guest-direct] connected to host {args.host}:{args.port}", flush=True)

    ls = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    ls.bind(args.unix)
    ls.listen(1)
    print(f"[guest-direct] waiting for Mesa on {args.unix}", flush=True)
    local, _ = ls.accept()
    print("[guest-direct] Mesa connected", flush=True)

    try:
        Relay(tcp, local, args.device).run()
    finally:
        local.close()
        ls.close()
        tcp.close()
        try:
            os.unlink(args.unix)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
