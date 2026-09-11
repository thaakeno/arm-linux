#version 460

layout(location = 0) in vec3 inWorldPos;
layout(location = 1) in vec3 inWorldNormal;
layout(location = 2) in vec3 inCameraPos;
layout(location = 3) in vec2 inUv;
layout(location = 4) flat in int inMaterial;
layout(location = 5) flat in int inObject;
layout(location = 0) out vec4 outColor;

struct BodyGpu {
    vec4 posRad;
    vec4 velMass;
    vec4 extra;
    ivec4 meta;
};
layout(set = 0, binding = 0, std430) readonly buffer Bodies { BodyGpu bodies[]; };

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
    float preRotation;
    float quality;
    float rtEnabled;
    float time;
} pc;

const float PI = 3.14159265358979323846;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}
float valueNoise(vec2 p) {
    vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
    float a=hash21(i), b=hash21(i+vec2(1,0)), c=hash21(i+vec2(0,1)), d=hash21(i+vec2(1,1));
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}
float fbm(vec2 p) {
    float v=0.0,a=0.5;
    for(int i=0;i<5;i++){v+=valueNoise(p)*a;p=p*2.013+vec2(17.1,9.2);a*=0.5;}
    return v;
}

struct Material {
    vec3 base;
    float metallic;
    float roughness;
    float clearcoat;
    vec3 emission;
};

Material materialAt(int m, vec3 P, vec3 N, vec2 uv, float q) {
    Material mat;
    mat.base=vec3(0.5); mat.metallic=0.0; mat.roughness=0.5; mat.clearcoat=0.0; mat.emission=vec3(0.0);

    if(m==0){
        // Honed limestone / travertine plinth. Spatial scale never changes with quality.
        float broad=fbm(P.xz*0.72 + P.xy*0.18);
        float pores=valueNoise(P.xz*18.0 + P.zy*3.0);
        float vein=smoothstep(0.69,0.82,fbm(P.xz*1.75+vec2(11.0,2.0)));
        mat.base=mix(vec3(0.43,0.45,0.46),vec3(0.67,0.64,0.57),broad*0.62);
        mat.base*=mix(0.90,1.04,pores);
        mat.base=mix(mat.base,mat.base*vec3(0.72,0.69,0.64),vein*0.38);
        mat.roughness=mix(0.56,0.40,q)*mix(1.08,0.92,pores);
    } else if(m==1){
        // Large polished dark-stone studio floor with subtle aggregate and real fixed grout.
        vec2 p=P.xz*0.32;
        vec2 cell=abs(fract(p)-0.5);
        float grout=1.0-smoothstep(0.465,0.492,max(cell.x,cell.y));
        float n=fbm(P.xz*0.58);
        float chips=valueNoise(P.xz*27.0);
        vec3 stone=mix(vec3(0.070,0.076,0.081),vec3(0.145,0.150,0.153),n);
        stone=mix(stone,stone+vec3(0.035),smoothstep(0.88,0.96,chips)*0.55);
        mat.base=mix(vec3(0.030,0.033,0.035),stone,grout);
        mat.roughness=mix(0.32,0.20,q);
        mat.clearcoat=0.14*q;
    } else if(m==2){
        // Brushed stainless sphere.
        float brush=0.5+0.5*sin((uv.x*2.0-1.0)*150.0 + valueNoise(uv*70.0)*2.0);
        mat.base=vec3(0.56,0.59,0.62)*mix(0.93,1.05,brush*0.35);
        mat.metallic=1.0;
        mat.roughness=mix(0.26,0.15,q);
    } else if(m==3){
        // Dense translucent-looking gummy material. Transmission is handled by the RT pass;
        // raster fallback keeps a physically plausible dielectric clear coat.
        float skin=valueNoise(uv*42.0);
        mat.base=vec3(0.92,0.16,0.035)*mix(0.94,1.03,skin);
        mat.roughness=mix(0.34,0.22,q);
        mat.clearcoat=0.72;
    } else if(m==4){
        // Physical light controller orb. Grab this sphere in INTERACT mode to move the light.
        mat.base=vec3(1.0,0.79,0.52);
        mat.roughness=0.16;
        mat.emission=vec3(11.0,7.1,3.6);
    }
    return mat;
}

