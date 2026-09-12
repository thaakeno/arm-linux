#version 460
#extension GL_EXT_ray_query : require
// Vulkan Studio v10.0: rainy house materials, stable side-wall fire, motion-stable HWRT and low-noise reconstructed path lighting.

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
const vec3 TORCH0=vec3(-6.54,1.58,-1.65);
const vec3 TORCH1=vec3( 6.54,1.58,-1.65);
const vec3 FIREPLACE=vec3(3.15,-.20,2.78);
struct Material{vec3 base;float metallic;float roughness;float clearcoat;float ao;vec3 emission;};

float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float hash11(float p){return fract(sin(p*91.173+17.31)*43758.5453);}
float noise2(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);return mix(mix(hash21(i),hash21(i+vec2(1,0)),f.x),mix(hash21(i+vec2(0,1)),hash21(i+vec2(1,1)),f.x),f.y);}
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);}
vec3 envSample(vec3 d,float lod){return textureLod(environmentMap,envUv(d),lod).rgb;}
float nightAmount(){return clamp((-pc.exposure-.46)/.78,0.0,1.0);}
vec3 skySample(vec3 d){d=normalize(d);float h=clamp(d.y*.5+.5,0.0,1.0),night=nightAmount();vec3 day=mix(vec3(.52,.60,.67),vec3(.12,.28,.55),smoothstep(.05,.92,h));vec3 ns=mix(vec3(.010,.014,.022),vec3(.004,.010,.042),smoothstep(.04,.94,h));vec3 sunD=normalize(vec3(-.53,.67,.52));float sun=pow(max(dot(d,sunD),0.0),1150.0),glow=pow(max(dot(d,sunD),0.0),18.0);day+=vec3(8.5,5.0,2.4)*sun+vec3(.34,.18,.07)*glow;float stars=step(.99945,hash21(floor(envUv(d)*vec2(900.0,450.0))))*smoothstep(.60,.93,h)*night;ns+=vec3(stars);vec3 c=mix(day,ns,night);float ground=smoothstep(.50,.43,h);return mix(c,mix(vec3(.050,.060,.055),vec3(.007,.009,.014),night),ground);}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=max(.025,r*r),a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
void basisFromNormal(vec3 n,out vec3 t,out vec3 b){vec3 a=abs(n.y)<.92?vec3(0,1,0):vec3(1,0,0);t=normalize(cross(a,n));b=normalize(cross(n,t));}
vec3 tangentNormal(sampler2D tex,vec2 uv,vec3 N,vec3 T,vec3 B,float strength){vec3 n=texture(tex,uv).xyz*2.0-1.0;n.xy*=strength;return normalize(mat3(normalize(T),normalize(B),normalize(N))*normalize(n));}

// Flame geometry can move strongly; emitted illumination is intentionally nearly constant so shadows do not shimmer.
float torchFlicker(float phase){return .994+.004*sin(pc.time*2.7+phase)+.002*sin(pc.time*5.1+phase*1.3);}
vec3 torchColor(float phase){return vec3(1.0,.285,.065)*torchFlicker(phase);}
float waterCoverage(vec2 uv){vec2 t=fract(uv);float h=texture(floorHeight,t).r,wet=clamp(pc.wetness,0.0,1.0);float low=1.0-smoothstep(.30,.54,h),edge=min(min(t.x,t.y),min(1.0-t.x,1.0-t.y)),joints=1.0-smoothstep(.018,.060,edge);float macro=smoothstep(.64,.92,hash21(floor(uv*3.5+vec2(13.0,5.0))));float fill=smoothstep(.34,.96,wet),puddle=(low*(.50+.20*macro)+joints*.26)*fill;return clamp(puddle,0.0,.78);}
float heroWetMask(int m,vec3 P,vec2 uv,vec3 N){float wet=clamp(pc.wetness,0.0,1.0);if(wet<.02)return 0.0;vec2 t=fract(uv*(m==6?1.20:1.28));float h=m==6?texture(darkHeight,t).r:texture(concreteHeight,t).r;float top=smoothstep(.05,.82,N.y),side=1.0-top,crevice=1.0-smoothstep(.30,.60,h);float streak=smoothstep(.70,.94,hash21(floor(vec2(t.x*11.0+P.y*1.7,t.y*5.0+3.0))));float pooling=crevice*(.30+.36*top)+streak*side*.20;return clamp(smoothstep(.34,.95,wet)*pooling,0.0,.68);}
void applyHeroWet(inout Material m,int objectId,int mat,vec3 P,vec2 uv,vec3 N){if(objectId!=1||(mat!=0&&mat!=6))return;float w=heroWetMask(mat,P,uv,N);if(w<=0.0)return;m.base*=mix(1.0,.58,w);m.roughness=mix(m.roughness,.14,w*.80);m.clearcoat=max(m.clearcoat,w*.58);m.ao=mix(m.ao,1.0,w*.12);}

