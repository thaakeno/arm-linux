#!/usr/bin/env python3
"""Protocol-12 Vessel runtime.

Protocol 11 got reliably through the KDE/TigerVNC package transaction, but the
final VNC launch could still fail with a bare rc=1. Two issues were hiding
there:

* Vessel installed Plasma with --no-install-recommends while Debian's
  plasma-desktop only *recommends* kwin-x11. startplasma-x11 can therefore
  exist while the actual X11 window manager is missing.
* tigervncserver couples X server lifetime to the xstartup script and hides the
  useful failure details in its own log.

Protocol 12 explicitly installs the X11 runtime pieces we need and runs
Xtigervnc + Plasma as two independently supervised processes. This avoids the
wrapper's "session exited too early" failure mode and lets Vessel report the
real Xtigervnc/Plasma log if either side dies.
"""
from __future__ import annotations

import threading
import time

import vessel_runtime_daemon_v11 as v11

core = v11.core
v10 = v11.v10
PROTOCOL_VERSION = 12
core.PROTOCOL_VERSION = PROTOCOL_VERSION


DESKTOP_READY_CHECK = (
    "command -v Xtigervnc >/dev/null 2>&1 && "
    "command -v startplasma-x11 >/dev/null 2>&1 && "
    "command -v kwin_x11 >/dev/null 2>&1 && "
    "command -v dbus-run-session >/dev/null 2>&1 && "
    "echo DESKTOP_PACKAGES_READY || true"
)


def _launch_desktop_stack_install(self: core.Runtime) -> None:
    """Install every package Vessel needs explicitly, including kwin-x11."""
    script = r'''cat > /tmp/vessel-desktop-install.sh <<'VSL_INSTALL'
#!/bin/sh
STATUS=/tmp/vessel-desktop-install.status
LOG=/tmp/vessel-desktop-install.log
printf 'RUNNING\n' > "$STATUS"
trap 'rc=$?; printf "%s\n" "$rc" > "$STATUS"' EXIT
trap 'exit 143' TERM
export DEBIAN_FRONTEND=noninteractive
# Recover safely after interrupted package transactions.
dpkg --configure -a
apt-get -o DPkg::Lock::Timeout=120 update
apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
  tigervnc-standalone-server tigervnc-common dbus-x11 \
  plasma-desktop plasma-workspace kwin-x11 \
  x11-xserver-utils xfonts-base xterm
VSL_INSTALL
chmod 700 /tmp/vessel-desktop-install.sh
rm -f /tmp/vessel-desktop-install.status
setsid -f sh -c '/tmp/vessel-desktop-install.sh > /tmp/vessel-desktop-install.log 2>&1' </dev/null >/dev/null 2>&1
echo INSTALL_LAUNCHED
'''
    out = v11.resilient_guest(self, script, 12.0)
    if "INSTALL_LAUNCHED" not in out:
        raise RuntimeError("Could not launch detached KDE/TigerVNC installer")


# Make protocol-11's automatic resume path use the corrected package set too.
v10._launch_package_install = _launch_desktop_stack_install


def _ensure_desktop_packages(self: core.Runtime) -> None:
    have = v11.resilient_guest(self, DESKTOP_READY_CHECK, 8.0)
    if "DESKTOP_PACKAGES_READY" in have:
        return

    self.set_progress("desktop_install", 64, "Installing KDE Plasma X11 + TigerVNC")
    try:
        current = v11.resilient_guest(
            self, "cat /tmp/vessel-desktop-install.status 2>/dev/null || true", 6.0
        ).strip()
    except Exception:
        current = ""
    if current != "RUNNING":
        _launch_desktop_stack_install(self)
    v11.wait_install_authoritatively(self)

    have = v11.resilient_guest(self, DESKTOP_READY_CHECK, 10.0)
    if "DESKTOP_PACKAGES_READY" not in have:
        missing = v11.resilient_guest(
            self,
            "for x in Xtigervnc startplasma-x11 kwin_x11 dbus-run-session; do "
            "command -v \"$x\" >/dev/null 2>&1 || echo MISSING:$x; done",
            8.0,
        )
        raise RuntimeError("Desktop packages installed but required commands are missing: " + missing.strip())


