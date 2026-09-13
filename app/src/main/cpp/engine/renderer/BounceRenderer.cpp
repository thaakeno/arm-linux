#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"
#define STB_IMAGE_IMPLEMENTATION
#include "../../third_party/stb_image.h"
#pragma clang diagnostic pop

#include "bounce_vert_spv.h"
#include "bounce_frag_spv.h"
#include "../physics/BallPhysics.h"

namespace {
using Clock = std::chrono::steady_clock;
constexpr uint32_t kTotalVerts = 14u*36u + 36u + 3u*36u + 48u*24u*6u + 10u*20u*10u*6u + 7u*14u*3u + 40u*12u*6u;
void vkOk(VkResult r,const char* what){ if(r!=VK_SUCCESS) throw std::runtime_error(std::string(what)+" failed: "+std::to_string(r)); }

struct Texture { VkImage image{}; VkDeviceMemory memory{}; VkImageView view{}; VkSampler sampler{}; };
struct Push { float ball[4]; float motion[4]; float misc[4]; };

class BounceRenderer {
public:
    BounceRenderer(ANativeWindow* window,AAssetManager* assets):window_(window),assets_(assets){
        if(!window_||!assets_) throw std::runtime_error("Bounce Quest: missing window/assets");
        ANativeWindow_acquire(window_); width_=std::max(1,ANativeWindow_getWidth(window_)); height_=std::max(1,ANativeWindow_getHeight(window_));
    }
    ~BounceRenderer(){ stop(); cleanup(); if(window_) ANativeWindow_release(window_); }
    void start(){ bool expected=false; if(running_.compare_exchange_strong(expected,true)) thread_=std::thread([this]{run();}); }
    void stop(){ if(running_.exchange(false)&&thread_.joinable()) thread_.join(); }
    void resize(int w,int h){ width_=std::max(1,w); height_=std::max(1,h); recreate_=true; }
    void input(float x,float z){ moveX_=std::clamp(x,-1.f,1.f); moveZ_=std::clamp(z,-1.f,1.f); }
    void jump(){ jump_=true; }
    void dash(){ dash_=true; }
    void pause(bool p){ paused_=p; }
    void reset(){ std::lock_guard<std::mutex> l(gameMutex_); physics_.reset(); start_=Clock::now(); }
    int coins(){ std::lock_guard<std::mutex> l(gameMutex_); return physics_.state().coins; }
    float elapsed() const { return std::chrono::duration<float>(Clock::now()-start_).count(); }
    std::string events(){ std::lock_guard<std::mutex> l(eventMutex_); std::string s; for(auto& e:events_){ if(!s.empty()) s+='\n'; s+=e; } events_.clear(); return s; }

private:
    ANativeWindow* window_{}; AAssetManager* assets_{};
    std::thread thread_; std::atomic<bool> running_{false},recreate_{false},paused_{false},jump_{false},dash_{false};
    std::atomic<int> width_{1},height_{1}; std::atomic<float> moveX_{0},moveZ_{0};
    bounce::BallPhysics physics_; std::mutex gameMutex_,eventMutex_; std::vector<std::string> events_; Clock::time_point start_=Clock::now();

    VkInstance instance_{}; VkSurfaceKHR surface_{}; VkPhysicalDevice physical_{}; VkDevice device_{}; uint32_t queueFamily_{}; VkQueue queue_{};
    VkSwapchainKHR swapchain_{}; VkFormat colorFormat_{}; VkExtent2D extent_{}; std::vector<VkImage> images_; std::vector<VkImageView> imageViews_; std::vector<VkFramebuffer> framebuffers_;
    VkImage depthImage_{}; VkDeviceMemory depthMemory_{}; VkImageView depthView_{};
    VkRenderPass renderPass_{}; VkDescriptorSetLayout descriptorLayout_{}; VkDescriptorPool descriptorPool_{}; VkDescriptorSet descriptorSet_{};
    VkPipelineLayout pipelineLayout_{}; VkPipeline pipeline_{}; VkCommandPool commandPool_{}; VkCommandBuffer commandBuffer_{};
    VkSemaphore imageAvailable_{},renderFinished_{}; VkFence frameFence_{}; Texture concrete_{},normal_{},arm_{};

