#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]

def read(path: str) -> str:
    return (ROOT / path).read_text()

def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)

def rep(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"v13 marker missing in {path}: {old[:180]!r}")
    write(path, text.replace(old, new))

def sub(path: str, pattern: str, repl: str, count: int = 1) -> None:
    text = read(path)
    new, n = re.subn(pattern, repl, text, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f"v13 regex expected {count}, got {n} in {path}: {pattern[:140]!r}")
    write(path, new)

# -----------------------------------------------------------------------------
# Jolt 5.5 integration. 5.5 is used deliberately as the conservative stable tag
# for this Android build. All benchmark bodies are real Jolt dynamic rigid bodies
# with LinearCast CCD; the old hand-written sphere solver is no longer authoritative.
# -----------------------------------------------------------------------------
cmake = "app/src/main/cpp/CMakeLists.txt"
rep(cmake, "project(dev1_linux_native)\n", '''project(dev1_linux_native)\n\ninclude(FetchContent)\nset(OVERRIDE_CXX_FLAGS OFF CACHE BOOL \"\" FORCE)\nset(CPP_EXCEPTIONS_ENABLED ON CACHE BOOL \"\" FORCE)\nset(CPP_RTTI_ENABLED ON CACHE BOOL \"\" FORCE)\nset(ENABLE_ALL_WARNINGS OFF CACHE BOOL \"\" FORCE)\nset(GENERATE_DEBUG_SYMBOLS OFF CACHE BOOL \"\" FORCE)\nset(INTERPROCEDURAL_OPTIMIZATION OFF CACHE BOOL \"\" FORCE)\nset(FLOATING_POINT_EXCEPTIONS_ENABLED OFF CACHE BOOL \"\" FORCE)\nset(DEBUG_RENDERER_IN_DEBUG_AND_RELEASE OFF CACHE BOOL \"\" FORCE)\nset(PROFILER_IN_DEBUG_AND_RELEASE OFF CACHE BOOL \"\" FORCE)\nset(ENABLE_OBJECT_STREAM OFF CACHE BOOL \"\" FORCE)\nset(ENABLE_INSTALL OFF CACHE BOOL \"\" FORCE)\nset(BUILD_SHARED_LIBS OFF CACHE BOOL \"\" FORCE)\nFetchContent_Declare(JoltPhysics\n    GIT_REPOSITORY https://github.com/jrouwe/JoltPhysics.git\n    GIT_TAG v5.5.0\n    SOURCE_SUBDIR Build)\nFetchContent_MakeAvailable(JoltPhysics)\n''')
rep(cmake,
    "add_library(vessel_vulkan SHARED native_vulkan_studio_v7.cpp)",
    "add_library(vessel_vulkan SHARED native_vulkan_studio_v7.cpp vessel_jolt_world.cpp)")
rep(cmake,
    '    "${CMAKE_CURRENT_SOURCE_DIR}/third_party")',
    '    "${CMAKE_CURRENT_SOURCE_DIR}/third_party"\n    "${joltphysics_SOURCE_DIR}")')
rep(cmake,
    "target_link_libraries(vessel_vulkan PRIVATE android log vulkan)",
    "target_link_libraries(vessel_vulkan PRIVATE android log vulkan Jolt)")

p1 = "app/src/main/cpp/native_vulkan_studio_v7_part1.inc"
rep(p1, "#include <jni.h>\n", "#include <jni.h>\n#include <memory>\n#include \"vessel_jolt_world.h\"\n")
rep(p1,
    "struct PushV7 {float yaw,pitch,aspect,cameraDistance;float preRotation,quality,rtEnabled,time;float wetness,exposure,heroMaterial,rtBudget;};",
    "struct PushV7 {float yaw,pitch,aspect,cameraDistance;float preRotation,quality,rtEnabled,time;float wetness,exposure,heroMaterial,rtBudget;float camX,camY,camZ,firstPerson;};")
rep(p1, "constexpr float kV7RoomHalf=6.82f;", "constexpr float kV7RoomHalf=29.50f;")
rep(p1, "// Vessel Vulkan Studio v12.0", "// Vessel Vulkan Studio v13.0")
rep(p1,
    "logV7(\"Studio v12.0 · Unreal-style physics playground + deterministic RT GI\");",
    "logV7(\"Studio v13.0 · large outdoor Jolt physics playground + deterministic RT GI\");")
