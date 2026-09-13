#version 460
layout(location=0) in vec3 inPos;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec2 inUv;
layout(location=3) in float inMat;
layout(location=4) in float inObject;

layout(location=0) out vec3 vWorld;
layout(location=1) out vec3 vNormal;
layout(location=2) out vec2 vUv;
layout(location=3) flat out int vMat;
layout(location=4) out vec3 vView;
layout(location=5) flat out int vObject;
layout(push_constant) uniform Push { vec4 ball; vec4 motion; vec4 misc; } pc;

mat4 viewProj(vec3 eye,vec3 target,float aspect){
    vec3 f=normalize(target-eye),r=normalize(cross(f,vec3(0,1,0))),u=cross(r,f);
    mat4 v=mat4(vec4(r.x,u.x,-f.x,0),vec4(r.y,u.y,-f.y,0),vec4(r.z,u.z,-f.z,0),vec4(-dot(r,eye),-dot(u,eye),dot(f,eye),1));
    float zN=.055,zF=130.0,t=1.0/tan(radians(61.0)*.5);
    mat4 p=mat4(vec4(t/aspect,0,0,0),vec4(0,t,0,0),vec4(0,0,zF/(zN-zF),-1),vec4(0,0,(zN*zF)/(zN-zF),0));
    return p*v;
}

void main(){
    int obj=int(inObject+.5), mat=int(inMat+.5);
    vec3 P=inPos, N=normalize(inNormal);

    // The only runtime-deformed mesh is the hero ball. Its actual sphere geometry is
    // stored in scene.bqmesh; runtime applies the physically driven squash/stretch.
    if(obj==1){
        float s=clamp(pc.motion.x,-.18,.38);
        vec3 rr=vec3(pc.ball.w*inversesqrt(max(.45,1.0-s)),pc.ball.w*(1.0-s),pc.ball.w*inversesqrt(max(.45,1.0-s)));
        P=pc.ball.xyz+inPos*rr;
        N=normalize(inNormal/max(rr,vec3(.001)));
    }

    // Collected orb vertices remain in the immutable asset buffer; hide them by state.
    if(obj>=100 && obj<110){
        int bit=obj-100, mask=int(pc.motion.w+.5);
        if((mask&(1<<bit))!=0){ gl_Position=vec4(2,2,2,1); return; }
        float pulse=.04*sin(pc.motion.y*3.1+float(bit)); P+=N*pulse;
    }

    // The environment mesh follows the player so it can never expose a blue clear-color void.
    if(obj==900) P += vec3(pc.ball.x,0.0,pc.ball.z);

    // Proper third-person game framing. The hero ball stays prominent while the next
    // obstacle group and portal line remain readable instead of showing the whole map.
    vec3 eye=pc.ball.xyz+vec3(-3.25,2.30,5.35);
    vec3 target=pc.ball.xyz+vec3(.25,.32,-3.25);
    mat4 vp=viewProj(eye,target,max(pc.motion.z,.25));
    gl_Position=vp*vec4(P,1.0);
    vWorld=P;vNormal=N;vUv=inUv;vMat=mat;vView=eye-P;vObject=obj;
}
