#!/usr/bin/env python3
"""Protocol-16 Vessel runtime.

Protocol 15 fixed the broker SIGTERM loop. Protocol 16 finishes the first usable
embedded desktop experience:
* transient command-channel errors are hidden from status while a desktop start
  is actively recovering, preventing red error-card flicker during successful
  startup;
* Konsole and the Breeze icon theme are installed once as the default useful
  workstation app;
* KDE's service/icon cache is rebuilt after minimal package installs;
* the Plasma task manager is rewritten through the supported Plasma scripting
  API so stale launchers for applications that are not installed no longer show
  as blank generic icons; Konsole is pinned instead;
* Baloo indexing, session restore, compositing and desktop animations are kept
  out of the phone VM path so warm starts stay lightweight;
* the persistent VNC broker and Hextile framebuffer transport remain unchanged.
"""
from __future__ import annotations

import base64
import shlex

import vessel_runtime_daemon_v15 as v15

core = v15.core
v14 = v15.v14
v11 = v15.v11
PROTOCOL_VERSION = 16
core.PROTOCOL_VERSION = PROTOCOL_VERSION

_original_state = core.Runtime.state
_original_configure_remote_plasma = v14.configure_remote_plasma


def _is_transient(text: str) -> bool:
    t = text.lower()
    return any(x in t for x in (
        "rc=-15", "signal 15", "sigterm", "socket", "channel",
        "connection", "broken pipe", "timed out", "timeout",
    ))


def state_v16(self: core.Runtime):
    """Never flash a fatal UI state for an error the desktop path is retrying."""
    out = _original_state(self)
    err = str(out.get("lastError") or "")
    phase = str(out.get("progressPhase") or "")
    if out.get("running") and err and _is_transient(err) and (
        phase.startswith("desktop") or phase in {"vnc_start", "desktop_attach"}
    ):
        out["lastError"] = ""
    return out


def configure_remote_plasma_v16(self: core.Runtime) -> None:
    _original_configure_remote_plasma(self)
    cmd = r'''mkdir -p /root/.config
# Phone/remote desktop profile: no expensive background indexer or session restore.
kwriteconfig5 --file /root/.config/baloofilerc --group "Basic Settings" --key Indexing-Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/ksmserverrc --group General --key loginMode emptySession 2>/dev/null || true
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true

# Minimal Plasma was intentionally installed without recommends. Add exactly one
# useful workstation app and repair the icon/service cache once.
if [ ! -f /root/.vessel-workstation-v16 ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
    konsole breeze-icon-theme >/tmp/vessel-workstation-v16.log 2>&1
  kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
  kbuildsycoca5 --noincremental >>/tmp/vessel-workstation-v16.log 2>&1 || true
  touch /root/.vessel-workstation-v16
fi
'''
    v11.resilient_guest(self, cmd, 240.0)


def polish_panel_v16(self: core.Runtime) -> None:
    """Remove stale default launchers and pin Konsole via Plasma's scripting API."""
    js = r'''var ps = panels();
for (var i = 0; i < ps.length; ++i) {
  var ws = ps[i].widgets();
  for (var j = 0; j < ws.length; ++j) {
    var w = ws[j];
    if (w.type == "org.kde.plasma.icontasks" || w.type == "org.kde.plasma.taskmanager") {
      w.currentConfigGroup = ["General"];
      w.writeConfig("launchers", "applications:org.kde.konsole.desktop");
      w.writeConfig("launchers", "applications:org.kde.konsole.desktop");
      if (w.reloadConfig) w.reloadConfig();
    }
  }
}
'''
    encoded = base64.b64encode(js.encode()).decode()
    command = rf'''if [ ! -f /root/.vessel-panel-v16 ]; then
  printf '%s' {shlex.quote(encoded)} | base64 -d > /tmp/vessel-panel-v16.js
  p=$(pgrep -x plasmashell | head -n1 || true)
  if [ -n "$p" ]; then
    dbus=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n1)
    disp=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DISPLAY=//p' | head -n1)
    xdg=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n1)
    if [ -n "$dbus" ]; then
      export DBUS_SESSION_BUS_ADDRESS="$dbus"
      export DISPLAY="${{disp:-:1}}"
      export XDG_RUNTIME_DIR="${{xdg:-/tmp/vessel-runtime}}"
      if command -v qdbus >/dev/null 2>&1; then
        qdbus org.kde.plasmashell /PlasmaShell org.kde.PlasmaShell.evaluateScript "$(cat /tmp/vessel-panel-v16.js)" >/tmp/vessel-panel-v16.log 2>&1 || true
      elif command -v qdbus-qt5 >/dev/null 2>&1; then
        qdbus-qt5 org.kde.plasmashell /PlasmaShell org.kde.PlasmaShell.evaluateScript "$(cat /tmp/vessel-panel-v16.js)" >/tmp/vessel-panel-v16.log 2>&1 || true
      fi
    fi
  fi
  kbuildsycoca5 --noincremental >>/tmp/vessel-panel-v16.log 2>&1 || true
  touch /root/.vessel-panel-v16
fi
'''
    try:
        v11.resilient_guest(self, command, 20.0, attempts=4)
    except Exception as exc:
        self.append(f"\n[vessel-ui] panel polish skipped: {exc}\n")
        self.last_error = ""


def desktop_v16(self: core.Runtime, width: int = 1280, height: int = 800, dpi: int = 120):
    self.last_error = ""
    result = v15.desktop_v15(self, width, height, dpi)
    polish_panel_v16(self)
    self.desktop_ready = True
    self.last_error = ""
    self.set_progress("desktop_ready", 100, "KDE Plasma is live")
    return self.state()


core.Runtime.state = state_v16
v14.configure_remote_plasma = configure_remote_plasma_v16
core.Runtime.ensure_desktop = desktop_v16

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
