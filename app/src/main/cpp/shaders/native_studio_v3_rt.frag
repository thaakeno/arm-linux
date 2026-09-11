#version 460
#extension GL_EXT_ray_query : require

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
layout(set = 0, binding = 1) uniform accelerationStructureEXT topLevelAS;

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

float hash21(vec2 p) {
    p = fract(p * vec2(123.34,345.45));
    p += dot(p,p+34.345);
    return fract(p.x*p.y);
}
float valueNoise(vec2 p) {
    vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
    float a=hash21(i), b=hash21(i+vec2(1,0)), c=hash21(i+vec2(0,1)), d=hash21(i+vec2(1,1));
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}
float fbm(vec2 p) {
    float v=0.0,a=0.5;
    for(int i=0;i<4;i++){v+=valueNoise(p)*a;p=p*2.03+vec2(17.1,9.2);a*=0.5;}
    return v;
}
vec3 materialBase(int m) {
    if(m==0)return vec3(0.38,0.42,0.47);
    if(m==1)return vec3(0.105,0.112,0.120);
    if(m==2)return vec3(0.66,0.69,0.72);
    if(m==3)return vec3(0.72,0.20,0.09);
    if(m==4)return vec3(0.055,0.060,0.064);
    return vec3(0.44);
}
float materialMetallic(int m){return (m==0||m==2)?0.82:0.0;}
float materialRoughness(int m){if(m==0)return 0.28;if(m==1)return 0.78;if(m==2)return 0.18;if(m==3)return 0.33;if(m==4)return 0.64;return 0.5;}
vec3 environment(vec3 r){
    float t=clamp(r.y*0.5+0.5,0.0,1.0);
    vec3 env=mix(vec3(0.018,0.021,0.025),vec3(0.46,0.51,0.58),smoothstep(0.04,0.96,t));
    vec3 a=normalize(vec3(-0.52,0.66,0.55)), b=normalize(vec3(0.72,0.28,-0.48));
    env+=vec3(1.0,0.94,0.85)*0.55*pow(max(dot(r,a),0.0),22.0);
    env+=vec3(0.48,0.59,0.76)*0.20*pow(max(dot(r,b),0.0),28.0);
    return env;
}
vec3 aces(vec3 x){const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

bool traceOcclusion(vec3 origin, vec3 dir, float tMax) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, topLevelAS,
        gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
        0xff, origin, 0.018, dir, tMax);
    while(rayQueryProceedEXT(rq)) { }
    return rayQueryGetIntersectionTypeEXT(rq,true) != gl_RayQueryCommittedIntersectionNoneEXT;
}

int traceReflection(vec3 origin, vec3 dir, out vec3 hitPos, out vec3 hitN, out int hitMat) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, topLevelAS, gl_RayFlagsOpaqueEXT,
        0xff, origin, 0.025, dir, 40.0);
    while(rayQueryProceedEXT(rq)) { }
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT) return -1;
    float t=rayQueryGetIntersectionTEXT(rq,true);
    hitPos=origin+dir*t;
    int custom=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));
    if(custom==0){hitN=vec3(0,1,0);hitMat=1;}
    else if(custom==1){
        vec3 a=abs(hitPos); if(a.x>a.y&&a.x>a.z)hitN=vec3(sign(hitPos.x),0,0);
        else if(a.y>a.z)hitN=vec3(0,sign(hitPos.y),0); else hitN=vec3(0,0,sign(hitPos.z));
        hitMat=0;
    } else {
        int bi=custom-2;
        hitN=normalize(hitPos-bodies[bi].posRad.xyz);
        hitMat=bodies[bi].meta.x;
    }
    return custom;
}

