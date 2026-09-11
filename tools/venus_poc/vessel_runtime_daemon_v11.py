#!/usr/bin/env python3
"""Protocol-11 Vessel runtime.

Protocol 10 correctly detached the first-run KDE/TigerVNC package transaction,
but a transient SIGTERM on a tiny *control/probe* command could still escape
from ensure_desktop(), causing Android to paint the operation red even while
the detached apt/dpkg transaction continued normally.

Protocol 11 makes the durable installer state authoritative:
* transient command-channel failures and rc=-15 while polling are reconnectable;
* a RUNNING installer clears stale runtime errors instead of failing the RPC;
* status polling tolerates a temporarily dead command agent for up to 60s;
* interrupted installers are resumed from dpkg's durable on-disk state;
* only the installer's own terminal status or a sustained control outage fails
  desktop startup.
"""
from __future__ import annotations

import threading
import time

import vessel_runtime_daemon_v10 as v10

core = v10.core
v9 = v10.v9
PROTOCOL_VERSION = 11
core.PROTOCOL_VERSION = PROTOCOL_VERSION


def _transient(exc: BaseException) -> bool:
    text = str(exc).lower()
    return any(token in text for token in (
        "rc=-15", "signal 15", "sigterm",
        "socket", "channel", "connection", "broken pipe",
        "timed out", "timeout",
    ))


def resilient_guest(self: core.Runtime, command: str, timeout: float = 45.0, attempts: int = 12) -> str:
    """Run a short/idempotent control command with reconnect-on-SIGTERM semantics."""
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            out = v9.socket_guest(self, command, timeout)
            # A recovered command channel means an old transient transport error
            # must not stay visible as a fatal runtime error.
            if self.last_error and _transient(RuntimeError(self.last_error)):
                self.last_error = ""
            return out
        except Exception as exc:
            last = exc
            if not _transient(exc):
                raise
            v9.close_agent(self)
            # The Debian UML process itself is still alive; do not mark the guest
            # dead merely because its disposable command-agent socket was reset.
            if self.proc is not None and self.proc.poll() is None:
                self.guest_ready = True
            if attempt + 1 < attempts:
                time.sleep(min(2.0, 0.20 * (attempt + 1)))
    assert last is not None
    raise last


def _installer_probe(self: core.Runtime) -> tuple[str, str]:
    out = resilient_guest(
        self,
        "s=$(cat /tmp/vessel-desktop-install.status 2>/dev/null || echo MISSING); "
        "printf 'VSL_STATUS:%s\\n' \"$s\"; "
        "tail -n 1 /tmp/vessel-desktop-install.log 2>/dev/null || true",
        8.0,
    )
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    status = next((line.split(":", 1)[1] for line in lines if line.startswith("VSL_STATUS:")), "MISSING")
    details = [line for line in lines if not line.startswith("VSL_STATUS:")]
    return status, (details[-1][-180:] if details else "")


