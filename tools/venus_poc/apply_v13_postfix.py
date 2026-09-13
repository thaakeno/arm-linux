#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def patch_between(path: str, start: str, end: str, replacement: str) -> None:
    p = ROOT / path
    text = p.read_text()
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"missing start marker in {path}: {start}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"missing end marker in {path}: {end}")
    p.write_text(text[:a] + replacement + text[b:])

box = '''void boxInfo(int id,out vec3 c,out vec3 s,out int mat){
    int b=id-2;mat=12;c=vec3(0);s=vec3(1);
    if(b==0){c=vec3(-14.4,-.69,-11.2);s=vec3(2.8,.32,2.4);}
    else if(b==1){c=vec3(12.8,-.49,-9.6);s=vec3(3.1,.52,2.5);}
    else if(b==2){c=vec3(0,-.20,12.8);s=vec3(4.2,.82,2.3);}
    else if(b==3){c=vec3(-16,-.89,4.8);s=vec3(1.7,.12,1.2);}
    else if(b==4){c=vec3(-16,-.68,6.9);s=vec3(1.7,.20,1.2);}
    else if(b==5){c=vec3(-16,-.43,9.0);s=vec3(1.7,.25,1.2);}
    else if(b==6){c=vec3(-16,-.12,11.0);s=vec3(1.7,.31,1.2);}
    else if(b==7){c=vec3(-16,.25,13.1);s=vec3(1.7,.37,1.2);}
    else if(b==8){c=vec3(17.6,-.45,11.2);s=vec3(1.3,.56,1.3);}
    else if(b==9){c=vec3(17.6,.70,11.2);s=vec3(.95,.58,.95);}
    else if(b==10){c=vec3(9.6,-.70,4.0);s=vec3(1.25,.31,1.25);}
    else if(b==11){c=vec3(9.6,-.02,4.0);s=vec3(.90,.36,.90);}
    else if(b==12){c=vec3(-6.4,-.78,-17.6);s=vec3(3,.23,1.3);}
    else if(b==13){c=vec3(-6.4,-.31,-17.6);s=vec3(2.2,.24,1);}
    else if(b==14){c=vec3(-6.4,.14,-17.6);s=vec3(1.35,.21,.75);}
    else if(b==15){c=vec3(6.4,-.62,-20.8);s=vec3(4,.40,1.8);}
    else if(b==16){c=vec3(20.8,-.60,-3.2);s=vec3(2.5,.42,2.5);}
    else{c=vec3(-22.4,-.55,-4.8);s=vec3(2.8,.47,2.2);}
}
'''
patch_between("app/src/main/cpp/shaders/native_studio_v7_rt.frag", "void boxInfo(int id,out vec3 c,out vec3 s,out int mat)", "vec3 boxNormal", box)

p = ROOT / "app/src/main/cpp/native_vulkan_studio_v7_part2.inc"
text = p.read_text()
start = text.find("    int activeBodiesV7()const{")
if start >= 0:
    end = text.find("    void configureBodiesV7()", start)
    if end < 0: raise SystemExit("activeBodiesV7 end marker missing")
    text = text[:start] + "    int activeBodiesV7()const{return int(kV7QualityBodies);}\n" + text[end:]
p.write_text(text)

p1 = "app/src/main/cpp/native_vulkan_studio_v7_part1.inc"
light_setter = '''    void setLightIntensity(float v){
        std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,.10f,2.00f);lightIntensity_=v;
        Vec3 c=deformDir_[2];float m=std::max(c.x,std::max(c.y,c.z));if(m<1e-4f)c={1,.62f,.31f};else c=c/m;
        bodies_[2].deform=v;deformDir_[2]=c*v;logV7Unlocked("light intensity -> "+std::to_string(v));
    }
    '''
patch_between(p1, "    void setLightIntensity(float v)", "void setLightHaze", light_setter)

p = ROOT / p1
text = p.read_text()
old = 'void setLightHaze(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,1.0f);bodies_[2].r=.255f+.075f*v;lightHaze_=v;logV7Unlocked("light haze -> "+std::to_string(v));}'
new = 'void setLightHaze(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,1.0f);lightHaze_=v;logV7Unlocked("light haze -> "+std::to_string(v));}'
if old in text: text = text.replace(old, new)
text = text.replace('bodies_[2].r=.255f+.075f*lightHaze_.load();', 'bodies_[2].r=.285f;')
text = text.replace('deformDir_[2]={1.0f,.62f,.31f}*lightIntensity_.load();', 'deformDir_[2]=Vec3{1.0f,.62f,.31f}*lightIntensity_.load();')
p.write_text(text)