rep(p1,
    "logV7(\"scene · clean Unreal-style playground · platforms + stairs + neutral concrete · torches removed\");",
    "logV7(\"scene · 60 m open playground · day/night HDR sky · platforms + stairs · no enclosing box\");")

# The v12 generic body tuner now updates Jolt immediately. Mass changes recreate
# the Jolt body while preserving its pose/velocity.
sub(p1,
    r"void setBodyProperty\(int body,int property,float value\)\{.*?\n    \}",
    '''void setBodyProperty(int body,int property,float value){
        float r=0,m=1,e=0,f=.5f;
        {
            std::lock_guard<std::mutex>lock(bodyMutex_);if(body<0||body>=int(kV7QualityBodies))return;Body&b=bodies_[body];
            if(property==0)b.restitution=std::clamp(value,0.0f,.98f);
            else if(property==1)b.friction=std::clamp(value,.05f,1.20f);
            else if(property==2)b.mass=std::clamp(value,.12f,80.0f);
            r=b.r;m=b.mass;e=b.restitution;f=b.friction;
            logV7Unlocked("Jolt body tune -> "+std::to_string(body)+" / "+std::to_string(property)+" = "+std::to_string(value));
        }
        if(jolt_)jolt_->setBodyProperties(body,r,m,e,f);
    }''')

rep(p1,
    'void setPhysics(bool v){physics_=v;physicsAccumulator_=0;logV7(v?"physics -> ON":"physics -> OFF");}',
    'void setPhysics(bool v){physics_=v;physicsAccumulator_=0;if(jolt_)jolt_->setEnabled(v);logV7(v?"physics -> ON · Jolt":"physics -> OFF");}')

# Fix the actual light control bug: store the requested intensity and preserve its
# magnitude in BodyGpu instead of normalizing it away before the shader sees it.
sub(p1,
    r"void setLightIntensity\(float v\)\{.*?\}",
    '''void setLightIntensity(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,.10f,3.50f);lightIntensity_=v;Vec3 c=deformDir_[2];float m=std::max(c.x,std::max(c.y,c.z));if(m<1e-4f)c={1,.62f,.31f};else c=c/m;bodies_[2].deform=v;deformDir_[2]=c*v;logV7Unlocked("light intensity -> "+std::to_string(v));}''')
rep(p1,
    "void setLightBounce(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,.90f);bodies_[2].restitution=v;lightBounce_=v;logV7Unlocked(\"light bounce -> \"+std::to_string(v));}",
    "void setLightBounce(float v){float r,m,f;{std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,.98f);bodies_[2].restitution=v;lightBounce_=v;r=bodies_[2].r;m=bodies_[2].mass;f=bodies_[2].friction;logV7Unlocked(\"light bounce -> \"+std::to_string(v));}if(jolt_)jolt_->setBodyProperties(2,r,m,v,f);}")

# Real mobile first-person state. The render camera gets an explicit eye position
# through the extended push constants while yaw/pitch remain the look controls.
insert_after = "    void grabStart(float nx,float ny){grabStartImpl(nx,ny,false);}\n"
rep(p1, insert_after, insert_after + '''    void setFirstPerson(bool v){firstPerson_=v;if(v){fpX_=0.0f;fpY_=.68f;fpZ_=13.5f;moveX_=moveZ_=0.0f;}logV7(v?"camera -> FIRST PERSON":"camera -> ORBIT");}\n    void setFirstPersonMove(float x,float z){moveX_=std::clamp(x,-1.0f,1.0f);moveZ_=std::clamp(z,-1.0f,1.0f);}\n''')

