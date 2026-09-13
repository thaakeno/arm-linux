#!/usr/bin/env python3
"""Protocol 29: direct Android X server desktop.

The protocol-24..28 framebuffer path is intentionally gone from this runtime.
KDE talks to the embedded Lorie X server directly; Vessel does not capture,
tile, encode, relay, decode, scale, or repost desktop pixels.
"""
from __future__ import annotations

import json
import os
import pathlib
import shlex
import signal
import socket
import subprocess
import threading
import time
from typing import Any

import vessel_runtime_daemon as base

PROTOCOL_VERSION = 29
DISPLAY_NUMBER = 7
DISPLAY = f"10.0.2.2:{DISPLAY_NUMBER}"
X11_PORT = 6000 + DISPLAY_NUMBER
ANDROID_PACKAGE = os.environ.get("VESSEL_ANDROID_PACKAGE", "com.example.dreamlinux")


class DirectXRuntime(base.Runtime):
    def __init__(self) -> None:
        super().__init__()
        self.xserver_proc: subprocess.Popen[bytes] | None = None
        self.xserver_log = None

    def state(self) -> dict[str, Any]:
        state = super().state()
        x_alive = self.xserver_proc is not None and self.xserver_proc.poll() is None
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": "lorie-direct-x11-v1",
            "presenter": "Lorie Android Surface",
            "inputMode": "lorie-native",
            "vncPort": -1,
            "x11Display": DISPLAY,
            "xServerReady": bool(x_alive),
            "targetFrameRate": 120,
        })
        return state

    @staticmethod
    def _apk_path() -> str:
        override = os.environ.get("VESSEL_APK_PATH", "").strip()
        if override:
            return override
        probes = [
            ["/system/bin/cmd", "package", "path", ANDROID_PACKAGE],
            ["/system/bin/pm", "path", ANDROID_PACKAGE],
        ]
        errors: list[str] = []
        for cmd in probes:
            try:
                out = subprocess.check_output(cmd, stderr=subprocess.STDOUT, timeout=8).decode("utf-8", "replace")
                for line in out.splitlines():
                    if line.startswith("package:") and line.endswith(".apk"):
                        return line[len("package:"):].strip()
            except Exception as exc:
                errors.append(f"{' '.join(cmd)}: {exc}")
        raise RuntimeError("Cannot locate installed Vessel APK for embedded X server: " + "; ".join(errors))

    def _stop_xserver(self) -> None:
        proc = self.xserver_proc
        self.xserver_proc = None
        if proc is not None and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=3)
            except Exception:
                try: os.killpg(proc.pid, signal.SIGKILL)
                except Exception: pass
        if self.xserver_log is not None:
            try: self.xserver_log.close()
            except Exception: pass
            self.xserver_log = None

    def _ensure_xserver(self) -> None:
        if self.xserver_proc is not None and self.xserver_proc.poll() is None:
            return
        self._stop_xserver()
        apk = self._apk_path()
        log_path = base.RUNTIME / "vessel-lorie-xserver.log"
        self.xserver_log = open(log_path, "ab", buffering=0)
        env = dict(os.environ)
        env["CLASSPATH"] = apk
        # Keep Termux's injected linker state out of app_process. CmdEntryPoint
        # loads libXlorie from the Vessel APK through its PathClassLoader.
        env.pop("LD_PRELOAD", None)
        env.pop("LD_LIBRARY_PATH", None)
        args = [
            "/system/bin/app_process", "-Xnoimage-dex2oat", "/",
            "com.termux.x11.CmdEntryPoint", f":{DISPLAY_NUMBER}",
            "-ac", "-listen", "tcp", "-terminate",
        ]
        self.append("LORIE_XSERVER_LAUNCHED\n")
        self.xserver_proc = subprocess.Popen(
            args, env=env, stdin=subprocess.DEVNULL,
            stdout=self.xserver_log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if self.xserver_proc.poll() is not None:
                try:
                    tail = log_path.read_text(errors="replace")[-5000:]
                except Exception:
                    tail = ""
                raise RuntimeError("Embedded Lorie X server exited during startup: " + tail)
            try:
                with socket.create_connection(("127.0.0.1", X11_PORT), timeout=.25):
                    self.append("LORIE_XSERVER_READY\n")
                    return
            except OSError:
                time.sleep(.1)
        raise RuntimeError(f"Embedded Lorie X server did not open TCP {X11_PORT}")

    def _install_desktop_packages(self) -> None:
        have = self.guest(
            "command -v startplasma-x11 >/dev/null 2>&1 && command -v xdpyinfo >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
            8,
        )
        if "DESKTOP_PACKAGES_READY" in have:
            return
        self.set_progress("desktop_install", 64, "Installing KDE Plasma packages")
        self.guest(
            "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends "
            "x11-utils dbus-x11 plasma-desktop plasma-workspace konsole dolphin systemsettings firefox-esr kate "
            "breeze-icon-theme hicolor-icon-theme shared-mime-info desktop-file-utils vulkan-tools mesa-utils okular kcalc",
            900,
        )

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del width, height, dpi  # Lorie follows the actual Android display size/refresh.
        self.start()
        self.set_progress("desktop_check", 60, "Starting direct Android X server")
        self._ensure_xserver()
        self._install_desktop_packages()

        self.set_progress("desktop_config", 78, "Connecting KDE directly to Android X server")
        probe = self.guest(
            f"DISPLAY={shlex.quote(DISPLAY)} xdpyinfo >/dev/null 2>&1 && echo DIRECT_X_READY || true",
            8,
        )
        if "DIRECT_X_READY" not in probe:
            raise RuntimeError(f"Guest cannot reach embedded X server at {DISPLAY}")

        cleanup = r'''pkill -x plasmashell 2>/dev/null || true
pkill -x kwin_x11 2>/dev/null || true
if [ -r /tmp/vessel-plasma.pid ]; then p=$(cat /tmp/vessel-plasma.pid 2>/dev/null || true); [ -n "$p" ] && kill "$p" 2>/dev/null || true; fi
rm -f /tmp/vessel-plasma.pid
mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
'''
        self.guest(cleanup, 10)

        self.set_progress("desktop_start", 88, "Starting KDE Plasma on direct X11 surface")
        env = (
            f"export DISPLAY={shlex.quote(DISPLAY)} XDG_RUNTIME_DIR=/tmp/vessel-runtime "
            "VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json; "
            "export QT_X11_NO_MITSHM=1; "
        )
        command = (
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " + env +
            "kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 1 2>/dev/null || true; "
            "balooctl disable >/dev/null 2>&1 || true; "
            "nohup sh -lc '" + env.replace("'", "'\\''") +
            "exec dbus-run-session -- startplasma-x11' >/tmp/vessel-plasma.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-plasma.pid; echo PLASMA_LAUNCHED"
        )
        self.guest(command, 12)
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            out = self.guest("pgrep -x plasmashell >/dev/null && echo PLASMA_READY || true", 3)
            if "PLASMA_READY" in out:
                self.desktop_ready = True
                self.last_error = ""
                self.append("PLASMA_READY\nDIRECT_DISPLAY_READY\n")
                self.set_progress("desktop_ready", 100, "KDE Plasma is live on direct Android X server")
                return self.state()
            time.sleep(.2)
        tail = self.guest("tail -100 /tmp/vessel-plasma.log 2>/dev/null || true", 5)
        raise RuntimeError("KDE Plasma failed on direct X server: " + tail[-5000:])

    def desktop_action(self, name: str) -> dict[str, Any]:
        commands = {
            "firefox": "firefox-esr --no-remote",
            "vulkan3d": "vkcube",
            "dolphin": "dolphin",
            "kate": "kate",
            "okular": "okular",
            "kcalc": "kcalc",
        }
        app = commands.get(name)
        if app is None:
            raise RuntimeError(f"unknown desktop action: {name}")
        guest_env = (
            f"export DISPLAY={shlex.quote(DISPLAY)} XDG_RUNTIME_DIR=/tmp/vessel-runtime "
            "VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json QT_X11_NO_MITSHM=1; "
        )
        self.guest(
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " + guest_env +
            f"nohup sh -lc {shlex.quote('exec ' + app)} >/tmp/vessel-{name}.log 2>&1 </dev/null & echo {name.upper()}_LAUNCHED",
            8,
        )
        return self.state()

    def stop(self) -> dict[str, Any]:
        try:
            return super().stop()
        finally:
            self._stop_xserver()


runtime = DirectXRuntime()


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 80)))
        if action == "stop": return runtime.stop()
        if action == "desktop": return runtime.ensure_desktop(int(req.get("width", 1600)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAction": return runtime.desktop_action(str(req.get("name", "")))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state(); result["output"] = output; return result
        if action == "logs": return runtime.state()
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
                if not chunk: break
                data += chunk
            req = json.loads(data.split(b"\n", 1)[0].decode() or "{}")
            reply = handle(req)
        except Exception as exc:
            reply = {"ok": False, "error": f"protocol: {type(exc).__name__}: {exc}"}
        try: conn.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode())
        except OSError: pass


def serve() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 47631)); srv.listen(16)
    print(f"[vessel-daemon] protocol={PROTOCOL_VERSION} display=lorie-direct-x11-v1 port={X11_PORT}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try: serve()
    finally: runtime.stop()
