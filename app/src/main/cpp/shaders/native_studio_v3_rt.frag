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

const float PI=3.14159265358979323846;

float hash21(vec2 p){p=fract(p*vec2(123.34,345.45));p+=dot(p,p+34.345);return fract(p.x*p.y);}
float valueNoise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);float a=hash21(i),b=hash21(i+vec2(1,0)),c=hash21(i+vec2(0,1)),d=hash21(i+vec2(1,1));return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);}
float fbm(vec2 p){float v=0.0,a=0.5;for(int i=0;i<5;i++){v+=valueNoise(p)*a;p=p*2.013+vec2(17.1,9.2);a*=0.5;}return v;}

struct Material{vec3 base;float metallic;float roughness;float clearcoat;vec3 emission;};

Material materialAt(int m,vec3 P,vec3 N,vec2 uv,float q){
    Material mat;mat.base=vec3(0.5);mat.metallic=0.0;mat.roughness=0.5;mat.clearcoat=0.0;mat.emission=vec3(0.0);
    if(m==0){
        float broad=fbm(P.xz*0.72+P.xy*0.18);float pores=valueNoise(P.xz*18.0+P.zy*3.0);float vein=smoothstep(0.69,0.82,fbm(P.xz*1.75+vec2(11.0,2.0)));
        mat.base=mix(vec3(0.43,0.45,0.46),vec3(0.67,0.64,0.57),broad*0.62);mat.base*=mix(0.90,1.04,pores);mat.base=mix(mat.base,mat.base*vec3(0.72,0.69,0.64),vein*0.38);mat.roughness=mix(0.56,0.40,q)*mix(1.08,0.92,pores);
    }else if(m==1){
        vec2 p=P.xz*0.32;vec2 cell=abs(fract(p)-0.5);float grout=1.0-smoothstep(0.465,0.492,max(cell.x,cell.y));float n=fbm(P.xz*0.58);float chips=valueNoise(P.xz*27.0);vec3 stone=mix(vec3(0.070,0.076,0.081),vec3(0.145,0.150,0.153),n);stone=mix(stone,stone+vec3(0.035),smoothstep(0.88,0.96,chips)*0.55);mat.base=mix(vec3(0.030,0.033,0.035),stone,grout);mat.roughness=mix(0.32,0.20,q);mat.clearcoat=0.14*q;
    }else if(m==2){
        float brush=0.5+0.5*sin((uv.x*2.0-1.0)*150.0+valueNoise(uv*70.0)*2.0);mat.base=vec3(0.56,0.59,0.62)*mix(0.93,1.05,brush*0.35);mat.metallic=1.0;mat.roughness=mix(0.26,0.15,q);
    }else if(m==3){
        float skin=valueNoise(uv*42.0);mat.base=vec3(0.92,0.16,0.035)*mix(0.94,1.03,skin);mat.roughness=mix(0.34,0.22,q);mat.clearcoat=0.72;
    }else if(m==4){
        mat.base=vec3(1.0,0.79,0.52);mat.roughness=0.16;mat.emission=vec3(11.0,7.1,3.6);
    }
    return mat;
}

vec3 perturbNormal(vec3 N,int m,vec3 P,float q){
    if(q<0.34||(m!=0&&m!=1))return N;float eps=0.018;vec2 p=(m==1?P.xz*0.58:P.xz*1.15);float h=valueNoise(p*8.0),hx=valueNoise((p+vec2(eps,0))*8.0),hz=valueNoise((p+vec2(0,eps))*8.0);vec3 T=normalize(abs(N.y)<0.95?cross(vec3(0,1,0),N):vec3(1,0,0));vec3 B=normalize(cross(N,T));float s=(m==1?0.10:0.075)*q;return normalize(N+T*(h-hx)*s/eps+B*(h-hz)*s/eps);
}

