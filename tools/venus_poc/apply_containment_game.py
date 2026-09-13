#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]

def read(p): return (ROOT / p).read_text()
def write(p, s): (ROOT / p).write_text(s)
def rep(p, old, new, count=1):
    s = read(p)
    if old not in s:
        raise SystemExit(f"missing marker in {p}: {old[:100]!r}")
    write(p, s.replace(old, new, count))
def sub(p, pat, repl, count=1):
    s = read(p)
    out, n = re.subn(pat, repl, s, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f"regex mismatch {p}: wanted {count}, got {n}: {pat[:100]}")
    write(p, out)

p1 = 'app/src/main/cpp/native_vulkan_studio_v7_part1.inc'
p4 = 'app/src/main/cpp/native_vulkan_studio_v7_part4.inc'
p5 = 'app/src/main/cpp/native_vulkan_studio_v7_part5.inc'
vert = 'app/src/main/cpp/shaders/native_studio_v7.vert'
frag = 'app/src/main/cpp/shaders/native_studio_v7.frag'
rt = 'app/src/main/cpp/shaders/native_studio_v7_rt.frag'

# Native game state + first-person camera.
rep(p1, '// Vessel Vulkan Studio v10.1', '// Vessel: Blacksite — containment breach vertical slice')
rep(p1, 'constexpr float kV7RoomHalf=6.82f;', 'constexpr float kV7RoomHalf=18.0f;')
rep(p1, 'constexpr uint32_t kV7ArchBoxes=18u;', 'constexpr uint32_t kV7ArchBoxes=28u;')
rep(p1, 'constexpr uint32_t kV7Leaves=96u;', 'constexpr uint32_t kV7Leaves=0u;')
rep(p1, 'constexpr uint32_t kV7StaticInstances=20u;', 'constexpr uint32_t kV7StaticInstances=2u+kV7ArchBoxes;')
rep(p1,
    'struct PushV7 {float yaw,pitch,aspect,cameraDistance;float preRotation,quality,rtEnabled,time;float wetness,exposure,heroMaterial,rtBudget;};',
    'struct PushV7 {float yaw,pitch,aspect,cameraDistance;float preRotation,quality,rtEnabled,time;float wetness,exposure,heroMaterial,rtBudget;float camX,camY,camZ,gameState;};')
rep(p1,
    'cameraDistance_=7.8f;pitch_=0.04f;yaw_=-0.42f;',
    'cameraDistance_=1.0f;pitch_=0.0f;yaw_=0.0f;camX_=0.0f;camY_=.67f;camZ_=7.35f;')
rep(p1, 'logV7("Studio v10.1 · cleaned house showcase + stable reconstructed path lighting");', 'logV7("BLACKSITE · first-person containment breach");')
rep(p1, 'logV7("scene · real alpine HDRI through rainy windows · no slime · no TV · side-wall fire sconces");', 'logV7("scene · subterranean lab · breaker room · generator · storm exit");')
rep(p1, 'void applyRenderScale(){float s=pathTracing_.load()?.42f:.80f;', 'void applyRenderScale(){float s=pathTracing_.load()?.58f:.82f;')
rep(p1, 'logV7(v?"PATH QUALITY -> ON · 0.42x sparse reconstructed indirect lighting for 120 Hz target":"PATH QUALITY -> OFF · 0.80x stable hybrid restored");', 'logV7(v?"PATH TRACING -> ON · temporal low-spp cinematic mode":"PATH TRACING -> OFF · 0.82x hybrid restored");')

