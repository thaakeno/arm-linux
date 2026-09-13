#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def patch(path, old, new, count=1):
    p = ROOT / path
    s = p.read_text()
    if old not in s:
        raise SystemExit(f'missing polish marker {path}: {old[:120]!r}')
    p.write_text(s.replace(old, new, count))

p1='app/src/main/cpp/native_vulkan_studio_v7_part1.inc'
p3='app/src/main/cpp/native_vulkan_studio_v7_part3.inc'
p4='app/src/main/cpp/native_vulkan_studio_v7_part4.inc'
vert='app/src/main/cpp/shaders/native_studio_v7.vert'
rt='app/src/main/cpp/shaders/native_studio_v7_rt.frag'

# Blacksite is a game, not the old benchmark. Do not draw the four showcase balls.
patch(p1, 'constexpr uint32_t kV7QualityBodies=4u;', 'constexpr uint32_t kV7QualityBodies=0u;')

# Hide the old hero cube in raster and RT; generator/breaker props are authored architecture now.
patch(vert, 'P=c+N*(bevel+h*depth*seam); M=mod(pc.heroMaterial,10.0)<.5?0:6;', 'P=c+N*(bevel+h*depth*seam)+vec3(0,-50.0,0); M=mod(pc.heroMaterial,10.0)<.5?0:6;')
patch(p4, 'putInstanceV7(d,0,{0,0,0},1,1,1,0,0x03,floorDetailedAs_);putInstanceV7(d,1,{0,0,0},1,1,1,1,0x03,heroAs_);', 'putInstanceV7(d,0,{0,0,0},1,1,1,0,0x03,floorDetailedAs_);putInstanceV7(d,1,{0,-50,0},1,1,1,1,0x00,heroAs_);')

# Make ray-traced floor geometry cover the same 40m authored facility floor as raster.
patch(p3, 'float u=gx*3.20f,vv=gy*3.20f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*7.05f,kFloorY+h*.058f,(gy*2.0f-1.0f)*7.05f};', 'float u=gx*8.0f,vv=gy*8.0f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*20.0f,kFloorY+h*.035f,(gy*2.0f-1.0f)*20.0f};')

# The old physics architecture loop is retained for future props but must see all authored boxes.
patch(p3, 'for(int k=0;k<18;++k){if(k==9||k==10)continue;', 'for(int k=0;k<int(kV7ArchBoxes);++k){')

# RT floor UVs must match the 40m / 8x material tiling.
patch(rt, 'huv=(hp.xz/40.0+.5)*8.0;', 'huv=(hp.xz/40.0+.5)*8.0;')
print('containment polish applied')