vec3 rainyLandscape(vec2 uv){
    float night=nightAmount();vec2 p=uv;float sky=clamp((p.y-.34)/.66,0.0,1.0);vec3 daySky=mix(vec3(.34,.43,.50),vec3(.68,.76,.82),sky);vec3 nightSky=mix(vec3(.012,.020,.035),vec3(.045,.075,.13),sky);vec3 col=mix(daySky,nightSky,night);
    float ridge=.49+.10*sin(p.x*6.0)+.045*sin(p.x*17.0+1.7);float ridge2=.39+.055*sin(p.x*9.0+2.1);float mountains=1.0-smoothstep(ridge-.012,ridge+.015,p.y);float farM=1.0-smoothstep(ridge2-.012,ridge2+.015,p.y);col=mix(col,mix(vec3(.10,.14,.15),vec3(.025,.036,.050),night),farM*.70);col=mix(col,mix(vec3(.055,.075,.070),vec3(.014,.022,.025),night),mountains);
    float grass=1.0-smoothstep(.30,.345,p.y);float blades=.5+.5*sin(p.x*420.0+sin(pc.time*.65+p.x*21.0)*2.4);vec3 grassCol=mix(vec3(.045,.14,.050),vec3(.010,.035,.018),night);col=mix(col,grassCol*(.70+.30*blades),grass);
    float cloud=noise2(p*vec2(4.0,2.0)+vec2(pc.time*.008,0));col*=mix(.78,1.02,cloud*.55+sky*.45);return col;
}
float rainMask(vec2 uv){vec2 p=uv;float lane=floor(p.x*42.0),rnd=hash11(lane*2.13);float speed=.12+.18*rnd;float y=fract(p.y+pc.time*speed+rnd);float x=abs(fract(p.x*42.0)-.5);float streak=smoothstep(.075,.010,x)*smoothstep(.42,.03,y)*smoothstep(.0,.09,y);float drop=1.0-smoothstep(.0,.055,length(vec2(fract(p.x*17.0+rnd)-.5,fract(p.y*9.0-pc.time*.055+rnd)-.5)*vec2(.8,1.7)));return clamp(streak*.55+drop*.22,0.0,1.0);}
vec3 windowColor(vec2 uv,vec3 N,vec3 V){float rain=rainMask(uv);vec3 outside=rainyLandscape(uv);vec3 R=reflect(-V,N);float fres=.04+.52*pow(1.0-max(dot(N,V),0.0),5.0);vec3 reflected=envSample(R,1.9)+skySample(R)*.22;vec3 c=mix(outside,reflected,clamp(fres+.12*rain,0.0,.74));c+=vec3(.50,.58,.64)*rain*.12;return c;}