vec3 perturbNormal(vec3 N, int m, vec3 P, float q) {
    if(q<0.34 || (m!=0 && m!=1)) return N;
    float eps=0.018;
    vec2 p=(m==1?P.xz*0.58:P.xz*1.15);
    float h=valueNoise(p*8.0);
    float hx=valueNoise((p+vec2(eps,0.0))*8.0);
    float hz=valueNoise((p+vec2(0.0,eps))*8.0);
    vec3 T=normalize(abs(N.y)<0.95?cross(vec3(0,1,0),N):vec3(1,0,0));
    vec3 B=normalize(cross(N,T));
    float strength=(m==1?0.10:0.075)*q;
    return normalize(N + T*(h-hx)*strength/eps + B*(h-hz)*strength/eps);
}

vec3 environment(vec3 r){
    float t=clamp(r.y*0.5+0.5,0.0,1.0);
    vec3 env=mix(vec3(0.010,0.012,0.014),vec3(0.16,0.19,0.23),smoothstep(0.0,1.0,t));
    vec3 strip1=normalize(vec3(-0.58,0.69,0.42));
    vec3 strip2=normalize(vec3(0.72,0.32,-0.61));
    env+=vec3(1.0,0.93,0.82)*1.8*pow(max(dot(r,strip1),0.0),38.0);
    env+=vec3(0.34,0.46,0.66)*0.52*pow(max(dot(r,strip2),0.0),26.0);
    return env;
}

vec3 fresnelSchlick(float cosTheta, vec3 F0){return F0+(1.0-F0)*pow(1.0-cosTheta,5.0);}
float D_GGX(float NoH,float rough){float a=rough*rough,a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float G_Smith(float NoV,float NoL,float rough){float r=rough+1.0,k=r*r/8.0;float gv=NoV/(NoV*(1.0-k)+k);float gl=NoL/(NoL*(1.0-k)+k);return gv*gl;}
vec3 aces(vec3 x){const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

vec3 shade(Material mat, vec3 P, vec3 N, vec3 V, vec3 lightPos, float q){
    if(max(mat.emission.r,max(mat.emission.g,mat.emission.b))>0.0) return mat.emission;
    vec3 toL=lightPos-P; float d2=max(dot(toL,toL),0.04); vec3 L=normalize(toL); vec3 H=normalize(L+V);
    float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),0.001),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);
    vec3 F0=mix(vec3(0.04),mat.base,mat.metallic);
    vec3 F=fresnelSchlick(VoH,F0);
    vec3 spec=D_GGX(NoH,mat.roughness)*G_Smith(NoV,NoL,mat.roughness)*F/max(4.0*NoV*NoL,0.001);
    vec3 kd=(1.0-F)*(1.0-mat.metallic);
    float power=88.0;
    vec3 direct=(kd*mat.base/PI+spec)*NoL*(power/d2)*vec3(1.0,0.78,0.58);
    vec3 R=reflect(-V,N);
    vec3 ambient=mat.base*environment(N)*(0.075+0.09*(1.0-mat.metallic));
    vec3 ibl=environment(R)*F*mix(0.26,0.055,mat.roughness);
    if(mat.clearcoat>0.0){vec3 Fc=fresnelSchlick(NoV,vec3(0.04));ibl+=environment(R)*Fc*mat.clearcoat*0.18;}
    return ambient+ibl+direct;
}

void main(){
    float q=clamp(pc.quality/100.0,0.0,1.0);
    vec3 N=normalize(inWorldNormal),V=normalize(inCameraPos-inWorldPos);
    if(dot(N,V)<=0.0)discard;
    N=perturbNormal(N,inMaterial,inWorldPos,q);
    Material mat=materialAt(inMaterial,inWorldPos,N,inUv,q);

    // Body #2 is the warm practical light. It is a real physics object and can be moved.
    vec3 lightPos=bodies[2].posRad.xyz;
    vec3 color=shade(mat,inWorldPos,N,V,lightPos,q);

    // Stable exposure. Quality changes features/roughness, never spatial texture frequency.
    color=aces(color*1.05);
    color=pow(color,vec3(1.0/2.2));
    outColor=vec4(color,1.0);
}