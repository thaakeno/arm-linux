#version 460
layout(location=0) in vec3 inWorldPos;
layout(location=1) in vec3 inWorldNormal;
layout(location=2) in vec3 inCameraPos;
layout(location=3) in vec2 inUv;
layout(location=4) flat in int inMaterial;
layout(location=5) flat in int inObject;
layout(location=0) out vec4 outColor;

struct BodyGpu { vec4 posRad; vec4 velMass; vec4 extra; ivec4 meta; };
layout(set=0,binding=0,std430) readonly buffer Bodies { BodyGpu bodies[]; };
layout(push_constant) uniform Push { float yaw; float pitch; float aspect; float cameraDistance; float preRotation; float quality; float rtEnabled; float time; } pc;

const float PI=3.14159265358979323846;
struct Material { vec3 base; float metallic; float roughness; float clearcoat; vec3 emission; };

float hash31(vec3 p){p=fract(p*0.1031);p+=dot(p,p.yzx+33.33);return fract((p.x+p.y)*p.z);}
Material materialAt(int m,vec3 P){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.emission=vec3(0);
    float micro=(hash31(floor(P*9.0))-.5)*.035;
    if(m==0){a.base=vec3(.64,.59,.50)+micro;a.roughness=.48;}                         // warm stone plinth
    else if(m==1){a.base=vec3(.46,.48,.50)+micro*.35;a.roughness=.38;}                 // neutral microcement floor
    else if(m==2){a.base=vec3(.67,.70,.73);a.metallic=1;a.roughness=.16;}              // stainless
    else if(m==3){a.base=vec3(.88,.105,.035);a.roughness=.30;a.clearcoat=.48;}         // gummy
    else if(m==4){a.base=vec3(1.0,.78,.50);a.roughness=.24;a.emission=vec3(5.0,3.45,2.05);} // light
    else if(m==5){a.base=vec3(.78,.765,.72)+micro*.15;a.roughness=.68;}                // plaster walls
    else if(m==6){a.base=vec3(.055,.20,.18);a.roughness=.72;}                          // matte teal
    else if(m==7){a.base=vec3(.82,.78,.69);a.roughness=.27;a.clearcoat=.16;}           // ceramic
    else if(m==8){a.base=vec3(.78,.24,.075);a.metallic=1;a.roughness=.22;}             // copper
    return a;
}
vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(1.0-c,5.0);}
float Dggx(float NoH,float r){float a=r*r,a2=a*a,d=NoH*NoH*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);}
float Gsmith(float NoV,float NoL,float r){float k=(r+1.0)*(r+1.0)/8.0;return NoV/(NoV*(1.0-k)+k)*NoL/(NoL*(1.0-k)+k);}
vec3 env(vec3 d){
    float t=clamp(d.y*.5+.5,0.0,1.0);vec3 c=mix(vec3(.13,.145,.16),vec3(.62,.67,.71),t);
    vec3 key=normalize(vec3(-.55,.72,.40)),fill=normalize(vec3(.62,.38,-.69));
    c+=vec3(1.0,.93,.84)*.75*pow(max(dot(d,key),0.0),48.0);
    c+=vec3(.50,.62,.82)*.22*pow(max(dot(d,fill),0.0),36.0);return c;
}
vec3 direct(Material m,vec3 P,vec3 N,vec3 V,vec3 lightPos){
    if(length(m.emission)>0.0)return m.emission;
    vec3 toL=lightPos-P;float d2=max(dot(toL,toL),.08);vec3 L=normalize(toL);float NoL=max(dot(N,L),0.0);if(NoL<=0.0)return vec3(0);
    vec3 H=normalize(V+L);float NoV=max(dot(N,V),.001),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);
    vec3 F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(VoH,F0);vec3 spec=Dggx(NoH,m.roughness)*Gsmith(NoV,NoL,m.roughness)*F/max(4.0*NoV*NoL,.001);vec3 kd=(1.0-F)*(1.0-m.metallic);
    return (kd*m.base/PI+spec)*NoL*(58.0/d2)*vec3(1.0,.86,.72);
}
vec3 tonemap(vec3 x){x*=1.16;const float a=2.51,b=.03,c=2.43,d=.59,e=.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}

void main(){
    vec3 N=normalize(inWorldNormal),V=normalize(inCameraPos-inWorldPos);if(dot(N,V)<=0.0)discard;Material m=materialAt(inMaterial,inWorldPos);
    vec3 color;
    if(length(m.emission)>0.0) color=m.emission;
    else {
        vec3 R=reflect(-V,N),F0=mix(vec3(.04),m.base,m.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);
        vec3 ambient=m.base*env(N)*(.15+.10*(1.0-m.metallic));
        vec3 refl=env(R)*F*mix(.32,.07,m.roughness);
        if(m.clearcoat>0.0)refl+=env(R)*fresnelSchlick(max(dot(N,V),0.0),vec3(.04))*m.clearcoat*.12;
        color=ambient+refl+direct(m,inWorldPos,N,V,bodies[2].posRad.xyz);
    }
    outColor=vec4(pow(tonemap(color),vec3(1.0/2.2)),1.0);
}