# Jolt owns reset state and then the renderer mirrors it.
sub(p1,
    r"void resetPhysics\(\)\{.*?\}\n    void grabStart",
    '''void resetPhysics(){
        {std::lock_guard<std::mutex>lock(bodyMutex_);initBodiesLocked();deformDir_.fill({0,1,0});configureBodiesLockedV7();bodies_[2].deform=lightIntensity_.load();bodies_[2].r=.255f+.075f*lightHaze_.load();bodies_[2].restitution=lightBounce_.load();deformDir_[2]={1.0f,.62f,.31f}*lightIntensity_.load();grabbed_=-1;grabTargetVelocity_={};omega_.fill({});spin_.fill(0);sleepTimer_.fill(0);physicsAccumulator_=0;}
        if(jolt_){jolt_->reset();for(int i=0;i<int(kV7QualityBodies);++i){std::lock_guard<std::mutex>lock(bodyMutex_);const Body&b=bodies_[i];jolt_->setBodyProperties(i,b.r,b.mass,b.restitution,b.friction);}}
        logV7("scene reset · Jolt CCD world restored");
    }
    void grabStart''')

# Dragging feeds a velocity target into Jolt instead of teleporting / visually
# stretching the body through colliders.
rep(p1,
    "sleepTimer_[grabbed_]=0;if(!physics_.load()){b.p=grabTarget_;b.v={};omega_[grabbed_]={};}}",
    "sleepTimer_[grabbed_]=0;if(jolt_&&physics_.load())jolt_->dragTo(grabTarget_.x,grabTarget_.y,grabTarget_.z,grabTargetVelocity_.x,grabTargetVelocity_.y,grabTargetVelocity_.z);else if(!physics_.load()){b.p=grabTarget_;b.v={};omega_[grabbed_]={};}}")
sub(p1,
    r"void grabEnd\(\)\{.*?\n    \}\n\nprivate:",
    '''void grabEnd(){
        std::lock_guard<std::mutex>lock(bodyMutex_);if(grabbed_>=0){float age=std::chrono::duration<float>(Clock::now()-grabLastUpdate_).count();Vec3 release=age<.125f?grabTargetVelocity_:Vec3{};float speed=len(release);if(speed>11.0f)release=release*(11.0f/speed);if(jolt_&&physics_.load())jolt_->endDrag(release.x,release.y,release.z);}grabbed_=-1;grabTargetVelocity_={};
    }

private:''')
rep(p1,
    "    std::atomic<bool>pathTracing_{false};",
    "    std::unique_ptr<VesselJoltWorld>jolt_{std::make_unique<VesselJoltWorld>()};\n    std::atomic<bool>firstPerson_{false};std::atomic<float>fpX_{0.0f},fpY_{.68f},fpZ_{13.5f},moveX_{0.0f},moveZ_{0.0f};\n    std::atomic<bool>pathTracing_{false};")

# -----------------------------------------------------------------------------
# Replace the hand-written collision solver's authoritative step with Jolt. The
# render Body array is now just a mirror. Fake gummy/rubber ellipsoid deformation
# is removed, eliminating the vertical stretching and collision-shape mismatch.
# -----------------------------------------------------------------------------
p3 = "app/src/main/cpp/native_vulkan_studio_v7_part3.inc"
sub(p3,
    r"void physicsStepV7\(float dt\)\{.*?\n    \}\n    void updateBodyGpuV7",
    '''void physicsStepV7(float dt){
        if(firstPerson_.load()){
            float y=yaw_.load(),mx=moveX_.load(),mz=moveZ_.load();Vec3 f{-std::sin(y),0,-std::cos(y)},r{std::cos(y),0,-std::sin(y)};Vec3 d=f*mz+r*mx;float dl=len(d);if(dl>1.0f)d=d/dl;float speed=5.2f;fpX_=std::clamp(fpX_.load()+d.x*speed*dt,-28.0f,28.0f);fpZ_=std::clamp(fpZ_.load()+d.z*speed*dt,-28.0f,28.0f);
        }
        if(!physics_.load()||!jolt_)return;
        jolt_->step(dt);
        std::lock_guard<std::mutex>lock(bodyMutex_);
        for(int i=0;i<int(kV7QualityBodies);++i){auto s=jolt_->state(i);Body&b=bodies_[i];b.p={s.px,s.py,s.pz};b.v={s.vx,s.vy,s.vz};b.deform=0.0f;b.deformV=0.0f;if(i!=2)deformDir_[i]={0,1,0};}
    }
    void updateBodyGpuV7''')
