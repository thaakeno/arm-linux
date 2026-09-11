#version 460
#extension GL_EXT_ray_query : require
layout(location=0) in vec3 inWorldPos;
layout(location=1) in vec3 inWorldNormal;
layout(location=2) in vec3 inCameraPos;
layout(location=3) in vec2 inUv;
layout(location=4) flat in int inMaterial;
layout(location=5) flat in int inObject;
layout(location=0) out vec4 outColor;

struct BodyGpu { vec4 posRad; vec4 velMass; vec4 extra; ivec4 meta; };
layout(set=0,binding=0,std430) readonly buffer Bodies { BodyGpu bodies[]; };
layout(set=0,binding=1) uniform accelerationStructureEXT topLevelAS;
layout(push_constant) uniform Push { float yaw; float pitch; float aspect; float cameraDistance; float preRotation; float quality; float rtEnabled; float time; } pc;

const float PI=3.14159265358979323846;
struct Material { vec3 base; float metallic; float roughness; float clearcoat; vec3 emission; };

float hash21(vec2 p){p=fract(p*vec2(123.34,345.45));p+=dot(p,p+34.345);return fract(p.x*p.y);}
float hash31(vec3 p){p=fract(p*.1031);p+=dot(p,p.yzx+33.33);return fract((p.x+p.y)*p.z);}
Material materialAt(int m,vec3 P){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.emission=vec3(0);
    float micro=(hash31(floor(P*9.0))-.5)*.035;
    if(m==0){a.base=vec3(.64,.59,.50)+micro;a.roughness=.48;}
    else if(m==1){a.base=vec3(.46,.48,.50)+micro*.35;a.roughness=.38;}
    else if(m==2){a.base=vec3(.67,.70,.73);a.metallic=1;a.roughness=.16;}
    else if(m==3){a.base=vec3(.88,.105,.035);a.roughness=.30;a.clearcoat=.48;}
    else if(m==4){a.base=vec3(1.0,.78,.50);a.roughness=.24;a.emission=vec3(5.0,3.45,2.05);}
    else if(m==5){a.base=vec3(.78,.765,.72)+micro*.15;a.roughness=.68;}
    else if(m==6){a.base=vec3(.055,.20,.18);a.roughness=.72;}
    else if(m==7){a.base=vec3(.82,.78,.69);a.roughness=.27;a.clearcoat=.16;}
    else if(m==8){a.base=vec3(.78,.24,.075);a.metallic=1;a.roughness=.22;}
    return a;
}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=r*r,a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
vec3 env(vec3 d){
    float t=clamp(d.y*.5+.5,0.0,1.0);vec3 c=mix(vec3(.13,.145,.16),vec3(.62,.67,.71),t);
    vec3 key=normalize(vec3(-.55,.72,.40)),fill=normalize(vec3(.62,.38,-.69));
    c+=vec3(1.0,.93,.84)*.75*pow(max(dot(d,key),0.0),48.0);c+=vec3(.50,.62,.82)*.22*pow(max(dot(d,fill),0.0),36.0);return c;
}
float rayEps(int objectId){if(objectId>=7){int bi=objectId-7;return max(.003,bodies[bi].posRad.w*.008);}return .004;}

bool occluded(vec3 P,vec3 N,vec3 target,int source){
    vec3 d=target-P;float L=length(d);if(L<.03)return false;d/=L;
    rayQueryEXT rq;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsTerminateOnFirstHitEXT|gl_RayFlagsOpaqueEXT,0xff,P+N*rayEps(source),rayEps(source),d,max(.01,L-.04));
    // Qualcomm explicitly recommends avoiding a traversal loop for terminate-on-first-hit opaque rays.
    rayQueryProceedEXT(rq);
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return false;
    int hit=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));return hit!=source;
}

vec3 staticNormal(int id,vec3 P){
    if(id==0)return vec3(0,1,0);
    if(id==1){vec3 q=P/vec3(1.15,1.0,1.15),a=abs(q);if(a.x>a.y&&a.x>a.z)return vec3(sign(q.x),0,0);if(a.y>a.z)return vec3(0,sign(q.y),0);return vec3(0,0,sign(q.z));}
    if(id==2)return vec3(0,0,1); if(id==3)return vec3(0,0,-1); if(id==4)return vec3(1,0,0); if(id==5)return vec3(-1,0,0); return vec3(0,-1,0);
}
int staticMaterial(int id){if(id==0)return 1;if(id==1)return 0;return 5;}

