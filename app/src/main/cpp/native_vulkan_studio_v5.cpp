// Vessel Vulkan Studio v5
// Dedicated scene/runtime layer: bright enclosed room, bounded physics, optimized HW ray queries.

#define private public
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeResize Java_com_example_dreamlinux_NativeCubeActivity_nativeResize_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs_v3_internal
#include "native_vulkan_studio_v3.cpp"
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeResize
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs
#undef private

#include "native_studio_v5_vert_spv.h"
#include "native_studio_v5_frag_spv.h"
#include "native_studio_v5_rt_frag_spv.h"

namespace {
constexpr float kRoomHalf = 7.12f;
constexpr float kRoomCeiling = 5.20f;
constexpr uint32_t kV5StaticVerts = 36u + 6u + 5u * 36u;
constexpr uint32_t kV5QualityVerts = kV5StaticVerts + kSphereVerts * 3u;
constexpr uint32_t kV5StaticInstances = 7u;
constexpr uint32_t kV5MaxInstances = kV5StaticInstances + kBodyCount;

class VulkanStudioV5 final : public VulkanStudioV3 {
public:
    explicit VulkanStudioV5(ANativeWindow* w) : VulkanStudioV3(w) {
        configureBodies();
        cameraDistance_ = 6.15f;
        pitch_ = 0.24f;
        yaw_ = -0.58f;
        logV5("Studio v5: dedicated bright room renderer");
        logV5("RT budget: 1 area-light shadow ray + selective 1-bounce metal reflection");
        logV5("physics: enclosed floor/walls/ceiling, 5 substeps, 3 impulse iterations");
    }

    void start() {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) return;
        thread_ = std::thread([this] { loopV5(); });
    }

    void zoom(float scale) {
        VulkanStudioV3::zoom(scale);
        cameraDistance_ = std::clamp(cameraDistance_.load(), 3.8f, 6.55f);
    }

    void resetPhysics() {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        initBodiesLocked();
        configureBodiesLocked();
        grabbed_ = -1;
        grabTargetVelocity_ = {};
        logV5Unlocked("scene reset · enclosed material room");
    }

    void grabStart(float nx, float ny) {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        Vec3 ro, rd; screenRay(nx, ny, ro, rd);
        float best = 1e9f; int bestIndex = -1;
        for (int i = 0; i < activeBodies(); ++i) {
            const Body& b = bodies_[i]; Vec3 oc = ro - b.p;
            float qb = dot(oc, rd), c = dot(oc, oc) - b.r * b.r, disc = qb * qb - c;
            if (disc < 0) continue;
            float t = -qb - std::sqrt(disc);
            if (t > 0.02f && t < best) { best = t; bestIndex = i; }
        }
        grabbed_ = bestIndex; grabDepth_ = best;
        if (grabbed_ >= 0) {
            grabTarget_ = ro + rd * best; grabPrevTarget_ = grabTarget_; grabTargetVelocity_ = {};
            grabLastUpdate_ = Clock::now();
            logV5Unlocked(std::string("grab body ") + std::to_string(grabbed_) + (grabbed_ == 2 ? " · LIGHT" : grabbed_ == 1 ? " · GUMMY" : ""));
        }
    }

    void grabMove(float nx, float ny) {
        std::lock_guard<std::mutex> lock(bodyMutex_); if (grabbed_ < 0) return;
        Vec3 ro, rd; screenRay(nx, ny, ro, rd); Vec3 next = ro + rd * grabDepth_;
        auto now = Clock::now(); float dt = std::chrono::duration<float>(now - grabLastUpdate_).count();
        if (dt > 0.0005f) {
            Vec3 instant = (next - grabPrevTarget_) / dt; float s = len(instant);
            if (s > 14.0f) instant = instant * (14.0f / s);
            grabTargetVelocity_ = grabTargetVelocity_ * 0.74f + instant * 0.26f;
        }
        grabPrevTarget_ = next; grabTarget_ = next; grabLastUpdate_ = now;
        if (!physics_.load()) {
            Body& b = bodies_[grabbed_]; b.p = clampInside(next, b.r); b.v = {}; // kinematic placement when physics is paused
        }
    }

