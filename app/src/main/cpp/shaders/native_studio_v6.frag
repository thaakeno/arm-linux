#version 460
layout(location=0) in vec3 inWorldPos;
layout(location=1) in vec3 inWorldNormal;
layout(location=2) in vec3 inCameraPos;
layout(location=3) in vec2 inUv;
layout(location=4) flat in int inMaterial;
layout(location=5) flat in int inObject;
layout(location=6) in vec3 inTangent;
layout(location=7) in vec3 inBitangent;
layout(location=0) out vec4 outColor;

struct BodyGpu { vec4 posRad; vec4 velMass; vec4 extra; ivec4 meta; };
layout(set=0,binding=0,std430) readonly buffer Bodies { BodyGpu bodies[]; };
layout(set=0,binding=2) uniform sampler2D floorAlbedo;
layout(set=0,binding=3) uniform sampler2D floorNormal;
layout(set=0,binding=4) uniform sampler2D floorArm;
layout(set=0,binding=5) uniform sampler2D floorHeight;
layout(set=0,binding=6) uniform sampler2D concreteAlbedo;
layout(set=0,binding=7) uniform sampler2D concreteNormal;
layout(set=0,binding=8) uniform sampler2D concreteArm;
layout(set=0,binding=9) uniform sampler2D concreteHeight;
layout(set=0,binding=10) uniform sampler2D environmentMap;
layout(push_constant) uniform Push { float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time; } pc;

const float PI=3.14159265358979323846;
struct Material{vec3 base;float metallic;float roughness;float clearcoat;float transmission;float ior;vec3 absorption;vec3 emission;};

float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);}
vec3 envSample(vec3 d,float lod){return textureLod(environmentMap,envUv(d),lod).rgb;}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=max(.025,r*r),a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
vec3 tangentNormal(sampler2D tex,vec2 uv,vec3 N,vec3 T,vec3 B,float strength){vec3 n=texture(tex,uv).xyz*2.0-1.0;n.xy*=strength;return normalize(mat3(normalize(T),normalize(B),normalize(N))*normalize(n));}

float puddleMask(vec3 P,vec2 uv){
    float tileR=texture(floorArm,fract(uv)).g;float broad=hash21(floor(P.xz*.36))+hash21(floor(P.zx*.19+7.3))*.5;
    float basin=smoothstep(.82,.33,tileR)*.55+smoothstep(.78,1.20,broad)*.55;float joint=1.0-smoothstep(.025,.075,min(fract(uv.x),min(fract(uv.y),min(1.0-fract(uv.x),1.0-fract(uv.y)))));
    return clamp(basin+joint*.32,0.0,1.0);
}

Material materialAt(int m,vec3 P,vec2 uv,out float wet){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.transmission=0;a.ior=1.5;a.absorption=vec3(0);a.emission=vec3(0);wet=0;
    if(m==0){vec2 t=fract(uv*1.35);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(texture(concreteArm,t).g,.28,.92);}
    else if(m==1){vec2 t=fract(uv);vec3 alb=texture(floorAlbedo,t).rgb;float r=texture(floorArm,t).g;wet=puddleMask(P,uv);a.base=alb*mix(1.0,.61,wet);a.roughness=mix(clamp(r,.32,.92),.035,wet);a.clearcoat=wet;}
    else if(m==2){a.base=vec3(.68,.71,.74);a.metallic=1;a.roughness=.075;}
    else if(m==3){a.base=vec3(.92,.055,.012);a.roughness=.085;a.clearcoat=.28;a.transmission=.76;a.ior=1.46;a.absorption=vec3(.14,1.05,2.55);}
    else if(m==4){a.base=vec3(1,.73,.42);a.roughness=.16;a.emission=vec3(13.0,6.1,2.3);}
    else if(m==5){vec2 t=fract(uv*1.8);a.base=texture(concreteAlbedo,t).rgb*vec3(.72,.74,.76);a.roughness=clamp(texture(concreteArm,t).g+.10,.52,.96);}
    else if(m==6){a.base=vec3(.018,.022,.027);a.metallic=.12;a.roughness=.22;a.clearcoat=.18;}
    else if(m==7){a.base=vec3(.88,.87,.82);a.roughness=.18;a.clearcoat=.62;}
    else if(m==8){a.base=vec3(.955,.305,.105);a.metallic=1;a.roughness=.18;}
    else if(m==9){a.base=vec3(.74,.80,.84);a.roughness=.42;a.transmission=.68;a.ior=1.47;a.absorption=vec3(.10,.055,.035);}
    return a;
}

