#!/usr/bin/env python3
import base64
import json
import os
import selectors
import socket
import subprocess
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "10.0.2.2"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 0
TOKEN = sys.argv[3] if len(sys.argv) > 3 else ""
PIDFILE = "/run/vessel-control-agent.pid"


def send_line(sock, obj):
    sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8"))


def enable_keepalive(sock):
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    for name, value in (("TCP_KEEPIDLE", 5), ("TCP_KEEPINTVL", 3), ("TCP_KEEPCNT", 3)):
        option = getattr(socket, name, None)
        if option is not None:
            try:
                sock.setsockopt(socket.IPPROTO_TCP, option, value)
            except OSError:
                pass


def run_command(sock, req):
    rid = int(req.get("id", -1))
    timeout = max(1, min(3600, int(req.get("timeout", 45))))
    try:
        command = base64.b64decode(req.get("command", ""), validate=True)
    except Exception:
        send_line(sock, {"id": rid, "type": "done", "rc": 126})
        return

    # Tell Android the command has crossed the point where retrying it could
    # duplicate a mutating operation. The host may safely retry only before this.
    send_line(sock, {"id": rid, "type": "accepted"})

    proc = subprocess.Popen(
        ["/bin/bash"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        cwd="/root",
        env=dict(os.environ, TERM="dumb", LC_ALL="C.UTF-8", LANG="C.UTF-8"),
        bufsize=0,
        start_new_session=True,
    )
    selector = selectors.DefaultSelector()
    try:
        proc.stdin.write(command)
        proc.stdin.close()
        selector.register(proc.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + timeout
        eof = False
        while not eof:
            if time.monotonic() >= deadline:
                try:
                    os.killpg(proc.pid, 9)
                except Exception:
                    proc.kill()
                proc.wait(timeout=5)
                send_line(sock, {"id": rid, "type": "chunk", "data": base64.b64encode(b"\n[Vessel] command timed out\n").decode("ascii")})
                send_line(sock, {"id": rid, "type": "done", "rc": 124})
                return
            events = selector.select(0.10)
            if not events and proc.poll() is not None:
                data = proc.stdout.read()
                if data:
                    send_line(sock, {"id": rid, "type": "chunk", "data": base64.b64encode(data).decode("ascii")})
                break
            for key, _ in events:
                data = os.read(key.fd, 32768)
                if data:
                    send_line(sock, {"id": rid, "type": "chunk", "data": base64.b64encode(data).decode("ascii")})
                else:
                    eof = True
                    break
        rc = proc.wait(timeout=5)
        send_line(sock, {"id": rid, "type": "done", "rc": int(rc)})
    finally:
        try:
            selector.close()
        except Exception:
            pass
        try:
            proc.stdout.close()
        except Exception:
            pass
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, 9)
            except Exception:
                proc.kill()


def session():
    sock = socket.create_connection((HOST, PORT), timeout=5)
    enable_keepalive(sock)
    file = sock.makefile("rb")
    try:
        send_line(sock, {"hello": TOKEN})
        hello = file.readline()
        if not hello or json.loads(hello.decode("utf-8")).get("hello") != "ok":
            raise RuntimeError("Vessel control authentication failed")
        sock.settimeout(None)
        while True:
            raw = file.readline()
            if not raw:
                raise ConnectionError("host closed Vessel control channel")
            req = None
            try:
                req = json.loads(raw.decode("utf-8"))
                run_command(sock, req)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                raise
            except Exception as exc:
                rid = req.get("id", -1) if isinstance(req, dict) else -1
                try:
                    send_line(sock, {"id": rid, "type": "chunk", "data": base64.b64encode((str(exc) + "\n").encode()).decode("ascii")})
                    send_line(sock, {"id": rid, "type": "done", "rc": 125})
                except Exception:
                    raise
    finally:
        try:
            file.close()
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass


def main():
    if PORT <= 0 or not TOKEN:
        return 2
    try:
        os.makedirs(os.path.dirname(PIDFILE), exist_ok=True)
        with open(PIDFILE, "w", encoding="ascii") as f:
            f.write(str(os.getpid()))
    except OSError:
        pass

    delay = 0.15
    while True:
        try:
            session()
            delay = 0.15
        except Exception:
            time.sleep(delay)
            delay = min(1.5, delay * 1.5)


if __name__ == "__main__":
    raise SystemExit(main())
