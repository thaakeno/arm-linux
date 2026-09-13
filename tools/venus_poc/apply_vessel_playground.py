#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]

def read(path: str) -> str:
    return (ROOT / path).read_text()

def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)

def rep(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"playground marker missing in {path}: {old[:160]!r}")
    write(path, text.replace(old, new, count))

def sub(path: str, pattern: str, repl: str, count: int = 1) -> None:
    text = read(path)
    out, n = re.subn(pattern, repl, text, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f"playground regex expected {count}, got {n} in {path}: {pattern[:160]!r}")
    write(path, out)

# -----------------------------------------------------------------------------
# Native renderer: keep the known-good v10.1 renderer/physics foundation, but
# replace the house showcase with a clean open physics playground and better
# contact/audio behavior.
# -----------------------------------------------------------------------------
p1 = "app/src/main/cpp/native_vulkan_studio_v7_part1.inc"
rep(p1, "// Vessel Vulkan Studio v10.1", "// Vessel Playground v1.0")
rep(p1, "constexpr float kV7RoomHalf=6.82f;", "constexpr float kV7RoomHalf=13.80f;")
rep(p1, "constexpr uint32_t kV7Leaves=96u;", "constexpr uint32_t kV7Leaves=0u;")
rep(p1, "ANativeWindow_setBuffersGeometry(w,std::max(1,int(visibleW_.load()*.80f)),std::max(1,int(visibleH_.load()*.80f)),0);",
        "ANativeWindow_setBuffersGeometry(w,std::max(1,int(visibleW_.load()*.82f)),std::max(1,int(visibleH_.load()*.82f)),0);")
rep(p1, "cameraDistance_=7.8f;pitch_=0.04f;yaw_=-0.42f;",
        "cameraDistance_=10.4f;pitch_=-0.08f;yaw_=-0.58f;")
rep(p1, 'logV7("Studio v10.1 · cleaned house showcase + stable reconstructed path lighting");',
        'logV7("Vessel Playground v1.0 · interactive physics arena");')
rep(p1, 'logV7("safety · Adreno tile-aware 0.80x hybrid / 0.42x path scale + sparse ray queries");',
        'logV7("render · Adreno-first 0.82x hybrid / 0.62x GI quality");')
rep(p1, 'logV7("scene · real alpine HDRI through rainy windows · no slime · no TV · side-wall fire sconces");',
        'logV7("scene · open concrete playground · platforms · steps · impact lab · no house gimmicks");')
rep(p1, 'logV7("RT · deterministic hybrid lighting + motion-aware TLAS refits");',
        'logV7("RT · deterministic local reflections/shadows with quality-governed GI");')
rep(p1, "void applyRenderScale(){float s=pathTracing_.load()?.42f:.80f;",
        "void applyRenderScale(){float s=pathTracing_.load()?.62f:.82f;")
rep(p1, 'logV7(v?"PATH QUALITY -> ON · 0.42x sparse reconstructed indirect lighting for 120 Hz target":"PATH QUALITY -> OFF · 0.80x stable hybrid restored");',
        'logV7(v?"GI QUALITY -> ON · 0.62x reconstructed indirect lighting":"GI QUALITY -> OFF · 0.82x 120 Hz hybrid restored");')
rep(p1, 'logV7("rain / wetness -> "+std::to_string(int(wetness_.load()*100)));',
        'logV7("surface wetness -> "+std::to_string(int(wetness_.load()*100)));')
rep(p1, 'logV7((heroMaterial_.load()%10)==0?"hero -> weathered concrete":"hero -> wet volcanic rock");',
        'logV7((heroMaterial_.load()%10)==0?"hero -> weathered concrete":"hero -> rough obsidian");')