screen_ray = '''    void screenRayV7(float nx,float ny,Vec3&ro,Vec3&rd)const{
        float yaw=yaw_.load(),pitch=pitch_.load(),cp=std::cos(pitch);Vec3 f;
        if(firstPerson_.load()){
            ro={fpX_.load(),fpY_.load(),fpZ_.load()};
            f=norm(Vec3{-cp*std::sin(yaw),std::sin(pitch),-cp*std::cos(yaw)});
        }else{
            ro=cameraDistance_.load()*Vec3{cp*std::sin(yaw),std::sin(pitch),cp*std::cos(yaw)}+Vec3{0,.38f,1.70f};
            Vec3 target{0,-.15f,-.15f};f=norm(target-ro);
        }
        Vec3 r=norm(cross(f,{0,1,0})),u=norm(cross(r,f));float x=nx*2-1,y=1-ny*2;
        float aspect=float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load()));
        float tanHalf=std::tan(34.0f*kPi/360.0f);rd=norm(f+r*(x*aspect*tanHalf)+u*(y*tanHalf));
    }
    '''
patch_between("app/src/main/cpp/native_vulkan_studio_v7_part3.inc", "    void screenRayV7(float nx,float ny,Vec3&ro,Vec3&rd)const", "    void grabStartImpl", screen_ray)

# Physics remains 100% Jolt-authoritative. The render deformation below is driven
# by actual Jolt velocity impulses and decays immediately after contact. It does
# not replace collision/CCD and cannot pull the collision sphere through geometry.
p3 = ROOT / "app/src/main/cpp/native_vulkan_studio_v7_part3.inc"
text = p3.read_text()
old_step = 'for(int i=0;i<int(kV7QualityBodies);++i){auto s=jolt_->state(i);Body&b=bodies_[i];b.p={s.px,s.py,s.pz};b.v={s.vx,s.vy,s.vz};b.deform=0.0f;b.deformV=0.0f;if(i!=2)deformDir_[i]={0,1,0};}'
new_step = '''for(int i=0;i<int(kV7QualityBodies);++i){
            auto s=jolt_->state(i);Body&b=bodies_[i];float prevVy=b.v.y;float impulse=std::max(0.0f,s.vy-prevVy);
            b.p={s.px,s.py,s.pz};b.v={s.vx,s.vy,s.vz};
            if(i==1||i==3){float cap=i==1?.34f:.24f;float target=std::clamp(impulse*(i==1?.050f:.036f),0.0f,cap);float rate=target>b.deform?.70f:.18f;b.deform+=(target-b.deform)*rate;b.deform=std::max(0.0f,b.deform-.012f);deformDir_[i]={0,1,0};}
            else if(i!=2){b.deform=0.0f;deformDir_[i]={0,1,0};}
        }'''
if old_step not in text: raise SystemExit('Jolt mirror loop marker missing')
text = text.replace(old_step, new_step, 1)
p3.write_text(text)

# Render world fixes: 120 m floor, distant HDR environment shell, no tiny-room
# illusion. The environment shell is camera-direction shaded, so at this distance
# its cube faces are no longer perceptible as walls.
vert = ROOT / "app/src/main/cpp/shaders/native_studio_v7.vert"
text = vert.read_text()
text = text.replace('uv=g*3.20; vec2 xz=(g*2.0-1.0)*7.05;', 'uv=g*18.0; vec2 xz=(g*2.0-1.0)*60.0;')
text = text.replace('uv=g*8.0; vec2 xz=(g*2.0-1.0)*30.0;', 'uv=g*18.0; vec2 xz=(g*2.0-1.0)*60.0;')
text = text.replace('P=boxPos(face,q)*45.0;', 'P=boxPos(face,q)*600.0;')
text = text.replace('P=boxPos(face,q)*220.0;', 'P=boxPos(face,q)*600.0;')
vert.write_text(text)

# Tone down the physically stacked movable light and wet mirror response. The orb
# remains emissive and illuminates the scene, but no longer acts like a white sun.
frag = ROOT / "app/src/main/cpp/shaders/native_studio_v7_rt.frag"
text = frag.read_text()
text = text.replace('a.emission=col*(7.8*intensity);', 'a.emission=col*(2.15*intensity);')
text = text.replace('m.emission=lightRgb*(7.8*lightIntensity);', 'm.emission=lightRgb*(2.15*lightIntensity);')
text = text.replace('a.roughness=mix(dampR,.070,water);a.clearcoat=water*.62;', 'a.roughness=mix(dampR,.16,water);a.clearcoat=water*.36;')
text = text.replace('m.roughness=mix(m.roughness,.055,w*.94);m.clearcoat=max(m.clearcoat,w*.96);', 'm.roughness=mix(m.roughness,.18,w*.72);m.clearcoat=max(m.clearcoat,w*.58);')
frag.write_text(text)

