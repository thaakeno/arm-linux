#!/usr/bin/env python3
from pathlib import Path
p = Path(__file__).resolve().parents[2] / 'app/src/main/cpp/native_vulkan_studio_v7_part3.inc'
text = p.read_text()
old = 'Vec3 nx{1,0,0},px{-1,0,0},nz{0,0,1},pz{0,0,-1},ny{0,1,0},py{0,-1,0};float rx=supportRadiusV7(b,i,nx),rpx=supportRadiusV7(b,i,px),rz=supportRadiusV7(b,i,nz),rpz=supportRadiusV7(b,i,pz),ry=supportRadiusV7(b,i,ny),rpy=supportRadiusV7(b,i,py);'
new = 'Vec3 nx{1,0,0},px{-1,0,0},nz{0,0,1},pz{0,0,-1},ny{0,1,0};float rx=supportRadiusV7(b,i,nx),rpx=supportRadiusV7(b,i,px),rz=supportRadiusV7(b,i,nz),rpz=supportRadiusV7(b,i,pz),ry=supportRadiusV7(b,i,ny);'
if old not in text:
    raise SystemExit('ceiling support marker missing')
p.write_text(text.replace(old,new,1))
print('removed unused ceiling support variable')
