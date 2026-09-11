#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "native_studio_v3_vert_spv.h"
#include "native_studio_v3_frag_spv.h"
#include "native_studio_v3_rt_frag_spv.h"

namespace {
using Clock = std::chrono::steady_clock;
constexpr const char* kTag = "VesselVulkan";
constexpr int kBodyCount = 67;
constexpr int kStressCount = 64;
constexpr uint32_t kCubeVerts = 36;
constexpr uint32_t kFloorVerts = 6;
constexpr uint32_t kSphereVerts = 32u * 16u * 6u;
constexpr uint32_t kQualityVerts = kCubeVerts + kFloorVerts + kSphereVerts * 3u;
constexpr uint32_t kStressSphereVerts = 16u * 8u * 6u;
constexpr float kFloorY = -1.01f;
constexpr float kPi = 3.14159265358979323846f;

void checkVk(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(what) + " failed VkResult=" + std::to_string(r));
}

struct Vec3 { float x=0,y=0,z=0; };
Vec3 operator+(Vec3 a, Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 operator-(Vec3 a, Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 operator*(Vec3 a,float s){return {a.x*s,a.y*s,a.z*s};}
Vec3 operator/(Vec3 a,float s){return {a.x/s,a.y/s,a.z/s};}
Vec3& operator+=(Vec3& a,Vec3 b){a=a+b;return a;}
Vec3& operator-=(Vec3& a,Vec3 b){a=a-b;return a;}
float dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
float len(Vec3 a){return std::sqrt(std::max(dot(a,a),0.0f));}
Vec3 norm(Vec3 a){float l=len(a);return l>1e-6f?a/l:Vec3{0,1,0};}
Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}

struct alignas(16) BodyGpu {
    float posRad[4];
    float velMass[4];
    float extra[4];
    int32_t meta[4];
};

struct Body {
    Vec3 p{}, v{};
    float r=.4f, mass=1.0f, restitution=.45f, friction=.45f;
    int material=2;
    bool gummy=false;
    float deform=0.0f, deformV=0.0f;
};

struct PushConstants {
    float yaw,pitch,aspect,cameraDistance,preRotation,quality,rtEnabled,time;
};

struct Buffer {
    VkBuffer buffer=VK_NULL_HANDLE;
    VkDeviceMemory memory=VK_NULL_HANDLE;
    VkDeviceSize size=0;
    void* mapped=nullptr;
};

struct Accel {
    VkAccelerationStructureKHR handle=VK_NULL_HANDLE;
    Buffer storage{};
    VkDeviceAddress address=0;
};

class VulkanStudioV3 {
public:
    explicit VulkanStudioV3(ANativeWindow* w):window_(w){
        if(!w)throw std::runtime_error("null Android window");
        visibleW_=std::max(1,ANativeWindow_getWidth(w));
        visibleH_=std::max(1,ANativeWindow_getHeight(w));
        initBodies();
        log("Studio v3 surface attached");
    }
    ~VulkanStudioV3(){stop();if(window_)ANativeWindow_release(window_);}

    void start(){bool e=false;if(!running_.compare_exchange_strong(e,true))return;thread_=std::thread([this]{loop();});}
    void stop(){if(!running_.exchange(false))return;if(thread_.joinable())thread_.join();}
    void resize(int w,int h){visibleW_=std::max(1,w);visibleH_=std::max(1,h);resize_=true;}
    void rotate(float y,float p){yaw_+=y*0.01745329252f;pitch_=std::clamp(pitch_.load()+p*0.01745329252f,-1.28f,1.28f);}
    void zoom(float s){if(s>.01f)cameraDistance_=std::clamp(cameraDistance_.load()/s,3.6f,14.0f);}
    void setStress(bool v){if(stress_.exchange(v)!=v)log(v?"mode -> STRESS":"mode -> QUALITY");}
    void setPhysics(bool v){if(physics_.exchange(v)!=v)log(v?"physics -> ON":"physics -> OFF");}
    void setRt(bool v){requestedRt_=v;log(v?"ray tracing requested -> ON":"ray tracing -> OFF");}
    void setQuality(int q){q=std::clamp(q,0,100);if(quality_.exchange(q)!=q)log("graphics quality -> "+std::to_string(q));}
    void resetPhysics(){std::lock_guard<std::mutex>l(bodyMutex_);initBodiesLocked();grabbed_=-1;log("physics reset");}

    void grabStart(float nx,float ny){
        std::lock_guard<std::mutex>l(bodyMutex_);
        Vec3 ro,rd;screenRay(nx,ny,ro,rd);
        float best=1e9f;int bi=-1;
        for(int i=0;i<activeBodies();++i){
            Vec3 oc=ro-bodies_[i].p;float b=dot(oc,rd);float c=dot(oc,oc)-bodies_[i].r*bodies_[i].r;
            float d=b*b-c;if(d<0)continue;float t=-b-std::sqrt(d);if(t>.05f&&t<best){best=t;bi=i;}
        }
        grabbed_=bi;grabDepth_=best;if(bi>=0){grabTarget_=ro+rd*best;log("grab body "+std::to_string(bi));}
    }
    void grabMove(float nx,float ny){std::lock_guard<std::mutex>l(bodyMutex_);if(grabbed_<0)return;Vec3 ro,rd;screenRay(nx,ny,ro,rd);grabTarget_=ro+rd*grabDepth_;}
    void grabEnd(){std::lock_guard<std::mutex>l(bodyMutex_);grabbed_=-1;}

    std::string status()const{std::lock_guard<std::mutex>l(statusMutex_);return status_;}
    std::string logs()const{std::lock_guard<std::mutex>l(logMutex_);std::ostringstream o;for(size_t i=0;i<logs_.size();++i){if(i)o<<'\n';o<<logs_[i];}return o.str();}

private:
    void log(const std::string&s){__android_log_print(ANDROID_LOG_INFO,kTag,"%s",s.c_str());std::lock_guard<std::mutex>l(logMutex_);logs_.push_back(s);if(logs_.size()>18)logs_.erase(logs_.begin());}
    void setStatus(std::string s){std::lock_guard<std::mutex>l(statusMutex_);status_=std::move(s);}

    void initBodies(){std::lock_guard<std::mutex>l(bodyMutex_);initBodiesLocked();}
    void initBodiesLocked(){
        bodies_.assign(kBodyCount,{});
        auto set=[&](int i,Vec3 p,float r,int m,float rest,float fric,bool gummy){Body&b=bodies_[i];b.p=p;b.r=r;b.material=m;b.restitution=rest;b.friction=fric;b.gummy=gummy;b.mass=std::max(.18f,4.18879f*r*r*r*(m==2?3.2f:1.15f));};
        set(0,{-2.35f,kFloorY+.82f,-.30f},.82f,2,.34f,.36f,false);
        set(1,{ 2.25f,kFloorY+.72f, .20f},.72f,3,.80f,.28f,true);
        set(2,{ .15f,kFloorY+.58f,-2.35f},.58f,4,.22f,.72f,false);
        for(int i=0;i<kStressCount;i++){
            int gx=i%8,gz=i/8;float r=.31f+.035f*float((i*7)%5);
            float x=(float(gx)-3.5f)*1.08f;float z=-4.5f-float(gz)*.91f;
            float y=kFloorY+r+((i%4)==0?float((i/8)%3)*.7f:0.0f);
            set(3+i,{x,y,z},r,2+(i%3),.42f+.08f*float(i%3),.38f,false);
        }
    }

    int activeBodies()const{
        int q=quality_.load();if(q<12)return 0;if(!stress_.load())return 3;
        int extras=8+(q*56)/100;return std::clamp(3+extras,3,kBodyCount);
    }

    void physicsStep(float dt){
        if(!physics_.load())return;
        std::lock_guard<std::mutex>l(bodyMutex_);
        int n=activeBodies();if(n<=0)return;
        int sub=3;float h=std::min(dt,.033f)/float(sub);
        for(int step=0;step<sub;step++){
            for(int i=0;i<n;i++){
                Body&b=bodies_[i];
                b.v.y-=9.81f*h;
                if(i==grabbed_){Vec3 e=grabTarget_-b.p;Vec3 force=e*42.0f-b.v*8.5f;b.v+=force*(h/std::max(b.mass,.1f));}
                b.p+=b.v*h;
                float penetration=kFloorY-(b.p.y-b.r);
                if(penetration>0){
                    b.p.y+=penetration;
                    float impact=std::max(0.0f,-b.v.y);
                    if(b.v.y<0)b.v.y=-b.v.y*b.restitution;
                    float damp=std::max(0.0f,1.0f-b.friction*h*9.0f);b.v.x*=damp;b.v.z*=damp;
                    if(b.gummy&&impact>.15f)b.deformV+=impact*.20f;
                }
                // Collision against the fixed cube [-1,1].
                Vec3 cp{std::clamp(b.p.x,-1.0f,1.0f),std::clamp(b.p.y,-1.0f,1.0f),std::clamp(b.p.z,-1.0f,1.0f)};
                Vec3 d=b.p-cp;float dl=len(d);
                if(dl<b.r&&dl>1e-5f){Vec3 nn=d/dl;float pen=b.r-dl;b.p+=nn*pen;float vn=dot(b.v,nn);if(vn<0)b.v-=nn*((1.0f+b.restitution)*vn);if(b.gummy)b.deformV+=std::abs(vn)*.15f;}
            }
            for(int i=0;i<n;i++)for(int j=i+1;j<n;j++){
                Body&a=bodies_[i],&b=bodies_[j];Vec3 d=b.p-a.p;float dl=len(d),rr=a.r+b.r;if(dl>=rr||dl<1e-5f)continue;
                Vec3 nn=d/dl;float pen=rr-dl;float ia=1.0f/std::max(a.mass,.1f),ib=1.0f/std::max(b.mass,.1f),sum=ia+ib;
                a.p-=nn*(pen*(ia/sum));b.p+=nn*(pen*(ib/sum));float rv=dot(b.v-a.v,nn);
                if(rv<0){float e=std::min(a.restitution,b.restitution);float impulse=-(1.0f+e)*rv/sum;Vec3 J=nn*impulse;a.v-=J*ia;b.v+=J*ib;if(a.gummy)a.deformV+=std::abs(impulse)*ia*.08f;if(b.gummy)b.deformV+=std::abs(impulse)*ib*.08f;}
            }
            for(int i=0;i<n;i++)if(bodies_[i].gummy){Body&b=bodies_[i];float k=46.0f,c=9.5f;b.deformV+=(-k*b.deform-c*b.deformV)*h;b.deform+=b.deformV*h;b.deform=std::clamp(b.deform,-.14f,.32f);}
        }
    }

    void screenRay(float nx,float ny,Vec3&ro,Vec3&rd)const{
        float yaw=yaw_.load(),pitch=pitch_.load(),cp=std::cos(pitch);
        ro={cameraDistance_.load()*cp*std::sin(yaw),cameraDistance_.load()*std::sin(pitch),cameraDistance_.load()*cp*std::cos(yaw)};
        Vec3 f=norm(ro*-1.0f),r=norm(cross(f,{0,1,0})),u=norm(cross(r,f));
        float x=nx*2.0f-1.0f,y=1.0f-ny*2.0f,aspect=float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load()));
        float tanHalf=std::tan(43.0f*kPi/360.0f);rd=norm(f+r*(x*aspect*tanHalf)+u*(y*tanHalf));
    }

    void updateBodyGpu(){
        if(!bodyMapped_)return;std::lock_guard<std::mutex>l(bodyMutex_);auto*out=reinterpret_cast<BodyGpu*>(bodyMapped_);
        for(int i=0;i<kBodyCount;i++){const Body&b=bodies_[i];out[i]={{b.p.x,b.p.y,b.p.z,b.r},{b.v.x,b.v.y,b.v.z,b.mass},{b.deform,b.restitution,b.friction,0},{b.material,b.gummy?1:0,0,0}};}
    }

    bool hasExt(VkPhysicalDevice p,const char*name){uint32_t n=0;vkEnumerateDeviceExtensionProperties(p,nullptr,&n,nullptr);std::vector<VkExtensionProperties>e(n);vkEnumerateDeviceExtensionProperties(p,nullptr,&n,e.data());for(auto&x:e)if(std::strcmp(x.extensionName,name)==0)return true;return false;}
    bool queueFamily(VkPhysicalDevice p,uint32_t*out){uint32_t n=0;vkGetPhysicalDeviceQueueFamilyProperties(p,&n,nullptr);std::vector<VkQueueFamilyProperties>q(n);vkGetPhysicalDeviceQueueFamilyProperties(p,&n,q.data());for(uint32_t i=0;i<n;i++){VkBool32 present=false;vkGetPhysicalDeviceSurfaceSupportKHR(p,i,surface_,&present);if((q[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&present){*out=i;return true;}}return false;}
    uint32_t memType(uint32_t bits,VkMemoryPropertyFlags req){VkPhysicalDeviceMemoryProperties m{};vkGetPhysicalDeviceMemoryProperties(physical_,&m);for(uint32_t i=0;i<m.memoryTypeCount;i++)if((bits&(1u<<i))&&(m.memoryTypes[i].propertyFlags&req)==req)return i;throw std::runtime_error("memory type not found");}

    Buffer createBuffer(VkDeviceSize size,VkBufferUsageFlags usage,VkMemoryPropertyFlags props,bool address=false){
        Buffer b{};b.size=size;VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=size;bi.usage=usage;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;checkVk(vkCreateBuffer(device_,&bi,nullptr,&b.buffer),"vkCreateBuffer");
        VkMemoryRequirements mr{};vkGetBufferMemoryRequirements(device_,b.buffer,&mr);VkMemoryAllocateFlagsInfo fi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO};fi.flags=address?VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT:0;
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.pNext=address?&fi:nullptr;ai.allocationSize=mr.size;ai.memoryTypeIndex=memType(mr.memoryTypeBits,props);checkVk(vkAllocateMemory(device_,&ai,nullptr,&b.memory),"vkAllocateMemory");checkVk(vkBindBufferMemory(device_,b.buffer,b.memory,0),"vkBindBufferMemory");
        if(props&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)checkVk(vkMapMemory(device_,b.memory,0,size,0,&b.mapped),"vkMapMemory");return b;
    }
    void destroyBuffer(Buffer&b){if(!device_)return;if(b.mapped)vkUnmapMemory(device_,b.memory);if(b.buffer)vkDestroyBuffer(device_,b.buffer,nullptr);if(b.memory)vkFreeMemory(device_,b.memory,nullptr);b={};}
    VkDeviceAddress addr(VkBuffer b){VkBufferDeviceAddressInfo i{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};i.buffer=b;return vkGetBufferDeviceAddress(device_,&i);}

    VkCommandBuffer beginOne(){VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=pool_;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;VkCommandBuffer c;checkVk(vkAllocateCommandBuffers(device_,&ai,&c),"alloc one-time");VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;checkVk(vkBeginCommandBuffer(c,&bi),"begin one-time");return c;}
    void endOne(VkCommandBuffer c){checkVk(vkEndCommandBuffer(c),"end one-time");VkSubmitInfo s{VK_STRUCTURE_TYPE_SUBMIT_INFO};s.commandBufferCount=1;s.pCommandBuffers=&c;checkVk(vkQueueSubmit(queue_,1,&s,VK_NULL_HANDLE),"submit one-time");checkVk(vkQueueWaitIdle(queue_),"wait one-time");vkFreeCommandBuffers(device_,pool_,1,&c);}

    std::vector<Vec3> cubeTriangles(){
        std::vector<Vec3>v;auto q=[&](Vec3 a,Vec3 b,Vec3 c,Vec3 d){v.insert(v.end(),{a,b,c,a,c,d});};
        q({-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1});q({1,-1,-1},{-1,-1,-1},{-1,1,-1},{1,1,-1});
        q({1,-1,1},{1,-1,-1},{1,1,-1},{1,1,1});q({-1,-1,-1},{-1,-1,1},{-1,1,1},{-1,1,-1});
        q({-1,1,1},{1,1,1},{1,1,-1},{-1,1,-1});q({-1,-1,-1},{1,-1,-1},{1,-1,1},{-1,-1,1});return v;
    }
    std::vector<Vec3> floorTriangles(){return {{-1,0,-1},{1,0,-1},{1,0,1},{-1,0,-1},{1,0,1},{-1,0,1}};}
    std::vector<Vec3> sphereTriangles(int su=16,int sv=8){std::vector<Vec3>v;auto pt=[](float u,float vv){float th=u*2*kPi,ph=vv*kPi,sp=std::sin(ph);return Vec3{std::cos(th)*sp,std::cos(ph),std::sin(th)*sp};};for(int y=0;y<sv;y++)for(int x=0;x<su;x++){float u0=float(x)/su,u1=float(x+1)/su,v0=float(y)/sv,v1=float(y+1)/sv;Vec3 a=pt(u0,v0),b=pt(u1,v0),c=pt(u1,v1),d=pt(u0,v1);v.insert(v.end(),{a,b,c,a,c,d});}return v;}

    Accel buildBlas(const std::vector<Vec3>&verts,Buffer&vertexKeep){
        vertexKeep=createBuffer(sizeof(Vec3)*verts.size(),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,true);std::memcpy(vertexKeep.mapped,verts.data(),sizeof(Vec3)*verts.size());
        VkAccelerationStructureGeometryTrianglesDataKHR tri{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR};tri.vertexFormat=VK_FORMAT_R32G32B32_SFLOAT;tri.vertexData.deviceAddress=addr(vertexKeep.buffer);tri.vertexStride=sizeof(Vec3);tri.maxVertex=uint32_t(verts.size());tri.indexType=VK_INDEX_TYPE_NONE_KHR;
        VkAccelerationStructureGeometryKHR g{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};g.geometryType=VK_GEOMETRY_TYPE_TRIANGLES_KHR;g.flags=VK_GEOMETRY_OPAQUE_BIT_KHR;g.geometry.triangles=tri;
        uint32_t prim=uint32_t(verts.size()/3);VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};info.type=VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;info.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;info.geometryCount=1;info.pGeometries=&g;
        VkAccelerationStructureBuildSizesInfoKHR sz{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};fpGetBuildSizes_(device_,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,&info,&prim,&sz);
        Accel a{};a.storage=createBuffer(sz.accelerationStructureSize,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,false);VkAccelerationStructureCreateInfoKHR ci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};ci.buffer=a.storage.buffer;ci.size=sz.accelerationStructureSize;ci.type=VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;checkVk(fpCreateAS_(device_,&ci,nullptr,&a.handle),"create BLAS");
        Buffer scratch=createBuffer(sz.buildScratchSize,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,true);info.dstAccelerationStructure=a.handle;info.scratchData.deviceAddress=addr(scratch.buffer);VkAccelerationStructureBuildRangeInfoKHR range{};range.primitiveCount=prim;const VkAccelerationStructureBuildRangeInfoKHR*pr=&range;VkCommandBuffer c=beginOne();fpCmdBuildAS_(c,1,&info,&pr);endOne(c);destroyBuffer(scratch);
        VkAccelerationStructureDeviceAddressInfoKHR di{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR};di.accelerationStructure=a.handle;a.address=fpGetASAddress_(device_,&di);return a;
    }

    void destroyAccel(Accel&a){if(a.handle)fpDestroyAS_(device_,a.handle,nullptr);a.handle=VK_NULL_HANDLE;destroyBuffer(a.storage);a.address=0;}

    VkTransformMatrixKHR transform(Vec3 p,float sx,float sy,float sz){VkTransformMatrixKHR t{{{sx,0,0,p.x},{0,sy,0,p.y},{0,0,sz,p.z}}};return t;}

    void createRtScene(){
        cubeAs_=buildBlas(cubeTriangles(),cubeGeom_);floorAs_=buildBlas(floorTriangles(),floorGeom_);sphereAs_=buildBlas(sphereTriangles(),sphereGeom_);
        instanceBuffer_=createBuffer(sizeof(VkAccelerationStructureInstanceKHR)*(kBodyCount+2),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,true);
        VkAccelerationStructureGeometryInstancesDataKHR id{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR};id.data.deviceAddress=addr(instanceBuffer_.buffer);VkAccelerationStructureGeometryKHR g{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};g.geometryType=VK_GEOMETRY_TYPE_INSTANCES_KHR;g.geometry.instances=id;
        uint32_t count=kBodyCount+2;VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};info.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;info.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR|VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;info.geometryCount=1;info.pGeometries=&g;
        VkAccelerationStructureBuildSizesInfoKHR sz{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};fpGetBuildSizes_(device_,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,&info,&count,&sz);
        tlas_.storage=createBuffer(sz.accelerationStructureSize,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,false);VkAccelerationStructureCreateInfoKHR ci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};ci.buffer=tlas_.storage.buffer;ci.size=sz.accelerationStructureSize;ci.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;checkVk(fpCreateAS_(device_,&ci,nullptr,&tlas_.handle),"create TLAS");
        tlasScratch_=createBuffer(std::max(sz.buildScratchSize,sz.updateScratchSize),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,true);tlasGeometry_=g;tlasBuilt_=false;log("HWRT acceleration structures ready");
    }

    void updateTlasInstances(){
        auto*dst=reinterpret_cast<VkAccelerationStructureInstanceKHR*>(instanceBuffer_.mapped);std::memset(dst,0,sizeof(VkAccelerationStructureInstanceKHR)*(kBodyCount+2));
        dst[0].transform=transform({0,kFloorY,0},8,1,8);dst[0].instanceCustomIndex=0;dst[0].mask=0xff;dst[0].instanceShaderBindingTableRecordOffset=0;dst[0].flags=VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;dst[0].accelerationStructureReference=floorAs_.address;
        dst[1].transform=transform({0,0,0},1,1,1);dst[1].instanceCustomIndex=1;dst[1].mask=0xff;dst[1].flags=VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;dst[1].accelerationStructureReference=cubeAs_.address;
        std::lock_guard<std::mutex>l(bodyMutex_);int active=activeBodies();for(int i=0;i<kBodyCount;i++){const Body&b=bodies_[i];auto&ins=dst[i+2];ins.transform=transform(b.p,b.r,b.r,b.r);ins.instanceCustomIndex=i+2;ins.mask=i<active?0xff:0;ins.flags=VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;ins.accelerationStructureReference=sphereAs_.address;}
    }

    void cmdUpdateTlas(VkCommandBuffer cmd){
        updateTlasInstances();VkAccelerationStructureGeometryInstancesDataKHR id{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR};id.data.deviceAddress=addr(instanceBuffer_.buffer);tlasGeometry_.geometry.instances=id;
        VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};info.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;info.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR|VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;info.mode=tlasBuilt_?VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR:VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;info.srcAccelerationStructure=tlasBuilt_?tlas_.handle:VK_NULL_HANDLE;info.dstAccelerationStructure=tlas_.handle;info.geometryCount=1;info.pGeometries=&tlasGeometry_;info.scratchData.deviceAddress=addr(tlasScratch_.buffer);
        VkAccelerationStructureBuildRangeInfoKHR range{};range.primitiveCount=kBodyCount+2;const VkAccelerationStructureBuildRangeInfoKHR*pr=&range;fpCmdBuildAS_(cmd,1,&info,&pr);tlasBuilt_=true;
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;barrier.dstAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }

    void initVulkan(){
        const char* ie[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};ai.pApplicationName="Vessel Vulkan Studio v3";ai.apiVersion=VK_API_VERSION_1_2;VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ii.pApplicationInfo=&ai;ii.enabledExtensionCount=2;ii.ppEnabledExtensionNames=ie;checkVk(vkCreateInstance(&ii,nullptr,&instance_),"vkCreateInstance");
        VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};si.window=window_;checkVk(vkCreateAndroidSurfaceKHR(instance_,&si,nullptr,&surface_),"vkCreateAndroidSurfaceKHR");
        uint32_t dn=0;checkVk(vkEnumeratePhysicalDevices(instance_,&dn,nullptr),"physical count");std::vector<VkPhysicalDevice>d(dn);checkVk(vkEnumeratePhysicalDevices(instance_,&dn,d.data()),"physical list");
        for(auto p:d){uint32_t q;if(hasExt(p,VK_KHR_SWAPCHAIN_EXTENSION_NAME)&&queueFamily(p,&q)){physical_=p;queueFamily_=q;break;}}if(!physical_)throw std::runtime_error("No graphics/present Vulkan device");
        VkPhysicalDeviceProperties prop{};vkGetPhysicalDeviceProperties(physical_,&prop);gpuName_=prop.deviceName;timestampPeriod_=prop.limits.timestampPeriod;apiVersion_=prop.apiVersion;
        bool exts=hasExt(physical_,VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)&&hasExt(physical_,VK_KHR_RAY_QUERY_EXTENSION_NAME)&&hasExt(physical_,VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME)&&hasExt(physical_,VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        VkPhysicalDeviceBufferDeviceAddressFeatures bda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};VkPhysicalDeviceAccelerationStructureFeaturesKHR asf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};VkPhysicalDeviceRayQueryFeaturesKHR rq{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR};rq.pNext=&asf;asf.pNext=&bda;VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};f2.pNext=&rq;vkGetPhysicalDeviceFeatures2(physical_,&f2);rtSupported_=exts&&rq.rayQuery&&asf.accelerationStructure&&bda.bufferDeviceAddress;
        log("GPU: "+gpuName_);log("Vulkan API "+std::to_string(VK_VERSION_MAJOR(apiVersion_))+"."+std::to_string(VK_VERSION_MINOR(apiVersion_)));log(std::string("VK_KHR_ray_query: ")+(rtSupported_?"YES":"NO"));
        float prio=1;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=queueFamily_;qi.queueCount=1;qi.pQueuePriorities=&prio;
        std::vector<const char*> de{VK_KHR_SWAPCHAIN_EXTENSION_NAME};VkPhysicalDeviceBufferDeviceAddressFeatures ebda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};VkPhysicalDeviceAccelerationStructureFeaturesKHR eas{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};VkPhysicalDeviceRayQueryFeaturesKHR erq{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR};
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;if(rtSupported_){de.insert(de.end(),{VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,VK_KHR_RAY_QUERY_EXTENSION_NAME,VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME});erq.rayQuery=VK_TRUE;eas.accelerationStructure=VK_TRUE;ebda.bufferDeviceAddress=VK_TRUE;erq.pNext=&eas;eas.pNext=&ebda;di.pNext=&erq;}di.enabledExtensionCount=uint32_t(de.size());di.ppEnabledExtensionNames=de.data();checkVk(vkCreateDevice(physical_,&di,nullptr,&device_),"vkCreateDevice");vkGetDeviceQueue(device_,queueFamily_,0,&queue_);
        VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pi.queueFamilyIndex=queueFamily_;checkVk(vkCreateCommandPool(device_,&pi,nullptr,&pool_),"command pool");VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};cai.commandPool=pool_;cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;cai.commandBufferCount=1;checkVk(vkAllocateCommandBuffers(device_,&cai,&cmd_),"command buffer");
        if(rtSupported_){fpCreateAS_=(PFN_vkCreateAccelerationStructureKHR)vkGetDeviceProcAddr(device_,"vkCreateAccelerationStructureKHR");fpDestroyAS_=(PFN_vkDestroyAccelerationStructureKHR)vkGetDeviceProcAddr(device_,"vkDestroyAccelerationStructureKHR");fpGetBuildSizes_=(PFN_vkGetAccelerationStructureBuildSizesKHR)vkGetDeviceProcAddr(device_,"vkGetAccelerationStructureBuildSizesKHR");fpCmdBuildAS_=(PFN_vkCmdBuildAccelerationStructuresKHR)vkGetDeviceProcAddr(device_,"vkCmdBuildAccelerationStructuresKHR");fpGetASAddress_=(PFN_vkGetAccelerationStructureDeviceAddressKHR)vkGetDeviceProcAddr(device_,"vkGetAccelerationStructureDeviceAddressKHR");if(!fpCreateAS_||!fpDestroyAS_||!fpGetBuildSizes_||!fpCmdBuildAS_||!fpGetASAddress_){rtSupported_=false;log("HWRT entry points missing; raster fallback");}}
        bodyBuffer_=createBuffer(sizeof(BodyGpu)*kBodyCount,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,false);bodyMapped_=bodyBuffer_.mapped;updateBodyGpu();
        if(rtSupported_)createRtScene();createDescriptors();createSync();recreateSwapchain();
    }

    VkFormat depthFormat(){for(VkFormat f:{VK_FORMAT_D32_SFLOAT,VK_FORMAT_D24_UNORM_S8_UINT,VK_FORMAT_D16_UNORM}){VkFormatProperties p{};vkGetPhysicalDeviceFormatProperties(physical_,f,&p);if(p.optimalTilingFeatures&VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)return f;}throw std::runtime_error("No depth format");}
    bool quarter(VkSurfaceTransformFlagBitsKHR t){return t==VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR||t==VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;}
    float rotCode(VkSurfaceTransformFlagBitsKHR t){if(t==VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR)return 1;if(t==VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR)return 2;if(t==VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR)return 3;return 0;}
    const char*rotName(VkSurfaceTransformFlagBitsKHR t){if(t==VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR)return"rot90";if(t==VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR)return"rot180";if(t==VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR)return"rot270";return"identity";}

    void createDescriptors(){
        std::vector<VkDescriptorSetLayoutBinding>b;VkDescriptorSetLayoutBinding sb{};sb.binding=0;sb.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;sb.descriptorCount=1;sb.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;b.push_back(sb);if(rtSupported_){VkDescriptorSetLayoutBinding ab{};ab.binding=1;ab.descriptorType=VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;ab.descriptorCount=1;ab.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;b.push_back(ab);}VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};li.bindingCount=uint32_t(b.size());li.pBindings=b.data();checkVk(vkCreateDescriptorSetLayout(device_,&li,nullptr,&descLayout_),"descriptor layout");
        std::vector<VkDescriptorPoolSize> ps{{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1}};if(rtSupported_)ps.push_back({VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,1});VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dpi.maxSets=1;dpi.poolSizeCount=uint32_t(ps.size());dpi.pPoolSizes=ps.data();checkVk(vkCreateDescriptorPool(device_,&dpi,nullptr,&descPool_),"descriptor pool");VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};dai.descriptorPool=descPool_;dai.descriptorSetCount=1;dai.pSetLayouts=&descLayout_;checkVk(vkAllocateDescriptorSets(device_,&dai,&descSet_),"descriptor set");
        VkDescriptorBufferInfo db{bodyBuffer_.buffer,0,bodyBuffer_.size};VkWriteDescriptorSet w0{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};w0.dstSet=descSet_;w0.dstBinding=0;w0.descriptorCount=1;w0.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;w0.pBufferInfo=&db;std::vector<VkWriteDescriptorSet>w{w0};VkWriteDescriptorSetAccelerationStructureKHR ai{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR};VkWriteDescriptorSet wa{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};if(rtSupported_){ai.accelerationStructureCount=1;ai.pAccelerationStructures=&tlas_.handle;wa.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;wa.pNext=&ai;wa.dstSet=descSet_;wa.dstBinding=1;wa.descriptorCount=1;wa.descriptorType=VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;w.push_back(wa);}vkUpdateDescriptorSets(device_,uint32_t(w.size()),w.data(),0,nullptr);
    }

    void createSync(){VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};checkVk(vkCreateSemaphore(device_,&si,nullptr,&imageAvail_),"sem");checkVk(vkCreateSemaphore(device_,&si,nullptr,&renderDone_),"sem");VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;checkVk(vkCreateFence(device_,&fi,nullptr,&fence_),"fence");VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};qi.queryType=VK_QUERY_TYPE_TIMESTAMP;qi.queryCount=2;if(vkCreateQueryPool(device_,&qi,nullptr,&queryPool_)==VK_SUCCESS)timestamps_=true;}

    void createDepth(){depthFmt_=depthFormat();VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.extent={extent_.width,extent_.height,1};ii.mipLevels=1;ii.arrayLayers=1;ii.format=depthFmt_;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;ii.samples=VK_SAMPLE_COUNT_1_BIT;checkVk(vkCreateImage(device_,&ii,nullptr,&depth_),"depth image");VkMemoryRequirements mr{};vkGetImageMemoryRequirements(device_,depth_,&mr);VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=mr.size;ai.memoryTypeIndex=memType(mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);checkVk(vkAllocateMemory(device_,&ai,nullptr,&depthMem_),"depth mem");checkVk(vkBindImageMemory(device_,depth_,depthMem_,0),"depth bind");VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=depth_;vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=depthFmt_;vi.subresourceRange.aspectMask=VK_IMAGE_ASPECT_DEPTH_BIT;vi.subresourceRange.levelCount=1;vi.subresourceRange.layerCount=1;checkVk(vkCreateImageView(device_,&vi,nullptr,&depthView_),"depth view");}

    VkShaderModule shader(const uint32_t*c,size_t n){VkShaderModuleCreateInfo i{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};i.codeSize=n;i.pCode=c;VkShaderModule m;checkVk(vkCreateShaderModule(device_,&i,nullptr,&m),"shader");return m;}

    void createPipeline(){
        VkAttachmentDescription ca{};ca.format=swapFmt_;ca.samples=VK_SAMPLE_COUNT_1_BIT;ca.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;ca.storeOp=VK_ATTACHMENT_STORE_OP_STORE;ca.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;ca.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;VkAttachmentDescription da{};da.format=depthFmt_;da.samples=VK_SAMPLE_COUNT_1_BIT;da.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;da.storeOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;da.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;da.finalLayout=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;std::array<VkAttachmentDescription,2>a{ca,da};VkAttachmentReference cr{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},dr{1,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};VkSubpassDescription sp{};sp.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sp.colorAttachmentCount=1;sp.pColorAttachments=&cr;sp.pDepthStencilAttachment=&dr;VkRenderPassCreateInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};ri.attachmentCount=2;ri.pAttachments=a.data();ri.subpassCount=1;ri.pSubpasses=&sp;checkVk(vkCreateRenderPass(device_,&ri,nullptr,&renderPass_),"render pass");
        VkShaderModule vs=shader(native_studio_v3_vert_spv,native_studio_v3_vert_spv_size);VkShaderModule fs=rtSupported_?shader(native_studio_v3_rt_frag_spv,native_studio_v3_rt_frag_spv_size):shader(native_studio_v3_frag_spv,native_studio_v3_frag_spv_size);VkPipelineShaderStageCreateInfo ss[2]={{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};ss[0].stage=VK_SHADER_STAGE_VERTEX_BIT;ss[0].module=vs;ss[0].pName="main";ss[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;ss[1].module=fs;ss[1].pName="main";
        VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vp.viewportCount=1;vp.scissorCount=1;VkDynamicState ds[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};VkPipelineDynamicStateCreateInfo dy{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};dy.dynamicStateCount=2;dy.pDynamicStates=ds;VkPipelineRasterizationStateCreateInfo ra{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};ra.polygonMode=VK_POLYGON_MODE_FILL;ra.cullMode=VK_CULL_MODE_NONE;ra.lineWidth=1;VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;VkPipelineDepthStencilStateCreateInfo dep{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};dep.depthTestEnable=VK_TRUE;dep.depthWriteEnable=VK_TRUE;dep.depthCompareOp=VK_COMPARE_OP_LESS;VkPipelineColorBlendAttachmentState ba{};ba.colorWriteMask=0xf;VkPipelineColorBlendStateCreateInfo bl{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};bl.attachmentCount=1;bl.pAttachments=&ba;VkPushConstantRange pr{};pr.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;pr.size=sizeof(PushConstants);VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pli.setLayoutCount=1;pli.pSetLayouts=&descLayout_;pli.pushConstantRangeCount=1;pli.pPushConstantRanges=&pr;checkVk(vkCreatePipelineLayout(device_,&pli,nullptr,&layout_),"pipeline layout");VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};gi.stageCount=2;gi.pStages=ss;gi.pVertexInputState=&vin;gi.pInputAssemblyState=&ia;gi.pViewportState=&vp;gi.pRasterizationState=&ra;gi.pMultisampleState=&ms;gi.pDepthStencilState=&dep;gi.pColorBlendState=&bl;gi.pDynamicState=&dy;gi.layout=layout_;gi.renderPass=renderPass_;checkVk(vkCreateGraphicsPipelines(device_,VK_NULL_HANDLE,1,&gi,nullptr,&pipeline_),"pipeline");vkDestroyShaderModule(device_,vs,nullptr);vkDestroyShaderModule(device_,fs,nullptr);log(rtSupported_?"hybrid raster + ray-query pipeline ready":"PBR raster pipeline ready (RT unavailable)");
    }

    void destroySwap(){for(auto f:fbs_)if(f)vkDestroyFramebuffer(device_,f,nullptr);fbs_.clear();if(pipeline_)vkDestroyPipeline(device_,pipeline_,nullptr);pipeline_=VK_NULL_HANDLE;if(layout_)vkDestroyPipelineLayout(device_,layout_,nullptr);layout_=VK_NULL_HANDLE;if(renderPass_)vkDestroyRenderPass(device_,renderPass_,nullptr);renderPass_=VK_NULL_HANDLE;if(depthView_)vkDestroyImageView(device_,depthView_,nullptr);depthView_=VK_NULL_HANDLE;if(depth_)vkDestroyImage(device_,depth_,nullptr);depth_=VK_NULL_HANDLE;if(depthMem_)vkFreeMemory(device_,depthMem_,nullptr);depthMem_=VK_NULL_HANDLE;for(auto v:views_)if(v)vkDestroyImageView(device_,v,nullptr);views_.clear();if(swap_)vkDestroySwapchainKHR(device_,swap_,nullptr);swap_=VK_NULL_HANDLE;}

    void recreateSwapchain(){
        vkDeviceWaitIdle(device_);destroySwap();VkSurfaceCapabilitiesKHR caps{};checkVk(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_,surface_,&caps),"surface caps");uint32_t fn=0;vkGetPhysicalDeviceSurfaceFormatsKHR(physical_,surface_,&fn,nullptr);std::vector<VkSurfaceFormatKHR>fs(fn);vkGetPhysicalDeviceSurfaceFormatsKHR(physical_,surface_,&fn,fs.data());VkSurfaceFormatKHR fmt=fs[0];for(auto&f:fs)if(f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM){fmt=f;break;}swapFmt_=fmt.format;preTransform_=(VkSurfaceTransformFlagBitsKHR)caps.currentTransform;extent_=caps.currentExtent.width!=UINT32_MAX?caps.currentExtent:VkExtent2D{uint32_t(std::max(1,ANativeWindow_getWidth(window_))),uint32_t(std::max(1,ANativeWindow_getHeight(window_)))};if(quarter(preTransform_))std::swap(extent_.width,extent_.height);uint32_t count=caps.minImageCount+1;if(caps.maxImageCount)count=std::min(count,caps.maxImageCount);VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};ci.surface=surface_;ci.minImageCount=count;ci.imageFormat=fmt.format;ci.imageColorSpace=fmt.colorSpace;ci.imageExtent=extent_;ci.imageArrayLayers=1;ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;ci.preTransform=preTransform_;ci.compositeAlpha=(caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;ci.presentMode=VK_PRESENT_MODE_FIFO_KHR;ci.clipped=VK_TRUE;checkVk(vkCreateSwapchainKHR(device_,&ci,nullptr,&swap_),"swapchain");uint32_t n=0;vkGetSwapchainImagesKHR(device_,swap_,&n,nullptr);images_.resize(n);vkGetSwapchainImagesKHR(device_,swap_,&n,images_.data());views_.resize(n);for(uint32_t i=0;i<n;i++){VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=images_[i];vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=swapFmt_;vi.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;vi.subresourceRange.levelCount=1;vi.subresourceRange.layerCount=1;checkVk(vkCreateImageView(device_,&vi,nullptr,&views_[i]),"view");}createDepth();createPipeline();fbs_.resize(n);for(uint32_t i=0;i<n;i++){VkImageView a[2]={views_[i],depthView_};VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};fi.renderPass=renderPass_;fi.attachmentCount=2;fi.pAttachments=a;fi.width=extent_.width;fi.height=extent_.height;fi.layers=1;checkVk(vkCreateFramebuffer(device_,&fi,nullptr,&fbs_[i]),"framebuffer");}resize_=false;log("swapchain "+std::to_string(extent_.width)+"x"+std::to_string(extent_.height)+" "+rotName(preTransform_));
    }

    uint32_t drawVerts()const{int q=quality_.load();if(q<12)return kCubeVerts+kFloorVerts;if(!stress_.load())return kQualityVerts;int extras=8+(q*56)/100;return kQualityVerts+uint32_t(std::clamp(extras,8,64))*kStressSphereVerts;}

    void record(uint32_t idx,float t){
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};checkVk(vkBeginCommandBuffer(cmd_,&bi),"begin cmd");if(timestamps_){vkCmdResetQueryPool(cmd_,queryPool_,0,2);vkCmdWriteTimestamp(cmd_,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queryPool_,0);}bool useRt=rtSupported_&&requestedRt_.load()&&quality_.load()>=48;if(useRt)cmdUpdateTlas(cmd_);VkClearValue cv[2]{};cv[0].color={{0.005f,0.007f,0.010f,1}};cv[1].depthStencil={1,0};VkRenderPassBeginInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};ri.renderPass=renderPass_;ri.framebuffer=fbs_[idx];ri.renderArea.extent=extent_;ri.clearValueCount=2;ri.pClearValues=cv;vkCmdBeginRenderPass(cmd_,&ri,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(cmd_,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline_);vkCmdBindDescriptorSets(cmd_,VK_PIPELINE_BIND_POINT_GRAPHICS,layout_,0,1,&descSet_,0,nullptr);VkViewport vp{};vp.width=float(extent_.width);vp.height=float(extent_.height);vp.maxDepth=1;VkRect2D sc{};sc.extent=extent_;vkCmdSetViewport(cmd_,0,1,&vp);vkCmdSetScissor(cmd_,0,1,&sc);PushConstants p{yaw_.load(),pitch_.load(),float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load())),cameraDistance_.load(),rotCode(preTransform_),float(quality_.load()),useRt?1.0f:0.0f,t};vkCmdPushConstants(cmd_,layout_,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(p),&p);vkCmdDraw(cmd_,drawVerts(),1,0,0);vkCmdEndRenderPass(cmd_);if(timestamps_)vkCmdWriteTimestamp(cmd_,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queryPool_,1);checkVk(vkEndCommandBuffer(cmd_),"end cmd");
    }

    void draw(float t){
        checkVk(vkWaitForFences(device_,1,&fence_,VK_TRUE,UINT64_MAX),"wait fence");if(timestamps_&&queryIssued_){uint64_t q[2]{};if(vkGetQueryPoolResults(device_,queryPool_,0,2,sizeof(q),q,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT)==VK_SUCCESS)gpuMs_=double(q[1]-q[0])*double(timestampPeriod_)/1e6;}
        uint32_t idx=0;VkResult a=vkAcquireNextImageKHR(device_,swap_,UINT64_MAX,imageAvail_,VK_NULL_HANDLE,&idx);if(a==VK_ERROR_OUT_OF_DATE_KHR){recreateSwapchain();return;}if(a!=VK_SUCCESS&&a!=VK_SUBOPTIMAL_KHR)checkVk(a,"acquire");checkVk(vkResetFences(device_,1,&fence_),"reset fence");checkVk(vkResetCommandBuffer(cmd_,0),"reset cmd");record(idx,t);queryIssued_=true;VkPipelineStageFlags stage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&imageAvail_;si.pWaitDstStageMask=&stage;si.commandBufferCount=1;si.pCommandBuffers=&cmd_;si.signalSemaphoreCount=1;si.pSignalSemaphores=&renderDone_;checkVk(vkQueueSubmit(queue_,1,&si,fence_),"submit");VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&renderDone_;pi.swapchainCount=1;pi.pSwapchains=&swap_;pi.pImageIndices=&idx;VkResult pr=vkQueuePresentKHR(queue_,&pi);if(pr==VK_ERROR_OUT_OF_DATE_KHR||pr==VK_SUBOPTIMAL_KHR||resize_.load())recreateSwapchain();else if(pr!=VK_SUCCESS)checkVk(pr,"present");
    }

    void loop(){
        try{setStatus("Vulkan Studio v3 initializing…");initVulkan();auto last=Clock::now(),stat=last,start=last;uint32_t frames=0;while(running_){auto now=Clock::now();float dt=std::chrono::duration<float>(now-last).count();last=now;physicsStep(dt);updateBodyGpu();float time=std::chrono::duration<float>(now-start).count();draw(time);frames++;float e=std::chrono::duration<float>(now-stat).count();if(e>=.5f){float fps=frames/e;bool rt=rtSupported_&&requestedRt_.load()&&quality_.load()>=48;std::ostringstream o;o.setf(std::ios::fixed);o.precision(1);o<<"Vulkan Studio v3 · "<<gpuName_<<" · "<<fps<<" FPS · GPU "<<gpuMs_<<" ms\n";o<<"Q"<<quality_.load()<<" · "<<(stress_.load()?"STRESS":"QUALITY")<<" · Physics "<<(physics_.load()?"ON":"OFF")<<" · RT "<<(rt?"RAY QUERY":"OFF")<<"\n";o<<"view "<<visibleW_.load()<<"x"<<visibleH_.load()<<" · buffer "<<extent_.width<<"x"<<extent_.height<<" · "<<rotName(preTransform_);setStatus(o.str());frames=0;stat=now;}}
        }catch(const std::exception&e){setStatus(std::string("ERROR: Vulkan Studio v3: ")+e.what());log(std::string("ERROR: ")+e.what());}cleanup();
    }

    void cleanup(){if(device_)vkDeviceWaitIdle(device_);destroySwap();if(queryPool_)vkDestroyQueryPool(device_,queryPool_,nullptr);if(fence_)vkDestroyFence(device_,fence_,nullptr);if(renderDone_)vkDestroySemaphore(device_,renderDone_,nullptr);if(imageAvail_)vkDestroySemaphore(device_,imageAvail_,nullptr);if(descPool_)vkDestroyDescriptorPool(device_,descPool_,nullptr);if(descLayout_)vkDestroyDescriptorSetLayout(device_,descLayout_,nullptr);if(rtSupported_&&fpDestroyAS_){destroyAccel(tlas_);destroyAccel(cubeAs_);destroyAccel(floorAs_);destroyAccel(sphereAs_);destroyBuffer(tlasScratch_);destroyBuffer(instanceBuffer_);destroyBuffer(cubeGeom_);destroyBuffer(floorGeom_);destroyBuffer(sphereGeom_);}destroyBuffer(bodyBuffer_);if(pool_)vkDestroyCommandPool(device_,pool_,nullptr);if(device_)vkDestroyDevice(device_,nullptr);device_=VK_NULL_HANDLE;if(instance_&&surface_)vkDestroySurfaceKHR(instance_,surface_,nullptr);if(instance_)vkDestroyInstance(instance_,nullptr);instance_=VK_NULL_HANDLE;}

    ANativeWindow*window_=nullptr;std::atomic<bool>running_{false};std::thread thread_;mutable std::mutex statusMutex_,logMutex_,bodyMutex_;std::string status_="waiting";std::vector<std::string>logs_;std::vector<Body>bodies_;int grabbed_=-1;float grabDepth_=0;Vec3 grabTarget_{};
    std::atomic<float>yaw_{-.49f},pitch_{.38f},cameraDistance_{6.6f};std::atomic<int>visibleW_{1},visibleH_{1},quality_{72};std::atomic<bool>resize_{false},stress_{false},physics_{true},requestedRt_{true};
    VkInstance instance_=VK_NULL_HANDLE;VkSurfaceKHR surface_=VK_NULL_HANDLE;VkPhysicalDevice physical_=VK_NULL_HANDLE;VkDevice device_=VK_NULL_HANDLE;VkQueue queue_=VK_NULL_HANDLE;uint32_t queueFamily_=0,apiVersion_=0;std::string gpuName_="Vulkan GPU";float timestampPeriod_=1;bool rtSupported_=false,timestamps_=false,queryIssued_=false;double gpuMs_=0;
    VkCommandPool pool_=VK_NULL_HANDLE;VkCommandBuffer cmd_=VK_NULL_HANDLE;VkSwapchainKHR swap_=VK_NULL_HANDLE;VkFormat swapFmt_=VK_FORMAT_UNDEFINED,depthFmt_=VK_FORMAT_UNDEFINED;VkExtent2D extent_{};VkSurfaceTransformFlagBitsKHR preTransform_=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;std::vector<VkImage>images_;std::vector<VkImageView>views_;std::vector<VkFramebuffer>fbs_;VkImage depth_=VK_NULL_HANDLE;VkDeviceMemory depthMem_=VK_NULL_HANDLE;VkImageView depthView_=VK_NULL_HANDLE;VkRenderPass renderPass_=VK_NULL_HANDLE;VkPipelineLayout layout_=VK_NULL_HANDLE;VkPipeline pipeline_=VK_NULL_HANDLE;
    VkDescriptorSetLayout descLayout_=VK_NULL_HANDLE;VkDescriptorPool descPool_=VK_NULL_HANDLE;VkDescriptorSet descSet_=VK_NULL_HANDLE;Buffer bodyBuffer_{};void*bodyMapped_=nullptr;VkSemaphore imageAvail_=VK_NULL_HANDLE,renderDone_=VK_NULL_HANDLE;VkFence fence_=VK_NULL_HANDLE;VkQueryPool queryPool_=VK_NULL_HANDLE;
    PFN_vkCreateAccelerationStructureKHR fpCreateAS_=nullptr;PFN_vkDestroyAccelerationStructureKHR fpDestroyAS_=nullptr;PFN_vkGetAccelerationStructureBuildSizesKHR fpGetBuildSizes_=nullptr;PFN_vkCmdBuildAccelerationStructuresKHR fpCmdBuildAS_=nullptr;PFN_vkGetAccelerationStructureDeviceAddressKHR fpGetASAddress_=nullptr;Accel cubeAs_{},floorAs_{},sphereAs_{},tlas_{};Buffer cubeGeom_{},floorGeom_{},sphereGeom_{},instanceBuffer_{},tlasScratch_{};VkAccelerationStructureGeometryKHR tlasGeometry_{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};bool tlasBuilt_=false;
};