# Jolt startup/runtime fixes plus one shared 120 m collision world. Dynamic balls
# spawn exactly on the visible floor (except the explicitly emissive light orb).
p = ROOT / "app/src/main/cpp/vessel_jolt_world.cpp"
text = p.read_text()
worker_old = 'JobSystemThreadPool jobs{cMaxPhysicsJobs, cMaxPhysicsBarriers, std::max(1u, std::min(3u, std::thread::hardware_concurrency() > 1 ? std::thread::hardware_concurrency() - 1 : 1u))};'
worker_new = 'JobSystemThreadPool jobs{cMaxPhysicsJobs, cMaxPhysicsBarriers, static_cast<int>(std::max(1u, std::min(3u, std::thread::hardware_concurrency() > 1 ? std::thread::hardware_concurrency() - 1 : 1u)))};'
if worker_old in text: text = text.replace(worker_old, worker_new)
elif worker_new not in text: raise SystemExit("Jolt worker marker missing")

init_marker = 'struct VesselJoltWorld::Impl {\n    BPInterface bp;'
init_repl = '''struct JoltRuntimeInit {
    JoltRuntimeInit(){ ensureJoltRegistered(); }
};

struct VesselJoltWorld::Impl {
    JoltRuntimeInit runtime_init;
    BPInterface bp;'''
if init_marker in text: text = text.replace(init_marker, init_repl, 1)
elif 'JoltRuntimeInit runtime_init;' not in text: raise SystemExit("Jolt init-order marker missing")

text = text.replace('addStaticBox(RVec3(0,-1.21f,0), Vec3(30.0f,.20f,30.0f));', 'addStaticBox(RVec3(0,-1.21f,0), Vec3(60.0f,.20f,60.0f));')
text = text.replace('makeDynamic(0,RVec3(-2.35f,-.30f,0.0f));', 'makeDynamic(0,RVec3(-3.00f,-.33f,1.20f));')
text = text.replace('makeDynamic(1,RVec3( .25f,-.25f,.45f));', 'makeDynamic(1,RVec3(-1.00f,-.29f,3.20f));')
text = text.replace('makeDynamic(1,RVec3( .25f,1.35f,3.20f));', 'makeDynamic(1,RVec3(-1.00f,-.29f,3.20f));')
text = text.replace('makeDynamic(3,RVec3( 2.20f,-.34f,-.10f));', 'makeDynamic(3,RVec3( 2.20f,-.37f,2.20f));')
# Spread the static gameplay islands so the arena reads as a world, not a room.
for old,new in [
('{-9.0f,-0.69f,-7.0f,','{-14.4f,-0.69f,-11.2f,'),('{ 8.0f,-0.49f,-6.0f,','{12.8f,-0.49f,-9.6f,'),('{ 0.0f,-0.20f, 8.0f,','{ 0.0f,-0.20f,12.8f,'),
('{-10.0f,-0.89f, 3.0f,','{-16.0f,-0.89f,4.8f,'),('{-10.0f,-0.68f, 4.3f,','{-16.0f,-0.68f,6.9f,'),('{-10.0f,-0.43f, 5.6f,','{-16.0f,-0.43f,9.0f,'),('{-10.0f,-0.12f, 6.9f,','{-16.0f,-0.12f,11.0f,'),('{-10.0f, 0.25f, 8.2f,','{-16.0f, 0.25f,13.1f,'),
('{ 11.0f,-0.45f, 7.0f,','{17.6f,-0.45f,11.2f,'),('{ 11.0f, 0.70f, 7.0f,','{17.6f, 0.70f,11.2f,'),('{ 6.0f,-0.70f, 2.5f,','{ 9.6f,-0.70f,4.0f,'),('{ 6.0f,-0.02f, 2.5f,','{ 9.6f,-0.02f,4.0f,'),
('{-4.0f,-0.78f,-11.0f,','{-6.4f,-0.78f,-17.6f,'),('{-4.0f,-0.31f,-11.0f,','{-6.4f,-0.31f,-17.6f,'),('{-4.0f, 0.14f,-11.0f,','{-6.4f, 0.14f,-17.6f,'),('{ 4.0f,-0.62f,-13.0f,','{ 6.4f,-0.62f,-20.8f,'),('{13.0f,-0.60f,-2.0f,','{20.8f,-0.60f,-3.2f,'),('{-14.0f,-0.55f,-3.0f,','{-22.4f,-0.55f,-4.8f,')]:
    text = text.replace(old,new)
p.write_text(text)

print("v13 quality fix applied: 120m world + distant HDR sky + sane light energy + Jolt-aligned spawns + impact-driven gummy/rubber squash")
