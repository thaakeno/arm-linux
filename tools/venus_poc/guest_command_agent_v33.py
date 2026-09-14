#!/usr/bin/env python3
"""Protocol 33 guest command + native-input agent.

The reverse RPC endpoint is discovered at runtime. The host chooses an ephemeral
port and passes it through VESSEL_COMMAND_PORT, so protocol 33 never depends on
a fixed internal command port. This agent intentionally contains no KWin or
vesseloutput logic.
"""
from __future__ import annotations

import fcntl
import json
import os
import pathlib
import socket
import struct
import subprocess
import threading
import time

HOST = os.environ.get("VESSEL_COMMAND_HOST", "10.0.2.2")
PORT_TEXT = os.environ.get("VESSEL_COMMAND_PORT", "").strip()
if not PORT_TEXT:
    raise RuntimeError("VESSEL_COMMAND_PORT was not supplied by the Vessel host runtime")
PORT = int(PORT_TEXT)
if not 1 <= PORT <= 65535:
    raise RuntimeError(f"invalid VESSEL_COMMAND_PORT={PORT}")

INPUT_HOST = os.environ.get("VESSEL_INPUT_HOST", HOST)
INPUT_PORT = int(os.environ.get("VESSEL_INPUT_PORT", "47633"))
LOCK_PATH = "/tmp/vessel-guest-command-agent-v33.lock"
SYSTEM_ICD = pathlib.Path("/usr/share/vulkan/icd.d/virtio_icd.json")
SYSTEM_VENUS = pathlib.Path("/usr/lib/aarch64-linux-gnu/libvulkan_virtio.so")
VESSEL_ICD = pathlib.Path("/root/virtio-wsi-test.json")
CUSTOM_ICD_BACKUP = pathlib.Path("/root/virtio-wsi-test.custom-26.2.2.json")
INPUT_LOG = pathlib.Path("/tmp/vessel-input.log")


def append_log(path: pathlib.Path, text: str) -> None:
    try:
        with path.open("a", encoding="utf-8") as f:
            f.write(f"{time.monotonic():.3f} {text}\n")
    except OSError:
        pass


def configure_matched_system_venus() -> None:
    if not SYSTEM_ICD.is_file():
        raise RuntimeError(f"system Venus ICD missing: {SYSTEM_ICD}")
    if not SYSTEM_VENUS.is_file():
        raise RuntimeError(f"system Venus library missing: {SYSTEM_VENUS}")
    try:
        system = json.loads(SYSTEM_ICD.read_text())
        api_version = str(system.get("ICD", {}).get("api_version", "1.3.0"))
    except Exception as exc:
        raise RuntimeError(f"cannot parse system Venus ICD: {exc}") from exc
    if VESSEL_ICD.exists() and not CUSTOM_ICD_BACKUP.exists():
        try:
            CUSTOM_ICD_BACKUP.write_bytes(VESSEL_ICD.read_bytes())
        except OSError:
            pass
    payload = {
        "file_format_version": "1.0.0",
        "ICD": {"library_path": str(SYSTEM_VENUS), "api_version": api_version},
    }
    tmp = VESSEL_ICD.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, indent=2) + "\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, VESSEL_ICD)


EV_SYN = 0x00
EV_KEY = 0x01
EV_REL = 0x02
EV_ABS = 0x03
SYN_REPORT = 0
REL_X = 0x00
REL_Y = 0x01
REL_HWHEEL = 0x06
REL_WHEEL = 0x08
ABS_X = 0x00
ABS_Y = 0x01
BTN_LEFT = 0x110
BTN_RIGHT = 0x111
BTN_MIDDLE = 0x112
BTN_TOUCH = 0x14A
BUS_VIRTUAL = 0x06
UINPUT_IOCTL_BASE = ord("U")


def _ioc(direction: int, typ: int, nr: int, size: int) -> int:
    return (direction << 30) | (size << 16) | (typ << 8) | nr


def _iow(nr: int) -> int:
    return _ioc(1, UINPUT_IOCTL_BASE, nr, struct.calcsize("i"))


def _io(nr: int) -> int:
    return _ioc(0, UINPUT_IOCTL_BASE, nr, 0)


UI_SET_EVBIT = _iow(100)
UI_SET_KEYBIT = _iow(101)
UI_SET_RELBIT = _iow(102)
UI_SET_ABSBIT = _iow(103)
UI_DEV_CREATE = _io(1)
UI_DEV_DESTROY = _io(2)


