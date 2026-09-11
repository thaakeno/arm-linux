#!/usr/bin/env python3
"""Protocol-19 Vessel runtime.

Hardens desktop package detection/installation and the embedded TigerVNC X11
lifecycle. Existing Plasma packages are authoritative; transient command-channel
hiccups never trigger a second install. X server startup is verified from durable
socket/process state instead of a fragile one-shot stdout marker.
"""
from __future__ import annotations

import time

import vessel_runtime_daemon_v18 as v18

core = v18.core
v12 = v18.v12
v11 = v18.v11
PROTOCOL_VERSION = 19
core.PROTOCOL_VERSION = PROTOCOL_VERSION
VNC_UNIX = v18.VNC_UNIX

READY_CMD = (
    "command -v Xtigervnc >/dev/null 2>&1 && "
    "command -v startplasma-x11 >/dev/null 2>&1 && "
    "command -v kwin_x11 >/dev/null 2>&1 && "
    "command -v dbus-run-session >/dev/null 2>&1"
)


def desktop_packages_ready(self: core.Runtime) -> bool:
    """Use command exit status, not an echoed marker, as the source of truth."""
    try:
        v11.resilient_guest(self, READY_CMD, 8.0, attempts=20)
        return True
    except Exception as exc:
        text = str(exc).lower()
        if "rc=1" in text or "rc=127" in text:
            return False
        raise


def launch_installer_v19(self: core.Runtime) -> None:
    """Launch apt/dpkg detached and confirm it through durable guest state."""
    script = r'''cat > /tmp/vessel-desktop-install.sh <<'VSL_INSTALL'
#!/bin/sh
STATUS=/tmp/vessel-desktop-install.status
LOG=/tmp/vessel-desktop-install.log
printf 'RUNNING\n' > "$STATUS"
trap 'rc=$?; printf "%s\n" "$rc" > "$STATUS"' EXIT
trap 'exit 143' TERM
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a
apt-get -o DPkg::Lock::Timeout=120 update
apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
  tigervnc-standalone-server tigervnc-common dbus-x11 \
  plasma-desktop plasma-workspace kwin-x11 \
  x11-xserver-utils xfonts-base xterm
VSL_INSTALL
chmod 700 /tmp/vessel-desktop-install.sh
s=$(cat /tmp/vessel-desktop-install.status 2>/dev/null || true)
if [ "$s" != RUNNING ]; then
  rm -f /tmp/vessel-desktop-install.status
  (setsid -f sh -c '/tmp/vessel-desktop-install.sh > /tmp/vessel-desktop-install.log 2>&1' </dev/null >/dev/null 2>&1 || \
   nohup sh -c '/tmp/vessel-desktop-install.sh > /tmp/vessel-desktop-install.log 2>&1' </dev/null >/dev/null 2>&1 &) || true
fi
true
'''
    try:
        v11.resilient_guest(self, script, 12.0, attempts=12)
    except Exception as exc:
        if desktop_packages_ready(self):
            return
        if not v11._transient(exc):
            raise

    deadline = time.monotonic() + 8.0
    last = ""
    while time.monotonic() < deadline:
        if desktop_packages_ready(self):
            return
        try:
            out = v11.resilient_guest(
                self,
                "s=$(cat /tmp/vessel-desktop-install.status 2>/dev/null || echo MISSING); printf '%s\\n' \"$s\"",
                5.0,
                attempts=8,
            ).strip()
            if out:
                last = out.splitlines()[-1].strip()
            if last in {"RUNNING", "0"}:
                return
            if last not in {"", "MISSING"}:
                tail = v11.resilient_guest(self, "tail -n 120 /tmp/vessel-desktop-install.log 2>/dev/null || true", 8.0, attempts=8)
                raise RuntimeError(f"KDE/TigerVNC installer exited rc={last}: {tail[-7000:]}")
        except Exception as exc:
            if not v11._transient(exc):
                raise
        time.sleep(0.4)

    tail = ""
    try:
        tail = v11.resilient_guest(self, "tail -n 120 /tmp/vessel-desktop-install.log 2>/dev/null || true", 8.0, attempts=8)
    except Exception:
        pass
    raise RuntimeError("Could not verify detached KDE/TigerVNC installer launch" + (": " + tail[-5000:] if tail else ""))


def ensure_desktop_packages_v19(self: core.Runtime) -> None:
    if desktop_packages_ready(self):
        # A stale RUNNING file from an interrupted first install must never make
        # a later boot think package installation is still in progress.
        try:
            v11.resilient_guest(
                self,
                "rm -f /tmp/vessel-desktop-install.status; echo DESKTOP_PACKAGES_READY",
                5.0,
                attempts=6,
            )
        except Exception:
            pass
        self.append("\n[vessel-desktop] packages already ready; installer skipped\n")
        return

    self.set_progress("desktop_install", 64, "Installing KDE Plasma X11 + TigerVNC")
    current = ""
    try:
        current = v11.resilient_guest(
            self, "cat /tmp/vessel-desktop-install.status 2>/dev/null || true", 6.0, attempts=12
        ).strip()
    except Exception as exc:
        if not v11._transient(exc):
            raise

    if current != "RUNNING":
        launch_installer_v19(self)

    old_launcher = v11.v10._launch_package_install
    v11.v10._launch_package_install = launch_installer_v19
    try:
        v11.wait_install_authoritatively(self)
    finally:
        v11.v10._launch_package_install = old_launcher

    if not desktop_packages_ready(self):
        missing = v11.resilient_guest(
            self,
            "for x in Xtigervnc startplasma-x11 kwin_x11 dbus-run-session; do command -v \"$x\" >/dev/null 2>&1 || echo MISSING:$x; done",
            8.0,
            attempts=12,
        )
        raise RuntimeError("Desktop package transaction finished but required commands are missing: " + missing.strip())

    self.last_error = ""
    self.set_progress("desktop_install", 80, "KDE Plasma + TigerVNC packages ready")


