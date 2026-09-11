#!/usr/bin/env python3
"""Protocol-19 Vessel runtime.

Hardens desktop package detection/installation so an already-working Plasma
stack cannot be misclassified as missing after a transient command-channel
hiccup. Installer launch is now verified from durable guest state rather than
from a fragile one-shot stdout marker.
"""
from __future__ import annotations

import time

import vessel_runtime_daemon_v18 as v18

core = v18.core
v12 = v18.v12
v11 = v18.v11
PROTOCOL_VERSION = 19
core.PROTOCOL_VERSION = PROTOCOL_VERSION

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
        # A real rc=1 means one of the binaries is absent. Transport failures are
        # retried inside resilient_guest; anything else should remain visible.
        if "rc=1" in text or "rc=127" in text:
            return False
        raise


def launch_installer_v19(self: core.Runtime) -> None:
    """Launch apt/dpkg detached and confirm it through its durable status file."""
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
    # Do not use an stdout marker as proof that the detached process started.
    # The command channel may reconnect after the launch while the installer is
    # already alive. Durable guest state is authoritative.
    try:
        v11.resilient_guest(self, script, 12.0, attempts=12)
    except Exception as exc:
        if desktop_packages_ready(self):
            return
        # Continue to the durable-state probe below for transient launch errors.
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
    # Repeat the authoritative binary check before touching apt. This prevents a
    # transient empty/partial response from triggering a second installer after
    # DESKTOP_PACKAGES_READY was already reached.
    if desktop_packages_ready(self):
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

    # Protocol 11's durable waiter is still useful; point all restart attempts
    # at the hardened launcher first.
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


# v18 resolves v12._ensure_desktop_packages at runtime.
v12._ensure_desktop_packages = ensure_desktop_packages_v19
v11.v10._launch_package_install = launch_installer_v19
core.Runtime.ensure_desktop = v18.desktop_v18

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