    void emit(std::string s){ std::lock_guard<std::mutex> l(eventMutex_); events_.push_back(std::move(s)); if(events_.size()>32) events_.erase(events_.begin(),events_.begin()+8); }
    uint32_t memoryType(uint32_t bits,VkMemoryPropertyFlags flags){ VkPhysicalDeviceMemoryProperties p{}; vkGetPhysicalDeviceMemoryProperties(physical_,&p); for(uint32_t i=0;i<p.memoryTypeCount;i++) if((bits&(1u<<i)) && (p.memoryTypes[i].propertyFlags&flags)==flags) return i; throw std::runtime_error("Bounce Quest: no memory type"); }

    void oneShot(const std::function<void(VkCommandBuffer)>& fn){
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ai.commandPool=commandPool_; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=1;
        VkCommandBuffer c{}; vkOk(vkAllocateCommandBuffers(device_,&ai,&c),"alloc command");
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT; vkOk(vkBeginCommandBuffer(c,&bi),"begin command"); fn(c); vkOk(vkEndCommandBuffer(c),"end command");
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1; si.pCommandBuffers=&c; vkOk(vkQueueSubmit(queue_,1,&si,VK_NULL_HANDLE),"submit command"); vkQueueWaitIdle(queue_); vkFreeCommandBuffers(device_,commandPool_,1,&c);
    }

    std::vector<unsigned char> readAsset(const char* path){
        AAsset* a=AAssetManager_open(assets_,path,AASSET_MODE_BUFFER); if(!a) throw std::runtime_error(std::string("missing asset: ")+path);
        off_t n=AAsset_getLength(a); std::vector<unsigned char> d(static_cast<size_t>(n)); int64_t got=AAsset_read(a,d.data(),static_cast<size_t>(n)); AAsset_close(a); if(got!=n) throw std::runtime_error("short asset read"); return d;
    }

    Texture loadTexture(const char* path,bool srgb){
        auto bytes=readAsset(path); int w=0,h=0,c=0; stbi_uc* px=stbi_load_from_memory(bytes.data(),static_cast<int>(bytes.size()),&w,&h,&c,4); if(!px) throw std::runtime_error(std::string("decode texture: ")+path);
        VkDeviceSize byteCount=VkDeviceSize(w)*VkDeviceSize(h)*4; VkBuffer staging{}; VkDeviceMemory stagingMemory{};
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bci.size=byteCount; bci.usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT; bci.sharingMode=VK_SHARING_MODE_EXCLUSIVE; vkOk(vkCreateBuffer(device_,&bci,nullptr,&staging),"staging buffer");
        VkMemoryRequirements mr{}; vkGetBufferMemoryRequirements(device_,staging,&mr); VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mai.allocationSize=mr.size; mai.memoryTypeIndex=memoryType(mr.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT); vkOk(vkAllocateMemory(device_,&mai,nullptr,&stagingMemory),"staging memory"); vkBindBufferMemory(device_,staging,stagingMemory,0);
        void* mapped=nullptr; vkMapMemory(device_,stagingMemory,0,byteCount,0,&mapped); std::memcpy(mapped,px,static_cast<size_t>(byteCount)); vkUnmapMemory(device_,stagingMemory); stbi_image_free(px);
        Texture t{}; VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ii.imageType=VK_IMAGE_TYPE_2D; ii.extent={static_cast<uint32_t>(w),static_cast<uint32_t>(h),1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=srgb?VK_FORMAT_R8G8B8A8_SRGB:VK_FORMAT_R8G8B8A8_UNORM; ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; vkOk(vkCreateImage(device_,&ii,nullptr,&t.image),"texture image");
        vkGetImageMemoryRequirements(device_,t.image,&mr); mai.allocationSize=mr.size; mai.memoryTypeIndex=memoryType(mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT); vkOk(vkAllocateMemory(device_,&mai,nullptr,&t.memory),"texture memory"); vkBindImageMemory(device_,t.image,t.memory,0);
        oneShot([&](VkCommandBuffer cmd){
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.image=t.image; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&b);
            VkBufferImageCopy cp{}; cp.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; cp.imageExtent={static_cast<uint32_t>(w),static_cast<uint32_t>(h),1}; vkCmdCopyBufferToImage(cmd,staging,t.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&cp);
            b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&b);
        });
        vkDestroyBuffer(device_,staging,nullptr); vkFreeMemory(device_,stagingMemory,nullptr);
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image=t.image; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=ii.format; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; vkOk(vkCreateImageView(device_,&vi,nullptr,&t.view),"texture view");
        VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; si.magFilter=VK_FILTER_LINEAR; si.minFilter=VK_FILTER_LINEAR; si.mipmapMode=VK_SAMPLER_MIPMAP_MODE_LINEAR; si.addressModeU=si.addressModeV=si.addressModeW=VK_SAMPLER_ADDRESS_MODE_REPEAT; si.maxLod=1.0f; vkOk(vkCreateSampler(device_,&si,nullptr,&t.sampler),"texture sampler"); return t;
    }