    void grabEnd() {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        if (grabbed_ >= 0 && physics_.load()) {
            bodies_[grabbed_].v += grabTargetVelocity_ * 0.60f; float s = len(bodies_[grabbed_].v);
            if (s > 16.0f) bodies_[grabbed_].v = bodies_[grabbed_].v * (16.0f / s);
        }
        grabbed_ = -1; grabTargetVelocity_ = {};
    }

private:
    void logV5(const std::string& s) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "%s", s.c_str());
        std::lock_guard<std::mutex> lock(logMutex_); logV5Unlocked(s);
    }
    void logV5Unlocked(const std::string& s) {
        logs_.push_back(s); if (logs_.size() > 160) logs_.erase(logs_.begin(), logs_.begin() + (logs_.size() - 160));
    }

    Vec3 clampInside(Vec3 p, float r) const {
        p.x = std::clamp(p.x, -kRoomHalf + r, kRoomHalf - r);
        p.z = std::clamp(p.z, -kRoomHalf + r, kRoomHalf - r);
        p.y = std::clamp(p.y, kFloorY + r, kRoomCeiling - r);
        return p;
    }

    void configureBodies() { std::lock_guard<std::mutex> lock(bodyMutex_); configureBodiesLocked(); }
    void configureBodiesLocked() {
        if (bodies_.size() < 3) return;
        bodies_[0].p = {-2.20f, kFloorY + .76f, -.25f}; bodies_[0].r = .76f; bodies_[0].material = 2; bodies_[0].restitution = .28f; bodies_[0].friction = .30f;
        bodies_[1].p = { 2.10f, kFloorY + .69f,  .15f}; bodies_[1].r = .69f; bodies_[1].material = 3; bodies_[1].restitution = .76f; bodies_[1].friction = .22f; bodies_[1].gummy = true;
        bodies_[2].p = { .15f, 2.55f, -2.10f}; bodies_[2].r = .46f; bodies_[2].material = 4; bodies_[2].restitution = .38f; bodies_[2].friction = .34f;
        for (int i = 0; i < kStressCount; ++i) {
            int bi = 3 + i, gx = i % 8, gz = i / 8; float r = .27f + .025f * float((i * 5) % 4);
            Body& b = bodies_[bi]; b.r = r;
            b.p = {(float(gx) - 3.5f) * 1.20f, kFloorY + r + .08f * float(i % 3), (float(gz) - 3.5f) * 1.18f};
            b.v = {}; b.gummy = false; b.restitution = .31f + .04f * float(i % 3); b.friction = .42f;
            // Stress scene is deliberately material-diverse without dozens of fake light emitters.
            b.material = (i % 11 == 0) ? 8 : ((i % 3 == 0) ? 7 : 6);
            float density = (b.material == 8 ? 4.5f : 1.35f); b.mass = std::max(.18f, 4.18879f * r * r * r * density);
        }
    }

    void bouncePlane(Body& b, Vec3 n, float penetration) {
        if (penetration <= 0.0f) return; b.p += n * penetration; float vn = dot(b.v, n);
        if (vn < 0.0f) b.v -= n * ((1.0f + b.restitution) * vn);
        Vec3 tangent = b.v - n * dot(b.v, n); b.v -= tangent * std::min(.22f, b.friction * .12f);
    }

    void collideRoom(Body& b) {
        bouncePlane(b, { 1,0,0}, (-kRoomHalf + b.r) - b.p.x);
        bouncePlane(b, {-1,0,0}, b.p.x - ( kRoomHalf - b.r));
        bouncePlane(b, {0,0, 1}, (-kRoomHalf + b.r) - b.p.z);
        bouncePlane(b, {0,0,-1}, b.p.z - ( kRoomHalf - b.r));
        float floorPen = kFloorY - (b.p.y - b.r);
        if (floorPen > 0.0f) {
            float impact = std::max(0.0f, -b.v.y); bouncePlane(b, {0,1,0}, floorPen);
            float lateral = std::max(0.0f, 1.0f - b.friction * .018f); b.v.x *= lateral; b.v.z *= lateral;
            if (b.gummy && impact > .12f) b.deformV += impact * .19f;
        }
        bouncePlane(b, {0,-1,0}, (b.p.y + b.r) - kRoomCeiling);
    }

    void collidePlinth(Body& b) {
        Vec3 lo{-1.15f,-1.0f,-1.15f}, hi{1.15f,1.0f,1.15f};
        Vec3 cp{std::clamp(b.p.x,lo.x,hi.x),std::clamp(b.p.y,lo.y,hi.y),std::clamp(b.p.z,lo.z,hi.z)};
        Vec3 d=b.p-cp; float dl=len(d); if (dl>=b.r) return;
        Vec3 n;
        if (dl>1e-5f) n=d/dl; else { Vec3 a{std::abs(b.p.x/1.15f),std::abs(b.p.y),std::abs(b.p.z/1.15f)}; if(a.x>=a.y&&a.x>=a.z)n={b.p.x>=0?1.f:-1.f,0,0};else if(a.y>=a.z)n={0,b.p.y>=0?1.f:-1.f,0};else n={0,0,b.p.z>=0?1.f:-1.f}; dl=0; }
        float pen=b.r-dl; b.p+=n*pen; float vn=dot(b.v,n);
        if(vn<0){float j=-(1.0f+b.restitution)*vn;b.v+=n*j;Vec3 t=b.v-n*dot(b.v,n);b.v-=t*std::min(.38f,b.friction*.28f);if(b.gummy)b.deformV+=std::abs(j)*.12f;}
    }

    void solvePair(Body& a, Body& b) {
        Vec3 d=b.p-a.p;float dist=len(d),rr=a.r+b.r;if(dist>=rr||dist<1e-6f)return;Vec3 n=d/dist;
        float ia=1.0f/std::max(a.mass,.1f),ib=1.0f/std::max(b.mass,.1f),sum=ia+ib,pen=rr-dist;
        float corr=std::max(0.0f,pen-.001f)*.72f;a.p-=n*(corr*ia/sum);b.p+=n*(corr*ib/sum);
        Vec3 rel=b.v-a.v;float vn=dot(rel,n);if(vn>=0)return;float e=std::min(a.restitution,b.restitution),j=-(1.0f+e)*vn/sum;
        Vec3 J=n*j;a.v-=J*ia;b.v+=J*ib;Vec3 tangent=rel-n*vn;float tl=len(tangent);
        if(tl>1e-5f){Vec3 t=tangent/tl;float jt=-dot(rel,t)/sum,mu=std::sqrt(std::max(0.0f,a.friction*b.friction));jt=std::clamp(jt,-j*mu,j*mu);Vec3 F=t*jt;a.v-=F*ia;b.v+=F*ib;}
        if(a.gummy)a.deformV+=std::abs(j)*ia*.08f;if(b.gummy)b.deformV+=std::abs(j)*ib*.08f;
    }

    void physicsStepV5(float dt) {
        std::lock_guard<std::mutex> lock(bodyMutex_); int n=activeBodies(); if(n<=0)return;
        if(!physics_.load()) return;
        const int substeps=5;float h=std::min(dt,.024f)/float(substeps);
        for(int sub=0;sub<substeps;++sub){
            for(int i=0;i<n;++i){Body& b=bodies_[i];b.v.y-=9.81f*h;
                if(i==grabbed_){Vec3 err=grabTarget_-b.p,desired=grabTargetVelocity_;Vec3 acc=err*68.0f+(desired-b.v)*16.0f;float al=len(acc);if(al>95.0f)acc=acc*(95.0f/al);b.v+=acc*h;}
                b.p+=b.v*h;collideRoom(b);collidePlinth(b);
            }
            for(int it=0;it<3;++it)for(int i=0;i<n;++i)for(int j=i+1;j<n;++j)solvePair(bodies_[i],bodies_[j]);
            for(int i=0;i<n;++i){Body& b=bodies_[i];if(!b.gummy)continue;constexpr float spring=58.0f,damping=8.8f;b.deformV+=(-spring*b.deform-damping*b.deformV)*h;b.deform+=b.deformV*h;b.deform=std::clamp(b.deform,-.045f,.095f);}
        }
    }

    void replacePipelineV5() {
        if(pipeline_) { vkDestroyPipeline(device_,pipeline_,nullptr); pipeline_=VK_NULL_HANDLE; }
        VkShaderModule vs=shader(native_studio_v5_vert_spv,native_studio_v5_vert_spv_size);
        VkShaderModule fs=rtSupported_?shader(native_studio_v5_rt_frag_spv,native_studio_v5_rt_frag_spv_size):shader(native_studio_v5_frag_spv,native_studio_v5_frag_spv_size);
        VkPipelineShaderStageCreateInfo ss[2]={{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
        ss[0].stage=VK_SHADER_STAGE_VERTEX_BIT;ss[0].module=vs;ss[0].pName="main";ss[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;ss[1].module=fs;ss[1].pName="main";
        VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vp.viewportCount=1;vp.scissorCount=1;VkDynamicState ds[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};VkPipelineDynamicStateCreateInfo dy{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};dy.dynamicStateCount=2;dy.pDynamicStates=ds;
        VkPipelineRasterizationStateCreateInfo ra{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};ra.polygonMode=VK_POLYGON_MODE_FILL;ra.cullMode=VK_CULL_MODE_BACK_BIT;ra.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;ra.lineWidth=1;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;VkPipelineDepthStencilStateCreateInfo dep{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};dep.depthTestEnable=VK_TRUE;dep.depthWriteEnable=VK_TRUE;dep.depthCompareOp=VK_COMPARE_OP_LESS;
        VkPipelineColorBlendAttachmentState ba{};ba.colorWriteMask=0xf;VkPipelineColorBlendStateCreateInfo bl{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};bl.attachmentCount=1;bl.pAttachments=&ba;
        VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};gi.stageCount=2;gi.pStages=ss;gi.pVertexInputState=&vin;gi.pInputAssemblyState=&ia;gi.pViewportState=&vp;gi.pRasterizationState=&ra;gi.pMultisampleState=&ms;gi.pDepthStencilState=&dep;gi.pColorBlendState=&bl;gi.pDynamicState=&dy;gi.layout=layout_;gi.renderPass=renderPass_;
        checkVk(vkCreateGraphicsPipelines(device_,VK_NULL_HANDLE,1,&gi,nullptr,&pipeline_),"v5 pipeline");vkDestroyShaderModule(device_,vs,nullptr);vkDestroyShaderModule(device_,fs,nullptr);
        logV5(rtSupported_?"v5 optimized hybrid ray-query pipeline ready":"v5 PBR raster pipeline ready");
    }

    void rebuildTlasV5() {
        if(!rtSupported_)return;vkDeviceWaitIdle(device_);destroyAccel(tlas_);destroyBuffer(tlasScratch_);destroyBuffer(instanceBuffer_);
        instanceBuffer_=createBuffer(sizeof(VkAccelerationStructureInstanceKHR)*kV5MaxInstances,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,true);
        VkAccelerationStructureGeometryInstancesDataKHR id{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR};id.data.deviceAddress=addr(instanceBuffer_.buffer);VkAccelerationStructureGeometryKHR g{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};g.geometryType=VK_GEOMETRY_TYPE_INSTANCES_KHR;g.geometry.instances=id;
        uint32_t count=kV5MaxInstances;VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};info.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;info.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR|VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;info.geometryCount=1;info.pGeometries=&g;
        VkAccelerationStructureBuildSizesInfoKHR sz{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};fpGetBuildSizes_(device_,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,&info,&count,&sz);
        tlas_.storage=createBuffer(sz.accelerationStructureSize,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,false);VkAccelerationStructureCreateInfoKHR ci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};ci.buffer=tlas_.storage.buffer;ci.size=sz.accelerationStructureSize;ci.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;checkVk(fpCreateAS_(device_,&ci,nullptr,&tlas_.handle),"v5 TLAS");
        tlasScratch_=createBuffer(std::max(sz.buildScratchSize,sz.updateScratchSize),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,true);tlasGeometry_=g;tlasBuilt_=false;
        VkWriteDescriptorSetAccelerationStructureKHR asi{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR};asi.accelerationStructureCount=1;asi.pAccelerationStructures=&tlas_.handle;VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};w.pNext=&asi;w.dstSet=descSet_;w.dstBinding=1;w.descriptorCount=1;w.descriptorType=VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;vkUpdateDescriptorSets(device_,1,&w,0,nullptr);
        logV5("v5 TLAS rebuilt: floor + plinth + 4 walls + ceiling + dynamic bodies");
    }

    void updateTlasInstancesV5() {
        auto* d=reinterpret_cast<VkAccelerationStructureInstanceKHR*>(instanceBuffer_.mapped);std::memset(d,0,sizeof(VkAccelerationStructureInstanceKHR)*kV5MaxInstances);
        auto put=[&](uint32_t i,Vec3 p,float sx,float sy,float sz,uint32_t custom,Accel& as){d[i].transform=transform(p,sx,sy,sz);d[i].instanceCustomIndex=custom;d[i].mask=0xff;d[i].flags=VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;d[i].accelerationStructureReference=as.address;};
        put(0,{0,kFloorY,0},7.2f,1,7.2f,0,floorAs_);put(1,{0,0,0},1.15f,1,1.15f,1,cubeAs_);
        put(2,{0,2.15f,-7.2f},7.2f,3.15f,.08f,2,cubeAs_);put(3,{0,2.15f,7.2f},7.2f,3.15f,.08f,3,cubeAs_);
        put(4,{-7.2f,2.15f,0},.08f,3.15f,7.2f,4,cubeAs_);put(5,{7.2f,2.15f,0},.08f,3.15f,7.2f,5,cubeAs_);put(6,{0,5.30f,0},7.2f,.08f,7.2f,6,cubeAs_);
        std::lock_guard<std::mutex> lock(bodyMutex_);int active=activeBodies();for(int i=0;i<kBodyCount;++i){const Body& b=bodies_[i];auto& ins=d[kV5StaticInstances+i];ins.transform=transform(b.p,b.r,b.r,b.r);ins.instanceCustomIndex=uint32_t(i)+kV5StaticInstances;ins.mask=i<active?0xff:0;ins.flags=VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;ins.accelerationStructureReference=sphereAs_.address;}
    }

    void cmdUpdateTlasV5(VkCommandBuffer cmd) {
        updateTlasInstancesV5();VkAccelerationStructureGeometryInstancesDataKHR id{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR};id.data.deviceAddress=addr(instanceBuffer_.buffer);tlasGeometry_.geometry.instances=id;
        VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};info.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;info.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR|VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;info.mode=tlasBuilt_?VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR:VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;info.srcAccelerationStructure=tlasBuilt_?tlas_.handle:VK_NULL_HANDLE;info.dstAccelerationStructure=tlas_.handle;info.geometryCount=1;info.pGeometries=&tlasGeometry_;info.scratchData.deviceAddress=addr(tlasScratch_.buffer);
        VkAccelerationStructureBuildRangeInfoKHR range{};range.primitiveCount=kV5MaxInstances;const VkAccelerationStructureBuildRangeInfoKHR* pr=&range;fpCmdBuildAS_(cmd,1,&info,&pr);tlasBuilt_=true;
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;barrier.dstAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }

    uint32_t drawVertsV5() const {int q=quality_.load();if(q<12)return kV5StaticVerts;if(!stress_.load())return kV5QualityVerts;int extras=8+(q*40)/100;return kV5QualityVerts+uint32_t(std::clamp(extras,8,48))*kStressSphereVerts;}

    void recordV5(uint32_t idx,float t){
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};checkVk(vkBeginCommandBuffer(cmd_,&bi),"begin v5 cmd");if(timestamps_){vkCmdResetQueryPool(cmd_,queryPool_,0,2);vkCmdWriteTimestamp(cmd_,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queryPool_,0);}bool useRt=rtSupported_&&requestedRt_.load()&&quality_.load()>=45;if(useRt)cmdUpdateTlasV5(cmd_);
        VkClearValue cv[2]{};cv[0].color={{.12f,.14f,.16f,1}};cv[1].depthStencil={1,0};VkRenderPassBeginInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};ri.renderPass=renderPass_;ri.framebuffer=fbs_[idx];ri.renderArea.extent=extent_;ri.clearValueCount=2;ri.pClearValues=cv;vkCmdBeginRenderPass(cmd_,&ri,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(cmd_,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline_);vkCmdBindDescriptorSets(cmd_,VK_PIPELINE_BIND_POINT_GRAPHICS,layout_,0,1,&descSet_,0,nullptr);
        VkViewport vp{};vp.width=float(extent_.width);vp.height=float(extent_.height);vp.maxDepth=1;VkRect2D sc{};sc.extent=extent_;vkCmdSetViewport(cmd_,0,1,&vp);vkCmdSetScissor(cmd_,0,1,&sc);PushConstants p{yaw_.load(),pitch_.load(),float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load())),cameraDistance_.load(),rotCode(preTransform_),float(quality_.load()),useRt?1.0f:0.0f,t};vkCmdPushConstants(cmd_,layout_,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(p),&p);vkCmdDraw(cmd_,drawVertsV5(),1,0,0);vkCmdEndRenderPass(cmd_);if(timestamps_)vkCmdWriteTimestamp(cmd_,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queryPool_,1);checkVk(vkEndCommandBuffer(cmd_),"end v5 cmd");
    }

    void recreateV5(){recreateSwapchain();replacePipelineV5();}
    void drawV5(float t){
        checkVk(vkWaitForFences(device_,1,&fence_,VK_TRUE,UINT64_MAX),"wait v5 fence");if(timestamps_&&queryIssued_){uint64_t q[2]{};if(vkGetQueryPoolResults(device_,queryPool_,0,2,sizeof(q),q,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT)==VK_SUCCESS)gpuMs_=double(q[1]-q[0])*double(timestampPeriod_)/1e6;}
        uint32_t idx=0;VkResult a=vkAcquireNextImageKHR(device_,swap_,UINT64_MAX,imageAvail_,VK_NULL_HANDLE,&idx);if(a==VK_ERROR_OUT_OF_DATE_KHR){recreateV5();return;}if(a!=VK_SUCCESS&&a!=VK_SUBOPTIMAL_KHR)checkVk(a,"v5 acquire");checkVk(vkResetFences(device_,1,&fence_),"reset v5 fence");checkVk(vkResetCommandBuffer(cmd_,0),"reset v5 cmd");recordV5(idx,t);queryIssued_=true;VkPipelineStageFlags stage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&imageAvail_;si.pWaitDstStageMask=&stage;si.commandBufferCount=1;si.pCommandBuffers=&cmd_;si.signalSemaphoreCount=1;si.pSignalSemaphores=&renderDone_;checkVk(vkQueueSubmit(queue_,1,&si,fence_),"v5 submit");VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&renderDone_;pi.swapchainCount=1;pi.pSwapchains=&swap_;pi.pImageIndices=&idx;VkResult pr=vkQueuePresentKHR(queue_,&pi);if(pr==VK_ERROR_OUT_OF_DATE_KHR||pr==VK_SUBOPTIMAL_KHR||resize_.load())recreateV5();else if(pr!=VK_SUCCESS)checkVk(pr,"v5 present");
    }

    void loopV5(){
        try{
            setStatus("Vulkan Studio v5 initializing…");initVulkan();if(rtSupported_)rebuildTlasV5();replacePipelineV5();logV5("bright room active · no black void · bounded physics");
            auto last=Clock::now(),stat=last,start=last,telemetry=last;uint32_t frames=0;
            while(running_){auto now=Clock::now();float dt=std::chrono::duration<float>(now-last).count();last=now;physicsStepV5(dt);updateBodyGpu();float time=std::chrono::duration<float>(now-start).count();drawV5(time);++frames;
                float e=std::chrono::duration<float>(now-stat).count();if(e>=.5f){float fps=frames/e;bool rt=rtSupported_&&requestedRt_.load()&&quality_.load()>=45;std::ostringstream o;o.setf(std::ios::fixed);o.precision(1);o<<"Vulkan Studio v5 · "<<gpuName_<<" · "<<fps<<" FPS · GPU "<<gpuMs_<<" ms\n";o<<"Q"<<quality_.load()<<" · "<<(stress_.load()?"STRESS":"QUALITY")<<" · Physics "<<(physics_.load()?"ON":"OFF")<<" · RT "<<(rt?"RAY QUERY":"OFF")<<"\n";o<<"room 14.2m · bodies "<<activeBodies()<<" · 1 shadow ray · selective 1-bounce";setStatus(o.str());frames=0;stat=now;}
                if(std::chrono::duration<float>(now-telemetry).count()>=2.0f){std::ostringstream l;l.setf(std::ios::fixed);l.precision(2);l<<"telemetry · GPU "<<gpuMs_<<" ms · bodies "<<activeBodies()<<" · RT "<<((rtSupported_&&requestedRt_.load())?"on":"off")<<" · target 120Hz";logV5(l.str());telemetry=now;}
            }
        }catch(const std::exception& e){setStatus(std::string("ERROR: Vulkan Studio v5: ")+e.what());logV5(std::string("ERROR: ")+e.what());}
        cleanup();
    }

    Vec3 grabPrevTarget_{};Vec3 grabTargetVelocity_{};Clock::time_point grabLastUpdate_=Clock::now();
};