sub(p3,
    r"void updateBodyGpuV7\(\)\{.*?\}\}\n\n    void screenRayV7",
    '''void updateBodyGpuV7(){if(!bodyMapped_)return;std::lock_guard<std::mutex>lock(bodyMutex_);auto*out=reinterpret_cast<BodyGpu*>(bodyMapped_);for(int i=0;i<kBodyCount;++i){const Body&b=bodies_[i];Vec3 d=deformDir_[i];if(i!=2){float dl=len(d);if(dl<1e-4f)d={0,1,0};else d=d/dl;}out[i]={{b.p.x,b.p.y,b.p.z,b.r},{b.v.x,b.v.y,b.v.z,b.mass},{b.deform,d.x,d.y,d.z},{b.material,0,0,int(std::clamp(impactFx_[i],0.0f,1.0f)*1000.0f)}};}}

    void screenRayV7''')
sub(p3,
    r"void screenRayV7\(float nx,float ny,Vec3&ro,Vec3&rd\)const\{.*?\}",
    '''void screenRayV7(float nx,float ny,Vec3&ro,Vec3&rd)const{float yaw=yaw_.load(),pitch=pitch_.load(),cp=std::cos(pitch);Vec3 f;if(firstPerson_.load()){ro={fpX_.load(),fpY_.load(),fpZ_.load()};f=norm(Vec3{-cp*std::sin(yaw),std::sin(pitch),-cp*std::cos(yaw)});}else{ro=cameraDistance_.load()*Vec3{cp*std::sin(yaw),std::sin(pitch),cp*std::cos(yaw)}+Vec3{0,.38f,1.70f};Vec3 target{0,-.15f,-.15f};f=norm(target-ro);}Vec3 r=norm(cross(f,{0,1,0})),u=norm(cross(r,f));float x=nx*2-1,y=1-ny*2,aspect=float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load()));float tanHalf=std::tan(34.0f*kPi/360.0f);rd=norm(f+r*(x*aspect*tanHalf)+u*(y*tanHalf));}''')
rep(p3,
    "grabbed_=bestIndex;if(grabbed_>=0){Body&b=bodies_[grabbed_];",
    "grabbed_=bestIndex;if(grabbed_>=0){if(jolt_&&physics_.load())jolt_->beginDrag(grabbed_);Body&b=bodies_[grabbed_];")

# v12 floor BLAS was 14.1 m wide. Expand it to match the 60 m Jolt arena.
rep(p3, "(gx*2.0f-1.0f)*7.05f,kFloorY,(gy*2.0f-1.0f)*7.05f", "(gx*2.0f-1.0f)*30.0f,kFloorY,(gy*2.0f-1.0f)*30.0f")

# -----------------------------------------------------------------------------
# Renderer scene: open 60 m benchmark playground. No enclosing walls. The 18
# boxes exactly match the Jolt static colliders.
# -----------------------------------------------------------------------------
p4 = "app/src/main/cpp/native_vulkan_studio_v7_part4.inc"
sub(p4,
    r"void archInfoV7\(int b,Vec3&c,Vec3&s,bool&light\)\{.*?\n    \}",
    '''void archInfoV7(int b,Vec3&c,Vec3&s,bool&light){light=false;c={};s={1,1,1};
        if(b==0){c={-9.0f,-.69f,-7.0f};s={2.8f,.32f,2.4f};}else if(b==1){c={8.0f,-.49f,-6.0f};s={3.1f,.52f,2.5f};}else if(b==2){c={0,-.20f,8.0f};s={4.2f,.82f,2.3f};}
        else if(b==3){c={-10,-.89f,3.0f};s={1.7f,.12f,1.2f};}else if(b==4){c={-10,-.68f,4.3f};s={1.7f,.20f,1.2f};}else if(b==5){c={-10,-.43f,5.6f};s={1.7f,.25f,1.2f};}else if(b==6){c={-10,-.12f,6.9f};s={1.7f,.31f,1.2f};}else if(b==7){c={-10,.25f,8.2f};s={1.7f,.37f,1.2f};}
        else if(b==8){c={11,-.45f,7};s={1.3f,.56f,1.3f};}else if(b==9){c={11,.70f,7};s={.95f,.58f,.95f};}else if(b==10){c={6,-.70f,2.5f};s={1.25f,.31f,1.25f};}else if(b==11){c={6,-.02f,2.5f};s={.90f,.36f,.90f};}
        else if(b==12){c={-4,-.78f,-11};s={3,.23f,1.3f};}else if(b==13){c={-4,-.31f,-11};s={2.2f,.24f,1};}else if(b==14){c={-4,.14f,-11};s={1.35f,.21f,.75f};}else if(b==15){c={4,-.62f,-13};s={4,.40f,1.8f};}else if(b==16){c={13,-.60f,-2};s={2.5f,.42f,2.5f};}else{c={-14,-.55f,-3};s={2.8f,.47f,2.2f};}
    }''')
