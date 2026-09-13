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

constexpr const char *kTag = "VesselWayland";
constexpr const char *kSocketName = "vessel-wayland-v1";
constexpr uint32_t kMagic = 0x31574656u;
constexpr uint32_t kMsgImport = 1;
constexpr uint32_t kMsgFrame = 2;
constexpr uint32_t kMsgReset = 3;
constexpr uint32_t kFramesInFlight = 3;
constexpr uint64_t kDrmModifierLinear = 0;

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
    uint32_t offset;
    uint32_t reserved;
    uint64_t modifier;
    uint64_t serial;
};
#pragma pack(pop)

struct ImportedImage {
    int fd = -1;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t fourcc = 0;
    uint32_t stride = 0;
    uint32_t offset = 0;
    uint64_t modifier = 0;
};

struct FrameContext {
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkSemaphore acquire = VK_NULL_HANDLE;
    VkSemaphore render = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
};

void loge(const std::string &s) { __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", s.c_str()); }
void logi(const std::string &s) { __android_log_print(ANDROID_LOG_INFO, kTag, "%s", s.c_str()); }

std::string vkerr(const char *what, VkResult result) {
    return std::string(what) + " failed VkResult=" + std::to_string(static_cast<int>(result));
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
            return;
        }
        destroyVulkanLocked();
        releaseWindowLocked();
        window_ = window;
        status_ = "surface-attached";
    }

    void detach() {
        std::lock_guard<std::mutex> guard(lock_);
        destroyVulkanLocked();
        releaseWindowLocked();
        status_ = "surface-detached";
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
    ImportedImage source_{};
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

    bool createVulkanLocked() {
        if (device_ != VK_NULL_HANDLE) return true;
        if (!window_) {
            status_ = "waiting-for-surface";
            return false;
        }

        const std::array<const char *, 2> instanceExts = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Vessel Direct Wayland Presenter";
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
        if (!getMemoryFdProperties_) {
            status_ = "vkGetMemoryFdPropertiesKHR unavailable";
            return false;
        }

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

        status_ = std::string("ready:direct-dmabuf-to-SurfaceFlinger:triple-flight") +
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
        uint32_t presentCount = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &presentCount, nullptr);
        std::vector<VkPresentModeKHR> modes(presentCount);
        vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &presentCount, modes.data());
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) {
            presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
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
        ci.presentMode = presentMode;
        ci.clipped = VK_TRUE;
        vr = vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateSwapchainKHR", vr); return false; }
        uint32_t count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, nullptr);
        swapImages_.resize(count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, swapImages_.data());
        return true;
    }

    uint32_t pickMemoryType(uint32_t bits) const {
        VkPhysicalDeviceMemoryProperties props{};
        vkGetPhysicalDeviceMemoryProperties(physical_, &props);
        for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
            if (bits & (1u << i)) return i;
        }
        return UINT32_MAX;
    }

    VkFormat formatForFourcc(uint32_t value) const {
        if (value == DRM_FORMAT_ABGR8888 || value == DRM_FORMAT_XBGR8888) return VK_FORMAT_R8G8B8A8_UNORM;
        if (value == DRM_FORMAT_ARGB8888 || value == DRM_FORMAT_XRGB8888) return VK_FORMAT_B8G8R8A8_UNORM;
        return VK_FORMAT_UNDEFINED;
    }

    void destroySourceLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        if (source_.image != VK_NULL_HANDLE) vkDestroyImage(device_, source_.image, nullptr);
        if (source_.memory != VK_NULL_HANDLE) vkFreeMemory(device_, source_.memory, nullptr);
        if (source_.fd >= 0) close(source_.fd);
        source_ = {};
    }

    bool importSourceLocked(int fd, const FrameMessage &msg) {
        if (!createVulkanLocked()) { close(fd); return false; }
        destroySourceLocked();
        const VkFormat format = formatForFourcc(msg.fourcc);
        if (format == VK_FORMAT_UNDEFINED) {
            status_ = "unsupported KWin dma-buf fourcc=" + std::to_string(msg.fourcc);
            close(fd);
            return false;
        }
        if (msg.modifier != kDrmModifierLinear && !hasDrmModifier_) {
            status_ = "non-linear KWin dma-buf requires VK_EXT_image_drm_format_modifier";
            close(fd);
            return false;
        }

        source_.fd = fd;
        source_.width = msg.width;
        source_.height = msg.height;
        source_.fourcc = msg.fourcc;
        source_.stride = msg.stride;
        source_.offset = msg.offset;
        source_.modifier = msg.modifier;

        VkSubresourceLayout plane{};
        plane.offset = msg.offset;
        plane.rowPitch = msg.stride;
        VkImageDrmFormatModifierExplicitCreateInfoEXT modifierInfo{
            VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT};
        modifierInfo.drmFormatModifier = msg.modifier;
        modifierInfo.drmFormatModifierPlaneCount = 1;
        modifierInfo.pPlaneLayouts = &plane;

        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        if (hasDrmModifier_) external.pNext = &modifierInfo;

        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.pNext = &external;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {msg.width, msg.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = hasDrmModifier_ ? VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT : VK_IMAGE_TILING_LINEAR;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult vr = vkCreateImage(device_, &imageInfo, nullptr, &source_.image);
        if (vr != VK_SUCCESS) { status_ = vkerr("source vkCreateImage", vr); destroySourceLocked(); return false; }

        VkMemoryRequirements req{};
        vkGetImageMemoryRequirements(device_, source_.image, &req);
        VkMemoryFdPropertiesKHR fdProps{VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        vr = getMemoryFdProperties_(device_, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &fdProps);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkGetMemoryFdPropertiesKHR", vr); destroySourceLocked(); return false; }
        const uint32_t memoryType = pickMemoryType(req.memoryTypeBits & fdProps.memoryTypeBits);
        if (memoryType == UINT32_MAX) { status_ = "no compatible memory type for KWin dma-buf"; destroySourceLocked(); return false; }

        VkImportMemoryFdInfoKHR importInfo{VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR};
        importInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        importInfo.fd = fd;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &importInfo;
        dedicated.image = source_.image;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.pNext = &dedicated;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = memoryType;
        vr = vkAllocateMemory(device_, &mai, nullptr, &source_.memory);
        if (vr != VK_SUCCESS) {
            status_ = vkerr("source vkAllocateMemory", vr);
            destroySourceLocked();
            return false;
        }
        // Vulkan owns the imported fd after successful vkAllocateMemory.
        source_.fd = -1;
        vr = vkBindImageMemory(device_, source_.image, source_.memory, 0);
        if (vr != VK_SUCCESS) { status_ = vkerr("source vkBindImageMemory", vr); destroySourceLocked(); return false; }
        status_ = "source-imported-direct:" + std::to_string(msg.width) + "x" + std::to_string(msg.height);
        logi(status_);
        return true;
    }

    static void imageBarrier(VkCommandBuffer cmd, VkImage image, VkImageLayout oldLayout,
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

    bool presentLocked() {
        if (source_.image == VK_NULL_HANDLE || !createVulkanLocked()) return false;

        FrameContext &frame = frames_[frameIndex_++ % kFramesInFlight];
        VkResult vr = vkWaitForFences(device_, 1, &frame.fence, VK_TRUE, UINT64_MAX);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkWaitForFences", vr); return false; }
        vkResetFences(device_, 1, &frame.fence);

        uint32_t swapIndex = 0;
        vr = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, frame.acquire, VK_NULL_HANDLE, &swapIndex);
        if (vr == VK_ERROR_OUT_OF_DATE_KHR) {
            status_ = "swapchain-out-of-date";
            return false;
        }
        if (vr != VK_SUCCESS && vr != VK_SUBOPTIMAL_KHR) {
            status_ = vkerr("vkAcquireNextImageKHR", vr);
            return false;
        }

        vkResetCommandBuffer(frame.command, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vr = vkBeginCommandBuffer(frame.command, &bi);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkBeginCommandBuffer", vr); return false; }

        imageBarrier(frame.command, source_.image,
                     VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                     VK_QUEUE_FAMILY_EXTERNAL, queueFamily_);
        imageBarrier(frame.command, swapImages_[swapIndex],
                     VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     0, VK_ACCESS_TRANSFER_WRITE_BIT, queueFamily_, queueFamily_);

        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = {static_cast<int32_t>(source_.width), static_cast<int32_t>(source_.height), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1] = {static_cast<int32_t>(swapExtent_.width), static_cast<int32_t>(swapExtent_.height), 1};
        vkCmdBlitImage(frame.command,
                       source_.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapImages_[swapIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       1, &blit, VK_FILTER_LINEAR);

        imageBarrier(frame.command, swapImages_[swapIndex],
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                     VK_ACCESS_TRANSFER_WRITE_BIT, 0, queueFamily_, queueFamily_);
        imageBarrier(frame.command, source_.image,
                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                     VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                     queueFamily_, VK_QUEUE_FAMILY_EXTERNAL);
        vr = vkEndCommandBuffer(frame.command);
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
        if (vr != VK_SUCCESS && vr != VK_SUBOPTIMAL_KHR) {
            status_ = vkerr("vkQueuePresentKHR", vr);
            return false;
        }

        // Intentionally no vkQueueWaitIdle here. Three frame contexts keep GPU
        // work and SurfaceFlinger presentation pipelined instead of stalling the
        // render thread after every frame.
        ++presented_;
        if (presented_ <= 3 || presented_ % 120 == 0) {
            status_ = "presenting-direct-gpu frames=" + std::to_string(presented_);
            logi(status_);
        }
        return true;
    }

    void destroyVulkanLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySourceLocked();
        if (device_ != VK_NULL_HANDLE) {
            for (auto &frame : frames_) {
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
            std::lock_guard<std::mutex> guard(lock_);
            if (msg.type == kMsgImport) {
                if (fd < 0) { status_ = "frame-import-missing-fd"; continue; }
                importSourceLocked(fd, msg);
            } else if (msg.type == kMsgFrame) {
                if (fd >= 0) close(fd);
                presentLocked();
            } else if (msg.type == kMsgReset) {
                if (fd >= 0) close(fd);
                destroySourceLocked();
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
        if (listen(fd, 2) != 0) {
            const std::string error = std::string("frame listen: ") + strerror(errno);
            close(fd); setStatus(error); return;
        }
        {
            std::lock_guard<std::mutex> guard(lock_);
            listenFd_ = fd;
            status_ = "wayland-frame-server-ready";
        }
        logi("abstract AF_UNIX direct Vulkan frame server ready");
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
