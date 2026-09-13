#!/usr/bin/env python3
"""Reliable reverse command channel from UML Debian to the Vessel host.

The boot PTY is used only until this process connects. Long-lived runtime
commands never depend on terminal prompts, bracketed-paste state or marker
parsing.
"""
from __future__ import annotations

import json
import socket
import subprocess
import time

HOST = "10.0.2.2"
PORT = 47640


def send_line(sock: socket.socket, obj: dict) -> None:
    sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode())


def serve(sock: socket.socket) -> None:
    f = sock.makefile("rb")
    send_line(sock, {"hello": "vessel-guest-command-v1"})
    while True:
        raw = f.readline()
        if not raw:
            raise EOFError("host disconnected")
        req = json.loads(raw.decode("utf-8"))
        req_id = req.get("id")
        command = str(req.get("command", ""))
        timeout = max(1.0, min(float(req.get("timeout", 45)), 3600.0))
        try:
            cp = subprocess.run(
                command,
                shell=True,
                executable="/bin/bash",
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout,
                env=None,
            )
            send_line(sock, {"id": req_id, "rc": cp.returncode, "output": cp.stdout[-2_000_000:]})
        except subprocess.TimeoutExpired as exc:
            out = exc.stdout or ""
            if isinstance(out, bytes):
                out = out.decode("utf-8", "replace")
            send_line(sock, {"id": req_id, "rc": 124, "output": out[-2_000_000:], "error": "command timed out"})
        except Exception as exc:
            send_line(sock, {"id": req_id, "rc": 125, "output": "", "error": f"{type(exc).__name__}: {exc}"})


def main() -> None:
    while True:
        try:
            with socket.create_connection((HOST, PORT), timeout=5) as sock:
                sock.settimeout(None)
                serve(sock)
        except Exception:
            time.sleep(0.5)


if __name__ == "__main__":
    main()
