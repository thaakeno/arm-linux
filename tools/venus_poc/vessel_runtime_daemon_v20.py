#!/usr/bin/env python3
"""Protocol-20 Vessel runtime.

Stabilizes the now-working Plasma desktop for normal phone use:
* keeps protocol-19 resilient Venus and TigerVNC startup;
* makes workstation provisioning one-time instead of rebuilding KDE caches on
  every cold desktop boot;
* installs Firefox ESR and Kate alongside Konsole/Dolphin/System Settings;
* updates taskbar launchers without blocking desktop readiness on a plasmashell
  restart, eliminating the false fatal command-channel timeout at ~97%;
* adds concise elapsed-time progress markers to the runtime log.
"""
from __future__ import annotations

import base64
import shlex

import vessel_runtime_daemon_v19 as v19

core = v19.core
v18 = v19.v18
v17 = v18.v17
v11 = v19.v11
PROTOCOL_VERSION = 20
core.PROTOCOL_VERSION = PROTOCOL_VERSION


# Clean, human-readable timing markers in the live log. Runtime.state() already
# exposes uptimeMs; these markers make the same timing visible in copied logs.
_original_set_progress = core.Runtime.set_progress


def set_progress_v20(self: core.Runtime, phase: str, percent: int, detail: str) -> None:
    previous = (self.progress_phase, self.progress_percent, self.progress_detail)
    _original_set_progress(self, phase, percent, detail)
    current = (self.progress_phase, self.progress_percent, self.progress_detail)
    if current == previous:
        return
    elapsed_ms = core.now_ms() - self.started_ms if self.started_ms else 0
    pct = f"{self.progress_percent}%" if self.progress_percent >= 0 else "--"
    self.append(f"\n[vessel +{elapsed_ms / 1000.0:05.1f}s] {pct} {self.progress_detail}\n")


core.Runtime.set_progress = set_progress_v20


def workstation_profile_v20(self: core.Runtime) -> None:
    """Install workstation apps once and keep later cold boots cheap."""
    cmd = r'''mkdir -p /root/.config
export DEBIAN_FRONTEND=noninteractive
need=0
command -v konsole >/dev/null 2>&1 || need=1
command -v dolphin >/dev/null 2>&1 || need=1
command -v systemsettings5 >/dev/null 2>&1 || need=1
command -v firefox-esr >/dev/null 2>&1 || need=1
command -v kate >/dev/null 2>&1 || need=1
[ -d /usr/share/icons/breeze ] || need=1
if [ "$need" -ne 0 ]; then
  apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
    konsole dolphin systemsettings firefox-esr kate \
    breeze-icon-theme hicolor-icon-theme shared-mime-info desktop-file-utils \
    >/tmp/vessel-workstation-v20.log 2>&1
fi
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
kwriteconfig5 --file /root/.config/baloofilerc --group "Basic Settings" --key Indexing-Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/ksmserverrc --group General --key loginMode emptySession 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true
if [ ! -f /root/.vessel-workstation-v20 ]; then
  update-desktop-database /usr/share/applications >>/tmp/vessel-workstation-v20.log 2>&1 || true
  kbuildsycoca5 --noincremental >>/tmp/vessel-workstation-v20.log 2>&1 || true
  touch /root/.vessel-workstation-v20
fi
printf 'WORKSTATION_READY\n'
'''
    out = v11.resilient_guest(self, cmd, 900.0, attempts=8)
    if "WORKSTATION_READY" not in out:
        raise RuntimeError("Vessel workstation profile did not complete")


def repair_panel_v20(self: core.Runtime) -> None:
    """Pin useful apps without making a cosmetic panel restart fatal.

    Protocol 18 restarted plasmashell synchronously from the command-agent RPC.
    On the phone this could leave the RPC waiting for its five-byte record header
    even though X11, KWin, Plasma and the VNC broker were already healthy. The
    desktop then looked stuck at 97%, and a second attach immediately worked.

    Here config editing is quick and authoritative. If a live panel needs a
    refresh, the refresh is detached from the command channel. Failure to polish
    the panel is cosmetic and must never demote an otherwise-live desktop.
    """
    py = r'''from pathlib import Path
import time
p=Path('/root/.config/plasma-org.kde.plasma.desktop-appletsrc')
for _ in range(30):
    if p.exists() and 'launchers=' in p.read_text(errors='replace'):
        break
    time.sleep(.1)
if not p.exists():
    print('PANEL_CONFIG_MISSING')
    raise SystemExit(0)
text=p.read_text(errors='replace')
apps='applications:org.kde.konsole.desktop,applications:org.kde.dolphin.desktop,applications:firefox-esr.desktop,applications:org.kde.kate.desktop,applications:systemsettings.desktop'
out=[]
changed=0
for line in text.splitlines():
    if line.startswith('launchers='):
        wanted='launchers='+apps
        if line != wanted:
            line=wanted
            changed+=1
    out.append(line)
p.write_text('\n'.join(out)+'\n')
print('PANEL_REWRITTEN', changed)
Path('/root/.vessel-panel-v20').touch()
'''
    encoded = base64.b64encode(py.encode()).decode()
    cmd = rf'''printf '%s' {shlex.quote(encoded)} | base64 -d | python3 - | tee /tmp/vessel-panel-v20.result
changed=$(awk '/PANEL_REWRITTEN/ {{print $2}}' /tmp/vessel-panel-v20.result | tail -n1)
if [ -n "$changed" ] && [ "$changed" != "0" ]; then
  p=$(pgrep -x plasmashell | head -n1 || true)
  if [ -n "$p" ]; then
    dbus=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n1)
    disp=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^DISPLAY=//p' | head -n1)
    xdg=$(tr '\0' '\n' < /proc/$p/environ | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n1)
    cat > /tmp/vessel-refresh-panel-v20.sh <<EOF
#!/bin/sh
sleep .35
export DBUS_SESSION_BUS_ADDRESS='$dbus'
export DISPLAY='${{disp:-:1}}'
export XDG_RUNTIME_DIR='${{xdg:-/tmp/vessel-runtime}}'
kquitapp5 plasmashell >/dev/null 2>&1 || true
sleep .25
kstart5 plasmashell >/tmp/vessel-plasmashell-v20.log 2>&1 || true
EOF
    chmod 700 /tmp/vessel-refresh-panel-v20.sh
    setsid -f /tmp/vessel-refresh-panel-v20.sh </dev/null >/dev/null 2>&1
  fi
fi
echo PANEL_READY
true'''
    try:
        out = v11.resilient_guest(self, cmd, 12.0, attempts=4)
        if "PANEL_READY" not in out:
            raise RuntimeError("panel update did not confirm completion")
    except Exception as exc:
        self.append(f"\n[vessel-ui] panel polish deferred: {exc}\n")
        self.last_error = ""


def wait_panel_v20(self: core.Runtime) -> None:
    # The panel refresh is deliberately detached. KWin/Xtigervnc remain live,
    # so waiting on a cosmetic plasmashell restart only adds latency and another
    # command-channel failure surface.
    return


# Dynamic call sites inside desktop_v18 resolve these globals at runtime.
v18.workstation_profile_v18 = workstation_profile_v20
v18.repair_panel_v18 = repair_panel_v20
v18.wait_plasmashell_after_repair = wait_panel_v20
core.Runtime.ensure_desktop = v18.desktop_v18

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
