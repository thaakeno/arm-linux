#!/usr/bin/env python3
"""Protocol-14 Vessel runtime.

Protocol 13 finally produced a live Plasma desktop, but the embedded viewer
could still trigger a false fatal error because every Android VNC connection
asked the guest command agent to launch a one-shot reverse helper.  That made
VNC reconnects depend on the command RPC channel and could race with the
short-lived guest agent connection.

Protocol 14 starts one persistent guest-side VNC broker after Plasma is ready.
The broker continuously connects the private Xtigervnc Unix socket to Vessel's
reverse listener. Android VNC reconnects therefore never execute guest
commands. It also applies a remote-desktop Plasma profile: compositing and
animations are disabled to avoid wasting CPU/GPU work that VNC immediately
re-encodes anyway.
"""
from __future__ import annotations

import base64
import shlex
import time

import vessel_runtime_daemon_v13 as v13

core = v13.core
v11 = v13.v11
v12 = v13.v12
PROTOCOL_VERSION = 14
core.PROTOCOL_VERSION = PROTOCOL_VERSION

VNC_UNIX = v13.VNC_UNIX


def install_persistent_reverse_helper(self: core.Runtime) -> None:
    helper = r'''#!/usr/bin/env python3
import select,socket,sys,time
host=sys.argv[1]; port=int(sys.argv[2]); path=sys.argv[3]

def pump(a,b):
    a.setblocking(False); b.setblocking(False)
    while True:
        r,_,_=select.select([a,b],[],[],30)
        if not r:
            continue
        for src,dst in ((a,b),(b,a)):
            if src not in r:
                continue
            try:
                data=src.recv(262144)
            except BlockingIOError:
                continue
            if not data:
                return
            view=memoryview(data)
            while view:
                try:
                    n=dst.send(view)
                except BlockingIOError:
                    select.select([], [dst], [], 1)
                    continue
                view=view[n:]

while True:
    a=b=None
    try:
        # Connect outward first. The host keeps this connection queued until
        # an Android VNC viewer is ready, so no guest command is needed later.
        a=socket.create_connection((host,port),timeout=8)
        a.settimeout(None)
        b=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        deadline=time.monotonic()+15
        while True:
            try:
                b.connect(path)
                break
            except (FileNotFoundError,ConnectionRefusedError):
                if time.monotonic()>=deadline:
                    raise
                time.sleep(0.1)
        pump(a,b)
    except Exception as e:
        print('[vessel-vnc-broker]',type(e).__name__,e,flush=True)
        time.sleep(0.25)
    finally:
        for s in (a,b):
            if s is not None:
                try: s.close()
                except OSError: pass
'''
    encoded = base64.b64encode(helper.encode()).decode()
    v11.resilient_guest(
        self,
        f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel_vnc_reverse_persistent.py; chmod +x /root/vessel_vnc_reverse_persistent.py",
        10.0,
    )


def configure_remote_plasma(self: core.Runtime) -> None:
    # KWin X11 compositing adds latency and causes large continuously-changing
    # regions that are expensive for a remote framebuffer. KDE itself supports
    # disabling compositing under X11, which is ideal for this VNC-backed UI.
    cmd = r'''mkdir -p /root/.config
kwriteconfig5 --file /root/.config/kwinrc --group Compositing --key Enabled false 2>/dev/null || true
kwriteconfig5 --file /root/.config/kdeglobals --group KDE --key AnimationDurationFactor 0 2>/dev/null || true
# Ensure the QML modules used by Kickoff are present. Earlier --no-install-
# recommends installs could leave an otherwise-running shell with broken menu
# pages on partially-upgraded images.
export DEBIAN_FRONTEND=noninteractive
apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends \
  qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels >/tmp/vessel-plasma-qml-fix.log 2>&1 || true
'''
    v11.resilient_guest(self, cmd, 180.0)


def start_persistent_broker(self: core.Runtime) -> None:
    # ReverseVncProxy must already be listening before the broker connects.
    cmd = (
        "pkill -f '[v]essel_vnc_reverse_persistent.py' 2>/dev/null || true; "
        "setsid -f python3 -u /root/vessel_vnc_reverse_persistent.py "
        f"10.0.2.2 {core.VNC_REVERSE_PORT} {VNC_UNIX} "
        ">/tmp/vessel-vnc-broker.log 2>&1 </dev/null; echo VNC_BROKER_LAUNCHED"
    )
    out = v11.resilient_guest(self, cmd, 10.0)
    if "VNC_BROKER_LAUNCHED" not in out:
        raise RuntimeError("Could not launch persistent VNC reverse broker")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        chk = v11.resilient_guest(
            self,
            "pgrep -f '[v]essel_vnc_reverse_persistent.py' >/dev/null && echo BROKER_READY || true",
            5.0,
        )
        if "BROKER_READY" in chk:
            return
        time.sleep(0.2)
    raise RuntimeError("Persistent VNC reverse broker did not stay running")


def connect_pair_without_guest_rpc(self, client):
    """Pair viewer with already-connected guest broker; never touch command RPC."""
    reverse = None
    try:
        assert self.reverse_server is not None
        # The persistent broker normally sits queued here before the viewer
        # connects. A timeout is a viewer transport miss, not a runtime crash.
        reverse, _ = self.reverse_server.accept()
        self._pump_pair(client, reverse)
    except Exception:
        try:
            client.close()
        except OSError:
            pass
        if reverse is not None:
            try:
                reverse.close()
            except OSError:
                pass


def desktop_v14(self: core.Runtime, width: int = 1280, height: int = 800, dpi: int = 120):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        import threading
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock

    with lock:
        self.start()
        width = max(960, min(width, 1920))
        height = max(600, min(height, 1200))
        dpi = max(96, min(dpi, 180))
        self.last_error = ""

        self.set_progress("desktop_check", 60, "Checking KDE Plasma X11 and TigerVNC")
        v12._ensure_desktop_packages(self)

        self.set_progress("desktop_config", 80, "Optimizing Plasma for embedded display")
        configure_remote_plasma(self)
        install_persistent_reverse_helper(self)

        v13.start_xtigervnc_unix(self, width, height, dpi)
        # Force no compositor for this session as well as persistent kwinrc.
        old_start_plasma = v12._start_plasma
        self.set_progress("vnc_start", 94, "Starting KDE Plasma X11 session")
        command = r'''mkdir -p /tmp/vessel-runtime
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
        v11.resilient_guest(self, command, 10.0)

        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            probe = v11.resilient_guest(
                self,
                "x=$(pgrep -f '[X]tigervnc.*:1' || true); "
                "k=$(pgrep -f '[k]win_x11' || true); "
                "p=$(pgrep -f '[p]lasmashell' || true); "
                "printf 'X=%s K=%s P=%s\\n' \"$x\" \"$k\" \"$p\"; "
                "[ -n \"$x\" ] && [ -n \"$k\" ] && [ -n \"$p\" ] && echo PLASMA_READY || true",
                6.0,
            )
            if "PLASMA_READY" in probe:
                break
            time.sleep(0.6)
        else:
            plog = v11.resilient_guest(self, "tail -n 220 /tmp/vessel-plasma.log 2>/dev/null || true", 8.0)
            raise RuntimeError("KDE Plasma X11 failed to become ready. Plasma log:\n" + plog[-9000:])

        if self.vnc_proxy is None:
            self.vnc_proxy = core.ReverseVncProxy(self)
            self.vnc_proxy.start()
        start_persistent_broker(self)

        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


core.ReverseVncProxy._connect_pair = connect_pair_without_guest_rpc
core.Runtime.ensure_desktop = desktop_v14

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