insert_methods = r'''
    void lookFp(float dx,float dy){
        float y=yaw_.load()+dx*.0027f;float p=std::clamp(pitch_.load()-dy*.00235f,-1.22f,1.22f);yaw_=y;pitch_=p;
    }
    void gameEvent(const std::string&s){std::lock_guard<std::mutex>l(eventMutex_);gameEvents_.push_back(s);if(gameEvents_.size()>48)gameEvents_.erase(gameEvents_.begin(),gameEvents_.begin()+16);}
    std::string drainEvents(){std::lock_guard<std::mutex>l(eventMutex_);std::string out;for(auto&s:gameEvents_){if(!out.empty())out+='\n';out+=s;}gameEvents_.clear();return out;}
    bool fpBlocked(float x,float z)const{
        int st=gameState_.load();
        if(z>8.25f||z<-17.6f||x<-6.25f||x>6.25f)return true;
        // Main corridor is narrow except at the breaker room and generator bay.
        bool breaker=(z>-1.0f&&z<2.7f), generator=(z<-7.4f&&z>-14.4f), exterior=(z<-14.2f);
        if(!breaker&&!generator&&!exterior&&std::abs(x)>2.72f)return true;
        if(breaker&&x>6.0f)return true;
        if(generator&&std::abs(x)>5.45f)return true;
        if(st<1&&z<-3.15f)return true;
        if(st<2&&z<-14.0f)return true;
        return false;
    }
    void moveFp(float forward,float strafe,float dt){
        dt=std::clamp(dt,0.0f,.04f);float yaw=yaw_.load();float sx=std::sin(yaw),cz=std::cos(yaw);float speed=3.05f;float dx=(sx*forward+cz*strafe)*speed*dt,dz=(-cz*forward+sx*strafe)*speed*dt;
        float x=camX_.load(),z=camZ_.load();float nx=x+dx,nz=z+dz;
        if(!fpBlocked(nx,z))x=nx;if(!fpBlocked(x,nz))z=nz;camX_=x;camZ_=z;
        if(gameState_.load()==2&&z<-15.35f){gameState_=3;gameEvent("SUB:Cold rain hits your face. The emergency lift is ahead. You made it out.");gameEvent("SFX:POWER");}
    }
    void interactFp(){
        float x=camX_.load(),z=camZ_.load();int st=gameState_.load();
        auto dist=[&](float px,float pz){float dx=x-px,dz=z-pz;return std::sqrt(dx*dx+dz*dz);};
        if(st==0&&dist(4.72f,.92f)<2.15f){gameState_=1;gameEvent("SFX:BREAKER");gameEvent("SUB:Emergency bus restored. The containment corridor unlocked.");logV7("GAME · breaker restored · blast door A opening");return;}
        if(st==1&&dist(.20f,-11.05f)<2.35f){gameState_=2;gameEvent("SFX:POWER");gameEvent("SFX:ALARM");gameEvent("SUB:Generator synchronized. Exterior blast door released. Move.");logV7("GAME · generator online · exterior door opening");return;}
        gameEvent("SUB:Nothing here responds.");
    }
'''
rep(p1, '\nprivate:\n', '\n'+insert_methods+'\nprivate:\n', 1)

