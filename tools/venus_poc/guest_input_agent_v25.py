#!/usr/bin/env python3
"""Vessel native input endpoint.

Uses /dev/uinput when the guest kernel exposes it. On older installed UML
kernels, falls back to XTest against Vessel's guest-local Xvfb :1 display.
Neither path uses VNC/RFB.
"""
from __future__ import annotations

import ctypes
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

def _ioc(direction, typ, nr, size): return (direction << 30) | (size << 16) | (typ << 8) | nr
def _iow(nr): return _ioc(1, UINPUT_IOCTL_BASE, nr, struct.calcsize('i'))
def _io(nr): return _ioc(0, UINPUT_IOCTL_BASE, nr, 0)
UI_SET_EVBIT=_iow(100); UI_SET_KEYBIT=_iow(101); UI_SET_RELBIT=_iow(102); UI_SET_ABSBIT=_iow(103)
UI_DEV_CREATE=_io(1); UI_DEV_DESTROY=_io(2)

class UInputDevice:
    def __init__(self, name, evbits=(), keybits=(), relbits=(), absbits=(), absmax=None):
        self.fd=os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
        for bit in evbits: fcntl.ioctl(self.fd, UI_SET_EVBIT, bit)
        for bit in keybits: fcntl.ioctl(self.fd, UI_SET_KEYBIT, bit)
        for bit in relbits: fcntl.ioctl(self.fd, UI_SET_RELBIT, bit)
        for bit in absbits: fcntl.ioctl(self.fd, UI_SET_ABSBIT, bit)
        maxv=[0]*64; minv=[0]*64; fuzz=[0]*64; flat=[0]*64
        for code,value in (absmax or {}).items(): maxv[code]=value
        header=struct.pack('<80sHHHHI', name.encode()[:79], BUS_VIRTUAL, 0x5653, 0x0001, 1, 0)
        payload=header + struct.pack('<'+'i'*64,*maxv)+struct.pack('<'+'i'*64,*minv)+struct.pack('<'+'i'*64,*fuzz)+struct.pack('<'+'i'*64,*flat)
        os.write(self.fd,payload); fcntl.ioctl(self.fd, UI_DEV_CREATE); time.sleep(.03)
    def event(self, typ, code, value, sync=True):
        os.write(self.fd, struct.pack('llHHi',0,0,typ,code,int(value)))
        if sync: os.write(self.fd, struct.pack('llHHi',0,0,EV_SYN,SYN_REPORT,0))
    def close(self):
        try: fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError: pass
        try: os.close(self.fd)
        except OSError: pass

class UInputBackend:
    def __init__(self):
        self.touch=UInputDevice('Vessel Touchscreen',(EV_KEY,EV_ABS),(BTN_TOUCH,),(),(ABS_X,ABS_Y),{ABS_X:32767,ABS_Y:32767})
        self.pointer=UInputDevice('Vessel Trackpad',(EV_KEY,EV_REL),(BTN_LEFT,BTN_RIGHT,BTN_MIDDLE),(REL_X,REL_Y,REL_WHEEL,REL_HWHEEL))
        self.keyboard=UInputDevice('Vessel Keyboard',(EV_KEY,),tuple(range(1,256)))
    def handle(self,m):
        t=m.get('t')
        if t=='abs':
            self.touch.event(EV_ABS,ABS_X,max(0,min(32767,int(m.get('x',0)))),False)
            self.touch.event(EV_ABS,ABS_Y,max(0,min(32767,int(m.get('y',0)))),False)
            self.touch.event(EV_KEY,BTN_TOUCH,1 if m.get('down') else 0)
        elif t=='rel':
            dx=int(m.get('dx',0)); dy=int(m.get('dy',0))
            if dx: self.pointer.event(EV_REL,REL_X,dx,False)
            if dy: self.pointer.event(EV_REL,REL_Y,dy,False)
            if dx or dy: self.pointer.event(EV_SYN,SYN_REPORT,0,False)
        elif t=='btn': self.pointer.event(EV_KEY,int(m.get('code',BTN_LEFT)),1 if m.get('down') else 0)
        elif t=='scroll':
            x=int(m.get('x',0)); y=int(m.get('y',0))
            if x: self.pointer.event(EV_REL,REL_HWHEEL,x,False)
            if y: self.pointer.event(EV_REL,REL_WHEEL,y,False)
            if x or y: self.pointer.event(EV_SYN,SYN_REPORT,0,False)
        elif t=='key':
            code=int(m.get('code',0))
            if 0 < code < 256: self.keyboard.event(EV_KEY,code,1 if m.get('down') else 0)