# Add two simple game-like actions without disturbing the core renderer.
needle = "    void grabStart(float nx,float ny){grabStartImpl(nx,ny,false);}\n"
insert = needle + '''    void impulseBlast(){\n        std::lock_guard<std::mutex>lock(bodyMutex_);\n        int n=std::min(activeBodiesV7(),int(kV7QualityBodies));\n        for(int i=0;i<n;++i){if(i==2)continue;Body&b=bodies_[i];Vec3 d=b.p-Vec3{0,-.15f,1.6f};d.y=std::max(.18f,d.y+.55f);float dl=len(d);if(dl<.10f)d={0,1,0};else d=d/dl;float strength=b.material==2?5.2f:8.4f;b.v+=d*strength;sleepTimer_[i]=0;}\n        logV7Unlocked("SFX:BLAST:1.0");\n    }\n    void dropTest(){\n        std::lock_guard<std::mutex>lock(bodyMutex_);\n        configureBodiesLockedV7();\n        bodies_[0].p={-1.7f,4.8f,.2f};bodies_[1].p={0.0f,6.0f,.2f};bodies_[3].p={1.8f,5.2f,.2f};\n        bodies_[0].v={0,-.2f,0};bodies_[1].v={0,-.2f,0};bodies_[3].v={0,-.2f,0};\n        logV7Unlocked("drop test armed");\n    }\n'''
rep(p1, needle, insert)

# -----------------------------------------------------------------------------
# C++ scene/physics: larger floor, no room ceiling, matched playground colliders,
# better material-pair restitution, stable multi-axis squash, and impact events.
# -----------------------------------------------------------------------------
p3 = "app/src/main/cpp/native_vulkan_studio_v7_part3.inc"
rep(p3, "float gx=std::clamp(x/14.10f+.5f,0.0f,1.0f),gz=std::clamp(z/14.10f+.5f,0.0f,1.0f);",
        "float gx=std::clamp(x/28.0f+.5f,0.0f,1.0f),gz=std::clamp(z/28.0f+.5f,0.0f,1.0f);")
rep(p3, "float u=gx*3.20f,v=gz*3.20f;", "float u=gx*7.0f,v=gz*7.0f;")
rep(p3, "p.y=std::clamp(p.y,floorSurfaceYV7(p.x,p.z)+r,4.55f-r);",
        "p.y=std::clamp(p.y,floorSurfaceYV7(p.x,p.z)+r,10.5f-r);")
rep(p3, "float u=gx*3.20f,vv=gy*3.20f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*7.05f,kFloorY+h*.058f,(gy*2.0f-1.0f)*7.05f};",
        "float u=gx*7.0f,vv=gy*7.0f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*14.0f,kFloorY+h*.040f,(gy*2.0f-1.0f)*14.0f};")
# Correct the hero mesh: volcanic/obsidian uses its own displacement instead of concrete height.
rep(p3,
    "float tex=sampleHeight(concreteHeightCpu_,su,sv);return heroFacePointV7(face,u,vv,tex-.5f);",
    "bool dark=(heroMaterial_.load()%10)!=0;float tex=sampleHeight(dark?darkHeightCpu_:concreteHeightCpu_,su,sv);return heroFacePointV7(face,u,vv,tex-.5f);")

playground_info = '''    void playgroundInfoV7(int k,Vec3&c,Vec3&s,bool&light)const{\n        light=false;c={0,-.7f,0};s={1,.3f,1};\n        if(k==0){c={-7.5f,-.72f,-4.5f};s={2.8f,.29f,2.7f};}\n        else if(k==1){c={7.3f,-.58f,-4.7f};s={3.1f,.43f,2.5f};}\n        else if(k==2){c={0,-.42f,8.0f};s={4.2f,.59f,2.0f};}\n        else if(k==3){c={-9.2f,-.88f,2.8f};s={1.7f,.12f,1.25f};}\n        else if(k==4){c={-9.2f,-.66f,4.0f};s={1.7f,.21f,1.25f};}\n        else if(k==5){c={-9.2f,-.39f,5.2f};s={1.7f,.27f,1.25f};}\n        else if(k==6){c={-9.2f,-.05f,6.4f};s={1.7f,.34f,1.25f};}\n        else if(k==7){c={-9.2f,.36f,7.6f};s={1.7f,.41f,1.25f};}\n        else if(k==8){c={9.4f,-.64f,5.6f};s={1.25f,.37f,1.25f};}\n        else if(k==9){c={9.4f,.10f,5.6f};s={.95f,.37f,.95f};}\n        else if(k==10){c={5.4f,-.72f,2.2f};s={1.2f,.29f,1.2f};}\n        else if(k==11){c={5.4f,-.08f,2.2f};s={.85f,.35f,.85f};}\n        else if(k==12){c={-4.2f,-.78f,-9.0f};s={3.0f,.22f,1.2f};}\n        else if(k==13){c={-4.2f,-.34f,-9.0f};s={2.2f,.22f,1.0f};}\n        else if(k==14){c={-4.2f,.08f,-9.0f};s={1.35f,.20f,.75f};}\n        else if(k==15){c={4.0f,-.64f,-10.5f};s={4.0f,.37f,1.65f};}\n        else if(k==16){c={11.1f,-.61f,-1.8f};s={2.2f,.40f,2.2f};}\n        else{c={-11.2f,-.59f,-2.6f};s={2.5f,.42f,2.0f};}\n    }\n'''
marker = "    void collideArchitectureV7(Body&b,int i){\n"
rep(p3, marker, playground_info + marker)
rep(p3, "Vec3 c,s;bool light;archInfoV7(k,c,s,light);", "Vec3 c,s;bool light;playgroundInfoV7(k,c,s,light);")
# No ceiling contacts in an outdoor/open arena.
sub(p3,
    r"applyPlaneContactV7\(b,i,nz,\(-kV7RoomHalf\+rz\)-b\.p\.z,rz\);applyPlaneContactV7\(b,i,pz,b\.p\.z-\(kV7RoomHalf-rpz\),rpz\);float floorY=(.*?);float floorPen=(.*?);applyPlaneContactV7\(b,i,ny,floorPen,ry\);applyPlaneContactV7\(b,i,py,\(b\.p\.y\+rpy\)-4\.55f,rpy\);",
    r"applyPlaneContactV7(b,i,nz,(-kV7RoomHalf+rz)-b.p.z,rz);applyPlaneContactV7(b,i,pz,b.p.z-(kV7RoomHalf-rpz),rpz);float floorY=\1;float floorPen=\2;applyPlaneContactV7(b,i,ny,floorPen,ry);")