    void initVulkan(){
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="Bounce Quest"; app.apiVersion=VK_API_VERSION_1_2;
        const char* instanceExts[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME}; VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo=&app; ici.enabledExtensionCount=2; ici.ppEnabledExtensionNames=instanceExts; vkOk(vkCreateInstance(&ici,nullptr,&instance_),"instance");
        VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR}; sci.window=window_; vkOk(vkCreateAndroidSurfaceKHR(instance_,&sci,nullptr,&surface_),"surface");
        uint32_t count=0; vkEnumeratePhysicalDevices(instance_,&count,nullptr); std::vector<VkPhysicalDevice> devices(count); vkEnumeratePhysicalDevices(instance_,&count,devices.data()); if(devices.empty()) throw std::runtime_error("Bounce Quest: no Vulkan device"); physical_=devices.front();
        uint32_t qn=0; vkGetPhysicalDeviceQueueFamilyProperties(physical_,&qn,nullptr); std::vector<VkQueueFamilyProperties> qp(qn); vkGetPhysicalDeviceQueueFamilyProperties(physical_,&qn,qp.data()); bool found=false; for(uint32_t i=0;i<qn;i++){ VkBool32 present=VK_FALSE; vkGetPhysicalDeviceSurfaceSupportKHR(physical_,i,surface_,&present); if((qp[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&present){queueFamily_=i;found=true;break;} } if(!found) throw std::runtime_error("Bounce Quest: no graphics queue");
        float priority=1.f; VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex=queueFamily_; qci.queueCount=1; qci.pQueuePriorities=&priority; const char* deviceExts[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME}; VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount=1; dci.pQueueCreateInfos=&qci; dci.enabledExtensionCount=1; dci.ppEnabledExtensionNames=deviceExts; vkOk(vkCreateDevice(physical_,&dci,nullptr,&device_),"device"); vkGetDeviceQueue(device_,queueFamily_,0,&queue_);
        VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpi.queueFamilyIndex=queueFamily_; cpi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; vkOk(vkCreateCommandPool(device_,&cpi,nullptr,&commandPool_),"command pool");
        concrete_=loadTexture("bounce/concrete_diff.jpg",true); normal_=loadTexture("bounce/concrete_normal.jpg",false); arm_=loadTexture("bounce/concrete_arm.jpg",false); createDescriptors(); createSwapchain();
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool=commandPool_; cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount=1; vkOk(vkAllocateCommandBuffers(device_,&cai,&commandBuffer_),"command buffer");
        VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO}; vkOk(vkCreateSemaphore(device_,&si,nullptr,&imageAvailable_),"image semaphore"); vkOk(vkCreateSemaphore(device_,&si,nullptr,&renderFinished_),"render semaphore"); VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT; vkOk(vkCreateFence(device_,&fi,nullptr,&frameFence_),"frame fence");
    }