VulkanStudioV3* ptr(jlong h){return reinterpret_cast<VulkanStudioV3*>(static_cast<intptr_t>(h));}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate(JNIEnv*e,jobject,jobject s){ANativeWindow*w=ANativeWindow_fromSurface(e,s);if(!w)return 0;try{auto*r=new VulkanStudioV3(w);r->start();return (jlong)(intptr_t)r;}catch(...){ANativeWindow_release(w);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy(JNIEnv*,jobject,jlong h){delete ptr(h);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResize(JNIEnv*,jobject,jlong h,jint w,jint he){if(auto*r=ptr(h))r->resize(w,he);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate(JNIEnv*,jobject,jlong h,jfloat y,jfloat p){if(auto*r=ptr(h))r->rotate(y,p);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom(JNIEnv*,jobject,jlong h,jfloat s){if(auto*r=ptr(h))r->zoom(s);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptr(h))r->setStress(v);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptr(h))r->setPhysics(v);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptr(h))r->setRt(v);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality(JNIEnv*,jobject,jlong h,jint q){if(auto*r=ptr(h))r->setQuality(q);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics(JNIEnv*,jobject,jlong h){if(auto*r=ptr(h))r->resetPhysics();} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptr(h))r->grabStart(x,y);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptr(h))r->grabMove(x,y);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd(JNIEnv*,jobject,jlong h){if(auto*r=ptr(h))r->grabEnd();} 
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus(JNIEnv*e,jobject,jlong h){std::string s=ptr(h)?ptr(h)->status():"renderer stopped";return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs(JNIEnv*e,jobject,jlong h){std::string s=ptr(h)?ptr(h)->logs():"";return e->NewStringUTF(s.c_str());}