# Material-pair restitution: a dead-heavy steel ball should not magically inherit gummy restitution.
rep(p3,
    "float e=std::abs(vn)<threshold?0.0f:std::max(a.restitution,b.restitution),jn=-(1+e)*vn/sum;",
    "float pairE=std::sqrt(std::max(0.0f,a.restitution*b.restitution));float e=std::abs(vn)<threshold?0.0f:pairE,jn=-(1+e)*vn/sum;")
# Strong impacts create audio events. Cooldown prevents contact chatter.
rep(p3,
    "float impactSpeed=std::max(0.0f,-vn);if(b.material==2&&impactSpeed>1.10f)",
    "float impactSpeed=std::max(0.0f,-vn);if(impactSpeed>1.25f&&impactAudioCooldown_[i]<=0.0f){const char*kind=b.material==2?\"METAL\":(b.material==3?\"SOFT\":(b.material==7?\"RUBBER\":\"HARD\"));logV7Unlocked(std::string(\"SFX:\")+kind+\":\"+std::to_string(std::min(1.0f,impactSpeed/7.0f)));impactAudioCooldown_[i]=.085f;}if(b.material==2&&impactSpeed>1.10f)")
rep(p3,
    "float impact=std::abs(vn);if(a.gummy)",
    "float impact=std::abs(vn);if(impact>1.35f&&impactAudioCooldown_[ia]<=0.0f&&impactAudioCooldown_[ib]<=0.0f){int ma=a.material,mb=b.material;const char*kind=(ma==2||mb==2)?\"METAL\":((ma==3||mb==3)?\"SOFT\":\"RUBBER\");logV7Unlocked(std::string(\"SFX:\")+kind+\":\"+std::to_string(std::min(1.0f,impact/7.0f)));impactAudioCooldown_[ia]=impactAudioCooldown_[ib]=.085f;}if(a.gummy)")
# Decrement audio cooldowns once per fixed step.
rep(p3,
    "Body&b=bodies_[i];impactFx_[i]=std::max(0.0f,impactFx_[i]-h*1.9f);",
    "Body&b=bodies_[i];impactFx_[i]=std::max(0.0f,impactFx_[i]-h*1.9f);impactAudioCooldown_[i]=std::max(0.0f,impactAudioCooldown_[i]-h);")

# -----------------------------------------------------------------------------
# Vertex scene: 28 m floor, clean arena blocks, no foliage/cushion clutter.
# -----------------------------------------------------------------------------
vert = "app/src/main/cpp/shaders/native_studio_v7.vert"
rep(vert, "// Vulkan Studio v10.1 house showcase. TV/slime/fake-painting test props are gone; geometry is focused on the room, fire, real HDRI windows and benchmark objects.",
          "// Vessel Playground v1.0: clean open physics arena built on the known-good renderer.")
