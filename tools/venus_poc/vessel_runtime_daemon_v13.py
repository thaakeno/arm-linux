#!/usr/bin/env python3
"""Protocol-13 Vessel runtime.

Protocol 12 reached the final display layer, but Xtigervnc was started with
`-localhost yes`.  The minimal UML guest does not guarantee an IPv4/IPv6
loopback address is configured, so TigerVNC could fail with:

    vncExtInit: createTcpListeners: no addresses available

Protocol 13 removes that network dependency completely. Xtigervnc exposes RFB
only on a private Unix-domain socket inside Debian. The existing reverse VNC
helper connects to that Unix socket and forwards it over the already-proven
outbound guest->host channel. No guest TCP listener is needed at all.
"""
from __future__ import annotations

import base64
import shlex
import time

import vessel_runtime_daemon_v12 as v12

core = v12.core
v11 = v12.v11
PROTOCOL_VERSION = 13
core.PROTOCOL_VERSION = PROTOCOL_VERSION

VNC_UNIX = "/tmp/vessel-vnc.sock"


def install_unix_reverse_helper(self: core.Runtime) -> None:
    helper = r'''#!/usr/bin/env python3
import select,socket,sys,time
host=sys.argv[1]; port=int(sys.argv[2]); path=sys.argv[3]
a=socket.create_connection((host,port),timeout=8)
b=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
deadline=time.monotonic()+8
while True:
    try:
        b.connect(path)
        break
    except (FileNotFoundError,ConnectionRefusedError):
        if time.monotonic()>=deadline:
            raise
        time.sleep(0.1)
a.setblocking(False); b.setblocking(False)
while True:
    r,_,_=select.select([a,b],[],[],30)
    if not r: continue
    for src,dst in ((a,b),(b,a)):
        if src not in r: continue
        try: data=src.recv(65536)
        except BlockingIOError: continue
        if not data: sys.exit(0)
        dst.sendall(data)
'''
    encoded = base64.b64encode(helper.encode()).decode()
    v11.resilient_guest(
        self,
        f"printf '%s' {shlex.quote(encoded)} | base64 -d > /root/vessel_vnc_reverse.py; chmod +x /root/vessel_vnc_reverse.py",
        10.0,
    )


def start_xtigervnc_unix(self: core.Runtime, width: int, height: int, dpi: int) -> None:
    self.set_progress("vnc_start", 88, "Starting TigerVNC X server")
    cleanup = (
        "pkill -f '[X]tigervnc.*:1' 2>/dev/null || true; "
        "pkill -f '[s]tartplasma-x11' 2>/dev/null || true; "
        "pkill -f '[k]win_x11' 2>/dev/null || true; "
        "pkill -f '[p]lasmashell' 2>/dev/null || true; "
        f"rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 {VNC_UNIX} /tmp/vessel-Xtigervnc.log /tmp/vessel-plasma.log"
    )
    v11.resilient_guest(self, cleanup, 12.0)

    # RFB is intentionally Unix-socket-only. This avoids relying on lo/127.0.0.1
    # configuration in the minimal UML guest and keeps the unauthenticated
    # SecurityTypes=None endpoint inaccessible to the guest network.
    command = (
        "setsid -f Xtigervnc :1 "
        f"-geometry {width}x{height} -depth 24 -dpi {dpi} "
        f"-rfbport -1 -rfbunixpath {VNC_UNIX} -rfbunixmode 0600 "
        "-SecurityTypes None -AlwaysShared "
        ">/tmp/vessel-Xtigervnc.log 2>&1 </dev/null; echo XSERVER_LAUNCHED"
    )
    v11.resilient_guest(self, command, 10.0)

    deadline = time.monotonic() + 15.0
    while time.monotonic() < deadline:
        out = v11.resilient_guest(
            self,
            f"pgrep -f '[X]tigervnc.*:1' >/dev/null 2>&1 && test -S {VNC_UNIX} && echo XSERVER_READY || true",
            5.0,
        )
        if "XSERVER_READY" in out:
            return
        time.sleep(0.35)

    diag = v11.resilient_guest(
        self,
        f"printf 'SOCKET='; test -S {VNC_UNIX} && echo yes || echo no; "
        "tail -n 220 /tmp/vessel-Xtigervnc.log 2>/dev/null || true",
        8.0,
    )
    raise RuntimeError("Xtigervnc failed to stay running with Unix RFB transport: " + diag[-9000:])


def desktop_v13(self: core.Runtime, width: int = 1920, height: int = 1080, dpi: int = 144):
    lock = getattr(self, "lifecycle_lock", None)
    if lock is None:
        import threading
        self.lifecycle_lock = threading.RLock()
        lock = self.lifecycle_lock

    with lock:
        self.start()
        width = max(800, min(width, 3840))
        height = max(600, min(height, 2160))
        dpi = max(96, min(dpi, 240))
        self.last_error = ""

        self.set_progress("desktop_check", 60, "Checking KDE Plasma X11 and TigerVNC")
        v12._ensure_desktop_packages(self)

        self.set_progress("desktop_config", 84, "Preparing persistent Plasma session")
        install_unix_reverse_helper(self)
        start_xtigervnc_unix(self, width, height, dpi)
        v12._start_plasma(self)

        if self.vnc_proxy is None:
            self.vnc_proxy = core.ReverseVncProxy(self)
            self.vnc_proxy.start()

        self.desktop_ready = True
        self.last_error = ""
        self.set_progress("desktop_ready", 100, "KDE Plasma is live")
        return self.state()


# ReverseVncProxy invokes /root/vessel_vnc_reverse.py with host + port. Add the
# Unix socket path transparently at generation time by wrapping its command.
_original_connect_pair = core.ReverseVncProxy._connect_pair

def connect_pair_unix(self, client):
    reverse = None
    try:
        self.runtime.guest(
            f"nohup python3 /root/vessel_vnc_reverse.py 10.0.2.2 {core.VNC_REVERSE_PORT} {VNC_UNIX} "
            ">/tmp/vessel-vnc-reverse.log 2>&1 </dev/null &",
            8,
        )
        assert self.reverse_server is not None
        reverse, _ = self.reverse_server.accept()
        self._pump_pair(client, reverse)
    except Exception as exc:
        self.runtime.last_error = f"VNC proxy: {type(exc).__name__}: {exc}"
        try:
            client.close()
        except OSError:
            pass
        if reverse is not None:
            try:
                reverse.close()
            except OSError:
                pass

core.ReverseVncProxy._connect_pair = connect_pair_unix
core.Runtime.ensure_desktop = desktop_v13

if __name__ == "__main__":
    try:
        core.serve()
    finally:
        core.runtime.stop()
