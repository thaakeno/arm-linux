#version 460

layout(location=0) out vec3 outWorldPos;
layout(location=1) out vec3 outWorldNormal;
layout(location=2) out vec3 outCameraPos;
layout(location=3) out vec2 outUv;
layout(location=4) flat out int outMaterial;
layout(location=5) flat out int outObject;
layout(location=6) out vec3 outTangent;
layout(location=7) out vec3 outBitangent;

struct BodyGpu { vec4 posRad; vec4 velMass; vec4 extra; ivec4 meta; };
layout(set=0,binding=0,std430) readonly buffer Bodies { BodyGpu bodies[]; };
layout(set=0,binding=5) uniform sampler2D floorHeight;
layout(set=0,binding=9) uniform sampler2D concreteHeight;
layout(push_constant) uniform Push {
    float yaw; float pitch; float aspect; float cameraDistance;
    float preRotation; float quality; float rtEnabled; float time;
} pc;

const float PI=3.14159265358979323846;
const int HERO_GRID=24;
const int HERO_FACE_VERTS=HERO_GRID*HERO_GRID*6;
const int HERO_VERTS=6*HERO_FACE_VERTS;
const int FLOOR_GRID=56;
const int FLOOR_VERTS=FLOOR_GRID*FLOOR_GRID*6;
const int ARCH_BOXES=12;
const int BOX_VERTS=36;
const int ARCH_VERTS=ARCH_BOXES*BOX_VERTS;
const int STATIC_VERTS=HERO_VERTS+FLOOR_VERTS+ARCH_VERTS;
const int SPHERE_U=40;
const int SPHERE_V=20;
const int SPHERE_VERTS=SPHERE_U*SPHERE_V*6;
const int QUALITY_SPHERES=5;
const int QUALITY_VERTS=STATIC_VERTS+QUALITY_SPHERES*SPHERE_VERTS;
const int STRESS_U=16;
const int STRESS_V=8;
const int STRESS_SPHERE_VERTS=STRESS_U*STRESS_V*6;
const int STATIC_INSTANCES=14;

vec2 triCorner(int c){
    if(c==0)return vec2(0,0);if(c==1)return vec2(1,0);if(c==2)return vec2(1,1);
    if(c==3)return vec2(0,0);if(c==4)return vec2(1,1);return vec2(0,1);
}
vec2 quadCorner(int c){return triCorner(c)*2.0-1.0;}
vec3 boxPos(int f,vec2 q){
    if(f==0)return vec3(q.x,q.y,1);if(f==1)return vec3(-q.x,q.y,-1);
    if(f==2)return vec3(1,q.y,-q.x);if(f==3)return vec3(-1,q.y,q.x);
    if(f==4)return vec3(q.x,1,-q.y);return vec3(q.x,-1,q.y);
}
vec3 boxN(int f){
    if(f==0)return vec3(0,0,1);if(f==1)return vec3(0,0,-1);
    if(f==2)return vec3(1,0,0);if(f==3)return vec3(-1,0,0);
    if(f==4)return vec3(0,1,0);return vec3(0,-1,0);
}
void basisFromNormal(vec3 n,out vec3 t,out vec3 b){
    vec3 a=abs(n.y)<.92?vec3(0,1,0):vec3(1,0,0);t=normalize(cross(a,n));b=normalize(cross(n,t));
}

void emitHero(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int face=local/HERO_FACE_VERTS;int fLocal=local-face*HERO_FACE_VERTS;
    int cell=fLocal/6,corner=fLocal-cell*6;int x=cell%HERO_GRID,y=cell/HERO_GRID;
    vec2 tc=triCorner(corner);uv=(vec2(x,y)+tc)/float(HERO_GRID);vec2 q=uv*2.0-1.0;
    vec3 unit=boxPos(face,q);vec3 halfExt=vec3(1.18,1.02,1.18);float bevel=.105;
    vec3 raw=unit*halfExt,inner=max(halfExt-vec3(bevel),vec3(.001));vec3 c=clamp(raw,-inner,inner);vec3 d=raw-c;
    N=normalize(d);if(length(d)<1e-5)N=boxN(face);
    float h=textureLod(concreteHeight,fract(uv*1.35),0.0).r-.5;
    float edge=1.0-smoothstep(.0,.22,min(min(abs(q.x),abs(q.y)),1.0));
    float chip=h*(.028+.022*edge);P=c+N*(bevel+chip);M=0;O=1;basisFromNormal(N,T,B);
}

void emitFloor(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int cell=local/6,corner=local-cell*6,x=cell%FLOOR_GRID,y=cell/FLOOR_GRID;vec2 tc=triCorner(corner);
    vec2 g=(vec2(x,y)+tc)/float(FLOOR_GRID);uv=g*4.6;vec2 xz=(g*2.0-1.0)*6.55;
    float h=textureLod(floorHeight,fract(uv),0.0).r-.5;P=vec3(xz.x,-1.01+h*.040,xz.y);N=vec3(0,1,0);T=vec3(1,0,0);B=vec3(0,0,1);M=1;O=0;
}

void emitBox(int local,vec3 center,vec3 scale,int mat,int obj,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int face=local/6,corner=local-face*6;vec2 q=quadCorner(corner);vec3 p=boxPos(face,q);
    P=center+p*scale;N=normalize(boxN(face)/max(scale,vec3(.001)));uv=q*.5+.5;M=mat;O=obj;basisFromNormal(N,T,B);
}

