#version 460

layout(location = 0) out vec3 outWorldPos;
layout(location = 1) out vec3 outWorldNormal;
layout(location = 2) out vec3 outCameraPos;
layout(location = 3) out vec2 outUv;
layout(location = 4) flat out int outMaterial;
layout(location = 5) flat out int outObject;

struct BodyGpu { vec4 posRad; vec4 velMass; vec4 extra; ivec4 meta; };
layout(set = 0, binding = 0, std430) readonly buffer Bodies { BodyGpu bodies[]; };
layout(push_constant) uniform Push {
    float yaw; float pitch; float aspect; float cameraDistance;
    float preRotation; float quality; float rtEnabled; float time;
} pc;

const float PI = 3.14159265358979323846;
const int BOX_VERTS = 36;
const int FLOOR_VERTS = 6;
const int ROOM_BOXES = 5;
const int STATIC_VERTS = BOX_VERTS + FLOOR_VERTS + ROOM_BOXES * BOX_VERTS;
const int SPHERE_U = 32;
const int SPHERE_V = 16;
const int SPHERE_VERTS = SPHERE_U * SPHERE_V * 6;
const int QUALITY_SPHERES = 3;
const int QUALITY_VERTS = STATIC_VERTS + SPHERE_VERTS * QUALITY_SPHERES;
const int STRESS_U = 16;
const int STRESS_V = 8;
const int STRESS_SPHERE_VERTS = STRESS_U * STRESS_V * 6;

vec2 quadCorner(int c) {
    if (c == 0) return vec2(-1,-1); if (c == 1) return vec2(1,-1);
    if (c == 2) return vec2(1,1); if (c == 3) return vec2(-1,-1);
    if (c == 4) return vec2(1,1); return vec2(-1,1);
}
vec3 boxPos(int f, vec2 q) {
    if (f==0) return vec3(q.x,q.y,1); if (f==1) return vec3(-q.x,q.y,-1);
    if (f==2) return vec3(1,q.y,-q.x); if (f==3) return vec3(-1,q.y,q.x);
    if (f==4) return vec3(q.x,1,-q.y); return vec3(q.x,-1,q.y);
}
vec3 boxN(int f) {
    if (f==0) return vec3(0,0,1); if (f==1) return vec3(0,0,-1);
    if (f==2) return vec3(1,0,0); if (f==3) return vec3(-1,0,0);
    if (f==4) return vec3(0,1,0); return vec3(0,-1,0);
}
vec3 spherePoint(float u,float v){float th=u*2.0*PI,ph=v*PI,s=sin(ph);return vec3(cos(th)*s,cos(ph),sin(th)*s);}

void emitBox(int local, vec3 center, vec3 scale, int mat, int obj,
             out vec3 P,out vec3 N,out vec2 uv,out int M,out int O){
    int face=local/6,corner=local-face*6;vec2 q=quadCorner(corner);
    vec3 p=boxPos(face,q);P=center+p*scale;N=normalize(boxN(face)/max(scale,vec3(0.001)));
    uv=q*0.5+0.5;M=mat;O=obj;
}

void sphereVertex(int local,int su,int sv,int bodyIndex,
                  out vec3 P,out vec3 N,out vec2 uv,out int M,out int O){
    int cell=local/6,corner=local-cell*6,x=cell%su,y=cell/su;
    vec2 q=quadCorner(corner)*0.5+0.5;float u=(float(x)+q.x)/float(su),v=(float(y)+q.y)/float(sv);
    vec3 n=spherePoint(u,v);BodyGpu b=bodies[bodyIndex];vec3 lp=n*b.posRad.w;
    if(b.meta.y==1){float c=clamp(b.extra.x,-0.06,0.10);float sy=max(0.84,1.0-c),sxz=inversesqrt(sy);lp*=vec3(sxz,sy,sxz);n=normalize(n/vec3(sxz,sy,sxz));}
    P=b.posRad.xyz+lp;N=n;uv=vec2(u,v);M=b.meta.x;O=bodyIndex+7;
}

void main(){
    vec3 P,N;vec2 uv;int M,O;int idx=gl_VertexIndex;
    if(idx<BOX_VERTS){
        emitBox(idx,vec3(0,0,0),vec3(1.15,1.0,1.15),0,1,P,N,uv,M,O);
    }else if(idx<BOX_VERTS+FLOOR_VERTS){
        int c=idx-BOX_VERTS;vec2 q=quadCorner(c);P=vec3(q.x*7.2,-1.01,q.y*7.2);N=vec3(0,1,0);uv=q*0.5+0.5;M=1;O=0;
    }else if(idx<STATIC_VERTS){
        int r=idx-(BOX_VERTS+FLOOR_VERTS);int block=r/BOX_VERTS;int local=r-block*BOX_VERTS;
        vec3 center,scale;
        if(block==0){center=vec3(0,2.15,-7.2);scale=vec3(7.2,3.15,0.08);}       // back
        else if(block==1){center=vec3(0,2.15,7.2);scale=vec3(7.2,3.15,0.08);}   // front
        else if(block==2){center=vec3(-7.2,2.15,0);scale=vec3(0.08,3.15,7.2);}  // left
        else if(block==3){center=vec3(7.2,2.15,0);scale=vec3(0.08,3.15,7.2);}   // right
        else {center=vec3(0,5.30,0);scale=vec3(7.2,0.08,7.2);}                  // ceiling
        emitBox(local,center,scale,5,2+block,P,N,uv,M,O);
    }else if(idx<QUALITY_VERTS){
        int b=(idx-STATIC_VERTS)/SPHERE_VERTS;int local=(idx-STATIC_VERTS)-b*SPHERE_VERTS;
        sphereVertex(local,SPHERE_U,SPHERE_V,b,P,N,uv,M,O);
    }else{
        int s=idx-QUALITY_VERTS;int bi=3+s/STRESS_SPHERE_VERTS;int local=s-(bi-3)*STRESS_SPHERE_VERTS;
        sphereVertex(local,STRESS_U,STRESS_V,bi,P,N,uv,M,O);
    }

    float cp=cos(pc.pitch);vec3 cam=pc.cameraDistance*vec3(cp*sin(pc.yaw),sin(pc.pitch),cp*cos(pc.yaw));
    vec3 fwd=normalize(-cam),right=normalize(cross(fwd,vec3(0,1,0))),up=normalize(cross(right,fwd));
    vec3 rel=P-cam;float vx=dot(rel,right),vy=dot(rel,up),vz=dot(rel,fwd);float f=1.0/tan(radians(43.0)*0.5);
    vec2 clip=vec2(vx*f/max(pc.aspect,0.01),-vy*f);int rot=int(pc.preRotation+0.5);
    if(rot==1)clip=vec2(-clip.y,clip.x);else if(rot==2)clip=-clip;else if(rot==3)clip=vec2(clip.y,-clip.x);
    float np=0.10,fp=80.0;float cz=(fp/(fp-np))*vz-(fp*np/(fp-np));gl_Position=vec4(clip,cz,vz);
    outWorldPos=P;outWorldNormal=N;outCameraPos=cam;outUv=uv;outMaterial=M;outObject=O;
}