def _start_xtigervnc(self: core.Runtime, width: int, height: int, dpi: int) -> None:
    """Start the RFB/X server directly instead of the fragile vncserver wrapper."""
    self.set_progress("vnc_start", 88, "Starting TigerVNC X server")
    cleanup = (
        "pkill -f '[X]tigervnc.*:1' 2>/dev/null || true; "
        "pkill -f '[s]tartplasma-x11' 2>/dev/null || true; "
        "pkill -f '[k]win_x11' 2>/dev/null || true; "
        "pkill -f '[p]lasmashell' 2>/dev/null || true; "
        "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /tmp/vessel-Xtigervnc.log /tmp/vessel-plasma.log"
    )
    v11.resilient_guest(self, cleanup, 12.0)

    command = (
        "setsid -f Xtigervnc :1 "
        f"-geometry {width}x{height} -depth 24 -dpi {dpi} "
        "-localhost yes -SecurityTypes None -AlwaysShared "
        "-rfbport 5901 >/tmp/vessel-Xtigervnc.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
    )
    v11.resilient_guest(self, command, 10.0)

    deadline = time.monotonic() + 15.0
    while time.monotonic() < deadline:
        out = v11.resilient_guest(
            self,
            "pgrep -f '[X]tigervnc.*:1' >/dev/null 2>&1 && echo XSERVER_READY || true",
            5.0,
        )
        if "XSERVER_READY" in out:
            return
        time.sleep(0.35)

    tail = v11.resilient_guest(
        self, "tail -n 200 /tmp/vessel-Xtigervnc.log 2>/dev/null || true", 8.0
    )
    raise RuntimeError("Xtigervnc failed to stay running: " + tail[-8000:])


def _start_plasma(self: core.Runtime) -> None:
    self.set_progress("vnc_start", 94, "Starting KDE Plasma X11 session")
    # Keep Plasma independent of the command socket and VNC wrapper. Debian's
    # system OpenGL stack is intentionally used for the desktop; Vulkan apps can
    # still opt into /root/venus-env.sh / the Venus ICD independently.
    command = r'''mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
setsid -f sh -c '
  export HOME=/root
  export USER=root
  export LOGNAME=root
  export DISPLAY=:1
  export XDG_RUNTIME_DIR=/tmp/vessel-runtime
  unset SESSION_MANAGER
  unset DBUS_SESSION_BUS_ADDRESS
  exec dbus-run-session -- startplasma-x11
' >/tmp/vessel-plasma.log 2>&1 </dev/null
echo PLASMA_LAUNCHED
'''
    v11.resilient_guest(self, command, 10.0)

    deadline = time.monotonic() + 45.0
    while time.monotonic() < deadline:
        probe = v11.resilient_guest(
            self,
            "x=$(pgrep -f '[X]tigervnc.*:1' || true); "
            "k=$(pgrep -f '[k]win_x11' || true); "
            "p=$(pgrep -f '[p]lasmashell' || true); "
            "printf 'X=%s K=%s P=%s\\n' \"$x\" \"$k\" \"$p\"; "
            "[ -n \"$x\" ] && [ -n \"$k\" ] && [ -n \"$p\" ] && echo PLASMA_READY || true",
            6.0,
        )
        if "PLASMA_READY" in probe:
            return
        if "X=" in probe and "X= K=" in probe:
            break
        time.sleep(0.7)

    xlog = v11.resilient_guest(
        self, "tail -n 160 /tmp/vessel-Xtigervnc.log 2>/dev/null || true", 8.0
    )
    plog = v11.resilient_guest(
        self, "tail -n 220 /tmp/vessel-plasma.log 2>/dev/null || true", 8.0
    )
    raise RuntimeError(
        "KDE Plasma X11 failed to become ready. Plasma log:\n"
        + plog[-9000:]
        + "\nXtigervnc log:\n"
        + xlog[-5000:]
    )


def desktop_v12(self: core.Runtime, width: int = 1920, height: int = 1080, dpi: int = 144):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock

    with lock:
        self.start()
        width = max(800, min(width, 3840))
        height = max(600, min(height, 2160))
        dpi = max(96, min(dpi, 240))
        self.last_error = ""

        self.set_progress("desktop_check", 60, "Checking KDE Plasma X11 and TigerVNC")
        _ensure_desktop_packages(self)

        self.set_progress("desktop_config", 84, "Preparing persistent Plasma session")
        self._install_reverse_vnc_helper()
        _start_xtigervnc(self, width, height, dpi)
        _start_plasma(self)

        if self.vnc_proxy is None:
            self.vnc_proxy = core.ReverseVncProxy(self)
            self.vnc_proxy.start()

        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


core.Runtime.ensure_desktop = desktop_v12

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