vec3 materialNormal(int m,vec3 P,vec2 uv,vec3 N,vec3 T,vec3 B,float wet){
    if(m==0)return tangentNormal(concreteNormal,fract(uv*1.35),N,T,B,.82);
    if(m==1){vec3 n=tangentNormal(floorNormal,fract(uv),N,T,B,mix(.92,.28,wet));return n;}
    if(m==8){float a=sin((uv.y*390.0+uv.x*31.0)*2.0*PI)*.016;return normalize(N+T*a);}
    if(m==6){float n=(hash21(floor(P.xz*155.0))-.5)*.025;return normalize(N+T*n+B*n*.7);}
    return normalize(N);
}

vec3 directPbr(Material m,vec3 P,vec3 N,vec3 V,vec3 lightPos,vec3 lightColor,float power){
    if(length(m.emission)>0.0)return m.emission;vec3 toL=lightPos-P;float d2=max(dot(toL,toL),.18);vec3 L=normalize(toL);float NoL=max(dot(N,L),0.0);if(NoL<=0)return vec3(0);
    vec3 H=normalize(V+L);float NoV=max(dot(N,V),.001),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);
    vec3 spec=Dggx(NoH,m.roughness)*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic)*(1.0-m.transmission);
    return (kd*m.base/PI+spec)*NoL*(power/d2)*lightColor;
}
vec3 sunPbr(Material m,vec3 N,vec3 V,vec3 L){
    float NoL=max(dot(N,L),0.0);if(NoL<=0)return vec3(0);vec3 H=normalize(V+L);float NoV=max(dot(N,V),.001),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);
    vec3 spec=Dggx(NoH,m.roughness)*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic)*(1.0-m.transmission);
    return (kd*m.base/PI+spec)*NoL*vec3(3.6,2.92,2.28);
}
vec3 ibl(Material m,vec3 N,vec3 V){
    vec3 R=reflect(-V,N);vec3 F0=mix(vec3(.04),m.base,m.metallic);vec3 F=fresnelSchlick(max(dot(N,V),0.0),F0);
    vec3 diffuse=envSample(N,7.0)*m.base*(1.0-m.metallic)*(1.0-m.transmission)*.46;
    float lod=mix(.15,7.5,m.roughness*m.roughness);vec3 spec=envSample(R,lod)*F*mix(1.0,.52,m.roughness);
    if(m.clearcoat>0)spec+=envSample(R,mix(.08,3.4,m.roughness))*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.58;
    return diffuse+spec;
}
vec3 tonemap(vec3 x){x*=1.14;const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

void main(){
    vec3 V=normalize(inCameraPos-inWorldPos);float wet;Material m=materialAt(inMaterial,inWorldPos,inUv,wet);vec3 N=materialNormal(inMaterial,inWorldPos,inUv,normalize(inWorldNormal),normalize(inTangent),normalize(inBitangent),wet);if(dot(N,V)<=-.08)discard;
    if(length(m.emission)>0){outColor=vec4(pow(tonemap(m.emission),vec3(1.0/2.2)),1);return;}
    vec3 sunL=normalize(vec3(-.49,.73,.47));vec3 color=ibl(m,N,V)+sunPbr(m,N,V,sunL);
    color+=directPbr(m,inWorldPos,N,V,bodies[2].posRad.xyz,vec3(1.0,.64,.36),52.0);
    color+=directPbr(m,inWorldPos,N,V,vec3(-4.9,1.4,-5.25),vec3(1.0,.55,.28),10.0);
    color+=directPbr(m,inWorldPos,N,V,vec3(4.9,1.4,-5.25),vec3(1.0,.55,.28),10.0);
    if(m.transmission>0){vec3 etaDir=refract(-V,N,1.0/m.ior);vec3 trans=envSample(etaDir,mix(1.0,6.0,m.roughness));vec3 F=fresnelSchlick(max(dot(N,V),0.0),vec3(pow((1.0-m.ior)/(1.0+m.ior),2.0)));vec3 absorb=exp(-m.absorption*1.25);color=mix(color,trans*absorb,(vec3(1)-F)*m.transmission);}
    outColor=vec4(pow(tonemap(max(color,vec3(0))),vec3(1.0/2.2)),1);
}
