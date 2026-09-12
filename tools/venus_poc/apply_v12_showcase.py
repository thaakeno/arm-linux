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
        raise SystemExit(f"v12 marker missing in {path}: {old[:140]!r}")
    write(path, text.replace(old, new))

def sub(path: str, pattern: str, repl: str, count: int = 1) -> None:
    text = read(path)
    new, n = re.subn(pattern, repl, text, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f"v12 regex expected {count}, got {n} in {path}: {pattern[:100]!r}")
    write(path, new)

# -----------------------------------------------------------------------------
# Native renderer: v12 branding, full-resolution-ish RT GI, configurable bodies.
# -----------------------------------------------------------------------------
p1 = "app/src/main/cpp/native_vulkan_studio_v7_part1.inc"
rep(p1, "// Vessel Vulkan Studio v11.0", "// Vessel Vulkan Studio v12.0")
rep(p1,
    "// Final Adreno-first showcase: deterministic 120 Hz hybrid HWRT plus sparse reconstructed path-quality lighting.",
    "// Unreal-style playground showcase: deterministic hybrid HWRT plus high-resolution sparse RT GI quality mode.")
rep(p1, "constexpr uint32_t kV7Leaves=96u;", "constexpr uint32_t kV7Leaves=0u;")
rep(p1,
    "logV7(\"Studio v11.0 · final alpine rain-house showcase + deterministic reconstructed path lighting\");",
    "logV7(\"Studio v12.0 · Unreal-style physics playground + deterministic RT GI\");")
rep(p1,
    "logV7(\"safety · Adreno tile-aware 0.76x hybrid / 0.34x path scale + sparse ray queries\");",
    "logV7(\"safety · Adreno tile-aware 0.76x hybrid / 0.70x RT GI scale + sparse stable world-space rays\");")
rep(p1,
    "logV7(\"scene · alpine landscape through rainy glass · no slime · no TV · CC0 flipbook fire sconces\");",
    "logV7(\"scene · clean Unreal-style playground · platforms + stairs + neutral concrete · torches removed\");")
rep(p1,
    "void applyRenderScale(){float s=pathTracing_.load()?.34f:.76f;",
    "void applyRenderScale(){float s=pathTracing_.load()?.70f:.76f;")
rep(p1,
    "PATH QUALITY -> ON · 0.34x sparse reconstructed indirect lighting for 120 Hz target",
    "PATH QUALITY -> ON · 0.70x high-resolution sparse RT GI")
rep(p1,
    "PATH QUALITY -> OFF · 0.76x stable hybrid restored",
    "PATH QUALITY -> OFF · 0.76x stable hybrid restored")
sub(p1,
    r"void setHeroMaterial\(int m\)\{.*?\}\n    void setGummyBounce",
    '''void setHeroMaterial(int m){
        int mode=pathTracing_.load()?10:0;int v=std::clamp(m,0,3);heroMaterial_=mode+v;
        const char*names[]={"CONCRETE","OBSIDIAN","BASALT","GRANITE"};
        logV7(std::string("hero -> ")+names[v]);
    }
    void setBodyProperty(int body,int property,float value){
        std::lock_guard<std::mutex>lock(bodyMutex_);if(body<0||body>=int(kV7QualityBodies))return;Body&b=bodies_[body];
        if(property==0)b.restitution=std::clamp(value,0.0f,.98f);
        else if(property==1)b.friction=std::clamp(value,.05f,1.20f);
        else if(property==2)b.mass=std::clamp(value,.12f,80.0f);
        logV7Unlocked("body tune -> "+std::to_string(body)+" / "+std::to_string(property)+" = "+std::to_string(value));
    }
    void setGummyBounce''')

