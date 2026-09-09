#!/usr/bin/env python3
import argparse
import array
import mmap
import os
import socket
import struct
import threading
import time

HDR = struct.Struct("!BI")
DATA_H2G = 1
DATA_G2H = 2
FD_H2G = 3
SHMEM_H2G = 4
SHMEM_G2H = 5
PAGE = 4096


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
    def __init__(self, tcp, local):
        self.tcp = tcp
        self.local = local
        self.writer = FramedWriter(tcp)
        self.shmem = {}
        self.stop = threading.Event()

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
                    f"POC does not yet support guest->host SCM_RIGHTS ({len(fds)} fds)"
                )
            if data:
                self.writer.send(DATA_G2H, data)

    def host_to_local(self):
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t == DATA_H2G:
                self.local.sendall(payload)
            elif t == FD_H2G:
                obj_id, size, data_len = struct.unpack("!IQI", payload[:16])
                data = payload[16:16+data_len]
                image = payload[16+data_len:]
                if len(image) != size:
                    raise RuntimeError(
                        f"bad initial shmem image: got {len(image)}, expected {size}"
                    )
                if not data:
                    raise RuntimeError("FD_H2G arrived without the required carrier byte")

                if hasattr(os, "memfd_create"):
                    fd = os.memfd_create(f"vkr-shmem-uml-{obj_id}", 0)
                else:
                    path = f"/tmp/vkr-shmem-uml-{obj_id}.bin"
                    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o600)
                os.ftruncate(fd, size)
                mm = mmap.mmap(fd, size, mmap.MAP_SHARED,
                               mmap.PROT_READ | mmap.PROT_WRITE)
                mm[:] = image
                self.shmem[obj_id] = {"fd": fd, "mm": mm, "size": size}

                anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                        array.array("i", [fd]).tobytes())]
                sent = self.local.sendmsg([data], anc)
                if sent != len(data):
                    raise RuntimeError(f"short SCM_RIGHTS sendmsg: {sent}/{len(data)}")
                print(
                    f"[guest] created local SCM_RIGHTS id={obj_id} fd={fd} "
                    f"size={size} data_len={len(data)}",
                    flush=True,
                )

                threading.Thread(target=self.sync_guest_to_host,
                                 args=(obj_id,), daemon=True).start()
            elif t == SHMEM_H2G:
                obj_id, off, n = struct.unpack("!IQI", payload[:16])
                obj = self.shmem.get(obj_id)
                if obj is None:
                    continue
                obj["mm"][off:off+n] = payload[16:16+n]
            else:
                raise RuntimeError(f"unexpected host frame type {t}")

    def sync_guest_to_host(self, obj_id):
        obj = self.shmem[obj_id]
        mm = obj["mm"]
        size = obj["size"]
        prev = bytearray(mm[:])
        while not self.stop.is_set() and obj_id in self.shmem:
            cur = mm[:]
            for off in range(0, size, PAGE):
                end = min(off + PAGE, size)
                if cur[off:end] != prev[off:end]:
                    payload = struct.pack("!IQI", obj_id, off, end - off) + cur[off:end]
                    self.writer.send(SHMEM_G2H, payload)
                    prev[off:end] = cur[off:end]
            time.sleep(0.001)

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
    args = ap.parse_args()

    try:
        os.unlink(args.unix)
    except FileNotFoundError:
        pass

    tcp = socket.create_connection((args.host, args.port))
    print(f"[guest] connected to host {args.host}:{args.port}", flush=True)

    ls = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    ls.bind(args.unix)
    ls.listen(1)
    print(f"[guest] waiting for Mesa on {args.unix}", flush=True)
    local, _ = ls.accept()
    print("[guest] Mesa connected", flush=True)

    try:
        Relay(tcp, local).run()
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
