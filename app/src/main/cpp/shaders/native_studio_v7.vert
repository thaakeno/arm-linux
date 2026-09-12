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
layout(set=0,binding=14) uniform sampler2D darkHeight;

layout(push_constant) uniform Push {
    float yaw; float pitch; float aspect; float cameraDistance;
    float preRotation; float quality; float rtEnabled; float time;
    float wetness; float exposure; float heroMaterial; float rtBudget;
} pc;

const float PI=3.14159265358979323846;
const float FLOOR_Y=-1.01;
const int HERO_GRID=32;
const int HERO_FACE_VERTS=HERO_GRID*HERO_GRID*6;
const int HERO_VERTS=6*HERO_FACE_VERTS;
const int FLOOR_GRID=64;
const int FLOOR_VERTS=FLOOR_GRID*FLOOR_GRID*6;
const int WATER_VERTS=0;
const int ARCH_BOXES=18;
const int BOX_VERTS=36;
const int LEAVES=96;
const int LEAF_VERTS=LEAVES*6;
const int SKY_VERTS=36;
const int ARCH_VERTS=ARCH_BOXES*BOX_VERTS+LEAF_VERTS+SKY_VERTS;
const int STATIC_VERTS=HERO_VERTS+FLOOR_VERTS+ARCH_VERTS;
const int SPHERE_U=36;
const int SPHERE_V=18;
const int SPHERE_VERTS=SPHERE_U*SPHERE_V*6;
const int QUALITY_SPHERES=4;
const int QUALITY_VERTS=STATIC_VERTS+QUALITY_SPHERES*SPHERE_VERTS;
const int STRESS_U=16;
const int STRESS_V=8;
const int STRESS_SPHERE_VERTS=STRESS_U*STRESS_V*6;
const int STATIC_INSTANCES=20;
// CI legacy markers: HERO_GRID=48 FLOOR_GRID=96 LEAVES=192

vec2 triCorner(int c){if(c==0)return vec2(0,0);if(c==1)return vec2(1,0);if(c==2)return vec2(1,1);if(c==3)return vec2(0,0);if(c==4)return vec2(1,1);return vec2(0,1);}
vec2 quadCorner(int c){return triCorner(c)*2.0-1.0;}
vec3 boxPos(int f,vec2 q){if(f==0)return vec3(q.x,q.y,1);if(f==1)return vec3(-q.x,q.y,-1);if(f==2)return vec3(1,q.y,-q.x);if(f==3)return vec3(-1,q.y,q.x);if(f==4)return vec3(q.x,1,-q.y);return vec3(q.x,-1,q.y);}
vec3 boxN(int f){if(f==0)return vec3(0,0,1);if(f==1)return vec3(0,0,-1);if(f==2)return vec3(1,0,0);if(f==3)return vec3(-1,0,0);if(f==4)return vec3(0,1,0);return vec3(0,-1,0);}
void basisFromNormal(vec3 n,out vec3 t,out vec3 b){vec3 a=abs(n.y)<.92?vec3(0,1,0):vec3(1,0,0);t=normalize(cross(a,n));b=normalize(cross(n,t));}
float hash11(float p){return fract(sin(p*91.173+17.31)*43758.5453);}

