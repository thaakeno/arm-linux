#!/usr/bin/env python3
"""Compile Bounce Quest's authored render scene into one immutable runtime mesh."""
from __future__ import annotations
import base64,json,math,os,pathlib,struct,tempfile,urllib.parse,urllib.request
UA="Vessel-BounceQuest-AssetBuilder/2.0"
OUT=pathlib.Path(os.environ.get("BQ_OUT","app/src/main/assets/bounce"));OUT.mkdir(parents=True,exist_ok=True)
V=[]
def vert(p,n,uv,mat,obj=0):V.append((*p,*n,*uv,float(mat),float(obj)))
def tri(a,b,c,n,mat,obj=0,uv=((0,0),(1,0),(1,1))):
    vert(a,n,uv[0],mat,obj);vert(b,n,uv[1],mat,obj);vert(c,n,uv[2],mat,obj)
def quad(a,b,c,d,n,mat,obj=0,uvscale=(1,1)):
    u,v=uvscale;tri(a,b,c,n,mat,obj,((0,0),(u,0),(u,v)));tri(a,c,d,n,mat,obj,((0,0),(u,v),(0,v)))
def box(c,h,mat,obj=0):
    x,y,z=c;X,Y,Z=h
    faces=[((0,0,1),[(-X,-Y,Z),(X,-Y,Z),(X,Y,Z),(-X,Y,Z)],(X*2,Y*2)),((0,0,-1),[(X,-Y,-Z),(-X,-Y,-Z),(-X,Y,-Z),(X,Y,-Z)],(X*2,Y*2)),((1,0,0),[(X,-Y,Z),(X,-Y,-Z),(X,Y,-Z),(X,Y,Z)],(Z*2,Y*2)),((-1,0,0),[(-X,-Y,-Z),(-X,-Y,Z),(-X,Y,Z),(-X,Y,-Z)],(Z*2,Y*2)),((0,1,0),[(-X,Y,Z),(X,Y,Z),(X,Y,-Z),(-X,Y,-Z)],(X*2,Z*2)),((0,-1,0),[(-X,-Y,-Z),(X,-Y,-Z),(X,-Y,Z),(-X,-Y,Z)],(X*2,Z*2))]
    for n,pts,uvs in faces:quad(*[(x+a,y+b,z+d) for a,b,d in pts],n,mat,obj,uvs)
def ramp(c,h,rise,mat):
    x,y,z=c;X,Y,Z=h;low=y-h[1];high=y+rise
    a=(x-X,high,z-Z);b=(x+X,high,z-Z);cc=(x+X,low,z+Z);d=(x-X,low,z+Z)
    nn=(0,2*Z,rise);L=math.hypot(nn[1],nn[2]);n=(0,nn[1]/L,nn[2]/L)
    quad(a,b,cc,d,n,mat,0,(X*2,Z*2))
    box((x,low-.10,z),(X,.10,Z),mat)
    tri((x-X,low,z-Z),(x-X,low,z+Z),(x-X,high,z-Z),(-1,0,0),mat)
    tri((x+X,low,z+Z),(x+X,low,z-Z),(x+X,high,z-Z),(1,0,0),mat)
def sphere(c,r,mat,obj,U=32,W=16):
    for j in range(W):
        for i in range(U):
            for a,b in ((0,0),(1,0),(1,1),(0,0),(1,1),(0,1)):
                u=(i+a)/U;v=(j+b)/W;th=u*math.tau;ph=v*math.pi;n=(math.cos(th)*math.sin(ph),math.cos(ph),math.sin(th)*math.sin(ph));p=(c[0]+n[0]*r,c[1]+n[1]*r,c[2]+n[2]*r);vert(p,n,(u,v),mat,obj)
def torus(c,R,r,mat,obj,U=48,W=12):
    for j in range(W):
        for i in range(U):
            for a,b in ((0,0),(1,0),(1,1),(0,0),(1,1),(0,1)):
                u=(i+a)/U*math.tau;v=(j+b)/W*math.tau;q=((R+r*math.cos(v))*math.cos(u),r*math.sin(v),(R+r*math.cos(v))*math.sin(u));n=(math.cos(v)*math.cos(u),math.sin(v),math.cos(v)*math.sin(u));vert((c[0]+q[0],c[1]+q[2],c[2]+q[1]),(n[0],n[2],n[1]),(u/math.tau,v/math.tau),mat,obj)
def spike(c,r,h,mat,segments=16):
    for i in range(segments):
        a=i/segments*math.tau;b=(i+1)/segments*math.tau;p0=(c[0]+math.cos(a)*r,c[1],c[2]+math.sin(a)*r);p1=(c[0]+math.cos(b)*r,c[1],c[2]+math.sin(b)*r);tip=(c[0],c[1]+h,c[2]);n=(math.cos((a+b)/2),r/h,math.sin((a+b)/2));L=math.sqrt(sum(x*x for x in n));tri(p0,p1,tip,tuple(x/L for x in n),mat)