Material materialAt(int m,vec3 P,vec2 uv,out float water){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.ao=1;a.emission=vec3(0);water=0;
    if(m==0){vec2 t=fract(uv*1.28);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(arm.g,.34,.94);a.ao=arm.r;}
    else if(m==1){vec2 t=fract(uv);vec4 arm=texture(floorArm,t);float h=texture(floorHeight,t).r;water=waterCoverage(uv);float wet=clamp(pc.wetness,0.0,1.0),cavity=1.0-smoothstep(.30,.64,h);vec3 stone=texture(floorAlbedo,t).rgb;float macro=.84+.16*hash21(floor(uv*9.0+vec2(3.0,17.0))),grain=.95+.05*hash21(floor(t*112.0));vec3 slate=stone*vec3(.56,.59,.61)*macro*grain;slate=mix(slate,slate*vec3(.58,.62,.66),cavity*.25);float damp=clamp(wet*(.22+.48*cavity)+water*.44,0.0,1.0);a.base=mix(slate,slate*vec3(.52,.58,.63),damp*.68);float dryR=clamp(arm.g+.08*cavity,.48,.94),dampR=mix(dryR,.31,damp);a.roughness=mix(dampR,.085,water);a.clearcoat=water*.50;a.ao=clamp(arm.r*(1.0-cavity*.16),.60,1.0);}
    else if(m==2){float grain=hash21(floor(uv*260.0)),scratch=smoothstep(.975,.998,hash21(floor(uv*vec2(42.0,480.0))));a.base=mix(vec3(.095,.105,.115),vec3(.34,.36,.39),grain*.34);a.base=mix(a.base,vec3(.50,.44,.35),scratch*.42);a.metallic=1.0;a.roughness=clamp(.15+grain*.20,.13,.39);a.clearcoat=.035;}
    else if(m==3){float pores=hash21(floor(uv*230.0)),scuff=smoothstep(.86,.99,hash21(floor(uv*35.0+9.0)));a.base=mix(vec3(.17,.003,.003),vec3(.72,.012,.008),pores*.35);a.base=mix(a.base,vec3(.09,.014,.011),scuff*.24);a.roughness=clamp(.34+pores*.12-scuff*.07,.28,.52);a.clearcoat=.38;a.ao=.98;}
    else if(m==4){float raw=max(max(bodies[2].extra.y,bodies[2].extra.z),bodies[2].extra.w),intensity=max(raw,.25);vec3 col=bodies[2].extra.yzw/intensity;float pulse=.994+.006*sin(pc.time*1.7);a.base=col;a.roughness=.07;a.clearcoat=.90;a.emission=col*(7.8*intensity)*pulse;}
    else if(m==5){vec2 t=fract(P.xy*.16+P.zy*.13);vec4 arm=texture(concreteArm,t);float plaster=.93+.07*noise2(t*24.0);a.base=texture(concreteAlbedo,t).rgb*vec3(.72,.69,.63)*plaster;a.roughness=clamp(arm.g+.16,.62,.98);a.ao=mix(.92,arm.r,.35);}
    else if(m==6){vec2 t=fract(uv*1.20);vec4 arm=texture(darkArm,t);a.base=texture(darkAlbedo,t).rgb*vec3(.23,.24,.27);a.roughness=clamp(arm.g*.86+.05,.30,.86);a.ao=arm.r;}
    else if(m==7){float grain=hash21(floor(uv*220.0)),scuff=smoothstep(.88,.995,hash21(floor(uv*31.0+4.0)));a.base=mix(vec3(.014,.018,.021),vec3(.055,.070,.080),grain*.38);a.base*=1.0-scuff*.13;a.roughness=clamp(.60+grain*.15-scuff*.10,.50,.82);a.clearcoat=.035;a.ao=.95;}
    else if(m==8){float cell=hash21(floor(uv*100.0+P.xy*3.0)),vein=hash21(floor(uv*27.0+vec2(8.0,3.0)));a.base=mix(vec3(.008,.060,.015),vec3(.028,.25,.064),cell*.60);a.base*=.86+.16*vein;a.roughness=clamp(.20+.14*cell,.17,.38);a.clearcoat=.72;a.ao=.98;}
    else if(m==10){float n=hash21(floor(P.xz*15.0+P.yy*7.0));a.base=mix(vec3(.035,.10,.04),vec3(.10,.25,.075),n);a.roughness=.76;a.ao=.84;}
    else if(m==12){float stain=.74+.26*hash21(floor(P.xz*7.0+P.yy));a.base=vec3(.050,.057,.064)*stain;a.roughness=.86;a.metallic=.02;}
    else if(m==14){float grain=.5+.5*sin((P.x+P.z)*16.0+sin((P.y+uv.y)*7.0));a.base=mix(vec3(.055,.020,.009),vec3(.28,.11,.035),grain*.48);a.roughness=.60;a.clearcoat=.04;}
    else if(m==15){float edge=abs(uv.x-.5)*2.0,tip=smoothstep(.02,.92,uv.y),shape=1.0-smoothstep(mix(.70,.12,tip),mix(.88,.25,tip),edge);float dance=.98+.02*sin(pc.time*5.7+P.y*3.0);a.base=mix(vec3(.15,.010,.001),vec3(1.0,.28,.012),tip);a.roughness=.10;a.emission=(vec3(10.0,1.55,.06)*shape+vec3(4.0,2.0,.38)*shape*shape)*dance;}
    else if(m==16){a.base=vec3(.08,.11,.13);a.roughness=.055;a.clearcoat=.96;a.ao=1.0;}
    else if(m==17){float weave=.5+.5*sin(uv.x*180.0)*sin(uv.y*155.0),nap=noise2(uv*42.0);a.base=mix(vec3(.055,.070,.078),vec3(.18,.20,.21),.34*weave+.28*nap);a.roughness=.90;a.ao=.88;}
    else if(m==18){float warp=.5+.5*sin(uv.x*95.0),weft=.5+.5*sin(uv.y*105.0);float border=1.0-smoothstep(.0,.055,min(min(uv.x,uv.y),min(1.0-uv.x,1.0-uv.y)));a.base=mix(vec3(.10,.025,.018),vec3(.34,.11,.045),.35*warp+.30*weft);a.base=mix(a.base,vec3(.035,.012,.010),border);a.roughness=.96;a.ao=.82;}
    else if(m==19){float scan=.5+.5*sin(uv.y*900.0);a.base=vec3(.003,.005,.008)+vec3(.004,.006,.012)*scan;a.roughness=.045;a.clearcoat=.92;a.emission=vec3(.004,.008,.015);}
    else if(m==20){float pat=noise2(uv*18.0);a.base=mix(vec3(.012,.014,.016),vec3(.085,.078,.067),pat*.28);a.metallic=.92;a.roughness=.36+.16*pat;a.ao=.90;}
    else if(m==21){float frame=1.0-smoothstep(.035,.085,min(min(uv.x,uv.y),min(1.0-uv.x,1.0-uv.y)));vec3 paint=mix(vec3(.10,.18,.26),vec3(.72,.31,.08),smoothstep(.15,.85,noise2(uv*vec2(5.0,7.0)+vec2(float(inObject)*.13,0))));paint=mix(paint,vec3(.06,.13,.08),smoothstep(.42,.74,noise2(uv*11.0+3.0)));a.base=mix(paint,vec3(.13,.060,.018),frame);a.roughness=mix(.56,.32,frame);a.clearcoat=frame*.16;}
    else if(m==22){float edge=abs(uv.x-.5)*2.0,flame=(1.0-smoothstep(.48,.94,edge))*smoothstep(.0,.16,uv.y)*(1.0-smoothstep(.72,1.0,uv.y));float coals=smoothstep(.0,.25,uv.y)*(1.0-smoothstep(.25,.52,uv.y));float wobble=.92+.08*sin(pc.time*6.0+uv.x*11.0);a.base=vec3(.025,.008,.003);a.roughness=.44;a.emission=vec3(8.5,1.15,.035)*flame*wobble+vec3(2.3,.16,.008)*coals;}
    return a;
}
vec3 materialNormal(int m,vec3 P,vec2 uv,vec3 N,vec3 T,vec3 B,float water){float q=clamp(pc.quality/100.0,0.0,1.0),detail=mix(.46,1.0,q);if(m==0)return tangentNormal(concreteNormal,fract(uv*1.28),N,T,B,1.04*detail);if(m==1)return tangentNormal(floorNormal,fract(uv),N,T,B,mix(1.52,.72,water)*detail);if(m==5)return tangentNormal(concreteNormal,fract(P.xy*.16+P.zy*.13),N,T,B,.36*detail);if(m==6)return tangentNormal(darkNormal,fract(uv*1.20),N,T,B,1.02*detail);if(m==2||m==3||m==7||m==8||m==17||m==18){float n1=hash21(floor(uv*211.0))-.5,n2=hash21(floor(uv.yx*247.0+11.0))-.5;float amp=m==2?.030:(m==8?.008:(m==3?.012:(m>=17?.010:.019)));return normalize(N+(T*n1+B*n2)*amp*detail);}return normalize(N);}
vec3 brdf(Material m,vec3 N,vec3 V,vec3 L,vec3 radiance){float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),.001);if(NoL<=0)return vec3(0);vec3 H=normalize(V+L);float NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);vec3 spec=Dggx(NoH,m.roughness)*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001),kd=(1.0-F)*(1.0-m.metallic);return (kd*m.base/PI+spec)*NoL*radiance;}
vec3 envSpec(vec3 R,float rough){vec3 sky=skySample(R),hdr=envSample(R,mix(.8,6.5,rough));return mix(sky,hdr,.18)*mix(1.10,.58,rough);}
vec3 ibl(Material m,vec3 N,vec3 V){vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);float night=nightAmount();vec3 diff=skySample(N)*m.base*(1.0-m.metallic)*((.22+.06*night)*m.ao),spec=envSpec(R,m.roughness)*F;if(m.clearcoat>0)spec+=envSpec(R,max(.045,m.roughness*.62))*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.40;return diff+spec;}
vec3 gelTransmission(int mat,Material m,vec3 N,vec3 V){if(mat!=3&&mat!=8)return vec3(0);float eta=mat==8?1.0/1.36:1.0/1.43;vec3 R=refract(-V,N,eta);if(dot(R,R)<.02)R=reflect(-V,N);float NoV=max(dot(N,V),0.0),edge=pow(1.0-NoV,1.35);vec3 env=envSample(R,mat==8?1.8:2.5)+skySample(R)*.18;vec3 tint=mat==8?vec3(.055,.58,.12):vec3(.92,.055,.028);float amount=mat==8?.28:.17;return env*tint*amount*(1.0-edge*.64)*(1.0-m.roughness*.40);}
vec3 aces(vec3 x){x*=exp2(pc.exposure);const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}
vec3 postProcess(vec3 hdr){vec3 c=aces(max(hdr,vec3(0)));float l=dot(c,vec3(.2126,.7152,.0722));c=mix(vec3(l),c,1.055);c=(c-.5)*1.025+.5;float hi=max(c.r,max(c.g,c.b)),bloom=smoothstep(.80,1.0,hi);c+=c*bloom*.024;return pow(clamp(c,0.0,1.0),vec3(1.0/2.2));}