int traceReflection(vec3 origin,vec3 dir,int source,out vec3 hp,out vec3 hn,out int hm){
    rayQueryEXT rq;rayQueryInitializeEXT(rq,topLevelAS,gl_RayFlagsOpaqueEXT,0xff,origin,rayEps(source),dir,32.0);while(rayQueryProceedEXT(rq)){}
    if(rayQueryGetIntersectionTypeEXT(rq,true)==gl_RayQueryCommittedIntersectionNoneEXT)return -1;
    int id=int(rayQueryGetIntersectionInstanceCustomIndexEXT(rq,true));if(id==source)return -1;
    float t=rayQueryGetIntersectionTEXT(rq,true);hp=origin+dir*t;
    if(id<7){hn=staticNormal(id,hp);hm=staticMaterial(id);}else{int bi=id-7;hn=normalize(hp-bodies[bi].posRad.xyz);hm=bodies[bi].meta.x;}
    return id;
}

vec3 directPbr(Material m,vec3 P,vec3 N,vec3 V,vec3 lightPos,float visibility){
    if(length(m.emission)>0.0)return m.emission;vec3 toL=lightPos-P;float d2=max(dot(toL,toL),.08);vec3 L=normalize(toL);float NoL=max(dot(N,L),0.0);if(NoL<=0.0)return vec3(0);
    vec3 H=normalize(V+L);float NoV=max(dot(N,V),.001),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);
    vec3 spec=Dggx(NoH,m.roughness)*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic);
    return (kd*m.base/PI+spec)*NoL*(58.0/d2)*vec3(1.0,.86,.72)*visibility;
}
vec3 baseLighting(Material m,vec3 P,vec3 N,vec3 V,vec3 lightPos,float visibility){
    if(length(m.emission)>0.0)return m.emission;vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);
    vec3 ambient=m.base*env(N)*(.15+.10*(1.0-m.metallic));vec3 refl=env(R)*F*mix(.32,.07,m.roughness);
    if(m.clearcoat>0.0)refl+=env(R)*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.12;
    return ambient+refl+directPbr(m,P,N,V,lightPos,visibility);
}
vec3 tonemap(vec3 x){x*=1.16;const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

void main(){
    float q=clamp(pc.quality/100.0,0.0,1.0);vec3 N=normalize(inWorldNormal),V=normalize(inCameraPos-inWorldPos);if(dot(N,V)<=0.0)discard;Material m=materialAt(inMaterial,inWorldPos);
    vec3 lightCenter=bodies[2].posRad.xyz;vec3 lightSample=lightCenter;
    float visibility=1.0;
    if(pc.rtEnabled>.5&&q>=.45&&inMaterial!=4){
        vec3 toL=lightCenter-inWorldPos;float d=length(toL);vec3 L=toL/max(d,.001);
        if(dot(N,L)>0.0&&d<18.0){
            vec3 T=normalize(abs(L.y)<.9?cross(L,vec3(0,1,0)):cross(L,vec3(1,0,0)));vec3 B=cross(L,T);
            float h=hash21(floor(gl_FragCoord.xy));float a=6.2831853*h;float r=sqrt(hash21(floor(gl_FragCoord.yx)+17.0))*bodies[2].posRad.w*.62;
            lightSample=lightCenter+(T*cos(a)+B*sin(a))*r;
            visibility=occluded(inWorldPos,N,lightSample,inObject)?0.0:1.0;
        }
    }
    vec3 color=baseLighting(m,inWorldPos,N,V,lightSample,visibility);

    // One true secondary reflection ray, only where the BRDF actually needs it.
    // This keeps the RT workload bounded enough for a 120 Hz mobile target.
    if(pc.rtEnabled>.5&&q>=.62&&m.metallic>.55){
        vec3 R=reflect(-V,N);vec3 hp,hn;int hm;int hid=traceReflection(inWorldPos+N*rayEps(inObject),R,inObject,hp,hn,hm);
        vec3 reflected=env(R);
        if(hid>=0){Material hit=materialAt(hm,hp);vec3 hv=normalize(-R);reflected=baseLighting(hit,hp,hn,hv,lightCenter,1.0);}
        vec3 F=fresnelSchlick(max(dot(N,V),0.0),mix(vec3(.04),m.base,m.metallic));
        color=mix(color,reflected,F*mix(.92,.58,m.roughness));
    }
    outColor=vec4(pow(tonemap(color),vec3(1.0/2.2)),1.0);
}