#!/usr/bin/env python3
"""Protocol-17 Vessel runtime.

Protocol 17 hardens the now-working embedded Plasma desktop for daily use:
* lower-cost 1152x720 framebuffer for phone rendering;
* single-process readiness waits instead of repeated pgrep command storms;
* stale/partial X11 + Plasma sessions are cleaned before a cold restart;
* qdbus-qt5 is guaranteed before panel scripting, so stale generic launchers
  are actually removed and Konsole is visibly pinned;
* panel success markers are written only after the Plasma DBus call succeeds;
* healthy warm sessions are adopted without restarting Plasma.
"""
from __future__ import annotations

import base64
import shlex
import threading

import vessel_runtime_daemon_v16 as v16

core = v16.core
v15 = v16.v15
v14 = v16.v14
v13 = v14.v13
v12 = v14.v12
v11 = v16.v11
PROTOCOL_VERSION = 17
core.PROTOCOL_VERSION = PROTOCOL_VERSION

VNC_UNIX = v14.VNC_UNIX
_original_remote_profile = v16._original_configure_remote_plasma


def configure_remote_plasma_v17(self: core.Runtime) -> None:
    # Keep v14's QML repairs and low-latency KWin settings, then make the
    # workstation/icon tooling explicit instead of trusting a stale v16 marker.
    _original_remote_profile(self)
    cmd = r'''mkdir -p /root/.config
kwriteconfig5 --file /root/.config/baloofilerc --group "Basic Settings" --key Indexing-Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/ksmserverrc --group General --key loginMode emptySession 2>/dev/null || true
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true

# Konsole is Vessel's first pinned workstation app. qdbus-qt5 is required for
# deterministic Plasma panel editing; earlier builds could silently skip this.
if ! command -v konsole >/dev/null 2>&1 || ! command -v qdbus-qt5 >/dev/null 2>&1 || [ ! -d /usr/share/icons/breeze ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
    konsole breeze-icon-theme qdbus-qt5 >/tmp/vessel-workstation-v17.log 2>&1
fi
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kbuildsycoca5 --noincremental >>/tmp/vessel-workstation-v17.log 2>&1 || true
'''
    v11.resilient_guest(self, cmd, 300.0)


def _wait_xserver(self: core.Runtime) -> None:
    script = rf'''import os,time
sock={VNC_UNIX!r}
end=time.monotonic()+15
while time.monotonic()<end:
    if os.path.exists(sock):
        print("XSERVER_READY", flush=True)
        raise SystemExit(0)
    time.sleep(.10)
raise SystemExit(1)
'''
    encoded = base64.b64encode(script.encode()).decode()
    out = v11.resilient_guest(
        self,
        f"printf '%s' {shlex.quote(encoded)} | base64 -d | python3 -",
        18.0,
        attempts=3,
    )
    if "XSERVER_READY" not in out:
        raise RuntimeError("TigerVNC Unix socket did not become ready")


def start_xtigervnc_v17(self: core.Runtime, width: int, height: int, dpi: int) -> None:
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
        "-SecurityTypes None -AlwaysShared "
        ">/tmp/vessel-Xtigervnc.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
    )
    out = v11.resilient_guest(self, launch, 10.0)
    if "XSERVER_LAUNCHED" not in out:
        raise RuntimeError("Could not launch TigerVNC X server")
    _wait_xserver(self)


def start_and_wait_plasma_v17(self: core.Runtime) -> None:
    self.set_progress("vnc_start", 92, "Starting KDE Plasma")
    launch = r'''mkdir -p /tmp/vessel-runtime
chmod 700 /tmp/vessel-runtime
setsid -f sh -c '
  export HOME=/root
  export USER=root
  export LOGNAME=root
  export DISPLAY=:1
  export XDG_RUNTIME_DIR=/tmp/vessel-runtime
  export KWIN_COMPOSE=N
  export QSG_USE_SIMPLE_ANIMATION_DRIVER=1
  unset SESSION_MANAGER
  unset DBUS_SESSION_BUS_ADDRESS
  exec dbus-run-session -- startplasma-x11
' >/tmp/vessel-plasma.log 2>&1 </dev/null
echo PLASMA_LAUNCHED
'''
    out = v11.resilient_guest(self, launch, 10.0)
    if "PLASMA_LAUNCHED" not in out:
        raise RuntimeError("Could not launch KDE Plasma")

    # One Python process performs the wait. Previous versions repeatedly
    # launched bash+pgrep probes, creating unnecessary UML contexts/fds.
    script = r'''import os,time
def names():
    found=set()
    try: entries=os.listdir('/proc')
    except OSError: return found
    for p in entries:
        if not p.isdigit(): continue
        try:
            with open('/proc/'+p+'/comm','r') as f: found.add(f.read().strip())
        except OSError: pass
    return found
end=time.monotonic()+45
while time.monotonic()<end:
    n=names()
    if 'kwin_x11' in n and 'plasmashell' in n:
        print('PLASMA_READY', flush=True)
        raise SystemExit(0)
    time.sleep(.20)
raise SystemExit(1)
'''
    encoded = base64.b64encode(script.encode()).decode()
    try:
        ready = v11.resilient_guest(
            self,
            f"printf '%s' {shlex.quote(encoded)} | base64 -d | python3 -",
            50.0,
            attempts=2,
        )
    except Exception:
        tail = v11.resilient_guest(self, "tail -n 180 /tmp/vessel-plasma.log 2>/dev/null || true", 8.0, attempts=3)
        raise RuntimeError("KDE Plasma failed to become ready. Plasma log:\n" + tail[-8000:])
    if "PLASMA_READY" not in ready:
        raise RuntimeError("KDE Plasma readiness check did not complete")


