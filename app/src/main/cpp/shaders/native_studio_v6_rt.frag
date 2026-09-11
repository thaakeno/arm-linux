#version 460
#extension GL_EXT_ray_query : require
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
layout(push_constant) uniform Push { float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time; } pc;

const float PI=3.14159265358979323846;
const int STATIC_INSTANCES=14;
struct Material{vec3 base;float metallic;float roughness;float clearcoat;float transmission;float ior;vec3 absorption;vec3 emission;float anisotropy;};

float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float hash31(vec3 p){p=fract(p*.1031);p+=dot(p,p.yzx+33.33);return fract((p.x+p.y)*p.z);}
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);}
vec3 envSample(vec3 d,float lod){return textureLod(environmentMap,envUv(d),lod).rgb;}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=max(.022,r*r),a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Daniso(vec3 H,vec3 N,vec3 T,vec3 B,float r,float an){float a=max(.035,r*r),ax=max(.025,a*(1.0-an*.72)),ay=max(.025,a*(1.0+an*.92));float x=dot(H,T)/ax,y=dot(H,B)/ay,z=max(dot(H,N),0.0);float d=x*x+y*y+z*z;return 1.0/max(PI*ax*ay*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
vec3 tangentNormal(sampler2D tex,vec2 uv,vec3 N,vec3 T,vec3 B,float strength){vec3 n=texture(tex,uv).xyz*2.0-1.0;n.xy*=strength;return normalize(mat3(normalize(T),normalize(B),normalize(N))*normalize(n));}
void basisFromNormal(vec3 n,out vec3 t,out vec3 b){vec3 a=abs(n.y)<.92?vec3(0,1,0):vec3(1,0,0);t=normalize(cross(a,n));b=normalize(cross(n,t));}

float puddleMask(vec3 P,vec2 uv){
    float r=texture(floorArm,fract(uv)).g;float a=hash21(floor(P.xz*.38)),b=hash21(floor(P.zx*.21+11.7));float basin=smoothstep(.78,.28,r)*.48+smoothstep(.86,1.24,a+b*.48)*.54;
    vec2 f=fract(uv);float edge=min(min(f.x,f.y),min(1.0-f.x,1.0-f.y));float joint=1.0-smoothstep(.025,.070,edge);return clamp(basin+joint*.27,0.0,1.0);
}

Material materialAt(int m,vec3 P,vec2 uv,out float wet){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.transmission=0;a.ior=1.5;a.absorption=vec3(0);a.emission=vec3(0);a.anisotropy=0;wet=0;
    if(m==0){vec2 t=fract(uv*1.35);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(texture(concreteArm,t).g,.26,.94);}
    else if(m==1){vec2 t=fract(uv);vec3 alb=texture(floorAlbedo,t).rgb;float rr=texture(floorArm,t).g;wet=puddleMask(P,uv);a.base=alb*mix(1.0,.58,wet);a.roughness=mix(clamp(rr,.30,.94),.028,wet);a.clearcoat=wet;}
    else if(m==2){a.base=vec3(.68,.715,.75);a.metallic=1;a.roughness=.065;a.anisotropy=.15;}
    else if(m==3){a.base=vec3(.94,.048,.008);a.roughness=.075;a.clearcoat=.34;a.transmission=.78;a.ior=1.46;a.absorption=vec3(.13,1.08,2.75);}
    else if(m==4){a.base=vec3(1,.71,.39);a.roughness=.15;a.emission=vec3(14.0,6.6,2.35);}
    else if(m==5){vec2 t=fract(uv*1.8);a.base=texture(concreteAlbedo,t).rgb*vec3(.69,.72,.75);a.roughness=clamp(texture(concreteArm,t).g+.11,.52,.97);}
    else if(m==6){a.base=vec3(.010,.014,.020);a.metallic=.18;a.roughness=.19;a.clearcoat=.18;}
    else if(m==7){a.base=vec3(.91,.895,.86);a.roughness=.16;a.clearcoat=.72;}
    else if(m==8){float scratch=.5+.5*sin(uv.y*920.0+sin(uv.x*71.0)*7.0);a.base=vec3(.955,.29,.085);a.metallic=1;a.roughness=mix(.12,.25,scratch*.32);a.anisotropy=.82;}
    else if(m==9){a.base=vec3(.73,.80,.85);a.roughness=.43;a.transmission=.72;a.ior=1.47;a.absorption=vec3(.08,.045,.025);}
    return a;
}

vec3 materialNormal(int m,vec3 P,vec2 uv,vec3 N,vec3 T,vec3 B,float wet){
    if(m==0)return tangentNormal(concreteNormal,fract(uv*1.35),N,T,B,.88);
    if(m==1)return tangentNormal(floorNormal,fract(uv),N,T,B,mix(.95,.20,wet));
    if(m==8){float s=sin(uv.y*1180.0+sin(uv.x*43.0)*5.0)*.010+(hash21(floor(uv*700.0))-.5)*.009;return normalize(N+T*s);}
    if(m==6){float s=(hash31(floor(P*165.0))-.5)*.030;return normalize(N+T*s+B*s*.61);}
    return normalize(N);
}

float rayEps(int obj){if(obj>=STATIC_INSTANCES){int bi=obj-STATIC_INSTANCES;return max(.0025,bodies[bi].posRad.w*.006);}return .0035;}
bool shadowOccluded(vec3 P,vec3 N,vec3 target,int source){
    vec3 d=target-P;float L=length(d);if(L<.03)return false;d/=L;rayQueryEXT rq;
    float e=rayEps(source);rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsTerminateOnFirstHitEXT|gl_RayFlagsOpaqueEXT,0xff,P+N*e,e,d,max(.02,L-.025));rayQueryProceedEXT(rq);
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return false;return int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true))!=source;
}