def course():
    platforms=[((0,-.42,2.0),(5.8,.42,3.6),0),((.7,.10,-3.0),(4.0,.40,1.8),0),((-2.8,.55,-6.2),(2.0,.42,1.6),1),((1.8,.72,-6.7),(2.6,.42,1.7),0),((4.1,1.02,-9.5),(1.7,.40,1.5),1),((.8,1.25,-9.7),(2.1,.35,1.1),0),((-2.0,1.55,-11.7),(1.6,.34,1.0),0),((1.4,1.85,-13.5),(1.7,.34,1.0),0),((4.1,2.10,-15.5),(1.6,.34,1.0),1),((1.7,2.35,-17.2),(1.8,.34,.90),0),((-1.0,2.62,-19.0),(1.7,.34,.90),0),((-3.2,2.90,-21.0),(1.6,.34,1.0),1),((-.5,3.15,-22.9),(2.0,.34,1.0),0),((1.0,3.50,-25.8),(4.2,.42,2.1),0)]
    for c,h,m in platforms:box(c,h,m)
    ramp((.15,.05,-.85),(1.65,.10,1.35),.70,2);ramp((-.7,.88,-8.0),(1.25,.10,1.10),.62,2)
    for z in (-10.6,-14.4,-18.1):box((-3.9,1.05 if z>-12 else 2.45,z),(.12,1.0,1.05),5)
    box((-6.2,1.7,-4.0),(.28,2.2,8.0),0);box((6.2,1.7,-4.0),(.28,2.2,8.0),0)
    for c in [(-3.2,.42,-.2),(-2.05,.42,-.2),(3.35,.55,-3.1),(2.8,1.15,-7.2)]:box(c,(.48,.48,.48),3)
    box((2.15,.04,1.05),(1.10,.11,1.05),4);box((2.15,.16,1.05),(.88,.025,.80),8)
    for i in range(7):spike((-2.0+i*.68,.12,-8.85),.25,1.0,6)
    coins=[(-2.4,.70,1.1),(2.2,.70,.1),(.7,1.05,-3.0),(-2.8,1.45,-6.2),(1.8,1.60,-6.7),(4.1,1.90,-9.5),(.8,2.10,-9.7),(1.4,2.80,-13.5),(1.7,3.25,-17.2),(1.0,4.35,-25.8)]
    for i,p in enumerate(coins):sphere(p,.26,7,100+i,24,12)
    torus((1.0,4.85,-27.0),1.28,.12,9,300)
    for i in range(11):
        a=math.pi*(i/10);box((1.0+math.cos(a)*1.65,4.85+math.sin(a)*1.65,-27.1),(.28,.28,.35),5)
    sphere((0,0,0),1.0,10,1,48,24);sphere((0,0,-8),48.0,13,900,48,24)