def polish_panel_v17(self: core.Runtime) -> None:
    # Plasma 5's evaluateScript API is the supported way to edit panel widgets.
    # Do not create the marker unless qdbus really reports success.
    js = r'''var ps = panels();
for (var i = 0; i < ps.length; ++i) {
  var ws = ps[i].widgets();
  for (var j = 0; j < ws.length; ++j) {
    var w = ws[j];
    if (w.type == "org.kde.plasma.icontasks" || w.type == "org.kde.plasma.taskmanager") {
      w.currentConfigGroup = ["General"];
      w.writeConfig("launchers", "applications:org.kde.konsole.desktop");
      if (w.reloadConfig) w.reloadConfig();
    }
  }
}
'''
    encoded = base64.b64encode(js.encode()).decode()
    cmd = rf'''rm -f /root/.vessel-panel-v16
printf '%s' {shlex.quote(encoded)} | base64 -d > /tmp/vessel-panel-v17.js
p=$(pgrep -x plasmashell | head -n1 || true)
[ -n "$p" ] || exit 20
dbus=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n1)
disp=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DISPLAY=//p' | head -n1)
xdg=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n1)
[ -n "$dbus" ] || exit 21
export DBUS_SESSION_BUS_ADDRESS="$dbus"
export DISPLAY="${{disp:-:1}}"
export XDG_RUNTIME_DIR="${{xdg:-/tmp/vessel-runtime}}"
qdbus-qt5 org.kde.plasmashell /PlasmaShell org.kde.PlasmaShell.evaluateScript "$(cat /tmp/vessel-panel-v17.js)" >/tmp/vessel-panel-v17.log 2>&1 || exit 22
kbuildsycoca5 --noincremental >>/tmp/vessel-panel-v17.log 2>&1 || true
touch /root/.vessel-panel-v17
echo PANEL_READY
'''
    try:
        out = v11.resilient_guest(self, cmd, 20.0, attempts=3)
        if "PANEL_READY" not in out:
            raise RuntimeError("Plasma panel script did not confirm success")
    except Exception as exc:
        # Cosmetic panel repair must never kill an otherwise healthy desktop,
        # but because no success marker is written it will retry next attach.
        self.append(f"\n[vessel-ui] panel repair deferred: {exc}\n")
        self.last_error = ""


def desktop_v17(self: core.Runtime, width: int = 1152, height: int = 720, dpi: int = 120):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock

    with lock:
        self.start()
        self.last_error = ""
        width = max(960, min(width, 1152))
        height = max(600, min(height, 720))
        dpi = max(96, min(dpi, 150))

        if v15._warm_desktop_ready(self):
            self.set_progress("desktop_attach", 96, "Attaching to running KDE Plasma")
            v15._ensure_reverse_proxy(self)
            v15.start_persistent_broker_v15(self)
            polish_panel_v17(self)
            self.desktop_ready = True
            self.last_error = ""
            self.set_progress("desktop_ready", 100, "KDE Plasma is live")
            return self.state()

        self.set_progress("desktop_check", 60, "Checking KDE Plasma")
        v12._ensure_desktop_packages(self)
        self.set_progress("desktop_config", 78, "Optimizing desktop")
        configure_remote_plasma_v17(self)
        v14.install_persistent_reverse_helper(self)

        # Host listener first, then X/Plasma, then the persistent reverse broker.
        v15._ensure_reverse_proxy(self)
        start_xtigervnc_v17(self, width, height, dpi)
        start_and_wait_plasma_v17(self)
        v15.start_persistent_broker_v15(self)
        polish_panel_v17(self)

        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


# Patch dynamic call sites and make protocol 17 the active desktop implementation.
v14.configure_remote_plasma = configure_remote_plasma_v17
v16.polish_panel_v16 = polish_panel_v17
core.Runtime.ensure_desktop = desktop_v17

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