rep(vert, "const int ARCH_BOXES=18; const int BOX_VERTS=36; const int LEAVES=96;", "const int ARCH_BOXES=18; const int BOX_VERTS=36; const int LEAVES=0;")
rep(vert, "uv=g*3.20; vec2 xz=(g*2.0-1.0)*7.05; float q=clamp(pc.quality/100.0,0.0,1.0),amp=.058*q,h=floorH(uv);",
          "uv=g*7.0; vec2 xz=(g*2.0-1.0)*14.0; float q=clamp(pc.quality/100.0,0.0,1.0),amp=.040*q,h=floorH(uv);")
rep(vert,
    "float qq=clamp(pc.quality/100.0,0.0,1.0); vec2 tiled=fract(uv*1.28+vec2(face*.173,face*.071)); float h=qq>0.001?textureLod(concreteHeight,tiled,0.0).r-.5:0.0;",
    "float qq=clamp(pc.quality/100.0,0.0,1.0); vec2 tiled=fract(uv*1.28+vec2(face*.173,face*.071)); bool dark=mod(pc.heroMaterial,10.0)>.5; float h=qq>0.001?(dark?textureLod(darkHeight,tiled,0.0).r:textureLod(concreteHeight,tiled,0.0).r)-.5:0.0;")
new_arch = '''void archInfo(int block,out vec3 c,out vec3 s,out int mat){\n    mat=0;c=vec3(0);s=vec3(1);\n    if(block==0){c=vec3(-7.5,-.72,-4.5);s=vec3(2.8,.29,2.7);mat=0;}\n    else if(block==1){c=vec3(7.3,-.58,-4.7);s=vec3(3.1,.43,2.5);mat=12;}\n    else if(block==2){c=vec3(0,-.42,8.0);s=vec3(4.2,.59,2.0);mat=0;}\n    else if(block==3){c=vec3(-9.2,-.88,2.8);s=vec3(1.7,.12,1.25);mat=0;}\n    else if(block==4){c=vec3(-9.2,-.66,4.0);s=vec3(1.7,.21,1.25);mat=0;}\n    else if(block==5){c=vec3(-9.2,-.39,5.2);s=vec3(1.7,.27,1.25);mat=0;}\n    else if(block==6){c=vec3(-9.2,-.05,6.4);s=vec3(1.7,.34,1.25);mat=0;}\n    else if(block==7){c=vec3(-9.2,.36,7.6);s=vec3(1.7,.41,1.25);mat=0;}\n    else if(block==8){c=vec3(9.4,-.64,5.6);s=vec3(1.25,.37,1.25);mat=12;}\n    else if(block==9){c=vec3(9.4,.10,5.6);s=vec3(.95,.37,.95);mat=12;}\n    else if(block==10){c=vec3(5.4,-.72,2.2);s=vec3(1.2,.29,1.2);mat=0;}\n    else if(block==11){c=vec3(5.4,-.08,2.2);s=vec3(.85,.35,.85);mat=12;}\n    else if(block==12){c=vec3(-4.2,-.78,-9.0);s=vec3(3.0,.22,1.2);mat=0;}\n    else if(block==13){c=vec3(-4.2,-.34,-9.0);s=vec3(2.2,.22,1.0);mat=0;}\n    else if(block==14){c=vec3(-4.2,.08,-9.0);s=vec3(1.35,.20,.75);mat=12;}\n    else if(block==15){c=vec3(4.0,-.64,-10.5);s=vec3(4.0,.37,1.65);mat=0;}\n    else if(block==16){c=vec3(11.1,-.61,-1.8);s=vec3(2.2,.40,2.2);mat=12;}\n    else{c=vec3(-11.2,-.59,-2.6);s=vec3(2.5,.42,2.0);mat=0;}\n}\n'''
sub(vert, r"void archInfo\(int block,out vec3 c,out vec3 s,out int mat\)\{.*?\n\}", new_arch.strip(), 1)
# Remove special torch animation branch; arena geometry stays completely stable.
sub(vert, r"if\(block==9\|\|block==10\)\{.*?\}return;", "return;", 1)

# -----------------------------------------------------------------------------
# RT fragment: ray geometry must exactly match the visible arena. Wet materials are
# restrained and rough instead of looking dipped in clear resin.
# -----------------------------------------------------------------------------
frag = "app/src/main/cpp/shaders/native_studio_v7_rt.frag"
rep(frag, "// Vulkan Studio v10.1: deterministic hybrid lighting, sparse reconstructed path quality, real HDRI windows and texture-driven fire.",
          "// Vessel Playground v1.0: deterministic hybrid lighting for an open interactive arena.")
