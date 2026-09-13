#!/usr/bin/env python3
"""Native Vessel desktop primitives shared by protocol 25+ runtimes.

No VNC/RFB/Termux:X11 path exists here. KDE runs on a guest-local X server;
frames are streamed with VFRM2 tiled damage updates and Android input travels
on its own low-latency channel.
"""
from __future__ import annotations

import base64
import json
import pathlib
import shlex
import socket
import struct
import threading
import time
from typing import Any

import vessel_runtime_daemon as base

PROTOCOL_VERSION = 24
CONTROL_HOST = "127.0.0.1"
CONTROL_PORT = 47631
INPUT_CLIENT_PORT = 47634
INPUT_GUEST_PORT = 47635
FRAME_CLIENT_PORT = 47636
FRAME_GUEST_PORT = 47637


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
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            threading.Thread(target=self._pump, args=(conn,), daemon=True, name="vessel-input-pump").start()

    def _pump(self, conn: socket.socket) -> None:
        try:
            pending = b""
            while True:
                chunk = conn.recv(65536)
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
    """VFRM2 fan-out bridge. Caches records since the latest full frame."""
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.clients: set[socket.socket] = set()
        self.header: bytes | None = None
        self.cache: list[bytes] = []
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

    def _broadcast(self, packet: bytes) -> None:
        dead: list[socket.socket] = []
        with self.lock:
            for client in tuple(self.clients):
                try: client.sendall(packet)
                except OSError: dead.append(client)
            for client in dead:
                self.clients.discard(client)
                try: client.close()
                except OSError: pass

    def _guest_server(self) -> None:
        srv = self._listener(FRAME_GUEST_PORT)
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            try:
                header = self._read_line(conn)
                info = json.loads(header.decode())
                if info.get("magic") != "VFRM2":
                    raise ValueError("unexpected native frame protocol")
                with self.lock:
                    self.header = header
                    self.cache.clear()
                    self.guest_connected = True
                self._broadcast(header)
                while True:
                    prefix = self._read_exact(conn, 13)
                    kind = prefix[:1]
                    seq = struct.unpack("!Q", prefix[1:9])[0]
                    value = struct.unpack("!I", prefix[9:13])[0]
                    if kind == b"F":
                        if value <= 0 or value > 64 * 1024 * 1024:
                            raise ValueError("invalid full-frame size")
                        payload = self._read_exact(conn, value)
                        record = prefix + payload
                        with self.lock:
                            self.cache = [record]
                    elif kind == b"D":
                        count = value
                        if count > 8192:
                            raise ValueError("invalid damage tile count")
                        parts = [prefix]
                        total = 13
                        for _ in range(count):
                            tile_head = self._read_exact(conn, 12)
                            tile_size = struct.unpack("!I", tile_head[8:12])[0]
                            if tile_size <= 0 or tile_size > 4 * 1024 * 1024:
                                raise ValueError("invalid damage tile size")
                            tile = self._read_exact(conn, tile_size)
                            parts.extend((tile_head, tile))
                            total += 12 + tile_size
                            if total > 64 * 1024 * 1024:
                                raise ValueError("damage record too large")
                        record = b"".join(parts)
                        with self.lock:
                            self.cache.append(record)
                            if len(self.cache) > 260:
                                # Keep the latest full frame plus recent deltas.
                                full = next((x for x in self.cache if x[:1] == b"F"), None)
                                self.cache = ([full] if full else []) + self.cache[-120:]
                    else:
                        raise ValueError("unknown VFRM2 record")
                    with self.lock:
                        self.frames = max(self.frames, int(seq))
                    self._broadcast(record)
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
                    cached = list(self.cache)
                    self.clients.add(conn)
                if header: conn.sendall(header)
                for record in cached: conn.sendall(record)
            except OSError:
                with self.lock: self.clients.discard(conn)
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
            "displayTransport": "native-frame-v2",
            "nativeDisplayReady": bool(self.native_display_ready and self.desktop_ready),
            "directInputReady": bool(self.direct_input_ready),
            "inputMode": "native",
            "frameCount": self.frame_bridge.frames,
        })
        return state

    def _install_desktop_packages(self) -> None:
        check = self.guest(
            "command -v Xvfb >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && command -v xdpyinfo >/dev/null 2>&1 && command -v gcc >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
            8,
        )
        if "DESKTOP_PACKAGES_READY" in check:
            return
        self.set_progress("desktop_install", 64, "Installing Plasma display packages")
        self.guest(
            "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends "
            "xvfb x11-utils x11-xserver-utils dbus-x11 plasma-desktop plasma-workspace konsole dolphin systemsettings "
            "firefox-esr kate breeze-icon-theme hicolor-icon-theme shared-mime-info desktop-file-utils vulkan-tools mesa-utils okular kcalc gcc",
            900,
        )

    def _install_frame_streamer(self) -> None:
        source = pathlib.Path(__file__).resolve().parent / "guest_frame_streamer_v2.c"
        payload = base64.b64encode(source.read_bytes()).decode()
        cmd = (
            f"printf '%s' {shlex.quote(payload)} | base64 -d >/root/vessel_frame_streamer_v2.c; "
            "if [ ! -x /root/vessel_frame_streamer_v2 ] || ! cmp -s /root/vessel_frame_streamer_v2.c /root/.vessel_frame_streamer_v2.source; then "
            "gcc -O3 -pipe -std=gnu11 -o /root/vessel_frame_streamer_v2 /root/vessel_frame_streamer_v2.c && "
            "cp /root/vessel_frame_streamer_v2.c /root/.vessel_frame_streamer_v2.source; fi; "
            "test -x /root/vessel_frame_streamer_v2 && echo FRAME_STREAMER_READY"
        )
        out = self.guest(cmd, 20)
        if "FRAME_STREAMER_READY" not in out:
            raise RuntimeError("native frame streamer failed to build")

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        self.start()
        width = max(960, min(int(width), 1920))
        height = max(540, min(int(height), 1200))
        dpi = max(96, min(int(dpi), 180))
        self.set_progress("desktop_check", 60, "Checking KDE Plasma")
        self._install_desktop_packages()
        self._install_frame_streamer()

        self.set_progress("desktop_config", 76, "Preparing low-latency desktop")
        cleanup = r'''for f in /tmp/vessel-frame.pid /tmp/vessel-plasma.pid /tmp/vessel-input.pid; do
  if [ -r "$f" ]; then p=$(cat "$f" 2>/dev/null || true); [ -n "$p" ] && kill "$p" 2>/dev/null || true; fi
  rm -f "$f"
done
pkill -x plasmashell 2>/dev/null || true
pkill -x kwin_x11 2>/dev/null || true
pkill -x Xvfb 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
rm -rf /tmp/vessel-fb /tmp/vessel-runtime
mkdir -p /tmp/vessel-fb /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
'''
        self.guest(cleanup, 12)

        launch_x = (
            f"nohup Xvfb :1 -screen 0 {width}x{height}x24 -fbdir /tmp/vessel-fb -nolisten tcp -ac -dpi {dpi} "
            ">/tmp/vessel-Xvfb.log 2>&1 </dev/null & echo $! >/tmp/vessel-x.pid; echo XSERVER_LAUNCHED"
        )
        self.guest(launch_x, 8)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            ready = self.guest("DISPLAY=:1 xdpyinfo >/dev/null 2>&1 && test -f /tmp/vessel-fb/Xvfb_screen0 && echo XSERVER_READY || true", 3)
            if "XSERVER_READY" in ready:
                self.append("XSERVER_READY\n")
                break
            time.sleep(0.12)
        else:
            tail = self.guest("tail -80 /tmp/vessel-Xvfb.log 2>/dev/null || true", 5)
            raise RuntimeError("native X server failed: " + tail[-3000:])

        self.set_progress("desktop_start", 88, "Starting KDE Plasma")
        profile = r'''[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true
export DISPLAY=:1
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
export VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true
nohup sh -lc 'export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/vessel-runtime VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json; exec dbus-run-session -- startplasma-x11' >/tmp/vessel-plasma.log 2>&1 </dev/null &
echo $! >/tmp/vessel-plasma.pid
echo PLASMA_LAUNCHED'''
        self.guest(profile, 10)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            out = self.guest("pgrep -x plasmashell >/dev/null && echo PLASMA_READY || true", 3)
            if "PLASMA_READY" in out:
                self.append("PLASMA_READY\n")
                break
            time.sleep(0.2)
        else:
            tail = self.guest("tail -100 /tmp/vessel-plasma.log 2>/dev/null || true", 5)
            raise RuntimeError("KDE Plasma failed to start: " + tail[-5000:])

        # Keep the real X cursor visible. VFRM2 damage updates are cheap enough
        # that cursor motion itself becomes a tiny real desktop update.
        self.set_progress("display_start", 94, "Starting 120 Hz native damage bridge")
        streamer = (
            "if [ -r /tmp/vessel-frame.pid ]; then p=$(cat /tmp/vessel-frame.pid 2>/dev/null || true); [ -n \"$p\" ] && kill \"$p\" 2>/dev/null || true; fi; "
            f"nohup /root/vessel_frame_streamer_v2 /tmp/vessel-fb/Xvfb_screen0 10.0.2.2 {FRAME_GUEST_PORT} 120 "
            ">/tmp/vessel-frame.log 2>&1 </dev/null & echo $! >/tmp/vessel-frame.pid; echo FRAME_STREAMER_LAUNCHED"
        )
        self.guest(streamer, 8)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if self.frame_bridge.guest_connected and self.frame_bridge.frames > 0:
                break
            time.sleep(0.05)
        else:
            tail = self.guest("tail -80 /tmp/vessel-frame.log 2>/dev/null || true", 5)
            raise RuntimeError("native frame bridge did not produce a frame: " + tail[-3000:])

        self.native_display_ready = True
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live on low-latency Android surface")
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
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; "
            "export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/vessel-runtime VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json; "
            f"nohup sh -lc {shlex.quote('exec ' + app)} >/tmp/vessel-{name}.log 2>&1 </dev/null & echo {name.upper()}_LAUNCHED"
        )
        self.guest(cmd, 5)
        return self.state()

    def stop(self) -> dict[str, Any]:
        self.native_display_ready = False
        self.direct_input_ready = False
        return super().stop()


input_bridge = InputBridge()
frame_bridge = FrameBridge()
runtime = NativeRuntime(input_bridge, frame_bridge)
