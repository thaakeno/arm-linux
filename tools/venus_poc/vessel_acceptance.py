#!/usr/bin/env python3
"""One-shot acceptance test for Vessel's APK-facing UML runtime.

Run from Termux after the app/runtime branch is checked out. It verifies the same
boundaries the APK depends on: control daemon, Debian UML boot, Venus enumeration,
networking, KDE/TigerVNC startup, and an actual RFB handshake through the host-side
reverse proxy. It leaves the session running by default for visual inspection.
"""
from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
CONTROL = 47631
VNC = 5901


def request(payload: dict, timeout: float = 120.0) -> dict:
    with socket.create_connection((HOST, CONTROL), timeout=5) as s:
        s.settimeout(timeout)
        s.sendall((json.dumps(payload, separators=(",", ":")) + "\n").encode())
        data = b""
        while b"\n" not in data:
            chunk = s.recv(65536)
            if not chunk:
                raise RuntimeError("runtime daemon closed control socket")
            data += chunk
        reply = json.loads(data.split(b"\n", 1)[0])
        if not reply.get("ok"):
            raise RuntimeError(reply.get("error", "runtime request failed"))
        return reply


def rfb_handshake() -> str:
    with socket.create_connection((HOST, VNC), timeout=8) as s:
        s.settimeout(8)
        banner = s.recv(12)
        if not banner.startswith(b"RFB "):
            raise RuntimeError(f"bad RFB banner: {banner!r}")
        s.sendall(b"RFB 003.008\n")
        n = s.recv(1)
        if len(n) != 1 or n[0] == 0:
            raise RuntimeError("VNC server offered no security types")
        sec = s.recv(n[0])
        if 1 not in sec:
            raise RuntimeError(f"VNC None security unavailable: {list(sec)}")
        s.sendall(b"\x01")
        result = s.recv(4)
        if len(result) != 4 or struct.unpack("!I", result)[0] != 0:
            raise RuntimeError("VNC security negotiation failed")
        s.sendall(b"\x01")
        init = b""
        while len(init) < 24:
            init += s.recv(24 - len(init))
        width, height = struct.unpack("!HH", init[:4])
        name_len = struct.unpack("!I", init[20:24])[0]
        name = b""
        while len(name) < name_len:
            name += s.recv(name_len - len(name))
        return f"RFB 3.8 {width}x{height} {name.decode('utf-8', 'replace')}"


def guest(command: str, timeout: int = 45) -> str:
    return request({"action": "guest", "command": command, "timeout": timeout}, timeout + 15).get("output", "")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stop", action="store_true", help="stop Linux after verification")
    ap.add_argument("--width", type=int, default=1600)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--dpi", type=int, default=144)
    args = ap.parse_args()

    checks: list[tuple[str, str]] = []
    try:
        status = request({"action": "status"}, 5)
        checks.append(("daemon", f"PASS pid={status.get('pid', -1)}"))

        status = request({"action": "start", "timeout": 90}, 105)
        if not status.get("guestReady"):
            raise RuntimeError("runtime returned without guestReady")
        checks.append(("debian", f"PASS runtime={status.get('runtimeDir', '?')}"))

        vk = guest(
            "source /root/venus-env.sh 2>/dev/null || true; "
            "export VTEST_SOCKET_NAME=/tmp/.venus_test VN_DEBUG=vtest "
            "VK_DRIVER_FILES=/root/virtio-wsi-test.json XDG_RUNTIME_DIR=/tmp; "
            "timeout 25 vulkaninfo --summary 2>&1",
            35,
        )
        if "driverName" not in vk or "venus" not in vk.lower() or "Virtio-GPU Venus" not in vk:
            raise RuntimeError("Venus enumeration failed:\n" + vk[-4000:])
        device = next((line.strip() for line in vk.splitlines() if "deviceName" in line), "Virtio-GPU Venus")
        checks.append(("venus", "PASS " + device))

        net = guest("ip -4 addr show vec0; getent ahostsv4 deb.debian.org | head -1", 20)
        if "vec0" not in net or "deb.debian.org" not in net:
            raise RuntimeError("guest networking/DNS check failed:\n" + net[-3000:])
        checks.append(("network", "PASS umnet/passt + DNS"))

        status = request(
            {"action": "desktop", "width": args.width, "height": args.height, "dpi": args.dpi},
            16 * 60,
        )
        if not status.get("desktopReady"):
            raise RuntimeError("desktop action returned without desktopReady")
        checks.append(("plasma", "PASS TigerVNC/Plasma started"))

        # Give the reverse connector a moment to accept the first viewer.
        deadline = time.monotonic() + 12
        last = None
        while time.monotonic() < deadline:
            try:
                desc = rfb_handshake()
                checks.append(("embedded-display-path", "PASS " + desc))
                break
            except Exception as exc:
                last = exc
                time.sleep(0.4)
        else:
            raise RuntimeError(f"RFB reverse-proxy handshake failed: {last}")

        print("\n========== VESSEL ACCEPTANCE ==========")
        for name, result in checks:
            print(f"{name:22} {result}")
        print("RESULT                 PASS")
        print("The Linux session is left running for the Vessel APK to attach to." if not args.stop else "Stopping verified session...")
        if args.stop:
            request({"action": "stop"}, 15)
        return 0
    except Exception as exc:
        print("\n========== VESSEL ACCEPTANCE ==========")
        for name, result in checks:
            print(f"{name:22} {result}")
        print(f"RESULT                 FAIL: {type(exc).__name__}: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
