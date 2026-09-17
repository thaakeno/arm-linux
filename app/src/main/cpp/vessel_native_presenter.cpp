#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VesselNativeDisplay";
constexpr uint32_t GPU_REPLY = 0x4;
constexpr uint32_t GPU_GET_PROTOCOL_FEATURES = 1;
constexpr uint32_t GPU_SET_PROTOCOL_FEATURES = 2;
constexpr uint32_t GPU_GET_DISPLAY_INFO = 3;
constexpr uint32_t GPU_CURSOR_POS = 4;
constexpr uint32_t GPU_CURSOR_POS_HIDE = 5;
constexpr uint32_t GPU_CURSOR_UPDATE = 6;
constexpr uint32_t GPU_SCANOUT = 7;
constexpr uint32_t GPU_UPDATE = 8;
constexpr uint32_t GPU_DMABUF_SCANOUT = 9;
constexpr uint32_t GPU_DMABUF_UPDATE = 10;
constexpr uint32_t GPU_GET_EDID = 11;
constexpr uint32_t GPU_DMABUF_SCANOUT2 = 12;
constexpr uint64_t GPU_F_EDID = 1ull << 0;
constexpr uint64_t GPU_F_DMABUF2 = 1ull << 1;
constexpr uint32_t MAX_SCANOUTS = 16;
constexpr uint32_t FRAMES_IN_FLIGHT = 3;
constexpr uint64_t DRM_MOD_LINEAR = 0;

constexpr uint32_t fourcc(char a, char b, char c, char d) {
    return static_cast<uint32_t>(a) | (static_cast<uint32_t>(b) << 8u) |
           (static_cast<uint32_t>(c) << 16u) | (static_cast<uint32_t>(d) << 24u);
}
constexpr uint32_t DRM_FORMAT_ABGR8888 = fourcc('A','B','2','4');
constexpr uint32_t DRM_FORMAT_XBGR8888 = fourcc('X','B','2','4');
constexpr uint32_t DRM_FORMAT_ARGB8888 = fourcc('A','R','2','4');
constexpr uint32_t DRM_FORMAT_XRGB8888 = fourcc('X','R','2','4');

#pragma pack(push,1)
struct GpuHdr { uint32_t request, flags, size; };
struct GpuDmabuf { uint32_t scanout_id,x,y,width,height,fd_width,fd_height,fd_stride,fd_flags,fourcc; };
struct GpuDmabuf2 { GpuDmabuf base; uint64_t modifier; };
struct CursorPos { uint32_t scanout_id, x, y; };
struct CursorUpdate { CursorPos pos; uint32_t hot_x, hot_y; };
struct VirtioCtrl { uint32_t type, flags; uint64_t fence_id; uint32_t ctx_id; uint8_t ring_idx; uint8_t padding[3]; };
struct DisplayOne { uint32_t x,y,width,height,enabled,flags; };
#pragma pack(pop)

void logi(const std::string& s) { __android_log_print(ANDROID_LOG_INFO, TAG, "%s", s.c_str()); }
void loge(const std::string& s) { __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", s.c_str()); }
std::string vkerr(const char* what, VkResult r) { return std::string(what)+" VkResult="+std::to_string((int)r); }

bool recvAll(int fd, void* p0, size_t n) {
    auto* p = static_cast<uint8_t*>(p0);
    while (n) {
        ssize_t r = recv(fd,p,n,0);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return false;
        p += r; n -= static_cast<size_t>(r);
    }
    return true;
}
bool sendAll(int fd, const void* p0, size_t n) {
    auto* p = static_cast<const uint8_t*>(p0);
    while (n) {
        ssize_t r = send(fd,p,n,MSG_NOSIGNAL);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return false;
        p += r; n -= static_cast<size_t>(r);
    }
    return true;
}
int recvHeaderWithFd(int fd, GpuHdr& hdr) {
    std::array<char,CMSG_SPACE(sizeof(int)*4)> control{};
    iovec iov{&hdr,sizeof(hdr)};
    msghdr msg{}; msg.msg_iov=&iov; msg.msg_iovlen=1; msg.msg_control=control.data(); msg.msg_controllen=control.size();
    ssize_t r;
    do { r=recvmsg(fd,&msg,MSG_WAITALL); } while (r<0 && errno==EINTR);
    if (r<=0 || static_cast<size_t>(r)!=sizeof(hdr)) return -2;
    int received=-1;
    for (cmsghdr* c=CMSG_FIRSTHDR(&msg); c; c=CMSG_NXTHDR(&msg,c)) {
        if (c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS && c->cmsg_len>=CMSG_LEN(sizeof(int))) {
            int* fds=reinterpret_cast<int*>(CMSG_DATA(c));
            size_t count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);
            if (count) received=fds[0];
            for (size_t i=1;i<count;i++) close(fds[i]);
        }
    }
    return received;
}
bool sendReply(int fd,uint32_t request,const void* body=nullptr,uint32_t size=0) {
    GpuHdr h{request,GPU_REPLY,size};
    return sendAll(fd,&h,sizeof(h)) && (!size || sendAll(fd,body,size));
}