rep(frag, "m.base*=mix(1.0,.42,w);m.roughness=mix(m.roughness,.055,w*.94);m.clearcoat=max(m.clearcoat,w*.96);",
          "m.base*=mix(1.0,.68,w);m.roughness=mix(m.roughness,.18,w*.72);m.clearcoat=max(m.clearcoat,w*.46);")
rep(frag, "a.roughness=mix(dampR,.070,water);a.clearcoat=water*.62;",
          "a.roughness=mix(dampR,.16,water);a.clearcoat=water*.38;")
# Rough obsidian instead of black mirror-plastic.
rep(frag, "a.base=texture(darkAlbedo,t).rgb*vec3(.20,.21,.24);a.roughness=clamp(arm.g*.82+.03,.26,.82);",
          "a.base=texture(darkAlbedo,t).rgb*vec3(.31,.32,.34);a.roughness=clamp(arm.g*.92+.08,.34,.90);")
new_box = '''void boxInfo(int id,out vec3 c,out vec3 s,out int mat){\n    int b=id-2;mat=0;c=vec3(0);s=vec3(1);\n    if(b==0){c=vec3(-7.5,-.72,-4.5);s=vec3(2.8,.29,2.7);}\n    else if(b==1){c=vec3(7.3,-.58,-4.7);s=vec3(3.1,.43,2.5);mat=12;}\n    else if(b==2){c=vec3(0,-.42,8.0);s=vec3(4.2,.59,2.0);}\n    else if(b==3){c=vec3(-9.2,-.88,2.8);s=vec3(1.7,.12,1.25);}\n    else if(b==4){c=vec3(-9.2,-.66,4.0);s=vec3(1.7,.21,1.25);}\n    else if(b==5){c=vec3(-9.2,-.39,5.2);s=vec3(1.7,.27,1.25);}\n    else if(b==6){c=vec3(-9.2,-.05,6.4);s=vec3(1.7,.34,1.25);}\n    else if(b==7){c=vec3(-9.2,.36,7.6);s=vec3(1.7,.41,1.25);}\n    else if(b==8){c=vec3(9.4,-.64,5.6);s=vec3(1.25,.37,1.25);mat=12;}\n    else if(b==9){c=vec3(9.4,.10,5.6);s=vec3(.95,.37,.95);mat=12;}\n    else if(b==10){c=vec3(5.4,-.72,2.2);s=vec3(1.2,.29,1.2);}\n    else if(b==11){c=vec3(5.4,-.08,2.2);s=vec3(.85,.35,.85);mat=12;}\n    else if(b==12){c=vec3(-4.2,-.78,-9.0);s=vec3(3.0,.22,1.2);}\n    else if(b==13){c=vec3(-4.2,-.34,-9.0);s=vec3(2.2,.22,1.0);}\n    else if(b==14){c=vec3(-4.2,.08,-9.0);s=vec3(1.35,.20,.75);mat=12;}\n    else if(b==15){c=vec3(4.0,-.64,-10.5);s=vec3(4.0,.37,1.65);}\n    else if(b==16){c=vec3(11.1,-.61,-1.8);s=vec3(2.2,.40,2.2);mat=12;}\n    else{c=vec3(-11.2,-.59,-2.6);s=vec3(2.5,.42,2.0);}\n}\n'''
sub(frag, r"void boxInfo\(int id,out vec3 c,out vec3 s,out int mat\)\{.*?\n\}", new_box.strip(), 1)

# -----------------------------------------------------------------------------
# Renderer status and JNI actions.
# -----------------------------------------------------------------------------
p5 = "app/src/main/cpp/native_vulkan_studio_v7_part5.inc"
rep(p5, 'setStatus("Vulkan Studio v10.0 · loading final rain-house renderer…");',
        'setStatus("Vessel Playground v1.0 · loading interactive arena…");')
rep(p5, 'logV7("READY · 240 Hz physics · stable hybrid HWRT · sparse reconstructed path lighting · Alpine rain windows");',
        'logV7("READY · 240 Hz physics · 120 Hz hybrid HWRT · material impact audio · open playground");')
