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
        self.error = None

    def _worker(self, fn, label):
        try:
            fn()
        except (EOFError, BrokenPipeError, ConnectionResetError, OSError) as e:
            if not self.stop.is_set():
                print(f"[guest-direct] {label} closed: {e}", flush=True)
        except Exception as e:
            self.error = e
            print(f"[guest-direct] {label} error: {e}", flush=True)
        finally:
            self.stop.set()
            for s in (self.local, self.tcp):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

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
                for fd in fds:
                    os.close(fd)
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
            try:
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
            finally:
                # Mesa owns the SCM_RIGHTS reference now. Closing our copy lets
                # /dev/umshm .release track the real client lifetime.
                os.close(fd)

    def run(self):
        a = threading.Thread(target=self._worker, args=(self.local_to_host, "Mesa->host"), daemon=True)
        b = threading.Thread(target=self._worker, args=(self.host_to_local, "host->Mesa"), daemon=True)
        a.start()
        b.start()
        while a.is_alive() and b.is_alive():
            time.sleep(0.05)
        self.stop.set()
        a.join(timeout=1)
        b.join(timeout=1)
        if self.error:
            raise self.error


def connect_host(host, port):
    while True:
        try:
            return socket.create_connection((host, port), timeout=5)
        except OSError as e:
            print(f"[guest-direct] host not ready ({e}); retrying...", flush=True)
            time.sleep(1)


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

    ls = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    ls.bind(args.unix)
    ls.listen(4)
    print(f"[guest-direct] reusable relay listening for Mesa on {args.unix}", flush=True)

    try:
        while True:
            local, _ = ls.accept()
            print("[guest-direct] Mesa connected", flush=True)
            tcp = connect_host(args.host, args.port)
            tcp.settimeout(None)
            print(f"[guest-direct] connected to host {args.host}:{args.port}", flush=True)
            try:
                Relay(tcp, local, args.device).run()
            except Exception as e:
                print(f"[guest-direct] session ended with error: {e}", flush=True)
            finally:
                for s in (local, tcp):
                    try:
                        s.close()
                    except OSError:
                        pass
            print("[guest-direct] session finished; waiting for next Mesa client", flush=True)
    except KeyboardInterrupt:
        print("[guest-direct] stopping", flush=True)
    finally:
        ls.close()
        try:
            os.unlink(args.unix)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
