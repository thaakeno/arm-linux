#!/usr/bin/env python3
"""Protocol-23 Vessel runtime: direct evdev input bridge, no RFB input path."""
from __future__ import annotations

import base64
import pathlib
import socket
import threading
import time

import vessel_runtime_daemon_v22 as v22

core=v22.core
v11=v22.v11
PROTOCOL_VERSION=23
core.PROTOCOL_VERSION=PROTOCOL_VERSION
INPUT_CLIENT_PORT=47632
INPUT_GUEST_PORT=47633

class InputBridge:
    def __init__(self):
        self.lock=threading.Lock(); self.guest=None
    def start(self):
        threading.Thread(target=self._guest_server,daemon=True,name='vessel-input-guest').start()
        threading.Thread(target=self._android_server,daemon=True,name='vessel-input-android').start()
    def _guest_server(self):
        srv=socket.socket(); srv.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); srv.bind(('127.0.0.1',INPUT_GUEST_PORT)); srv.listen(2)
        while True:
            conn,_=srv.accept(); conn.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
            with self.lock:
                old=self.guest; self.guest=conn
                if old:
                    try: old.close()
                    except OSError: pass
    def _android_server(self):
        srv=socket.socket(); srv.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); srv.bind(('127.0.0.1',INPUT_CLIENT_PORT)); srv.listen(4)
        while True:
            conn,_=srv.accept(); threading.Thread(target=self._pump,args=(conn,),daemon=True).start()
    def _pump(self,conn):
        try:
            buf=b''
            while True:
                chunk=conn.recv(16384)
                if not chunk: break
                buf+=chunk
                while b'\n' in buf:
                    line,buf=buf.split(b'\n',1)
                    if not line: continue
                    with self.lock: guest=self.guest
                    if guest:
                        try: guest.sendall(line+b'\n')
                        except OSError:
                            with self.lock:
                                if self.guest is guest: self.guest=None
        finally:
            try: conn.close()
            except OSError: pass

input_bridge=InputBridge()


def ensure_input_agent(self: core.Runtime):
    source=pathlib.Path(__file__).resolve().parent/'guest_input_agent.py'
    payload=base64.b64encode(source.read_bytes()).decode()
    cmd=f"printf '%s' '{payload}' | base64 -d >/root/vessel_input_agent.py; chmod 700 /root/vessel_input_agent.py; " \
        "test -c /dev/uinput || { echo UINPUT_MISSING; exit 42; }; " \
        "if ! pgrep -f '^python3 /root/vessel_input_agent.py' >/dev/null; then setsid -f python3 /root/vessel_input_agent.py 10.0.2.2 47633 >/tmp/vessel-input.log 2>&1 </dev/null; fi; echo INPUT_READY"
    out=v11.resilient_guest(self,cmd,8.0,attempts=8)
    if 'INPUT_READY' not in out: raise RuntimeError('guest evdev input agent failed: '+out[-1000:])
    self.append('\n[vessel-input] direct /dev/uinput bridge ready\n'); self.last_error=''

_prev_start=core.Runtime.start
def start_v23(self: core.Runtime,timeout:float=75.0):
    state=_prev_start(self,timeout)
    if self.proc is not None and self.proc.poll() is None and self.guest_ready:
        ensure_input_agent(self)
    return self.state()
core.Runtime.start=start_v23

_prev_desktop=core.Runtime.ensure_desktop
def desktop_v23(self: core.Runtime,width:int,height:int,dpi:int):
    ensure_input_agent(self)
    return _prev_desktop(self,width,height,dpi)
core.Runtime.ensure_desktop=desktop_v23

if __name__=='__main__':
    input_bridge.start()
    try: core.serve()
    finally: core.runtime.stop()
