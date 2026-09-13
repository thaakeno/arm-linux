#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
COUNT_PATH=ROOT/'app/src/main/assets/blacksite/refrigerator.count'
if not COUNT_PATH.exists(): raise SystemExit('missing baked refrigerator.count')
COUNT=int(COUNT_PATH.read_text().strip())
if COUNT<=0: raise SystemExit('bad prop vertex count')

def rep(path,old,new,count=1):
    p=ROOT/path;s=p.read_text()
    if old not in s: raise SystemExit(f'prop marker missing {path}: {old[:100]!r}')
    p.write_text(s.replace(old,new,count))

p1='app/src/main/cpp/native_vulkan_studio_v7_part1.inc'
p3='app/src/main/cpp/native_vulkan_studio_v7_part3.inc'
p4='app/src/main/cpp/native_vulkan_studio_v7_part4.inc'
p5='app/src/main/cpp/native_vulkan_studio_v7_part5.inc'
vert='app/src/main/cpp/shaders/native_studio_v7.vert'
rt='app/src/main/cpp/shaders/native_studio_v7_rt.frag'

# C++ constants and packed GPU vertex type.
rep(p1,
    'constexpr uint32_t kV7StaticVerts=kV7HeroVerts+kV7FloorVerts+kV7WaterVerts+kV7ArchVerts;',
    f'constexpr uint32_t kBlacksitePropVerts={COUNT}u;\nconstexpr uint32_t kV7StaticVerts=kV7HeroVerts+kV7FloorVerts+kV7WaterVerts+kV7ArchVerts+kBlacksitePropVerts;')
rep(p1,
    'struct HeightV7{int w=0,h=0;std::vector<float> p;};',
    'struct HeightV7{int w=0,h=0;std::vector<float> p;};\nstruct PropVertexV7{float px,py,pz,pw,nx,ny,nz,nw,u,v,q0,q1;};')

# Load the baked GLB triangle list into a storage buffer and CPU positions for BLAS.
marker='        environment_=createTextureHdr("vulkan_v7/evening_museum_courtyard_1k.hdr");\n'
insert=marker+'''        {
            auto raw=assetBytes("blacksite/refrigerator.bin");
            if(raw.size()!=size_t(kBlacksitePropVerts)*sizeof(PropVertexV7))throw std::runtime_error("Blacksite prop vertex payload size mismatch");
            propVertexBuffer_=createBuffer(raw.size(),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,false);
            std::memcpy(propVertexBuffer_.mapped,raw.data(),raw.size());
            const auto*pv=reinterpret_cast<const PropVertexV7*>(raw.data());propTrianglesCpu_.clear();propTrianglesCpu_.reserve(kBlacksitePropVerts);
            for(uint32_t i=0;i<kBlacksitePropVerts;++i)propTrianglesCpu_.push_back({pv[i].px,pv[i].py,pv[i].pz});
        }
'''
rep(p3,marker,insert)
rep(p3,'logV7("assets · wet stone + plaster/rock + fabric/glass + Alpine HDRI ready");','logV7("assets · Poly Haven PBR + real CommercialRefrigerator GLB geometry + material audio ready");')

# BLAS + TLAS: real model participates in reflections and shadows.
rep(p4,
    'destroyAccel(heroAs_);destroyAccel(floorDetailedAs_);destroyBuffer(heroGeom_);destroyBuffer(floorDetailedGeom_);',
    'destroyAccel(heroAs_);destroyAccel(floorDetailedAs_);destroyAccel(propAs_);destroyBuffer(heroGeom_);destroyBuffer(floorDetailedGeom_);destroyBuffer(propGeom_);',1)
rep(p4,
    'heroAs_=buildBlas(heroTrianglesV7(),heroGeom_);floorDetailedAs_=buildBlas(floorTrianglesV7(),floorDetailedGeom_);',
    'heroAs_=buildBlas(heroTrianglesV7(),heroGeom_);floorDetailedAs_=buildBlas(floorTrianglesV7(),floorDetailedGeom_);propAs_=buildBlas(propTrianglesCpu_,propGeom_);')
