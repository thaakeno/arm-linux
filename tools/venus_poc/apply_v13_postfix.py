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

# apply_v13_jolt_playground.py originally used a non-greedy regex on a one-line
# GLSL function containing many nested braces. It stopped after the first branch
# and left v12 branches behind. Replace the complete span up to boxNormal.
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
patch_between(
    "app/src/main/cpp/shaders/native_studio_v7_rt.frag",
    "void boxInfo(int id,out vec3 c,out vec3 s,out int mat)",
    "vec3 boxNormal",
    box,
)

# v13 has four authoritative Jolt bodies. Do not expose legacy stress bodies that
# would still be governed by the old render-only Body array.
p = ROOT / "app/src/main/cpp/native_vulkan_studio_v7_part2.inc"
text = p.read_text()
start = text.find("    int activeBodiesV7()const{")
if start >= 0:
    end = text.find("    void configureBodiesV7()", start)
    if end < 0:
        raise SystemExit("activeBodiesV7 end marker missing")
    text = text[:start] + "    int activeBodiesV7()const{return int(kV7QualityBodies);}\n" + text[end:]
p.write_text(text)

# Haze is a visual parameter. It must not silently change the Jolt collision
# radius; that was a render/physics mismatch in v12.
p = ROOT / "app/src/main/cpp/native_vulkan_studio_v7_part1.inc"
text = p.read_text()
old = 'void setLightHaze(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,1.0f);bodies_[2].r=.255f+.075f*v;lightHaze_=v;logV7Unlocked("light haze -> "+std::to_string(v));}'
new = 'void setLightHaze(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,1.0f);lightHaze_=v;logV7Unlocked("light haze -> "+std::to_string(v));}'
if old in text:
    text = text.replace(old, new)
# Reset must also keep the physical orb radius stable.
text = text.replace('bodies_[2].r=.255f+.075f*lightHaze_.load();', 'bodies_[2].r=.285f;')
p.write_text(text)

print("v13 post-fix applied: complete RT boxInfo + four-body Jolt authority + stable orb radius")