# -----------------------------------------------------------------------------
# Physics: reduce energy injection in mixed rigid/soft impacts and improve stacking.
# The old solver used max(restitution), which made a dense steel ball inherit the
# gummy/rubber bounce coefficient. Use a mixed coefficient + softer positional
# correction and a little impact damping for deformable contacts.
# -----------------------------------------------------------------------------
p3 = "app/src/main/cpp/native_vulkan_studio_v7_part3.inc"
rep(p3,
    "float e=std::abs(vn)<threshold?0.0f:std::max(a.restitution,b.restitution),jn=-(1+e)*vn/sum;",
    "float e=std::abs(vn)<threshold?0.0f:std::sqrt(std::max(0.0f,a.restitution*b.restitution));if(a.gummy||b.gummy)e*=.78f;float jn=-(1+e)*vn/sum;")
rep(p3,
    "Vec3 corr=n*(std::max(0.f,pen-.0007f)*.86f/sum);",
    "Vec3 corr=n*(std::min(.18f,std::max(0.f,pen-.0012f))*.68f/sum);")
rep(p3,
    "for(int it=0;it<10;++it)",
    "for(int it=0;it<12;++it)")
# Flat playground floor on CPU side and artifact-free hero BLAS geometry.
sub(p3,
    r"float floorSurfaceYV7\(float x,float z\)const\{.*?\n    \}",
    '''float floorSurfaceYV7(float x,float z)const{
        (void)x;(void)z;return kFloorY;
    }''')
sub(p3,
    r"std::vector<Vec3>heroTrianglesV7\(\)\{.*?return v;\}",
    '''std::vector<Vec3>heroTrianglesV7(){std::vector<Vec3>v;v.reserve(kV7HeroVerts);auto point=[&](int face,float u,float vv){return heroFacePointV7(face,u,vv,0.0f);};for(int f=0;f<6;++f)for(uint32_t y=0;y<kV7HeroGrid;++y)for(uint32_t x=0;x<kV7HeroGrid;++x){float u0=float(x)/kV7HeroGrid,u1=float(x+1)/kV7HeroGrid,v0=float(y)/kV7HeroGrid,v1=float(y+1)/kV7HeroGrid;Vec3 a=point(f,u0,v0),b=point(f,u1,v0),c=point(f,u1,v1),d=point(f,u0,v1);v.insert(v.end(),{a,b,c,a,c,d});}return v;}''')
sub(p3,
    r"std::vector<Vec3>floorTrianglesV7\(\)\{.*?return v;\}",
    '''std::vector<Vec3>floorTrianglesV7(){std::vector<Vec3>v;v.reserve(kV7FloorVerts);auto point=[&](float gx,float gy){return Vec3{(gx*2.0f-1.0f)*7.05f,kFloorY,(gy*2.0f-1.0f)*7.05f};};for(uint32_t y=0;y<kV7FloorGrid;++y)for(uint32_t x=0;x<kV7FloorGrid;++x){float x0=float(x)/kV7FloorGrid,x1=float(x+1)/kV7FloorGrid,y0=float(y)/kV7FloorGrid,y1=float(y+1)/kV7FloorGrid;Vec3 a=point(x0,y0),b=point(x1,y0),c=point(x1,y1),d=point(x0,y1);v.insert(v.end(),{a,b,c,a,c,d});}return v;}''')