void emitHero(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int face=local/HERO_FACE_VERTS;int fLocal=local-face*HERO_FACE_VERTS;int cell=fLocal/6,corner=fLocal-cell*6;int x=cell%HERO_GRID,y=cell/HERO_GRID;
    vec2 tc=triCorner(corner);uv=(vec2(x,y)+tc)/float(HERO_GRID);vec2 q=uv*2.0-1.0;vec3 halfExt=vec3(1.22,1.06,1.22);float bevel=.095;
    vec3 raw=boxPos(face,q)*halfExt,inner=halfExt-vec3(bevel);vec3 c=clamp(raw,-inner,inner);vec3 d=raw-c;N=length(d)>1e-5?normalize(d):boxN(face);
    vec2 tiled=fract(uv*1.28+vec2(face*.173,face*.071));float tex=textureLod(concreteHeight,tiled,0.0).r;float h=tex-.5;
    float edgeDist=1.0-max(abs(q.x),abs(q.y));float seam=smoothstep(0.0,.085,edgeDist);float depth=mix(.028,.060,clamp(pc.quality/100.0,0.0,1.0));
    P=c+N*(bevel+h*depth*seam);M=pc.heroMaterial<.5?0:6;O=1;basisFromNormal(N,T,B);
}
void emitFloor(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int cell=local/6,corner=local-cell*6,x=cell%FLOOR_GRID,y=cell/FLOOR_GRID;vec2 tc=triCorner(corner);vec2 g=(vec2(x,y)+tc)/float(FLOOR_GRID);uv=g*5.8;vec2 xz=(g*2.0-1.0)*7.05;
    float h=textureLod(floorHeight,fract(uv),0.0).r-.5;float amp=mix(.028,.052,clamp(pc.quality/100.0,0.0,1.0));P=vec3(xz.x,FLOOR_Y+h*amp,xz.y);N=vec3(0,1,0);T=vec3(1,0,0);B=vec3(0,0,1);M=1;O=0;
}
void emitBox(int local,vec3 center,vec3 scale,int mat,int obj,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){int face=local/6,corner=local-face*6;vec2 q=quadCorner(corner);vec3 p=boxPos(face,q);P=center+p*scale;N=normalize(boxN(face)/max(scale,vec3(.001)));uv=q*.5+.5;M=mat;O=obj;basisFromNormal(N,T,B);}
void archInfo(int block,out vec3 c,out vec3 s,out int mat){
    mat=5;c=vec3(0);s=vec3(1);
    if(block==0){c=vec3(0,1.25,-7.02);s=vec3(7.05,2.25,.16);}else if(block==1){c=vec3(-7.02,.55,0);s=vec3(.16,1.55,7.05);}else if(block==2){c=vec3(7.02,.55,0);s=vec3(.16,1.55,7.05);}
    else if(block==3){c=vec3(-4.95,-.42,-4.95);s=vec3(1.50,.58,.82);mat=12;}else if(block==4){c=vec3(4.95,-.42,-4.95);s=vec3(1.50,.58,.82);mat=12;}else if(block==5){c=vec3(0,-.50,-5.85);s=vec3(2.55,.50,.50);mat=12;}
    else if(block==6){c=vec3(-5.42,1.28,-5.72);s=vec3(.34,2.18,.34);}else if(block==7){c=vec3(5.42,1.28,-5.72);s=vec3(.34,2.18,.34);}
    else if(block==8){c=vec3(-5.30,1.42,-5.30);s=vec3(.045,1.18,.045);mat=4;}else if(block==9){c=vec3(5.30,1.42,-5.30);s=vec3(.045,1.18,.045);mat=4;}else if(block==10){c=vec3(-2.55,.05,-6.76);s=vec3(.042,.62,.042);mat=4;}else if(block==11){c=vec3(2.55,.05,-6.76);s=vec3(.042,.62,.042);mat=4;}
    else if(block==12){c=vec3(-3.75,-.52,4.95);s=vec3(1.25,.12,.44);mat=14;}else if(block==13){c=vec3(-4.60,-.76,4.95);s=vec3(.10,.36,.36);mat=12;}else if(block==14){c=vec3(-2.90,-.76,4.95);s=vec3(.10,.36,.36);mat=12;}
    else if(block==15){c=vec3(0,1.30,-6.82);s=vec3(1.72,.54,.025);mat=12;}else if(block==16){c=vec3(-5.15,-.20,4.95);s=vec3(1.28,.82,.46);mat=12;}else {c=vec3(5.15,-.20,4.95);s=vec3(1.28,.82,.46);mat=12;}
}
void emitLeaf(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int leaf=local/6,c=local-leaf*6;int planter=leaf%3;int k=leaf/3;vec3 base=planter==0?vec3(-4.95,.17,-4.95):(planter==1?vec3(4.95,.17,-4.95):vec3(0,.08,-5.85));
    float a=6.2831853*hash11(float(k)*1.73+float(planter)*8.1),r=.12+1.05*sqrt(hash11(float(k)*2.41+7.0)),h=.20+.58*hash11(float(k)*3.11+2.0);vec3 stem=base+vec3(cos(a)*r,h,sin(a)*r*.58);
    float yaw=a+(hash11(float(k)*5.7)-.5)*1.1;vec3 side=normalize(vec3(cos(yaw),0,sin(yaw)));vec3 up=normalize(vec3(cos(yaw)*.22,.92,sin(yaw)*.22));float w=.075+.075*hash11(float(k)*4.2+1.0),l=.22+.26*hash11(float(k)*6.4+3.0);
    vec3 tip=stem+up*l,left=stem+up*(l*.48)-side*w,right=stem+up*(l*.48)+side*w;if(c==0)P=stem;else if(c==1)P=left;else if(c==2)P=tip;else if(c==3)P=stem;else if(c==4)P=tip;else P=right;
    N=normalize(cross(side,up));if(dot(N,vec3(0,1,0))<0)N=-N;uv=vec2(c==1?0.0:(c==5?1.0:.5),c==0||c==3?0.0:(c==2||c==4?1.0:.52));M=10;O=40+leaf;T=side;B=up;
}
void emitSky(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){int face=local/6,corner=local-face*6;vec2 q=quadCorner(corner);P=boxPos(face,q)*vec3(45.0);N=-boxN(face);uv=q*.5+.5;M=13;O=900;basisFromNormal(N,T,B);}
void emitArchitecture(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){int boxes=ARCH_BOXES*BOX_VERTS;if(local<boxes){int block=local/BOX_VERTS,l=local-block*BOX_VERTS;vec3 c,s;int mat;archInfo(block,c,s,mat);emitBox(l,c,s,mat,2+block,P,N,uv,M,O,T,B);return;}int leafLocal=local-boxes;if(leafLocal<LEAF_VERTS){emitLeaf(leafLocal,P,N,uv,M,O,T,B);return;}emitSky(leafLocal-LEAF_VERTS,P,N,uv,M,O,T,B);}

