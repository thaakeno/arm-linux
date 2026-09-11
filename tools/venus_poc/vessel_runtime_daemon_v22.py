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


# Regenerate the runner from the original script instead of re-patching v21's
# generated copy. Termux:X11 is not used by the embedded TigerVNC desktop and
# starting it costs processes/fds/CPU on every boot.
def _install_runner_v22() -> None:
    source = pathlib.Path(v21.core.POC_DIR) / "tools" / "venus_poc" / "run_venus_uml.sh"
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
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key ShowDeleteCommand false 2>/dev/null || true
echo WORKSTATION_READY'''
    v11.resilient_guest(self, cmd, 10.0, attempts=6)


def workstation_profile_v22(self: core.Runtime) -> None:
    network_v22(self)
    if _workstation_ready_v22(self):
        _apply_profile_v22(self)
        self.append("\n[vessel-workstation] protocol22 workstation ready\n")
        return

    launcher = r'''cat >/tmp/vessel-workstation-v22.sh <<'VSL'
#!/bin/sh
status=/tmp/vessel-workstation-v22.status
printf 'RUNNING\n' >"$status"
trap 'rc=$?; printf "%s\n" "$rc" >"$status"' EXIT
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a
apt-get -o DPkg::Lock::Timeout=120 update
apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
  konsole dolphin systemsettings firefox-esr kate okular kcalc \
  vulkan-tools mesa-utils breeze-icon-theme hicolor-icon-theme \
  shared-mime-info desktop-file-utils ca-certificates
update-desktop-database /usr/share/applications || true
kbuildsycoca5 --noincremental || true
touch /root/.vessel-workstation-v22
VSL
chmod 700 /tmp/vessel-workstation-v22.sh
s=$(cat /tmp/vessel-workstation-v22.status 2>/dev/null || true)
if [ "$s" != RUNNING ]; then
  rm -f /tmp/vessel-workstation-v22.status
  setsid -f sh -c '/tmp/vessel-workstation-v22.sh >/tmp/vessel-workstation-v22.log 2>&1' </dev/null >/dev/null 2>&1 || true
fi
echo WORKSTATION_INSTALL_ACTIVE
true'''
    v11.resilient_guest(self, launcher, 8.0, attempts=8)
    deadline = time.monotonic() + 12 * 60
    while time.monotonic() < deadline:
        if _workstation_ready_v22(self):
            _apply_profile_v22(self)
            self.last_error = ""
            return
        try:
            status = v11.resilient_guest(
                self,
                "cat /tmp/vessel-workstation-v22.status 2>/dev/null || echo MISSING",
                4.0,
                attempts=4,
            ).strip().splitlines()[-1]
            if status not in {"", "MISSING", "RUNNING", "0"}:
                tail = v11.resilient_guest(self, "tail -n 120 /tmp/vessel-workstation-v22.log 2>/dev/null || true", 6.0, attempts=4)
                raise RuntimeError(f"Workstation setup exited rc={status}: {tail[-5000:]}")
        except RuntimeError:
            raise
        except Exception:
            pass
        time.sleep(0.7)
    raise TimeoutError("Workstation package setup exceeded 12 minutes")


v18.workstation_profile_v18 = workstation_profile_v22


def start_xtigervnc_v22(self: core.Runtime, width: int, height: int, dpi: int) -> None:
    self.set_progress("vnc_start", 86, "Starting low-latency X server")
    cleanup = (
        "pkill -f '[X]tigervnc.*:1' 2>/dev/null || true; "
        "pkill -f '[s]tartplasma-x11' 2>/dev/null || true; "
        "pkill -f '[k]win_x11' 2>/dev/null || true; "
        "pkill -f '[p]lasmashell' 2>/dev/null || true; "
        f"rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 {v18.VNC_UNIX} /tmp/vessel-Xtigervnc.log"
    )
    v11.resilient_guest(self, cleanup, 10.0, attempts=5)
    # 1152x720 is deliberate: rendering/decoding a phone-sized framebuffer is
    # substantially cheaper than 1080p in ptrace UML and still sharp on mobile.
    width = max(960, min(width, 1152))
    height = max(600, min(height, 720))
    dpi = max(96, min(dpi, 140))
    launch = (
        "setsid -f Xtigervnc :1 "
        f"-geometry {width}x{height} -depth 24 -dpi {dpi} "
        f"-rfbport -1 -rfbunixpath {v18.VNC_UNIX} -rfbunixmode 0600 "
        "-SecurityTypes None -AlwaysShared -AcceptPointerEvents=1 -AcceptKeyEvents=1 "
        "-FrameRate 60 -CompareFB 2 "
        ">/tmp/vessel-Xtigervnc.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
    )
    out = v11.resilient_guest(self, launch, 8.0, attempts=5)
    if "XSERVER_LAUNCHED" not in out:
        raise RuntimeError("Could not launch TigerVNC X server")
    v17._wait_xserver(self)


v18.start_xtigervnc_v18 = start_xtigervnc_v22


def _panel_v22(self: core.Runtime) -> None:
    """Edit launchers once; never restart Plasma on the critical path."""
    py = r'''from pathlib import Path