vec3 environment(vec3 r){float t=clamp(r.y*0.5+0.5,0.0,1.0);vec3 env=mix(vec3(0.010,0.012,0.014),vec3(0.16,0.19,0.23),smoothstep(0.0,1.0,t));vec3 s1=normalize(vec3(-0.58,0.69,0.42)),s2=normalize(vec3(0.72,0.32,-0.61));env+=vec3(1.0,0.93,0.82)*1.8*pow(max(dot(r,s1),0.0),38.0);env+=vec3(0.34,0.46,0.66)*0.52*pow(max(dot(r,s2),0.0),26.0);return env;}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float D_GGX(float NoH,float rough){float a=rough*rough,a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float G_Smith(float NoV,float NoL,float rough){float r=rough+1.0,k=r*r/8.0;float gv=NoV/(NoV*(1.0-k)+k),gl=NoL/(NoL*(1.0-k)+k);return gv*gl;}
vec3 aces(vec3 x){const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

float rayEps(int objectId){if(objectId>=2){int bi=objectId-2;return max(0.035,bodies[bi].posRad.w*0.055);}return 0.045;}

bool traceOcclusion(vec3 origin,vec3 dir,float tMax,int sourceObject){
    rayQueryEXT rq;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsTerminateOnFirstHitEXT|gl_RayFlagsOpaqueEXT,0xff,origin,rayEps(sourceObject),dir,tMax);
    while(rayQueryProceedEXT(rq)){}
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return false;
    int hit=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));
    return hit!=sourceObject;
}

vec2 cubeUv(vec3 p,vec3 n){vec3 a=abs(n);if(a.x>a.y&&a.x>a.z)return p.zy*0.5+0.5;if(a.y>a.z)return p.xz*0.5+0.5;return p.xy*0.5+0.5;}
vec2 sphereUv(vec3 n){float u=atan(n.z,n.x)/(2.0*PI)+0.5;float v=acos(clamp(n.y,-1.0,1.0))/PI;return vec2(u,v);}

int traceReflection(vec3 origin,vec3 dir,int sourceObject,out vec3 hitPos,out vec3 hitN,out vec2 hitUv,out int hitMat){
    rayQueryEXT rq;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsOpaqueEXT,0xff,origin,rayEps(sourceObject),dir,48.0);while(rayQueryProceedEXT(rq)){}
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return -1;
    int custom=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));
    if(custom==sourceObject)return -1;
    float t=rayQueryGetIntersectionTEXT(rq,true);hitPos=origin+dir*t;
    if(custom==0){hitN=vec3(0,1,0);hitMat=1;hitUv=hitPos.xz*0.08+0.5;}
    else if(custom==1){vec3 a=abs(hitPos);if(a.x>a.y&&a.x>a.z)hitN=vec3(sign(hitPos.x),0,0);else if(a.y>a.z)hitN=vec3(0,sign(hitPos.y),0);else hitN=vec3(0,0,sign(hitPos.z));hitMat=0;hitUv=cubeUv(hitPos,hitN);}
    else{int bi=custom-2;hitN=normalize(hitPos-bodies[bi].posRad.xyz);hitMat=bodies[bi].meta.x;hitUv=sphereUv(hitN);}
    return custom;
}

vec3 directPbr(Material mat,vec3 P,vec3 N,vec3 V,vec3 lightSample,float visibility){
    if(max(mat.emission.r,max(mat.emission.g,mat.emission.b))>0.0)return mat.emission;
    vec3 toL=lightSample-P;float d2=max(dot(toL,toL),0.04);vec3 L=normalize(toL),H=normalize(L+V);float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),0.001),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(0.04),mat.base,mat.metallic),F=fresnelSchlick(VoH,F0);vec3 spec=D_GGX(NoH,mat.roughness)*G_Smith(NoV,NoL,mat.roughness)*F/max(4.0*NoV*NoL,0.001);vec3 kd=(1.0-F)*(1.0-mat.metallic);return (kd*mat.base/PI+spec)*NoL*(88.0/d2)*vec3(1.0,0.78,0.58)*visibility;
}

vec3 shadeHit(Material mat,vec3 P,vec3 N,vec3 V,int objectId,vec3 lightPos,float q,bool allowShadow){
    if(max(mat.emission.r,max(mat.emission.g,mat.emission.b))>0.0)return mat.emission;
    float vis=1.0;
    if(allowShadow){vec3 toL=lightPos-P;float d=length(toL);vis=traceOcclusion(P+N*rayEps(objectId),toL/d,d-0.08,objectId)?0.0:1.0;}
    vec3 direct=directPbr(mat,P,N,V,lightPos,vis);vec3 R=reflect(-V,N);vec3 F0=mix(vec3(0.04),mat.base,mat.metallic);vec3 F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 ambient=mat.base*environment(N)*(0.065+0.085*(1.0-mat.metallic));vec3 ibl=environment(R)*F*mix(0.24,0.045,mat.roughness);if(mat.clearcoat>0.0)ibl+=environment(R)*fresnelSchlick(max(dot(N,V),0.0),vec3(0.04))*mat.clearcoat*0.16;return ambient+ibl+direct;
}

