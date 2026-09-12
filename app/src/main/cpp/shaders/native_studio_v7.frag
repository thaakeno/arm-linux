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
layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;} pc;

const float PI=3.14159265358979323846;
struct Material{vec3 base;float metallic;float roughness;float clearcoat;float transmission;float ior;vec3 absorption;vec3 emission;float anisotropy;float ao;};
float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);}
vec3 envSample(vec3 d,float lod){return textureLod(environmentMap,envUv(d),lod).rgb;}
vec3 skySample(vec3 d){d=normalize(d);float h=clamp(d.y*.5+.5,0.0,1.0);vec3 zen=vec3(.19,.38,.68),hor=vec3(.72,.72,.66);vec3 c=mix(hor,zen,smoothstep(.10,.88,h));vec3 sunD=normalize(vec3(-.53,.67,.52));float sun=pow(max(dot(d,sunD),0.0),900.0);float glow=pow(max(dot(d,sunD),0.0),18.0);c+=vec3(7.0,4.9,2.8)*sun+vec3(.34,.20,.09)*glow;float ground=smoothstep(.50,.43,h);c=mix(c,vec3(.18,.20,.17),ground);return c;}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=max(.028,r*r),a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
vec3 tangentNormal(sampler2D tex,vec2 uv,vec3 N,vec3 T,vec3 B,float strength){vec3 n=texture(tex,uv).xyz*2.0-1.0;n.xy*=strength;return normalize(mat3(normalize(T),normalize(B),normalize(N))*normalize(n));}
float waterCoverage(vec2 uv){float h=texture(floorHeight,fract(uv)).r,wet=clamp(pc.wetness,0.0,1.0),level=mix(.32,.61,wet);float basin=1.0-smoothstep(level-.055,level+.028,h);vec2 f=fract(uv);float edge=min(min(f.x,f.y),min(1.0-f.x,1.0-f.y));float joints=1.0-smoothstep(.024,.068,edge);return clamp(max(basin,joints*wet*.82),0.0,1.0);}

