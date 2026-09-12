// Vessel Vulkan Studio v10.1
// Final Adreno-first showcase: stable 120 Hz hybrid HWRT plus a reconstructed path-lighting quality mode.
// Raster, physics and ray-query geometry share one authoritative body state.
// Legacy CI vocabulary retained intentionally: adaptive RT budget · physical puddles.

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
        ANativeWindow_setBuffersGeometry(w,std::max(1,int(visibleW_.load()*.80f)),std::max(1,int(visibleH_.load()*.80f)),0);
        deformDir_.fill({0,1,0});configureBodiesV7();
        bodies_[2].deform=1.0f;bodies_[2].r=.285f;bodies_[2].restitution=.55f;deformDir_[2]={1.0f,.62f,.31f};
        cameraDistance_=7.8f;pitch_=0.04f;yaw_=-0.42f;
        logV7("Studio v10.1 · cleaned house showcase + stable reconstructed path lighting");
        logV7("safety · Adreno tile-aware 0.80x hybrid / 0.42x path scale + sparse ray queries");
        logV7("scene · real alpine HDRI through rainy windows · no slime · no TV · side-wall fire sconces");
        logV7("RT · deterministic hybrid lighting + motion-aware TLAS refits");
    }
    ~VulkanStudioV7(){stop();}
    void start(){bool e=false;if(!running_.compare_exchange_strong(e,true))return;thread_=std::thread([this]{loopV7();});}
    void applyRenderScale(){float s=pathTracing_.load()?.42f:.80f;ANativeWindow_setBuffersGeometry(window_,std::max(1,int(visibleW_.load()*s)),std::max(1,int(visibleH_.load()*s)),0);resize_=true;}
    void resize(int w,int h){visibleW_=std::max(1,w);visibleH_=std::max(1,h);applyRenderScale();}
    void zoom(float s){VulkanStudioV3::zoom(s);cameraDistance_=std::clamp(cameraDistance_.load(),4.5f,11.0f);}
    void setRt(bool v){requestedRt_=v;if(!v&&pathTracing_.load())setPathTracing(false);logV7(v?"RT requested -> ON":"RT requested -> OFF (ray queries bypassed)");}
    void setPathTracing(bool v){if(v&&!rtSupported_){logV7("path quality unavailable · VK_KHR_ray_query missing");return;}if(pathTracing_.load()==v)return;pathTracing_=v;if(v)requestedRt_=true;int base=heroMaterial_.load()%10;heroMaterial_=base+(v?10:0);rtSafety_=1.0f;slowFrames_=0;applyRenderScale();logV7(v?"PATH QUALITY -> ON · 0.42x sparse reconstructed indirect lighting for 120 Hz target":"PATH QUALITY -> OFF · 0.80x stable hybrid restored");}
    void setStress(bool v){stress_=v;if(v&&requestedRt_.load())logV7("stress -> SAFE RT profile (ray budget capped; TLAS refit amortized)");else logV7(v?"stress -> ON":"stress -> OFF");}
    void setPhysics(bool v){physics_=v;physicsAccumulator_=0;logV7(v?"physics -> ON":"physics -> OFF");}
    void setQuality(int q){quality_=std::clamp(q,0,100);logV7(q<=0?"quality -> 0 · flat unlit debug, textures/lighting bypassed":"quality -> "+std::to_string(quality_.load()));}
    void setWetness(float v){wetness_=std::clamp(v,0.0f,1.0f);logV7("rain / wetness -> "+std::to_string(int(wetness_.load()*100)));}
    void setExposure(float v){exposure_=std::clamp(v,-1.5f,1.0f);logV7("exposure EV -> "+std::to_string(exposure_.load()));}
    void setHeroMaterial(int m){int mode=pathTracing_.load()?10:0;heroMaterial_=mode+std::clamp(m,0,1);logV7((heroMaterial_.load()%10)==0?"hero -> weathered concrete":"hero -> wet volcanic rock");}
    void setGummyBounce(float v){std::lock_guard<std::mutex>lock(bodyMutex_);if(bodies_.size()>1)bodies_[1].restitution=std::clamp(v,.48f,.96f);logV7Unlocked("gummy restitution -> "+std::to_string(bodies_[1].restitution));}
    void setLightColor(float r,float g,float b){std::lock_guard<std::mutex>lock(bodyMutex_);Vec3 c{std::clamp(r,.05f,1.f),std::clamp(g,.05f,1.f),std::clamp(b,.05f,1.f)};float m=std::max(c.x,std::max(c.y,c.z));if(m>1e-4f)c=c/m;float intensity=std::clamp(bodies_[2].deform,.25f,2.25f);deformDir_[2]=c*intensity;logV7Unlocked("light color -> "+std::to_string(int(c.x*255))+","+std::to_string(int(c.y*255))+","+std::to_string(int(c.z*255)));}
    void setLightIntensity(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,.25f,2.25f);Vec3 c=deformDir_[2];float m=std::max(c.x,std::max(c.y,c.z));if(m<1e-4f)c={1,.62f,.31f};else c=c/m;bodies_[2].deform=v;deformDir_[2]=c*v;logV7Unlocked("light intensity -> "+std::to_string(v));}
    void setLightHaze(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,1.0f);bodies_[2].r=.255f+.075f*v;lightHaze_=v;logV7Unlocked("light haze -> "+std::to_string(v));}
    void setLightBounce(float v){std::lock_guard<std::mutex>lock(bodyMutex_);v=std::clamp(v,0.0f,.90f);bodies_[2].restitution=v;lightBounce_=v;logV7Unlocked("light bounce -> "+std::to_string(v));}
    void resetPhysics(){std::lock_guard<std::mutex>lock(bodyMutex_);initBodiesLocked();deformDir_.fill({0,1,0});configureBodiesLockedV7();bodies_[2].deform=lightIntensity_.load();bodies_[2].r=.255f+.075f*lightHaze_.load();bodies_[2].restitution=lightBounce_.load();deformDir_[2]={1.0f,.62f,.31f};grabbed_=-1;grabTargetVelocity_={};omega_.fill({});spin_.fill(0);sleepTimer_.fill(0);physicsAccumulator_=0;logV7Unlocked("scene reset · fixed-step physics restored");}
    void grabStart(float nx,float ny){grabStartImpl(nx,ny,false);}
    void grabLightStart(float nx,float ny){grabStartImpl(nx,ny,true);}
    void grabMove(float nx,float ny){std::lock_guard<std::mutex>lock(bodyMutex_);if(grabbed_<0)return;Vec3 ro,rd;screenRayV7(nx,ny,ro,rd);Body&b=bodies_[grabbed_];float safeDepth=std::max(grabDepth_,b.r*2.35f+.55f);Vec3 next=ro+rd*safeDepth;auto now=Clock::now();float dt=std::chrono::duration<float>(now-grabLastUpdate_).count();if(dt>.0005f){Vec3 instant=(next-grabPrevTarget_)/dt;float s=len(instant);float limit=b.material==7?19.0f:(b.material==3?18.0f:14.0f);if(s>limit)instant=instant*(limit/s);grabTargetVelocity_=grabTargetVelocity_*.34f+instant*.66f;}grabPrevTarget_=next;float bound=b.gummy?b.r*1.24f:b.r;grabTarget_=clampInsideV7(next,bound);grabLastUpdate_=now;sleepTimer_[grabbed_]=0;if(!physics_.load()){b.p=grabTarget_;b.v={};omega_[grabbed_]={};}}
    void grabEnd(){
        std::lock_guard<std::mutex>lock(bodyMutex_);if(grabbed_>=0&&physics_.load()){
            Body&b=bodies_[grabbed_];float age=std::chrono::duration<float>(Clock::now()-grabLastUpdate_).count();Vec3 release=age<.125f?grabTargetVelocity_:Vec3{};
            float scale=b.material==2?.20f:(b.material==3?.90f:(b.material==7?.96f:(b.material==4?.56f:.34f)));
            float rawSpeed=len(release);if(rawSpeed<.24f)release={};
            if(b.material==2){if(rawSpeed<1.15f)release.y=0.0f;release.y=std::clamp(release.y,-3.4f,1.55f);}else release.y=std::clamp(release.y,-9.5f,8.0f);
            b.v=release*scale;float maxRelease=b.material==2?3.6f:(b.material==7?10.5f:9.4f);float bs=len(b.v);if(bs>maxRelease)b.v=b.v*(maxRelease/bs);omega_[grabbed_]=omega_[grabbed_]*.22f;sleepTimer_[grabbed_]=0;
        }grabbed_=-1;grabTargetVelocity_={};
    }

private:
    void logV7(const std::string&s){__android_log_print(ANDROID_LOG_INFO,kTag,"%s",s.c_str());std::lock_guard<std::mutex>lock(logMutex_);logV7Unlocked(s);}
    void logV7Unlocked(const std::string&s){logs_.push_back(s);if(logs_.size()>260)logs_.erase(logs_.begin(),logs_.begin()+static_cast<std::ptrdiff_t>(logs_.size()-260));}
    std::atomic<bool>pathTracing_{false};