struct Imported {
    int fd=-1; VkImage image=VK_NULL_HANDLE; VkDeviceMemory memory=VK_NULL_HANDLE;
    uint32_t width=0,height=0,stride=0,fourcc=0,offset=0; uint64_t modifier=0;
};
struct Frame { VkCommandBuffer cmd=VK_NULL_HANDLE; VkSemaphore acquire=VK_NULL_HANDLE,render=VK_NULL_HANDLE; VkFence fence=VK_NULL_HANDLE; };

class Presenter {
public:
    void configure(const std::string& path,uint32_t w,uint32_t h,uint32_t dpi,float refresh) {
        std::lock_guard<std::mutex> g(lock_);
        socketPath_=path; preferredW_=std::clamp(w,640u,3840u); preferredH_=std::clamp(h,480u,2160u);
        dpi_=std::clamp(dpi,72u,480u); refresh_=std::clamp(refresh,30.0f,240.0f);
    }
    void start() {
        std::lock_guard<std::mutex> g(lock_);
        if (server_.joinable()) return;
        stop_=false; server_=std::thread([this]{serverLoop();});
    }
    void stop() {
        stop_=true; int l=-1; { std::lock_guard<std::mutex> g(lock_); l=listenFd_; listenFd_=-1; }
        if(l>=0) shutdown(l,SHUT_RDWR); if(server_.joinable()) server_.join();
        std::lock_guard<std::mutex> g(lock_); destroyVulkan(); releaseWindow(); clearScanout();
    }
    void attach(JNIEnv* env,jobject surface) {
        ANativeWindow* w=ANativeWindow_fromSurface(env,surface); if(!w){setStatus("surface-error");return;}
        std::lock_guard<std::mutex> g(lock_); destroyVulkan(); releaseWindow(); window_=w; status_="surface-attached";
        if(scanout_.fd>=0) presentLocked();
    }
    void detach(){ std::lock_guard<std::mutex> g(lock_); destroyVulkan(); releaseWindow(); status_="surface-detached"; }
    std::string status(){ std::lock_guard<std::mutex> g(lock_); return status_; }
    int cursorX(){std::lock_guard<std::mutex>g(lock_);return (int)cursorX_;}
    int cursorY(){std::lock_guard<std::mutex>g(lock_);return (int)cursorY_;}
    int hotX(){std::lock_guard<std::mutex>g(lock_);return (int)hotX_;}
    int hotY(){std::lock_guard<std::mutex>g(lock_);return (int)hotY_;}
    bool cursorVisible(){std::lock_guard<std::mutex>g(lock_);return cursorVisible_;}
    uint64_t cursorSerial(){std::lock_guard<std::mutex>g(lock_);return cursorSerial_;}
    uint32_t guestW(){std::lock_guard<std::mutex>g(lock_);return scanout_.width?scanout_.width:preferredW_;}
    uint32_t guestH(){std::lock_guard<std::mutex>g(lock_);return scanout_.height?scanout_.height:preferredH_;}
    std::array<uint32_t,4096> cursorPixels(){std::lock_guard<std::mutex>g(lock_);return cursorPixels_;}
private:
    std::mutex lock_; std::atomic<bool> stop_{false}; std::thread server_; int listenFd_=-1;
    std::string socketPath_; std::string status_="stopped"; uint32_t preferredW_=1280,preferredH_=720,dpi_=120; float refresh_=120;
    ANativeWindow* window_=nullptr; Imported scanout_{};
    uint32_t cursorX_=0,cursorY_=0,hotX_=0,hotY_=0; bool cursorVisible_=false; uint64_t cursorSerial_=0; std::array<uint32_t,4096> cursorPixels_{};
    VkInstance instance_=VK_NULL_HANDLE; VkSurfaceKHR surface_=VK_NULL_HANDLE; VkPhysicalDevice physical_=VK_NULL_HANDLE; VkDevice device_=VK_NULL_HANDLE;
    VkQueue queue_=VK_NULL_HANDLE; uint32_t qfam_=UINT32_MAX; VkSwapchainKHR swapchain_=VK_NULL_HANDLE; VkExtent2D extent_{};
    std::vector<VkImage> swapImages_; VkCommandPool pool_=VK_NULL_HANDLE; std::array<Frame,FRAMES_IN_FLIGHT> frames_{}; uint32_t frameNo_=0;
    PFN_vkGetMemoryFdPropertiesKHR getFdProps_=nullptr; bool drmModifier_=false;