# -----------------------------------------------------------------------------
# CPU RT/physics architecture: same 18 playground boxes as the vertex/RT shader.
# -----------------------------------------------------------------------------
p4 = "app/src/main/cpp/native_vulkan_studio_v7_part4.inc"
sub(p4,
    r"void archInfoV7\(int b,Vec3&c,Vec3&s,bool&light\)\{.*?\n    \}",
    '''void archInfoV7(int b,Vec3&c,Vec3&s,bool&light){
        light=false;c={};s={1,1,1};
        if(b==0){c={0,.35f,-6.72f};s={6.70f,1.36f,.12f};}
        else if(b==1){c={-6.72f,.15f,0};s={.12f,1.18f,6.70f};}
        else if(b==2){c={6.72f,.15f,0};s={.12f,1.18f,6.70f};}
        else if(b==3){c={-3.75f,-.66f,-3.05f};s={1.45f,.34f,1.45f};}
        else if(b==4){c={3.45f,-.50f,-2.65f};s={1.55f,.50f,1.35f};}
        else if(b==5){c={.15f,-.30f,3.45f};s={2.20f,.70f,1.28f};}
        else if(b==6){c={-4.65f,-.88f,1.60f};s={1.10f,.12f,.70f};}
        else if(b==7){c={-4.65f,-.69f,2.32f};s={1.10f,.19f,.70f};}
        else if(b==8){c={-4.65f,-.46f,3.04f};s={1.10f,.23f,.70f};}
        else if(b==9){c={-4.65f,-.18f,3.76f};s={1.10f,.28f,.70f};}
        else if(b==10){c={-4.65f,.15f,4.48f};s={1.10f,.33f,.70f};}
        else if(b==11){c={4.75f,-.55f,3.70f};s={.70f,.45f,.70f};}
        else if(b==12){c={4.75f,.35f,3.70f};s={.52f,.45f,.52f};}
        else if(b==13){c={2.65f,-.72f,1.25f};s={.70f,.28f,.70f};}
        else if(b==14){c={2.65f,-.14f,1.25f};s={.52f,.30f,.52f};}
        else if(b==15){c={.00f,-.77f,-4.55f};s={1.55f,.23f,.72f};}
        else if(b==16){c={.00f,-.32f,-4.55f};s={1.10f,.22f,.58f};}
        else {c={.00f,.10f,-4.55f};s={.68f,.20f,.46f};}
    }''')
rep(p4,
    "logV7(\"RT scene · cleaned house shell + furniture + side sconces + synchronized body bounds\");",
    "logV7(\"RT scene · Unreal-style playground platforms + stairs + synchronized body bounds\");")
rep(p4,
    "if(pathTracing_.load())return .18f;",
    "if(pathTracing_.load())return .10f;")