rep(p4,
    "logV7(\"RT scene · Unreal-style playground platforms + stairs + synchronized body bounds\");",
    "logV7(\"RT scene · 60 m open Jolt playground + platforms/stairs + synchronized CCD spheres\");")
# Push explicit FPS camera position.
rep(p4,
    "PushV7 p{yaw_.load(),pitch_.load(),float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load())),cameraDistance_.load(),rotCode(preTransform_),float(quality_.load()),useRt?1.f:0.f,time,wetness_.load(),exposure_.load(),float(heroMaterial_.load()),rtBudgetV7()};",
    "PushV7 p{yaw_.load(),pitch_.load(),float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load())),cameraDistance_.load(),rotCode(preTransform_),float(quality_.load()),useRt?1.f:0.f,time,wetness_.load(),exposure_.load(),float(heroMaterial_.load()),rtBudgetV7(),fpX_.load(),fpY_.load(),fpZ_.load(),firstPerson_.load()?1.0f:0.0f};")

# -----------------------------------------------------------------------------
# Vertex + RT shaders: big floor/open sky, matching platforms and FPS camera.
# -----------------------------------------------------------------------------
vert = "app/src/main/cpp/shaders/native_studio_v7.vert"
rep(vert, "// Vulkan Studio v12.0 Unreal-style physics playground.", "// Vulkan Studio v13.0 large outdoor Jolt physics playground.")
rep(vert,
    "layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;} pc;",
    "layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;float camX;float camY;float camZ;float firstPerson;} pc;")
rep(vert, "vec2 xz=(g*2.0-1.0)*7.05;", "vec2 xz=(g*2.0-1.0)*30.0;")
sub(vert,
    r"void archInfo\(int block,out vec3 c,out vec3 s,out int mat\)\{.*?\n\}",
    '''void archInfo(int block,out vec3 c,out vec3 s,out int mat){mat=12;c=vec3(0);s=vec3(1);
    if(block==0){c=vec3(-9,-.69,-7);s=vec3(2.8,.32,2.4);}else if(block==1){c=vec3(8,-.49,-6);s=vec3(3.1,.52,2.5);}else if(block==2){c=vec3(0,-.20,8);s=vec3(4.2,.82,2.3);}
    else if(block==3){c=vec3(-10,-.89,3);s=vec3(1.7,.12,1.2);}else if(block==4){c=vec3(-10,-.68,4.3);s=vec3(1.7,.20,1.2);}else if(block==5){c=vec3(-10,-.43,5.6);s=vec3(1.7,.25,1.2);}else if(block==6){c=vec3(-10,-.12,6.9);s=vec3(1.7,.31,1.2);}else if(block==7){c=vec3(-10,.25,8.2);s=vec3(1.7,.37,1.2);}
    else if(block==8){c=vec3(11,-.45,7);s=vec3(1.3,.56,1.3);}else if(block==9){c=vec3(11,.70,7);s=vec3(.95,.58,.95);}else if(block==10){c=vec3(6,-.70,2.5);s=vec3(1.25,.31,1.25);}else if(block==11){c=vec3(6,-.02,2.5);s=vec3(.90,.36,.90);}
    else if(block==12){c=vec3(-4,-.78,-11);s=vec3(3,.23,1.3);}else if(block==13){c=vec3(-4,-.31,-11);s=vec3(2.2,.24,1);}else if(block==14){c=vec3(-4,.14,-11);s=vec3(1.35,.21,.75);}else if(block==15){c=vec3(4,-.62,-13);s=vec3(4,.40,1.8);}else if(block==16){c=vec3(13,-.60,-2);s=vec3(2.5,.42,2.5);}else{c=vec3(-14,-.55,-3);s=vec3(2.8,.47,2.2);}
}''')
rep(vert, "P=boxPos(face,q)*45.0;", "P=boxPos(face,q)*80.0;")
rep(vert,
    "float cp=cos(pc.pitch);vec3 cam=pc.cameraDistance*vec3(cp*sin(pc.yaw),sin(pc.pitch),cp*cos(pc.yaw))+vec3(0,.38,1.70),target=vec3(0,-.15,-.15);\n    vec3 fwd=normalize(target-cam),right=normalize(cross(fwd,vec3(0,1,0))),up=normalize(cross(right,fwd)),rel=P-cam;",
    "float cp=cos(pc.pitch);vec3 cam,target;if(pc.firstPerson>.5){cam=vec3(pc.camX,pc.camY,pc.camZ);vec3 dir=normalize(vec3(-cp*sin(pc.yaw),sin(pc.pitch),-cp*cos(pc.yaw)));target=cam+dir;}else{cam=pc.cameraDistance*vec3(cp*sin(pc.yaw),sin(pc.pitch),cp*cos(pc.yaw))+vec3(0,.38,1.70);target=vec3(0,-.15,-.15);}\n    vec3 fwd=normalize(target-cam),right=normalize(cross(fwd,vec3(0,1,0))),up=normalize(cross(right,fwd)),rel=P-cam;")