VulkanStudioV5* ptrV5(jlong h){return reinterpret_cast<VulkanStudioV5*>(static_cast<intptr_t>(h));}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate(JNIEnv* e,jobject,jobject surface){ANativeWindow* w=ANativeWindow_fromSurface(e,surface);if(!w)return 0;try{auto* r=new VulkanStudioV5(w);r->start();return static_cast<jlong>(reinterpret_cast<intptr_t>(r));}catch(...){ANativeWindow_release(w);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy(JNIEnv*,jobject,jlong h){delete ptrV5(h);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResize(JNIEnv*,jobject,jlong h,jint w,jint he){if(auto*r=ptrV5(h))r->resize(w,he);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate(JNIEnv*,jobject,jlong h,jfloat y,jfloat p){if(auto*r=ptrV5(h))r->rotate(y,p);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom(JNIEnv*,jobject,jlong h,jfloat s){if(auto*r=ptrV5(h))r->zoom(s);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV5(h))r->setStress(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV5(h))r->setPhysics(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV5(h))r->setRt(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality(JNIEnv*,jobject,jlong h,jint q){if(auto*r=ptrV5(h))r->setQuality(q);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics(JNIEnv*,jobject,jlong h){if(auto*r=ptrV5(h))r->resetPhysics();}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptrV5(h))r->grabStart(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptrV5(h))r->grabMove(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd(JNIEnv*,jobject,jlong h){if(auto*r=ptrV5(h))r->grabEnd();}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus(JNIEnv* e,jobject,jlong h){std::string s=ptrV5(h)?ptrV5(h)->status():"renderer stopped";return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs(JNIEnv* e,jobject,jlong h){std::string s=ptrV5(h)?ptrV5(h)->logs():"";return e->NewStringUTF(s.c_str());}