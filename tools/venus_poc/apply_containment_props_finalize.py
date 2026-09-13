#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def rep(p,a,b):
    f=ROOT/p;s=f.read_text()
    if a not in s: raise SystemExit(f'missing instance marker: {p}: {a}')
    f.write_text(s.replace(a,b,1))
rep('app/src/main/cpp/native_vulkan_studio_v7_part1.inc','constexpr uint32_t kV7StaticInstances=2u+kV7ArchBoxes;','constexpr uint32_t kV7StaticInstances=2u+kV7ArchBoxes+1u;')
rep('app/src/main/cpp/shaders/native_studio_v7.vert','const int STATIC_INSTANCES=30;','const int STATIC_INSTANCES=31;')
print('prop instance indexing finalized')