frag = "app/src/main/cpp/shaders/native_studio_v7_rt.frag"
rep(frag, "// Vulkan Studio v12.0: Unreal-style playground, stable hybrid HWRT and high-resolution sparse RT GI.", "// Vulkan Studio v13.0: open Jolt playground, stable hybrid HWRT and high-resolution sparse RT GI.")
rep(frag,
    "layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;} pc;",
    "layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;float camX;float camY;float camZ;float firstPerson;} pc;")
# A cleaner day/night sky: HDR daylight, then blue-black night with stars rather
# than turning the Alpine horizon into the old green enclosing-box appearance.
sub(frag,
    r"vec3 skySample\(vec3 d\)\{.*?\}",
    '''vec3 skySample(vec3 d){d=normalize(d);float n=nightAmount();vec3 hdr=envSample(d,1.4);float l=max(max(hdr.r,hdr.g),hdr.b);hdr/=max(l*.30+1.0,1.0);float sun=pow(max(dot(d,normalize(vec3(-.34,.82,-.44))),0.0),420.0);vec3 day=hdr*.78+vec3(1.0,.82,.58)*sun*2.2;float stars=step(.9975,hash21(floor(envUv(d)*vec2(1100,550))))*pow(max(d.y,0.0),.35);vec3 night=vec3(.006,.010,.025)+vec3(.06,.09,.16)*max(d.y,0.0)+vec3(.9,.95,1.0)*stars;return mix(day,night,n);}''')
# Movable orb is now the single configurable point light. Intensity comes from
# BodyGpu.extra.yzw magnitude and affects both raster/hybrid and GI lighting.
sub(frag,
    r"vec3 fireLighting\(Material m,vec3 N,vec3 V,vec3 P\)\{return vec3\(0\);\}",
    '''vec3 fireLighting(Material m,vec3 N,vec3 V,vec3 P){vec3 raw=bodies[2].extra.yzw;float intensity=max(max(raw.r,raw.g),raw.b);vec3 col=intensity>1e-4?raw/intensity:vec3(1,.62,.31);vec3 lp=bodies[2].posRad.xyz,Lv=lp-P;float d2=max(dot(Lv,Lv),.10),d=sqrt(d2);vec3 L=Lv/d;float NoL=max(dot(N,L),0.0);float range=1.0-smoothstep(7.0,17.0,d);float power=intensity*18.0;return col*(power*range*NoL/(1.0+.32*d2));}''')
