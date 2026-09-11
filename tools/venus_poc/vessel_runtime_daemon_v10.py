#!/usr/bin/env python3
"""Protocol-10 Vessel runtime.

Protocol 9 moved command traffic off the UML PTY onto a dedicated TCP channel.
Protocol 10 hardens the remaining process-lifetime boundary:

* the guest command agent is started in its own session via setsid;
* every command is started in its own session (`start_new_session=True`);
* the long first-run KDE/TigerVNC apt transaction is detached from the command
  RPC entirely and writes durable status/log files inside Debian;
* a dropped/recreated command socket can reconnect while the package install
  keeps running;
* interrupted dpkg state is repaired before retrying the install.

This specifically prevents a control/session teardown from turning a long
`apt-get` into return code -15 (SIGTERM).
"""
from __future__ import annotations

import os
import shlex
import socket
import struct
import threading
import time

import vessel_runtime_daemon_v9 as v9

core = v9.core
PROTOCOL_VERSION = 10
core.PROTOCOL_VERSION = PROTOCOL_VERSION

READY = b"VSL10READY"
v9.READY = READY

# The agent itself stays small and is still transferred over TCP.  Commands are
# placed in a new session, so job-control/session signals from the bootstrap
# shell cannot terminate apt, dpkg, Plasma setup, etc.
AGENT_SOURCE = r'''import socket,struct,subprocess
s=globals()["s"]

def rx(n):
    out=bytearray()
    while len(out)<n:
        part=s.recv(n-len(out))
        if not part:
            raise EOFError("host command socket closed")
        out.extend(part)
    return bytes(out)

def tx(kind,payload=b""):
    s.sendall(kind+struct.pack("!I",len(payload))+payload)

s.sendall(b"VSL10READY")
while True:
    try:
        size=struct.unpack("!I",rx(4))[0]
        if size>16*1024*1024:
            raise ValueError("command too large")
        cmd=rx(size).decode("utf-8")
        p=subprocess.Popen(
            ["/bin/bash","-lc",cmd],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
            start_new_session=True,
        )
        assert p.stdout is not None
        while True:
            chunk=p.stdout.read(16384)
            if not chunk:
                break
            tx(b"O",chunk)
        rc=p.wait()
        tx(b"D",struct.pack("!i",rc))
    except EOFError:
        break
    except Exception as exc:
        try:
            tx(b"E",f"{type(exc).__name__}:{exc}".encode("utf-8","replace"))
        except Exception:
            break
'''
v9.AGENT_SOURCE = AGENT_SOURCE
v9.AGENT_BYTES = AGENT_SOURCE.encode("utf-8")


def ensure_agent_v10(self: core.Runtime) -> None:
    if getattr(self, "agent_socket", None) is not None:
        return

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        listener.bind((v9.AGENT_HOST, v9.AGENT_PORT))
    except OSError as exc:
        listener.close()
        raise RuntimeError(f"cannot bind guest command channel {v9.AGENT_HOST}:{v9.AGENT_PORT}: {exc}") from exc
    listener.listen(1)
    listener.settimeout(15.0)

    bootstrap_py = (
        "import socket,struct;"
        f"s=socket.create_connection(({v9.GUEST_HOST_GATEWAY!r},{v9.AGENT_PORT}),10);"
        "h=s.recv(4,socket.MSG_WAITALL);"
        "n=struct.unpack('!I',h)[0];"
        "src=s.recv(n,socket.MSG_WAITALL);"
        "exec(compile(src,'<vessel-agent>','exec'),{'s':s})"
    )
    quoted = shlex.quote(bootstrap_py)
    # setsid is supplied by util-linux in Debian.  The PTY is used only to
    # launch this detached process; it is never used for command traffic.
    command = (
        "stty -echo 2>/dev/null || true; "
        "setsid -f python3 -u -c " + quoted +
        " </dev/null >/tmp/vessel-agent-bootstrap.log 2>&1 &\n"
    )
    if len(command.encode("utf-8")) >= 1024:
        listener.close()
        raise RuntimeError("guest socket bootstrap unexpectedly exceeds safe tty size")

    try:
        v9.full_write(self, command)
        conn, _ = listener.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.sendall(struct.pack("!I", len(v9.AGENT_BYTES)) + v9.AGENT_BYTES)
        ready = v9.recv_exact(conn, len(READY), time.monotonic() + 10.0)
        if ready != READY:
            conn.close()
            raise RuntimeError(f"bad guest command-agent handshake: {ready!r}")
        conn.settimeout(None)
        self.agent_socket = conn
        self.append("\n[vessel-agent] protocol-10 socket command channel ready\n")
    except Exception as exc:
        v9.close_agent(self)
        with self.lock:
            tail = self.console_text[-2500:]
        raise RuntimeError(f"guest socket command channel failed: {exc}. Console tail:\n{tail}") from exc
    finally:
        listener.close()


# v9.socket_guest resolves _ensure_agent from the v9 module at call time.
v9._ensure_agent = ensure_agent_v10


def guest_retry(self: core.Runtime, command: str, timeout: float = 45.0, attempts: int = 3) -> str:
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            return v9.socket_guest(self, command, timeout)
        except Exception as exc:
            last = exc
            # Only retry transport/channel failures. A real guest command
            # failure is deterministic and should be surfaced immediately.
            msg = str(exc).lower()
            if "socket" not in msg and "channel" not in msg and "connection" not in msg:
                raise
            v9.close_agent(self)
            if attempt + 1 < attempts:
                time.sleep(0.25 * (attempt + 1))
    assert last is not None
    raise last


