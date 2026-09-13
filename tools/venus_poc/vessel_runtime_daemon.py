#!/usr/bin/env python3
"""Vessel rootless runtime controller for the Termux-hosted UML + Venus stack.

The daemon owns the UML PTY, starts the Venus renderer/umshm relay, launches a
local TigerVNC X server in Debian, and exposes a loopback-only JSON protocol for
the Android APK. Long operations are intentionally concurrent with status/log
requests so the APK can show live boot/install progress.
"""
from __future__ import annotations

import base64
import json
import os
import pathlib
import pty
import re
import select
import shlex
import signal
import socket
import subprocess
import termios
import threading
import time
from collections import deque
from typing import Any

PROTOCOL_VERSION = 3
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
VNC_REVERSE_PORT = int(os.environ.get("VESSEL_VNC_REVERSE_PORT", "5902"))
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
        self.progress_phase = "idle"
        self.progress_percent = -1
        self.progress_detail = "Runtime ready"
        self.vnc_proxy: ReverseVncProxy | None = None

    def set_progress(self, phase: str, percent: int, detail: str) -> None:
        with self.lock:
            self.progress_phase = phase
            self.progress_percent = max(-1, min(percent, 100))
            self.progress_detail = detail

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
                "protocolVersion": PROTOCOL_VERSION,
                "backend": "UML_VENUS",
                "running": running,
                "guestReady": bool(running and self.guest_ready),
                "desktopReady": bool(running and self.desktop_ready),
                "vncPort": VNC_HOST_PORT if running and self.desktop_ready else -1,
                "pid": self.proc.pid if running and self.proc else -1,
                "runtimeDir": str(RUNTIME),
                "pocDir": str(POC),
                "uptimeMs": now_ms() - self.started_ms if running else 0,
                "lastError": self.last_error,
                "progressPhase": self.progress_phase,
                "progressPercent": self.progress_percent,
                "progressDetail": self.progress_detail,
                "logTail": self.console_text[-20000:],
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
            self.set_progress("uml_boot", 8, "Starting UML kernel")
            master, slave = pty.openpty()
            attrs = termios.tcgetattr(slave)
            attrs[3] &= ~(termios.ECHO | termios.ECHONL)
            termios.tcsetattr(slave, termios.TCSANOW, attrs)
            env = dict(os.environ)
            env.update({
                "POC_DIR": str(POC),
                "UML_DIR": str(RUNTIME),
                "ENABLE_X11": "0",
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
                self.set_progress("debian_ready", 35, "Debian shell ready")
                # The UML console has its own guest-side tty settings. Turning
                # host PTY echo off is not enough: bash otherwise echoes every
                # injected command, including our large base64 helper payloads.
                self._write("stty -echo 2>/dev/null || true\n")
                time.sleep(0.15)
                self._prepare_venus_guest()
                self.set_progress("debian_ready", 55, "Debian + Venus ready")
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
        # Only accept a marker followed by an actual numeric exit status. The
        # guest tty can echo the literal `printf '<marker>:%s\\n' $?` command;
        # treating that echoed `%s` as the result caused ValueError during boot.
        result_re = re.compile(re.escape(marker) + r":([0-9]+)")
        with self.command_lock:
            with self.lock:
                start = len(self.console_text)
            self._write(wrapped)
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                with self.lock:
                    text = self.console_text[start:]
                match = result_re.search(text)
                if match is not None:
                    rc = int(match.group(1))
                    output = text[:match.start()]
                    if rc != 0:
                        raise RuntimeError(f"guest command failed rc={rc}: {output[-5000:]}")
                    return output
                if self.proc is None or self.proc.poll() is not None:
                    raise RuntimeError("UML exited while running guest command")
                time.sleep(0.05)
        raise TimeoutError("Guest command timed out")

    def _prepare_venus_guest(self) -> None:
        self.set_progress("venus", 40, "Preparing Mesa Venus relay")
        if not GUEST_RELAY_SOURCE.exists():
            raise RuntimeError(f"missing guest relay source: {GUEST_RELAY_SOURCE}")
        payload = base64.b64encode(GUEST_RELAY_SOURCE.read_bytes()).decode()
        self.guest(f"printf '%s' {shlex.quote(payload)} | base64 -d > /root/guest_relay_direct.py", 15)
        check = self.guest(
            "test -s /opt/mesa-venus-26.2.2/lib/aarch64-linux-gnu/libvulkan_virtio.so && "
            "test -f /root/virtio-wsi-test.json && echo VENUS_READY", 10
        )
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
            out = self.guest("test -S /tmp/.venus_test && echo RELAY_READY || true", 3)
            if "RELAY_READY" in out:
                self.set_progress("venus", 55, "Venus relay ready")
                return
            time.sleep(0.2)
        raise RuntimeError("Venus guest relay did not create /tmp/.venus_test")

    def _install_reverse_vnc_helper(self) -> None:
        helper = r'''#!/usr/bin/env python3
import select,socket,sys
host=sys.argv[1]; port=int(sys.argv[2])
a=socket.create_connection((host,port),timeout=8)
b=socket.create_connection(("127.0.0.1",5901),timeout=8)
a.setblocking(False); b.setblocking(False)
while True:
    r,_,_=select.select([a,b],[],[],30)
    if not r: continue
    for src,dst in ((a,b),(b,a)):
        if src not in r: continue
        try: data=src.recv(65536)
        except BlockingIOError: continue
        if not data: sys.exit(0)
        dst.sendall(data)
'''
        encoded = base64.b64encode(helper.encode()).decode()
        self.guest(f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel_vnc_reverse.py; chmod +x /root/vessel_vnc_reverse.py", 10)

    def ensure_desktop(self, width: int = 1920, height: int = 1080, dpi: int = 144) -> dict[str, Any]:
        self.start()
        width = max(800, min(width, 3840))
        height = max(600, min(height, 2160))
        dpi = max(96, min(dpi, 240))
        self.set_progress("desktop_check", 60, "Checking KDE Plasma and TigerVNC")
        have = self.guest(
            "command -v Xtigervnc >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
            8,
        )
        if "DESKTOP_PACKAGES_READY" not in have:
            self.set_progress("desktop_install", 64, "First run: installing KDE Plasma + TigerVNC. This can take several minutes")
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && "
                "apt-get install -y tigervnc-standalone-server tigervnc-common dbus-x11 plasma-desktop plasma-workspace xterm",
                900,
            )
        self.set_progress("desktop_config", 82, "Configuring Plasma session")
        setup = r'''mkdir -p /root/.vnc /root/.config/tigervnc
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
cp /root/.vnc/xstartup /root/.config/tigervnc/xstartup
chmod +x /root/.vnc/xstartup /root/.config/tigervnc/xstartup
(vncserver -kill :1 || tigervncserver -kill :1) >/dev/null 2>&1 || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
'''
        self.guest(setup, 30)
        self._install_reverse_vnc_helper()
        self.set_progress("vnc_start", 90, "Starting KDE Plasma display")
        start_cmd = (
            "if command -v tigervncserver >/dev/null 2>&1; then VNC=tigervncserver; else VNC=vncserver; fi; "
            f"$VNC :1 -localhost yes -SecurityTypes None -geometry {width}x{height} -depth 24 -dpi {dpi} "
            ">/tmp/vessel-vnc.log 2>&1"
        )
        self.guest(start_cmd, 45)
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            out = self.guest("pgrep -f 'Xtigervnc.*:1' >/dev/null && echo VNC_READY || true", 3)
            if "VNC_READY" in out:
                break
            time.sleep(0.4)
        else:
            tail = self.guest("tail -120 /tmp/vessel-vnc.log 2>/dev/null || true", 5)
            raise RuntimeError("TigerVNC failed to start: " + tail[-6000:])
        if self.vnc_proxy is None:
            self.vnc_proxy = ReverseVncProxy(self)
            self.vnc_proxy.start()
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()

    def stop(self) -> dict[str, Any]:
        self.set_progress("stopping", -1, "Stopping Debian safely")
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
            try:
                os.close(master)
            except OSError:
                pass
        self.set_progress("idle", -1, "Runtime ready")
        return self.state()


class ReverseVncProxy:
    def __init__(self, runtime: Runtime) -> None:
        self.runtime = runtime
        self.stop_event = threading.Event()
        self.client_server: socket.socket | None = None
        self.reverse_server: socket.socket | None = None

    def start(self) -> None:
        self.stop_event.clear()
        self.client_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.client_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.client_server.bind((CONTROL_HOST, VNC_HOST_PORT))
        self.client_server.listen(4)
        self.client_server.settimeout(0.5)
        self.reverse_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.reverse_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.reverse_server.bind((CONTROL_HOST, VNC_REVERSE_PORT))
        self.reverse_server.listen(4)
        self.reverse_server.settimeout(8)
        threading.Thread(target=self._serve, daemon=True, name="vessel-vnc-proxy").start()

    def stop(self) -> None:
        self.stop_event.set()
        for s in (self.client_server, self.reverse_server):
            if s:
                try:
                    s.close()
                except OSError:
                    pass
        self.client_server = None
        self.reverse_server = None

    def _serve(self) -> None:
        assert self.client_server is not None
        while not self.stop_event.is_set():
            try:
                client, _ = self.client_server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self._connect_pair, args=(client,), daemon=True).start()

    def _connect_pair(self, client: socket.socket) -> None:
        reverse = None
        try:
            self.runtime.guest(
                f"nohup python3 /root/vessel_vnc_reverse.py 10.0.2.2 {VNC_REVERSE_PORT} "
                ">/tmp/vessel-vnc-reverse.log 2>&1 </dev/null &",
                8,
            )
            assert self.reverse_server is not None
            reverse, _ = self.reverse_server.accept()
            self._pump_pair(client, reverse)
        except Exception as exc:
            self.runtime.last_error = f"VNC proxy: {type(exc).__name__}: {exc}"
            try:
                client.close()
            except OSError:
                pass
            if reverse:
                try:
                    reverse.close()
                except OSError:
                    pass

    @staticmethod
    def _pump_pair(a: socket.socket, b: socket.socket) -> None:
        def pump(src: socket.socket, dst: socket.socket) -> None:
            try:
                while True:
                    data = src.recv(65536)
                    if not data:
                        break
                    dst.sendall(data)
            except OSError:
                pass
            finally:
                try:
                    dst.shutdown(socket.SHUT_WR)
                except OSError:
                    pass
        t1 = threading.Thread(target=pump, args=(a, b), daemon=True)
        t2 = threading.Thread(target=pump, args=(b, a), daemon=True)
        t1.start(); t2.start(); t1.join(); t2.join()
        a.close(); b.close()


runtime = Runtime()


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status":
            return runtime.state()
        if action == "start":
            return runtime.start(float(req.get("timeout", 75)))
        if action == "stop":
            return runtime.stop()
        if action == "desktop":
            return runtime.ensure_desktop(int(req.get("width", 1920)), int(req.get("height", 1080)), int(req.get("dpi", 144)))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state(); result["output"] = output; return result
        if action == "logs":
            return runtime.state()
        return {"ok": False, "error": f"unknown action: {action}"}
    except Exception as exc:
        runtime.last_error = f"{type(exc).__name__}: {exc}"
        runtime.set_progress("error", -1, runtime.last_error)
        result = runtime.state(); result.update({"ok": False, "error": runtime.last_error}); return result


def serve_connection(conn: socket.socket) -> None:
    with conn:
        try:
            conn.settimeout(5)
            data = b""
            while b"\n" not in data and len(data) < 1_000_000:
                chunk = conn.recv(65536)
                if not chunk:
                    break
                data += chunk
            req = json.loads(data.split(b"\n", 1)[0].decode() or "{}")
            reply = handle(req)
        except Exception as exc:
            reply = {"ok": False, "error": f"protocol: {type(exc).__name__}: {exc}"}
        try:
            conn.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode())
        except OSError:
            pass


def serve() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((CONTROL_HOST, CONTROL_PORT))
    srv.listen(16)
    print(f"[vessel-daemon] protocol={PROTOCOL_VERSION} control={CONTROL_HOST}:{CONTROL_PORT} runtime={RUNTIME}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try:
        serve()
    finally:
        runtime.stop()