def _xserver_ready_v19(self: core.Runtime) -> bool:
    """Cheap durable probe that never uses a failing rc as control flow."""
    try:
        out = v11.resilient_guest(
            self,
            f"if [ -S {VNC_UNIX} ] && pgrep -f '[X]tigervnc.*:1' >/dev/null 2>&1; then echo XSERVER_READY; else echo XSERVER_DOWN; fi",
            5.0,
            attempts=8,
        )
        return "XSERVER_READY" in out
    except Exception:
        return False


def start_xtigervnc_v19(self: core.Runtime, width: int, height: int, dpi: int) -> None:
    """Start Xtigervnc idempotently and diagnose the real failure in one pass.

    Protocol 18 launched with `setsid -f` and then ran a separate readiness
    command that exited 1. On a slow/restarting UML that rc=1 escaped as a fatal
    GuestCommandError even when the X server was merely still coming up. This
    version uses durable pid/status/socket files and an always-successful waiter.
    """
    self.set_progress("vnc_start", 86, "Starting embedded X server")

    if _xserver_ready_v19(self):
        self.append("\n[vessel-desktop] existing TigerVNC X server adopted\n")
        return

    cleanup = f'''pkill -TERM -f '[X]tigervnc.*:1' 2>/dev/null || true
pkill -TERM -f '[X]vnc.*:1' 2>/dev/null || true
pkill -TERM -f '[s]tartplasma-x11' 2>/dev/null || true
pkill -TERM -f '[k]win_x11' 2>/dev/null || true
pkill -TERM -f '[p]lasmashell' 2>/dev/null || true
pkill -TERM -f '[k]ded5' 2>/dev/null || true
i=0
while pgrep -f '[X]tigervnc.*:1' >/dev/null 2>&1 && [ "$i" -lt 20 ]; do sleep .1; i=$((i+1)); done
pkill -KILL -f '[X]tigervnc.*:1' 2>/dev/null || true
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 {VNC_UNIX} \
      /tmp/vessel-Xtigervnc.pid /tmp/vessel-Xtigervnc.status /tmp/vessel-Xtigervnc.log
true'''
    v11.resilient_guest(self, cleanup, 15.0, attempts=8)

    launcher = f'''cat > /tmp/vessel-start-Xtigervnc.sh <<'VSL_X'
#!/bin/sh
exec Xtigervnc :1 \\
  -geometry {width}x{height} -depth 24 -dpi {dpi} \\
  -rfbport -1 -rfbunixpath {VNC_UNIX} -rfbunixmode 0600 \\
  -SecurityTypes None -AlwaysShared -AcceptPointerEvents=1 -AcceptKeyEvents=1 \\
  -DeferUpdate=8
VSL_X
chmod 700 /tmp/vessel-start-Xtigervnc.sh
setsid -f sh -c '/tmp/vessel-start-Xtigervnc.sh > /tmp/vessel-Xtigervnc.log 2>&1; rc=$?; printf "%s\\n" "$rc" > /tmp/vessel-Xtigervnc.status' </dev/null >/dev/null 2>&1
printf 'XSERVER_START_REQUESTED\\n'
true'''
    v11.resilient_guest(self, launcher, 10.0, attempts=8)

    # One waiter, one guest context, zero rc=1 readiness probes. If Xtigervnc
    # exits, its log is returned in the same successful RPC response so Android
    # gets the actual cause instead of "guest command failed rc=1".
    waiter = f'''i=0
while [ "$i" -lt 120 ]; do
  if [ -S {VNC_UNIX} ] && pgrep -f '[X]tigervnc.*:1' >/dev/null 2>&1; then
    echo XSERVER_READY
    exit 0
  fi
  if [ -s /tmp/vessel-Xtigervnc.status ]; then
    echo XSERVER_EXITED:$(cat /tmp/vessel-Xtigervnc.status)
    tail -n 120 /tmp/vessel-Xtigervnc.log 2>/dev/null || true
    exit 0
  fi
  sleep .1
  i=$((i+1))
done
echo XSERVER_TIMEOUT
tail -n 120 /tmp/vessel-Xtigervnc.log 2>/dev/null || true
exit 0'''
    out = v11.resilient_guest(self, waiter, 18.0, attempts=4)
    if "XSERVER_READY" in out:
        self.append("\n[vessel-desktop] TigerVNC X server ready\n")
        return

    detail = out[-7000:].strip()
    if not detail:
        try:
            detail = v11.resilient_guest(
                self,
                "tail -n 120 /tmp/vessel-Xtigervnc.log 2>/dev/null || true",
                8.0,
                attempts=6,
            )[-7000:].strip()
        except Exception:
            detail = "no Xtigervnc log available"
    raise RuntimeError("TigerVNC X server failed to become ready: " + detail)


# Patch every dynamic call site used by the protocol-18 desktop implementation.
v12._ensure_desktop_packages = ensure_desktop_packages_v19
v11.v10._launch_package_install = launch_installer_v19
v18.start_xtigervnc_v18 = start_xtigervnc_v19
core.Runtime.ensure_desktop = v18.desktop_v18

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
