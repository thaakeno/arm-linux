#!/usr/bin/env python3
"""Protocol-18 Vessel runtime.

Fixes the remaining phone-desktop defects seen in protocol 17:
* explicitly enables TigerVNC pointer/key input;
* removes the fragile Plasma evaluateScript/qdbus panel mutation that returned rc=22;
* installs three known workstation apps (Konsole, Dolphin, System Settings), forces
  Breeze icons, rewrites stale task-manager launcher lines directly, and restarts
  plasmashell inside its existing DBus session once so generic blank launchers vanish;
* keeps the protocol-17 low-fd startup and 1152x720 phone framebuffer.
"""
from __future__ import annotations

import base64
import shlex
import threading
import time

import vessel_runtime_daemon_v17 as v17

core = v17.core
v15 = v17.v15
v14 = v17.v14
v12 = v17.v12
v11 = v17.v11
PROTOCOL_VERSION = 18
core.PROTOCOL_VERSION = PROTOCOL_VERSION
VNC_UNIX = v17.VNC_UNIX


def workstation_profile_v18(self: core.Runtime) -> None:
    cmd = r'''mkdir -p /root/.config
export DEBIAN_FRONTEND=noninteractive
if ! command -v konsole >/dev/null 2>&1 || ! command -v dolphin >/dev/null 2>&1 || ! command -v systemsettings5 >/dev/null 2>&1 || [ ! -d /usr/share/icons/breeze ]; then
  apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
    konsole dolphin systemsettings breeze-icon-theme hicolor-icon-theme shared-mime-info desktop-file-utils \
    >/tmp/vessel-workstation-v18.log 2>&1
fi
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
update-desktop-database /usr/share/applications >>/tmp/vessel-workstation-v18.log 2>&1 || true
kbuildsycoca5 --noincremental >>/tmp/vessel-workstation-v18.log 2>&1 || true
'''
    v11.resilient_guest(self, cmd, 360.0)


def start_xtigervnc_v18(self: core.Runtime, width: int, height: int, dpi: int) -> None:
    self.set_progress("vnc_start", 86, "Starting embedded X server")
    cleanup = (
        "pkill -f '[X]tigervnc.*:1' 2>/dev/null || true; "
        "pkill -f '[s]tartplasma-x11' 2>/dev/null || true; "
        "pkill -f '[k]win_x11' 2>/dev/null || true; "
        "pkill -f '[p]lasmashell' 2>/dev/null || true; "
        "pkill -f '[k]ded5' 2>/dev/null || true; "
        f"rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 {VNC_UNIX} /tmp/vessel-Xtigervnc.log /tmp/vessel-plasma.log"
    )
    v11.resilient_guest(self, cleanup, 15.0)
    launch = (
        "setsid -f Xtigervnc :1 "
        f"-geometry {width}x{height} -depth 24 -dpi {dpi} "
        f"-rfbport -1 -rfbunixpath {VNC_UNIX} -rfbunixmode 0600 "
        "-SecurityTypes None -AlwaysShared -AcceptPointerEvents=1 -AcceptKeyEvents=1 "
        "-DeferUpdate=8 "
        ">/tmp/vessel-Xtigervnc.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
    )
    out = v11.resilient_guest(self, launch, 10.0)
    if "XSERVER_LAUNCHED" not in out:
        raise RuntimeError("Could not launch TigerVNC X server")
    v17._wait_xserver(self)