sub(frag,
    r"void boxInfo\(int id,out vec3 c,out vec3 s,out int mat\)\{.*?\}",
    '''void boxInfo(int id,out vec3 c,out vec3 s,out int mat){int b=id-2;mat=12;c=vec3(0);s=vec3(1);if(b==0){c=vec3(-9,-.69,-7);s=vec3(2.8,.32,2.4);}else if(b==1){c=vec3(8,-.49,-6);s=vec3(3.1,.52,2.5);}else if(b==2){c=vec3(0,-.20,8);s=vec3(4.2,.82,2.3);}else if(b==3){c=vec3(-10,-.89,3);s=vec3(1.7,.12,1.2);}else if(b==4){c=vec3(-10,-.68,4.3);s=vec3(1.7,.20,1.2);}else if(b==5){c=vec3(-10,-.43,5.6);s=vec3(1.7,.25,1.2);}else if(b==6){c=vec3(-10,-.12,6.9);s=vec3(1.7,.31,1.2);}else if(b==7){c=vec3(-10,.25,8.2);s=vec3(1.7,.37,1.2);}else if(b==8){c=vec3(11,-.45,7);s=vec3(1.3,.56,1.3);}else if(b==9){c=vec3(11,.70,7);s=vec3(.95,.58,.95);}else if(b==10){c=vec3(6,-.70,2.5);s=vec3(1.25,.31,1.25);}else if(b==11){c=vec3(6,-.02,2.5);s=vec3(.90,.36,.90);}else if(b==12){c=vec3(-4,-.78,-11);s=vec3(3,.23,1.3);}else if(b==13){c=vec3(-4,-.31,-11);s=vec3(2.2,.24,1);}else if(b==14){c=vec3(-4,.14,-11);s=vec3(1.35,.21,.75);}else if(b==15){c=vec3(4,-.62,-13);s=vec3(4,.40,1.8);}else if(b==16){c=vec3(13,-.60,-2);s=vec3(2.5,.42,2.5);}else{c=vec3(-14,-.55,-3);s=vec3(2.8,.47,2.2);}}''')

# Gummy vs rubber become visually unmistakable: gummy is bright translucent-like
# candy, rubber is dark matte blue-black. Both remain perfect Jolt collision spheres.
sub(frag,
    r"else if\(m==3\)\{.*?\}",
    '''else if(m==3){float pores=hash21(floor(uv*190.0));a.base=mix(vec3(.62,.018,.025),vec3(.98,.12,.055),pores*.28);a.roughness=clamp(.18+pores*.10,.16,.30);a.clearcoat=.72;a.ao=.99;}''')
sub(frag,
    r"else if\(m==7\)\{.*?\}",
    '''else if(m==7){float grain=hash21(floor(uv*210.0));a.base=mix(vec3(.006,.010,.016),vec3(.018,.035,.060),grain*.38);a.roughness=clamp(.70+grain*.14,.68,.88);a.clearcoat=.005;a.ao=.96;}''')

# -----------------------------------------------------------------------------
# UI: v13 identity + compact first-person mode button and dual mobile touch pads.
# -----------------------------------------------------------------------------
ui = "app/src/main/java/com/example/dreamlinux/NativeCubeActivity.kt"
rep(ui, "Vulkan Studio v12.0\\nLoading Unreal-style physics playground…", "Vulkan Studio v13.0\\nLoading large Jolt physics playground…")
rep(ui, "VULKAN STUDIO  //  v12.0", "VULKAN STUDIO  //  v13.0")
rep(ui, "PHYSICS PLAYGROUND · 120 HZ · HWRT · RT GI", "JOLT PLAYGROUND · 120 HZ · HWRT · RT GI")
rep(ui, "private var lightFocusMode = false", "private var lightFocusMode = false\n    private var firstPersonMode = false\n    private lateinit var movePad: View\n    private lateinit var lookPad: View")
rep(ui,
    'else -> "CAMERA · orbit / pinch"',
    'firstPersonMode -> "CAMERA · first person / dual touch"\n                    else -> "CAMERA · orbit / pinch"')
# Quick bar gets an FPS/ORBIT switch.
rep(ui,
    'addView(iconButton("RESET", "Reset physical scene") { resetScene() }, LinearLayout.LayoutParams(dp(58), dp(42)))',
    'addView(iconButton("FPS", "Toggle first person") { toggleFirstPerson() }, LinearLayout.LayoutParams(dp(52), dp(42)))\n        addView(iconButton("RESET", "Reset physical scene") { resetScene() }, LinearLayout.LayoutParams(dp(58), dp(42)))')
