#!/usr/bin/env python3
"""Vessel rootless runtime controller for the proven Termux UML+Venus stack.

This daemon deliberately keeps the already-working graphics architecture instead of
reintroducing AVF/KVM/root requirements.  It owns the UML PTY, starts the Venus
renderer/umshm relay, launches a local TigerVNC X server in Debian, and exposes a
small loopback-only JSON protocol for the Android APK.

Protocol: one JSON object per TCP connection on 127.0.0.1:47631, one JSON reply.
"""
from __future__ import annotations

import base64
import json
import os
import pathlib
import pty
import select
import shlex
import signal
import socket
import subprocess
import threading
import time
from collections import deque
from typing import Any

HOME = pathlib.Path.home()
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(HOME / "venus-poc")))
RUNTIME = pathlib.Path(os.environ.get("VESSEL_UML_DIR", str(HOME / "venus-wsi-local")))
if not (RUNTIME / "debian-docker.ext4").exists():
    RUNTIME = HOME / "uml-test"
RUNNER = POC / "tools/venus_poc/run_venus_uml.sh"
GUEST_RELAY_SOURCE = POC / "tools/venus_poc/guest_relay_direct.py"
CONTROL_HOST = "127.0.0.1"
CONTROL_PORT = int(os.environ.get("VESSEL_CONTROL_PORT", "47631"))
VNC_HOST_PORT = int(os.environ.get("VESSEL_VNC_PORT", "5901"))
GUEST_IP = os.environ.get("VESSEL_GUEST_IP", "10.0.2.15")
GUEST_VNC_PORT = 5901
PROMPT = "root@umdebian:/#"


def now_ms() -> int:
    return int(time.monotonic() * 1000)