Material materialAt(int m,vec3 P,vec2 uv,out float water){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.transmission=0;a.ior=1.5;a.absorption=vec3(0);a.emission=vec3(0);a.anisotropy=0;a.ao=1;water=0;
    if(m==0){vec2 t=fract(uv*1.28);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(arm.g,.28,.92);a.ao=arm.r;}
    else if(m==1){vec2 t=fract(uv);vec4 arm=texture(floorArm,t);water=waterCoverage(t);vec3 dry=texture(floorAlbedo,t).rgb;a.base=dry*mix(1.0,.56,water*.88);a.roughness=mix(clamp(arm.g,.38,.94),.075,water);a.clearcoat=water*.82;a.ao=arm.r;}
    else if(m==2){float scratch=.5+.5*sin(uv.y*420.0+sin(uv.x*37.0)*3.0);a.base=vec3(.19,.205,.22);a.metallic=1;a.roughness=mix(.28,.40,scratch*.35);a.anisotropy=.28;}
    else if(m==3){float pores=hash21(floor(uv*180.0));a.base=mix(vec3(.48,.012,.010),vec3(.72,.026,.018),pores*.18);a.roughness=.54;a.clearcoat=.08;a.ao=.97;}
    else if(m==4){a.base=vec3(.94,.72,.43);a.roughness=.34;a.clearcoat=.18;a.emission=vec3(6.2,3.15,1.25);}
    else if(m==5){vec2 t=fract(uv*1.15);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb*vec3(.72,.67,.57);a.roughness=clamp(arm.g+.10,.56,.96);a.ao=arm.r;}
    else if(m==6){vec2 t=fract(uv*1.20);vec4 arm=texture(darkArm,t);a.base=texture(darkAlbedo,t).rgb*vec3(.22,.235,.25);a.roughness=clamp(arm.g*.82,.24,.80);a.clearcoat=.06;a.ao=arm.r;}
    else if(m==7){a.base=vec3(.12,.20,.26);a.roughness=.62;a.clearcoat=.03;a.ao=.96;}
    else if(m==8){a.base=vec3(.40,.18,.055);a.metallic=.82;a.roughness=.35;}
    else if(m==9){a.base=vec3(.32,.37,.40);a.roughness=.70;}
    else if(m==10){float n=hash21(floor(P.xz*15.0+P.yy*7.0));a.base=mix(vec3(.045,.13,.052),vec3(.12,.29,.10),n);a.roughness=.72;a.ao=.84;}
    else if(m==12){a.base=vec3(.075,.082,.086);a.roughness=.72;a.metallic=.04;}
    else if(m==14){float grain=.5+.5*sin(uv.y*75.0+sin(uv.x*14.0)*2.0);a.base=mix(vec3(.16,.060,.020),vec3(.31,.13,.045),grain*.32);a.roughness=.58;}
    return a;
}
vec3 materialNormal(int m,vec3 P,vec2 uv,vec3 N,vec3 T,vec3 B,float water){float q=clamp(pc.quality/100.0,0.0,1.0),detail=mix(.35,1.0,q);if(m==0)return tangentNormal(concreteNormal,fract(uv*1.28),N,T,B,.88*detail);if(m==1)return tangentNormal(floorNormal,fract(uv),N,T,B,mix(.92,.30,water)*detail);if(m==6)return tangentNormal(darkNormal,fract(uv*1.20),N,T,B,.78*detail);if(m==2){float s=sin(uv.y*520.0)*.012;return normalize(N+T*s);}return normalize(N);}
vec3 brdf(Material m,vec3 N,vec3 T,vec3 B,vec3 V,vec3 L,vec3 radiance){float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),.001);if(NoL<=0)return vec3(0);vec3 H=normalize(V+L);float NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);float D=Dggx(NoH,m.roughness);vec3 spec=D*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic);return (kd*m.base/PI+spec)*NoL*radiance;}
vec3 envSpec(vec3 R,float rough){return skySample(R)*mix(1.0,.62,rough);}
vec3 ibl(Material m,vec3 N,vec3 V){vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 ambientTint=mix(vec3(.78,.86,.95),envSample(N,8.0),.10);vec3 diff=ambientTint*m.base*(1.0-m.metallic)*(.35*m.ao);vec3 spec=envSpec(R,m.roughness)*F;if(m.clearcoat>0)spec+=envSpec(R,max(.05,m.roughness*.65))*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.42;return diff+spec;}
vec3 aces(vec3 x){x*=exp2(pc.exposure);const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

void main(){
    if(inMaterial==13){vec3 d=normalize(inWorldPos-inCameraPos);outColor=vec4(pow(aces(skySample(d)),vec3(1.0/2.2)),1);return;}
    float water;Material m=materialAt(inMaterial,inWorldPos,inUv,water);vec3 V=normalize(inCameraPos-inWorldPos),T=normalize(inTangent),B=normalize(inBitangent),N=materialNormal(inMaterial,inWorldPos,inUv,normalize(inWorldNormal),T,B,water);if(dot(N,V)<=-.12)discard;
    if(length(m.emission)>0){vec3 e=m.emission+m.base*.15;outColor=vec4(pow(aces(e),vec3(1.0/2.2)),1);return;}
    vec3 sunL=normalize(vec3(-.53,.67,.52));vec3 color=ibl(m,N,V)+brdf(m,N,T,B,V,sunL,vec3(2.25,1.78,1.30));
    vec3 lp=bodies[2].posRad.xyz,d=lp-inWorldPos;color+=brdf(m,N,T,B,V,normalize(d),vec3(1.0,.62,.31)*(24.0/max(dot(d,d),.38)));
    if(inMaterial==10){float back=max(dot(-N,sunL),0.0);color+=m.base*vec3(.8,.48,.18)*back*.35;}
    if(inMaterial==3){float rim=pow(1.0-max(dot(N,V),0.0),2.2);color+=m.base*vec3(1.15,.18,.12)*rim*.22;}
    if(inMaterial==1&&water>.02){vec3 R=reflect(-V,N);vec3 wetRefl=envSpec(R,mix(.06,.18,1.0-water));float f=.04+.24*pow(1.0-max(dot(N,V),0.0),5.0);color=mix(color,wetRefl,clamp(water*(.16+f),0.0,.52));}
    outColor=vec4(pow(aces(max(color,vec3(0))),vec3(1.0/2.2)),1);
}
