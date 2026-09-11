#!/usr/bin/env python3
"""Protocol-22 Vessel runtime.

Phone-desktop latency, app-launch and networking hardening:
* keeps protocol-21 Venus lifecycle fixes;
* uses 8 GiB by default and disables the unused host Termux:X11 path;
* configures vec0 networking/DNS explicitly inside the UML guest;
* launches desktop apps through short detached RPCs with a fixed :1 X display;
* installs Okular and KCalc alongside Firefox/Kate/Vulkan tools;
* starts TigerVNC at 60 fps and trims cold desktop readiness work;
* makes Plasma panel polishing fully asynchronous/non-fatal.
"""
from __future__ import annotations

import pathlib
import shlex
import threading
import time

import vessel_runtime_daemon_v21 as v21

core = v21.core
v20 = v21.v20
v18 = v21.v18
v17 = v18.v17
v15 = v17.v15
v11 = v21.v11
PROTOCOL_VERSION = 22
core.PROTOCOL_VERSION = PROTOCOL_VERSION


# Regenerate the runner from the checked-out source script instead of trying to
# discover the repository through the base daemon module. Protocol 21 mutates
# core.RUNNER to point at its generated cache copy during import, and the base
# module does not expose POC_DIR. Using this file's own directory is stable in
# both Termux worktrees and CI.
def _install_runner_v22() -> None:
    source = pathlib.Path(__file__).resolve().parent / "run_venus_uml.sh"
    text = source.read_text()
    if "mem=2048M" not in text:
        raise RuntimeError("Vessel runner memory argument changed unexpectedly")
    text = text.replace("mem=2048M", 'mem="${VESSEL_MEM_MB:-8192}M"', 1)
    text = text.replace('ENABLE_X11="${ENABLE_X11:-1}"', 'ENABLE_X11="${ENABLE_X11:-0}"', 1)
    cache = pathlib.Path.home() / ".cache" / "vessel"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / "vessel-run-venus-v22.sh"
    temp = cache / "vessel-run-venus-v22.sh.tmp"
    temp.write_text(text)
    temp.chmod(0o700)
    temp.replace(target)
    core.RUNNER = target


_install_runner_v22()


def network_v22(self: core.Runtime) -> bool:
    """Make umnet/passt useful even though /umarm-init does no DHCP."""
    cmd = r'''set +e
ip link set vec0 up 2>/dev/null
ip addr replace 10.0.2.15/24 dev vec0 2>/dev/null
ip route replace default via 10.0.2.2 dev vec0 2>/dev/null
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:1 attempts:2\n' >/etc/resolv.conf
if getent ahostsv4 deb.debian.org >/dev/null 2>&1; then
  echo NETWORK_READY
else
  echo NETWORK_CONFIGURED
fi
true'''
    try:
        out = v11.resilient_guest(self, cmd, 8.0, attempts=8)
        ready = "NETWORK_READY" in out
        self.append("\n[vessel-network] " + ("internet/DNS ready" if ready else "vec0 configured; DNS probe pending") + "\n")
        return ready
    except Exception as exc:
        self.append(f"\n[vessel-network] deferred: {exc}\n")
        self.last_error = ""
        return False


_previous_start = core.Runtime.start


def start_v22(self: core.Runtime, timeout: float = 75.0):
    state = _previous_start(self, timeout)
    if self.proc is not None and self.proc.poll() is None and self.guest_ready:
        network_v22(self)
    self.last_error = ""
    return self.state()


core.Runtime.start = start_v22


def _workstation_ready_v22(self: core.Runtime) -> bool:
    cmd = (
        "command -v konsole >/dev/null 2>&1 && command -v dolphin >/dev/null 2>&1 && "
        "command -v systemsettings5 >/dev/null 2>&1 && command -v firefox-esr >/dev/null 2>&1 && "
        "command -v kate >/dev/null 2>&1 && command -v vulkaninfo >/dev/null 2>&1 && "
        "command -v vkcube >/dev/null 2>&1 && command -v okular >/dev/null 2>&1 && "
        "command -v kcalc >/dev/null 2>&1 && test -d /usr/share/icons/breeze"
    )
    try:
        v11.resilient_guest(self, cmd, 6.0, attempts=5)
        return True
    except Exception as exc:
        if "rc=1" in str(exc).lower() or "rc=127" in str(exc).lower():
            return False
        raise


