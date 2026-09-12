#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def patch(path: str, replacements: list[tuple[str, str]]) -> None:
    p = ROOT / path
    text = p.read_text()
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"v11 patch marker missing in {path}: {old[:120]!r}")
        text = text.replace(old, new)
    p.write_text(text)

# Native renderer branding + final mobile render scales.
patch("app/src/main/cpp/native_vulkan_studio_v7_part1.inc", [
    ("// Vessel Vulkan Studio v10.1", "// Vessel Vulkan Studio v11.0"),
    ("// Final Adreno-first showcase: stable 120 Hz hybrid HWRT plus a reconstructed path-lighting quality mode.",
     "// Final Adreno-first showcase: deterministic 120 Hz hybrid HWRT plus sparse reconstructed path-quality lighting."),
    ("ANativeWindow_setBuffersGeometry(w,std::max(1,int(visibleW_.load()*.80f)),std::max(1,int(visibleH_.load()*.80f)),0);",
     "ANativeWindow_setBuffersGeometry(w,std::max(1,int(visibleW_.load()*.76f)),std::max(1,int(visibleH_.load()*.76f)),0);"),
    ("logV7(\"Studio v10.1 · cleaned house showcase + stable reconstructed path lighting\");",
     "logV7(\"Studio v11.0 · final alpine rain-house showcase + deterministic reconstructed path lighting\");"),
    ("logV7(\"safety · Adreno tile-aware 0.80x hybrid / 0.42x path scale + sparse ray queries\");",
     "logV7(\"safety · Adreno tile-aware 0.76x hybrid / 0.34x path scale + sparse ray queries\");"),
    ("logV7(\"scene · real alpine HDRI through rainy windows · no slime · no TV · side-wall fire sconces\");",
     "logV7(\"scene · alpine landscape through rainy glass · no slime · no TV · CC0 flipbook fire sconces\");"),
    ("void applyRenderScale(){float s=pathTracing_.load()?.42f:.80f;",
     "void applyRenderScale(){float s=pathTracing_.load()?.34f:.76f;"),
    ("PATH QUALITY -> ON · 0.42x sparse reconstructed indirect lighting for 120 Hz target",
     "PATH QUALITY -> ON · 0.34x sparse reconstructed indirect lighting for 120 Hz target"),
    ("PATH QUALITY -> OFF · 0.80x stable hybrid restored",
     "PATH QUALITY -> OFF · 0.76x stable hybrid restored"),
])

# Repurpose the unused GPU dark-rock displacement sampler for a real CC0 60-frame fire atlas.
# Keep the CPU dark-rock height map for any geometry/physics-side use.
patch("app/src/main/cpp/native_vulkan_studio_v7_part3.inc", [
    ("darkHeight_=createTexture8(\"vulkan_v7/dark_rock_disp_2k.png\",VK_FORMAT_R8G8B8A8_UNORM);",
     "darkHeight_=createTexture8(\"vulkan_v7/fire1_64.png\",VK_FORMAT_R8G8B8A8_UNORM);"),
    ("logV7(\"assets · wet stone + plaster/rock + fabric/glass + Alpine HDRI ready\");",
     "logV7(\"assets · wet stone + plaster/rock + fabric/glass + Alpine HDRI + CC0 fire atlas ready\");"),
])

# Reduce ray-query pressure to match the real Q100 telemetry rather than the old optimistic budget.
patch("app/src/main/cpp/native_vulkan_studio_v7_part4.inc", [
    ("float rtBudgetV7()const{if(!rtActiveV7())return 0;if(pathTracing_.load())return .30f;",
     "float rtBudgetV7()const{if(!rtActiveV7())return 0;if(pathTracing_.load())return .18f;"),
    ("uint32_t cadence=moving?2u:(pathTracing_.load()?10u:(stress_.load()?16u:14u));",
     "uint32_t cadence=moving?3u:(pathTracing_.load()?14u:(stress_.load()?20u:18u));"),
    ("if(gpuMs_>8.3){s=std::max(.06f,s*.72f);",
     "if(gpuMs_>8.15){s=std::max(.04f,s*.68f);"),
    ("else if(gpuMs_>7.45){s=std::max(.09f,s*.88f);",
     "else if(gpuMs_>7.25){s=std::max(.07f,s*.84f);"),
    ("else if(gpuMs_<6.75){slowFrames_=0;s=std::min(1.0f,s+.006f);}",
     "else if(gpuMs_<6.55){slowFrames_=0;s=std::min(1.0f,s+.004f);}"),
    ("RT governor · protecting a 7.4 ms GPU budget for stable 120 Hz presentation",
     "RT governor · protecting a 7.2 ms GPU budget for stable 120 Hz presentation"),
])

