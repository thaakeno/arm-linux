#!/usr/bin/env python3
import fcntl,json,os,shutil,socket,struct,subprocess,time
HOST=os.environ.get('VESSEL_INPUT_HOST','10.0.2.2'); PORT=int(os.environ.get('VESSEL_INPUT_PORT','47633'))
LOG='/tmp/vessel-input.log'
EV_SYN=0;EV_KEY=1;EV_REL=2;EV_ABS=3;SYN_REPORT=0;REL_X=0;REL_Y=1;REL_HWHEEL=6;REL_WHEEL=8;ABS_X=0;ABS_Y=1
BTN_LEFT=0x110;BTN_RIGHT=0x111;BTN_MIDDLE=0x112;BTN_TOUCH=0x14a;BUS_VIRTUAL=6;PROP_POINTER=0;PROP_DIRECT=1;U=ord('U')
def log(s):
 try:
  with open(LOG,'a',encoding='utf-8') as f:f.write('%.3f %s\n'%(time.monotonic(),s))
 except Exception:pass
def ioc(d,t,n,s):return (d<<30)|(s<<16)|(t<<8)|n
def iow(n):return ioc(1,U,n,4)
def io(n):return ioc(0,U,n,0)
UI_SET_EVBIT=iow(100);UI_SET_KEYBIT=iow(101);UI_SET_RELBIT=iow(102);UI_SET_ABSBIT=iow(103);UI_SET_PROPBIT=iow(110);UI_DEV_CREATE=io(1)
class Dev:
 def __init__(self,name,ev=(),keys=(),rels=(),abss=(),props=(),amax=None):
  self.fd=os.open('/dev/uinput',os.O_WRONLY|os.O_NONBLOCK)
  for x in ev:fcntl.ioctl(self.fd,UI_SET_EVBIT,x)
  for x in keys:fcntl.ioctl(self.fd,UI_SET_KEYBIT,x)
  for x in rels:fcntl.ioctl(self.fd,UI_SET_RELBIT,x)
  for x in abss:fcntl.ioctl(self.fd,UI_SET_ABSBIT,x)
  for x in props:fcntl.ioctl(self.fd,UI_SET_PROPBIT,x)
  mx=[0]*64;mn=[0]*64;fz=[0]*64;fl=[0]*64
  for k,v in (amax or {}).items():mx[k]=v
  payload=struct.pack('<80sHHHHI',name.encode()[:79],BUS_VIRTUAL,0x5653,0x0039,1,0)+struct.pack('<64i',*mx)+struct.pack('<64i',*mn)+struct.pack('<64i',*fz)+struct.pack('<64i',*fl)
  os.write(self.fd,payload);fcntl.ioctl(self.fd,UI_DEV_CREATE);time.sleep(.04)
 def e(self,t,c,v,sync=True):
  os.write(self.fd,struct.pack('llHHi',0,0,t,c,int(v)))
  if sync:os.write(self.fd,struct.pack('llHHi',0,0,EV_SYN,SYN_REPORT,0))
 def sync(self):self.e(EV_SYN,SYN_REPORT,0,False)
def setup_network():
 if not HOST.startswith('10.0.2.'):
  return
 ifaces=[x for x in os.listdir('/sys/class/net') if x!='lo']
 if not ifaces:
  raise RuntimeError('no UML network interface found')
 iface=ifaces[0]
 ip=shutil.which('ip')
 if not ip:
  for p in ('/usr/sbin/ip','/sbin/ip','/usr/bin/ip','/bin/ip'):
   if os.path.exists(p):ip=p;break
 if not ip:
  raise RuntimeError('iproute2/ip command missing in guest')
 cmds=(
  [ip,'link','set','dev',iface,'up'],
  [ip,'addr','replace','10.0.2.15/24','dev',iface],
  [ip,'route','replace','default','via','10.0.2.2','dev',iface],
 )
 for cmd in cmds:
  cp=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
  if cp.returncode:raise RuntimeError('%s rc=%d %s'%(' '.join(cmd),cp.returncode,cp.stdout.strip()))
 try:
  with open('/etc/resolv.conf','w',encoding='utf-8') as f:f.write('nameserver 1.1.1.1\nnameserver 8.8.8.8\n')
 except Exception as e:log('resolv.conf warning: %r'%e)
 log('network ready iface=%s addr=10.0.2.15 gateway=10.0.2.2'%iface)
try:
 open(LOG,'w').close()
 setup_network()
 touch=Dev('Vessel Touchscreen',ev=(EV_KEY,EV_ABS),keys=(BTN_TOUCH,),abss=(ABS_X,ABS_Y),props=(PROP_DIRECT,),amax={ABS_X:32767,ABS_Y:32767})
 ptr=Dev('Vessel Trackpad',ev=(EV_KEY,EV_REL),keys=(BTN_LEFT,BTN_RIGHT,BTN_MIDDLE),rels=(REL_X,REL_Y,REL_WHEEL,REL_HWHEEL),props=(PROP_POINTER,))
 kbd=Dev('Vessel Keyboard',ev=(EV_KEY,),keys=tuple(range(1,256)))
 log('uinput devices ready: touchscreen + trackpad + keyboard')
except Exception as e:
 log('startup failed: %s: %s'%(type(e).__name__,e))
 raise
while True:
 try:
  log('connecting to %s:%d'%(HOST,PORT))
  with socket.create_connection((HOST,PORT),timeout=5) as s:
   s.settimeout(None);s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1);f=s.makefile('r',encoding='utf-8',errors='replace');s.sendall(b'HELLO uinput-v39-r3\n');log('connected to Vessel input server')
   for line in f:
    try:m=json.loads(line)
    except Exception:continue
    t=m.get('t');seq=int(m.get('seq',0))
    if t=='abs':
     touch.e(EV_ABS,ABS_X,max(0,min(32767,int(m.get('x',0)))),False);touch.e(EV_ABS,ABS_Y,max(0,min(32767,int(m.get('y',0)))),False);touch.e(EV_KEY,BTN_TOUCH,1 if m.get('down') else 0)
    elif t=='rel':
     x=int(m.get('dx',0));y=int(m.get('dy',0))
     if x:ptr.e(EV_REL,REL_X,x,False)
     if y:ptr.e(EV_REL,REL_Y,y,False)
     if x or y:ptr.sync()
    elif t=='btn':
     c=int(m.get('code',BTN_LEFT))
     if c in (BTN_LEFT,BTN_RIGHT,BTN_MIDDLE):ptr.e(EV_KEY,c,1 if m.get('down') else 0)
    elif t=='scroll':
     x=int(m.get('x',0));y=int(m.get('y',0))
     if x:ptr.e(EV_REL,REL_HWHEEL,x,False)
     if y:ptr.e(EV_REL,REL_WHEEL,y,False)
     if x or y:ptr.sync()
    elif t=='key':
     c=int(m.get('code',0))
     if 0<c<256:kbd.e(EV_KEY,c,1 if m.get('down') else 0)
    if seq and (t=='ping' or seq%16==0):
     try:s.sendall(('ACK %d\n'%seq).encode())
     except Exception:pass
 except Exception as e:
  log('connect/reconnect failed: %s: %s'%(type(e).__name__,e));time.sleep(.1)
