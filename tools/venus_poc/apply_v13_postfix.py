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
    if(b==0){c=vec3(-9,-.69,-7);s=vec3(2.8,.32,2.4);}
    else if(b==1){c=vec3(8,-.49,-6);s=vec3(3.1,.52,2.5);}
    else if(b==2){c=vec3(0,-.20,8);s=vec3(4.2,.82,2.3);}
    else if(b==3){c=vec3(-10,-.89,3);s=vec3(1.7,.12,1.2);}
    else if(b==4){c=vec3(-10,-.68,4.3);s=vec3(1.7,.20,1.2);}
    else if(b==5){c=vec3(-10,-.43,5.6);s=vec3(1.7,.25,1.2);}
    else if(b==6){c=vec3(-10,-.12,6.9);s=vec3(1.7,.31,1.2);}
    else if(b==7){c=vec3(-10,.25,8.2);s=vec3(1.7,.37,1.2);}
    else if(b==8){c=vec3(11,-.45,7);s=vec3(1.3,.56,1.3);}
    else if(b==9){c=vec3(11,.70,7);s=vec3(.95,.58,.95);}
    else if(b==10){c=vec3(6,-.70,2.5);s=vec3(1.25,.31,1.25);}
    else if(b==11){c=vec3(6,-.02,2.5);s=vec3(.90,.36,.90);}
    else if(b==12){c=vec3(-4,-.78,-11);s=vec3(3,.23,1.3);}
    else if(b==13){c=vec3(-4,-.31,-11);s=vec3(2.2,.24,1);}
    else if(b==14){c=vec3(-4,.14,-11);s=vec3(1.35,.21,.75);}
    else if(b==15){c=vec3(4,-.62,-13);s=vec3(4,.40,1.8);}
    else if(b==16){c=vec3(13,-.60,-2);s=vec3(2.5,.42,2.5);}
    else{c=vec3(-14,-.55,-3);s=vec3(2.8,.47,2.2);}
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
        std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,.10f,3.50f);lightIntensity_=v;
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
p.write_text(text)

# The v13 first pass also used a nested-brace regex for screenRayV7 and could
# leave the tail of the orbit-only implementation behind. Replace the whole span.
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

p = ROOT / "app/src/main/cpp/vessel_jolt_world.cpp"
text = p.read_text()
old = 'JobSystemThreadPool jobs{cMaxPhysicsJobs, cMaxPhysicsBarriers, std::max(1u, std::min(3u, std::thread::hardware_concurrency() > 1 ? std::thread::hardware_concurrency() - 1 : 1u))};'
new = 'JobSystemThreadPool jobs{cMaxPhysicsJobs, cMaxPhysicsBarriers, static_cast<int>(std::max(1u, std::min(3u, std::thread::hardware_concurrency() > 1 ? std::thread::hardware_concurrency() - 1 : 1u)))};'
if old not in text: raise SystemExit("Jolt worker marker missing")
p.write_text(text.replace(old,new))

print("v13 post-fix applied: RT boxes + Jolt authority + light setter + FPS ray + Android Jolt workers")