# -----------------------------------------------------------------------------
# Vertex shader: flat neutral floor, clean rounded hero, zero furniture/leaves,
# and a simple Unreal-template-like block playground.
# -----------------------------------------------------------------------------
vert = "app/src/main/cpp/shaders/native_studio_v7.vert"
rep(vert, "// Vulkan Studio v11.0 final alpine rain-house showcase.", "// Vulkan Studio v12.0 Unreal-style physics playground.")
rep(vert, "const int ARCH_BOXES=18; const int BOX_VERTS=36; const int LEAVES=96;", "const int ARCH_BOXES=18; const int BOX_VERTS=36; const int LEAVES=0;")
sub(vert,
    r"void emitHero\(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B\)\{.*?\n\}",
    '''void emitHero(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int face=local/HERO_FACE_VERTS,fLocal=local-face*HERO_FACE_VERTS,cell=fLocal/6,corner=fLocal-cell*6;
    int x=cell%HERO_GRID,y=cell/HERO_GRID;vec2 tc=triCorner(corner);uv=(vec2(x,y)+tc)/float(HERO_GRID);vec2 q=uv*2.0-1.0;
    vec3 halfExt=vec3(1.22,1.06,1.22);float bevel=.075;vec3 raw=boxPos(face,q)*halfExt,inner=halfExt-vec3(bevel);vec3 c=clamp(raw,-inner,inner),d=raw-c;N=length(d)>1e-5?normalize(d):boxN(face);
    P=c+N*bevel;int variant=int(mod(pc.heroMaterial,10.0)+.5);M=variant==0?0:6;O=1;basisFromNormal(N,T,B);
}''')
sub(vert,
    r"float floorH\(vec2 uv\)\{.*?\}\nvoid emitFloor\(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B\)\{.*?\n\}",
    '''float floorH(vec2 uv){return 0.0;}
void emitFloor(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){
    int cell=local/6,corner=local-cell*6,x=cell%FLOOR_GRID,y=cell/FLOOR_GRID;vec2 tc=triCorner(corner);vec2 g=(vec2(x,y)+tc)/float(FLOOR_GRID);uv=g*3.20;vec2 xz=(g*2.0-1.0)*7.05;P=vec3(xz.x,FLOOR_Y,xz.y);N=vec3(0,1,0);T=vec3(1,0,0);B=vec3(0,0,1);M=1;O=0;
}''')
sub(vert,
    r"void archInfo\(int block,out vec3 c,out vec3 s,out int mat\)\{.*?\n\}",
    '''void archInfo(int block,out vec3 c,out vec3 s,out int mat){
    mat=12;c=vec3(0);s=vec3(1);
    if(block==0){c=vec3(0,.35,-6.72);s=vec3(6.70,1.36,.12);}else if(block==1){c=vec3(-6.72,.15,0);s=vec3(.12,1.18,6.70);}else if(block==2){c=vec3(6.72,.15,0);s=vec3(.12,1.18,6.70);}
    else if(block==3){c=vec3(-3.75,-.66,-3.05);s=vec3(1.45,.34,1.45);}else if(block==4){c=vec3(3.45,-.50,-2.65);s=vec3(1.55,.50,1.35);}else if(block==5){c=vec3(.15,-.30,3.45);s=vec3(2.20,.70,1.28);}
    else if(block==6){c=vec3(-4.65,-.88,1.60);s=vec3(1.10,.12,.70);}else if(block==7){c=vec3(-4.65,-.69,2.32);s=vec3(1.10,.19,.70);}else if(block==8){c=vec3(-4.65,-.46,3.04);s=vec3(1.10,.23,.70);}else if(block==9){c=vec3(-4.65,-.18,3.76);s=vec3(1.10,.28,.70);}else if(block==10){c=vec3(-4.65,.15,4.48);s=vec3(1.10,.33,.70);}
    else if(block==11){c=vec3(4.75,-.55,3.70);s=vec3(.70,.45,.70);}else if(block==12){c=vec3(4.75,.35,3.70);s=vec3(.52,.45,.52);}else if(block==13){c=vec3(2.65,-.72,1.25);s=vec3(.70,.28,.70);}else if(block==14){c=vec3(2.65,-.14,1.25);s=vec3(.52,.30,.52);}else if(block==15){c=vec3(0,-.77,-4.55);s=vec3(1.55,.23,.72);}else if(block==16){c=vec3(0,-.32,-4.55);s=vec3(1.10,.22,.58);}else{c=vec3(0,.10,-4.55);s=vec3(.68,.20,.46);}
}''')
# Remove old torch-specific vertex animation.
rep(vert,
    "if(block==9||block==10){float fy=clamp((P.y-(c.y-s.y))/max(2.0*s.y,.001),0.0,1.0);float sway=sin(pc.time*7.3+float(block)*1.3+fy*5.0)*.060*fy;P.z+=sway;P.y+=sin(pc.time*10.0+fy*6.0)*.016*fy;N=normalize(N+vec3(0,.08*sin(pc.time*6.2+fy),sway));basisFromNormal(N,T,B);}",
    "")

# -----------------------------------------------------------------------------
# RT fragment shader: neutral playground floor, restrained wet stone, variants,
# no fire lights, world-stable sparse GI, and no 0.34x quality destruction.
# -----------------------------------------------------------------------------
frag = "app/src/main/cpp/shaders/native_studio_v7_rt.frag"
rep(frag,
    "// Vulkan Studio v11.0: deterministic hybrid lighting, sparse reconstructed path quality, real Alpine rain windows and CC0 flipbook fire.",
    "// Vulkan Studio v12.0: Unreal-style playground, stable hybrid HWRT and high-resolution sparse RT GI.")
# Restrained hero wet response.
rep(frag,
    "m.base*=mix(1.0,.34,w);m.roughness=mix(m.roughness,.035,w*.96);m.clearcoat=max(m.clearcoat,w);",
    "m.base*=mix(1.0,.72,w);m.roughness=mix(m.roughness,.16,w*.62);m.clearcoat=max(m.clearcoat,w*.46);")
