#!/usr/bin/env python3
"""Protocol 31: Plasma/KWin -> Zink(OpenGL-on-Vulkan) -> Venus -> dma-buf -> Android Vulkan/AHB.

This keeps the mature KWin/Plasma desktop semantics but removes the software
llvmpipe compositor path. KWin's OpenGL compositor is translated by Mesa Zink
into Vulkan, Venus serializes Vulkan to the Android host, and the existing
zero-pixel-copy dma-buf/AHardwareBuffer bridge presents through SurfaceFlinger.

The runtime deliberately has no software-renderer fallback: if Zink/Venus is not
active, startup fails with a stage-specific diagnostic instead of silently
burning CPU and battery in llvmpipe.
"""
from __future__ import annotations

import base64
import json
import os
import pathlib
import re
import shlex
import socket
import threading
import time
from typing import Any

import vessel_runtime_daemon as base

PROTOCOL_VERSION = 31
DISPLAY_TRANSPORT = "wayland-zink-venus-ahb-v2"
COMMAND_PORT = 47640
POC = pathlib.Path(os.environ.get("VESSEL_POC_DIR", str(pathlib.Path.home() / "vessel-poc-runtime")))
base.RUNNER = POC / "tools/venus_poc/run_venus_wayland.sh"


class WaylandRuntime(base.Runtime):
    def __init__(self) -> None:
        super().__init__()
        self.rpc_lock = threading.Lock()
        self.rpc_condition = threading.Condition()
        self.rpc_socket: socket.socket | None = None
        self.rpc_file = None
        self.rpc_seq = 0
        self.rpc_listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.rpc_listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.rpc_listener.bind(("127.0.0.1", COMMAND_PORT))
        self.rpc_listener.listen(1)
        threading.Thread(target=self._rpc_accept_loop, daemon=True, name="vessel-guest-rpc-listener").start()

    def state(self) -> dict[str, Any]:
        state = super().state()
        with self.rpc_condition:
            rpc = self.rpc_socket is not None
        relay_tail = ""
        try:
            relay_tail = (base.RUNTIME / "vessel-relay.log").read_text(errors="replace")[-5000:]
        except Exception:
            pass
        state.update({
            "protocolVersion": PROTOCOL_VERSION,
            "displayTransport": DISPLAY_TRANSPORT,
            "presenter": "Android Vulkan triple-flight + SurfaceFlinger",
            "inputMode": "direct-evdev",
            "vncPort": -1,
            "commandTransport": "reverse-json-rpc-v1" if rpc else "bootstrap-pty",
            "targetFrameRate": 120,
            "framePolicy": "damage-driven; no forced idle repaints",
            "renderer": "KWin/Plasma + Zink(OpenGL-on-Vulkan) + Venus/Adreno",
            "softwareFallback": False,
            "relayTail": relay_tail,
        })
        return state

    def _rpc_accept_loop(self) -> None:
        while True:
            try:
                conn, _ = self.rpc_listener.accept()
                conn.settimeout(None)
                f = conn.makefile("rb")
                hello = f.readline()
                obj = json.loads(hello.decode("utf-8")) if hello else {}
                if obj.get("hello") != "vessel-guest-command-v1":
                    conn.close()
                    continue
                with self.rpc_condition:
                    old = self.rpc_socket
                    old_file = self.rpc_file
                    self.rpc_socket = conn
                    self.rpc_file = f
                    self.rpc_condition.notify_all()
                if old_file is not None:
                    try: old_file.close()
                    except Exception: pass
                if old is not None:
                    try: old.close()
                    except Exception: pass
                self.append(f"GUEST_COMMAND_AGENT_READY pid={obj.get('pid', '?')}\n")
            except Exception as exc:
                self.append(f"GUEST_COMMAND_AGENT_ACCEPT_ERROR {exc}\n")
                time.sleep(.2)

    def _drop_rpc(self) -> None:
        with self.rpc_condition:
            sock, f = self.rpc_socket, self.rpc_file
            self.rpc_socket = None
            self.rpc_file = None
        if f is not None:
            try: f.close()
            except Exception: pass
        if sock is not None:
            try: sock.close()
            except Exception: pass

    def _wait_rpc(self, timeout: float = 15.0) -> bool:
        deadline = time.monotonic() + timeout
        with self.rpc_condition:
            while self.rpc_socket is None and time.monotonic() < deadline:
                self.rpc_condition.wait(timeout=max(.01, deadline - time.monotonic()))
            return self.rpc_socket is not None

    def _pty_guest(self, command: str, timeout: float = 20.0) -> str:
        marker = "__VESSEL_BOOT_%x__" % int(time.time_ns())
        wrapped = f"{command}\nprintf '{marker}:%s\\n' $?\n"
        pattern = re.compile(re.escape(marker) + r":([0-9]+)")
        with self.command_lock:
            with self.lock:
                start_len = len(self.console_text)
            self._write(wrapped)
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                with self.lock:
                    text = self.console_text
                match = pattern.search(text)
                if match:
                    rc = int(match.group(1))
                    output = text[max(0, start_len - 2048):match.start()]
                    if rc:
                        raise RuntimeError(f"bootstrap guest command failed rc={rc}: {output[-5000:]}")
                    return output
                if self.proc is None or self.proc.poll() is not None:
                    raise RuntimeError("UML exited during bootstrap command")
                time.sleep(.02)
        raise TimeoutError(f"bootstrap command timed out after {timeout:.0f}s")

    def guest(self, command: str, timeout: float = 45.0) -> str:
        if not command.strip():
            return ""
        if not self._wait_rpc(.05):
            return self._pty_guest(command, max(timeout, 20.0))
        with self.rpc_lock:
            with self.rpc_condition:
                sock, f = self.rpc_socket, self.rpc_file
            if sock is None or f is None:
                return self._pty_guest(command, max(timeout, 20.0))
            self.rpc_seq += 1
            req_id = self.rpc_seq
            req = {"id": req_id, "command": command, "timeout": timeout}
            try:
                sock.sendall((json.dumps(req, separators=(",", ":")) + "\n").encode())
                sock.settimeout(timeout + 10)
                raw = f.readline()
                sock.settimeout(None)
                if not raw:
                    raise EOFError("guest command agent disconnected")
                reply = json.loads(raw.decode("utf-8"))
                if reply.get("id") != req_id:
                    raise RuntimeError(f"guest command reply id mismatch {reply.get('id')} != {req_id}")
            except Exception:
                self._drop_rpc()
                raise
            rc = int(reply.get("rc", 125))
            output = str(reply.get("output", ""))
            if rc != 0:
                detail = str(reply.get("error", ""))
                raise RuntimeError(
                    f"guest command failed id={req_id} rc={rc} cmd={command[:240]!r}: "
                    f"{(output + chr(10) + detail)[-8000:]}"
                )
            return output

    @staticmethod
    def _b64(path: pathlib.Path) -> str:
        return base64.b64encode(path.read_bytes()).decode("ascii")

    def _bootstrap_upload(self, source: pathlib.Path, target: str) -> None:
        payload = self._b64(source)
        self._pty_guest(f"rm -f {shlex.quote(target)}.b64 {shlex.quote(target)}", 20)
        for offset in range(0, len(payload), 1024):
            chunk = payload[offset:offset + 1024]
            self._pty_guest(f"printf '%s' {shlex.quote(chunk)} >> {shlex.quote(target)}.b64", 20)
        self._pty_guest(
            f"base64 -d {shlex.quote(target)}.b64 > {shlex.quote(target)} && rm -f {shlex.quote(target)}.b64",
            20,
        )

    def _rpc_upload(self, source: pathlib.Path, target: str) -> None:
        payload = self._b64(source)
        self.guest(f"printf '%s' {shlex.quote(payload)} | base64 -d > {shlex.quote(target)}", 60)

    def _prepare_venus_guest(self) -> None:
        self.set_progress("command_agent", 38, "Starting reliable guest command channel")
        agent = POC / "tools/venus_poc/guest_command_agent.py"
        relay = POC / "tools/venus_poc/guest_relay_wayland.py"
        if not agent.exists() or not relay.exists():
            raise RuntimeError("protocol 31 guest helpers are missing")
        self._bootstrap_upload(agent, "/root/vessel_guest_command_agent.py")
        self._pty_guest("nohup python3 /root/vessel_guest_command_agent.py >/tmp/vessel-command-agent.log 2>&1 </dev/null &", 20)
        if not self._wait_rpc(20):
            raise RuntimeError("guest command agent did not establish reverse RPC channel")

        self.set_progress("venus", 45, "Starting bidirectional Venus dma-buf relay")
        self._rpc_upload(relay, "/root/guest_relay_wayland.py")
        check = self.guest(
            "test -s /opt/mesa-venus-26.2.2/lib/aarch64-linux-gnu/libvulkan_virtio.so && "
            "test -f /root/virtio-wsi-test.json && test -e /dev/umshm && echo VENUS_READY",
            20,
        )
        if "VENUS_READY" not in check:
            raise RuntimeError("Mesa Venus 26.2.2 or /dev/umshm is unavailable")

        self.guest("pkill -f '^python3 /root/guest_relay_wayland.py( |$)' 2>/dev/null || true", 10)
        self.guest("rm -f /tmp/.venus_test /tmp/vessel-frame-export.sock /tmp/vessel-guest-wayland.log", 10)
        self.guest(
            "nohup python3 /root/guest_relay_wayland.py --host 10.0.2.2 --port 5002 "
            "--unix /tmp/.venus_test --frame-unix /tmp/vessel-frame-export.sock "
            ">/tmp/vessel-guest-wayland.log 2>&1 </dev/null &",
            20,
        )
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            out = self.guest("test -S /tmp/.venus_test && echo WAYLAND_RELAY_READY || true", 10)
            if "WAYLAND_RELAY_READY" in out:
                self.set_progress("debian_ready", 55, "Debian + Venus relay ready")
                return
            time.sleep(.25)
        tail = self.guest("tail -120 /tmp/vessel-guest-wayland.log 2>/dev/null || true", 10)
        raise RuntimeError("Wayland Venus relay did not become ready: " + tail[-6000:])

    def _install_effect(self) -> None:
        effect_root = POC / "tools/venus_poc/kwin_vessel_output"
        for name in ("CMakeLists.txt", "vesseloutput.cpp", "vesseloutput.json"):
            source = effect_root / name
            if not source.exists():
                raise RuntimeError(f"missing KWin effect source: {source}")

        self.guest("mkdir -p /root/vessel-kwin-output", 10)
        for name in ("CMakeLists.txt", "vesseloutput.cpp", "vesseloutput.json"):
            self._rpc_upload(effect_root / name, f"/root/vessel-kwin-output/{name}")

        have = self.guest(
            "command -v kwin_wayland >/dev/null && command -v cmake >/dev/null && "
            "test -d /usr/lib/aarch64-linux-gnu/cmake/KWinEffects && "
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && "
            "test -e /usr/lib/aarch64-linux-gnu/qt5/plugins/platforms/libqwayland-generic.so && "
            "echo VESSEL_DESKTOP_DEPS_READY || true",
            15,
        )
        if "VESSEL_DESKTOP_DEPS_READY" not in have:
            self.set_progress("wayland_deps", 63, "Installing GPU Wayland desktop runtime")
            self.guest(
                "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends "
                "kwin-wayland kwin-dev plasma-workspace plasma-desktop dbus-x11 qtwayland5 "
                "libgl1-mesa-dri mesa-utils cmake ninja-build extra-cmake-modules build-essential "
                "libegl-dev libgl-dev libkf5coreaddons-dev libxcb1-dev libepoxy-dev",
                1200,
            )

        verify = self.guest(
            "test -e /usr/lib/aarch64-linux-gnu/dri/zink_dri.so && "
            "test -e /usr/lib/aarch64-linux-gnu/qt5/plugins/platforms/libqwayland-generic.so && "
            "echo ZINK_QTWAYLAND_READY",
            15,
        )
        if "ZINK_QTWAYLAND_READY" not in verify:
            raise RuntimeError("Zink or Qt Wayland platform plugin is unavailable")

        self.set_progress("wayland_build", 72, "Building damage-driven dma-buf output bridge")
        build = self.guest(
            "rm -rf /root/vessel-kwin-output/build && "
            "cmake -S /root/vessel-kwin-output -B /root/vessel-kwin-output/build -G Ninja "
            "-DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr && "
            "cmake --build /root/vessel-kwin-output/build -j4 && "
            "cmake --install /root/vessel-kwin-output/build && ldconfig && echo VESSEL_EFFECT_INSTALLED",
            900,
        )
        if "VESSEL_EFFECT_INSTALLED" not in build:
            raise RuntimeError("Vessel dma-buf output bridge build did not complete")

    @staticmethod
    def _gpu_env() -> str:
        return (
            "export XDG_RUNTIME_DIR=/tmp/vessel-runtime VTEST_SOCKET_NAME=/tmp/.venus_test "
            "VN_DEBUG=vtest VK_DRIVER_FILES=/root/virtio-wsi-test.json "
            "MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=0 "
            "KWIN_COMPOSE=O2 QT_QPA_PLATFORM=wayland QT_QUICK_BACKEND=opengl "
            "MOZ_ENABLE_WAYLAND=1; unset DISPLAY; "
        )

    def ensure_desktop(self, width: int = 1600, height: int = 720, dpi: int = 120) -> dict[str, Any]:
        del dpi
        self.start()
        width = max(800, min(width, 3840))
        height = max(540, min(height, 2160))
        self._install_effect()

        self.set_progress("wayland_start", 84, "Starting GPU-only Plasma compositor through Zink/Venus")
        env = self._gpu_env()
        wrapper = f'''#!/bin/bash
set -euo pipefail
mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true
{env}
mkdir -p /root/.config
kwriteconfig5 --file /root/.config/kwinrc --group Plugins --key vesseloutputEnabled true
kwin_wayland --virtual --width {width} --height {height} --scale 1 --socket wayland-0 &
KWIN_PID=$!
for i in $(seq 1 160); do
  [ -S /tmp/vessel-runtime/wayland-0 ] && break
  kill -0 "$KWIN_PID" 2>/dev/null || exit 41
  sleep .1
done
[ -S /tmp/vessel-runtime/wayland-0 ] || exit 42
export WAYLAND_DISPLAY=wayland-0
kded5 >/tmp/vessel-kded.log 2>&1 &
plasmashell >/tmp/vessel-plasmashell.log 2>&1 &
wait "$KWIN_PID"
'''
        encoded = base64.b64encode(wrapper.encode()).decode()
        launch = (
            "rm -rf /tmp/vessel-runtime; mkdir -p /tmp/vessel-runtime; chmod 700 /tmp/vessel-runtime; "
            "rm -f /tmp/vessel-wayland.log /tmp/vessel-kded.log /tmp/vessel-plasmashell.log /tmp/vessel-wayland.pid; "
            f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel-wayland-session.sh; "
            "chmod +x /root/vessel-wayland-session.sh; "
            "pkill -x plasmashell 2>/dev/null || true; pkill -x kwin_wayland 2>/dev/null || true; "
            "export XDG_RUNTIME_DIR=/tmp/vessel-runtime; "
            "nohup dbus-run-session -- /root/vessel-wayland-session.sh >/tmp/vessel-wayland.log 2>&1 </dev/null & "
            "echo $! >/tmp/vessel-wayland.pid"
        )
        self.guest(launch, 30)

        self.set_progress("wayland_present", 92, "Waiting for first Adreno-backed shared frame")
        deadline = time.monotonic() + 90
        startup_grace = time.monotonic() + 8.0
        relay_log = base.RUNTIME / "vessel-relay.log"
        software_checked = False
        while time.monotonic() < deadline:
            out = self.guest(
                "pgrep -x kwin_wayland >/dev/null && pgrep -x plasmashell >/dev/null && "
                "test -S /tmp/vessel-runtime/wayland-0 && test -S /tmp/vessel-frame-export.sock && "
                "echo WAYLAND_SESSION_READY || true; "
                "grep -m1 -E 'OpenGL renderer string:|Driver:' /tmp/vessel-wayland.log 2>/dev/null || true",
                12,
            )
            low = out.lower()
            if "llvmpipe" in low or "softpipe" in low:
                raise RuntimeError(
                    "GPU compositor rejected: KWin fell back to software rendering. "
                    "Protocol 31 has no llvmpipe fallback.\n" + out[-3000:]
                )
            if "zink" in low:
                software_checked = True

            relay = ""
            try:
                relay = relay_log.read_text(errors="replace")[-14000:]
            except Exception:
                pass
            if "WAYLAND_SESSION_READY" in out and "Android imported KWin object" in relay:
                self.desktop_ready = True
                self.last_error = ""
                self.append("ZINK_VENUS_GPU_READY\nKWIN_WAYLAND_READY\nVENUS_DMABUF_TO_ANDROID_READY\n")
                detail = "Plasma is live through Zink/Venus -> Android Vulkan/SurfaceFlinger"
                if not software_checked:
                    detail += " (GPU path verified by dma-buf import)"
                self.set_progress("desktop_ready", 100, detail)
                return self.state()

            health = self.guest(
                "if pgrep -x kwin_wayland >/dev/null; then echo KWIN_ALIVE; "
                "elif [ -s /tmp/vessel-wayland.pid ] && kill -0 $(cat /tmp/vessel-wayland.pid) 2>/dev/null; "
                "then echo SESSION_STARTING; else echo SESSION_DEAD; fi",
                8,
            )
            if time.monotonic() >= startup_grace and "SESSION_DEAD" in health:
                break
            time.sleep(.30)

        guest_tail = self.guest(
            "echo '=== session ==='; "
            "if [ -s /tmp/vessel-wayland.pid ]; then echo pid=$(cat /tmp/vessel-wayland.pid); ps -o pid,ppid,stat,comm,args -p $(cat /tmp/vessel-wayland.pid) 2>/dev/null || true; fi; "
            "echo '=== kwin/session log ==='; tail -260 /tmp/vessel-wayland.log 2>/dev/null || true; "
            "echo '=== effect loader ==='; tail -160 /tmp/vessel-effect-loader.log 2>/dev/null || true; "
            "echo '=== kded ==='; tail -100 /tmp/vessel-kded.log 2>/dev/null || true; "
            "echo '=== plasmashell ==='; tail -120 /tmp/vessel-plasmashell.log 2>/dev/null || true; "
            "echo '=== relay ==='; tail -160 /tmp/vessel-guest-wayland.log 2>/dev/null || true; "
            "echo '=== processes ==='; ps -ef | grep -E 'kwin_wayland|plasmashell|dbus-run-session|dbus-daemon' | grep -v grep || true; "
            "echo '=== sockets ==='; ls -l /tmp/.venus_test /tmp/vessel-runtime/wayland-0 /tmp/vessel-frame-export.sock 2>&1 || true",
            15,
        )
        host_tail = ""
        try:
            host_tail = relay_log.read_text(errors="replace")[-10000:]
        except Exception:
            pass
        raise RuntimeError(
            "Zink/Venus Wayland presentation did not become ready:\n" +
            guest_tail[-15000:] + "\n=== host relay ===\n" + host_tail
        )

    def desktop_action(self, name: str) -> dict[str, Any]:
        commands = {
            "firefox": "firefox-esr --no-remote",
            "vulkan3d": "vkcube-wayland 2>/dev/null || vkcube",
            "dolphin": "dolphin",
            "kate": "kate",
            "okular": "okular",
            "kcalc": "kcalc",
        }
        app = commands.get(name)
        if app is None:
            raise RuntimeError(f"unknown desktop action: {name}")
        env = self._gpu_env() + "export WAYLAND_DISPLAY=wayland-0; "
        self.guest(
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " + env +
            f"nohup sh -lc {shlex.quote('exec ' + app)} >/tmp/vessel-{name}.log 2>&1 </dev/null &",
            15,
        )
        return self.state()

    def stop(self) -> dict[str, Any]:
        if self.guest_ready and self._wait_rpc(.05):
            try:
                self.guest("pkill -x plasmashell 2>/dev/null || true; pkill -x kwin_wayland 2>/dev/null || true", 10)
            except Exception:
                pass
        result = super().stop()
        self._drop_rpc()
        return result


runtime = WaylandRuntime()


def handle(req: dict[str, Any]) -> dict[str, Any]:
    action = str(req.get("action", "status"))
    try:
        if action == "status": return runtime.state()
        if action == "start": return runtime.start(float(req.get("timeout", 100)))
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
    print(f"[vessel-daemon] protocol={PROTOCOL_VERSION} display={DISPLAY_TRANSPORT}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve_connection, args=(conn,), daemon=True, name="vessel-control").start()


if __name__ == "__main__":
    try: serve()
    finally: runtime.stop()