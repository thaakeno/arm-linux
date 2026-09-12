#version 460
#extension GL_EXT_ray_query : require
// v7.9 quality pass: Adreno-first sparse ray queries, next-event path lighting and soft-material transmission.

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
layout(set=0,binding=1) uniform accelerationStructureEXT topLevelAS;
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
const int STATIC_INSTANCES=20;
const vec3 TORCH0=vec3(-2.55,.58,-6.63);
const vec3 TORCH1=vec3( 2.55,.58,-6.63);
struct Material{vec3 base;float metallic;float roughness;float clearcoat;float ao;vec3 emission;};

float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float hash11(float p){return fract(sin(p*91.173+17.31)*43758.5453);}
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);}
vec3 envSample(vec3 d,float lod){return textureLod(environmentMap,envUv(d),lod).rgb;}
vec3 skySample(vec3 d){d=normalize(d);float h=clamp(d.y*.5+.5,0.0,1.0),night=clamp((-pc.exposure-.52)/.70,0.0,1.0);vec3 day=mix(vec3(.60,.69,.77),vec3(.08,.25,.58),smoothstep(.04,.92,h));vec3 ns=mix(vec3(.009,.014,.023),vec3(.004,.010,.045),smoothstep(.04,.94,h));vec3 sunD=normalize(vec3(-.53,.67,.52));float sun=pow(max(dot(d,sunD),0.0),1100.0),glow=pow(max(dot(d,sunD),0.0),18.0);day+=vec3(9.0,5.8,2.9)*sun+vec3(.42,.22,.09)*glow;float stars=step(.9992,hash21(floor(envUv(d)*vec2(850.0,425.0))))*smoothstep(.60,.92,h)*night;ns+=vec3(stars)*1.2;vec3 c=mix(day,ns,night);float ground=smoothstep(.50,.43,h);return mix(c,mix(vec3(.070,.082,.078),vec3(.003,.005,.010),night),ground);}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=max(.025,r*r),a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
void basisFromNormal(vec3 n,out vec3 t,out vec3 b){vec3 a=abs(n.y)<.92?vec3(0,1,0):vec3(1,0,0);t=normalize(cross(a,n));b=normalize(cross(n,t));}
vec3 tangentNormal(sampler2D tex,vec2 uv,vec3 N,vec3 T,vec3 B,float strength){vec3 n=texture(tex,uv).xyz*2.0-1.0;n.xy*=strength;return normalize(mat3(normalize(T),normalize(B),normalize(N))*normalize(n));}

float torchFlicker(float phase){return .88+.08*sin(pc.time*11.3+phase)+.035*sin(pc.time*23.7+phase*2.1)+.020*sin(pc.time*37.1+phase*.7);}
vec3 torchColor(float phase){float f=torchFlicker(phase);return vec3(1.0,.255,.052)*f;}
float waterCoverage(vec2 uv){vec2 t=fract(uv);float h=texture(floorHeight,t).r,wet=clamp(pc.wetness,0.0,1.0);float low=1.0-smoothstep(.30,.54,h),edge=min(min(t.x,t.y),min(1.0-t.x,1.0-t.y)),joints=1.0-smoothstep(.018,.060,edge);float macro=smoothstep(.62,.91,hash21(floor(uv*3.5+vec2(13.0,5.0))));float fill=smoothstep(.38,.96,wet),puddle=(low*(.56+.22*macro)+joints*.32)*fill;return clamp(puddle,0.0,.86);}
float heroWetMask(int m,vec3 P,vec2 uv,vec3 N){float wet=clamp(pc.wetness,0.0,1.0);if(wet<.02)return 0.0;vec2 t=fract(uv*(m==6?1.20:1.28));float h=m==6?texture(darkHeight,t).r:texture(concreteHeight,t).r;float top=smoothstep(.05,.82,N.y),side=1.0-top,crevice=1.0-smoothstep(.30,.60,h);float streak=smoothstep(.70,.94,hash21(floor(vec2(t.x*11.0+P.y*1.7,t.y*5.0+3.0))));float pooling=crevice*(.30+.36*top)+streak*side*.20;return clamp(smoothstep(.34,.95,wet)*pooling,0.0,.68);}
void applyHeroWet(inout Material m,int objectId,int mat,vec3 P,vec2 uv,vec3 N){if(objectId!=1||(mat!=0&&mat!=6))return;float w=heroWetMask(mat,P,uv,N);if(w<=0.0)return;m.base*=mix(1.0,.56,w);m.roughness=mix(m.roughness,.13,w*.82);m.clearcoat=max(m.clearcoat,w*.62);m.ao=mix(m.ao,1.0,w*.12);}

