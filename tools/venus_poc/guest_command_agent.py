#!/usr/bin/env python3
"""Reliable reverse command channel from UML Debian to the Vessel host.

The boot PTY is used only until this process connects. Long-lived runtime
commands never depend on terminal prompts, bracketed-paste state or marker
parsing.

Protocol 31 deliberately uses Debian's Mesa Venus ICD together with Debian's
Zink driver.  Keeping both halves on the same Mesa build avoids mixing the
system Zink Gallium driver with the older custom /opt Mesa Venus build.
"""
from __future__ import annotations

import fcntl
import json
import os
import pathlib
import socket
import subprocess
import time

HOST = "10.0.2.2"
PORT = 47640
LOCK_PATH = "/tmp/vessel-guest-command-agent.lock"
SYSTEM_ICD = pathlib.Path("/usr/share/vulkan/icd.d/virtio_icd.json")
SYSTEM_VENUS = pathlib.Path("/usr/lib/aarch64-linux-gnu/libvulkan_virtio.so")
VESSEL_ICD = pathlib.Path("/root/virtio-wsi-test.json")
CUSTOM_ICD_BACKUP = pathlib.Path("/root/virtio-wsi-test.custom-26.2.2.json")


def configure_matched_system_venus() -> None:
    """Make the legacy Vessel ICD path resolve to Debian's matching Mesa Venus.

    vessel_runtime_daemon_v31.py intentionally points Vulkan at
    /root/virtio-wsi-test.json.  Older images made that file reference the
    separately-built Mesa 26.2.2 ICD while Zink came from Debian Mesa.  Rewrite
    only the JSON selector, leaving the custom build installed for diagnostics.
    """
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
        "ICD": {
            "library_path": str(SYSTEM_VENUS),
            "api_version": api_version,
        },
    }
    tmp = VESSEL_ICD.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, indent=2) + "\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, VESSEL_ICD)


def send_line(sock: socket.socket, obj: dict) -> None:
    sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode())


def serve(sock: socket.socket) -> None:
    f = sock.makefile("rb")
    send_line(sock, {
        "hello": "vessel-guest-command-v1",
        "pid": os.getpid(),
        "graphicsIcd": str(SYSTEM_VENUS),
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
    # Only one reverse-command agent may exist in a guest. A second agent could
    # race the first connection and replace the host's active RPC socket.
    lock = open(LOCK_PATH, "w")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    lock.write(str(os.getpid()))
    lock.flush()

    # Do this before the host can issue any Vulkan/KWin command.  The daemon's
    # existing VK_DRIVER_FILES path then transparently selects system Venus.
    configure_matched_system_venus()

    while True:
        try:
            with socket.create_connection((HOST, PORT), timeout=5) as sock:
                sock.settimeout(None)
                serve(sock)
        except Exception:
            time.sleep(0.5)


if __name__ == "__main__":
    main()