void boxInfo(int id,out vec3 c,out vec3 s,out int mat){
    mat=5;c=vec3(0);s=vec3(1);
    if(id==2){c=vec3(0,1.55,-6.45);s=vec3(6.45,2.55,.12);}else if(id==3){c=vec3(-6.45,.95,0);s=vec3(.12,1.95,6.45);}else if(id==4){c=vec3(6.45,.95,0);s=vec3(.12,1.95,6.45);}
    else if(id==5){c=vec3(-4.55,-.34,-4.75);s=vec3(1.25,.67,.78);mat=0;}else if(id==6){c=vec3(4.55,-.34,-4.55);s=vec3(1.25,.67,.78);mat=0;}
    else if(id==7){c=vec3(-5.05,1.35,-5.75);s=vec3(.38,2.35,.38);mat=0;}else if(id==8){c=vec3(5.05,1.35,-5.75);s=vec3(.38,2.35,.38);mat=0;}
    else if(id==9){c=vec3(-4.94,1.38,-5.34);s=vec3(.055,1.28,.055);mat=4;}else if(id==10){c=vec3(4.94,1.38,-5.34);s=vec3(.055,1.28,.055);mat=4;}
    else if(id==11){c=vec3(0,-.38,-5.65);s=vec3(2.45,.60,.48);mat=5;}else if(id==12){c=vec3(-3.45,-.62,4.95);s=vec3(.10,.38,.10);mat=4;}else if(id==13){c=vec3(3.45,-.62,4.95);s=vec3(.10,.38,.10);mat=4;}
}
vec3 boxNormal(vec3 P,vec3 c,vec3 s){vec3 q=(P-c)/s,a=abs(q);if(a.x>=a.y&&a.x>=a.z)return vec3(sign(q.x),0,0);if(a.y>=a.z)return vec3(0,sign(q.y),0);return vec3(0,0,sign(q.z));}
vec2 planarUv(vec3 P,vec3 N){vec3 a=abs(N);if(a.y>=a.x&&a.y>=a.z)return P.xz*.36;if(a.x>=a.z)return P.zy*.36;return P.xy*.36;}