class Runtime:
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.command_lock = threading.Lock()
        self.proc: subprocess.Popen[bytes] | None = None
        self.master: int | None = None
        self.reader: threading.Thread | None = None
        self.started_ms = 0
        self.console = deque(maxlen=6000)
        self.console_text = ""
        self.guest_ready = False
        self.desktop_ready = False
        self.last_error = ""
        self.vnc_proxy: TcpProxy | None = None

    def append(self, text: str) -> None:
        with self.lock:
            self.console.append(text)
            self.console_text = (self.console_text + text)[-800_000:]
            if PROMPT in self.console_text[-4096:]:
                self.guest_ready = True

    def _reader_loop(self, fd: int) -> None:
        while True:
            try:
                ready, _, _ = select.select([fd], [], [], 0.5)
                if not ready:
                    if self.proc is None or self.proc.poll() is not None:
                        break
                    continue
                data = os.read(fd, 16384)
                if not data:
                    break
                self.append(data.decode("utf-8", "replace"))
            except (OSError, ValueError):
                break

    def state(self) -> dict[str, Any]:
        with self.lock:
            running = self.proc is not None and self.proc.poll() is None
            return {
                "ok": True,
                "backend": "UML_VENUS",
                "running": running,
                "guestReady": bool(running and self.guest_ready),
                "desktopReady": bool(running and self.desktop_ready),
                "vncPort": VNC_HOST_PORT if self.desktop_ready else -1,
                "pid": self.proc.pid if running and self.proc else -1,
                "runtimeDir": str(RUNTIME),
                "pocDir": str(POC),
                "uptimeMs": now_ms() - self.started_ms if running else 0,
                "lastError": self.last_error,
                "logTail": self.console_text[-12000:],
            }

    def _require_files(self) -> None:
        needed = [
            RUNNER,
            RUNTIME / "linux-umshm",
            RUNTIME / "stub_exe-umshm",
            RUNTIME / "umnet",
            RUNTIME / "passt",
            RUNTIME / "debian-docker.ext4",
        ]
        missing = [str(p) for p in needed if not p.exists()]
        if missing:
            raise RuntimeError("Missing Vessel runtime files: " + ", ".join(missing))

    def start(self, timeout: float = 75.0) -> dict[str, Any]:
        with self.lock:
            if self.proc is not None and self.proc.poll() is None:
                return self.state()
            self._require_files()
            self.guest_ready = False
            self.desktop_ready = False
            self.last_error = ""
            self.console_text = ""
            self.console.clear()
            master, slave = pty.openpty()
            env = dict(os.environ)
            env.update({
                "POC_DIR": str(POC),
                "UML_DIR": str(RUNTIME),
                "ENABLE_X11": "0",  # desktop uses guest-local Xvnc; no remote PutImage path
                "HOST_LOG": str(RUNTIME / "vessel-renderer.log"),
                "RELAY_LOG": str(RUNTIME / "vessel-relay.log"),
                "X11_LOG": str(RUNTIME / "vessel-x11-unused.log"),
            })
            self.proc = subprocess.Popen(
                ["bash", str(RUNNER)], cwd=str(POC), stdin=slave, stdout=slave,
                stderr=slave, env=env, start_new_session=True
            )
            os.close(slave)
            self.master = master
            self.started_ms = now_ms()
            self.reader = threading.Thread(target=self._reader_loop, args=(master,), daemon=True, name="vessel-uml-console")
            self.reader.start()

        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.proc is None or self.proc.poll() is not None:
                raise RuntimeError("UML exited during boot. " + self.console_text[-4000:])
            if self.guest_ready:
                self._prepare_venus_guest()
                return self.state()
            time.sleep(0.2)
        raise TimeoutError("Debian UML did not reach a shell within %.0fs" % timeout)

    def _write(self, text: str) -> None:
        if self.master is None:
            raise RuntimeError("UML console is not open")
        os.write(self.master, text.encode())

    def guest(self, command: str, timeout: float = 45.0) -> str:
        if not command.strip():
            return ""
        if not self.guest_ready:
            raise RuntimeError("Debian is not ready")
        marker = "__VESSEL_DONE_%x__" % int(time.time_ns())
        wrapped = f"{command}\nprintf '{marker}:%s\\n' $?\n"
        with self.command_lock:
            with self.lock:
                start = len(self.console_text)
            self._write(wrapped)
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                with self.lock:
                    text = self.console_text[start:]
                idx = text.find(marker + ":")
                if idx >= 0:
                    tail = text[idx:].splitlines()[0]
                    rc = int(tail.split(":", 1)[1])
                    output = text[:idx]
                    if rc != 0:
                        raise RuntimeError(f"guest command failed rc={rc}: {output[-5000:]}")
                    return output
                if self.proc is None or self.proc.poll() is not None:
                    raise RuntimeError("UML exited while running guest command")
                time.sleep(0.05)
        raise TimeoutError("Guest command timed out")

    def _prepare_venus_guest(self) -> None:
        if not GUEST_RELAY_SOURCE.exists():
            raise RuntimeError(f"missing guest relay source: {GUEST_RELAY_SOURCE}")
        payload = base64.b64encode(GUEST_RELAY_SOURCE.read_bytes()).decode()
        cmd = f"printf '%s' {shlex.quote(payload)} | base64 -d > /root/guest_relay_direct.py"
        self.guest(cmd, 15)
        check = self.guest("test -s /opt/mesa-venus-26.2.2/lib/aarch64-linux-gnu/libvulkan_virtio.so && test -f /root/virtio-wsi-test.json && echo VENUS_READY", 10)
        if "VENUS_READY" not in check:
            raise RuntimeError("Mesa Venus 26.2.2 is not installed in this guest image")
        self.guest(
            "pkill -f '[g]uest_relay_direct.py' 2>/dev/null || true; "
            "rm -f /tmp/.venus_test /tmp/vessel-guest-relay.log; "
            "nohup python3 /root/guest_relay_direct.py --host 10.0.2.2 --port 5002 --unix /tmp/.venus_test "
            ">/tmp/vessel-guest-relay.log 2>&1 </dev/null &", 10
        )
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            try:
                out = self.guest("test -S /tmp/.venus_test && echo RELAY_READY || true", 3)
                if "RELAY_READY" in out:
                    return
            except Exception:
                pass
            time.sleep(0.2)
        raise RuntimeError("Venus guest relay did not create /tmp/.venus_test")

    def ensure_desktop(self, width: int = 1920, height: int = 1080, dpi: int = 144) -> dict[str, Any]:
        self.start()
        width = max(800, min(width, 3840))
        height = max(600, min(height, 2160))
        dpi = max(96, min(dpi, 240))
        install = r'''export DEBIAN_FRONTEND=noninteractive
if ! command -v Xtigervnc >/dev/null 2>&1 || ! command -v startplasma-x11 >/dev/null 2>&1; then
  apt-get update
  apt-get install -y tigervnc-standalone-server tigervnc-common dbus-x11 plasma-desktop plasma-workspace xterm
fi
mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<'EOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
[ -f /root/venus-env.sh ] && . /root/venus-env.sh
export DISPLAY=:1
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
export VK_DRIVER_FILES=/root/virtio-wsi-test.json
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
exec dbus-run-session -- startplasma-x11
EOF
chmod +x /root/.vnc/xstartup
vncserver -kill :1 >/dev/null 2>&1 || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
'''
        self.guest(install, 900)
        launch = (
            f"vncserver :1 -localhost no -SecurityTypes None -geometry {width}x{height} "
            f"-depth 24 -dpi {dpi} >/tmp/vessel-vnc.log 2>&1; sleep 2; "
            "pgrep -f 'Xtigervnc.*:1' >/dev/null && pgrep -f startplasma >/dev/null"
        )
        self.guest(launch, 45)
        if self.vnc_proxy is None:
            self.vnc_proxy = TcpProxy(CONTROL_HOST, VNC_HOST_PORT, GUEST_IP, GUEST_VNC_PORT)
            self.vnc_proxy.start()
        self.desktop_ready = True
        return self.state()

    def stop(self) -> dict[str, Any]:
        with self.lock:
            proxy = self.vnc_proxy
            self.vnc_proxy = None
        if proxy:
            proxy.stop()
        if self.guest_ready:
            try:
                self._write("sync\npoweroff -f\n")
                time.sleep(1)
            except Exception:
                pass
        with self.lock:
            proc = self.proc
            self.proc = None
            master = self.master
            self.master = None
            self.guest_ready = False
            self.desktop_ready = False
        if proc and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=4)
            except Exception:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except Exception:
                    pass
        if master is not None:
            try: os.close(master)
            except OSError: pass
        return self.state()