void emitArchitecture(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int block=local/BOX_VERTS;int l=local-block*BOX_VERTS;vec3 c,s;int mat=5;int obj=2+block;
    if(block==0){c=vec3(0,1.55,-6.45);s=vec3(6.45,2.55,.12);}             // rear brutalist wall
    else if(block==1){c=vec3(-6.45,.95,0);s=vec3(.12,1.95,6.45);}        // left wall
    else if(block==2){c=vec3(6.45,.95,0);s=vec3(.12,1.95,6.45);}         // right wall
    else if(block==3){c=vec3(-4.55,-.34,-4.75);s=vec3(1.25,.67,.78);mat=0;}
    else if(block==4){c=vec3(4.55,-.34,-4.55);s=vec3(1.25,.67,.78);mat=0;}
    else if(block==5){c=vec3(-5.05,1.35,-5.75);s=vec3(.38,2.35,.38);mat=0;}
    else if(block==6){c=vec3(5.05,1.35,-5.75);s=vec3(.38,2.35,.38);mat=0;}
    else if(block==7){c=vec3(-4.94,1.38,-5.34);s=vec3(.055,1.28,.055);mat=4;}
    else if(block==8){c=vec3(4.94,1.38,-5.34);s=vec3(.055,1.28,.055);mat=4;}
    else if(block==9){c=vec3(0,-.38,-5.65);s=vec3(2.45,.60,.48);mat=5;}
    else if(block==10){c=vec3(-3.45,-.62,4.95);s=vec3(.10,.38,.10);mat=4;}
    else {c=vec3(3.45,-.62,4.95);s=vec3(.10,.38,.10);mat=4;}
    emitBox(l,c,s,mat,obj,P,N,uv,M,O,T,B);
}

vec3 spherePoint(float u,float v){float th=u*2.0*PI,ph=v*PI,s=sin(ph);return vec3(cos(th)*s,cos(ph),sin(th)*s);}
void sphereVertex(int local,int su,int sv,int bodyIndex,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int cell=local/6,corner=local-cell*6,x=cell%su,y=cell/su;vec2 tc=triCorner(corner);
    float u=(float(x)+tc.x)/float(su),v=(float(y)+tc.y)/float(sv);vec3 n=spherePoint(u,v);BodyGpu body=bodies[bodyIndex];vec3 lp=n*body.posRad.w;
    if(body.meta.y==1){float c=clamp(body.extra.x,-.055,.11);float sy=max(.86,1.0-c),sxz=inversesqrt(sy);lp*=vec3(sxz,sy,sxz);n=normalize(n/vec3(sxz,sy,sxz));}
    P=body.posRad.xyz+lp;N=n;uv=vec2(u,v);M=body.meta.x;O=bodyIndex+STATIC_INSTANCES;
    T=normalize(vec3(-sin(u*2.0*PI),0,cos(u*2.0*PI)));B=normalize(cross(N,T));
}

void main(){
    vec3 P,N,T,B;vec2 uv;int M,O;int idx=gl_VertexIndex;
    if(idx<HERO_VERTS)emitHero(idx,P,N,uv,M,O,T,B);
    else if(idx<HERO_VERTS+FLOOR_VERTS)emitFloor(idx-HERO_VERTS,P,N,uv,M,O,T,B);
    else if(idx<STATIC_VERTS)emitArchitecture(idx-HERO_VERTS-FLOOR_VERTS,P,N,uv,M,O,T,B);
    else if(idx<QUALITY_VERTS){int b=(idx-STATIC_VERTS)/SPHERE_VERTS;int l=(idx-STATIC_VERTS)-b*SPHERE_VERTS;sphereVertex(l,SPHERE_U,SPHERE_V,b,P,N,uv,M,O,T,B);}
    else {int s=idx-QUALITY_VERTS;int bi=5+s/STRESS_SPHERE_VERTS;int l=s-(bi-5)*STRESS_SPHERE_VERTS;sphereVertex(l,STRESS_U,STRESS_V,bi,P,N,uv,M,O,T,B);}

    float cp=cos(pc.pitch);vec3 cam=pc.cameraDistance*vec3(cp*sin(pc.yaw),sin(pc.pitch),cp*cos(pc.yaw));
    cam+=vec3(0,.15,1.25);vec3 target=vec3(0,-.15,-.35);vec3 fwd=normalize(target-cam),right=normalize(cross(fwd,vec3(0,1,0))),up=normalize(cross(right,fwd));
    vec3 rel=P-cam;float vx=dot(rel,right),vy=dot(rel,up),vz=dot(rel,fwd);float f=1.0/tan(radians(46.0)*.5);
    vec2 clip=vec2(vx*f/max(pc.aspect,.01),-vy*f);int rot=int(pc.preRotation+.5);
    if(rot==1)clip=vec2(-clip.y,clip.x);else if(rot==2)clip=-clip;else if(rot==3)clip=vec2(clip.y,-clip.x);
    float np=.08,fp=90.0;float cz=(fp/(fp-np))*vz-(fp*np/(fp-np));gl_Position=vec4(clip,cz,vz);
    outWorldPos=P;outWorldNormal=N;outCameraPos=cam;outUv=uv;outMaterial=M;outObject=O;outTangent=T;outBitangent=B;
}