int traceScene(vec3 origin,vec3 dir,int source,out vec3 hp,out vec3 hn,out int hm,out vec2 huv){
    rayQueryEXT rq;float e=rayEps(source);rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsOpaqueEXT,0xff,origin,e,dir,42.0);while(rayQueryProceedEXT(rq)){}
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return -1;int id=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));if(id==source)return -1;
    float t=rayQueryGetIntersectionTEXT(rq,true);hp=origin+dir*t;
    if(id==0){hn=vec3(0,1,0);hm=1;huv=(hp.xz/13.1+.5)*4.6;}
    else if(id==1){vec3 halfExt=vec3(1.18,1.02,1.18),inner=halfExt-vec3(.105),c=clamp(hp,-inner,inner);hn=normalize(hp-c);if(length(hn)<.5)hn=boxNormal(hp,vec3(0),halfExt);hm=0;huv=planarUv(hp,hn);}
    else if(id<STATIC_INSTANCES){vec3 c,s;boxInfo(id,c,s,hm);hn=boxNormal(hp,c,s);huv=planarUv(hp,hn);}
    else {int bi=id-STATIC_INSTANCES;vec3 n=normalize(hp-bodies[bi].posRad.xyz);hn=n;hm=bodies[bi].meta.x;huv=vec2(atan(n.z,n.x)/(2.0*PI)+.5,acos(clamp(n.y,-1,1))/PI);}
    return id;
}