rep(p4,
    'for(uint32_t i=0;i<kV7ArchBoxes;++i){Vec3 c,s;bool light;archInfoV7(int(i),c,s,light);putInstanceV7(d,2+i,c,s.x,s.y,s.z,2+i,light?0x02:0x03,cubeAs_);}\n        std::lock_guard<std::mutex>lock(bodyMutex_);',
    'for(uint32_t i=0;i<kV7ArchBoxes;++i){Vec3 c,s;bool light;archInfoV7(int(i),c,s,light);putInstanceV7(d,2+i,c,s.x,s.y,s.z,2+i,light?0x02:0x03,cubeAs_);}\n        putInstanceV7(d,2+kV7ArchBoxes,{-4.45f,-.94f,-10.55f},1,1,1,2+kV7ArchBoxes,0x03,propAs_);\n        std::lock_guard<std::mutex>lock(bodyMutex_);')

# Add storage-buffer descriptor at binding 15.
rep(p4,
    'for(uint32_t binding=2;binding<=14;++binding){VkDescriptorSetLayoutBinding tb{};tb.binding=binding;tb.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;tb.descriptorCount=1;tb.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;b.push_back(tb);}',
    'for(uint32_t binding=2;binding<=14;++binding){VkDescriptorSetLayoutBinding tb{};tb.binding=binding;tb.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;tb.descriptorCount=1;tb.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;b.push_back(tb);}\n        VkDescriptorSetLayoutBinding pb{};pb.binding=15;pb.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;pb.descriptorCount=1;pb.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;b.push_back(pb);')
rep(p4,
    'std::vector<VkDescriptorPoolSize>ps{{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1},{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,13}};',
    'std::vector<VkDescriptorPoolSize>ps{{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,2},{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,13}};')
rep(p4,
    'VkDescriptorBufferInfo db{bodyBuffer_.buffer,0,bodyBuffer_.size};std::vector<VkWriteDescriptorSet>w;w.reserve(15);',
    'VkDescriptorBufferInfo db{bodyBuffer_.buffer,0,bodyBuffer_.size};VkDescriptorBufferInfo pdb{propVertexBuffer_.buffer,0,propVertexBuffer_.size};std::vector<VkWriteDescriptorSet>w;w.reserve(16);')
rep(p4,
    'w.push_back(wb);\n        VkWriteDescriptorSetAccelerationStructureKHR asi',
    'w.push_back(wb);VkWriteDescriptorSet wp{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};wp.dstSet=descSet_;wp.dstBinding=15;wp.descriptorCount=1;wp.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;wp.pBufferInfo=&pdb;w.push_back(wp);\n        VkWriteDescriptorSetAccelerationStructureKHR asi')

# Lifetime members / cleanup.
rep(p5,
    'destroyAccel(heroAs_);destroyAccel(floorDetailedAs_);destroyBuffer(heroGeom_);destroyBuffer(floorDetailedGeom_);',
    'destroyAccel(heroAs_);destroyAccel(floorDetailedAs_);destroyAccel(propAs_);destroyBuffer(heroGeom_);destroyBuffer(floorDetailedGeom_);destroyBuffer(propGeom_);destroyBuffer(propVertexBuffer_);')
rep(p5,
    'Accel heroAs_{},floorDetailedAs_{};Buffer heroGeom_{},floorDetailedGeom_{};',
    'Accel heroAs_{},floorDetailedAs_{},propAs_{};Buffer heroGeom_{},floorDetailedGeom_{},propGeom_{},propVertexBuffer_{};std::vector<Vec3>propTrianglesCpu_{};')