    void createDescriptors(){
        std::array<VkDescriptorSetLayoutBinding,3> bindings{}; for(uint32_t i=0;i<3;i++){bindings[i].binding=i;bindings[i].descriptorCount=1;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;bindings[i].stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;}
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; li.bindingCount=static_cast<uint32_t>(bindings.size()); li.pBindings=bindings.data(); vkOk(vkCreateDescriptorSetLayout(device_,&li,nullptr,&descriptorLayout_),"descriptor layout");
        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,3}; VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; pi.maxSets=1; pi.poolSizeCount=1; pi.pPoolSizes=&ps; vkOk(vkCreateDescriptorPool(device_,&pi,nullptr,&descriptorPool_),"descriptor pool"); VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; ai.descriptorPool=descriptorPool_; ai.descriptorSetCount=1; ai.pSetLayouts=&descriptorLayout_; vkOk(vkAllocateDescriptorSets(device_,&ai,&descriptorSet_),"descriptor set");
        Texture* tex[3]={&concrete_,&normal_,&arm_}; std::array<VkDescriptorImageInfo,3> infos{}; std::array<VkWriteDescriptorSet,3> writes{}; for(uint32_t i=0;i<3;i++){infos[i]={tex[i]->sampler,tex[i]->view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[i].dstSet=descriptorSet_;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;writes[i].pImageInfo=&infos[i];} vkUpdateDescriptorSets(device_,3,writes.data(),0,nullptr);
    }

    VkShaderModule shader(const unsigned char* data,size_t size){ VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; ci.codeSize=size; ci.pCode=reinterpret_cast<const uint32_t*>(data); VkShaderModule m{}; vkOk(vkCreateShaderModule(device_,&ci,nullptr,&m),"shader module"); return m; }

