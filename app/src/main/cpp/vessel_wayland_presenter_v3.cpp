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
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

constexpr const char *kTag = "VesselWayland";
constexpr const char *kSocketName = "vessel-wayland-v1";
constexpr uint32_t kMagic = 0x31574656u;
constexpr uint32_t kMsgImport = 1;
constexpr uint32_t kMsgFrame = 2;
constexpr uint32_t kMsgReset = 3;
constexpr uint32_t kMsgShmDamage = 4;
constexpr uint32_t kFramesInFlight = 3;
constexpr uint64_t kDrmModifierLinear = 0;
constexpr uint64_t kMaxSoftwarePayload = 64ull * 1024ull * 1024ull;

constexpr uint32_t fourcc(char a, char b, char c, char d) {
    return static_cast<uint32_t>(a) |
           (static_cast<uint32_t>(b) << 8u) |
           (static_cast<uint32_t>(c) << 16u) |
           (static_cast<uint32_t>(d) << 24u);
}
constexpr uint32_t DRM_FORMAT_ABGR8888 = fourcc('A', 'B', '2', '4');
constexpr uint32_t DRM_FORMAT_XBGR8888 = fourcc('X', 'B', '2', '4');
constexpr uint32_t DRM_FORMAT_ARGB8888 = fourcc('A', 'R', '2', '4');
constexpr uint32_t DRM_FORMAT_XRGB8888 = fourcc('X', 'R', '2', '4');

#pragma pack(push, 1)
struct FrameMessage {
    uint32_t magic;
    uint32_t type;
    uint32_t width;
    uint32_t height;
    uint32_t fourcc;
    uint32_t stride;
    uint32_t offset;      // SHM damage x, dma-buf byte offset otherwise
    uint32_t reserved;    // SHM damage y
    uint64_t modifier;    // SHM: high32=h low32=w, dma-buf modifier otherwise
    uint64_t serial;
};
#pragma pack(pop)

struct ImportedImage {
    int backingFd = -1; // retained so Surface recreation can re-import it
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t fourcc = 0;
    uint32_t stride = 0;
    uint32_t offset = 0;
    uint64_t modifier = 0;
    uint64_t lastUse = 0;
};

struct StagingBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void *mapped = nullptr;
    VkDeviceSize size = 0;
};

struct FrameContext {
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkSemaphore acquire = VK_NULL_HANDLE;
    VkSemaphore render = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    StagingBuffer staging{};
};

struct SoftwareImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
    uint32_t width = 0;
    uint32_t height = 0;
};