p=Path('/root/.config/plasma-org.kde.plasma.desktop-appletsrc')
if not p.exists():
    print('PANEL_CONFIG_MISSING'); raise SystemExit(0)
apps='applications:org.kde.konsole.desktop,applications:org.kde.dolphin.desktop,applications:firefox-esr.desktop,applications:org.kde.kate.desktop,applications:okular.desktop,applications:systemsettings.desktop'
text=p.read_text(errors='replace'); out=[]; changed=0
for line in text.splitlines():
    if line.startswith('launchers='):
        wanted='launchers='+apps
        if line != wanted: line=wanted; changed+=1
    out.append(line)
p.write_text('\n'.join(out)+'\n')
Path('/root/.vessel-panel-v22').touch()
print('PANEL_REWRITTEN', changed)
'''
    import base64
    encoded = base64.b64encode(py.encode()).decode()
    try:
        v11.resilient_guest(self, f"printf '%s' {shlex.quote(encoded)} | base64 -d | python3 -", 6.0, attempts=3)
    except Exception as exc:
        self.append(f"\n[vessel-ui] panel update deferred: {exc}\n")
        self.last_error = ""


v18.repair_panel_v18 = _panel_v22
v18.wait_plasmashell_after_repair = lambda self: None


def launch_desktop_action_v22(self: core.Runtime, action: str):
    """Short app-launch RPCs: never run GUI programs or vulkaninfo inline."""
    if action == "firefox":
        body = r'''export DISPLAY=:1
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VK_DRIVER_FILES=/root/virtio-wsi-test.json
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
mkdir -p /dev/shm
setsid -f sh -c 'firefox-esr --new-window about:blank >/tmp/vessel-firefox.log 2>&1' </dev/null >/dev/null 2>&1
echo FIREFOX_LAUNCHED'''
    elif action == "vulkan":
        body = r'''export DISPLAY=:1
export XDG_RUNTIME_DIR=/tmp/vessel-runtime
[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VK_DRIVER_FILES=/root/virtio-wsi-test.json
setsid -f sh -c 'echo "[vessel-3d] $(date -Iseconds)" >/tmp/vessel-vulkan-test.log; vulkaninfo --summary >>/tmp/vessel-vulkan-test.log 2>&1; exec vkcube >>/tmp/vessel-vulkan-test.log 2>&1' </dev/null >/dev/null 2>&1
echo VKCUBE_LAUNCHED'''
    elif action == "okular":
        body = "export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/vessel-runtime; setsid -f okular >/tmp/vessel-okular.log 2>&1 </dev/null; echo OKULAR_LAUNCHED"
    elif action == "kcalc":
        body = "export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/vessel-runtime; setsid -f kcalc >/tmp/vessel-kcalc.log 2>&1 </dev/null; echo KCALC_LAUNCHED"
    else:
        raise ValueError(f"unknown desktop action: {action}")
    out = v11.resilient_guest(self, body, 6.0, attempts=8)
    self.last_error = ""
    return out


core.Runtime.launch_desktop_action_v22 = launch_desktop_action_v22

# Make the desktop authoritative as soon as X/KWin/plasmashell and the broker
# are live. Panel cosmetics are intentionally not a readiness dependency.
_original_desktop = v18.desktop_v18


def desktop_v22(self: core.Runtime, width: int = 1152, height: int = 720, dpi: int = 120):
    result = _original_desktop(self, width, height, dpi)
    self.last_error = ""
    return result


core.Runtime.ensure_desktop = desktop_v22

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