def repair_panel_v18(self: core.Runtime) -> None:
    # Edit the real Plasma config, not evaluateScript. Protocol 17's rc=22 came
    # from that DBus scripting call. Existing task-manager launchers are replaced
    # with three .desktop files that we guarantee are installed.
    py = r'''from pathlib import Path
p=Path('/root/.config/plasma-org.kde.plasma.desktop-appletsrc')
if not p.exists():
    print('PANEL_CONFIG_MISSING')
    raise SystemExit(0)
text=p.read_text(errors='replace')
apps='applications:org.kde.konsole.desktop,applications:org.kde.dolphin.desktop,applications:systemsettings.desktop'
out=[]
changed=0
for line in text.splitlines():
    if line.startswith('launchers='):
        line='launchers='+apps
        changed+=1
    out.append(line)
p.write_text('\n'.join(out)+'\n')
print('PANEL_REWRITTEN',changed)
'''
    encoded = base64.b64encode(py.encode()).decode()
    cmd = rf'''printf '%s' {shlex.quote(encoded)} | base64 -d | python3 -
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kbuildsycoca5 --noincremental >/tmp/vessel-panel-v18.log 2>&1 || true
p=$(pgrep -x plasmashell | head -n1 || true)
if [ -n "$p" ]; then
  dbus=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n1)
  disp=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DISPLAY=//p' | head -n1)
  xdg=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n1)
  if [ -n "$dbus" ]; then
    export DBUS_SESSION_BUS_ADDRESS="$dbus"
    export DISPLAY="${{disp:-:1}}"
    export XDG_RUNTIME_DIR="${{xdg:-/tmp/vessel-runtime}}"
    kquitapp5 plasmashell >/dev/null 2>&1 || kill "$p" 2>/dev/null || true
    sleep .3
    kstart5 plasmashell >/tmp/vessel-plasmashell-restart.log 2>&1 || true
  fi
fi
touch /root/.vessel-panel-v18
echo PANEL_READY
'''
    out = v11.resilient_guest(self, cmd, 25.0, attempts=3)
    if "PANEL_READY" not in out:
        raise RuntimeError("Panel repair did not complete")


def wait_plasmashell_after_repair(self: core.Runtime) -> None:
    script = r'''import os,time
end=time.monotonic()+15
while time.monotonic()<end:
    for p in os.listdir('/proc'):
        if not p.isdigit(): continue
        try:
            if open('/proc/'+p+'/comm').read().strip()=='plasmashell':
                print('PANEL_LIVE'); raise SystemExit(0)
        except OSError: pass
    time.sleep(.2)
raise SystemExit(1)
'''
    enc = base64.b64encode(script.encode()).decode()
    try:
        v11.resilient_guest(self, f"printf '%s' {shlex.quote(enc)} | base64 -d | python3 -", 18.0, attempts=2)
    except Exception:
        pass


def desktop_v18(self: core.Runtime, width: int = 1152, height: int = 720, dpi: int = 120):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock(); lock = self.lifecycle_lock

    with lock:
        self.start()
        self.last_error = ""
        width = max(960, min(width, 1152)); height = max(600, min(height, 720)); dpi = max(96, min(dpi, 150))

        # A pre-v18 warm desktop may still contain the bad launcher state. Restart
        # it once so the repaired config and explicit input flags take effect.
        warm = v15._warm_desktop_ready(self)
        marker = v11.resilient_guest(self, "test -f /root/.vessel-panel-v18 && echo V18_READY || true", 5.0, attempts=2)
        if warm and "V18_READY" in marker:
            self.set_progress("desktop_attach", 96, "Attaching to running KDE Plasma")
            v15._ensure_reverse_proxy(self)
            v15.start_persistent_broker_v15(self)
            self.desktop_ready = True
            self.set_progress("desktop_ready", 100, "KDE Plasma is live")
            return self.state()

        self.set_progress("desktop_check", 60, "Checking KDE Plasma")
        v12._ensure_desktop_packages(self)
        self.set_progress("desktop_config", 76, "Repairing desktop apps and icons")
        v17.configure_remote_plasma_v17(self)
        workstation_profile_v18(self)
        v14.install_persistent_reverse_helper(self)
        v15._ensure_reverse_proxy(self)

        start_xtigervnc_v18(self, width, height, dpi)
        v17.start_and_wait_plasma_v17(self)
        v15.start_persistent_broker_v15(self)
        self.set_progress("desktop_config", 97, "Finalizing Plasma panel")
        repair_panel_v18(self)
        wait_plasmashell_after_repair(self)

        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


core.Runtime.ensure_desktop = desktop_v18

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