    void setStatus(const std::string&s){std::lock_guard<std::mutex>g(lock_);status_=s;loge(s);}
    void releaseWindow(){if(window_){ANativeWindow_release(window_);window_=nullptr;}}
    void destroyImportedGpu(){if(device_){if(scanout_.image)vkDestroyImage(device_,scanout_.image,nullptr);if(scanout_.memory)vkFreeMemory(device_,scanout_.memory,nullptr);}scanout_.image=VK_NULL_HANDLE;scanout_.memory=VK_NULL_HANDLE;}
    void clearScanout(){destroyImportedGpu();if(scanout_.fd>=0)close(scanout_.fd);scanout_=Imported{};}
    static bool hasExt(const std::vector<VkExtensionProperties>&v,const char*n){return std::any_of(v.begin(),v.end(),[&](auto&p){return strcmp(p.extensionName,n)==0;});}
    uint32_t memoryType(uint32_t bits){VkPhysicalDeviceMemoryProperties p{};vkGetPhysicalDeviceMemoryProperties(physical_,&p);for(uint32_t i=0;i<p.memoryTypeCount;i++)if(bits&(1u<<i))return i;return UINT32_MAX;}
    VkFormat format(uint32_t f){if(f==DRM_FORMAT_ABGR8888||f==DRM_FORMAT_XBGR8888)return VK_FORMAT_R8G8B8A8_UNORM;if(f==DRM_FORMAT_ARGB8888||f==DRM_FORMAT_XRGB8888)return VK_FORMAT_B8G8R8A8_UNORM;return VK_FORMAT_UNDEFINED;}
    void barrier(VkCommandBuffer c,VkImage im,VkImageLayout oldL,VkImageLayout newL,VkAccessFlags src,VkAccessFlags dst,uint32_t sq,uint32_t dq){VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.srcAccessMask=src;b.dstAccessMask=dst;b.oldLayout=oldL;b.newLayout=newL;b.srcQueueFamilyIndex=sq;b.dstQueueFamilyIndex=dq;b.image=im;b.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;b.subresourceRange.levelCount=1;b.subresourceRange.layerCount=1;vkCmdPipelineBarrier(c,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&b);}