# New authored collision / RT architecture. Door blocks 20/21 move with game state.
new_arch_cpp = r'''    void archInfoV7(int b,Vec3&c,Vec3&s,bool&light){
        light=false;c={};s={1,1,1};int st=gameState_.load();
        if(b==0){c={-2.92f,1.20f,4.9f};s={.14f,2.25f,3.4f};}
        else if(b==1){c={ 2.92f,1.20f,5.0f};s={.14f,2.25f,3.3f};}
        else if(b==2){c={-2.92f,1.20f,-3.9f};s={.14f,2.25f,5.0f};}
        else if(b==3){c={ 2.92f,1.20f,-5.2f};s={.14f,2.25f,3.7f};}
        else if(b==4){c={ 2.92f,1.20f, 3.15f};s={.14f,2.25f,.65f};}
        else if(b==5){c={ 2.92f,1.20f,-1.55f};s={.14f,2.25f,1.15f};}
        else if(b==6){c={ 6.10f,1.20f,.85f};s={.14f,2.25f,2.05f};}
        else if(b==7){c={ 4.45f,1.20f, 2.90f};s={1.80f,2.25f,.14f};}
        else if(b==8){c={ 4.45f,1.20f,-1.20f};s={1.80f,2.25f,.14f};}
        else if(b==9){c={-5.65f,1.20f,-10.7f};s={.14f,2.25f,3.6f};}
        else if(b==10){c={5.65f,1.20f,-10.7f};s={.14f,2.25f,3.6f};}
        else if(b==11){c={-4.25f,1.20f,-7.20f};s={1.55f,2.25f,.14f};}
        else if(b==12){c={ 4.25f,1.20f,-7.20f};s={1.55f,2.25f,.14f};}
        else if(b==13){c={-4.25f,1.20f,-14.25f};s={1.55f,2.25f,.14f};}
        else if(b==14){c={ 4.25f,1.20f,-14.25f};s={1.55f,2.25f,.14f};}
        else if(b==15){c={0,3.42f,-2.9f};s={6.10f,.10f,11.4f};}
        else if(b==16){c={0,-.95f,-3.4f};s={6.10f,.06f,12.0f};}
        else if(b==17){c={4.78f,.05f,.92f};s={.48f,1.05f,.20f};light=true;}
        else if(b==18){c={.20f,-.20f,-11.05f};s={1.20f,.82f,.90f};light=true;}
        else if(b==19){c={0,1.00f,8.35f};s={3.05f,2.05f,.16f};}
        else if(b==20){c={0,(st>=1?4.8f:1.05f),-3.18f};s={2.72f,2.05f,.13f};}
        else if(b==21){c={0,(st>=2?4.8f:1.05f),-14.15f};s={2.72f,2.05f,.16f};}
        else if(b==22){c={-2.60f,1.55f,-16.0f};s={.16f,1.55f,1.7f};}
        else if(b==23){c={ 2.60f,1.55f,-16.0f};s={.16f,1.55f,1.7f};}
        else if(b==24){c={0,3.05f,-16.0f};s={2.75f,.12f,1.7f};}
        else if(b==25){c={-1.45f,.10f,-9.30f};s={.55f,.55f,.55f};}
        else if(b==26){c={ 1.55f,-.05f,-9.65f};s={.65f,.40f,.65f};}
        else {c={0,1.65f,-16.8f};s={2.45f,1.20f,.025f};light=true;}
    }
'''
sub(p4, r'    void archInfoV7\(int b,Vec3&c,Vec3&s,bool&light\)\{.*?\n    \}\n    void putInstanceV7', new_arch_cpp+'    void putInstanceV7', 1)
rep(p4, 'for(uint32_t i=0;i<18;++i)', 'for(uint32_t i=0;i<kV7ArchBoxes;++i)')
rep(p4, 'logV7("RT scene · cleaned house shell + furniture + side sconces + synchronized body bounds");', 'logV7("RT scene · Blacksite lab shell + dynamic blast doors + synchronized physics");')
rep(p4,
    'PushV7 p{yaw_.load(),pitch_.load(),float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load())),cameraDistance_.load(),rotCode(preTransform_),float(quality_.load()),useRt?1.f:0.f,time,wetness_.load(),exposure_.load(),float(heroMaterial_.load()),rtBudgetV7()};',
    'PushV7 p{yaw_.load(),pitch_.load(),float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load())),cameraDistance_.load(),rotCode(preTransform_),float(quality_.load()),useRt?1.f:0.f,time,wetness_.load(),exposure_.load(),float(heroMaterial_.load()),rtBudgetV7(),camX_.load(),camY_.load(),camZ_.load(),float(gameState_.load())};')

# Renderer status + game members + JNI.
rep(p5, 'setStatus("Vulkan Studio v10.0 · loading final rain-house renderer…");', 'setStatus("BLACKSITE · loading containment facility…");')
rep(p5, 'logV7("READY · 240 Hz physics · stable hybrid HWRT · sparse reconstructed path lighting · Alpine rain windows");', 'logV7("READY · first-person Blacksite · 240 Hz physics · hybrid HWRT · cinematic path mode");')
rep(p5, 'logV7("final scene · steel + gummy + light orb + rubber · slime removed");', 'logV7("GAME · restore breaker · restart generator · escape into storm");')
rep(p5, 'o<<"Vulkan Studio v10.0 · "<<gpuName_', 'o<<"BLACKSITE · "<<gpuName_')
rep(p5, '<<(stress_.load()?"SAFE STRESS":"RAIN HOUSE")', '<<"CONTAINMENT"')
rep(p5, 'std::atomic<bool>deviceLost_{false};uint32_t frameSerial_=0;int slowFrames_=0;', 'std::atomic<bool>deviceLost_{false};uint32_t frameSerial_=0;int slowFrames_=0;\n    std::atomic<float>camX_{0.0f},camY_{.67f},camZ_{7.35f};std::atomic<int>gameState_{0};\n    std::mutex eventMutex_;std::vector<std::string>gameEvents_;')
append_jni = r'''
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeLook(JNIEnv*,jobject,jlong h,jfloat dx,jfloat dy){if(auto*r=ptrV7(h))r->lookFp(dx,dy);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeMove(JNIEnv*,jobject,jlong h,jfloat f,jfloat s,jfloat dt){if(auto*r=ptrV7(h))r->moveFp(f,s,dt);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeInteract(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->interactFp();}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeEvents(JNIEnv*e,jobject,jlong h){std::string s=ptrV7(h)?ptrV7(h)->drainEvents():"";return e->NewStringUTF(s.c_str());}
'''
write(p5, read(p5) + '\n' + append_jni)

