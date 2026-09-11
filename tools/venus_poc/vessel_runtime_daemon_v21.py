#!/usr/bin/env python3
"""Protocol-21 Vessel runtime.

Cold-boot and workstation hardening for the phone-first Vessel desktop:
* removes the self-SIGTERM Venus relay restart pattern entirely;
* repairs a live UML whose Venus relay vanished instead of treating process-alive as ready;
* provisions Firefox/Kate/Vulkan tools through durable detached guest state;
* keeps protocol-20 panel/icon/uptime improvements;
* raises the default UML memory target from 2 GiB to 4 GiB without requiring a rebuilt kernel.
"""
from __future__ import annotations

import base64
import pathlib
import shlex
import time

import vessel_runtime_daemon_v20 as v20

core = v20.core
v18 = v20.v18
v11 = v20.v11
PROTOCOL_VERSION = 21
core.PROTOCOL_VERSION = PROTOCOL_VERSION


# Keep the proven runner, but give the daily desktop enough memory.  The generated
# copy lives outside the checkout, so updating the branch never dirties the tree.
def _install_runner_v21() -> None:
    original = pathlib.Path(core.RUNNER)
    text = original.read_text()
    needle = "    mem=2048M \\\n"
    replacement = "    mem=\"${VESSEL_MEM_MB:-4096}M\" \\\n"
    if needle not in text:
        raise RuntimeError("Vessel runner memory argument changed unexpectedly")
    target = pathlib.Path("/tmp/vessel-run-venus-v21.sh")
    target.write_text(text.replace(needle, replacement, 1))
    target.chmod(0o700)
    core.RUNNER = target


_install_runner_v21()


def prepare_venus_v21(self: core.Runtime) -> None:
    """Prepare Venus without a pkill command that can kill its own shell.

    Older revisions executed `pkill -f '[g]uest_relay_direct.py'` in the same
    shell command whose argv also contained the replacement relay path.  On the
    target this intermittently SIGTERMed that command (rc=-15) and poisoned the
    following agent RPC.  Stop and start are now separate operations and process
    selection only targets python processes whose real argv contains the relay.
    """
    self.set_progress("venus", 40, "Preparing Mesa Venus relay")
    relay_source = core.GUEST_RELAY_SOURCE
    if not relay_source.exists():
        raise RuntimeError(f"missing guest relay source: {relay_source}")

    payload = base64.b64encode(relay_source.read_bytes()).decode()
    v11.resilient_guest(
        self,
        f"printf '%s' {shlex.quote(payload)} | base64 -d > /root/guest_relay_direct.py",
        15.0,
        attempts=20,
    )
    check = v11.resilient_guest(
        self,
        "test -s /opt/mesa-venus-26.2.2/lib/aarch64-linux-gnu/libvulkan_virtio.so && "
        "test -f /root/virtio-wsi-test.json && echo VENUS_READY",
        10.0,
        attempts=20,
    )
    if "VENUS_READY" not in check:
        raise RuntimeError("Mesa Venus 26.2.2 is not installed in this guest image")

    killer = r'''python3 - <<'PY'
import os, signal, time
for name in os.listdir('/proc'):
    if not name.isdigit():
        continue
    pid=int(name)
    if pid in (1, os.getpid(), os.getppid()):
        continue
    try:
        comm=open(f'/proc/{pid}/comm').read().strip()
        cmd=open(f'/proc/{pid}/cmdline','rb').read()
    except OSError:
        continue
    if comm.startswith('python') and b'/root/guest_relay_direct.py' in cmd:
        try: os.kill(pid, signal.SIGTERM)
        except ProcessLookupError: pass
end=time.monotonic()+1.2
while time.monotonic()<end:
    alive=False
    for name in os.listdir('/proc'):
        if not name.isdigit(): continue
        try:
            comm=open(f'/proc/{name}/comm').read().strip()
            cmd=open(f'/proc/{name}/cmdline','rb').read()
        except OSError:
            continue
        if comm.startswith('python') and b'/root/guest_relay_direct.py' in cmd:
            alive=True; break
    if not alive: break
    time.sleep(.05)
PY
rm -f /tmp/.venus_test /tmp/vessel-guest-relay.log /tmp/vessel-guest-relay.pid
true'''
    v11.resilient_guest(self, killer, 8.0, attempts=12)

    launcher = r'''nohup python3 /root/guest_relay_direct.py --host 10.0.2.2 --port 5002 --unix /tmp/.venus_test \
  >/tmp/vessel-guest-relay.log 2>&1 </dev/null &
echo $! >/tmp/vessel-guest-relay.pid
echo RELAY_LAUNCHED
true'''
    launched = v11.resilient_guest(self, launcher, 8.0, attempts=12)
    if "RELAY_LAUNCHED" not in launched:
        raise RuntimeError("Venus guest relay launch was not acknowledged")

    deadline = time.monotonic() + 15.0
    while time.monotonic() < deadline:
        out = v11.resilient_guest(
            self,
            "if [ -S /tmp/.venus_test ]; then echo RELAY_READY; else echo RELAY_WAIT; fi",
            4.0,
            attempts=10,
        )
        if "RELAY_READY" in out:
            self.last_error = ""
            self.set_progress("venus", 55, "Venus relay ready")
            return
        time.sleep(0.15)

    tail = ""
    try:
        tail = v11.resilient_guest(self, "tail -n 160 /tmp/vessel-guest-relay.log 2>/dev/null || true", 6.0, attempts=6)
    except Exception:
        pass
    raise RuntimeError("Venus relay did not become ready" + (": " + tail[-5000:] if tail else ""))