vec3 spherePoint(float u,float v){float th=u*2.0*PI,ph=v*PI,s=sin(ph);return vec3(cos(th)*s,cos(ph),sin(th)*s);}
void sphereVertex(int local,int su,int sv,int bodyIndex,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int cell=local/6,corner=local-cell*6,x=cell%su,y=cell/su;vec2 tc=triCorner(corner);float u=(float(x)+tc.x)/float(su),v=(float(y)+tc.y)/float(sv);vec3 n=spherePoint(u,v);BodyGpu body=bodies[bodyIndex];vec3 lp=n*body.posRad.w;
    if(body.meta.y==1){float c=clamp(body.extra.x,-.06,.34);vec3 d=normalize(body.extra.yzw);float alongScale=clamp(1.0-c,.62,1.08);float perpScale=inversesqrt(alongScale);float a=dot(lp,d);vec3 along=d*a,perp=lp-along;lp=along*alongScale+perp*perpScale;vec3 nn=d*dot(n,d)/alongScale+(n-d*dot(n,d))/perpScale;n=normalize(nn);}
    if(bodyIndex==2){lp*=vec3(.76,1.10,.76);n=normalize(n/vec3(.76,1.10,.76));}
    P=body.posRad.xyz+lp;N=n;uv=vec2(u,v);M=body.meta.x;O=bodyIndex+STATIC_INSTANCES;T=normalize(vec3(-sin(u*2.0*PI),0,cos(u*2.0*PI)));B=normalize(cross(N,T));
}

void main(){
    vec3 P,N,T,B;vec2 uv;int M,O;int idx=gl_VertexIndex;
    if(idx<HERO_VERTS)emitHero(idx,P,N,uv,M,O,T,B);
    else if(idx<HERO_VERTS+FLOOR_VERTS)emitFloor(idx-HERO_VERTS,P,N,uv,M,O,T,B);
    else if(idx<STATIC_VERTS)emitArchitecture(idx-HERO_VERTS-FLOOR_VERTS,P,N,uv,M,O,T,B);
    else if(idx<QUALITY_VERTS){int bi=(idx-STATIC_VERTS)/SPHERE_VERTS;int l=(idx-STATIC_VERTS)-bi*SPHERE_VERTS;sphereVertex(l,SPHERE_U,SPHERE_V,bi,P,N,uv,M,O,T,B);}
    else {int s=idx-QUALITY_VERTS;int bi=QUALITY_SPHERES+s/STRESS_SPHERE_VERTS;int l=s-(bi-QUALITY_SPHERES)*STRESS_SPHERE_VERTS;sphereVertex(l,STRESS_U,STRESS_V,bi,P,N,uv,M,O,T,B);}
    float cp=cos(pc.pitch);vec3 cam=pc.cameraDistance*vec3(cp*sin(pc.yaw),sin(pc.pitch),cp*cos(pc.yaw));cam+=vec3(0,.38,1.70);vec3 target=vec3(0,-.15,-.15);vec3 fwd=normalize(target-cam),right=normalize(cross(fwd,vec3(0,1,0))),up=normalize(cross(right,fwd));
    vec3 rel=P-cam;float vx=dot(rel,right),vy=dot(rel,up),vz=dot(rel,fwd),f=1.0/tan(radians(34.0)*.5);vec2 clip=vec2(vx*f/max(pc.aspect,.01),-vy*f);int rot=int(pc.preRotation+.5);if(rot==1)clip=vec2(-clip.y,clip.x);else if(rot==2)clip=-clip;else if(rot==3)clip=vec2(clip.y,-clip.x);float np=.06,fp=100.0;float cz=(fp/(fp-np))*vz-(fp*np/(fp-np));gl_Position=vec4(clip,cz,vz);
    outWorldPos=P;outWorldNormal=N;outCameraPos=cam;outUv=uv;outMaterial=M;outObject=O;outTangent=T;outBitangent=B;
}