# Neutral floor material replacing wet cobbles.
sub(frag,
    r"else if\(m==1\)\{.*?\}\n    else if\(m==2\)",
    '''else if(m==1){vec2 t=fract(uv*.46);vec4 arm=texture(concreteArm,t);vec3 tex=texture(concreteAlbedo,t).rgb;float micro=.94+.06*noise2(t*32.0);a.base=mix(vec3(.34,.35,.36),tex*vec3(.62,.63,.64),.28)*micro;a.roughness=clamp(.72+arm.g*.16,.68,.92);a.clearcoat=0.0;a.ao=.96;water=0.0;}
    else if(m==2)''')
# Stone variants in m==6.
sub(frag,
    r"else if\(m==6\)\{.*?\}",
    '''else if(m==6){vec2 t=fract(uv*1.12);vec4 arm=texture(darkArm,t);vec3 tex=texture(darkAlbedo,t).rgb;int variant=int(mod(pc.heroMaterial,10.0)+.5);if(variant==1){a.base=tex*vec3(.17,.18,.21);a.roughness=clamp(.38+arm.g*.24,.34,.66);a.clearcoat=.08;}else if(variant==2){a.base=tex*vec3(.34,.33,.31);a.roughness=clamp(.58+arm.g*.22,.54,.82);a.clearcoat=.02;}else{a.base=mix(tex*vec3(.44,.42,.40),vec3(.38,.34,.31),.28);a.roughness=clamp(.48+arm.g*.24,.44,.76);a.clearcoat=.03;}a.ao=arm.r;}''')
# Floor normals become subtle concrete normals.
rep(frag,
    "if(m==1)return tangentNormal(floorNormal,fract(uv),N,T,B,mix(1.52,.72,water)*q);",
    "if(m==1)return tangentNormal(concreteNormal,fract(uv*.46),N,T,B,.22*q);")
# Remove fire contribution globally.
sub(frag,
    r"vec3 fireLighting\(Material m,vec3 N,vec3 V,vec3 P\)\{.*?\}",
    "vec3 fireLighting(Material m,vec3 N,vec3 V,vec3 P){return vec3(0);}")
# Flat floor hit normal for RT.
sub(frag,
    r"vec3 floorHitNormal\(vec2 uv\)\{.*?\}",
    "vec3 floorHitNormal(vec2 uv){return vec3(0,1,0);}")
# Match RT box scene to playground.
sub(frag,
    r"void boxInfo\(int id,out vec3 c,out vec3 s,out int mat\)\{.*?\}",
    '''void boxInfo(int id,out vec3 c,out vec3 s,out int mat){int b=id-2;mat=12;c=vec3(0);s=vec3(1);if(b==0){c=vec3(0,.35,-6.72);s=vec3(6.70,1.36,.12);}else if(b==1){c=vec3(-6.72,.15,0);s=vec3(.12,1.18,6.70);}else if(b==2){c=vec3(6.72,.15,0);s=vec3(.12,1.18,6.70);}else if(b==3){c=vec3(-3.75,-.66,-3.05);s=vec3(1.45,.34,1.45);}else if(b==4){c=vec3(3.45,-.50,-2.65);s=vec3(1.55,.50,1.35);}else if(b==5){c=vec3(.15,-.30,3.45);s=vec3(2.20,.70,1.28);}else if(b==6){c=vec3(-4.65,-.88,1.60);s=vec3(1.10,.12,.70);}else if(b==7){c=vec3(-4.65,-.69,2.32);s=vec3(1.10,.19,.70);}else if(b==8){c=vec3(-4.65,-.46,3.04);s=vec3(1.10,.23,.70);}else if(b==9){c=vec3(-4.65,-.18,3.76);s=vec3(1.10,.28,.70);}else if(b==10){c=vec3(-4.65,.15,4.48);s=vec3(1.10,.33,.70);}else if(b==11){c=vec3(4.75,-.55,3.70);s=vec3(.70,.45,.70);}else if(b==12){c=vec3(4.75,.35,3.70);s=vec3(.52,.45,.52);}else if(b==13){c=vec3(2.65,-.72,1.25);s=vec3(.70,.28,.70);}else if(b==14){c=vec3(2.65,-.14,1.25);s=vec3(.52,.30,.52);}else if(b==15){c=vec3(0,-.77,-4.55);s=vec3(1.55,.23,.72);}else if(b==16){c=vec3(0,-.32,-4.55);s=vec3(1.10,.22,.58);}else{c=vec3(0,.10,-4.55);s=vec3(.68,.20,.46);}}''')