class XTestBackend:
    def __init__(self):
        self.x11=ctypes.CDLL('libX11.so.6')
        self.xtst=ctypes.CDLL('libXtst.so.6')
        self.x11.XOpenDisplay.restype=ctypes.c_void_p
        self.display=None
        self._connect()
    def _connect(self):
        while not self.display:
            self.display=self.x11.XOpenDisplay(b':1')
            if not self.display: time.sleep(.1)
    def _flush(self): self.x11.XFlush(ctypes.c_void_p(self.display))
    def _button(self,button,down): self.xtst.XTestFakeButtonEvent(ctypes.c_void_p(self.display),button,1 if down else 0,0); self._flush()
    def handle(self,m):
        t=m.get('t')
        if t=='abs':
            x=max(0,min(32767,int(m.get('x',0)))); y=max(0,min(32767,int(m.get('y',0))))
            # XTest absolute coordinates are screen pixels. Query geometry lazily.
            root=ctypes.c_ulong(); rx=ctypes.c_int(); ry=ctypes.c_int(); w=ctypes.c_uint(); h=ctypes.c_uint(); bw=ctypes.c_uint(); depth=ctypes.c_uint()
            self.x11.XDefaultRootWindow.restype=ctypes.c_ulong
            root.value=self.x11.XDefaultRootWindow(ctypes.c_void_p(self.display))
            self.x11.XGetGeometry(ctypes.c_void_p(self.display),root,ctypes.byref(root),ctypes.byref(rx),ctypes.byref(ry),ctypes.byref(w),ctypes.byref(h),ctypes.byref(bw),ctypes.byref(depth))
            px=int(x*max(1,w.value-1)/32767); py=int(y*max(1,h.value-1)/32767)
            self.xtst.XTestFakeMotionEvent(ctypes.c_void_p(self.display),-1,px,py,0)
            self._button(1,bool(m.get('down')))
        elif t=='rel':
            self.xtst.XTestFakeRelativeMotionEvent(ctypes.c_void_p(self.display),int(m.get('dx',0)),int(m.get('dy',0)),0); self._flush()
        elif t=='btn':
            code=int(m.get('code',BTN_LEFT)); button={BTN_LEFT:1,BTN_MIDDLE:2,BTN_RIGHT:3}.get(code,1); self._button(button,bool(m.get('down')))
        elif t=='scroll':
            x=int(m.get('x',0)); y=int(m.get('y',0))
            for _ in range(abs(y)):
                b=4 if y>0 else 5; self._button(b,True); self._button(b,False)
            for _ in range(abs(x)):
                b=6 if x>0 else 7; self._button(b,True); self._button(b,False)
        elif t=='key':
            code=int(m.get('code',0))
            if 0 < code < 256:
                self.xtst.XTestFakeKeyEvent(ctypes.c_void_p(self.display),code+8,1 if m.get('down') else 0,0); self._flush()

def make_backend():
    if os.path.exists('/dev/uinput'):
        try: return UInputBackend()
        except Exception: pass
    return XTestBackend()

def run(host,port):
    backend=make_backend()
    while True:
        s=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        try:
            s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
            s.connect((host,port)); f=s.makefile('r',encoding='utf-8',errors='replace')
            for line in f:
                try: backend.handle(json.loads(line))
                except Exception: continue
        except (OSError,ConnectionError): time.sleep(.1)
        finally:
            try: s.close()
            except OSError: pass

if __name__=='__main__': run(sys.argv[1] if len(sys.argv)>1 else '10.0.2.2', int(sys.argv[2]) if len(sys.argv)>2 else 47635)
