// Vessel Vulkan Studio v7.2
// Coherent offline courtyard renderer: matched raster/RT geometry, physical puddles,
// local HW ray queries, adaptive RT budget, and physics-derived directional gummy deformation.

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"
#pragma clang diagnostic ignored "-Wmissing-field-initializers"
#define STB_IMAGE_IMPLEMENTATION
#include "third_party/stb_image.h"
#pragma clang diagnostic pop

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

#include "native_studio_v7_vert_spv.h"
#include "native_studio_v7_frag_spv.h"
#include "native_studio_v7_rt_frag_spv.h"

namespace {
constexpr float kV7RoomHalf=6.82f;
constexpr uint32_t kV7HeroGrid=32u;
constexpr uint32_t kV7HeroVerts=6u*kV7HeroGrid*kV7HeroGrid*6u;
constexpr uint32_t kV7FloorGrid=64u;
constexpr uint32_t kV7FloorVerts=kV7FloorGrid*kV7FloorGrid*6u;
constexpr uint32_t kV7WaterVerts=0u;
constexpr uint32_t kV7ArchBoxes=18u;
constexpr uint32_t kV7Leaves=96u;
constexpr uint32_t kV7ArchVerts=kV7ArchBoxes*36u+kV7Leaves*6u+36u;
constexpr uint32_t kV7StaticVerts=kV7HeroVerts+kV7FloorVerts+kV7WaterVerts+kV7ArchVerts;
constexpr uint32_t kV7SphereVerts=36u*18u*6u;
constexpr uint32_t kV7QualityBodies=4u;
constexpr uint32_t kV7QualityVerts=kV7StaticVerts+kV7QualityBodies*kV7SphereVerts;
constexpr uint32_t kV7StressSphereVerts=16u*8u*6u;
constexpr uint32_t kV7StaticInstances=20u;
constexpr uint32_t kV7MaxRtBodies=30u;
constexpr uint32_t kV7MaxInstances=kV7StaticInstances+kV7MaxRtBodies;

struct PushV7 {float yaw,pitch,aspect,cameraDistance;float preRotation,quality,rtEnabled,time;float wetness,exposure,heroMaterial,rtBudget;};
struct TextureV7{VkImage image=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;VkImageView view=VK_NULL_HANDLE;VkSampler sampler=VK_NULL_HANDLE;uint32_t width=0,height=0,mips=1;};
struct HeightV7{int w=0,h=0;std::vector<float> p;};

class VulkanStudioV7 final:public VulkanStudioV3{
public:
    VulkanStudioV7(ANativeWindow*w,AAssetManager*assets):VulkanStudioV3(w),assets_(assets){
        if(!assets_)throw std::runtime_error("Android AssetManager unavailable");
        deformDir_.fill({0,1,0});configureBodiesV7();deformDir_[2]={1.0f,.62f,.31f};cameraDistance_=7.5f;pitch_=0.14f;yaw_=-0.55f;
        logV7("Studio v7.2 · photoreal 120 Hz courtyard");
        logV7("safety · adaptive HWRT budget + mobile tile-aware workload");
        logV7("scene · worn PBR materials + collider-matched props + deep wet pavement");
        logV7("RT · selective ray-query contact shadows + local reflections");
    }
    ~VulkanStudioV7(){stop();}
    void start(){bool e=false;if(!running_.compare_exchange_strong(e,true))return;thread_=std::thread([this]{loopV7();});}
    void zoom(float s){VulkanStudioV3::zoom(s);cameraDistance_=std::clamp(cameraDistance_.load(),4.5f,11.0f);}
    void setRt(bool v){requestedRt_=v;logV7(v?"RT requested -> ON":"RT requested -> OFF (ray queries bypassed)");}
    void setStress(bool v){stress_=v;if(v&&requestedRt_.load())logV7("stress -> SAFE RT profile (ray budget capped; TLAS refit amortized)");else logV7(v?"stress -> ON":"stress -> OFF");}
    void setPhysics(bool v){physics_=v;logV7(v?"physics -> ON":"physics -> OFF");}
    void setQuality(int q){quality_=std::clamp(q,0,100);}
    void setWetness(float v){wetness_=std::clamp(v,0.0f,1.0f);logV7("wetness -> "+std::to_string(int(wetness_.load()*100)));}
    void setExposure(float v){exposure_=std::clamp(v,-1.5f,1.0f);logV7("exposure EV -> "+std::to_string(exposure_.load()));}
    void setHeroMaterial(int m){heroMaterial_=std::clamp(m,0,1);logV7(heroMaterial_.load()==0?"hero -> weathered concrete":"hero -> dark volcanic rock");}
    void setGummyBounce(float v){std::lock_guard<std::mutex>lock(bodyMutex_);if(bodies_.size()>1)bodies_[1].restitution=std::clamp(v,.30f,.96f);logV7Unlocked("gummy restitution -> "+std::to_string(bodies_[1].restitution));}
    void setLightColor(float r,float g,float b){std::lock_guard<std::mutex>lock(bodyMutex_);Vec3 c{std::clamp(r,.05f,1.f),std::clamp(g,.05f,1.f),std::clamp(b,.05f,1.f)};float m=std::max(c.x,std::max(c.y,c.z));if(m>1e-4f)c=c/m;deformDir_[2]=c;logV7Unlocked("light color -> "+std::to_string(int(c.x*255))+","+std::to_string(int(c.y*255))+","+std::to_string(int(c.z*255)));}
    void resetPhysics(){std::lock_guard<std::mutex>lock(bodyMutex_);initBodiesLocked();deformDir_.fill({0,1,0});configureBodiesLockedV7();deformDir_[2]={1.0f,.62f,.31f};grabbed_=-1;grabTargetVelocity_={};omega_.fill({});spin_.fill(0);logV7Unlocked("scene reset · grounded physics restored");}
    void grabStart(float nx,float ny){grabStartImpl(nx,ny,false);}
    void grabLightStart(float nx,float ny){grabStartImpl(nx,ny,true);}
    void grabMove(float nx,float ny){std::lock_guard<std::mutex>lock(bodyMutex_);if(grabbed_<0)return;Vec3 ro,rd;screenRayV7(nx,ny,ro,rd);Vec3 next=ro+rd*grabDepth_;auto now=Clock::now();float dt=std::chrono::duration<float>(now-grabLastUpdate_).count();if(dt>.0005f){Vec3 instant=(next-grabPrevTarget_)/dt;float s=len(instant);if(s>11.5f)instant=instant*(11.5f/s);grabTargetVelocity_=grabTargetVelocity_*.80f+instant*.20f;}grabPrevTarget_=next;grabTarget_=clampInsideV7(next,bodies_[grabbed_].r);grabLastUpdate_=now;if(!physics_.load()){Body&b=bodies_[grabbed_];b.p=grabTarget_;b.v={};omega_[grabbed_]={};}}
    void grabEnd(){std::lock_guard<std::mutex>lock(bodyMutex_);if(grabbed_>=0&&physics_.load()){bodies_[grabbed_].v+=grabTargetVelocity_*.42f;float s=len(bodies_[grabbed_].v);if(s>12.5f)bodies_[grabbed_].v=bodies_[grabbed_].v*(12.5f/s);}grabbed_=-1;grabTargetVelocity_={};}

private:
    void logV7(const std::string&s){__android_log_print(ANDROID_LOG_INFO,kTag,"%s",s.c_str());std::lock_guard<std::mutex>lock(logMutex_);logV7Unlocked(s);}
    void logV7Unlocked(const std::string&s){logs_.push_back(s);if(logs_.size()>260)logs_.erase(logs_.begin(),logs_.begin()+static_cast<std::ptrdiff_t>(logs_.size()-260));}