# Correct hero material selection for 4 variants.
rep(frag,
    "hm=mod(pc.heroMaterial,10.0)<.5?0:6;",
    "hm=int(mod(pc.heroMaterial,10.0)+.5)==0?0:6;")
# Stable world-space path sample gate instead of screen-space pattern.
rep(frag,
    "float gate=hash21(floor(gl_FragCoord.xy*.5)+vec2(float(inObject)*.37,float(mat)*.23));",
    "float gate=hash21(floor(inWorldPos.xz*11.0)+vec2(float(inObject)*.37,float(mat)*.23));")
rep(frag,
    "if(localScore>.11&&gate<.12)localVis=shadowVisibility",
    "if(localScore>.11&&gate<.08)localVis=shadowVisibility")
rep(frag,
    "vec3 indirect=gate<.14?stablePathIndirect",
    "vec3 indirect=gate<.10?stablePathIndirect")
rep(frag,
    "vec3 color=direct+indirect*.22+gelTransmission(mat,m,N,V)*.28+haze;",
    "vec3 color=direct+indirect*.12+gelTransmission(mat,m,N,V)*.24+haze;")

# -----------------------------------------------------------------------------
# UI: v12, neutral charcoal instead of neon teal, playground wording, 4 stone
# variants, and per-object physics tuning through one generic JNI entry point.
# -----------------------------------------------------------------------------
ui = "app/src/main/java/com/example/dreamlinux/NativeCubeActivity.kt"
rep(ui, "Vulkan Studio v11.0\\nLoading final alpine rain-house showcase…", "Vulkan Studio v12.0\\nLoading Unreal-style physics playground…")
rep(ui, "VULKAN STUDIO  //  v11.0", "VULKAN STUDIO  //  v12.0")
rep(ui, "ALPINE RAIN HOUSE · 120 HZ · HWRT · PATH QUALITY", "PHYSICS PLAYGROUND · 120 HZ · HWRT · RT GI")
rep(ui,
    "Hybrid targets 120 Hz at 0.76x. Path Quality uses sparse reconstructed indirect lighting at 0.34x; 0/100 is completely flat/unlit.",
    "Hybrid uses 0.76x. RT GI keeps 0.70x internal resolution with sparse world-stable rays; 0/100 stays completely flat/unlit.")
# Neutral UI colors.
for a,b in {
    "0xFF277C6B":"0xFF4C525A", "0xFF2D7565":"0xFF434850", "0xFF2C403A":"0xFF34383E",
    "0xFF70CFB5":"0xFFB0B4BA", "0xFF327A69":"0xFF4A4F56", "0xFF64E9CB":"0xFFB8BDC4",
    "0xF018806E":"0xF03A3E44", "0xFF2D413B":"0xFF3B4047", "0xEA09100E":"0xEA15171A",
    "0xF4070C0A":"0xF4141619", "0xE6070C0A":"0xE6141619", "0xC6101714":"0xC61C1F23"
}.items():
    text=read(ui);write(ui,text.replace(a,b))