# Runtime labels.
patch("app/src/main/cpp/native_vulkan_studio_v7_part5.inc", [
    ("Vulkan Studio v10.0", "Vulkan Studio v11.0"),
    ("loading final rain-house renderer…", "loading final v11 alpine rain-house renderer…"),
    ("READY · 240 Hz physics · stable hybrid HWRT · sparse reconstructed path lighting · Alpine rain windows",
     "READY · 240 Hz physics · deterministic hybrid HWRT · sparse reconstructed path lighting · Alpine rain windows · flipbook fire"),
    ("final scene · steel + gummy + light orb + rubber · slime removed",
     "final scene · steel + gummy + light orb + rubber · slime/TV removed"),
    ("<<(pathTracing_.load()?0.42:0.80)", "<<(pathTracing_.load()?0.34:0.76)"),
])

# UI branding, truthful slider description, and remove the stale slime copy.
patch("app/src/main/java/com/example/dreamlinux/NativeCubeActivity.kt", [
    ("Vulkan Studio v10.0\\nLoading rainy house showcase…", "Vulkan Studio v11.0\\nLoading final alpine rain-house showcase…"),
    ("VULKAN STUDIO  //  v10.0", "VULKAN STUDIO  //  v11.0"),
    ("RAIN HOUSE · 120 HZ · HWRT · PATH QUALITY", "ALPINE RAIN HOUSE · 120 HZ · HWRT · PATH QUALITY"),
    ("Hybrid targets 120 Hz. Path Quality runs a stable reconstructed indirect bounce at 0.50x to keep ray cost bounded without the old salt-and-pepper flicker.",
     "Hybrid targets 120 Hz at 0.76x. Path Quality uses sparse reconstructed indirect lighting at 0.34x; 0/100 is completely flat/unlit."),
    ("Rubber and gummy keep collision-matched multi-axis squash with faster release velocity. Slime stays viscous, grounded and slow to relax.",
     "Rubber and gummy use collision-matched multi-axis squash, high-speed release velocity and 240 Hz contacts. Slime is removed."),
])

# Vertex branding and slightly fuller fire-card silhouettes on the side walls.
patch("app/src/main/cpp/shaders/native_studio_v7.vert", [
    ("// Vulkan Studio v10.1 house showcase.", "// Vulkan Studio v11.0 final alpine rain-house showcase."),
    ("s=vec3(.055,.50,.18);mat=15;", "s=vec3(.045,.62,.28);mat=15;"),
])