rep(p5, 'logV7("final scene · steel + gummy + light orb + rubber · slime removed");',
        'logV7("playground · steel + gummy + light orb + rubber · platforms + steps + chaos mode");')
rep(p5, '"Vulkan Studio v10.0 · "<<gpuName_', '"Vessel Playground v1.0 · "<<gpuName_')
rep(p5, '(stress_.load()?"SAFE STRESS":"RAIN HOUSE")', '(stress_.load()?"CHAOS":"PLAYGROUND")')
rep(p5, '(pt?"PATH QUALITY":(rt?"RT LOCAL HW":"RT BYPASSED"))', '(pt?"GI QUALITY":(rt?"RT HYBRID":"RT BYPASSED"))')
rep(p5, '<<"bodies "<<activeBodiesV7()<<" · rain "<<int(wetness_.load()*100)<<"% · hero "',
        '<<"bodies "<<activeBodiesV7()<<" · wet "<<int(wetness_.load()*100)<<"% · hero "')
rep(p5, '(pathTracing_.load()?"PATH_QUALITY":(rtActiveV7()?"HYBRID_HWRT":"RASTER"))',
        '(pathTracing_.load()?"GI_QUALITY":(rtActiveV7()?"HYBRID_HWRT":"RASTER"))')
rep(p5, '<<(pathTracing_.load()?0.42:0.80);', '<<(pathTracing_.load()?0.62:0.82);')
rep(p5, 'std::array<Vec3,kBodyCount>omega_{};std::array<float,kBodyCount>spin_{};',
        'std::array<Vec3,kBodyCount>omega_{};std::array<float,kBodyCount>spin_{};std::array<float,kBodyCount>impactAudioCooldown_{};')
# JNI actions.
rep(p5,
    'extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->resetPhysics();}',
    'extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->resetPhysics();}\nextern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeBlast(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->impulseBlast();}\nextern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeDropTest(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->dropTest();}')

# -----------------------------------------------------------------------------
# Android UI + real CC0 impact audio. The UI deliberately loses the neon look and
# becomes a restrained dark tool panel; sound is driven by native material impacts.
# -----------------------------------------------------------------------------
kt = "app/src/main/java/com/example/dreamlinux/NativeCubeActivity.kt"
rep(kt, "import android.os.Build\n", "import android.os.Build\nimport android.media.AudioAttributes\nimport android.media.SoundPool\n")
rep(kt, "    private val logHistory = ArrayDeque<String>()\n",
        "    private val logHistory = ArrayDeque<String>()\n    private lateinit var soundPool: SoundPool\n    private var sfxMetal = 0\n    private var sfxSoft = 0\n")
rep(kt, "        nativeLoaded = try {\n",
        '''        soundPool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()\n        sfxMetal = soundPool.load(this, R.raw.impact_metal, 1)\n        sfxSoft = soundPool.load(this, R.raw.impact_soft, 1)\n        nativeLoaded = try {\n''')
rep(kt, 'text = "Vulkan Studio v10.0\\nLoading rainy house showcase…"',
        'text = "VESSEL PLAYGROUND  v1.0\\nLoading physics arena…"')
rep(kt, 'text = "VULKAN STUDIO  //  v10.0"', 'text = "VESSEL PLAYGROUND  //  v1.0"')
rep(kt, 'text = "RAIN HOUSE · 120 HZ · HWRT · PATH QUALITY"', 'text = "PHYSICS LAB · 120 HZ · HWRT · MATERIAL AUDIO"')
# Neutral UI palette.
rep(kt, 'background = panel(0xE6070C0A.toInt(), 0xFF2D7565.toInt(), 8)', 'background = panel(0xEA101214.toInt(), 0xFF3D4248.toInt(), 3)')
rep(kt, 'background = panel(0xC6101714.toInt(), 0xFF2C403A.toInt(), 6)', 'background = panel(0xE1191C20.toInt(), 0xFF40454C.toInt(), 2)')
rep(kt, 'setTextColor(0xFF70CFB5.toInt())', 'setTextColor(0xFFAEB7C2.toInt())')
rep(kt, 'background = panel(0xF4070C0A.toInt(), 0xFF327A69.toInt(), 9)', 'background = panel(0xF20E1013.toInt(), 0xFF41464D.toInt(), 3)', 2)
rep(kt, 'active -> 0xF018806E.toInt()', 'active -> 0xF03A4654.toInt()')
rep(kt, 'active -> 0xFF64E9CB.toInt()', 'active -> 0xFFB9C7D8.toInt()')
rep(kt, 'else -> 0xEA09100E.toInt()', 'else -> 0xEA171A1E.toInt()')
rep(kt, 'else -> 0xFF2D413B.toInt()', 'else -> 0xFF3E444B.toInt()')
# Terminology and controls.
text = read(kt)
text = text.replace('"PATH QUALITY ON"', '"GI QUALITY ON"').replace('"PATH QUALITY"', '"GI QUALITY"')
text = text.replace('Hybrid targets 120 Hz. Path Quality runs a stable reconstructed indirect bounce at 0.50x to keep ray cost bounded without the old salt-and-pepper flicker.',
                    'Hybrid is the main 120 Hz mode. GI Quality raises indirect-light quality without changing the physics sandbox.')