# Stone button cycles 4 variants.
sub(ui,
    r"heroButton = makeButton\(\"VOLCANIC\", true\) \{ b ->.*?\n            \}",
    '''heroButton = makeButton("OBSIDIAN", true) { b ->
                heroMaterial = (heroMaterial + 1) % 4
                val names = arrayOf("CONCRETE", "OBSIDIAN", "BASALT", "GRANITE")
                setButtonState(b, true, false, names[heroMaterial])
                if (rendererHandle != 0L) nativeSetHeroMaterial(rendererHandle, heroMaterial)
            }''')
rep(ui, "private var heroMaterial = 1", "private var heroMaterial = 1")
# Insert object tuning section before LIGHT ORB.
marker = '            addView(section("LIGHT ORB"))\n'
insert = '''            addView(section("OBJECT PHYSICS"))
            addView(note("Tune the four benchmark bodies independently. Values update live."))
            fun bodySlider(title: String, body: Int, prop: Int, initial: Int, minV: Float, maxV: Float) {
                val l = label(title)
                addView(l)
                addView(slider(initial) { p ->
                    val v = minV + (maxV - minV) * (p / 100f)
                    l.text = String.format(Locale.US, "%s  %.2f", title, v)
                    if (rendererHandle != 0L) nativeSetBodyProperty(rendererHandle, body, prop, v)
                }, LinearLayout.LayoutParams(-1, dp(30)))
            }
            bodySlider("STEEL BOUNCE", 0, 0, 3, 0f, .35f)
            bodySlider("STEEL FRICTION", 0, 1, 55, .20f, .95f)
            bodySlider("GUMMY FRICTION", 1, 1, 45, .18f, .90f)
            bodySlider("RUBBER BOUNCE", 3, 0, 78, .25f, .95f)
            bodySlider("RUBBER FRICTION", 3, 1, 62, .20f, 1.05f)
            bodySlider("LIGHT MASS", 2, 2, 12, .20f, 5.0f)

            addView(section("LIGHT ORB"))
'''
rep(ui, marker, insert)
# Scene/weather copy becomes playground/environment.
rep(ui, 'addView(section("HOUSE / WEATHER"))', 'addView(section("ENVIRONMENT"))')
rep(ui, 'stressMode, stressMode, if (stressMode) "STRESS LAB" else "HOUSE"', 'stressMode, stressMode, if (stressMode) "STRESS LAB" else "PLAYGROUND"')
# JNI declaration.
rep(ui,
    "    private external fun nativeSetGummyBounce(handle: Long, value: Float)\n",
    "    private external fun nativeSetGummyBounce(handle: Long, value: Float)\n    private external fun nativeSetBodyProperty(handle: Long, body: Int, property: Int, value: Float)\n")

# -----------------------------------------------------------------------------
# JNI + status labels.
# -----------------------------------------------------------------------------
p5 = "app/src/main/cpp/native_vulkan_studio_v7_part5.inc"
rep(p5, "Vulkan Studio v11.0", "Vulkan Studio v12.0")
rep(p5, "RAIN HOUSE", "PLAYGROUND")
rep(p5, "Alpine rain windows · flipbook fire", "Unreal-style playground · neutral materials")
rep(p5, "final scene · steel + gummy + light orb + rubber · slime/TV removed", "final scene · steel + gummy + configurable light orb + rubber · playground geometry")
rep(p5, "<<(pathTracing_.load()?0.34:0.76)", "<<(pathTracing_.load()?0.70:0.76)")
rep(p5,
    "extern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetGummyBounce(JNIEnv*,jobject,jlong h,jfloat v){if(auto*r=ptrV7(h))r->setGummyBounce(v);}\n",
    "extern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetGummyBounce(JNIEnv*,jobject,jlong h,jfloat v){if(auto*r=ptrV7(h))r->setGummyBounce(v);}\nextern \"C\" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetBodyProperty(JNIEnv*,jobject,jlong h,jint b,jint p,jfloat v){if(auto*r=ptrV7(h))r->setBodyProperty(b,p,v);}\n")

print("Vulkan Studio v12 source pass applied")