# Shared shader push constants.
for p in (vert, frag, rt):
    rep(p,
        'layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;} pc;',
        'layout(push_constant) uniform Push {float yaw;float pitch;float aspect;float cameraDistance;float preRotation;float quality;float rtEnabled;float time;float wetness;float exposure;float heroMaterial;float rtBudget;float camX;float camY;float camZ;float gameState;} pc;')

# First-person authored facility vertex scene.
rep(vert, 'const int ARCH_BOXES=18; const int BOX_VERTS=36; const int LEAVES=96;', 'const int ARCH_BOXES=28; const int BOX_VERTS=36; const int LEAVES=0;')
rep(vert, 'const int STATIC_INSTANCES=20;', 'const int STATIC_INSTANCES=30;') if 'const int STATIC_INSTANCES=20;' in read(vert) else None
rep(vert, 'uv=g*3.20; vec2 xz=(g*2.0-1.0)*7.05;', 'uv=g*8.0; vec2 xz=(g*2.0-1.0)*20.0;')
new_arch_glsl = r'''void archInfo(int block,out vec3 c,out vec3 s,out int mat){
    mat=5;c=vec3(0);s=vec3(1);int st=int(pc.gameState+.5);
    if(block==0){c=vec3(-2.92,1.20,4.9);s=vec3(.14,2.25,3.4);mat=12;}
    else if(block==1){c=vec3(2.92,1.20,5.0);s=vec3(.14,2.25,3.3);mat=12;}
    else if(block==2){c=vec3(-2.92,1.20,-3.9);s=vec3(.14,2.25,5.0);mat=12;}
    else if(block==3){c=vec3(2.92,1.20,-5.2);s=vec3(.14,2.25,3.7);mat=12;}
    else if(block==4){c=vec3(2.92,1.20,3.15);s=vec3(.14,2.25,.65);mat=12;}
    else if(block==5){c=vec3(2.92,1.20,-1.55);s=vec3(.14,2.25,1.15);mat=12;}
    else if(block==6){c=vec3(6.10,1.20,.85);s=vec3(.14,2.25,2.05);mat=12;}
    else if(block==7){c=vec3(4.45,1.20,2.90);s=vec3(1.80,2.25,.14);mat=5;}
    else if(block==8){c=vec3(4.45,1.20,-1.20);s=vec3(1.80,2.25,.14);mat=5;}
    else if(block==9){c=vec3(-5.65,1.20,-10.7);s=vec3(.14,2.25,3.6);mat=12;}
    else if(block==10){c=vec3(5.65,1.20,-10.7);s=vec3(.14,2.25,3.6);mat=12;}
    else if(block==11){c=vec3(-4.25,1.20,-7.20);s=vec3(1.55,2.25,.14);mat=5;}
    else if(block==12){c=vec3(4.25,1.20,-7.20);s=vec3(1.55,2.25,.14);mat=5;}
    else if(block==13){c=vec3(-4.25,1.20,-14.25);s=vec3(1.55,2.25,.14);mat=5;}
    else if(block==14){c=vec3(4.25,1.20,-14.25);s=vec3(1.55,2.25,.14);mat=5;}
    else if(block==15){c=vec3(0,3.42,-2.9);s=vec3(6.10,.10,11.4);mat=7;}
    else if(block==16){c=vec3(0,-.95,-3.4);s=vec3(6.10,.06,12.0);mat=1;}
    else if(block==17){c=vec3(4.78,.05,.92);s=vec3(.48,1.05,.20);mat=22;}
    else if(block==18){c=vec3(.20,-.20,-11.05);s=vec3(1.20,.82,.90);mat=6;}
    else if(block==19){c=vec3(0,1.00,8.35);s=vec3(3.05,2.05,.16);mat=5;}
    else if(block==20){c=vec3(0,st>=1?4.8:1.05,-3.18);s=vec3(2.72,2.05,.13);mat=2;}
    else if(block==21){c=vec3(0,st>=2?4.8:1.05,-14.15);s=vec3(2.72,2.05,.16);mat=2;}
    else if(block==22){c=vec3(-2.60,1.55,-16.0);s=vec3(.16,1.55,1.7);mat=5;}
    else if(block==23){c=vec3(2.60,1.55,-16.0);s=vec3(.16,1.55,1.7);mat=5;}
    else if(block==24){c=vec3(0,3.05,-16.0);s=vec3(2.75,.12,1.7);mat=7;}
    else if(block==25){c=vec3(-1.45,.10,-9.30);s=vec3(.55,.55,.55);mat=2;}
    else if(block==26){c=vec3(1.55,-.05,-9.65);s=vec3(.65,.40,.65);mat=7;}
    else{c=vec3(0,1.65,-16.8);s=vec3(2.45,1.20,.025);mat=16;}
}'''
sub(vert, r'void archInfo\(int block,out vec3 c,out vec3 s,out int mat\)\{.*?\n\}', new_arch_glsl, 1)
sub(vert, r'if\(block==9\|\|block==10\)\{.*?\}return;', 'return;', 1)
# Replace orbit camera with true FPS camera.
sub(vert,
    r'float cp=cos\(pc\.pitch\);vec3 cam=pc\.cameraDistance\*vec3\(cp\*sin\(pc\.yaw\),sin\(pc\.pitch\),cp\*cos\(pc\.yaw\)\)\+vec3\(0,\.38,1\.70\),target=vec3\(0,-\.15,-\.15\);\n    vec3 fwd=normalize\(target-cam\),right=normalize\(cross\(fwd,vec3\(0,1,0\)\)\),up=normalize\(cross\(right,fwd\)\),rel=P-cam;',
    'float cp=cos(pc.pitch);vec3 cam=vec3(pc.camX,pc.camY,pc.camZ);vec3 fwd=normalize(vec3(cp*sin(pc.yaw),sin(pc.pitch),-cp*cos(pc.yaw))),right=normalize(cross(fwd,vec3(0,1,0))),up=normalize(cross(right,fwd)),rel=P-cam;', 1)

