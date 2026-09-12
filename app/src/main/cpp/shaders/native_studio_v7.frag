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
layout(set=0,binding=11) uniform sampler2D darkAlbedo;
layout(set=0,binding=12) uniform sampler2D darkNormal;
layout(set=0,binding=13) uniform sampler2D darkArm;
layout(set=0,binding=14) uniform sampler2D darkHeight;
layout(push_constant) uniform Push {
    float yaw;float pitch;float aspect;float cameraDistance;
    float preRotation;float quality;float rtEnabled;float time;
    float wetness;float exposure;float heroMaterial;float rtBudget;
} pc;

const float PI=3.14159265358979323846;
struct Material{vec3 base;float metallic;float roughness;float clearcoat;float transmission;float ior;vec3 absorption;vec3 emission;float anisotropy;float ao;};

float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);}
vec3 envSample(vec3 d,float lod){return textureLod(environmentMap,envUv(d),lod).rgb;}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=max(.022,r*r),a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Daniso(vec3 H,vec3 N,vec3 T,vec3 B,float r,float an){float a=max(.03,r*r),ax=max(.022,a*(1.0-an*.73)),ay=max(.022,a*(1.0+an*.95));float x=dot(H,T)/ax,y=dot(H,B)/ay,z=max(dot(H,N),0.0);float d=x*x+y*y+z*z;return 1.0/max(PI*ax*ay*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
vec3 tangentNormal(sampler2D tex,vec2 uv,vec3 N,vec3 T,vec3 B,float strength){vec3 n=texture(tex,uv).xyz*2.0-1.0;n.xy*=strength;return normalize(mat3(normalize(T),normalize(B),normalize(N))*normalize(n));}

float floorStoneHeight(vec2 uv){return texture(floorHeight,fract(uv)).r;}
float waterCoverage(vec2 uv){
    float h=floorStoneHeight(uv);float wet=clamp(pc.wetness,0.0,1.0);
    float waterLevel=mix(.32,.61,wet);float basin=1.0-smoothstep(waterLevel-.06,waterLevel+.025,h);
    vec2 f=fract(uv);float edge=min(min(f.x,f.y),min(1.0-f.x,1.0-f.y));float joints=1.0-smoothstep(.022,.070,edge);
    return clamp(max(basin,joints*wet*.92),0.0,1.0);
}

Material materialAt(int m,vec3 P,vec2 uv,out float water){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.transmission=0;a.ior=1.5;a.absorption=vec3(0);a.emission=vec3(0);a.anisotropy=0;a.ao=1;water=0;
    if(m==0){vec2 t=fract(uv*1.28);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(arm.g,.24,.94);a.ao=arm.r;}
    else if(m==1){vec2 t=fract(uv);vec4 arm=texture(floorArm,t);water=waterCoverage(t);float damp=clamp(pc.wetness*.34+water*.32,0.0,.52);a.base=texture(floorAlbedo,t).rgb*mix(1.0,.64,damp);a.roughness=mix(clamp(arm.g,.34,.96),.17,pc.wetness*.35);a.ao=arm.r;}
    else if(m==2){a.base=vec3(.71,.735,.76);a.metallic=1;a.roughness=.055;a.anisotropy=.16;}
    else if(m==3){a.base=vec3(1.0,.075,.018);a.roughness=.11;a.clearcoat=.34;a.transmission=.91;a.ior=1.43;a.absorption=vec3(.08,.73,1.95);}
    else if(m==4){a.base=vec3(1,.67,.31);a.roughness=.16;a.emission=vec3(8.8,3.7,1.35);}
    else if(m==5){vec2 t=fract(uv*1.35);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb*vec3(.78,.80,.82);a.roughness=clamp(arm.g+.08,.48,.96);a.ao=arm.r;}
    else if(m==6){vec2 t=fract(uv*1.20);vec4 arm=texture(darkArm,t);a.base=texture(darkAlbedo,t).rgb*vec3(.20,.22,.26);a.roughness=clamp(arm.g*.78,.20,.78);a.clearcoat=.10;a.ao=arm.r;}
    else if(m==7){a.base=vec3(.94,.925,.89);a.roughness=.14;a.clearcoat=.72;}
    else if(m==8){float scratch=.5+.5*sin(uv.y*870.0+sin(uv.x*61.0)*6.0);a.base=vec3(.955,.285,.075);a.metallic=1;a.roughness=mix(.11,.24,scratch*.34);a.anisotropy=.84;}
    else if(m==9){a.base=vec3(.78,.84,.88);a.roughness=.48;a.transmission=.68;a.ior=1.46;a.absorption=vec3(.06,.035,.018);}
    else if(m==10){float n=hash21(floor(P.xz*17.0+P.yy*9.0));a.base=mix(vec3(.055,.16,.065),vec3(.16,.36,.12),n);a.roughness=.58;a.ao=.82;}
    else if(m==11){water=waterCoverage(uv);a.base=vec3(.018,.030,.036);a.roughness=mix(.020,.055,1.0-water);a.clearcoat=1;a.transmission=.96;a.ior=1.333;a.absorption=vec3(.055,.026,.015);a.ao=1;}
    else if(m==12){a.base=vec3(.025,.032,.036);a.metallic=.58;a.roughness=.32;}
    else if(m==14){a.base=vec3(.18,.075,.026);a.roughness=.44;a.clearcoat=.10;}
    return a;
}

vec3 materialNormal(int m,vec3 P,vec2 uv,vec3 N,vec3 T,vec3 B,float water){
    float q=clamp(pc.quality/100.0,0.0,1.0);float detail=mix(.28,1.0,q);
    if(m==0)return tangentNormal(concreteNormal,fract(uv*1.28),N,T,B,.92*detail);
    if(m==1)return tangentNormal(floorNormal,fract(uv),N,T,B,mix(.92,.54,pc.wetness)*detail);
    if(m==6)return tangentNormal(darkNormal,fract(uv*1.20),N,T,B,.82*detail);
    if(m==8){float s=sin(uv.y*1160.0+sin(uv.x*47.0)*4.0)*.009;return normalize(N+T*s);}
    if(m==11){float r1=sin(P.x*.73+P.z*.51+pc.time*.18),r2=sin(P.x*1.21-P.z*.92-pc.time*.13);return normalize(N+vec3(r1*.006,0,r2*.006)*water);}
    return normalize(N);
}

vec3 brdf(Material m,vec3 N,vec3 T,vec3 B,vec3 V,vec3 L,vec3 radiance){
    float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),.001);if(NoL<=0)return vec3(0);vec3 H=normalize(V+L);float NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);
    float D=m.anisotropy>.05?Daniso(H,N,T,B,m.roughness,m.anisotropy):Dggx(NoH,m.roughness);vec3 spec=D*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic)*(1.0-m.transmission);
    return (kd*m.base/PI+spec)*NoL*radiance;
}
vec3 envSpec(vec3 R,float rough){
    float lod=mix(.08,8.2,rough*rough);vec3 c=envSample(R,lod);
    if(rough>.23){vec3 A=normalize(abs(R.y)<.9?cross(R,vec3(0,1,0)):cross(R,vec3(1,0,0))),B=cross(R,A);float s=rough*.18;c=(c+envSample(normalize(R+A*s),lod)+envSample(normalize(R-A*s),lod)+envSample(normalize(R+B*s),lod)+envSample(normalize(R-B*s),lod))/5.0;}
    return c;
}
vec3 ibl(Material m,vec3 N,vec3 V){
    vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);
    vec3 diff=envSample(N,7.2)*m.base*(1.0-m.metallic)*(1.0-m.transmission)*(.46*m.ao);
    vec3 spec=envSpec(R,m.roughness)*F*mix(1.0,.53,m.roughness);
    if(m.clearcoat>0)spec+=envSpec(R,max(.025,m.roughness*.55))*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.56;
    return diff+spec;
}
vec3 aces(vec3 x){x*=exp2(pc.exposure);const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

void main(){
    if(inMaterial==13){vec3 d=normalize(inWorldPos-inCameraPos);vec3 sky=envSample(d,0.0);outColor=vec4(pow(aces(sky),vec3(1.0/2.2)),1);return;}
    float water;Material m=materialAt(inMaterial,inWorldPos,inUv,water);if(inMaterial==11&&water<.08)discard;
    vec3 V=normalize(inCameraPos-inWorldPos),T=normalize(inTangent),B=normalize(inBitangent),N=materialNormal(inMaterial,inWorldPos,inUv,normalize(inWorldNormal),T,B,water);if(dot(N,V)<=-.08)discard;
    if(length(m.emission)>0){outColor=vec4(pow(aces(m.emission),vec3(1.0/2.2)),1);return;}
    vec3 sunL=normalize(vec3(-.53,.67,.52));vec3 color=ibl(m,N,V)+brdf(m,N,T,B,V,sunL,vec3(2.35,1.74,1.23));
    vec3 lp=bodies[2].posRad.xyz,d=lp-inWorldPos;color+=brdf(m,N,T,B,V,normalize(d),vec3(1,.62,.31)*(26.0/max(dot(d,d),.35)));
    vec3 l1=vec3(-5.30,1.42,-5.30)-inWorldPos;color+=brdf(m,N,T,B,V,normalize(l1),vec3(1,.50,.22)*(7.0/max(dot(l1,l1),.30)));
    vec3 l2=vec3(5.30,1.42,-5.30)-inWorldPos;color+=brdf(m,N,T,B,V,normalize(l2),vec3(1,.50,.22)*(7.0/max(dot(l2,l2),.30)));
    if(inMaterial==10){float back=max(dot(-N,sunL),0.0);color+=m.base*vec3(.9,.55,.22)*back*.42;}
    if(m.transmission>.05){vec3 refr=refract(-V,N,1.0/m.ior);vec3 trans=envSample(refr,mix(.7,7.0,m.roughness));float f0=pow((1.0-m.ior)/(1.0+m.ior),2.0);vec3 F=fresnelSchlick(max(dot(N,V),0.0),vec3(f0));color=mix(color,trans*exp(-m.absorption*.72),(vec3(1)-F)*m.transmission);}
    outColor=vec4(pow(aces(max(color,vec3(0))),vec3(1.0/2.2)),1);
}