# Add transparent move/look pads over the lower half of the viewport.
rep(ui,
    "root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))",
    '''root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        movePad = View(this).apply { visibility = View.GONE; alpha = 0.01f
            setOnTouchListener { v,e -> val h=rendererHandle;val cx=v.width*.5f;val cy=v.height*.55f
                when(e.actionMasked){MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{if(h!=0L){val x=((e.x-cx)/max(cx,1f)).coerceIn(-1f,1f);val z=((cy-e.y)/max(cy,1f)).coerceIn(-1f,1f);nativeFirstPersonMove(h,x,z)}}MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(h!=0L)nativeFirstPersonMove(h,0f,0f)}};true }
        }
        lookPad = View(this).apply { visibility = View.GONE; alpha = 0.01f
            setOnTouchListener { _,e -> val h=rendererHandle;when(e.actionMasked){MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y}MotionEvent.ACTION_MOVE->{if(h!=0L){nativeRotate(h,-(e.x-lastX)*(150f/max(width,1)),-(e.y-lastY)*(105f/max(height,1)));lastX=e.x;lastY=e.y}}};true }
        }
        root.addView(movePad, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels/2, resources.displayMetrics.heightPixels/2, Gravity.BOTTOM or Gravity.START))
        root.addView(lookPad, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels/2, resources.displayMetrics.heightPixels/2, Gravity.BOTTOM or Gravity.END))''')
# Methods + JNI declarations.
marker = "    private fun togglePanel(view: View) {\n"
rep(ui, marker, '''    private fun toggleFirstPerson() {
        firstPersonMode = !firstPersonMode
        interactMode = false; lightFocusMode = false
        if (::movePad.isInitialized) movePad.visibility = if (firstPersonMode) View.VISIBLE else View.GONE
        if (::lookPad.isInitialized) lookPad.visibility = if (firstPersonMode) View.VISIBLE else View.GONE
        if (rendererHandle != 0L) { nativeSetFirstPerson(rendererHandle, firstPersonMode); nativeFirstPersonMove(rendererHandle,0f,0f) }
        appendLog(if(firstPersonMode) "camera -> FIRST PERSON" else "camera -> ORBIT")
    }

''' + marker)
rep(ui,
    "    private external fun nativeSetBodyProperty(handle: Long, body: Int, property: Int, value: Float)\n",
    "    private external fun nativeSetBodyProperty(handle: Long, body: Int, property: Int, value: Float)\n    private external fun nativeSetFirstPerson(handle: Long, enabled: Boolean)\n    private external fun nativeFirstPersonMove(handle: Long, x: Float, z: Float)\n")

p5 = "app/src/main/cpp/native_vulkan_studio_v7_part5.inc"
rep(p5, "Vulkan Studio v12.0", "Vulkan Studio v13.0")
rep(p5, "PLAYGROUND", "JOLT PLAYGROUND")
rep(p5,
    "final scene · steel + gummy + configurable light orb + rubber · playground geometry",
    "final scene · Jolt CCD steel + candy gummy + configurable light orb + matte rubber · 60 m playground")
# Jolt member cleanup is automatic; status explicitly identifies the engine.
rep(p5,
    "READY · 240 Hz physics",
    "READY · Jolt Physics 5.5 CCD")
rep(p5,
    "extern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetBodyProperty(JNIEnv*,jobject,jlong h,jint b,jint p,jfloat v){if(auto*r=ptrV7(h))r->setBodyProperty(b,p,v);}\n",
    "extern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetBodyProperty(JNIEnv*,jobject,jlong h,jint b,jint p,jfloat v){if(auto*r=ptrV7(h))r->setBodyProperty(b,p,v);}\nextern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetFirstPerson(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV7(h))r->setFirstPerson(v);}\nextern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeFirstPersonMove(JNIEnv*,jobject,jlong h,jfloat x,jfloat z){if(auto*r=ptrV7(h))r->setFirstPersonMove(x,z);}\n")

print("Vulkan Studio v13 Jolt playground pass applied")