void loge(const std::string &s) { __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", s.c_str()); }
void logi(const std::string &s) { __android_log_print(ANDROID_LOG_INFO, kTag, "%s", s.c_str()); }

std::string vkerr(const char *what, VkResult result) {
    return std::string(what) + " failed VkResult=" + std::to_string(static_cast<int>(result));
}

bool recvAll(int fd, void *dst, size_t bytes) {
    auto *p = static_cast<uint8_t *>(dst);
    while (bytes) {
        ssize_t n = recv(fd, p, bytes, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        bytes -= static_cast<size_t>(n);
    }
    return true;
}

class Presenter {
public:
    void start() {
        std::lock_guard<std::mutex> guard(lock_);
        if (server_.joinable()) return;
        stop_.store(false);
        server_ = std::thread([this] { serverLoop(); });
    }

    void stop() {
        stop_.store(true);
        int local = -1;
        {
            std::lock_guard<std::mutex> guard(lock_);
            local = listenFd_;
            listenFd_ = -1;
        }
        if (local >= 0) shutdown(local, SHUT_RDWR);
        if (server_.joinable()) server_.join();
        std::lock_guard<std::mutex> guard(lock_);
        destroyVulkanLocked();
        clearSourcesLocked();
        releaseWindowLocked();
    }

    void attach(JNIEnv *env, jobject surface) {
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (!window) {
            setStatus("surface-error:ANativeWindow_fromSurface");
            return;
        }
        std::lock_guard<std::mutex> guard(lock_);
        if (window_ == window) {
            ANativeWindow_release(window);
            presentPendingLocked();
            return;
        }
        destroyVulkanLocked();
        releaseWindowLocked();
        window_ = window;
        status_ = "surface-attached";
        presentPendingLocked();
    }

    void detach() {
        std::lock_guard<std::mutex> guard(lock_);
        destroyVulkanLocked();
        releaseWindowLocked();
        status_ = "surface-detached:frames-retained";
    }

    std::string status() const {
        std::lock_guard<std::mutex> guard(lock_);
        return status_;
    }

private:
    mutable std::mutex lock_;
    std::atomic<bool> stop_{false};
    std::thread server_;
    int listenFd_ = -1;
    ANativeWindow *window_ = nullptr;
    std::string status_ = "starting";

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = UINT32_MAX;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapExtent_{};
    std::vector<VkImage> swapImages_;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::array<FrameContext, kFramesInFlight> frames_{};
    uint32_t frameIndex_ = 0;

    PFN_vkGetMemoryFdPropertiesKHR getMemoryFdProperties_ = nullptr;
    bool hasDrmModifier_ = false;
    std::unordered_map<uint64_t, ImportedImage> sources_;
    uint64_t pendingSerial_ = 0;
    uint64_t useCounter_ = 0;
    SoftwareImage software_{};
    std::vector<uint8_t> softwarePixels_;
    uint32_t softwareFourcc_ = 0;
    bool softwarePending_ = false;
    uint64_t presented_ = 0;

    void setStatus(const std::string &s) {
        std::lock_guard<std::mutex> guard(lock_);
        status_ = s;
        loge(s);
    }

    void releaseWindowLocked() {
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    static bool hasExtension(const std::vector<VkExtensionProperties> &props, const char *name) {
        return std::any_of(props.begin(), props.end(), [name](const VkExtensionProperties &p) {
            return std::strcmp(p.extensionName, name) == 0;
        });
    }

    uint32_t pickMemoryType(uint32_t bits, VkMemoryPropertyFlags required = 0) const {
        VkPhysicalDeviceMemoryProperties props{};
        vkGetPhysicalDeviceMemoryProperties(physical_, &props);
        for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & required) == required) return i;
        }
        return UINT32_MAX;
    }

    VkFormat formatForFourcc(uint32_t value) const {
        if (value == DRM_FORMAT_ABGR8888 || value == DRM_FORMAT_XBGR8888) return VK_FORMAT_R8G8B8A8_UNORM;
        if (value == DRM_FORMAT_ARGB8888 || value == DRM_FORMAT_XRGB8888) return VK_FORMAT_B8G8R8A8_UNORM;
        return VK_FORMAT_UNDEFINED;
    }

    bool createVulkanLocked() {
        if (device_ != VK_NULL_HANDLE) return true;
        if (!window_) {
            status_ = "waiting-for-surface:frame-retained";
            return false;
        }

        const std::array<const char *, 2> instanceExts = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Vessel Wayland Presenter v3";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo = &app;
        ici.enabledExtensionCount = static_cast<uint32_t>(instanceExts.size());
        ici.ppEnabledExtensionNames = instanceExts.data();
        VkResult vr = vkCreateInstance(&ici, nullptr, &instance_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateInstance", vr); return false; }

        VkAndroidSurfaceCreateInfoKHR asci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        asci.window = window_;
        vr = vkCreateAndroidSurfaceKHR(instance_, &asci, nullptr, &surface_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateAndroidSurfaceKHR", vr); return false; }

        uint32_t physicalCount = 0;
        vkEnumeratePhysicalDevices(instance_, &physicalCount, nullptr);
        if (!physicalCount) { status_ = "no Vulkan physical device"; return false; }
        std::vector<VkPhysicalDevice> devices(physicalCount);
        vkEnumeratePhysicalDevices(instance_, &physicalCount, devices.data());
        for (VkPhysicalDevice candidate : devices) {
            uint32_t familyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
            std::vector<VkQueueFamilyProperties> families(familyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
            for (uint32_t i = 0; i < familyCount; ++i) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &present);
                if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                    physical_ = candidate;
                    queueFamily_ = i;
                    break;
                }
            }
            if (physical_ != VK_NULL_HANDLE) break;
        }
        if (physical_ == VK_NULL_HANDLE) { status_ = "no graphics+present Vulkan queue"; return false; }

        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(physical_, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> extensions(extCount);
        vkEnumerateDeviceExtensionProperties(physical_, nullptr, &extCount, extensions.data());
        const std::array<const char *, 3> required = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
            VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        };
        for (const char *name : required) {
            if (!hasExtension(extensions, name)) {
                status_ = std::string("required Vulkan extension missing: ") + name;
                return false;
            }
        }
        hasDrmModifier_ = hasExtension(extensions, VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME);
        std::vector<const char *> enabled(required.begin(), required.end());
        if (hasDrmModifier_) enabled.push_back(VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME);
        if (hasExtension(extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
            enabled.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        }

        const float priority = 1.0f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = queueFamily_;
        qci.queueCount = 1;
        qci.pQueuePriorities = &priority;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        dci.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
        dci.ppEnabledExtensionNames = enabled.data();
        vr = vkCreateDevice(physical_, &dci, nullptr, &device_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateDevice", vr); return false; }
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        getMemoryFdProperties_ = reinterpret_cast<PFN_vkGetMemoryFdPropertiesKHR>(
            vkGetDeviceProcAddr(device_, "vkGetMemoryFdPropertiesKHR"));
        if (!getMemoryFdProperties_) { status_ = "vkGetMemoryFdPropertiesKHR unavailable"; return false; }

        if (!createSwapchainLocked()) return false;

        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cp.queueFamilyIndex = queueFamily_;
        cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        vr = vkCreateCommandPool(device_, &cp, nullptr, &commandPool_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateCommandPool", vr); return false; }

        std::array<VkCommandBuffer, kFramesInFlight> commands{};
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = commandPool_;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = kFramesInFlight;
        vr = vkAllocateCommandBuffers(device_, &cai, commands.data());
        if (vr != VK_SUCCESS) { status_ = vkerr("vkAllocateCommandBuffers", vr); return false; }

        VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (uint32_t i = 0; i < kFramesInFlight; ++i) {
            frames_[i].command = commands[i];
            if ((vr = vkCreateSemaphore(device_, &sci, nullptr, &frames_[i].acquire)) != VK_SUCCESS ||
                (vr = vkCreateSemaphore(device_, &sci, nullptr, &frames_[i].render)) != VK_SUCCESS ||
                (vr = vkCreateFence(device_, &fci, nullptr, &frames_[i].fence)) != VK_SUCCESS) {
                status_ = vkerr("frame synchronization create", vr);
                return false;
            }
        }

        status_ = std::string("ready:damage-aware-vulkan:FIFO:triple-flight") +
                  (hasDrmModifier_ ? "+drm-modifier" : "");
        logi(status_);
        return true;
    }

    bool createSwapchainLocked() {
        VkSurfaceCapabilitiesKHR caps{};
        VkResult vr = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps);
        if (vr != VK_SUCCESS) { status_ = vkerr("surface capabilities", vr); return false; }
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount, nullptr);
        if (!formatCount) { status_ = "surface has no formats"; return false; }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount, formats.data());
        VkSurfaceFormatKHR chosen = formats[0];
        for (const auto &f : formats) {
            if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                chosen = f;
                break;
            }
        }
        if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
            status_ = "SurfaceFlinger swapchain does not allow TRANSFER_DST";
            return false;
        }
        swapFormat_ = chosen.format;
        if (caps.currentExtent.width != UINT32_MAX) {
            swapExtent_ = caps.currentExtent;
        } else {
            swapExtent_.width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
            swapExtent_.height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
            swapExtent_.width = std::clamp(swapExtent_.width, caps.minImageExtent.width, caps.maxImageExtent.width);
            swapExtent_.height = std::clamp(swapExtent_.height, caps.minImageExtent.height, caps.maxImageExtent.height);
        }
        uint32_t imageCount = std::max(kFramesInFlight, caps.minImageCount);
        if (caps.maxImageCount && imageCount > caps.maxImageCount) imageCount = caps.maxImageCount;
        VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        ci.surface = surface_;
        ci.minImageCount = imageCount;
        ci.imageFormat = swapFormat_;
        ci.imageColorSpace = chosen.colorSpace;
        ci.imageExtent = swapExtent_;
        ci.imageArrayLayers = 1;
        ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ci.preTransform = caps.currentTransform;
        ci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                                ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                                : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        // FIFO is guaranteed by Vulkan and keeps presentation vsync-paced. Unlike
        // MAILBOX it does not encourage clients to burn battery rendering frames
        // SurfaceFlinger will immediately replace.
        ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        ci.clipped = VK_TRUE;
        vr = vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateSwapchainKHR", vr); return false; }
        uint32_t count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, nullptr);
        swapImages_.resize(count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, swapImages_.data());
        return true;
    }

    void imageBarrier(VkCommandBuffer cmd, VkImage image, VkImageLayout oldLayout,
                      VkImageLayout newLayout, VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                      uint32_t srcQueue, uint32_t dstQueue) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = srcAccess;
        barrier.dstAccessMask = dstAccess;
        barrier.oldLayout = oldLayout;
        barrier.newLayout = newLayout;
        barrier.srcQueueFamilyIndex = srcQueue;
        barrier.dstQueueFamilyIndex = dstQueue;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    void destroyImportedGpuLocked(ImportedImage &src) {
        if (device_ != VK_NULL_HANDLE) {
            if (src.image != VK_NULL_HANDLE) vkDestroyImage(device_, src.image, nullptr);
            if (src.memory != VK_NULL_HANDLE) vkFreeMemory(device_, src.memory, nullptr);
        }
        src.image = VK_NULL_HANDLE;
        src.memory = VK_NULL_HANDLE;
    }

    bool materializeSourceLocked(ImportedImage &src) {
        if (src.image != VK_NULL_HANDLE) return true;
        if (!createVulkanLocked() || src.backingFd < 0) return false;
        const VkFormat format = formatForFourcc(src.fourcc);
        if (format == VK_FORMAT_UNDEFINED) { status_ = "unsupported dma-buf fourcc"; return false; }
        if (src.modifier != kDrmModifierLinear && !hasDrmModifier_) {
            status_ = "non-linear dma-buf requires VK_EXT_image_drm_format_modifier";
            return false;
        }

        VkSubresourceLayout plane{};
        plane.offset = src.offset;
        plane.rowPitch = src.stride;
        VkImageDrmFormatModifierExplicitCreateInfoEXT modifierInfo{
            VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT};
        modifierInfo.drmFormatModifier = src.modifier;
        modifierInfo.drmFormatModifierPlaneCount = 1;
        modifierInfo.pPlaneLayouts = &plane;

        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        if (hasDrmModifier_) external.pNext = &modifierInfo;

        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.pNext = &external;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {src.width, src.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = hasDrmModifier_ ? VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT : VK_IMAGE_TILING_LINEAR;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult vr = vkCreateImage(device_, &imageInfo, nullptr, &src.image);
        if (vr != VK_SUCCESS) { status_ = vkerr("source vkCreateImage", vr); destroyImportedGpuLocked(src); return false; }

        VkMemoryRequirements req{};
        vkGetImageMemoryRequirements(device_, src.image, &req);
        VkMemoryFdPropertiesKHR fdProps{VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        vr = getMemoryFdProperties_(device_, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, src.backingFd, &fdProps);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkGetMemoryFdPropertiesKHR", vr); destroyImportedGpuLocked(src); return false; }
        const uint32_t memoryType = pickMemoryType(req.memoryTypeBits & fdProps.memoryTypeBits);
        if (memoryType == UINT32_MAX) { status_ = "no compatible dma-buf memory type"; destroyImportedGpuLocked(src); return false; }

        const int importFd = dup(src.backingFd);
        if (importFd < 0) { status_ = "dup dma-buf failed"; destroyImportedGpuLocked(src); return false; }
        VkImportMemoryFdInfoKHR importInfo{VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR};
        importInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        importInfo.fd = importFd;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &importInfo;
        dedicated.image = src.image;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.pNext = &dedicated;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = memoryType;
        vr = vkAllocateMemory(device_, &mai, nullptr, &src.memory);
        if (vr != VK_SUCCESS) {
            close(importFd);
            status_ = vkerr("source vkAllocateMemory", vr);
            destroyImportedGpuLocked(src);
            return false;
        }
        vr = vkBindImageMemory(device_, src.image, src.memory, 0);
        if (vr != VK_SUCCESS) { status_ = vkerr("source vkBindImageMemory", vr); destroyImportedGpuLocked(src); return false; }
        return true;
    }

    void storeImportLocked(int fd, const FrameMessage &msg) {
        auto it = sources_.find(msg.serial);
        if (it != sources_.end()) {
            close(fd);
            return;
        }
        ImportedImage src{};
        src.backingFd = fd;
        src.width = msg.width;
        src.height = msg.height;
        src.fourcc = msg.fourcc;
        src.stride = msg.stride;
        src.offset = msg.offset;
        src.modifier = msg.modifier;
        src.lastUse = ++useCounter_;
        sources_.emplace(msg.serial, std::move(src));
        trimSourceCacheLocked();
        status_ = "dma-buf-cached serial=" + std::to_string(msg.serial);
    }

    void trimSourceCacheLocked() {
        constexpr size_t kMaxSources = 12;
        while (sources_.size() > kMaxSources) {
            auto victim = sources_.end();
            for (auto it = sources_.begin(); it != sources_.end(); ++it) {
                if (it->first == pendingSerial_) continue;
                if (victim == sources_.end() || it->second.lastUse < victim->second.lastUse) victim = it;
            }
            if (victim == sources_.end()) break;
            destroyImportedGpuLocked(victim->second);
            if (victim->second.backingFd >= 0) close(victim->second.backingFd);
            sources_.erase(victim);
        }
    }

    bool ensureStagingLocked(FrameContext &frame, VkDeviceSize bytes) {
        if (frame.staging.buffer != VK_NULL_HANDLE && frame.staging.size >= bytes) return true;
        if (frame.staging.mapped) vkUnmapMemory(device_, frame.staging.memory);
        if (frame.staging.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.staging.buffer, nullptr);
        if (frame.staging.memory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.staging.memory, nullptr);
        frame.staging = {};

        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = std::max<VkDeviceSize>(bytes, 4096);
        bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult vr = vkCreateBuffer(device_, &bci, nullptr, &frame.staging.buffer);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateBuffer staging", vr); return false; }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device_, frame.staging.buffer, &req);
        uint32_t type = pickMemoryType(req.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (type == UINT32_MAX) { status_ = "no host-visible coherent staging memory"; return false; }
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = type;
        vr = vkAllocateMemory(device_, &mai, nullptr, &frame.staging.memory);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkAllocateMemory staging", vr); return false; }
        vr = vkBindBufferMemory(device_, frame.staging.buffer, frame.staging.memory, 0);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkBindBufferMemory staging", vr); return false; }
        vr = vkMapMemory(device_, frame.staging.memory, 0, bci.size, 0, &frame.staging.mapped);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkMapMemory staging", vr); return false; }
        frame.staging.size = bci.size;
        return true;
    }

    void destroySoftwareGpuLocked() {
        if (device_ != VK_NULL_HANDLE) {
            if (software_.image != VK_NULL_HANDLE) vkDestroyImage(device_, software_.image, nullptr);
            if (software_.memory != VK_NULL_HANDLE) vkFreeMemory(device_, software_.memory, nullptr);
        }
        software_ = {};
    }

    bool ensureSoftwareImageLocked(uint32_t width, uint32_t height, uint32_t fourccValue) {
        const VkFormat format = formatForFourcc(fourccValue);
        if (format == VK_FORMAT_UNDEFINED) { status_ = "unsupported SHM fourcc"; return false; }
        if (software_.image != VK_NULL_HANDLE && software_.width == width && software_.height == height && software_.format == format) return true;
        destroySoftwareGpuLocked();
        if (!createVulkanLocked()) return false;
        VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = format;
        ici.extent = {width, height, 1};
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult vr = vkCreateImage(device_, &ici, nullptr, &software_.image);
        if (vr != VK_SUCCESS) { status_ = vkerr("software vkCreateImage", vr); return false; }
        VkMemoryRequirements req{};
        vkGetImageMemoryRequirements(device_, software_.image, &req);
        uint32_t type = pickMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type == UINT32_MAX) type = pickMemoryType(req.memoryTypeBits);
        if (type == UINT32_MAX) { status_ = "no memory type for software image"; return false; }
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = type;
        vr = vkAllocateMemory(device_, &mai, nullptr, &software_.memory);
        if (vr != VK_SUCCESS) { status_ = vkerr("software vkAllocateMemory", vr); return false; }
        vr = vkBindImageMemory(device_, software_.image, software_.memory, 0);
        if (vr != VK_SUCCESS) { status_ = vkerr("software vkBindImageMemory", vr); return false; }
        software_.width = width;
        software_.height = height;
        software_.format = format;
        software_.layout = VK_IMAGE_LAYOUT_UNDEFINED;
        return true;
    }

    bool beginFrameLocked(FrameContext *&frame, uint32_t &swapIndex) {
        if (!createVulkanLocked()) return false;
        frame = &frames_[frameIndex_++ % kFramesInFlight];
        VkResult vr = vkWaitForFences(device_, 1, &frame->fence, VK_TRUE, UINT64_MAX);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkWaitForFences", vr); return false; }
        vkResetFences(device_, 1, &frame->fence);
        vr = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, frame->acquire, VK_NULL_HANDLE, &swapIndex);
        if (vr == VK_ERROR_OUT_OF_DATE_KHR) { status_ = "swapchain-out-of-date"; return false; }
        if (vr != VK_SUCCESS && vr != VK_SUBOPTIMAL_KHR) { status_ = vkerr("vkAcquireNextImageKHR", vr); return false; }
        vkResetCommandBuffer(frame->command, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vr = vkBeginCommandBuffer(frame->command, &bi);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkBeginCommandBuffer", vr); return false; }
        return true;
    }

    bool finishFrameLocked(FrameContext &frame, uint32_t swapIndex) {
        imageBarrier(frame.command, swapImages_[swapIndex],
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                     VK_ACCESS_TRANSFER_WRITE_BIT, 0, queueFamily_, queueFamily_);
        VkResult vr = vkEndCommandBuffer(frame.command);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkEndCommandBuffer", vr); return false; }
        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &frame.acquire;
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &frame.command;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &frame.render;
        vr = vkQueueSubmit(queue_, 1, &submit, frame.fence);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkQueueSubmit", vr); return false; }
        VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores = &frame.render;
        pi.swapchainCount = 1;
        pi.pSwapchains = &swapchain_;
        pi.pImageIndices = &swapIndex;
        vr = vkQueuePresentKHR(queue_, &pi);
        if (vr != VK_SUCCESS && vr != VK_SUBOPTIMAL_KHR) { status_ = vkerr("vkQueuePresentKHR", vr); return false; }
        ++presented_;
        return true;
    }

    bool presentDmabufLocked(uint64_t serial) {
        pendingSerial_ = serial;
        auto it = sources_.find(serial);
        if (it == sources_.end()) { status_ = "frame references unknown dma-buf serial=" + std::to_string(serial); return false; }
        it->second.lastUse = ++useCounter_;
        if (!window_) { status_ = "dmabuf-frame-retained-until-surface"; return true; }
        if (!materializeSourceLocked(it->second)) return false;
        ImportedImage &src = it->second;

        FrameContext *frame = nullptr;
        uint32_t swapIndex = 0;
        if (!beginFrameLocked(frame, swapIndex)) return false;
        imageBarrier(frame->command, src.image,
                     VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                     VK_QUEUE_FAMILY_EXTERNAL, queueFamily_);
        imageBarrier(frame->command, swapImages_[swapIndex],
                     VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     0, VK_ACCESS_TRANSFER_WRITE_BIT, queueFamily_, queueFamily_);
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = {static_cast<int32_t>(src.width), static_cast<int32_t>(src.height), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1] = {static_cast<int32_t>(swapExtent_.width), static_cast<int32_t>(swapExtent_.height), 1};
        vkCmdBlitImage(frame->command, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapImages_[swapIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
        imageBarrier(frame->command, src.image,
                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                     VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                     queueFamily_, VK_QUEUE_FAMILY_EXTERNAL);
        if (!finishFrameLocked(*frame, swapIndex)) return false;
        softwarePending_ = false;
        status_ = "presenting-dmabuf:FIFO frames=" + std::to_string(presented_);
        if (presented_ <= 3 || presented_ % 120 == 0) logi(status_);
        return true;
    }

    bool presentSoftwareDamageLocked(const FrameMessage &msg, const std::vector<uint8_t> &payload) {
        const uint32_t damageW = static_cast<uint32_t>(msg.modifier & 0xffffffffu);
        const uint32_t damageH = static_cast<uint32_t>(msg.modifier >> 32u);
        if (!damageW || !damageH || msg.offset + damageW > msg.width || msg.reserved + damageH > msg.height) {
            status_ = "invalid SHM damage rectangle";
            return false;
        }
        if (msg.stride < damageW * 4u || payload.size() != static_cast<size_t>(msg.stride) * damageH) {
            status_ = "invalid SHM payload size";
            return false;
        }
        if (softwarePixels_.size() != static_cast<size_t>(msg.width) * msg.height * 4u || softwareFourcc_ != msg.fourcc) {
            softwarePixels_.assign(static_cast<size_t>(msg.width) * msg.height * 4u, 0);
            softwareFourcc_ = msg.fourcc;
        }
        for (uint32_t row = 0; row < damageH; ++row) {
            std::memcpy(softwarePixels_.data() + (static_cast<size_t>(msg.reserved + row) * msg.width + msg.offset) * 4u,
                        payload.data() + static_cast<size_t>(row) * msg.stride,
                        static_cast<size_t>(damageW) * 4u);
        }
        softwarePending_ = true;
        pendingSerial_ = 0;
        if (!window_) { status_ = "shm-frame-retained-until-surface"; return true; }
        if (!ensureSoftwareImageLocked(msg.width, msg.height, msg.fourcc)) return false;

        FrameContext *frame = nullptr;
        uint32_t swapIndex = 0;
        if (!beginFrameLocked(frame, swapIndex)) return false;
        const VkDeviceSize tightBytes = static_cast<VkDeviceSize>(damageW) * damageH * 4u;
        if (!ensureStagingLocked(*frame, tightBytes)) return false;
        auto *dst = static_cast<uint8_t *>(frame->staging.mapped);
        for (uint32_t row = 0; row < damageH; ++row) {
            std::memcpy(dst + static_cast<size_t>(row) * damageW * 4u,
                        payload.data() + static_cast<size_t>(row) * msg.stride,
                        static_cast<size_t>(damageW) * 4u);
        }

        imageBarrier(frame->command, software_.image, software_.layout,
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     software_.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_TRANSFER_READ_BIT,
                     VK_ACCESS_TRANSFER_WRITE_BIT, queueFamily_, queueFamily_);
        software_.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        VkBufferImageCopy copy{};
        copy.bufferOffset = 0;
        copy.bufferRowLength = damageW;
        copy.bufferImageHeight = damageH;
        copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.imageSubresource.layerCount = 1;
        copy.imageOffset = {static_cast<int32_t>(msg.offset), static_cast<int32_t>(msg.reserved), 0};
        copy.imageExtent = {damageW, damageH, 1};
        vkCmdCopyBufferToImage(frame->command, frame->staging.buffer, software_.image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        imageBarrier(frame->command, software_.image,
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, queueFamily_, queueFamily_);
        software_.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        imageBarrier(frame->command, swapImages_[swapIndex],
                     VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     0, VK_ACCESS_TRANSFER_WRITE_BIT, queueFamily_, queueFamily_);
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = {static_cast<int32_t>(software_.width), static_cast<int32_t>(software_.height), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1] = {static_cast<int32_t>(swapExtent_.width), static_cast<int32_t>(swapExtent_.height), 1};
        vkCmdBlitImage(frame->command, software_.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapImages_[swapIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
        if (!finishFrameLocked(*frame, swapIndex)) return false;
        status_ = "presenting-shm-damage:FIFO frames=" + std::to_string(presented_);
        if (presented_ <= 3 || presented_ % 120 == 0) logi(status_);
        return true;
    }

    bool presentSoftwareFullLocked() {
        if (!softwarePending_ || softwarePixels_.empty() || !window_) return false;
        FrameMessage msg{};
        msg.magic = kMagic;
        msg.type = kMsgShmDamage;
        msg.width = software_.width ? software_.width : static_cast<uint32_t>(0);
        msg.height = software_.height ? software_.height : static_cast<uint32_t>(0);
        if (!msg.width || !msg.height) return false;
        msg.fourcc = softwareFourcc_;
        msg.stride = msg.width * 4u;
        msg.offset = 0;
        msg.reserved = 0;
        msg.modifier = (static_cast<uint64_t>(msg.height) << 32u) | msg.width;
        return presentSoftwareDamageLocked(msg, softwarePixels_);
    }

    void presentPendingLocked() {
        if (!window_) return;
        if (pendingSerial_) {
            presentDmabufLocked(pendingSerial_);
        } else if (softwarePending_ && !softwarePixels_.empty()) {
            // If the SurfaceView was attached after a software client already
            // rendered, upload the retained full image once so the screen never
            // opens black waiting for the client to happen to repaint.
            const size_t pixels = softwarePixels_.size() / 4u;
            uint32_t width = 0, height = 0;
            if (software_.width && software_.height) { width = software_.width; height = software_.height; }
            if (!width || !height || static_cast<size_t>(width) * height != pixels) return;
            FrameMessage msg{};
            msg.magic = kMagic; msg.type = kMsgShmDamage;
            msg.width = width; msg.height = height; msg.fourcc = softwareFourcc_;
            msg.stride = width * 4u; msg.modifier = (static_cast<uint64_t>(height) << 32u) | width;
            presentSoftwareDamageLocked(msg, softwarePixels_);
        }
    }

    void clearSourcesLocked() {
        for (auto &[serial, src] : sources_) {
            (void)serial;
            destroyImportedGpuLocked(src);
            if (src.backingFd >= 0) close(src.backingFd);
        }
        sources_.clear();
        pendingSerial_ = 0;
        softwarePixels_.clear();
        softwareFourcc_ = 0;
        softwarePending_ = false;
    }

    void destroyVulkanLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        for (auto &[serial, src] : sources_) { (void)serial; destroyImportedGpuLocked(src); }
        destroySoftwareGpuLocked();
        if (device_ != VK_NULL_HANDLE) {
            for (auto &frame : frames_) {
                if (frame.staging.mapped) vkUnmapMemory(device_, frame.staging.memory);
                if (frame.staging.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame.staging.buffer, nullptr);
                if (frame.staging.memory != VK_NULL_HANDLE) vkFreeMemory(device_, frame.staging.memory, nullptr);
                if (frame.acquire != VK_NULL_HANDLE) vkDestroySemaphore(device_, frame.acquire, nullptr);
                if (frame.render != VK_NULL_HANDLE) vkDestroySemaphore(device_, frame.render, nullptr);
                if (frame.fence != VK_NULL_HANDLE) vkDestroyFence(device_, frame.fence, nullptr);
                frame = {};
            }
            if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);
            if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            vkDestroyDevice(device_, nullptr);
        }
        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
        surface_ = VK_NULL_HANDLE;
        physical_ = VK_NULL_HANDLE;
        device_ = VK_NULL_HANDLE;
        queue_ = VK_NULL_HANDLE;
        queueFamily_ = UINT32_MAX;
        swapchain_ = VK_NULL_HANDLE;
        swapImages_.clear();
        commandPool_ = VK_NULL_HANDLE;
        getMemoryFdProperties_ = nullptr;
        hasDrmModifier_ = false;
        frameIndex_ = 0;
        // Keep software dimensions while detached so retained pixels can be
        // presented on the next attach.
        if (!softwarePixels_.empty()) {
            const size_t pixels = softwarePixels_.size() / 4u;
            if (software_.width == 0 || software_.height == 0 || static_cast<size_t>(software_.width) * software_.height != pixels) {
                software_ = {};
            }
        }
    }

    static int receivedFd(msghdr &msg) {
        for (cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS &&
                cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
                int fd = -1;
                std::memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
                return fd;
            }
        }
        return -1;
    }

    void clientLoop(int client) {
        while (!stop_.load()) {
            FrameMessage msg{};
            std::array<char, CMSG_SPACE(sizeof(int) * 4)> control{};
            iovec iov{&msg, sizeof(msg)};
            msghdr hdr{};
            hdr.msg_iov = &iov;
            hdr.msg_iovlen = 1;
            hdr.msg_control = control.data();
            hdr.msg_controllen = control.size();
            ssize_t got;
            do { got = recvmsg(client, &hdr, MSG_WAITALL); } while (got < 0 && errno == EINTR);
            if (got <= 0) return;
            if (static_cast<size_t>(got) != sizeof(msg) || msg.magic != kMagic) {
                setStatus("frame-protocol-error");
                return;
            }
            const int fd = receivedFd(hdr);
            std::vector<uint8_t> payload;
            if (msg.type == kMsgShmDamage) {
                const uint32_t damageH = static_cast<uint32_t>(msg.modifier >> 32u);
                const uint64_t bytes = static_cast<uint64_t>(msg.stride) * damageH;
                if (bytes == 0 || bytes > kMaxSoftwarePayload) {
                    if (fd >= 0) close(fd);
                    setStatus("SHM payload exceeds safety limit");
                    return;
                }
                payload.resize(static_cast<size_t>(bytes));
                if (!recvAll(client, payload.data(), payload.size())) {
                    if (fd >= 0) close(fd);
                    return;
                }
            }

            std::lock_guard<std::mutex> guard(lock_);
            if (msg.type == kMsgImport) {
                if (fd < 0) { status_ = "frame-import-missing-fd"; continue; }
                storeImportLocked(fd, msg);
            } else if (msg.type == kMsgFrame) {
                if (fd >= 0) close(fd);
                presentDmabufLocked(msg.serial);
            } else if (msg.type == kMsgShmDamage) {
                if (fd >= 0) close(fd);
                // Preserve dimensions separately from the Vulkan image so a
                // frame received before SurfaceView attach can still be replayed.
                if (software_.width != msg.width || software_.height != msg.height) {
                    destroySoftwareGpuLocked();
                    software_.width = msg.width;
                    software_.height = msg.height;
                }
                presentSoftwareDamageLocked(msg, payload);
            } else if (msg.type == kMsgReset) {
                if (fd >= 0) close(fd);
                if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
                clearSourcesLocked();
                status_ = "frame-state-reset";
            } else {
                if (fd >= 0) close(fd);
                status_ = "unknown-frame-message";
            }
        }
    }

    void serverLoop() {
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) { setStatus(std::string("frame socket: ") + strerror(errno)); return; }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        const size_t nameLen = std::strlen(kSocketName);
        addr.sun_path[0] = '\0';
        std::memcpy(addr.sun_path + 1, kSocketName, nameLen);
        const socklen_t addrLen = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + nameLen);
        if (bind(fd, reinterpret_cast<sockaddr *>(&addr), addrLen) != 0) {
            const std::string error = std::string("frame bind: ") + strerror(errno);
            close(fd); setStatus(error); return;
        }
        if (listen(fd, 4) != 0) {
            const std::string error = std::string("frame listen: ") + strerror(errno);
            close(fd); setStatus(error); return;
        }
        {
            std::lock_guard<std::mutex> guard(lock_);
            listenFd_ = fd;
            status_ = "wayland-frame-server-v3-ready";
        }
        logi("Vessel v3 Vulkan presenter ready: cached dma-buf + damage-aware SHM");
        while (!stop_.load()) {
            const int client = accept4(fd, nullptr, nullptr, SOCK_CLOEXEC);
            if (client < 0) {
                if (errno == EINTR) continue;
                if (stop_.load()) break;
                setStatus(std::string("frame accept: ") + strerror(errno));
                break;
            }
            clientLoop(client);
            close(client);
        }
        close(fd);
        std::lock_guard<std::mutex> guard(lock_);
        if (listenFd_ == fd) listenFd_ = -1;
    }
};

Presenter gPresenter;

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeStart(JNIEnv *, jclass) {
    gPresenter.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeStop(JNIEnv *, jclass) {
    gPresenter.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAttachSurface(JNIEnv *env, jclass, jobject surface) {
    gPresenter.attach(env, surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeDetachSurface(JNIEnv *, jclass) {
    gPresenter.detach();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeStatus(JNIEnv *env, jclass) {
    const std::string value = gPresenter.status();
    return env->NewStringUTF(value.c_str());
}