float rayEps(int obj){if(obj>=STATIC_INSTANCES){int bi=obj-STATIC_INSTANCES;return max(.0120,bodies[bi].posRad.w*.022);}return .0100;}
float shadowVisibility(vec3 P,vec3 N,vec3 target,int source){vec3 d=target-P;float L=length(d);if(L<.05)return 1.0;d/=L;float e=rayEps(source);vec3 origin=P+normalize(N)*e;rayQueryEXT rq;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsTerminateOnFirstHitEXT|gl_RayFlagsOpaqueEXT,0x01,origin,e,d,max(.02,L-.075));rayQueryProceedEXT(rq);return rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT?1.0:.10;}
void boxInfo(int id,out vec3 c,out vec3 s,out int mat){int b=id-2;mat=5;c=vec3(0);s=vec3(1);if(b==0){c=vec3(0,-.02,-6.95);s=vec3(6.82,.98,.16);mat=5;}else if(b==1){c=vec3(0,4.00,-6.95);s=vec3(6.82,.50,.16);mat=5;}else if(b==2){c=vec3(-5.55,2.20,-6.95);s=vec3(1.27,1.22,.16);mat=5;}else if(b==3){c=vec3(0,2.20,-6.95);s=vec3(.62,1.22,.16);mat=5;}else if(b==4){c=vec3(5.55,2.20,-6.95);s=vec3(1.27,1.22,.16);mat=5;}else if(b==5){c=vec3(-3.12,2.20,-6.87);s=vec3(1.79,1.17,.025);mat=16;}else if(b==6){c=vec3(3.12,2.20,-6.87);s=vec3(1.79,1.17,.025);mat=16;}else if(b==7){c=vec3(-6.95,1.75,0);s=vec3(.16,2.75,6.95);mat=5;}else if(b==8){c=vec3(6.95,1.75,0);s=vec3(.16,2.75,6.95);mat=5;}else if(b==9){c=vec3(-6.70,1.55,-1.65);s=vec3(.055,.50,.18);mat=15;}else if(b==10){c=vec3(6.70,1.55,-1.65);s=vec3(.055,.50,.18);mat=15;}else if(b==11){c=vec3(.10,-.94,2.85);s=vec3(2.65,.035,1.48);mat=18;}else if(b==12){c=vec3(-3.78,-.48,3.25);s=vec3(1.55,.52,.82);mat=17;}else if(b==13){c=vec3(-3.78,.27,3.78);s=vec3(1.55,.82,.28);mat=17;}else if(b==14){c=vec3(3.15,-.42,3.58);s=vec3(.92,.60,.68);mat=20;}else if(b==15){c=vec3(3.15,-.34,2.895);s=vec3(.67,.39,.025);mat=22;}else if(b==16){c=vec3(6.73,.82,2.35);s=vec3(.035,1.02,1.68);mat=19;}else{c=vec3(5.80,-.61,2.35);s=vec3(.78,.39,1.72);mat=14;}}
vec3 boxNormal(vec3 P,vec3 c,vec3 s){vec3 q=(P-c)/max(s,vec3(.001)),a=abs(q);if(a.x>=a.y&&a.x>=a.z)return vec3(sign(q.x),0,0);if(a.y>=a.z)return vec3(0,sign(q.y),0);return vec3(0,0,sign(q.z));}
vec2 planarUv(vec3 P,vec3 N){vec3 a=abs(N);if(a.y>=a.x&&a.y>=a.z)return P.xz*.36;if(a.x>=a.z)return P.zy*.36;return P.xy*.36;}
vec3 floorHitNormal(vec2 uv){vec2 e=vec2(1.0/2048.0);float hx=texture(floorHeight,fract(uv+vec2(e.x,0))).r-texture(floorHeight,fract(uv-vec2(e.x,0))).r;float hz=texture(floorHeight,fract(uv+vec2(0,e.y))).r-texture(floorHeight,fract(uv-vec2(0,e.y))).r;return normalize(vec3(-hx*6.0,1,-hz*6.0));}
void bodyHitInfo(int bi,vec3 hp,out vec3 hn,out vec2 huv){BodyGpu body=bodies[bi];vec3 v=hp-body.posRad.xyz,axis=body.extra.yzw;float dl=length(axis);axis=dl>.0001?axis/dl:vec3(0,1,0);if(body.meta.y==1){bool slime=body.meta.z==1;float maxC=slime?.62:(body.meta.x==3?.50:.42);float minA=slime?.36:(body.meta.x==3?.50:.58);float c=clamp(body.extra.x,-.040,maxC),along=clamp(1.0-c,minA,1.040),perp=inversesqrt(along);float va=dot(v,axis);vec3 vp=v-axis*va;float ar=body.posRad.w*along,pr=body.posRad.w*perp;hn=normalize(axis*(va/max(ar*ar,1e-5))+vp/max(pr*pr,1e-5));vec3 local=normalize(axis*(va/max(ar,1e-4))+vp/max(pr,1e-4));huv=vec2(atan(local.z,local.x)/(2.0*PI)+.5,acos(clamp(local.y,-1.0,1.0))/PI);}else{vec3 n=normalize(v);hn=n;huv=vec2(atan(n.z,n.x)/(2.0*PI)+.5,acos(clamp(n.y,-1.0,1.0))/PI);}}
int traceScene(vec3 origin,vec3 dir,int source,out vec3 hp,out vec3 hn,out int hm,out vec2 huv){rayQueryEXT rq;float e=source>=0?rayEps(source):.010;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsOpaqueEXT,0x03,origin,e,dir,42.0);while(rayQueryProceedEXT(rq)){}if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return -1;int id=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));float t=rayQueryGetIntersectionTEXT(rq,true);hp=origin+dir*t;if(id==0){hm=1;huv=(hp.xz/14.1+.5)*3.20;hn=floorHitNormal(huv);}else if(id==1){vec3 he=vec3(1.22,1.06,1.22);hn=boxNormal(hp,vec3(0),he);hm=mod(pc.heroMaterial,10.0)<.5?0:6;huv=planarUv(hp,hn);}else if(id<STATIC_INSTANCES){vec3 c,s;boxInfo(id,c,s,hm);hn=boxNormal(hp,c,s);huv=planarUv(hp,hn);}else{int bi=id-STATIC_INSTANCES;bodyHitInfo(bi,hp,hn,huv);hm=bodies[bi].meta.x;}return id;}
vec3 stableBounceDir(Material m,vec3 N,vec3 V,vec2 uv,int source){vec3 T,B;basisFromNormal(N,T,B);float h=hash21(floor(uv*19.0)+vec2(float(source)*.31,float(source)*.17));vec3 diffuse=normalize(N+T*(.18*(h-.5))+B*(.13*(.5-h)));vec3 spec=reflect(-V,N);float specMix=clamp(m.metallic*.82+(1.0-m.roughness)*.32,0.0,.92);return normalize(mix(diffuse,spec,specMix));}
vec3 pointLight(Material m,vec3 N,vec3 V,vec3 P,vec3 lp,vec3 rgb,float power){vec3 d=lp-P;float d2=max(dot(d,d),.24);return brdf(m,N,V,normalize(d),rgb*(power/d2));}
vec3 fireLighting(Material m,vec3 N,vec3 V,vec3 P){float n=nightAmount(),p=14.0*(1.0+n*1.30);vec3 side=pointLight(m,N,V,P,TORCH0,torchColor(.3),p)+pointLight(m,N,V,P,TORCH1,torchColor(2.7),p);vec3 hearth=pointLight(m,N,V,P,FIREPLACE,vec3(1.0,.22,.045),10.5*(1.0+n*.80));return side+hearth;}
vec3 stablePathIndirect(vec3 P,vec3 N,vec3 V,Material first,int source,vec2 uv){vec3 rd=stableBounceDir(first,N,V,uv,source),hp,hn;int hm;vec2 huv;int hid=traceScene(P+N*rayEps(source),rd,source,hp,hn,hm,huv);vec3 F0=mix(vec3(.04),first.base,first.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 throughput=mix(first.base*(1.0-first.metallic),F,clamp(first.metallic+.18*(1.0-first.roughness),0.0,1.0));if(hid<0)return min(throughput*(skySample(rd)*.72+envSample(rd,4.8)*.12),vec3(2.1));float w;Material hit=materialAt(hm,hp,huv,w);hn=normalize(hn);if(dot(hn,-rd)<0)hn=-hn;applyHeroWet(hit,hid,hm,hp,huv,hn);if(length(hit.emission)>0)return min(throughput*hit.emission*.26,vec3(2.1));vec3 bounce=ibl(hit,hn,-rd)*.56+fireLighting(hit,hn,-rd,hp)*.12+gelTransmission(hm,hit,hn,-rd)*.10;return min(throughput*bounce,vec3(2.1));}
vec3 orbHaze(vec3 P){vec3 d=P-bodies[2].posRad.xyz;float r=max(bodies[2].posRad.w,.255),dist=length(d);float intensity=max(max(bodies[2].extra.y,bodies[2].extra.z),bodies[2].extra.w);vec3 col=bodies[2].extra.yzw/max(intensity,.25);float haze=clamp((r-.255)/.075,0.0,1.0);float g=exp(-dist*(1.7+1.5*(1.0-haze)))*haze*intensity;return col*g*.17;}

void main(){
    if(inMaterial==13){outColor=vec4(postProcess(skySample(normalize(inWorldPos-inCameraPos))),1);return;}
    float water;int mat=inMaterial;Material m=materialAt(mat,inWorldPos,inUv,water);vec3 V=normalize(inCameraPos-inWorldPos),T=normalize(inTangent),B=normalize(inBitangent),geomN=normalize(inWorldNormal);if(dot(geomN,V)<0)geomN=-geomN;applyHeroWet(m,inObject,mat,inWorldPos,inUv,geomN);vec3 N=materialNormal(mat,inWorldPos,inUv,geomN,T,B,water);if(dot(N,V)<0)N=-N;
    if(mat==15){float tip=smoothstep(.04,.96,inUv.y),width=mix(.78,.10,tip),edge=abs(inUv.x-.5)*2.0;float jag=.06*sin(pc.time*7.0+inUv.y*15.0+float(inObject));if(edge>width+jag)discard;}
    vec3 lightRaw=max(bodies[2].extra.yzw,vec3(.01));float lightIntensity=max(max(lightRaw.r,lightRaw.g),lightRaw.b);vec3 lightRgb=lightRaw/max(lightIntensity,.25);if(inMaterial==4&&inObject==STATIC_INSTANCES+2){m.base=lightRgb;m.emission=lightRgb*(7.8*lightIntensity);m.roughness=.07;m.clearcoat=.90;}
    if(mat==16){vec3 wc=windowColor(inUv,N,V);float night=nightAmount();vec3 warm=vec3(.12,.060,.025)*night*(.16+.84*(1.0-max(dot(N,V),0.0)));outColor=vec4(postProcess(wc+warm),1);return;}
    if(length(m.emission)>.001){float rim=pow(1.0-max(dot(N,V),0.0),2.0);outColor=vec4(postProcess(m.emission+m.base*(.10+rim*.42)),1);return;}
    vec3 sunL=normalize(vec3(-.53,.67,.52)),lv=bodies[2].posRad.xyz-inWorldPos,localL=normalize(lv);float ld2=max(dot(lv,lv),.35),night=nightAmount();bool pt=pc.heroMaterial>=9.5&&pc.rtEnabled>.5;
    vec3 fireDirect=fireLighting(m,N,V,inWorldPos),haze=orbHaze(inWorldPos);
    if(pt){float localScore=max(dot(N,localL),0.0)*(20.0*lightIntensity/ld2),localVis=1.0;if(localScore>.060)localVis=shadowVisibility(inWorldPos,geomN,bodies[2].posRad.xyz,inObject);vec3 direct=ibl(m,N,V)*.72+brdf(m,N,V,sunL,vec3(1.36,1.17,1.00)*(1.0-night*.84));direct+=brdf(m,N,V,localL,lightRgb*(20.0*lightIntensity/ld2))*localVis;direct+=fireDirect;vec3 indirect=stablePathIndirect(inWorldPos,geomN,V,m,inObject,inUv);vec3 color=direct+indirect*.24+gelTransmission(mat,m,N,V)*.32+haze;float impact=float(bodies[0].meta.w)/1000.0;if(mat==1&&impact>.01){float d=distance(inWorldPos.xz,bodies[0].posRad.xz);color+=vec3(.17,.10,.045)*impact*smoothstep(.90,.06,d)*.34;}outColor=vec4(postProcess(color),1);return;}
    float budget=clamp(pc.rtBudget,0.0,1.0),sunScore=max(dot(N,sunL),0.0)*1.7*(1.0-night*.84),localScore=max(dot(N,localL),0.0)*(20.0*lightIntensity/ld2),vis=1.0;bool important=inObject==0||inObject==1||(inObject>=STATIC_INSTANCES&&inObject<STATIC_INSTANCES+5);if(pc.rtEnabled>.5&&budget>.045&&important&&(sunScore>.025||localScore>.025)){vec3 target=localScore>sunScore?bodies[2].posRad.xyz:inWorldPos+sunL*36.0;vis=shadowVisibility(inWorldPos,geomN,target,inObject);}vec3 color=ibl(m,N,V);color+=brdf(m,N,V,sunL,vec3(1.82,1.49,1.16)*(1.0-night*.86))*((sunScore>=localScore)?vis:1.0);color+=brdf(m,N,V,localL,lightRgb*(20.0*lightIntensity/ld2))*((localScore>sunScore)?vis:1.0);color+=fireDirect+gelTransmission(mat,m,N,V)*.17+haze;
    float NoV=max(dot(N,V),0.0);vec3 F=fresnelSchlick(NoV,mix(vec3(.04),m.base,m.metallic));bool heroWet=inObject==1&&m.clearcoat>.08;bool reflective=(mat==2)||(mat==1&&water>.48)||heroWet||(m.clearcoat>.58)||(mat==19);float importance=max(F.r,max(F.g,F.b))*(1.0-m.roughness*.75)*budget;vec2 stableCell=floor(inUv*48.0)+vec2(float(inObject%17)*.37,float(mat)*.23);float rayGate=hash21(stableCell),rayRate=mix(.018,.085,budget);
    if(pc.rtEnabled>.5&&reflective&&importance>.040&&rayGate<rayRate){vec3 R=reflect(-V,N),hp,hn;int hm;vec2 huv;int hid=traceScene(inWorldPos+geomN*rayEps(inObject),R,inObject,hp,hn,hm,huv);vec3 refl=envSpec(R,m.roughness);if(hid>=0){float hw;Material hit=materialAt(hm,hp,huv,hw);vec3 hitV=normalize(inWorldPos-hp);if(dot(hn,hitV)<0)hn=-hn;applyHeroWet(hit,hid,hm,hp,huv,hn);refl=hit.emission+hit.base*.16+ibl(hit,hn,hitV)*.44;}float strength=mat==1?water*.38:(mat==2?.82:(heroWet?.28:(mat==19?.62:.30)));color=mix(color,refl,clamp(F*strength,vec3(0),vec3(.74)));}
    if(mat==1&&water>.01){vec3 R=reflect(-V,N);float f=.055+.36*pow(1.0-NoV,5.0);vec3 wetRefl=envSpec(R,mix(.070,.18,1.0-water));color=mix(color,wetRefl,clamp(water*(.18+f),0.0,.44));}
    float impact=float(bodies[0].meta.w)/1000.0;if(mat==1&&impact>.01){float d=distance(inWorldPos.xz,bodies[0].posRad.xz);color+=vec3(.15,.085,.038)*impact*smoothstep(.88,.05,d)*.36;}
    if(mat==3){float rim=pow(1.0-NoV,2.0);color+=m.base*vec3(.52,.05,.04)*rim*.07;}if(mat==8){float rim=pow(1.0-NoV,2.4);color+=m.base*vec3(.18,.68,.24)*rim*.16;}outColor=vec4(postProcess(color),1);
}