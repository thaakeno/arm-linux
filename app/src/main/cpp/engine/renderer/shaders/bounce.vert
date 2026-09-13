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

mat4 lookAtRH(vec3 eye, vec3 target){
    vec3 f=normalize(target-eye);
    vec3 r=normalize(cross(f,vec3(0.0,1.0,0.0)));
    vec3 u=cross(r,f);
    return mat4(
        vec4(r.x,u.x,-f.x,0.0),
        vec4(r.y,u.y,-f.y,0.0),
        vec4(r.z,u.z,-f.z,0.0),
        vec4(-dot(r,eye),-dot(u,eye),dot(f,eye),1.0)
    );
}

mat4 perspectiveVk(float fovy,float aspect,float zn,float zf){
    float f=1.0/tan(fovy*0.5);
    // Vulkan's framebuffer Y axis is opposite OpenGL's clip-space convention.
    return mat4(
        vec4(f/max(aspect,.2),0,0,0),
        vec4(0,-f,0,0),
        vec4(0,0,zf/(zn-zf),-1),
        vec4(0,0,(zn*zf)/(zn-zf),0)
    );
}

void main(){
    int obj=int(inObject+.5), mat=int(inMat+.5);
    vec3 P=inPos, N=normalize(inNormal);

    if(obj==1){
        float s=clamp(pc.motion.x,-.18,.38);
        vec3 rr=vec3(pc.ball.w*inversesqrt(max(.45,1.0-s)),pc.ball.w*(1.0-s),pc.ball.w*inversesqrt(max(.45,1.0-s)));
        P=pc.ball.xyz+inPos*rr;
        N=normalize(inNormal/max(rr,vec3(.001)));
    }

    if(obj>=100 && obj<110){
        int bit=obj-100, mask=int(pc.motion.w+.5);
        if((mask&(1<<bit))!=0){gl_Position=vec4(2,2,2,1);return;}
        float pulse=.045*sin(pc.motion.y*3.15+float(bit));
        P+=N*pulse;
    }

    // Keep the environment shell centered on the player while leaving the course static.
    if(obj==900) P+=vec3(pc.ball.x,0.0,pc.ball.z);

    // Target composition: large hero ball in the lower-left/center, obstacle lane and portal ahead.
    vec3 eye=pc.ball.xyz+vec3(4.55,2.85,6.75);
    vec3 target=pc.ball.xyz+vec3(.10,.58,-4.20);
    float aspect=max(pc.motion.z,.25);
    mat4 vp=perspectiveVk(radians(55.0),aspect,.055,145.0)*lookAtRH(eye,target);
    gl_Position=vp*vec4(P,1.0);

    vWorld=P;
    vNormal=N;
    vUv=inUv;
    vMat=mat;
    vView=eye-P;
    vObject=obj;
}