def fetch_json(url):return json.load(urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":UA}),timeout=45))
def flatten_urls(x):
    out=[]
    if isinstance(x,dict):
        if isinstance(x.get('url'),str):out.append(x['url'])
        for v in x.values():out+=flatten_urls(v)
    elif isinstance(x,list):
        for v in x:out+=flatten_urls(v)
    return out
def select_gltf(files):
    g=files.get('gltf',{})
    for res in ('1k','2k','4k'):
        if res in g and isinstance(g[res],dict):
            node=g[res];rec=node.get('gltf') or next((v for v in node.values() if isinstance(v,dict) and isinstance(v.get('url'),str) and v['url'].lower().endswith('.gltf')),None)
            if isinstance(rec,dict) and rec.get('url'):return rec
    for u in flatten_urls(g):
        if u.lower().endswith('.gltf'):return {'url':u}
    raise RuntimeError('no glTF file')
def matmul(a,b):return [[sum(a[r][k]*b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]
def ident():return [[1 if r==c else 0 for c in range(4)] for r in range(4)]
def node_matrix(n):
    if 'matrix' in n:
        m=n['matrix'];return [[m[c*4+r] for c in range(4)] for r in range(4)]
    t=n.get('translation',[0,0,0]);s=n.get('scale',[1,1,1]);x,y,z,w=n.get('rotation',[0,0,0,1]);R=[[1-2*y*y-2*z*z,2*x*y-2*z*w,2*x*z+2*y*w,0],[2*x*y+2*z*w,1-2*x*x-2*z*z,2*y*z-2*x*w,0],[2*x*z-2*y*w,2*y*z+2*x*w,1-2*x*x-2*y*y,0],[0,0,0,1]];S=[[s[0],0,0,0],[0,s[1],0,0],[0,0,s[2],0],[0,0,0,1]];M=matmul(R,S);M[0][3]=t[0];M[1][3]=t[1];M[2][3]=t[2];return M
def xf(M,p,w=1):
    v=[p[0],p[1],p[2],w];r=[sum(M[i][k]*v[k] for k in range(4)) for i in range(3)]
    if w==0:
        L=math.sqrt(sum(x*x for x in r)) or 1;r=[x/L for x in r]
    return tuple(r)
def add_polyhaven(asset_id,instances,mat):
    files=fetch_json(f'https://api.polyhaven.com/files/{asset_id}');rec=select_gltf(files);urls=[rec['url']]+flatten_urls(rec.get('include',{}))
    with tempfile.TemporaryDirectory() as td:
        td=pathlib.Path(td)
        for u in dict.fromkeys(urls):
            name=pathlib.PurePosixPath(urllib.parse.unquote(urllib.parse.urlparse(u).path)).name;(td/name).write_bytes(urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':UA}),timeout=60).read())
        main=td/pathlib.PurePosixPath(urllib.parse.unquote(urllib.parse.urlparse(rec['url']).path)).name;g=json.loads(main.read_text());bufs=[]
        for b in g.get('buffers',[]):
            uri=b.get('uri','');bufs.append(base64.b64decode(uri.split(',',1)[1]) if uri.startswith('data:') else (td/pathlib.PurePosixPath(urllib.parse.unquote(uri)).name).read_bytes())
        def accessor(i):
            a=g['accessors'][i];bv=g['bufferViews'][a['bufferView']];raw=bufs[bv['buffer']];off=bv.get('byteOffset',0)+a.get('byteOffset',0);fmt={5126:'f',5125:'I',5123:'H',5121:'B'}[a['componentType']];sz=struct.calcsize('<'+fmt);comps={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}[a['type']];stride=bv.get('byteStride',sz*comps);return [struct.unpack_from('<'+fmt*comps,raw,off+j*stride) for j in range(a['count'])]
        roots=g.get('scenes',[{'nodes':list(range(len(g.get('nodes',[]))))}])[g.get('scene',0)].get('nodes',[])
        def walk(ni,parent):
            n=g['nodes'][ni];M=matmul(parent,node_matrix(n))
            if 'mesh' in n:
                for pr in g['meshes'][n['mesh']]['primitives']:
                    if pr.get('mode',4)!=4 or 'POSITION' not in pr['attributes']:continue
                    pos=accessor(pr['attributes']['POSITION']);norm=accessor(pr['attributes']['NORMAL']) if 'NORMAL' in pr['attributes'] else [(0,1,0)]*len(pos);uv=accessor(pr['attributes']['TEXCOORD_0']) if 'TEXCOORD_0' in pr['attributes'] else [(0,0)]*len(pos);idx=accessor(pr['indices']) if 'indices' in pr else [(i,) for i in range(len(pos))]
                    for tx,ty,tz,sc,ry in instances:
                        co=math.cos(ry);si=math.sin(ry)
                        for it in idx:
                            k=int(it[0]);p=xf(M,pos[k]);nn=xf(M,norm[k],0);vert(((p[0]*co+p[2]*si)*sc+tx,p[1]*sc+ty,(-p[0]*si+p[2]*co)*sc+tz),(nn[0]*co+nn[2]*si,nn[1],-nn[0]*si+nn[2]*co),uv[k],mat)
            for ch in n.get('children',[]):walk(ch,M)
        for r in roots:walk(r,ident())
    print('added Poly Haven',asset_id)
def fetch_hdri(asset_id='alps_field'):
    f=fetch_json(f'https://api.polyhaven.com/files/{asset_id}');cand=[]
    def walk(x,path=''):
        if isinstance(x,dict):
            if isinstance(x.get('url'),str) and x['url'].lower().endswith(('.jpg','.jpeg')):cand.append((path,x))
            for k,v in x.items():walk(v,path+'/'+k)
        elif isinstance(x,list):
            for i,v in enumerate(x):walk(v,path+f'/{i}')
    walk(f);good=[x for x in cand if 'tonemapped' in x[0].lower() and '2k' in x[0].lower()] or [x for x in cand if 'tonemapped' in x[0].lower()] or cand
    if not good:raise RuntimeError('no HDRI jpg')
    rec=min(good,key=lambda x:x[1].get('size',1<<60))[1];(OUT/'alps.jpg').write_bytes(urllib.request.urlopen(urllib.request.Request(rec['url'],headers={'User-Agent':UA}),timeout=60).read())
course();add_polyhaven('concrete_road_barrier',[(-5.0,.2,-1.4,.85,.15),(5.0,.2,-2.0,.85,-.25),(-4.8,1.0,-7.7,.70,.5)],11);add_polyhaven('rock_face_01',[(-10,-2,-15,.70,.2),(9,-2,-19,.72,-.4),(-9,-2,-28,.8,.7)],12);add_polyhaven('fern_02',[(-5.2,.1,-2.0,.55,0),(5.1,.1,-4.0,.45,1.1),(-4.7,1.2,-8.0,.42,.5),(4.5,1.6,-12,.45,-.7)],14);fetch_hdri()
path=OUT/'scene.bqmesh'
with path.open('wb') as f:
    f.write(struct.pack('<4sIII',b'BQMS',2,len(V),40))
    for row in V:f.write(struct.pack('<10f',*row))
print('wrote',path,len(V),'vertices',path.stat().st_size,'bytes')
