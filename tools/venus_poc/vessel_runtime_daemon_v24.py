#!/usr/bin/env python3
"""Protocol-24 Vessel runtime: native Android display + direct evdev input.

This runtime intentionally does not start TigerVNC, an RFB proxy, or Termux:X11.
KDE runs against a guest-local Xvfb X server. Xvfb's mmap-backed framebuffer is
streamed over a dedicated guest->host channel and presented by Vessel's Android
SurfaceView. Android input travels independently through /dev/uinput.

No runtime method monkey-patching is used here. NativeRuntime is an ordinary
subclass of the stable base Runtime and this file owns its protocol dispatcher.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import pathlib
import shlex
import socket
import struct
import subprocess
import threading
import time
import urllib.request
from typing import Any

import vessel_runtime_daemon as base

PROTOCOL_VERSION = 24
CONTROL_HOST = "127.0.0.1"
CONTROL_PORT = 47631
INPUT_CLIENT_PORT = 47634
INPUT_GUEST_PORT = 47635
FRAME_CLIENT_PORT = 47636
FRAME_GUEST_PORT = 47637
KERNEL_TAG = "vessel-uml-smp-latest"
KERNEL_BASE_URL = f"https://github.com/thaakeno/arm-linux/releases/download/{KERNEL_TAG}"


class InputBridge:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.guest: socket.socket | None = None

    def start(self) -> None:
        threading.Thread(target=self._guest_server, daemon=True, name="vessel-input-guest").start()
        threading.Thread(target=self._android_server, daemon=True, name="vessel-input-android").start()

    @staticmethod
    def _listener(port: int) -> socket.socket:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((CONTROL_HOST, port))
        s.listen(8)
        return s

    def _guest_server(self) -> None:
        srv = self._listener(INPUT_GUEST_PORT)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            with self.lock:
                old = self.guest
                self.guest = conn
            if old:
                try: old.close()
                except OSError: pass

    def _android_server(self) -> None:
        srv = self._listener(INPUT_CLIENT_PORT)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self._pump, args=(conn,), daemon=True).start()

    def _pump(self, conn: socket.socket) -> None:
        try:
            pending = b""
            while True:
                chunk = conn.recv(16384)
                if not chunk:
                    return
                pending += chunk
                while b"\n" in pending:
                    line, pending = pending.split(b"\n", 1)
                    if not line:
                        continue
                    with self.lock:
                        guest = self.guest
                    if guest is None:
                        continue
                    try:
                        guest.sendall(line + b"\n")
                    except OSError:
                        with self.lock:
                            if self.guest is guest:
                                self.guest = None
        finally:
            try: conn.close()
            except OSError: pass


class FrameBridge:
    """Fan-out bridge for the native VFRM1 desktop stream."""
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.clients: set[socket.socket] = set()
        self.header: bytes | None = None
        self.last_packet: bytes | None = None
        self.frames = 0
        self.guest_connected = False

    def start(self) -> None:
        threading.Thread(target=self._guest_server, daemon=True, name="vessel-frame-guest").start()
        threading.Thread(target=self._android_server, daemon=True, name="vessel-frame-android").start()

    @staticmethod
    def _listener(port: int) -> socket.socket:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((CONTROL_HOST, port))
        s.listen(8)
        return s

    @staticmethod
    def _read_exact(conn: socket.socket, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            chunk = conn.recv(n - len(out))
            if not chunk:
                raise EOFError("native frame source closed")
            out += chunk
        return bytes(out)

    @staticmethod
    def _read_line(conn: socket.socket, limit: int = 4096) -> bytes:
        out = bytearray()
        while len(out) < limit:
            b = conn.recv(1)
            if not b:
                raise EOFError("native frame source closed before header")
            out += b
            if b == b"\n":
                return bytes(out)
        raise ValueError("native frame header too large")

    def _broadcast(self, data: bytes) -> None:
        dead = []
        with self.lock:
            for client in tuple(self.clients):
                try:
                    client.sendall(data)
                except OSError:
                    dead.append(client)
            for client in dead:
                self.clients.discard(client)
                try: client.close()
                except OSError: pass

    def _guest_server(self) -> None:
        srv = self._listener(FRAME_GUEST_PORT)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                header = self._read_line(conn)
                info = json.loads(header.decode())
                if info.get("magic") != "VFRM1":
                    raise ValueError("unexpected native frame protocol")
                with self.lock:
                    self.header = header
                    self.last_packet = None
                    self.guest_connected = True
                self._broadcast(header)
                while True:
                    prefix = self._read_exact(conn, 16)
                    seq, compressed, raw = struct.unpack("!QII", prefix)
                    if compressed <= 0 or compressed > 32 * 1024 * 1024:
                        raise ValueError(f"invalid frame size {compressed}")
                    if raw <= 0 or raw > 64 * 1024 * 1024:
                        raise ValueError(f"invalid raw frame size {raw}")
                    payload = self._read_exact(conn, compressed)
                    packet = prefix + payload
                    with self.lock:
                        self.last_packet = packet
                        self.frames = max(self.frames, int(seq))
                    self._broadcast(packet)
            except (OSError, EOFError, ValueError, json.JSONDecodeError):
                pass
            finally:
                with self.lock:
                    self.guest_connected = False
                try: conn.close()
                except OSError: pass

    def _android_server(self) -> None:
        srv = self._listener(FRAME_CLIENT_PORT)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            try:
                with self.lock:
                    header = self.header
                    packet = self.last_packet
                    self.clients.add(conn)
                if header:
                    conn.sendall(header)
                if packet:
                    conn.sendall(packet)
            except OSError:
                with self.lock:
                    self.clients.discard(conn)
                try: conn.close()
                except OSError: pass


class NativeRuntime(base.Runtime):
    def __init__(self, input_bridge: InputBridge, frame_bridge: FrameBridge) -> None:
        super().__init__()
        self.input_bridge = input_bridge
        self.frame_bridge = frame_bridge
        self.direct_input_ready = False
        self.native_display_ready = False

    def state(self) -> dict[str, Any]:
        state = super().state()
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "vncPort": -1,
            "displayTransport": "native-frame-v1",
            "nativeDisplayReady": bool(self.native_display_ready and self.desktop_ready),
            "directInputReady": bool(self.direct_input_ready),
            "inputMode": "evdev" if self.direct_input_ready else "unavailable",
            "frameCount": self.frame_bridge.frames,
        })
        phase = state.get("progressPhase", "")
        uptime = int(state.get("uptimeMs", 0))
        if phase == "uml_boot":
            state["progressPercent"] = 20 if uptime >= 1000 else 10
        return state

    def _ensure_kernel_bundle(self) -> None:
        """Install the reproducible SMP+uinput UML kernel once, before boot."""
        runtime = base.RUNTIME
        marker = runtime / ".vessel-uinput-kernel-v1"
        kernel = runtime / "linux-umshm"
        stub = runtime / "stub_exe-umshm"
        if marker.exists() and kernel.exists() and stub.exists():
            return

        temp = runtime / ".vessel-kernel-download"
        temp.mkdir(parents=True, exist_ok=True)
        names = ("linux-umshm", "stub_exe-umshm", "SHA256SUMS.txt")
        try:
            for name in names:
                dst = temp / name
                with urllib.request.urlopen(f"{KERNEL_BASE_URL}/{name}", timeout=45) as response, open(dst, "wb") as out:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        out.write(chunk)
            expected: dict[str, str] = {}
            for line in (temp / "SHA256SUMS.txt").read_text().splitlines():
                parts = line.split()
                if len(parts) >= 2:
                    expected[pathlib.Path(parts[-1]).name] = parts[0]
            for name in ("linux-umshm", "stub_exe-umshm"):
                data = (temp / name).read_bytes()
                actual = hashlib.sha256(data).hexdigest()
                if expected.get(name) != actual:
                    raise RuntimeError(f"kernel checksum mismatch for {name}")
            for name in ("linux-umshm", "stub_exe-umshm"):
                src = temp / name
                dst = runtime / name
                os.replace(src, dst)
                dst.chmod(0o700)
            marker.write_text("CONFIG_SMP=y\nCONFIG_INPUT_UINPUT=y\n")
            self.append("\n[vessel-kernel] installed SMP + uinput kernel bundle\n")
        finally:
            for p in temp.glob("*"):
                try: p.unlink()
                except OSError: pass
            try: temp.rmdir()
            except OSError: pass

    def start(self, timeout: float = 75.0) -> dict[str, Any]:
        if self.proc is None or self.proc.poll() is not None:
            self.set_progress("kernel_prepare", 5, "Verifying direct-input UML kernel")
            self._ensure_kernel_bundle()
        result = super().start(timeout)
        self._ensure_input_agent()
        return self.state()

    def _ensure_input_agent(self) -> None:
        probe_cmd = r'''set +e
if [ ! -c /dev/uinput ]; then
  modprobe uinput >/dev/null 2>&1 || true
fi
if [ ! -c /dev/uinput ] && grep -qE '(^|[[:space:]])uinput$' /proc/misc 2>/dev/null; then
  minor=$(awk '$2=="uinput"{print $1; exit}' /proc/misc)
  [ -n "$minor" ] && mknod -m 0660 /dev/uinput c 10 "$minor" 2>/dev/null || true
fi
test -c /dev/uinput && echo UINPUT_READY || echo UINPUT_MISSING'''
        probe = self.guest(probe_cmd, 8)
        if "UINPUT_READY" not in probe:
            self.direct_input_ready = False
            raise RuntimeError("Rebuilt UML kernel booted without /dev/uinput")

        source = pathlib.Path(__file__).resolve().parent / "guest_input_agent.py"
        payload = base64.b64encode(source.read_bytes()).decode()
        command = (
            f"printf '%s' {shlex.quote(payload)} | base64 -d >/root/vessel_input_agent.py; "
            "chmod 700 /root/vessel_input_agent.py; "
            "pkill -f '[v]essel_input_agent.py' 2>/dev/null || true; "
            f"setsid -f python3 /root/vessel_input_agent.py 10.0.2.2 {INPUT_GUEST_PORT} "
            ">/tmp/vessel-input.log 2>&1 </dev/null; sleep .15; "
            "pgrep -f '^python3 /root/vessel_input_agent.py' >/dev/null && echo INPUT_READY"
        )
        out = self.guest(command, 10)
        if "INPUT_READY" not in out:
            self.direct_input_ready = False
            raise RuntimeError("Direct evdev input agent failed to start")
        self.direct_input_ready = True
        self.append("\n[vessel-input] Android -> /dev/uinput direct input ready\n")

    def _install_desktop_packages(self) -> None:
        check = self.guest(
            "command -v Xvfb >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && "
            "command -v xdpyinfo >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
            8,
        )
        if "DESKTOP_PACKAGES_READY" in check:
            return
        self.set_progress("desktop_install", 64, "Installing native Plasma display packages")
        self.guest(
            "export DEBIAN_FRONTEND=noninteractive; apt-get update && "
            "apt-get install -y --no-install-recommends xvfb x11-utils x11-xserver-utils dbus-x11 "
            "plasma-desktop plasma-workspace konsole dolphin systemsettings firefox-esr kate "
            "breeze-icon-theme hicolor-icon-theme shared-mime-info desktop-file-utils vulkan-tools mesa-utils okular kcalc",
            900,
        )

    def _install_frame_streamer(self) -> None:
        source = pathlib.Path(__file__).resolve().parent / "guest_frame_streamer.py"
        payload = base64.b64encode(source.read_bytes()).decode()
        self.guest(
            f"printf '%s' {shlex.quote(payload)} | base64 -d >/root/vessel_frame_streamer.py; chmod 700 /root/vessel_frame_streamer.py",
            12,
        )

    def _hide_x_cursor(self) -> None:
        # XFixesHideCursor is used instead of cursor-theme hacks. The Android
        # view draws a zero-latency local cursor only in trackpad/mouse mode.
        helper = r'''import ctypes,time
x11=ctypes.CDLL('libX11.so.6'); xf=ctypes.CDLL('libXfixes.so.3')
x11.XOpenDisplay.restype=ctypes.c_void_p
D=x11.XOpenDisplay(b':1')
if D:
    x11.XDefaultRootWindow.restype=ctypes.c_ulong
    root=x11.XDefaultRootWindow(ctypes.c_void_p(D))
    xf.XFixesHideCursor(ctypes.c_void_p(D),ctypes.c_ulong(root))
    x11.XFlush(ctypes.c_void_p(D))
    time.sleep(10**8)
'''
        encoded = base64.b64encode(helper.encode()).decode()
        self.guest(
            f"printf '%s' {shlex.quote(encoded)} | base64 -d >/root/vessel_hide_cursor.py; "
            "pkill -f '[v]essel_hide_cursor.py' 2>/dev/null || true; "
            "setsid -f env DISPLAY=:1 python3 /root/vessel_hide_cursor.py >/tmp/vessel-cursor.log 2>&1 </dev/null",
            8,
        )

    def ensure_desktop(self, width: int = 1152, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        self.start()
        width = max(960, min(int(width), 1600))
        height = max(540, min(int(height), 1000))
        dpi = max(96, min(int(dpi), 180))
        self.set_progress("desktop_check", 60, "Checking native KDE Plasma display")
        self._install_desktop_packages()
        self._install_frame_streamer()

        self.set_progress("desktop_config", 76, "Preparing guest-local X server")
        cleanup = r'''pkill -f '[v]essel_frame_streamer.py' 2>/dev/null || true
pkill -f '[v]essel_hide_cursor.py' 2>/dev/null || true
pkill -f '[X]vfb :1' 2>/dev/null || true
pkill -f '[s]tartplasma-x11' 2>/dev/null || true
pkill -f '[k]win_x11' 2>/dev/null || true
pkill -f '[p]lasmashell' 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
rm -rf /tmp/vessel-fb
mkdir -p /tmp/vessel-fb /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
'''
        self.guest(cleanup, 15)
        launch_x = (
            f"setsid -f Xvfb :1 -screen 0 {width}x{height}x24 -fbdir /tmp/vessel-fb "
            f"-nolisten tcp -ac -dpi {dpi} >/tmp/vessel-Xvfb.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
        )
        self.guest(launch_x, 8)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            ready = self.guest("DISPLAY=:1 xdpyinfo >/dev/null 2>&1 && test -f /tmp/vessel-fb/Xvfb_screen0 && echo XSERVER_READY || true", 3)
            if "XSERVER_READY" in ready:
                break
            time.sleep(0.2)
        else:
            tail = self.guest("tail -100 /tmp/vessel-Xvfb.log 2>/dev/null || true", 5)
            raise RuntimeError("native X server failed: " + tail[-4000:])

        self.set_progress("desktop_start", 88, "Starting KDE Plasma")
        profile = r'''export DISPLAY=:1
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true
pkill -f '[d]bus-run-session.*startplasma-x11' 2>/dev/null || true
setsid -f sh -lc 'export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/vessel-runtime; [ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; export VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json; exec dbus-run-session -- startplasma-x11' >/tmp/vessel-plasma.log 2>&1 </dev/null
echo PLASMA_LAUNCHED'''
        self.guest(profile, 10)
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            out = self.guest("pgrep -x plasmashell >/dev/null && echo PLASMA_READY || true", 3)
            if "PLASMA_READY" in out:
                break
            time.sleep(0.35)
        else:
            tail = self.guest("tail -120 /tmp/vessel-plasma.log 2>/dev/null || true", 5)
            raise RuntimeError("KDE Plasma failed to start: " + tail[-6000:])

        self._hide_x_cursor()
        self.set_progress("display_start", 94, "Starting native Android frame bridge")
        streamer = (
            "pkill -f '[v]essel_frame_streamer.py' 2>/dev/null || true; "
            f"setsid -f python3 /root/vessel_frame_streamer.py /tmp/vessel-fb/Xvfb_screen0 10.0.2.2 {FRAME_GUEST_PORT} 30 "
            ">/tmp/vessel-frame.log 2>&1 </dev/null; echo FRAME_STREAMER_LAUNCHED"
        )
        self.guest(streamer, 8)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self.frame_bridge.guest_connected and self.frame_bridge.frames > 0:
                break
            time.sleep(0.1)
        else:
            tail = self.guest("tail -100 /tmp/vessel-frame.log 2>/dev/null || true", 5)
            raise RuntimeError("native frame bridge did not produce a frame: " + tail[-4000:])

        self.native_display_ready = True
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live on Android Surface")
        return self.state()

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
        cmd = (
            "export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/vessel-runtime; "
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; "
            "export VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json; "
            f"setsid -f {app} >/tmp/vessel-{name}.log 2>&1 </dev/null; echo APP_LAUNCHED"
        )
        self.guest(cmd, 8)
        return self.state()

    def stop(self) -> dict[str, Any]:
        self.native_display_ready = False
        self.direct_input_ready = False
        return super().stop()


input_bridge = InputBridge()
frame_bridge = FrameBridge()
runtime = NativeRuntime(input_bridge, frame_bridge)


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status":
            return runtime.state()
        if action == "start":
            return runtime.start(float(req.get("timeout", 80)))
        if action == "stop":
            return runtime.stop()
        if action == "desktop":
            return runtime.ensure_desktop(int(req.get("width", 1152)), int(req.get("height", 720)), int(req.get("dpi", 120)))
        if action == "desktopAction":
            return runtime.desktop_action(str(req.get("name", "")))
        if action == "guest":
            output = runtime.guest(str(req.get("command", "")), float(req.get("timeout", 45)))
            result = runtime.state(); result["output"] = output; return result
        if action == "logs":
            return runtime.state()
        return {"ok": False, "error": f"unknown action: {action}"}
    except Exception as exc:
        runtime.last_error = f"{type(exc).__name__}: {exc}"
        runtime.set_progress("error", -1, runtime.last_error)
        result = runtime.state()
        result.update({"ok": False, "error": runtime.last_error})
        return result


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
    input_bridge.start()
    frame_bridge.start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((CONTROL_HOST, CONTROL_PORT))
    srv.listen(16)
    print(
        f"[vessel-daemon] protocol={PROTOCOL_VERSION} native-frame={FRAME_CLIENT_PORT}/{FRAME_GUEST_PORT} "
        f"input={INPUT_CLIENT_PORT}/{INPUT_GUEST_PORT} runtime={base.RUNTIME}",
        flush=True,
    )
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try:
        serve()
    finally:
        runtime.stop()