Material materialAt(int m,vec3 P,vec2 uv,out float water){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.ao=1;a.emission=vec3(0);water=0;
    if(m==0){vec2 t=fract(uv*1.28);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(arm.g,.34,.94);a.ao=arm.r;}
    else if(m==1){vec2 t=fract(uv);vec4 arm=texture(floorArm,t);float h=texture(floorHeight,t).r;water=waterCoverage(uv);float wet=clamp(pc.wetness,0.0,1.0),cavity=1.0-smoothstep(.30,.64,h);vec3 stone=texture(floorAlbedo,t).rgb;float macro=.82+.18*hash21(floor(uv*9.0+vec2(3.0,17.0))),grain=.91+.12*hash21(floor(t*112.0));vec3 slate=stone*vec3(.58,.62,.65)*macro*grain;slate=mix(slate,slate*vec3(.52,.59,.64),cavity*.34);float damp=clamp(wet*(.28+.54*cavity)+water*.42,0.0,1.0);a.base=mix(slate,slate*vec3(.48,.56,.62),damp*.72);float dryR=clamp(arm.g+.08*cavity,.46,.94),dampR=mix(dryR,.30,damp);a.roughness=mix(dampR,.085,water);a.clearcoat=water*.54;a.ao=clamp(arm.r*(1.0-cavity*.18),.56,1.0);}
    else if(m==2){float grain=hash21(floor(uv*260.0)),scratch=smoothstep(.975,.998,hash21(floor(uv*vec2(42.0,480.0))));a.base=mix(vec3(.095,.105,.115),vec3(.34,.36,.39),grain*.34);a.base=mix(a.base,vec3(.50,.44,.35),scratch*.42);a.metallic=1.0;a.roughness=clamp(.15+grain*.20,.13,.39);a.clearcoat=.035;}
    else if(m==3){float pores=hash21(floor(uv*230.0)),scuff=smoothstep(.86,.99,hash21(floor(uv*35.0+9.0)));a.base=mix(vec3(.17,.003,.003),vec3(.72,.012,.008),pores*.35);a.base=mix(a.base,vec3(.09,.014,.011),scuff*.24);a.roughness=clamp(.39+pores*.14-scuff*.08,.32,.58);a.clearcoat=.34;a.ao=.97;}
    else if(m==4){float pulse=.96+.04*sin(pc.time*3.2);a.base=vec3(1.0,.54,.20);a.roughness=.08;a.clearcoat=.88;a.emission=vec3(8.6,3.9,1.25)*pulse;}
    else if(m==5){vec2 t=fract(uv*1.12);vec4 arm=texture(concreteArm,t);float stain=.74+.26*hash21(floor((P.xz+uv)*7.0));a.base=texture(concreteAlbedo,t).rgb*vec3(.46,.47,.47)*stain;a.roughness=clamp(arm.g+.10,.56,.98);a.ao=arm.r;}
    else if(m==6){vec2 t=fract(uv*1.20);vec4 arm=texture(darkArm,t);a.base=texture(darkAlbedo,t).rgb*vec3(.23,.24,.27);a.roughness=clamp(arm.g*.86+.05,.30,.86);a.ao=arm.r;}
    else if(m==7){float grain=hash21(floor(uv*220.0)),scuff=smoothstep(.88,.995,hash21(floor(uv*31.0+4.0)));a.base=mix(vec3(.018,.024,.030),vec3(.078,.104,.122),grain*.43);a.base*=1.0-scuff*.16;a.roughness=clamp(.56+grain*.14-scuff*.13,.44,.76);a.clearcoat=.10;a.ao=.95;}
    else if(m==8){float cell=hash21(floor(uv*100.0+P.xy*3.0)),vein=hash21(floor(uv*27.0+vec2(8.0,3.0)));a.base=mix(vec3(.010,.070,.020),vec3(.035,.30,.080),cell*.60);a.base*=.86+.16*vein;a.roughness=clamp(.19+.14*cell,.16,.37);a.clearcoat=.76;a.ao=.98;}
    else if(m==10){float n=hash21(floor(P.xz*15.0+P.yy*7.0));a.base=mix(vec3(.04,.12,.05),vec3(.11,.28,.09),n);a.roughness=.74;a.ao=.84;}
    else if(m==12){float stain=.74+.26*hash21(floor(P.xz*7.0+P.yy));a.base=vec3(.050,.057,.064)*stain;a.roughness=.86;a.metallic=.02;}
    else if(m==14){float grain=.5+.5*sin(uv.y*70.0+sin(uv.x*14.0)*2.0);a.base=mix(vec3(.09,.035,.016),vec3(.27,.115,.046),grain*.42);a.roughness=.66;}
    else if(m==15){float tip=smoothstep(.16,.48,P.y),core=smoothstep(.34,.61,P.y);a.base=mix(vec3(.055,.020,.009),vec3(.95,.18,.025),tip);a.roughness=mix(.74,.13,tip);a.emission=vec3(7.8,1.55,.16)*tip*(.74+.34*torchFlicker(P.x>0?2.7:.3))+vec3(4.5,3.0,.85)*core;}
    return a;
}
vec3 materialNormal(int m,vec3 P,vec2 uv,vec3 N,vec3 T,vec3 B,float water){float q=clamp(pc.quality/100.0,0.0,1.0),detail=mix(.46,1.0,q);if(m==0)return tangentNormal(concreteNormal,fract(uv*1.28),N,T,B,1.04*detail);if(m==1)return tangentNormal(floorNormal,fract(uv),N,T,B,mix(1.58,.62,water)*detail);if(m==6)return tangentNormal(darkNormal,fract(uv*1.20),N,T,B,1.02*detail);if(m==2||m==3||m==7||m==8){float n1=hash21(floor(uv*211.0))-.5,n2=hash21(floor(uv.yx*247.0+11.0))-.5;float amp=m==2?.030:(m==8?.008:(m==3?.012:.019));return normalize(N+(T*n1+B*n2)*amp*detail);}return normalize(N);}
vec3 brdf(Material m,vec3 N,vec3 V,vec3 L,vec3 radiance){float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),.001);if(NoL<=0)return vec3(0);vec3 H=normalize(V+L);float NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);vec3 spec=Dggx(NoH,m.roughness)*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001),kd=(1.0-F)*(1.0-m.metallic);return (kd*m.base/PI+spec)*NoL*radiance;}
vec3 envSpec(vec3 R,float rough){vec3 sky=skySample(R),hdr=envSample(R,mix(.7,6.5,rough));return mix(sky,hdr,.20)*mix(1.12,.60,rough);}
vec3 ibl(Material m,vec3 N,vec3 V){vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 diff=skySample(N)*m.base*(1.0-m.metallic)*(.25*m.ao),spec=envSpec(R,m.roughness)*F;if(m.clearcoat>0)spec+=envSpec(R,max(.040,m.roughness*.60))*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.42;return diff+spec;}
vec3 gelTransmission(int mat,Material m,vec3 N,vec3 V){if(mat!=3&&mat!=8)return vec3(0);float eta=mat==8?1.0/1.36:1.0/1.43;vec3 R=refract(-V,N,eta);if(dot(R,R)<.02)R=reflect(-V,N);float NoV=max(dot(N,V),0.0),edge=pow(1.0-NoV,1.35);vec3 env=envSample(R,mat==8?1.8:2.5)+skySample(R)*.18;vec3 tint=mat==8?vec3(.065,.67,.15):vec3(.92,.055,.028);float amount=mat==8?.30:.18;return env*tint*amount*(1.0-edge*.64)*(1.0-m.roughness*.40);}
vec3 aces(vec3 x){x*=exp2(pc.exposure);const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}
vec3 postProcess(vec3 hdr){vec3 c=aces(max(hdr,vec3(0)));float l=dot(c,vec3(.2126,.7152,.0722));c=mix(vec3(l),c,1.07);c=(c-.5)*1.04+.5;float hi=max(c.r,max(c.g,c.b)),bloom=smoothstep(.74,1.0,hi);c+=c*bloom*.036;return pow(clamp(c,0.0,1.0),vec3(1.0/2.2));}