_previous_start = core.Runtime.start


def start_v21(self: core.Runtime, timeout: float = 75.0):
    state = _previous_start(self, timeout)
    if self.proc is None or self.proc.poll() is not None or not self.guest_ready:
        return state
    try:
        probe = v11.resilient_guest(
            self,
            "if [ -S /tmp/.venus_test ]; then echo RELAY_READY; else echo RELAY_MISSING; fi",
            4.0,
            attempts=6,
        )
    except Exception:
        probe = "RELAY_MISSING"
    if "RELAY_READY" not in probe:
        prepare_venus_v21(self)
    self.last_error = ""
    return self.state()


def _workstation_ready(self: core.Runtime) -> bool:
    cmd = (
        "command -v konsole >/dev/null 2>&1 && command -v dolphin >/dev/null 2>&1 && "
        "command -v systemsettings5 >/dev/null 2>&1 && command -v firefox-esr >/dev/null 2>&1 && "
        "command -v kate >/dev/null 2>&1 && command -v vulkaninfo >/dev/null 2>&1 && "
        "command -v vkcube >/dev/null 2>&1 && test -d /usr/share/icons/breeze"
    )
    try:
        v11.resilient_guest(self, cmd, 7.0, attempts=8)
        return True
    except Exception as exc:
        text = str(exc).lower()
        if "rc=1" in text or "rc=127" in text:
            return False
        raise


def _apply_workstation_preferences(self: core.Runtime) -> None:
    v11.resilient_guest(
        self,
        r'''mkdir -p /root/.config
kwriteconfig5 --file /root/.config/kdeglobals --group Icons --key Theme breeze 2>/dev/null || true
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
kwriteconfig5 --file /root/.config/baloofilerc --group "Basic Settings" --key Indexing-Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/ksmserverrc --group General --key loginMode emptySession 2>/dev/null || true
balooctl disable >/dev/null 2>&1 || true
echo WORKSTATION_READY''',
        12.0,
        attempts=8,
    )


def workstation_profile_v21(self: core.Runtime) -> None:
    """Provision workstation apps durably instead of keeping one RPC open."""
    if _workstation_ready(self):
        _apply_workstation_preferences(self)
        self.append("\n[vessel-workstation] Firefox, Kate and Vulkan tools already ready\n")
        return

    script = r'''cat > /tmp/vessel-workstation-v21.sh <<'VSL_WORK'
#!/bin/sh
STATUS=/tmp/vessel-workstation-v21.status
LOG=/tmp/vessel-workstation-v21.log
printf 'RUNNING\n' > "$STATUS"
trap 'rc=$?; printf "%s\n" "$rc" > "$STATUS"' EXIT
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a
apt-get -o DPkg::Lock::Timeout=120 update
apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
  konsole dolphin systemsettings firefox-esr kate \
  vulkan-tools mesa-utils \
  breeze-icon-theme hicolor-icon-theme shared-mime-info desktop-file-utils
update-desktop-database /usr/share/applications || true
kbuildsycoca5 --noincremental || true
touch /root/.vessel-workstation-v21
VSL_WORK
chmod 700 /tmp/vessel-workstation-v21.sh
s=$(cat /tmp/vessel-workstation-v21.status 2>/dev/null || true)
if [ "$s" != RUNNING ]; then
  rm -f /tmp/vessel-workstation-v21.status
  setsid -f sh -c '/tmp/vessel-workstation-v21.sh >/tmp/vessel-workstation-v21.log 2>&1' </dev/null >/dev/null 2>&1 || true
fi
echo WORKSTATION_INSTALL_ACTIVE
true'''
    v11.resilient_guest(self, script, 10.0, attempts=12)

    deadline = time.monotonic() + 12 * 60
    while time.monotonic() < deadline:
        if _workstation_ready(self):
            _apply_workstation_preferences(self)
            self.last_error = ""
            self.append("\n[vessel-workstation] workstation profile ready\n")
            return
        status = ""
        try:
            status = v11.resilient_guest(
                self,
                "cat /tmp/vessel-workstation-v21.status 2>/dev/null || echo MISSING",
                5.0,
                attempts=6,
            ).strip().splitlines()[-1]
        except Exception:
            pass
        if status not in {"", "MISSING", "RUNNING", "0"}:
            tail = v11.resilient_guest(self, "tail -n 160 /tmp/vessel-workstation-v21.log 2>/dev/null || true", 7.0, attempts=6)
            raise RuntimeError(f"Workstation package setup exited rc={status}: {tail[-7000:]}")
        time.sleep(0.8)

    tail = v11.resilient_guest(self, "tail -n 180 /tmp/vessel-workstation-v21.log 2>/dev/null || true", 8.0, attempts=6)
    raise TimeoutError("Workstation package setup exceeded 12 minutes: " + tail[-7000:])


core.Runtime._prepare_venus_guest = prepare_venus_v21
core.Runtime.start = start_v21
v18.workstation_profile_v18 = workstation_profile_v21
core.Runtime.ensure_desktop = v18.desktop_v18

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