def _apply_profile_v22(self: core.Runtime) -> None:
    cmd = r'''mkdir -p /root/.config /dev/shm
mountpoint -q /dev/shm || mount -t tmpfs -o size=768m tmpfs /dev/shm 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group General --key ColorScheme BreezeDark 2>/dev/null || true
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
kwriteconfig5 --file /root/.config/baloofilerc --group "Basic Settings" --key Indexing-Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/ksmserverrc --group General --key loginMode emptySession 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true
# Keep Qt raster work cheap for the single-vCPU UML guest.
kwriteconfig5 --file /root/.config/kwinrc --group Xwayland --key Scale 1 2>/dev/null || true
update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
kbuildsycoca5 --noincremental >/dev/null 2>&1 || true
touch /root/.vessel-workstation-v22
echo WORKSTATION_READY'''
    v11.resilient_guest(self, cmd, 45.0, attempts=10)


def workstation_profile_v22(self: core.Runtime) -> None:
    if _workstation_ready_v22(self):
        _apply_profile_v22(self)
        self.append("\n[vessel-workstation] Firefox, Kate and Vulkan tools already ready\n")
        return
    install = (
        "DEBIAN_FRONTEND=noninteractive apt-get update && "
        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "
        "konsole dolphin systemsettings firefox-esr kate breeze-icon-theme hicolor-icon-theme "
        "shared-mime-info desktop-file-utils vulkan-tools mesa-utils okular kcalc"
    )
    v11.resilient_guest(self, install, 900.0, attempts=8)
    _apply_profile_v22(self)


v18.workstation_profile_v18 = workstation_profile_v22


_old_start_vnc = v18.start_xtigervnc_v18


def start_xtigervnc_v22(self: core.Runtime, width: int, height: int, dpi: int):
    width = max(960, min(int(width), 1152))
    height = max(540, min(int(height), 720))
    return _old_start_vnc(self, width, height, dpi)


v18.start_xtigervnc_v18 = start_xtigervnc_v22


def _desktop_env_prefix() -> str:
    return (
        "export DISPLAY=:1; "
        "export XDG_RUNTIME_DIR=/tmp/runtime-root; mkdir -p /tmp/runtime-root; chmod 700 /tmp/runtime-root; "
        "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; "
        "export VTEST_SOCKET_NAME=/tmp/.venus_test; export VK_DRIVER_FILES=/root/virtio-wsi-test.json; "
    )


def desktop_action_v22(self: core.Runtime, name: str):
    actions = {
        "firefox": _desktop_env_prefix() + "setsid -f firefox-esr --no-remote >/tmp/vessel-firefox.log 2>&1 </dev/null; echo FIREFOX_LAUNCHED",
        "vulkan3d": _desktop_env_prefix() + "(vulkaninfo --summary >/tmp/vessel-vulkaninfo.log 2>&1 || true); setsid -f vkcube >/tmp/vessel-vkcube.log 2>&1 </dev/null; echo VKCUBE_LAUNCHED",
        "dolphin": _desktop_env_prefix() + "setsid -f dolphin >/tmp/vessel-dolphin.log 2>&1 </dev/null; echo DOLPHIN_LAUNCHED",
        "kate": _desktop_env_prefix() + "setsid -f kate >/tmp/vessel-kate.log 2>&1 </dev/null; echo KATE_LAUNCHED",
        "okular": _desktop_env_prefix() + "setsid -f okular >/tmp/vessel-okular.log 2>&1 </dev/null; echo OKULAR_LAUNCHED",
        "kcalc": _desktop_env_prefix() + "setsid -f kcalc >/tmp/vessel-kcalc.log 2>&1 </dev/null; echo KCALC_LAUNCHED",
    }
    command = actions.get(name)
    if command is None:
        raise RuntimeError(f"unknown desktop action: {name}")
    out = v11.resilient_guest(self, command, 8.0, attempts=8)
    self.append(f"\n[vessel-action] {name}: {out.strip()}\n")
    self.last_error = ""
    return self.state()


core.Runtime.desktop_action = desktop_action_v22


if __name__ == "__main__":
    core.main()