float rayEps(int obj){if(obj>=STATIC_INSTANCES){int bi=obj-STATIC_INSTANCES;return max(.0120,bodies[bi].posRad.w*.022);}return .0100;}
float shadowVisibility(vec3 P,vec3 N,vec3 target,int source){vec3 d=target-P;float L=length(d);if(L<.05)return 1.0;d/=L;float e=rayEps(source);vec3 origin=P+normalize(N)*e;rayQueryEXT rq;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsTerminateOnFirstHitEXT|gl_RayFlagsOpaqueEXT,0x01,origin,e,d,max(.02,L-.075));rayQueryProceedEXT(rq);if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return 1.0;int id=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));return id==source?1.0:.075;}
void boxInfo(int id,out vec3 c,out vec3 s,out int mat){int b=id-2;mat=5;c=vec3(0);s=vec3(1);if(b==0){c=vec3(0,1.25,-7.02);s=vec3(7.05,2.25,.16);}else if(b==1){c=vec3(-7.02,.55,0);s=vec3(.16,1.55,7.05);}else if(b==2){c=vec3(7.02,.55,0);s=vec3(.16,1.55,7.05);}else if(b==3){c=vec3(-4.95,-.42,-4.95);s=vec3(1.50,.58,.82);mat=12;}else if(b==4){c=vec3(4.95,-.42,-4.95);s=vec3(1.50,.58,.82);mat=12;}else if(b==5){c=vec3(0,-.50,-5.85);s=vec3(2.55,.50,.50);mat=12;}else if(b==6){c=vec3(-5.42,1.28,-5.72);s=vec3(.34,2.18,.34);}else if(b==7){c=vec3(5.42,1.28,-5.72);s=vec3(.34,2.18,.34);}else if(b==8){c=vec3(-5.30,1.42,-5.30);s=vec3(.045,1.18,.045);mat=4;}else if(b==9){c=vec3(5.30,1.42,-5.30);s=vec3(.045,1.18,.045);mat=4;}else if(b==10){c=vec3(-2.55,.05,-6.76);s=vec3(.042,.62,.042);mat=15;}else if(b==11){c=vec3(2.55,.05,-6.76);s=vec3(.042,.62,.042);mat=15;}else if(b==12){c=vec3(-3.75,-.52,4.95);s=vec3(1.25,.12,.44);mat=14;}else if(b==13){c=vec3(-4.60,-.76,4.95);s=vec3(.10,.36,.36);mat=12;}else if(b==14){c=vec3(-2.90,-.76,4.95);s=vec3(.10,.36,.36);mat=12;}else if(b==15){c=vec3(0,1.30,-6.82);s=vec3(1.72,.54,.025);mat=12;}else if(b==16){c=vec3(-5.15,-.20,4.95);s=vec3(1.28,.82,.46);mat=12;}else{c=vec3(5.15,-.20,4.95);s=vec3(1.28,.82,.46);mat=12;}}
vec3 boxNormal(vec3 P,vec3 c,vec3 s){vec3 q=(P-c)/max(s,vec3(.001)),a=abs(q);if(a.x>=a.y&&a.x>=a.z)return vec3(sign(q.x),0,0);if(a.y>=a.z)return vec3(0,sign(q.y),0);return vec3(0,0,sign(q.z));}
vec2 planarUv(vec3 P,vec3 N){vec3 a=abs(N);if(a.y>=a.x&&a.y>=a.z)return P.xz*.36;if(a.x>=a.z)return P.zy*.36;return P.xy*.36;}
vec3 floorHitNormal(vec2 uv){vec2 e=vec2(1.0/2048.0);float hx=texture(floorHeight,fract(uv+vec2(e.x,0))).r-texture(floorHeight,fract(uv-vec2(e.x,0))).r;float hz=texture(floorHeight,fract(uv+vec2(0,e.y))).r-texture(floorHeight,fract(uv-vec2(0,e.y))).r;return normalize(vec3(-hx*6.0,1,-hz*6.0));}
void bodyHitInfo(int bi,vec3 hp,out vec3 hn,out vec2 huv){BodyGpu body=bodies[bi];vec3 v=hp-body.posRad.xyz,axis=body.extra.yzw;float dl=length(axis);axis=dl>.0001?axis/dl:vec3(0,1,0);if(body.meta.y==1){bool slime=body.meta.z==1;float maxC=slime?.58:(body.meta.x==3?.48:.34);float minA=slime?.40:(body.meta.x==3?.54:.66);float c=clamp(body.extra.x,-.030,maxC),along=clamp(1.0-c,minA,1.030),perp=inversesqrt(along);float va=dot(v,axis);vec3 vp=v-axis*va;float ar=body.posRad.w*along,pr=body.posRad.w*perp;hn=normalize(axis*(va/max(ar*ar,1e-5))+vp/max(pr*pr,1e-5));vec3 local=normalize(axis*(va/max(ar,1e-4))+vp/max(pr,1e-4));huv=vec2(atan(local.z,local.x)/(2.0*PI)+.5,acos(clamp(local.y,-1.0,1.0))/PI);}else{vec3 n=normalize(v);hn=n;huv=vec2(atan(n.z,n.x)/(2.0*PI)+.5,acos(clamp(n.y,-1.0,1.0))/PI);}}
int traceScene(vec3 origin,vec3 dir,int source,out vec3 hp,out vec3 hn,out int hm,out vec2 huv){rayQueryEXT rq;float e=source>=0?rayEps(source):.010;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsOpaqueEXT,0x03,origin,e,dir,42.0);while(rayQueryProceedEXT(rq)){}if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return -1;int id=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));float t=rayQueryGetIntersectionTEXT(rq,true);hp=origin+dir*t;if(id==0){hm=1;huv=(hp.xz/14.1+.5)*3.20;hn=floorHitNormal(huv);}else if(id==1){vec3 he=vec3(1.22,1.06,1.22);hn=boxNormal(hp,vec3(0),he);hm=mod(pc.heroMaterial,10.0)<.5?0:6;huv=planarUv(hp,hn);}else if(id<STATIC_INSTANCES){vec3 c,s;boxInfo(id,c,s,hm);hn=boxNormal(hp,c,s);huv=planarUv(hp,hn);}else{int bi=id-STATIC_INSTANCES;bodyHitInfo(bi,hp,hn,huv);hm=bodies[bi].meta.x;}return id;}
vec3 cosineHemisphere(vec3 N,vec2 xi){float r=sqrt(xi.x),phi=2.0*PI*xi.y;vec3 T,B;basisFromNormal(N,T,B);return normalize(T*(r*cos(phi))+B*(r*sin(phi))+N*sqrt(max(0.0,1.0-xi.x)));}
vec3 sampleBounce(Material m,vec3 N,vec3 V,float a,float b){vec3 F0=mix(vec3(.04),m.base,m.metallic);float specChance=clamp(max(F0.r,max(F0.g,F0.b))*(1.0-m.roughness*.42)+m.metallic*.58,.08,.94);if(a<specChance){vec3 R=reflect(-V,N),j=cosineHemisphere(R,vec2(fract(a*7.31+.17),b));return normalize(mix(R,j,m.roughness*m.roughness*.52));}return cosineHemisphere(N,vec2(fract(a*5.17+.31),b));}
vec3 pointLight(Material m,vec3 N,vec3 V,vec3 P,vec3 lp,vec3 rgb,float power){vec3 d=lp-P;float d2=max(dot(d,d),.24);return brdf(m,N,V,normalize(d),rgb*(power/d2));}
vec3 torchLighting(Material m,vec3 N,vec3 V,vec3 P){return pointLight(m,N,V,P,TORCH0,torchColor(.3),17.0)+pointLight(m,N,V,P,TORCH1,torchColor(2.7),17.0);}
vec3 sampledBounceLight(Material hit,vec3 hp,vec3 hn,vec3 V,int source,float seed){float q=hash11(seed+103.73);vec3 lp,rgb;float power;if(q<.34){lp=bodies[2].posRad.xyz;rgb=max(bodies[2].extra.yzw,vec3(.05));power=23.0;}else if(q<.67){lp=TORCH0;rgb=torchColor(.3);power=19.0;}else{lp=TORCH1;rgb=torchColor(2.7);power=19.0;}float vis=shadowVisibility(hp,hn,lp,source);return pointLight(hit,hn,V,hp,lp,rgb,power)*vis*2.85;}
vec3 pathIndirect(vec3 P,vec3 N,vec3 V,Material first,int source,float seed){
    float r0=hash11(seed+17.31),r1=hash11(seed+61.77);vec3 rd=sampleBounce(first,N,V,r0,r1),hp,hn;int hm;vec2 huv;int hid=traceScene(P+N*rayEps(source),rd,source,hp,hn,hm,huv);
    vec3 F0=mix(vec3(.04),first.base,first.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 throughput=mix(first.base*(1.0-first.metallic),F,clamp(first.metallic+.24*(1.0-first.roughness),0.0,1.0))*mix(.60,.94,1.0-first.roughness);
    if(hid<0)return min(throughput*(skySample(rd)*.82+envSample(rd,4.0)*.16),vec3(8.0));
    float w;Material hit=materialAt(hm,hp,huv,w);hn=normalize(hn);if(dot(hn,-rd)<0)hn=-hn;applyHeroWet(hit,hid,hm,hp,huv,hn);
    if(length(hit.emission)>0)return min(throughput*hit.emission,vec3(8.0));
    vec3 sunL=normalize(vec3(-.53,.67,.52));vec3 bounced=ibl(hit,hn,-rd)*.42+brdf(hit,hn,-rd,sunL,vec3(1.40,1.18,.98))*.26+sampledBounceLight(hit,hp,hn,-rd,hid,seed)*.42+gelTransmission(hm,hit,hn,-rd)*.24;
    return min(throughput*bounced,vec3(8.0));
}

void main(){
    if(inMaterial==13){outColor=vec4(postProcess(skySample(normalize(inWorldPos-inCameraPos))),1);return;}
    float water;int mat=inMaterial;bool torchObj=(inObject==12||inObject==13);if(torchObj)mat=15;Material m=materialAt(mat,inWorldPos,inUv,water);vec3 V=normalize(inCameraPos-inWorldPos),T=normalize(inTangent),B=normalize(inBitangent),geomN=normalize(inWorldNormal);if(dot(geomN,V)<0)geomN=-geomN;applyHeroWet(m,inObject,mat,inWorldPos,inUv,geomN);vec3 N=materialNormal(mat,inWorldPos,inUv,geomN,T,B,water);if(dot(N,V)<0)N=-N;
    vec3 lightRgb=max(bodies[2].extra.yzw,vec3(.05));if(inMaterial==4&&inObject==STATIC_INSTANCES+2){float pulse=.96+.04*sin(pc.time*3.2);m.base=lightRgb;m.emission=lightRgb*8.6*pulse;m.roughness=.08;m.clearcoat=.88;}
    if(length(m.emission)>0){float rim=pow(1.0-max(dot(N,V),0.0),2.0);outColor=vec4(postProcess(m.emission+m.base*(.13+rim*.65)),1);return;}
    vec3 sunL=normalize(vec3(-.53,.67,.52)),lv=bodies[2].posRad.xyz-inWorldPos,localL=normalize(lv);float ld2=max(dot(lv,lv),.35),night=clamp((-pc.exposure-.52)/.70,0.0,1.0);bool pt=pc.heroMaterial>=9.5&&pc.rtEnabled>.5;
    vec3 torchDirect=torchLighting(m,N,V,inWorldPos);
    if(pt){float localVis=shadowVisibility(inWorldPos,geomN,bodies[2].posRad.xyz,inObject);vec3 nearestTorch=distance(inWorldPos,TORCH0)<distance(inWorldPos,TORCH1)?TORCH0:TORCH1;float torchVis=shadowVisibility(inWorldPos,geomN,nearestTorch,inObject);vec3 direct=ibl(m,N,V)*.28+brdf(m,N,V,sunL,vec3(1.55,1.30,1.08)*(1.0-night*.80));direct+=brdf(m,N,V,localL,lightRgb*(25.0/ld2))*localVis;direct+=torchDirect*mix(.68,1.0,torchVis);float seed=dot(floor(gl_FragCoord.xy),vec2(12.9898,78.233))+float(inObject)*19.17;vec3 indirect=pathIndirect(inWorldPos,geomN,V,m,inObject,seed);vec3 stableGI=mix(ibl(m,N,V),indirect,.76);vec3 color=direct+stableGI*.88+gelTransmission(mat,m,N,V)*.46;float impact=float(bodies[0].meta.w)/1000.0;if(mat==1&&impact>.01){float d=distance(inWorldPos.xz,bodies[0].posRad.xz);float dust=impact*smoothstep(.95,.05,d)*smoothstep(.02,.20,d);color+=vec3(.22,.135,.065)*dust*.65;}outColor=vec4(postProcess(color),1);return;}
    float budget=clamp(pc.rtBudget,0.0,1.0),sunScore=max(dot(N,sunL),0.0)*1.9*(1.0-night*.82),localScore=max(dot(N,localL),0.0)*(25.0/ld2),vis=1.0;bool important=inObject==0||inObject==1||(inObject>=STATIC_INSTANCES&&inObject<STATIC_INSTANCES+5);if(pc.rtEnabled>.5&&budget>.040&&important&&(sunScore>.018||localScore>.018)){vec3 target=localScore>sunScore?bodies[2].posRad.xyz:inWorldPos+sunL*36.0;vis=shadowVisibility(inWorldPos,geomN,target,inObject);}vec3 color=ibl(m,N,V);color+=brdf(m,N,V,sunL,vec3(2.0,1.62,1.24)*(1.0-night*.82))*((sunScore>=localScore)?vis:1.0);color+=brdf(m,N,V,localL,lightRgb*(25.0/ld2))*((localScore>sunScore)?vis:1.0);color+=torchDirect;color+=gelTransmission(mat,m,N,V)*.20;
    float NoV=max(dot(N,V),0.0);vec3 F=fresnelSchlick(NoV,mix(vec3(.04),m.base,m.metallic));bool heroWet=inObject==1&&m.clearcoat>.08;bool reflective=(mat==2)||(mat==1&&water>.44)||heroWet||(m.clearcoat>.48);float importance=max(F.r,max(F.g,F.b))*(1.0-m.roughness*.75)*budget,rayGate=hash21(floor(gl_FragCoord.xy*.5)),rayRate=mix(.032,.16,budget);
    if(pc.rtEnabled>.5&&reflective&&importance>.032&&rayGate<rayRate){vec3 R=reflect(-V,N),hp,hn;int hm;vec2 huv;int hid=traceScene(inWorldPos+geomN*rayEps(inObject),R,inObject,hp,hn,hm,huv);vec3 refl=envSpec(R,m.roughness);if(hid>=0){float hw;Material hit=materialAt(hm,hp,huv,hw);vec3 hitV=normalize(inWorldPos-hp);if(dot(hn,hitV)<0)hn=-hn;applyHeroWet(hit,hid,hm,hp,huv,hn);refl=hit.emission+hit.base*.20+ibl(hit,hn,hitV)*.52;}float strength=mat==1?water*.46:(mat==2?.90:(heroWet?.32:(m.clearcoat>.48?.38:.52)));color=mix(color,refl,clamp(F*strength,vec3(0),vec3(.82)));}
    if(mat==1&&water>.01){vec3 R=reflect(-V,N);float f=.06+.42*pow(1.0-NoV,5.0);vec3 wetRefl=envSpec(R,mix(.060,.16,1.0-water));color=mix(color,wetRefl,clamp(water*(.22+f),0.0,.54));}
    float impact=float(bodies[0].meta.w)/1000.0;if(mat==1&&impact>.01){float d=distance(inWorldPos.xz,bodies[0].posRad.xz);float ring=smoothstep(.90,.04,d)*smoothstep(.015,.18,d);color+=vec3(.18,.105,.050)*impact*ring*.45;}
    if(mat==3){float rim=pow(1.0-NoV,2.0);color+=m.base*vec3(.52,.05,.04)*rim*.09;}if(mat==8){float rim=pow(1.0-NoV,2.4);color+=m.base*vec3(.18,.72,.26)*rim*.20;}outColor=vec4(postProcess(color),1);
}