void main(){
    vec3 N=normalize(inWorldNormal), V=normalize(inCameraPos-inWorldPos);
    if(dot(N,V)<=0.0)discard;
    float q=clamp(pc.quality/100.0,0.0,1.0);
    vec3 base=materialBase(inMaterial);
    float metallic=materialMetallic(inMaterial), rough=materialRoughness(inMaterial);

    if(q>0.18){
        vec2 p=inUv*mix(9.0,55.0,q)+inWorldPos.xz*3.0;
        float n=fbm(p*0.7), fine=valueNoise(p*7.0);
        base*=mix(0.91,1.08,n)*mix(0.975,1.025,fine);
        rough=clamp(rough+(fine-0.5)*0.10*q,0.08,0.95);
    }
    if(inMaterial==1&&q>0.28){
        vec2 cell=abs(fract(inWorldPos.xz*0.5+0.5)-0.5);
        float seam=smoothstep(0.475,0.495,max(cell.x,cell.y));
        base*=mix(1.0,0.72,seam);
    }

    vec3 lightPos=vec3(-3.7,6.2,4.8);
    vec3 toL=lightPos-inWorldPos; float lightDist=length(toL); vec3 L=toL/lightDist;
    float shadow=1.0;
    if(pc.rtEnabled>0.5&&q>=0.48){
        int samples=(q>0.88)?2:1;
        float visible=0.0;
        for(int s=0;s<2;s++){
            if(s>=samples)break;
            vec2 seed=gl_FragCoord.xy+vec2(float(s)*71.3,pc.time*11.0);
            vec2 j=vec2(hash21(seed),hash21(seed.yx+17.0))-0.5;
            vec3 samplePos=lightPos+vec3(j.x*0.75,0.0,j.y*0.75)*q;
            vec3 ld=samplePos-inWorldPos; float d=length(ld); ld/=d;
            visible+=traceOcclusion(inWorldPos+N*0.022,ld,d-0.035)?0.0:1.0;
        }
        shadow=visible/float(samples);
    }

    vec3 H=normalize(L+V);
    float ndl=max(dot(N,L),0.0), ndv=max(dot(N,V),0.001), ndh=max(dot(N,H),0.0), vdh=max(dot(V,H),0.0);
    float alpha=rough*rough,a2=alpha*alpha,den=ndh*ndh*(a2-1.0)+1.0;
    float D=a2/max(3.14159265*den*den,0.0001);
    float k=(rough+1.0);k=k*k/8.0;
    float Gv=ndv/(ndv*(1.0-k)+k),Gl=ndl/(ndl*(1.0-k)+k);
    vec3 F0=mix(vec3(0.04),base,metallic);
    vec3 F=F0+(1.0-F0)*pow(1.0-vdh,5.0);
    vec3 spec=(D*Gv*Gl*F)/max(4.0*ndv*ndl,0.001);
    vec3 kd=(1.0-F)*(1.0-metallic);
    float intensity=58.0/max(dot(toL,toL),0.01);
    vec3 direct=(kd*base/3.14159265+spec)*ndl*intensity*vec3(1.0,0.93,0.84)*shadow;

    vec3 R=reflect(-V,N);
    vec3 ambient=environment(N)*base*(0.10+0.12*(1.0-metallic));
    vec3 refl=environment(R)*F*mix(0.24,0.045,rough);

    if(pc.rtEnabled>0.5&&q>=0.62&&(metallic>0.25||rough<0.42)){
        vec3 hp,hn;int hm;
        if(traceReflection(inWorldPos+N*0.028,R,hp,hn,hm)>=0){
            vec3 hb=materialBase(hm);
            vec3 hL=normalize(lightPos-hp);
            float hdiff=max(dot(hn,hL),0.0);
            float occ=traceOcclusion(hp+hn*0.025,hL,length(lightPos-hp)-0.04)?0.22:1.0;
            vec3 bounced=hb*(0.12+0.88*hdiff*occ)+environment(reflect(-R,hn))*0.12;
            float reflWeight=mix(0.10,0.68,metallic)*(1.0-rough*0.68)*smoothstep(0.58,0.98,q);
            refl=mix(refl,bounced,reflWeight);
        }
    }

    vec3 color=ambient+refl+direct;
    color=aces(color*1.24);
    color=pow(color,vec3(1.0/2.2));
    outColor=vec4(color,1.0);
}