# Vertex shader fetches actual baked GLB vertices after authored architecture.
rep(vert,
    'layout(set=0,binding=14) uniform sampler2D darkHeight;',
    'layout(set=0,binding=14) uniform sampler2D darkHeight;\nstruct PropVertex{vec4 p;vec4 n;vec4 uv;};layout(set=0,binding=15,std430) readonly buffer PropVertices{PropVertex propVerts[];};')
rep(vert,
    'const int ARCH_VERTS=ARCH_BOXES*BOX_VERTS+LEAF_VERTS+SKY_VERTS; const int STATIC_VERTS=HERO_VERTS+FLOOR_VERTS+ARCH_VERTS;',
    f'const int ARCH_VERTS=ARCH_BOXES*BOX_VERTS+LEAF_VERTS+SKY_VERTS; const int PROP_VERTS={COUNT}; const int STATIC_VERTS=HERO_VERTS+FLOOR_VERTS+ARCH_VERTS+PROP_VERTS;')
# Architecture end is before sphereVertex. Add prop emitter.
needle='void sphereVertex(int local,int su,int sv,int bodyIndex,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){'
propfn='''void emitImportedProp(int local,out vec3 P,out vec3 N,out vec2 uv,out int M,out int O,out vec3 T,out vec3 B){PropVertex v=propVerts[local];P=v.p.xyz+vec3(-4.45,-.94,-10.55);N=normalize(v.n.xyz);uv=v.uv.xy;M=2;O=30;basisFromNormal(N,T,B);}\n'''
rep(vert,needle,propfn+needle)
rep(vert,
    'else if(idx<STATIC_VERTS)emitArchitecture(idx-HERO_VERTS-FLOOR_VERTS,P,N,uv,M,O,T,B);\n    else if(idx<QUALITY_VERTS)',
    'else if(idx<HERO_VERTS+FLOOR_VERTS+ARCH_VERTS)emitArchitecture(idx-HERO_VERTS-FLOOR_VERTS,P,N,uv,M,O,T,B);\n    else if(idx<STATIC_VERTS)emitImportedProp(idx-(HERO_VERTS+FLOOR_VERTS+ARCH_VERTS),P,N,uv,M,O,T,B);\n    else if(idx<QUALITY_VERTS)')

# RT shader reads the same prop geometry to reconstruct hit normals/UVs.
rep(rt,'const int STATIC_INSTANCES=30;', 'const int STATIC_INSTANCES=31;')
rep(rt,
    'layout(set=0,binding=14) uniform sampler2D darkHeight;',
    'layout(set=0,binding=14) uniform sampler2D darkHeight;\nstruct PropVertex{vec4 p;vec4 n;vec4 uv;};layout(set=0,binding=15,std430) readonly buffer PropVertices{PropVertex propVerts[];};')
old='else if(id<STATIC_INSTANCES){vec3 c,s;boxInfo(id,c,s,hm);hn=boxNormal(hp,c,s);huv=planarUv(hp,hn);}else{int bi=id-STATIC_INSTANCES;bodyHitInfo(bi,hp,hn,huv);hm=bodies[bi].meta.x;}'
new='else if(id==30){int pi=int(rayQueryGetIntersectionPrimitiveIndexEXT(rq,true));vec2 bc=rayQueryGetIntersectionBarycentricsEXT(rq,true);float b0=1.0-bc.x-bc.y;PropVertex a=propVerts[pi*3],b=propVerts[pi*3+1],c=propVerts[pi*3+2];hn=normalize(a.n.xyz*b0+b.n.xyz*bc.x+c.n.xyz*bc.y);huv=a.uv.xy*b0+b.uv.xy*bc.x+c.uv.xy*bc.y;hm=2;}else if(id<STATIC_INSTANCES){vec3 c,s;boxInfo(id,c,s,hm);hn=boxNormal(hp,c,s);huv=planarUv(hp,hn);}else{int bi=id-STATIC_INSTANCES;bodyHitInfo(bi,hp,hn,huv);hm=bodies[bi].meta.x;}'
rep(rt,old,new)

print(f'imported real GLB prop integrated: {COUNT} vertices')
