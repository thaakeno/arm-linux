#!/usr/bin/env python3
"""Low-latency guest input endpoint for Vessel.

Creates three /dev/uinput devices inside UML so Android input arrives as normal
Linux evdev input. This is independent of X11/VNC and works for Xorg or Wayland.
"""
from __future__ import annotations

import fcntl
import json
import os
import socket
import struct
import sys
import time

EV_SYN=0x00; EV_KEY=0x01; EV_REL=0x02; EV_ABS=0x03
SYN_REPORT=0
REL_X=0x00; REL_Y=0x01; REL_HWHEEL=0x06; REL_WHEEL=0x08
ABS_X=0x00; ABS_Y=0x01
BTN_LEFT=0x110; BTN_RIGHT=0x111; BTN_MIDDLE=0x112; BTN_TOUCH=0x14a
BUS_VIRTUAL=0x06
UINPUT_IOCTL_BASE=ord('U')

def _ioc(direction, typ, nr, size):
    return (direction << 30) | (size << 16) | (typ << 8) | nr

def _iow(nr): return _ioc(1, UINPUT_IOCTL_BASE, nr, struct.calcsize('i'))
def _io(nr): return _ioc(0, UINPUT_IOCTL_BASE, nr, 0)
UI_SET_EVBIT=_iow(100); UI_SET_KEYBIT=_iow(101); UI_SET_RELBIT=_iow(102); UI_SET_ABSBIT=_iow(103)
UI_DEV_CREATE=_io(1); UI_DEV_DESTROY=_io(2)

class UInputDevice:
    def __init__(self, name: str, evbits=(), keybits=(), relbits=(), absbits=(), absmax=None):
        self.fd=os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
        for bit in evbits: fcntl.ioctl(self.fd, UI_SET_EVBIT, bit)
        for bit in keybits: fcntl.ioctl(self.fd, UI_SET_KEYBIT, bit)
        for bit in relbits: fcntl.ioctl(self.fd, UI_SET_RELBIT, bit)
        for bit in absbits: fcntl.ioctl(self.fd, UI_SET_ABSBIT, bit)
        maxv=[0]*64; minv=[0]*64; fuzz=[0]*64; flat=[0]*64
        for code,value in (absmax or {}).items(): maxv[code]=value
        header=struct.pack('<80sHHHHI', name.encode()[:79], BUS_VIRTUAL, 0x5653, 0x0001, 1, 0)
        payload=header + struct.pack('<'+'i'*64, *maxv) + struct.pack('<'+'i'*64, *minv) + struct.pack('<'+'i'*64, *fuzz) + struct.pack('<'+'i'*64, *flat)
        os.write(self.fd,payload)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)
        time.sleep(0.05)
    def event(self, typ:int, code:int, value:int, sync=True):
        os.write(self.fd, struct.pack('llHHi',0,0,typ,code,int(value)))
        if sync: os.write(self.fd, struct.pack('llHHi',0,0,EV_SYN,SYN_REPORT,0))
    def close(self):
        try: fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError: pass
        try: os.close(self.fd)
        except OSError: pass

def create_devices():
    touch=UInputDevice('Vessel Touchscreen', evbits=(EV_KEY,EV_ABS), keybits=(BTN_TOUCH,), absbits=(ABS_X,ABS_Y), absmax={ABS_X:32767,ABS_Y:32767})
    pointer=UInputDevice('Vessel Trackpad', evbits=(EV_KEY,EV_REL), keybits=(BTN_LEFT,BTN_RIGHT,BTN_MIDDLE), relbits=(REL_X,REL_Y,REL_WHEEL,REL_HWHEEL))
    keyboard=UInputDevice('Vessel Keyboard', evbits=(EV_KEY,), keybits=tuple(range(1,256)))
    return touch,pointer,keyboard

def run(host:str,port:int):
    touch,pointer,keyboard=create_devices()
    try:
        while True:
            s=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
            try:
                s.connect((host,port)); f=s.makefile('r',encoding='utf-8',errors='replace')
                for line in f:
                    try: m=json.loads(line)
                    except json.JSONDecodeError: continue
                    t=m.get('t')
                    if t=='abs':
                        touch.event(EV_ABS,ABS_X,max(0,min(32767,int(m.get('x',0)))),False)
                        touch.event(EV_ABS,ABS_Y,max(0,min(32767,int(m.get('y',0)))),False)
                        touch.event(EV_KEY,BTN_TOUCH,1 if m.get('down') else 0,True)
                    elif t=='rel':
                        dx=int(m.get('dx',0)); dy=int(m.get('dy',0))
                        if dx: pointer.event(EV_REL,REL_X,dx,False)
                        if dy: pointer.event(EV_REL,REL_Y,dy,False)
                        if dx or dy: pointer.event(EV_SYN,SYN_REPORT,0,False)
                    elif t=='btn':
                        code=int(m.get('code',BTN_LEFT))
                        if code in (BTN_LEFT,BTN_RIGHT,BTN_MIDDLE): pointer.event(EV_KEY,code,1 if m.get('down') else 0)
                    elif t=='scroll':
                        x=int(m.get('x',0)); y=int(m.get('y',0))
                        if x: pointer.event(EV_REL,REL_HWHEEL,x,False)
                        if y: pointer.event(EV_REL,REL_WHEEL,y,False)
                        if x or y: pointer.event(EV_SYN,SYN_REPORT,0,False)
                    elif t=='key':
                        code=int(m.get('code',0))
                        if 0 < code < 256: keyboard.event(EV_KEY,code,1 if m.get('down') else 0)
            except (OSError,ConnectionError):
                time.sleep(0.15)
            finally:
                try: s.close()
                except OSError: pass
    finally:
        touch.close(); pointer.close(); keyboard.close()

if __name__=='__main__':
    run(sys.argv[1] if len(sys.argv)>1 else '10.0.2.2', int(sys.argv[2]) if len(sys.argv)>2 else 47633)