    bool createVulkan(){
        if(device_)return true; if(!window_){status_="waiting-for-surface";return false;}
        const char* ie[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME}; VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};ai.pApplicationName="Vessel";ai.apiVersion=VK_API_VERSION_1_1;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&ai;ici.enabledExtensionCount=2;ici.ppEnabledExtensionNames=ie;VkResult r=vkCreateInstance(&ici,nullptr,&instance_);if(r){status_=vkerr("vkCreateInstance",r);return false;}
        VkAndroidSurfaceCreateInfoKHR as{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};as.window=window_;r=vkCreateAndroidSurfaceKHR(instance_,&as,nullptr,&surface_);if(r){status_=vkerr("vkCreateAndroidSurfaceKHR",r);return false;}
        uint32_t pc=0;vkEnumeratePhysicalDevices(instance_,&pc,nullptr);std::vector<VkPhysicalDevice> ps(pc);vkEnumeratePhysicalDevices(instance_,&pc,ps.data());
        for(auto p:ps){uint32_t n=0;vkGetPhysicalDeviceQueueFamilyProperties(p,&n,nullptr);std::vector<VkQueueFamilyProperties> qs(n);vkGetPhysicalDeviceQueueFamilyProperties(p,&n,qs.data());for(uint32_t i=0;i<n;i++){VkBool32 present=0;vkGetPhysicalDeviceSurfaceSupportKHR(p,i,surface_,&present);if((qs[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&present){physical_=p;qfam_=i;break;}}if(physical_)break;}
        if(!physical_){status_="no-present-queue";return false;}uint32_t ec=0;vkEnumerateDeviceExtensionProperties(physical_,nullptr,&ec,nullptr);std::vector<VkExtensionProperties> ex(ec);vkEnumerateDeviceExtensionProperties(physical_,nullptr,&ec,ex.data());
        const char* required[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME,VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME};for(auto*n:required)if(!hasExt(ex,n)){status_=std::string("missing-")+n;return false;}
        drmModifier_=hasExt(ex,VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME);std::vector<const char*> enabled(required,required+3);if(drmModifier_)enabled.push_back(VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME);if(hasExt(ex,VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))enabled.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        float pr=1;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=qfam_;qi.queueCount=1;qi.pQueuePriorities=&pr;VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;di.enabledExtensionCount=(uint32_t)enabled.size();di.ppEnabledExtensionNames=enabled.data();r=vkCreateDevice(physical_,&di,nullptr,&device_);if(r){status_=vkerr("vkCreateDevice",r);return false;}vkGetDeviceQueue(device_,qfam_,0,&queue_);getFdProps_=(PFN_vkGetMemoryFdPropertiesKHR)vkGetDeviceProcAddr(device_,"vkGetMemoryFdPropertiesKHR");if(!getFdProps_){status_="no-vkGetMemoryFdPropertiesKHR";return false;}
        VkSurfaceCapabilitiesKHR caps{};vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_,surface_,&caps);uint32_t fc=0;vkGetPhysicalDeviceSurfaceFormatsKHR(physical_,surface_,&fc,nullptr);std::vector<VkSurfaceFormatKHR> fs(fc);vkGetPhysicalDeviceSurfaceFormatsKHR(physical_,surface_,&fc,fs.data());if(fs.empty()){status_="no-surface-format";return false;}auto chosen=fs[0];for(auto&f:fs)if((f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM)&&f.colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR){chosen=f;break;}extent_=caps.currentExtent;if(extent_.width==UINT32_MAX){extent_.width=(uint32_t)std::max(1,ANativeWindow_getWidth(window_));extent_.height=(uint32_t)std::max(1,ANativeWindow_getHeight(window_));extent_.width=std::clamp(extent_.width,caps.minImageExtent.width,caps.maxImageExtent.width);extent_.height=std::clamp(extent_.height,caps.minImageExtent.height,caps.maxImageExtent.height);}uint32_t ic=std::max(FRAMES_IN_FLIGHT,caps.minImageCount);if(caps.maxImageCount)ic=std::min(ic,caps.maxImageCount);
        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};sci.surface=surface_;sci.minImageCount=ic;sci.imageFormat=chosen.format;sci.imageColorSpace=chosen.colorSpace;sci.imageExtent=extent_;sci.imageArrayLayers=1;sci.imageUsage=VK_IMAGE_USAGE_TRANSFER_DST_BIT;sci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;sci.preTransform=caps.currentTransform;sci.compositeAlpha=(caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;sci.presentMode=VK_PRESENT_MODE_FIFO_KHR;sci.clipped=VK_TRUE;r=vkCreateSwapchainKHR(device_,&sci,nullptr,&swapchain_);if(r){status_=vkerr("swapchain",r);return false;}uint32_t sn=0;vkGetSwapchainImagesKHR(device_,swapchain_,&sn,nullptr);swapImages_.resize(sn);vkGetSwapchainImagesKHR(device_,swapchain_,&sn,swapImages_.data());
        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;cp.queueFamilyIndex=qfam_;r=vkCreateCommandPool(device_,&cp,nullptr,&pool_);if(r)return false;std::array<VkCommandBuffer,FRAMES_IN_FLIGHT> cmds{};VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool_;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=FRAMES_IN_FLIGHT;vkAllocateCommandBuffers(device_,&ca,cmds.data());VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;for(uint32_t i=0;i<FRAMES_IN_FLIGHT;i++){frames_[i].cmd=cmds[i];vkCreateSemaphore(device_,&sem,nullptr,&frames_[i].acquire);vkCreateSemaphore(device_,&sem,nullptr,&frames_[i].render);vkCreateFence(device_,&fi,nullptr,&frames_[i].fence);}status_="ready-dmabuf-zero-copy";return true;
    }
    bool importScanout(){if(scanout_.image)return true;if(!createVulkan()||scanout_.fd<0)return false;VkFormat fmt=format(scanout_.fourcc);if(fmt==VK_FORMAT_UNDEFINED){status_="unsupported-fourcc";return false;}if(scanout_.modifier!=DRM_MOD_LINEAR&&!drmModifier_){status_="modifier-extension-missing";return false;}
        VkSubresourceLayout plane{};plane.offset=scanout_.offset;plane.rowPitch=scanout_.stride;VkImageDrmFormatModifierExplicitCreateInfoEXT mi{VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT};mi.drmFormatModifier=scanout_.modifier;mi.drmFormatModifierPlaneCount=1;mi.pPlaneLayouts=&plane;VkExternalMemoryImageCreateInfo ei{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};ei.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;if(drmModifier_)ei.pNext=&mi;VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.pNext=&ei;ii.imageType=VK_IMAGE_TYPE_2D;ii.format=fmt;ii.extent={scanout_.width,scanout_.height,1};ii.mipLevels=1;ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=drmModifier_?VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT:VK_IMAGE_TILING_LINEAR;ii.usage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT;ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;VkResult r=vkCreateImage(device_,&ii,nullptr,&scanout_.image);if(r){status_=vkerr("source-image",r);return false;}VkMemoryRequirements req{};vkGetImageMemoryRequirements(device_,scanout_.image,&req);VkMemoryFdPropertiesKHR fp{VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};r=getFdProps_(device_,VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,scanout_.fd,&fp);if(r){status_=vkerr("fd-properties",r);return false;}uint32_t mt=memoryType(req.memoryTypeBits&fp.memoryTypeBits);if(mt==UINT32_MAX){status_="no-dmabuf-memory-type";return false;}int dupfd=dup(scanout_.fd);VkImportMemoryFdInfoKHR imp{VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR};imp.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;imp.fd=dupfd;VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};ded.pNext=&imp;ded.image=scanout_.image;VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ma.pNext=&ded;ma.allocationSize=req.size;ma.memoryTypeIndex=mt;r=vkAllocateMemory(device_,&ma,nullptr,&scanout_.memory);if(r){close(dupfd);status_=vkerr("import-memory",r);return false;}r=vkBindImageMemory(device_,scanout_.image,scanout_.memory,0);if(r){status_=vkerr("bind-import",r);return false;}return true;}
    bool presentLocked(){if(!window_){status_="frame-ready-waiting-surface";return true;}if(!importScanout())return false;Frame& f=frames_[frameNo_++%FRAMES_IN_FLIGHT];vkWaitForFences(device_,1,&f.fence,VK_TRUE,UINT64_MAX);vkResetFences(device_,1,&f.fence);uint32_t idx=0;VkResult r=vkAcquireNextImageKHR(device_,swapchain_,UINT64_MAX,f.acquire,VK_NULL_HANDLE,&idx);if(r!=VK_SUCCESS&&r!=VK_SUBOPTIMAL_KHR){status_=vkerr("acquire",r);return false;}vkResetCommandBuffer(f.cmd,0);VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;vkBeginCommandBuffer(f.cmd,&bi);
        barrier(f.cmd,scanout_.image,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_ACCESS_MEMORY_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_EXTERNAL,qfam_);barrier(f.cmd,swapImages_[idx],VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,qfam_,qfam_);
        VkClearColorValue black{{0,0,0,1}};VkImageSubresourceRange range{};range.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;range.levelCount=1;range.layerCount=1;vkCmdClearColorImage(f.cmd,swapImages_[idx],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,&black,1,&range);
        double sx=(double)extent_.width/scanout_.width, sy=(double)extent_.height/scanout_.height, scale=std::min(sx,sy);int32_t dw=(int32_t)std::max(1.0,scanout_.width*scale),dh=(int32_t)std::max(1.0,scanout_.height*scale),dx=((int32_t)extent_.width-dw)/2,dy=((int32_t)extent_.height-dh)/2;VkImageBlit bl{};bl.srcSubresource.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;bl.srcSubresource.layerCount=1;bl.srcOffsets[1]={(int32_t)scanout_.width,(int32_t)scanout_.height,1};bl.dstSubresource.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;bl.dstSubresource.layerCount=1;bl.dstOffsets[0]={dx,dy,0};bl.dstOffsets[1]={dx+dw,dy+dh,1};vkCmdBlitImage(f.cmd,scanout_.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,swapImages_[idx],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&bl,VK_FILTER_LINEAR);
        barrier(f.cmd,scanout_.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_ACCESS_TRANSFER_READ_BIT,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,qfam_,VK_QUEUE_FAMILY_EXTERNAL);barrier(f.cmd,swapImages_[idx],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0,qfam_,qfam_);vkEndCommandBuffer(f.cmd);VkPipelineStageFlags stage=VK_PIPELINE_STAGE_TRANSFER_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&f.acquire;si.pWaitDstStageMask=&stage;si.commandBufferCount=1;si.pCommandBuffers=&f.cmd;si.signalSemaphoreCount=1;si.pSignalSemaphores=&f.render;r=vkQueueSubmit(queue_,1,&si,f.fence);if(r){status_=vkerr("submit",r);return false;}VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&f.render;pi.swapchainCount=1;pi.pSwapchains=&swapchain_;pi.pImageIndices=&idx;r=vkQueuePresentKHR(queue_,&pi);if(r!=VK_SUCCESS&&r!=VK_SUBOPTIMAL_KHR){status_=vkerr("present",r);return false;}status_="presenting-dmabuf";return true;}
    void destroyVulkan(){if(device_)vkDeviceWaitIdle(device_);destroyImportedGpu();if(device_){for(auto&f:frames_){if(f.acquire)vkDestroySemaphore(device_,f.acquire,nullptr);if(f.render)vkDestroySemaphore(device_,f.render,nullptr);if(f.fence)vkDestroyFence(device_,f.fence,nullptr);f={};}if(pool_)vkDestroyCommandPool(device_,pool_,nullptr);if(swapchain_)vkDestroySwapchainKHR(device_,swapchain_,nullptr);vkDestroyDevice(device_,nullptr);}if(surface_&&instance_)vkDestroySurfaceKHR(instance_,surface_,nullptr);if(instance_)vkDestroyInstance(instance_,nullptr);instance_=VK_NULL_HANDLE;surface_=VK_NULL_HANDLE;physical_=VK_NULL_HANDLE;device_=VK_NULL_HANDLE;queue_=VK_NULL_HANDLE;qfam_=UINT32_MAX;swapchain_=VK_NULL_HANDLE;swapImages_.clear();pool_=VK_NULL_HANDLE;getFdProps_=nullptr;drmModifier_=false;frameNo_=0;}
    std::array<uint8_t,128> makeEdid(){std::array<uint8_t,128> e{};uint8_t hdr[8]={0,0xff,0xff,0xff,0xff,0xff,0xff,0};memcpy(e.data(),hdr,8);e[8]=0x5a;e[9]=0x73;e[16]=1;e[17]=4;e[18]=1;e[19]=4;e[20]=0xa5;e[21]=(uint8_t)std::clamp((int)(preferredW_*2.54f/dpi_),1,255);e[22]=(uint8_t)std::clamp((int)(preferredH_*2.54f/dpi_),1,255);e[23]=120;e[24]=0x78;uint32_t hb=std::max(160u,preferredW_/5),vb=std::max(30u,preferredH_/20);uint32_t pclk=(uint32_t)std::clamp((double)(preferredW_+hb)*(preferredH_+vb)*refresh_/10000.0,2500.0,65535.0);size_t d=54;e[d]=pclk&255;e[d+1]=(pclk>>8)&255;e[d+2]=preferredW_&255;e[d+3]=hb&255;e[d+4]=((preferredW_>>8)<<4)|((hb>>8)&15);e[d+5]=preferredH_&255;e[d+6]=vb&255;e[d+7]=((preferredH_>>8)<<4)|((vb>>8)&15);e[d+17]=0x1a;const char*name="Vessel GPU\n";e[72]=0;e[73]=0;e[74]=0;e[75]=0xfc;e[76]=0;memcpy(e.data()+77,name,strlen(name));e[126]=0;uint32_t sum=0;for(int i=0;i<127;i++)sum+=e[i];e[127]=(uint8_t)(0-sum);return e;}
    void handleClient(int c){
        while(!stop_){GpuHdr h{};int recvfd=recvHeaderWithFd(c,h);if(recvfd==-2)return;if(h.flags&GPU_REPLY){if(recvfd>=0)close(recvfd);return;}if(h.size>32*1024*1024){if(recvfd>=0)close(recvfd);return;}std::vector<uint8_t> body(h.size);if(h.size&&!recvAll(c,body.data(),body.size())){if(recvfd>=0)close(recvfd);return;}
            if(h.request==GPU_GET_PROTOCOL_FEATURES){uint64_t f=GPU_F_EDID|GPU_F_DMABUF2;sendReply(c,h.request,&f,sizeof(f));if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_SET_PROTOCOL_FEATURES){if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_GET_DISPLAY_INFO){std::array<uint8_t,sizeof(VirtioCtrl)+MAX_SCANOUTS*sizeof(DisplayOne)> out{};DisplayOne one{0,0,preferredW_,preferredH_,1,0};memcpy(out.data()+sizeof(VirtioCtrl),&one,sizeof(one));sendReply(c,h.request,out.data(),(uint32_t)out.size());if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_GET_EDID){std::array<uint8_t,sizeof(VirtioCtrl)+8+1024> out{};auto ed=makeEdid();uint32_t size=128;memcpy(out.data()+sizeof(VirtioCtrl),&size,4);memcpy(out.data()+sizeof(VirtioCtrl)+8,ed.data(),ed.size());sendReply(c,h.request,out.data(),(uint32_t)out.size());if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_DMABUF_SCANOUT||h.request==GPU_DMABUF_SCANOUT2){if(body.size()<(h.request==GPU_DMABUF_SCANOUT2?sizeof(GpuDmabuf2):sizeof(GpuDmabuf))){if(recvfd>=0)close(recvfd);return;}GpuDmabuf d{};uint64_t mod=0;if(h.request==GPU_DMABUF_SCANOUT2){GpuDmabuf2 d2{};memcpy(&d2,body.data(),sizeof(d2));d=d2.base;mod=d2.modifier;}else memcpy(&d,body.data(),sizeof(d));std::lock_guard<std::mutex>g(lock_);clearScanout();if(d.width&&d.height){if(recvfd<0){status_="dmabuf-missing-fd";return;}scanout_.fd=recvfd;recvfd=-1;scanout_.width=d.fd_width?d.fd_width:d.width;scanout_.height=d.fd_height?d.fd_height:d.height;scanout_.stride=d.fd_stride;scanout_.fourcc=d.fourcc;scanout_.modifier=mod;status_="dmabuf-scanout-ready";}if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_DMABUF_UPDATE){if(recvfd>=0)close(recvfd);{std::lock_guard<std::mutex>g(lock_);presentLocked();}sendReply(c,h.request,nullptr,0);}
            else if(h.request==GPU_CURSOR_POS||h.request==GPU_CURSOR_POS_HIDE){if(body.size()>=sizeof(CursorPos)){CursorPos p{};memcpy(&p,body.data(),sizeof(p));std::lock_guard<std::mutex>g(lock_);cursorX_=p.x;cursorY_=p.y;cursorVisible_=(h.request==GPU_CURSOR_POS);cursorSerial_++;}if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_CURSOR_UPDATE){if(body.size()>=sizeof(CursorUpdate)+4096*4){CursorUpdate u{};memcpy(&u,body.data(),sizeof(u));std::lock_guard<std::mutex>g(lock_);cursorX_=u.pos.x;cursorY_=u.pos.y;hotX_=u.hot_x;hotY_=u.hot_y;const uint8_t*px=body.data()+sizeof(u);for(size_t i=0;i<4096;i++){uint32_t v;memcpy(&v,px+i*4,4);cursorPixels_[i]=v;}cursorVisible_=true;cursorSerial_++;}if(recvfd>=0)close(recvfd);}
            else if(h.request==GPU_SCANOUT||h.request==GPU_UPDATE){if(recvfd>=0)close(recvfd);setStatus("software-scanout-rejected");return;}
            else {if(recvfd>=0)close(recvfd);setStatus("unsupported-gpu-request-"+std::to_string(h.request));return;}
        }
    }
    void serverLoop(){std::string path;{std::lock_guard<std::mutex>g(lock_);path=socketPath_;}if(path.empty()){setStatus("display-socket-path-missing");return;}unlink(path.c_str());int s=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(s<0){setStatus("display-socket-create-failed");return;}sockaddr_un a{};a.sun_family=AF_UNIX;if(path.size()>=sizeof(a.sun_path)){close(s);setStatus("display-socket-path-too-long");return;}strncpy(a.sun_path,path.c_str(),sizeof(a.sun_path)-1);if(bind(s,(sockaddr*)&a,sizeof(a))||listen(s,2)){std::string er=strerror(errno);close(s);setStatus("display-bind-failed:"+er);return;}{std::lock_guard<std::mutex>g(lock_);listenFd_=s;status_="display-socket-ready";}logi("native vhost-user-gpu frontend ready "+path);while(!stop_){int c=accept4(s,nullptr,nullptr,SOCK_CLOEXEC);if(c<0){if(errno==EINTR)continue;if(stop_)break;continue;}handleClient(c);close(c);}close(s);unlink(path.c_str());}
};
Presenter g;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeConfigure(JNIEnv*e,jclass,jstring p,jint w,jint h,jint dpi,jfloat refresh){const char*s=e->GetStringUTFChars(p,nullptr);g.configure(s,(uint32_t)w,(uint32_t)h,(uint32_t)dpi,refresh);e->ReleaseStringUTFChars(p,s);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeStart(JNIEnv*,jclass){g.start();}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeStop(JNIEnv*,jclass){g.stop();}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAttachSurface(JNIEnv*e,jclass,jobject s){g.attach(e,s);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeDetachSurface(JNIEnv*,jclass){g.detach();}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeStatus(JNIEnv*e,jclass){auto s=g.status();return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorX(JNIEnv*,jclass){return g.cursorX();}
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorY(JNIEnv*,jclass){return g.cursorY();}
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorHotX(JNIEnv*,jclass){return g.hotX();}
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorHotY(JNIEnv*,jclass){return g.hotY();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorVisible(JNIEnv*,jclass){return g.cursorVisible();}
extern "C" JNIEXPORT jlong JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorSerial(JNIEnv*,jclass){return (jlong)g.cursorSerial();}
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeGuestWidth(JNIEnv*,jclass){return (jint)g.guestW();}
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeGuestHeight(JNIEnv*,jclass){return (jint)g.guestH();}
extern "C" JNIEXPORT jintArray JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeCursorPixels(JNIEnv*e,jclass){auto p=g.cursorPixels();jintArray a=e->NewIntArray(4096);e->SetIntArrayRegion(a,0,4096,reinterpret_cast<const jint*>(p.data()));return a;}