def wait_install_authoritatively(self: core.Runtime, max_seconds: float = 20 * 60) -> None:
    deadline = time.monotonic() + max_seconds
    restart_count = 0
    control_outage_since: float | None = None

    while time.monotonic() < deadline:
        try:
            status, detail = _installer_probe(self)
            control_outage_since = None
        except Exception as exc:
            if not _transient(exc):
                raise
            if control_outage_since is None:
                control_outage_since = time.monotonic()
            elapsed = time.monotonic() - control_outage_since
            self.last_error = ""
            self.set_progress(
                "desktop_install", 64,
                f"KDE install is still detached; reconnecting control channel ({int(elapsed)}s)",
            )
            if elapsed > 60:
                raise RuntimeError(f"Lost Debian control channel for more than 60s while KDE installer was detached: {exc}") from exc
            v9.close_agent(self)
            time.sleep(1.0)
            continue

        if status == "RUNNING":
            self.last_error = ""
            msg = "Installing KDE Plasma + TigerVNC"
            if detail:
                msg += " · " + detail
            self.set_progress("desktop_install", 64, msg)
            time.sleep(1.0)
            continue

        if status == "0":
            self.last_error = ""
            self.set_progress("desktop_install", 80, "KDE Plasma + TigerVNC packages installed")
            return

        if status in {"143", "-15", "137", "-9"} and restart_count < 4:
            restart_count += 1
            self.last_error = ""
            self.append(f"\n[vessel-desktop] detached installer interrupted ({status}); resuming {restart_count}/4\n")
            v10._launch_package_install(self)
            time.sleep(1.0)
            continue

        if status == "MISSING":
            # The launcher writes RUNNING before invoking dpkg. MISSING should
            # only be a very short startup race; launch again idempotently after
            # one second rather than reporting a false failure.
            time.sleep(1.0)
            status2, _ = _installer_probe(self)
            if status2 == "MISSING":
                v10._launch_package_install(self)
            continue

        tail = resilient_guest(self, "tail -n 160 /tmp/vessel-desktop-install.log 2>/dev/null || true", 8.0)
        raise RuntimeError(f"KDE/TigerVNC package install failed rc={status}: {tail[-8000:]}")

    tail = resilient_guest(self, "tail -n 160 /tmp/vessel-desktop-install.log 2>/dev/null || true", 8.0)
    raise TimeoutError("KDE/TigerVNC package installation exceeded 20 minutes: " + tail[-8000:])


def desktop_v11(self: core.Runtime, width: int = 1920, height: int = 1080, dpi: int = 144):
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

        self.set_progress("desktop_check", 60, "Checking KDE Plasma and TigerVNC")
        have = resilient_guest(
            self,
            "command -v Xtigervnc >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
            8.0,
        )

        if "DESKTOP_PACKAGES_READY" not in have:
            self.set_progress("desktop_install", 64, "Installing KDE Plasma + TigerVNC")
            try:
                current = resilient_guest(self, "cat /tmp/vessel-desktop-install.status 2>/dev/null || true", 6.0).strip()
            except Exception:
                current = ""
            if current != "RUNNING":
                v10._launch_package_install(self)
            wait_install_authoritatively(self)

            have = resilient_guest(
                self,
                "command -v Xtigervnc >/dev/null 2>&1 && command -v startplasma-x11 >/dev/null 2>&1 && echo DESKTOP_PACKAGES_READY || true",
                10.0,
            )
            if "DESKTOP_PACKAGES_READY" not in have:
                raise RuntimeError("KDE/TigerVNC package transaction completed, but required executables are missing")

        self.last_error = ""
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
        resilient_guest(self, setup, 30.0)
        self._install_reverse_vnc_helper()

        self.set_progress("vnc_start", 90, "Starting KDE Plasma display")
        start_cmd = (
            "if command -v tigervncserver >/dev/null 2>&1; then VNC=tigervncserver; else VNC=vncserver; fi; "
            f"$VNC :1 -localhost yes -SecurityTypes None -geometry {width}x{height} -depth 24 -dpi {dpi} "
            ">/tmp/vessel-vnc.log 2>&1"
        )
        resilient_guest(self, start_cmd, 45.0)

        deadline = time.monotonic() + 45.0
        while time.monotonic() < deadline:
            out = resilient_guest(self, "pgrep -f 'Xtigervnc.*:1' >/dev/null && echo VNC_READY || true", 5.0)
            if "VNC_READY" in out:
                break
            time.sleep(0.5)
        else:
            tail = resilient_guest(self, "tail -n 160 /tmp/vessel-vnc.log 2>/dev/null || true", 8.0)
            raise RuntimeError("TigerVNC failed to start: " + tail[-7000:])

        if self.vnc_proxy is None:
            self.vnc_proxy = core.ReverseVncProxy(self)
            self.vnc_proxy.start()
        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


core.Runtime.ensure_desktop = desktop_v11

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