def _launch_package_install(self: core.Runtime) -> None:
    script = r'''cat > /tmp/vessel-desktop-install.sh <<'VSL_INSTALL'
#!/bin/sh
STATUS=/tmp/vessel-desktop-install.status
LOG=/tmp/vessel-desktop-install.log
printf 'RUNNING\n' > "$STATUS"
trap 'rc=$?; printf "%s\n" "$rc" > "$STATUS"' EXIT
trap 'exit 143' TERM
export DEBIAN_FRONTEND=noninteractive
# Recover cleanly if a previous install was interrupted between unpack/configure.
dpkg --configure -a
apt-get -o DPkg::Lock::Timeout=120 update
apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
  tigervnc-standalone-server tigervnc-common dbus-x11 \
  plasma-desktop plasma-workspace xterm
VSL_INSTALL
chmod 700 /tmp/vessel-desktop-install.sh
rm -f /tmp/vessel-desktop-install.status
setsid -f sh -c '/tmp/vessel-desktop-install.sh > /tmp/vessel-desktop-install.log 2>&1' </dev/null >/dev/null 2>&1
echo INSTALL_LAUNCHED
'''
    out = guest_retry(self, script, 12.0)
    if "INSTALL_LAUNCHED" not in out:
        raise RuntimeError("Could not launch detached KDE/TigerVNC installer")


def _wait_package_install(self: core.Runtime, max_seconds: float = 15 * 60) -> None:
    deadline = time.monotonic() + max_seconds
    retries = 0
    last_line = ""
    while time.monotonic() < deadline:
        probe = guest_retry(
            self,
            "s=$(cat /tmp/vessel-desktop-install.status 2>/dev/null || echo MISSING); "
            "printf 'VSL_STATUS:%s\\n' \"$s\"; "
            "tail -n 1 /tmp/vessel-desktop-install.log 2>/dev/null || true",
            8.0,
        )
        lines = [line.strip() for line in probe.splitlines() if line.strip()]
        status = next((line.split(":", 1)[1] for line in lines if line.startswith("VSL_STATUS:")), "MISSING")
        detail_lines = [line for line in lines if not line.startswith("VSL_STATUS:")]
        if detail_lines:
            last_line = detail_lines[-1][-160:]
            self.set_progress("desktop_install", 64, "Installing KDE Plasma + TigerVNC · " + last_line)

        if status == "0":
            return
        if status in {"143", "-15"} and retries < 2:
            # SIGTERM is exactly the failure observed on-device. Since the
            # transaction is detached and dpkg is repaired on each launch,
            # resume automatically rather than failing the whole VM startup.
            retries += 1
            self.append(f"\n[vessel-desktop] installer interrupted by SIGTERM; automatic resume {retries}/2\n")
            _launch_package_install(self)
            time.sleep(1.0)
            continue
        if status not in {"RUNNING", "MISSING"}:
            tail = guest_retry(self, "tail -n 120 /tmp/vessel-desktop-install.log 2>/dev/null || true", 8.0)
            raise RuntimeError(f"KDE/TigerVNC package install failed rc={status}: {tail[-7000:]}")
        time.sleep(1.0)

    tail = guest_retry(self, "tail -n 120 /tmp/vessel-desktop-install.log 2>/dev/null || true", 8.0)
    raise TimeoutError("KDE/TigerVNC package installation exceeded 15 minutes: " + tail[-7000:])


def resilient_desktop(self: core.Runtime, width: int = 1920, height: int = 1080, dpi: int = 144):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock
    with lock:
        self.start()
        width = max(800, min(width, 3840))
        height = max(600, min(height, 2160))
        dpi = max(96, min(dpi, 240))

        self.set_progress("desktop_check", 60, "Checking KDE Plasma and TigerVNC")
        have = guest_retry(
            self,
            "command -v Xtigervnc >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
            8.0,
        )
        if "DESKTOP_PACKAGES_READY" not in have:
            self.set_progress("desktop_install", 64, "Installing KDE Plasma + TigerVNC")
            current = guest_retry(self, "cat /tmp/vessel-desktop-install.status 2>/dev/null || true", 5.0).strip()
            if current != "RUNNING":
                _launch_package_install(self)
            _wait_package_install(self)
            have = guest_retry(
                self,
                "command -v Xtigervnc >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
                8.0,
            )
            if "DESKTOP_PACKAGES_READY" not in have:
                raise RuntimeError("KDE/TigerVNC install completed but required executables are still missing")

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
        guest_retry(self, setup, 30.0)
        self._install_reverse_vnc_helper()

        self.set_progress("vnc_start", 90, "Starting KDE Plasma display")
        start_cmd = (
            "if command -v tigervncserver >/dev/null 2>&1; then VNC=tigervncserver; else VNC=vncserver; fi; "
            f"$VNC :1 -localhost yes -SecurityTypes None -geometry {width}x{height} -depth 24 -dpi {dpi} "
            ">/tmp/vessel-vnc.log 2>&1"
        )
        guest_retry(self, start_cmd, 45.0)

        deadline = time.monotonic() + 40.0
        while time.monotonic() < deadline:
            out = guest_retry(self, "pgrep -f 'Xtigervnc.*:1' >/dev/null && echo VNC_READY || true", 5.0)
            if "VNC_READY" in out:
                break
            time.sleep(0.4)
        else:
            tail = guest_retry(self, "tail -n 120 /tmp/vessel-vnc.log 2>/dev/null || true", 8.0)
            raise RuntimeError("TigerVNC failed to start: " + tail[-6000:])

        if self.vnc_proxy is None:
            self.vnc_proxy = core.ReverseVncProxy(self)
            self.vnc_proxy.start()
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


core.Runtime.ensure_desktop = resilient_desktop

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