class TcpProxy:
    def __init__(self, bind_host: str, bind_port: int, target_host: str, target_port: int) -> None:
        self.bind_host, self.bind_port = bind_host, bind_port
        self.target_host, self.target_port = target_host, target_port
        self.stop_event = threading.Event()
        self.server: socket.socket | None = None

    def start(self) -> None:
        self.stop()
        self.stop_event.clear()
        t = threading.Thread(target=self._serve, daemon=True, name="vessel-vnc-forward")
        t.start()
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            if self.server is not None: return
            time.sleep(0.05)
        raise RuntimeError("VNC forwarder did not start")

    def stop(self) -> None:
        self.stop_event.set()
        if self.server:
            try: self.server.close()
            except OSError: pass
        self.server = None

    def _serve(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.bind_host, self.bind_port))
        srv.listen(8)
        srv.settimeout(0.5)
        self.server = srv
        while not self.stop_event.is_set():
            try: client, _ = srv.accept()
            except socket.timeout: continue
            except OSError: break
            threading.Thread(target=self._pipe_pair, args=(client,), daemon=True).start()

    def _pipe_pair(self, client: socket.socket) -> None:
        try:
            guest = socket.create_connection((self.target_host, self.target_port), timeout=4)
        except OSError:
            client.close(); return
        def pump(a: socket.socket, b: socket.socket) -> None:
            try:
                while True:
                    data = a.recv(65536)
                    if not data: break
                    b.sendall(data)
            except OSError: pass
            finally:
                try: b.shutdown(socket.SHUT_WR)
                except OSError: pass
        a = threading.Thread(target=pump, args=(client, guest), daemon=True)
        b = threading.Thread(target=pump, args=(guest, client), daemon=True)
        a.start(); b.start(); a.join(); b.join()
        client.close(); guest.close()


runtime = Runtime()


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 75)))
        if action == "stop": return runtime.stop()
        if action == "desktop": return runtime.ensure_desktop(int(req.get("width", 1920)), int(req.get("height", 1080)), int(req.get("dpi", 144)))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state(); result["output"] = output; return result
        if action == "logs": return runtime.state()
        return {"ok": False, "error": f"unknown action: {action}"}
    except Exception as exc:
        runtime.last_error = f"{type(exc).__name__}: {exc}"
        result = runtime.state(); result.update({"ok": False, "error": runtime.last_error}); return result


def serve() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((CONTROL_HOST, CONTROL_PORT))
    srv.listen(16)
    print(f"[vessel-daemon] control={CONTROL_HOST}:{CONTROL_PORT} runtime={RUNTIME}", flush=True)
    while True:
        conn, _ = srv.accept()
        with conn:
            conn.settimeout(3)
            data = b""
            while b"\n" not in data and len(data) < 1_000_000:
                chunk = conn.recv(65536)
                if not chunk: break
                data += chunk
            try:
                req = json.loads(data.split(b"\n", 1)[0].decode() or "{}")
                reply = handle(req)
            except Exception as exc:
                reply = {"ok": False, "error": f"protocol: {type(exc).__name__}: {exc}"}
            conn.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode())


if __name__ == "__main__":
    try: serve()
    finally: runtime.stop()