# RT fragment matches visible architecture and uses cool industrial lights instead of fire.
rep(rt, 'const int STATIC_INSTANCES=20;', 'const int STATIC_INSTANCES=30;')
sub(rt, r'const vec3 TORCH0=.*?const vec3 FIREPLACE=.*?;', 'const vec3 LAB0=vec3(0,2.85,4.4);\nconst vec3 LAB1=vec3(0,2.85,-2.0);\nconst vec3 LAB2=vec3(0,2.85,-10.6);', 1)
new_boxinfo = new_arch_glsl.replace('void archInfo(int block,out vec3 c,out vec3 s,out int mat)', 'void boxInfoRaw(int block,out vec3 c,out vec3 s,out int mat)') + '\nvoid boxInfo(int id,out vec3 c,out vec3 s,out int mat){boxInfoRaw(id-2,c,s,mat);}'
sub(rt, r'void boxInfo\(int id,out vec3 c,out vec3 s,out int mat\)\{.*?\}\nvec3 boxNormal', new_boxinfo+'\nvec3 boxNormal', 1)
rep(rt, 'huv=(hp.xz/14.1+.5)*3.20;', 'huv=(hp.xz/40.0+.5)*8.0;')
sub(rt,
    r'vec3 fireLighting\(Material m,vec3 N,vec3 V,vec3 P\)\{.*?\}',
    'vec3 fireLighting(Material m,vec3 N,vec3 V,vec3 P){float emergency=pc.gameState<1.0?0.42:1.0;vec3 cool=vec3(.62,.78,1.0);vec3 warm=vec3(1.0,.16,.055);vec3 c=pointLight(m,N,V,P,LAB0,cool,12.0*emergency)+pointLight(m,N,V,P,LAB1,cool,10.0*emergency)+pointLight(m,N,V,P,LAB2,cool,13.0*max(emergency,.72));float pulse=.55+.45*sin(pc.time*5.4);if(pc.gameState>=2.0)c+=pointLight(m,N,V,P,vec3(0,2.4,-13.2),warm,6.0*pulse);return c;}', 1)
# More restrained wet floor; interior should not look dipped in oil.
rep(rt, 'a.roughness=mix(dampR,.070,water);a.clearcoat=water*.62;', 'a.roughness=mix(dampR,.18,water);a.clearcoat=water*.30;')
rep(rt, 'm.base*=mix(1.0,.42,w);m.roughness=mix(m.roughness,.055,w*.94);m.clearcoat=max(m.clearcoat,w*.96);', 'm.base*=mix(1.0,.72,w);m.roughness=mix(m.roughness,.20,w*.70);m.clearcoat=max(m.clearcoat,w*.34);')

# Raster fallback needs static count only via shared push; reduce wet look there too when marker exists.
if 'a.roughness=mix(dampR,.070,water);a.clearcoat=water*.62;' in read(frag):
    rep(frag, 'a.roughness=mix(dampR,.070,water);a.clearcoat=water*.62;', 'a.roughness=mix(dampR,.18,water);a.clearcoat=water*.30;')

print('containment game patch applied')