    void createDepth(){
        VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ii.imageType=VK_IMAGE_TYPE_2D; ii.extent={extent_.width,extent_.height,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_D32_SFLOAT; ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.usage=VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; vkOk(vkCreateImage(device_,&ii,nullptr,&depthImage_),"depth image");
        VkMemoryRequirements mr{}; vkGetImageMemoryRequirements(device_,depthImage_,&mr); VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize=mr.size; ai.memoryTypeIndex=memoryType(mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT); vkOk(vkAllocateMemory(device_,&ai,nullptr,&depthMemory_),"depth memory"); vkBindImageMemory(device_,depthImage_,depthMemory_,0); VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image=depthImage_; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_D32_SFLOAT; vi.subresourceRange={VK_IMAGE_ASPECT_DEPTH_BIT,0,1,0,1}; vkOk(vkCreateImageView(device_,&vi,nullptr,&depthView_),"depth view");
    }

    void createPipeline(){
        VkAttachmentDescription a[2]{}; a[0].format=colorFormat_; a[0].samples=VK_SAMPLE_COUNT_1_BIT; a[0].loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; a[0].storeOp=VK_ATTACHMENT_STORE_OP_STORE; a[0].initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; a[0].finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR; a[1].format=VK_FORMAT_D32_SFLOAT; a[1].samples=VK_SAMPLE_COUNT_1_BIT; a[1].loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; a[1].storeOp=VK_ATTACHMENT_STORE_OP_DONT_CARE; a[1].initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; a[1].finalLayout=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        VkAttachmentReference colorRef{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},depthRef{1,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL}; VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS; sub.colorAttachmentCount=1; sub.pColorAttachments=&colorRef; sub.pDepthStencilAttachment=&depthRef; VkRenderPassCreateInfo rpi{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO}; rpi.attachmentCount=2; rpi.pAttachments=a; rpi.subpassCount=1; rpi.pSubpasses=&sub; vkOk(vkCreateRenderPass(device_,&rpi,nullptr,&renderPass_),"render pass");
        VkPushConstantRange pr{}; pr.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT; pr.size=sizeof(Push); VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount=1; pli.pSetLayouts=&descriptorLayout_; pli.pushConstantRangeCount=1; pli.pPushConstantRanges=&pr; vkOk(vkCreatePipelineLayout(device_,&pli,nullptr,&pipelineLayout_),"pipeline layout");
        VkShaderModule vs=shader(bounce_vert_spv,bounce_vert_spv_size),fs=shader(bounce_frag_spv,bounce_frag_spv_size); VkPipelineShaderStageCreateInfo stages[2]={{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}}; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vs; stages[0].pName="main"; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=fs; stages[1].pName="main";
        VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO}; VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO}; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO}; vp.viewportCount=1; vp.scissorCount=1; VkDynamicState dyns[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR}; VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO}; dyn.dynamicStateCount=2; dyn.pDynamicStates=dyns;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO}; rs.polygonMode=VK_POLYGON_MODE_FILL; rs.cullMode=VK_CULL_MODE_BACK_BIT; rs.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE; rs.lineWidth=1; VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO}; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT; VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO}; ds.depthTestEnable=VK_TRUE; ds.depthWriteEnable=VK_TRUE; ds.depthCompareOp=VK_COMPARE_OP_LESS_OR_EQUAL; VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xf; VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO}; cb.attachmentCount=1; cb.pAttachments=&ba;
        VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO}; gi.stageCount=2; gi.pStages=stages; gi.pVertexInputState=&vin; gi.pInputAssemblyState=&ia; gi.pViewportState=&vp; gi.pRasterizationState=&rs; gi.pMultisampleState=&ms; gi.pDepthStencilState=&ds; gi.pColorBlendState=&cb; gi.pDynamicState=&dyn; gi.layout=pipelineLayout_; gi.renderPass=renderPass_; vkOk(vkCreateGraphicsPipelines(device_,VK_NULL_HANDLE,1,&gi,nullptr,&pipeline_),"graphics pipeline"); vkDestroyShaderModule(device_,vs,nullptr); vkDestroyShaderModule(device_,fs,nullptr);
    }

    void createSwapchain(){
        destroySwapchain(); VkSurfaceCapabilitiesKHR caps{}; vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_,surface_,&caps); uint32_t fn=0; vkGetPhysicalDeviceSurfaceFormatsKHR(physical_,surface_,&fn,nullptr); std::vector<VkSurfaceFormatKHR> formats(fn); vkGetPhysicalDeviceSurfaceFormatsKHR(physical_,surface_,&fn,formats.data()); if(formats.empty()) throw std::runtime_error("Bounce Quest: no surface formats"); VkSurfaceFormatKHR sf=formats.front(); for(auto f:formats) if(f.format==VK_FORMAT_R8G8B8A8_SRGB||f.format==VK_FORMAT_B8G8R8A8_SRGB){sf=f;break;} colorFormat_=sf.format; extent_=caps.currentExtent.width!=UINT32_MAX?caps.currentExtent:VkExtent2D{static_cast<uint32_t>(width_.load()),static_cast<uint32_t>(height_.load())};
        uint32_t imageCount=caps.minImageCount+1; if(caps.maxImageCount&&imageCount>caps.maxImageCount) imageCount=caps.maxImageCount; VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR}; ci.surface=surface_; ci.minImageCount=imageCount; ci.imageFormat=colorFormat_; ci.imageColorSpace=sf.colorSpace; ci.imageExtent=extent_; ci.imageArrayLayers=1; ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT; ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=caps.currentTransform; ci.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR; ci.presentMode=VK_PRESENT_MODE_FIFO_KHR; ci.clipped=VK_TRUE; vkOk(vkCreateSwapchainKHR(device_,&ci,nullptr,&swapchain_),"swapchain");
        vkGetSwapchainImagesKHR(device_,swapchain_,&imageCount,nullptr); images_.resize(imageCount); vkGetSwapchainImagesKHR(device_,swapchain_,&imageCount,images_.data()); imageViews_.resize(imageCount); for(size_t i=0;i<images_.size();i++){VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=images_[i];vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=colorFormat_;vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};vkOk(vkCreateImageView(device_,&vi,nullptr,&imageViews_[i]),"swap view");}
        createDepth(); createPipeline(); framebuffers_.resize(images_.size()); for(size_t i=0;i<images_.size();i++){VkImageView attachments[]={imageViews_[i],depthView_};VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};fi.renderPass=renderPass_;fi.attachmentCount=2;fi.pAttachments=attachments;fi.width=extent_.width;fi.height=extent_.height;fi.layers=1;vkOk(vkCreateFramebuffer(device_,&fi,nullptr,&framebuffers_[i]),"framebuffer");}
    }

    void destroySwapchain(){ if(!device_) return; vkDeviceWaitIdle(device_); for(auto f:framebuffers_) if(f)vkDestroyFramebuffer(device_,f,nullptr); framebuffers_.clear(); if(pipeline_)vkDestroyPipeline(device_,pipeline_,nullptr);pipeline_=VK_NULL_HANDLE;if(pipelineLayout_)vkDestroyPipelineLayout(device_,pipelineLayout_,nullptr);pipelineLayout_=VK_NULL_HANDLE;if(renderPass_)vkDestroyRenderPass(device_,renderPass_,nullptr);renderPass_=VK_NULL_HANDLE;if(depthView_)vkDestroyImageView(device_,depthView_,nullptr);depthView_=VK_NULL_HANDLE;if(depthImage_)vkDestroyImage(device_,depthImage_,nullptr);depthImage_=VK_NULL_HANDLE;if(depthMemory_)vkFreeMemory(device_,depthMemory_,nullptr);depthMemory_=VK_NULL_HANDLE;for(auto v:imageViews_)if(v)vkDestroyImageView(device_,v,nullptr);imageViews_.clear();images_.clear();if(swapchain_)vkDestroySwapchainKHR(device_,swapchain_,nullptr);swapchain_=VK_NULL_HANDLE; }

    void draw(){
        vkWaitForFences(device_,1,&frameFence_,VK_TRUE,UINT64_MAX); uint32_t index=0; VkResult a=vkAcquireNextImageKHR(device_,swapchain_,UINT64_MAX,imageAvailable_,VK_NULL_HANDLE,&index); if(a==VK_ERROR_OUT_OF_DATE_KHR){createSwapchain();return;} vkOk(a,"acquire"); vkResetFences(device_,1,&frameFence_); vkResetCommandBuffer(commandBuffer_,0);
        Push p{}; {std::lock_guard<std::mutex>l(gameMutex_);const auto&s=physics_.state();p.ball[0]=s.pos.x;p.ball[1]=s.pos.y;p.ball[2]=s.pos.z;p.ball[3]=s.radius;p.motion[0]=s.squash;p.motion[1]=elapsed();p.motion[2]=float(extent_.width)/float(std::max(1u,extent_.height));p.motion[3]=float(s.coinMask);p.misc[0]=s.won?1.f:0.f;p.misc[1]=1.f;}
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; vkOk(vkBeginCommandBuffer(commandBuffer_,&bi),"begin frame"); VkClearValue clear[2]{}; clear[0].color={{.27f,.55f,.84f,1.f}}; clear[1].depthStencil={1.f,0}; VkRenderPassBeginInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO}; ri.renderPass=renderPass_; ri.framebuffer=framebuffers_[index]; ri.renderArea.extent=extent_; ri.clearValueCount=2; ri.pClearValues=clear; vkCmdBeginRenderPass(commandBuffer_,&ri,VK_SUBPASS_CONTENTS_INLINE); vkCmdBindPipeline(commandBuffer_,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline_); vkCmdBindDescriptorSets(commandBuffer_,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLayout_,0,1,&descriptorSet_,0,nullptr); VkViewport vp{};vp.width=float(extent_.width);vp.height=float(extent_.height);vp.maxDepth=1;VkRect2D sc{};sc.extent=extent_;vkCmdSetViewport(commandBuffer_,0,1,&vp);vkCmdSetScissor(commandBuffer_,0,1,&sc);vkCmdPushConstants(commandBuffer_,pipelineLayout_,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(Push),&p);vkCmdDraw(commandBuffer_,kTotalVerts,1,0,0);vkCmdEndRenderPass(commandBuffer_);vkOk(vkEndCommandBuffer(commandBuffer_),"end frame");
        VkPipelineStageFlags wait=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&imageAvailable_;si.pWaitDstStageMask=&wait;si.commandBufferCount=1;si.pCommandBuffers=&commandBuffer_;si.signalSemaphoreCount=1;si.pSignalSemaphores=&renderFinished_;vkOk(vkQueueSubmit(queue_,1,&si,frameFence_),"submit frame");VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&renderFinished_;pi.swapchainCount=1;pi.pSwapchains=&swapchain_;pi.pImageIndices=&index;VkResult pr=vkQueuePresentKHR(queue_,&pi);if(pr==VK_ERROR_OUT_OF_DATE_KHR||pr==VK_SUBOPTIMAL_KHR||recreate_.exchange(false))createSwapchain();else vkOk(pr,"present");
    }

    void run(){
        try{ initVulkan(); auto last=Clock::now(); while(running_){auto now=Clock::now();float dt=std::chrono::duration<float>(now-last).count();last=now;if(!paused_.load()){bounce::PhysicsEvents ev;{std::lock_guard<std::mutex>l(gameMutex_);ev=physics_.step(dt,moveX_.load(),moveZ_.load(),jump_.exchange(false),dash_.exchange(false));}if(ev.bounced)emit("SFX:BOUNCE:"+std::to_string(ev.impact));if(ev.jumped)emit("SFX:JUMP");if(ev.dashed)emit("SFX:DASH");if(ev.collected)emit("SFX:COLLECT");if(ev.died)emit("SFX:DEATH");if(ev.won)emit("SFX:WIN");}draw();} vkDeviceWaitIdle(device_); }
        catch(const std::exception&e){ emit(std::string("ERR:")+e.what()); running_=false; }
    }

    void destroyTexture(Texture&t){if(!device_)return;if(t.sampler)vkDestroySampler(device_,t.sampler,nullptr);if(t.view)vkDestroyImageView(device_,t.view,nullptr);if(t.image)vkDestroyImage(device_,t.image,nullptr);if(t.memory)vkFreeMemory(device_,t.memory,nullptr);t={};}
    void cleanup(){if(!device_&& !instance_)return;if(device_)vkDeviceWaitIdle(device_);destroySwapchain();if(frameFence_)vkDestroyFence(device_,frameFence_,nullptr);if(imageAvailable_)vkDestroySemaphore(device_,imageAvailable_,nullptr);if(renderFinished_)vkDestroySemaphore(device_,renderFinished_,nullptr);if(commandPool_)vkDestroyCommandPool(device_,commandPool_,nullptr);if(descriptorPool_)vkDestroyDescriptorPool(device_,descriptorPool_,nullptr);if(descriptorLayout_)vkDestroyDescriptorSetLayout(device_,descriptorLayout_,nullptr);destroyTexture(concrete_);destroyTexture(normal_);destroyTexture(arm_);if(device_)vkDestroyDevice(device_,nullptr);device_=VK_NULL_HANDLE;if(surface_)vkDestroySurfaceKHR(instance_,surface_,nullptr);surface_=VK_NULL_HANDLE;if(instance_)vkDestroyInstance(instance_,nullptr);instance_=VK_NULL_HANDLE;}
};