class UInputDevice:
    def __init__(self, name: str, evbits=(), keybits=(), relbits=(), absbits=(), absmax=None):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        for bit in evbits:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, bit)
        for bit in keybits:
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, bit)
        for bit in relbits:
            fcntl.ioctl(self.fd, UI_SET_RELBIT, bit)
        for bit in absbits:
            fcntl.ioctl(self.fd, UI_SET_ABSBIT, bit)
        maxv = [0] * 64
        minv = [0] * 64
        fuzz = [0] * 64
        flat = [0] * 64
        for code, value in (absmax or {}).items():
            maxv[code] = value
        header = struct.pack("<80sHHHHI", name.encode()[:79], BUS_VIRTUAL, 0x5653, 0x0001, 1, 0)
        payload = (
            header
            + struct.pack("<" + "i" * 64, *maxv)
            + struct.pack("<" + "i" * 64, *minv)
            + struct.pack("<" + "i" * 64, *fuzz)
            + struct.pack("<" + "i" * 64, *flat)
        )
        os.write(self.fd, payload)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)
        time.sleep(0.03)

    def event(self, typ: int, code: int, value: int, sync: bool = True) -> None:
        os.write(self.fd, struct.pack("llHHi", 0, 0, typ, code, int(value)))
        if sync:
            os.write(self.fd, struct.pack("llHHi", 0, 0, EV_SYN, SYN_REPORT, 0))

    def close(self) -> None:
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        try:
            os.close(self.fd)
        except OSError:
            pass


def input_loop() -> None:
    INPUT_LOG.unlink(missing_ok=True)
    if not pathlib.Path("/dev/uinput").exists():
        append_log(INPUT_LOG, "/dev/uinput missing; native input disabled")
        return
    try:
        touch = UInputDevice(
            "Vessel Touchscreen",
            evbits=(EV_KEY, EV_ABS),
            keybits=(BTN_TOUCH,),
            absbits=(ABS_X, ABS_Y),
            absmax={ABS_X: 32767, ABS_Y: 32767},
        )
        pointer = UInputDevice(
            "Vessel Trackpad",
            evbits=(EV_KEY, EV_REL),
            keybits=(BTN_LEFT, BTN_RIGHT, BTN_MIDDLE),
            relbits=(REL_X, REL_Y, REL_WHEEL, REL_HWHEEL),
        )
        keyboard = UInputDevice("Vessel Keyboard", evbits=(EV_KEY,), keybits=tuple(range(1, 256)))
    except Exception as exc:
        append_log(INPUT_LOG, f"uinput create failed {type(exc).__name__}: {exc}")
        return
    append_log(INPUT_LOG, "uinput devices ready")
    try:
        while True:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                s.settimeout(5)
                s.connect((INPUT_HOST, INPUT_PORT))
                s.settimeout(None)
                append_log(INPUT_LOG, "connected to Android input bridge")
                f = s.makefile("r", encoding="utf-8", errors="replace")
                for line in f:
                    try:
                        m = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    t = m.get("t")
                    if t == "abs":
                        touch.event(EV_ABS, ABS_X, max(0, min(32767, int(m.get("x", 0)))), False)
                        touch.event(EV_ABS, ABS_Y, max(0, min(32767, int(m.get("y", 0)))), False)
                        touch.event(EV_KEY, BTN_TOUCH, 1 if m.get("down") else 0, True)
                    elif t == "rel":
                        dx = int(m.get("dx", 0)); dy = int(m.get("dy", 0))
                        if dx: pointer.event(EV_REL, REL_X, dx, False)
                        if dy: pointer.event(EV_REL, REL_Y, dy, False)
                        if dx or dy: pointer.event(EV_SYN, SYN_REPORT, 0, False)
                    elif t == "btn":
                        code = int(m.get("code", BTN_LEFT))
                        if code in (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE):
                            pointer.event(EV_KEY, code, 1 if m.get("down") else 0)
                    elif t == "scroll":
                        x = int(m.get("x", 0)); y = int(m.get("y", 0))
                        if x: pointer.event(EV_REL, REL_HWHEEL, x, False)
                        if y: pointer.event(EV_REL, REL_WHEEL, y, False)
                        if x or y: pointer.event(EV_SYN, SYN_REPORT, 0, False)
                    elif t == "key":
                        code = int(m.get("code", 0))
                        if 0 < code < 256:
                            keyboard.event(EV_KEY, code, 1 if m.get("down") else 0)
            except (OSError, ConnectionError) as exc:
                append_log(INPUT_LOG, f"bridge reconnect: {exc}")
                time.sleep(0.12)
            finally:
                try:
                    s.close()
                except OSError:
                    pass
    finally:
        touch.close(); pointer.close(); keyboard.close()


def send_line(sock: socket.socket, obj: dict) -> None:
    sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode())


def serve(sock: socket.socket) -> None:
    f = sock.makefile("rb")
    send_line(sock, {
        "hello": "vessel-guest-command-v1",
        "pid": os.getpid(),
        "graphicsIcd": str(SYSTEM_VENUS),
        "commandPort": PORT,
    })
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
                start_new_session=True,
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
    lock = open(LOCK_PATH, "w")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    lock.write(str(os.getpid()))
    lock.flush()
    configure_matched_system_venus()
    threading.Thread(target=input_loop, daemon=True, name="vessel-native-input").start()
    while True:
        try:
            with socket.create_connection((HOST, PORT), timeout=5) as sock:
                sock.settimeout(None)
                serve(sock)
        except Exception:
            time.sleep(0.5)


if __name__ == "__main__":
    main()
