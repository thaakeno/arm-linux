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
RING_CHUNK = 64
BULK_CHUNK = 4096
RING_MAX = 256 * 1024


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
    def __init__(self, unix_path, tcp):
        self.unix_path = unix_path
        self.tcp = tcp
        self.writer = FramedWriter(tcp)
        self.vsock = None
        self.shmem = {}
        self.next_id = 1
        self.stop = threading.Event()

    def connect_venus(self):
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(self.unix_path)
        self.vsock = s
        print(f"[host] connected to {self.unix_path}", flush=True)

    def venus_to_guest(self):
        # One-byte recvmsg is deliberate. SCM_RIGHTS on SOCK_STREAM is attached
        # to a byte position. Reading large chunks can move/drop the ancillary
        # data when the receiver consumes preceding protocol bytes with read().
        ancbuf = socket.CMSG_SPACE(16 * struct.calcsize("i"))
        while not self.stop.is_set():
            data, anc, flags, _ = self.vsock.recvmsg(1, ancbuf)
            if not data and not anc:
                raise EOFError("venus socket closed")

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
                raise RuntimeError(f"POC expected one external FD, got {len(fds)}")

            fd = fds[0]
            st = os.fstat(fd)
            if st.st_size <= 0:
                raise RuntimeError(f"external FD has non-mappable size {st.st_size}")

            obj_id = self.next_id
            self.next_id += 1
            mm = mmap.mmap(fd, st.st_size, mmap.MAP_SHARED,
                           mmap.PROT_READ | mmap.PROT_WRITE)
            image = mm[:]
            chunk_size = RING_CHUNK if st.st_size <= RING_MAX else BULK_CHUNK
            self.shmem[obj_id] = {
                "fd": fd,
                "mm": mm,
                "size": st.st_size,
                "shadow": bytearray(image),
                "lock": threading.Lock(),
                "chunk": chunk_size,
            }

            payload = struct.pack("!IQI", obj_id, st.st_size, len(data)) + data + image
            self.writer.send(FD_H2G, payload)
            print(
                f"[host] captured SCM_RIGHTS id={obj_id} fd={fd} "
                f"size={st.st_size} data_len={len(data)} chunk={chunk_size}",
                flush=True,
            )

            threading.Thread(target=self.sync_host_to_guest,
                             args=(obj_id,), daemon=True).start()

    def sync_host_to_guest(self, obj_id):
        obj = self.shmem[obj_id]
        mm = obj["mm"]
        size = obj["size"]
        chunk_size = obj["chunk"]
        shadow = obj["shadow"]
        lock = obj["lock"]
        sleep_s = 0.00025 if chunk_size == RING_CHUNK else 0.001

        while not self.stop.is_set() and obj_id in self.shmem:
            with lock:
                cur = mm[:]
                changed = []
                for off in range(0, size, chunk_size):
                    end = min(off + chunk_size, size)
                    if cur[off:end] != shadow[off:end]:
                        chunk = cur[off:end]
                        shadow[off:end] = chunk
                        changed.append((off, chunk))

            for off, chunk in changed:
                payload = struct.pack("!IQI", obj_id, off, len(chunk)) + chunk
                self.writer.send(SHMEM_H2G, payload)

            time.sleep(sleep_s)

    def guest_to_venus(self):
        while not self.stop.is_set():
            t, payload = recv_frame(self.tcp)
            if t == DATA_G2H:
                self.vsock.sendall(payload)
            elif t == SHMEM_G2H:
                obj_id, off, n = struct.unpack("!IQI", payload[:16])
                obj = self.shmem.get(obj_id)
                if obj is None:
                    continue
                chunk = payload[16:16+n]
                if len(chunk) != n or off + n > obj["size"]:
                    raise RuntimeError(
                        f"bad guest shmem update id={obj_id} off={off} len={n}"
                    )
                # Update the mapping and our shadow atomically with respect to the
                # polling thread. Otherwise a remote write is detected as a local
                # write and echoed back, which can overwrite newer ring state.
                with obj["lock"]:
                    obj["mm"][off:off+n] = chunk
                    obj["shadow"][off:off+n] = chunk
            else:
                raise RuntimeError(f"unexpected guest frame type {t}")

    def run(self):
        self.connect_venus()
        a = threading.Thread(target=self.venus_to_guest, daemon=True)
        b = threading.Thread(target=self.guest_to_venus, daemon=True)
        a.start(); b.start()
        while a.is_alive() and b.is_alive():
            time.sleep(0.1)
        self.stop.set()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unix", required=True, help="virglrenderer Venus Unix socket")
    ap.add_argument("--listen", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5002)
    args = ap.parse_args()

    ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ls.bind((args.listen, args.port))
    ls.listen(1)
    print(f"[host] waiting on {args.listen}:{args.port}", flush=True)
    conn, addr = ls.accept()
    print(f"[host] guest connected from {addr}", flush=True)
    try:
        Relay(args.unix, conn).run()
    finally:
        conn.close()
        ls.close()


if __name__ == "__main__":
    main()