text = text.replace('"HOUSE"', '"CHAOS"').replace('"STRESS LAB"', '"CHAOS ON"')
text = text.replace('addView(section("HOUSE / WEATHER"))', 'addView(section("ARENA / SURFACE"))')
text = text.replace('"RAIN / WETNESS  ', '"SURFACE WETNESS  ')
text = text.replace('"RAIN / WETNESS  $it%"', '"SURFACE WETNESS  $it%"')
text = text.replace('Rubber and gummy keep collision-matched multi-axis squash with faster release velocity. Slime stays viscous, grounded and slow to relax.',
                    'Rubber and gummy use collision-matched multi-axis squash. Heavy-vs-soft restitution is material-paired so steel no longer makes soft bodies explode.')
# Add game actions directly under the physics controls.
anchor = '            addView(row(physicsButton, interactButton))\n'
extra = anchor + '''            addView(row(\n                makeButton("IMPULSE BLAST") { if (rendererHandle != 0L) nativeBlast(rendererHandle) },\n                makeButton("DROP TEST") { if (rendererHandle != 0L) nativeDropTest(rendererHandle) }\n            ))\n'''
if anchor not in text: raise SystemExit('physics UI anchor missing')
text = text.replace(anchor, extra, 1)
# Native declarations.
text = text.replace('    private external fun nativeResetPhysics(handle: Long)\n',
                    '    private external fun nativeResetPhysics(handle: Long)\n    private external fun nativeBlast(handle: Long)\n    private external fun nativeDropTest(handle: Long)\n')
# Play material-specific impact events as they arrive from native physics.
old_absorb = '''        for (i in start until lines.size) appendLog(lines[i])\n        lastNativeTail = lines.last()\n'''
new_absorb = '''        for (i in start until lines.size) {\n            val line = lines[i]\n            if (line.startsWith("SFX:")) playImpactSfx(line) else appendLog(line)\n        }\n        lastNativeTail = lines.last()\n'''
if old_absorb not in text: raise SystemExit('absorbNativeLog marker missing')
text = text.replace(old_absorb, new_absorb, 1)
# Insert sound helper before appendLog.
helper_anchor = '    private fun appendLog(line: String) {\n'
helper = '''    private fun playImpactSfx(line: String) {\n        if (!::soundPool.isInitialized) return\n        val parts = line.split(':')\n        val amount = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(.12f, 1f) ?: .45f\n        val kind = parts.getOrNull(1).orEmpty()\n        when (kind) {\n            "METAL" -> soundPool.play(sfxMetal, amount, amount, 2, 0, .90f + amount * .15f)\n            "RUBBER" -> soundPool.play(sfxSoft, amount * .70f, amount * .70f, 1, 0, .72f)\n            "SOFT" -> soundPool.play(sfxSoft, amount * .58f, amount * .58f, 1, 0, .55f)\n            "BLAST" -> soundPool.play(sfxMetal, .78f, .78f, 3, 0, .62f)\n            else -> soundPool.play(sfxSoft, amount * .55f, amount * .55f, 0, 0, .82f)\n        }\n    }\n\n'''
if helper_anchor not in text: raise SystemExit('appendLog anchor missing')
text = text.replace(helper_anchor, helper + helper_anchor, 1)
# Release SoundPool with the renderer.
text = text.replace('        handler.removeCallbacks(statsPoll)\n', '        handler.removeCallbacks(statsPoll)\n        if (::soundPool.isInitialized) soundPool.release()\n', 1)
write(kt, text)

print('Vessel Playground v1.0 source patch applied')