# Shader-side final pass: actual flipbook fire, stronger wet volcanic response, stable/sparser path rays.
patch("app/src/main/cpp/shaders/native_studio_v7_rt.frag", [
    ("// Vulkan Studio v10.1: deterministic hybrid lighting, sparse reconstructed path quality, real HDRI windows and texture-driven fire.",
     "// Vulkan Studio v11.0: deterministic hybrid lighting, sparse reconstructed path quality, real Alpine rain windows and CC0 flipbook fire."),
    ("layout(set=0,binding=14) uniform sampler2D darkHeight;", "layout(set=0,binding=14) uniform sampler2D fireAtlas;"),
    ("float h=m==6?texture(darkHeight,t).r:texture(concreteHeight,t).r;",
     "float h=m==6?texture(darkArm,t).r:texture(concreteHeight,t).r;"),
    ("m.base*=mix(1.0,.42,w);m.roughness=mix(m.roughness,.055,w*.94);m.clearcoat=max(m.clearcoat,w*.96);",
     "m.base*=mix(1.0,.34,w);m.roughness=mix(m.roughness,.035,w*.96);m.clearcoat=max(m.clearcoat,w);"),
    ("Material materialAt(int m,vec3 P,vec2 uv,out float water){",
     "vec4 fireFrame(vec2 uv,float phase){float f=mod(floor(pc.time*24.0+phase),60.0);vec2 cell=vec2(mod(f,10.0),5.0-floor(f/10.0));vec2 auv=(cell+clamp(uv,vec2(0),vec2(1)))/vec2(10.0,6.0);return texture(fireAtlas,auv);}\nMaterial materialAt(int m,vec3 P,vec2 uv,out float water){"),
    ("else if(m==15){vec2 fuv=uv;float n1=texture(concreteAlbedo,fract(vec2(fuv.x*1.5,fuv.y*1.8-pc.time*.70))).r;float n2=texture(concreteAlbedo,fract(vec2(fuv.x*2.7+.21,fuv.y*2.3-pc.time*1.02))).g;float tip=smoothstep(.04,.98,fuv.y),width=mix(.78,.08,tip),edge=abs(fuv.x-.5)*2.0;float shape=1.0-smoothstep(width-.10,width+.08,edge+(n1-.5)*.34*tip);float core=(1.0-smoothstep(width*.48,width*.78,edge))*shape;float heat=clamp(.34+.66*tip,0.0,1.0);a.base=mix(vec3(.05,.004,.001),vec3(.95,.10,.005),heat);a.roughness=.08;a.emission=shape*vec3(7.5,1.25,.035)*(1.15-.50*tip)+core*vec3(4.0,3.0,.58)*(1.0-.35*tip)+(n2*.10)*vec3(1.2,.14,.01);}",
     "else if(m==15){vec4 f=fireFrame(uv,float(inObject)*7.0);float alpha=max(f.a,max(f.r,max(f.g,f.b)));a.base=max(f.rgb,vec3(.015,.002,.001));a.roughness=.10;a.emission=f.rgb*(7.4+3.2*alpha)+vec3(1.0,.16,.015)*alpha*.45;}"),
    ("else if(m==22){float n1=texture(concreteAlbedo,fract(vec2(uv.x*2.0,uv.y*2.2-pc.time*.64))).r;float edge=abs(uv.x-.5)*2.0,flame=(1.0-smoothstep(.42+.12*n1,.88,edge))*smoothstep(.0,.14,uv.y)*(1.0-smoothstep(.78,1.0,uv.y));float coals=smoothstep(.0,.25,uv.y)*(1.0-smoothstep(.25,.52,uv.y));a.base=vec3(.020,.007,.003);a.roughness=.44;a.emission=vec3(8.0,1.10,.035)*flame+vec3(2.4,.18,.008)*coals;}",
     "else if(m==22){vec4 f=fireFrame(vec2(uv.x,clamp(uv.y*1.12,0.0,1.0)),19.0);float alpha=max(f.a,max(f.r,max(f.g,f.b)));float coals=smoothstep(.0,.28,uv.y)*(1.0-smoothstep(.28,.52,uv.y));a.base=vec3(.020,.007,.003);a.roughness=.40;a.emission=f.rgb*(6.8+2.0*alpha)+vec3(2.5,.20,.008)*coals;}"),
    ("if(mat==15){float n=texture(concreteAlbedo,fract(vec2(inUv.x*2.2,inUv.y*2.1-pc.time*.85))).r;float tip=smoothstep(.03,.98,inUv.y),width=mix(.82,.07,tip),edge=abs(inUv.x-.5)*2.0;if(edge>width+(n-.5)*.28*tip)discard;}",
     "if(mat==15){vec4 f=fireFrame(inUv,float(inObject)*7.0);float alpha=max(f.a,max(f.r,max(f.g,f.b)));if(alpha<.055)discard;}"),
    ("if(localScore>.08&&gate<.22)localVis=shadowVisibility", "if(localScore>.11&&gate<.12)localVis=shadowVisibility"),
    ("vec3 indirect=gate<.26?stablePathIndirect", "vec3 indirect=gate<.14?stablePathIndirect"),
    ("float fixedRayRate=.018+.028*clamp(pc.quality/100.0,0.0,1.0);",
     "float fixedRayRate=.012+.020*clamp(pc.quality/100.0,0.0,1.0);"),
])

print("Vulkan Studio v11 source pass applied")
