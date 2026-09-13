#!/usr/bin/env python3
import json, math, struct, sys
from pathlib import Path

# Minimal offline glTF 2.0/GLB triangle baker. It intentionally handles only the
# features needed by static benchmark/game props: mesh nodes, TRS/matrix,
# POSITION/NORMAL/TEXCOORD_0 and triangle indices. Output is a tightly packed
# triangle list of 12 float32 values per vertex: pos4, normal4, uv4.

def mat_identity():
    return [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]

def mat_mul(a,b):
    o=[0.0]*16
    for r in range(4):
        for c in range(4):
            o[c*4+r]=sum(a[k*4+r]*b[c*4+k] for k in range(4))
    return o

def node_matrix(n):
    if 'matrix' in n: return [float(x) for x in n['matrix']]
    t=n.get('translation',[0,0,0]); s=n.get('scale',[1,1,1]); q=n.get('rotation',[0,0,0,1])
    x,y,z,w=[float(v) for v in q]
    xx,yy,zz=x*x,y*y,z*z; xy,xz,yz=x*y,x*z,y*z; wx,wy,wz=w*x,w*y,w*z
    r=[1-2*(yy+zz),2*(xy+wz),2*(xz-wy),0,
       2*(xy-wz),1-2*(xx+zz),2*(yz+wx),0,
       2*(xz+wy),2*(yz-wx),1-2*(xx+yy),0,
       0,0,0,1]
    sm=mat_identity(); sm[0]=s[0];sm[5]=s[1];sm[10]=s[2]
    tm=mat_identity(); tm[12]=t[0];tm[13]=t[1];tm[14]=t[2]
    return mat_mul(tm,mat_mul(r,sm))

def transform_pos(m,p):
    x,y,z=p
    return (m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13],m[2]*x+m[6]*y+m[10]*z+m[14])

def transform_dir(m,n):
    x,y,z=n
    v=(m[0]*x+m[4]*y+m[8]*z,m[1]*x+m[5]*y+m[9]*z,m[2]*x+m[6]*y+m[10]*z)
    l=math.sqrt(sum(q*q for q in v)) or 1.0
    return tuple(q/l for q in v)

def read_glb(path):
    b=Path(path).read_bytes()
    magic,version,total=struct.unpack_from('<III',b,0)
    if magic != 0x46546C67 or version != 2 or total > len(b): raise SystemExit('not glTF 2.0 GLB')
    off=12; js=None; binchunk=None
    while off+8<=total:
        ln,typ=struct.unpack_from('<II',b,off);off+=8;chunk=b[off:off+ln];off+=ln
        if typ==0x4E4F534A: js=json.loads(chunk.decode('utf-8').rstrip('\0 \t\r\n'))
        elif typ==0x004E4942: binchunk=chunk
    if js is None or binchunk is None: raise SystemExit('GLB missing JSON/BIN')
    return js,binchunk

COMP={5120:('b',1),5121:('B',1),5122:('h',2),5123:('H',2),5125:('I',4),5126:('f',4)}
NCOMP={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}

def accessor(doc,blob,idx):
    a=doc['accessors'][idx]; bv=doc['bufferViews'][a['bufferView']]; fmt,sz=COMP[a['componentType']]; n=NCOMP[a['type']]
    stride=bv.get('byteStride',n*sz); base=bv.get('byteOffset',0)+a.get('byteOffset',0); count=a['count']; out=[]
    sf='<'+fmt*n
    for i in range(count): out.append(struct.unpack_from(sf,blob,base+i*stride))
    return out

def bake(src,dst,meta):
    doc,blob=read_glb(src); verts=[]
    scene=doc.get('scene',0); roots=doc.get('scenes',[{'nodes':list(range(len(doc.get('nodes',[]))))}])[scene].get('nodes',[])
    def walk(ni,parent):
        n=doc['nodes'][ni]; world=mat_mul(parent,node_matrix(n))
        if 'mesh' in n:
            mesh=doc['meshes'][n['mesh']]
            for prim in mesh.get('primitives',[]):
                if prim.get('mode',4)!=4 or 'POSITION' not in prim.get('attributes',{}): continue
                at=prim['attributes']; pos=accessor(doc,blob,at['POSITION']); nor=accessor(doc,blob,at['NORMAL']) if 'NORMAL' in at else [(0,1,0)]*len(pos); uv=accessor(doc,blob,at['TEXCOORD_0']) if 'TEXCOORD_0' in at else [(0,0)]*len(pos)
                ind=accessor(doc,blob,prim['indices']) if 'indices' in prim else [(i,) for i in range(len(pos))]
                for it in ind:
                    i=int(it[0]); verts.append([*transform_pos(world,pos[i]),*transform_dir(world,nor[i]),float(uv[i][0]),float(uv[i][1])])
        for ch in n.get('children',[]): walk(ch,world)
    for r in roots: walk(r,mat_identity())
    if not verts: raise SystemExit('GLB produced no triangles')
    mn=[min(v[i] for v in verts) for i in range(3)]; mx=[max(v[i] for v in verts) for i in range(3)]
    h=max(mx[1]-mn[1],1e-5); scale=1.86/h; cx=(mn[0]+mx[0])*.5; cz=(mn[2]+mx[2])*.5; floor=mn[1]
    out=bytearray()
    for v in verts:
        px=(v[0]-cx)*scale; py=(v[1]-floor)*scale; pz=(v[2]-cz)*scale
        out += struct.pack('<12f',px,py,pz,1.0,v[3],v[4],v[5],0.0,v[6],v[7],0.0,0.0)
    Path(dst).parent.mkdir(parents=True,exist_ok=True);Path(dst).write_bytes(out)
    Path(meta).write_text(str(len(verts))+'\n')
    print(f'baked {len(verts)} triangle-list vertices ({len(out)} bytes)')

if __name__=='__main__':
    if len(sys.argv)!=4: raise SystemExit('usage: glb_to_blacksite_bin.py in.glb out.bin out.count')
    bake(*sys.argv[1:])