vec3 brdf(Material m,vec3 N,vec3 T,vec3 B,vec3 V,vec3 L,vec3 radiance){
    float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),.001);if(NoL<=0)return vec3(0);vec3 H=normalize(V+L);float NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);
    float D=m.anisotropy>.05?Daniso(H,N,T,B,m.roughness,m.anisotropy):Dggx(NoH,m.roughness);vec3 spec=D*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic)*(1.0-m.transmission);
    return (kd*m.base/PI+spec)*NoL*radiance;
}
vec3 ibl(Material m,vec3 N,vec3 V){vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);vec3 diff=envSample(N,7.0)*m.base*(1.0-m.metallic)*(1.0-m.transmission)*.45;float lod=mix(.12,7.7,m.roughness*m.roughness);vec3 spec=envSample(R,lod)*F*mix(1.0,.50,m.roughness);if(m.clearcoat>0)spec+=envSample(R,mix(.05,3.2,m.roughness))*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.62;return diff+spec;}
vec3 shadeNoShadow(Material m,vec3 P,vec3 N,vec3 T,vec3 B,vec3 V){
    if(length(m.emission)>0)return m.emission;vec3 sunL=normalize(vec3(-.49,.73,.47));vec3 c=ibl(m,N,V)+brdf(m,N,T,B,V,sunL,vec3(3.6,2.92,2.28));
    vec3 lp=bodies[2].posRad.xyz,d=lp-P;c+=brdf(m,N,T,B,V,normalize(d),vec3(1,.64,.36)*(52.0/max(dot(d,d),.18)));
    vec3 l1=vec3(-4.94,1.38,-5.34)-P;c+=brdf(m,N,T,B,V,normalize(l1),vec3(1,.52,.26)*(10.0/max(dot(l1,l1),.25)));
    vec3 l2=vec3(4.94,1.38,-5.34)-P;c+=brdf(m,N,T,B,V,normalize(l2),vec3(1,.52,.26)*(10.0/max(dot(l2,l2),.25)));return c;
}
vec3 tonemap(vec3 x){x*=1.13;const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

void main(){
    float q=clamp(pc.quality/100.0,0.0,1.0);vec3 V=normalize(inCameraPos-inWorldPos);float wet;Material m=materialAt(inMaterial,inWorldPos,inUv,wet);vec3 T=normalize(inTangent),B=normalize(inBitangent);vec3 N=materialNormal(inMaterial,inWorldPos,inUv,normalize(inWorldNormal),T,B,wet);if(dot(N,V)<=-.08)discard;
    if(length(m.emission)>0){outColor=vec4(pow(tonemap(m.emission),vec3(1.0/2.2)),1);return;}

    vec3 sunL=normalize(vec3(-.49,.73,.47));vec3 localVec=bodies[2].posRad.xyz-inWorldPos;float localD2=max(dot(localVec,localVec),.18);vec3 localL=normalize(localVec);
    float sunScore=max(dot(N,sunL),0.0)*3.6;float localScore=max(dot(N,localL),0.0)*(52.0/localD2);float vis=1.0;
    if(pc.rtEnabled>.5&&q>=.42&&(sunScore>0.02||localScore>0.02)){
        vec3 target;
        if(localScore>sunScore*.82){float h=hash21(floor(gl_FragCoord.xy));float a=h*2.0*PI,r=sqrt(hash21(floor(gl_FragCoord.yx)+19.0))*bodies[2].posRad.w*.72;vec3 LT=normalize(abs(localL.y)<.9?cross(localL,vec3(0,1,0)):cross(localL,vec3(1,0,0)));vec3 LB=cross(localL,LT);target=bodies[2].posRad.xyz+(LT*cos(a)+LB*sin(a))*r;}
        else target=inWorldPos+sunL*38.0;
        vis=shadowOccluded(inWorldPos,N,target,inObject)?0.035:1.0;
    }

    vec3 color=ibl(m,N,V);color+=brdf(m,N,T,B,V,sunL,vec3(3.6,2.92,2.28))*((sunScore>=localScore*.82)?vis:1.0);
    color+=brdf(m,N,T,B,V,localL,vec3(1,.64,.36)*(52.0/localD2))*((localScore>sunScore*.82)?vis:1.0);
    vec3 l1=vec3(-4.94,1.38,-5.34)-inWorldPos;color+=brdf(m,N,T,B,V,normalize(l1),vec3(1,.52,.26)*(10.0/max(dot(l1,l1),.25)));
    vec3 l2=vec3(4.94,1.38,-5.34)-inWorldPos;color+=brdf(m,N,T,B,V,normalize(l2),vec3(1,.52,.26)*(10.0/max(dot(l2,l2),.25)));

    bool reflective=m.metallic>.48||wet>.58||m.clearcoat>.64;
    if(pc.rtEnabled>.5&&q>=.58&&reflective){
        vec3 R=reflect(-V,N),hp,hn;int hm;vec2 huv;int hid=traceScene(inWorldPos+N*rayEps(inObject),R,inObject,hp,hn,hm,huv);vec3 reflected=envSample(R,mix(.05,7.0,m.roughness*m.roughness));
        if(hid>=0){float hw;Material hit=materialAt(hm,hp,huv,hw);vec3 ht,hb;basisFromNormal(hn,ht,hb);vec3 hv=normalize(-R);vec3 hnn=materialNormal(hm,hp,huv,hn,ht,hb,hw);reflected=shadeNoShadow(hit,hp,hnn,ht,hb,hv);}
        vec3 F=fresnelSchlick(max(dot(N,V),0.0),mix(vec3(.04),m.base,m.metallic));float strength=m.metallic>.5?mix(.96,.58,m.roughness):mix(.18,.78,wet);color=mix(color,reflected,F*strength);
    }

    if(m.transmission>.05){
        vec3 refrDir=refract(-V,N,1.0/m.ior);vec3 trans=envSample(refrDir,mix(.4,6.6,m.roughness));
        if(pc.rtEnabled>.5&&q>=.72&&inMaterial==3){float radius=(inObject>=STATIC_INSTANCES)?bodies[inObject-STATIC_INSTANCES].posRad.w:.7;vec3 start=inWorldPos+refrDir*(radius*1.72);vec3 hp,hn;int hm;vec2 huv;int hid=traceScene(start,refrDir,inObject,hp,hn,hm,huv);if(hid>=0){float hw;Material hit=materialAt(hm,hp,huv,hw);vec3 ht,hb;basisFromNormal(hn,ht,hb);trans=shadeNoShadow(hit,hp,hn,ht,hb,normalize(-refrDir));}}
        float thickness=(inMaterial==3?1.45:.75);vec3 absorb=exp(-m.absorption*thickness);float f0=pow((1.0-m.ior)/(1.0+m.ior),2.0);vec3 F=fresnelSchlick(max(dot(N,V),0.0),vec3(f0));color=mix(color,trans*absorb,(vec3(1)-F)*m.transmission);
    }

    outColor=vec4(pow(tonemap(max(color,vec3(0))),vec3(1.0/2.2)),1);
}