BounceRenderer* ptr(jlong h){return reinterpret_cast<BounceRenderer*>(h);} 
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeCreate(JNIEnv* env,jobject,jobject surface,jobject assets){ANativeWindow*w=ANativeWindow_fromSurface(env,surface);AAssetManager*a=AAssetManager_fromJava(env,assets);if(!w||!a){if(w)ANativeWindow_release(w);return 0;}try{auto*r=new BounceRenderer(w,a);ANativeWindow_release(w);r->start();return reinterpret_cast<jlong>(r);}catch(...){ANativeWindow_release(w);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeDestroy(JNIEnv*,jobject,jlong h){delete ptr(h);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeResize(JNIEnv*,jobject,jlong h,jint w,jint he){if(auto*r=ptr(h))r->resize(w,he);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeInput(JNIEnv*,jobject,jlong h,jfloat x,jfloat z){if(auto*r=ptr(h))r->input(x,z);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeJump(JNIEnv*,jobject,jlong h){if(auto*r=ptr(h))r->jump();} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeDash(JNIEnv*,jobject,jlong h){if(auto*r=ptr(h))r->dash();} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativePause(JNIEnv*,jobject,jlong h,jboolean p){if(auto*r=ptr(h))r->pause(p);} 
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeReset(JNIEnv*,jobject,jlong h){if(auto*r=ptr(h))r->reset();} 
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeCoins(JNIEnv*,jobject,jlong h){return ptr(h)?ptr(h)->coins():0;} 
extern "C" JNIEXPORT jfloat JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeElapsed(JNIEnv*,jobject,jlong h){return ptr(h)?ptr(h)->elapsed():0.f;} 
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_BounceQuestActivity_nativeEvents(JNIEnv* env,jobject,jlong h){std::string s=ptr(h)?ptr(h)->events():"";return env->NewStringUTF(s.c_str());}