void main(){
    float q=clamp(pc.quality/100.0,0.0,1.0);vec3 N=normalize(inWorldNormal),V=normalize(inCameraPos-inWorldPos);if(dot(N,V)<=0.0)discard;N=perturbNormal(N,inMaterial,inWorldPos,q);Material mat=materialAt(inMaterial,inWorldPos,N,inUv,q);
    vec3 lightPos=bodies[2].posRad.xyz;float shadow=1.0;

    if(pc.rtEnabled>0.5&&q>=0.42&&inMaterial!=4){
        // The light is the visible warm sphere (body 2). At high quality sample
        // several points across its physical radius for true ray-query soft shadows.
        vec3 toCenter=lightPos-inWorldPos;float dist=length(toCenter);vec3 Lc=toCenter/dist;vec3 T=normalize(abs(Lc.y)<0.95?cross(Lc,vec3(0,1,0)):cross(Lc,vec3(1,0,0)));vec3 B=cross(Lc,T);float r=max(0.18,bodies[2].posRad.w*0.72);
        vec3 samples[3]=vec3[3](lightPos,lightPos+T*r*0.58+B*r*0.22,lightPos-T*r*0.43+B*r*0.51);int count=q>0.90?3:(q>0.68?2:1);float visible=0.0;
        for(int i=0;i<3;i++){if(i>=count)break;vec3 d=samples[i]-inWorldPos;float dl=length(d);visible+=traceOcclusion(inWorldPos+N*rayEps(inObject),d/dl,dl-0.08,inObject)?0.0:1.0;}
        shadow=visible/float(count);
    }

    vec3 color;
    if(max(mat.emission.r,max(mat.emission.g,mat.emission.b))>0.0){color=mat.emission;}
    else{
        vec3 direct=directPbr(mat,inWorldPos,N,V,lightPos,shadow);vec3 R=reflect(-V,N);vec3 F0=mix(vec3(0.04),mat.base,mat.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 ambient=mat.base*environment(N)*(0.065+0.085*(1.0-mat.metallic));vec3 refl=environment(R)*F*mix(0.24,0.045,mat.roughness);if(mat.clearcoat>0.0)refl+=environment(R)*fresnelSchlick(max(dot(N,V),0.0),vec3(0.04))*mat.clearcoat*0.16;

        if(pc.rtEnabled>0.5&&q>=0.58&&(mat.metallic>0.18||mat.clearcoat>0.18||mat.roughness<0.34)){
            // One physically meaningful secondary ray. The hit is shaded through the
            // same material path as the primary surface, including floor/cube detail.
            vec3 tangent=normalize(abs(N.y)<0.95?cross(N,vec3(0,1,0)):vec3(1,0,0));vec3 bitangent=cross(N,tangent);float roughSpread=mat.roughness*mat.roughness*0.20;vec2 j=vec2(hash21(gl_FragCoord.xy),hash21(gl_FragCoord.yx+37.0))-0.5;vec3 rayDir=normalize(R+(tangent*j.x+bitangent*j.y)*roughSpread);
            vec3 hp,hn;vec2 huv;int hm;int hid=traceReflection(inWorldPos+N*rayEps(inObject),rayDir,inObject,hp,hn,huv,hm);
            if(hid>=0){float hq=q;hn=perturbNormal(normalize(hn),hm,hp,hq);Material hmat=materialAt(hm,hp,hn,huv,hq);vec3 bounced=shadeHit(hmat,hp,hn,normalize(-rayDir),hid,lightPos,hq,q>0.78);float w=(0.18+0.70*mat.metallic+0.18*mat.clearcoat)*(1.0-mat.roughness*0.78);w*=smoothstep(0.55,0.96,q);refl=mix(refl,bounced*F,w);}
        }
        color=ambient+refl+direct;
    }

    color=aces(color*1.05);color=pow(color,vec3(1.0/2.2));outColor=vec4(color,1.0);
}