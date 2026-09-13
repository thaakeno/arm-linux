#include <jni.h>

#include <android/hardware_buffer.h>
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
constexpr uint32_t kMagic = 0x31574656u; // "VFW1" little-endian
constexpr uint32_t kMsgImport = 1;
constexpr uint32_t kMsgFrame = 2;
constexpr uint32_t kMsgReset = 3;
constexpr uint32_t kAhbCount = 3;
constexpr uint64_t kDrmModifierLinear = 0;

// Common DRM fourcc values. Keep these local so libdrm is not an APK dependency.
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

struct AhbImage {
    AHardwareBuffer *buffer = nullptr;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    bool initialized = false;
};

void loge(const std::string &s) { __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", s.c_str()); }
void logi(const std::string &s) { __android_log_print(ANDROID_LOG_INFO, kTag, "%s", s.c_str()); }

std::string vkerr(const char *what, VkResult result) {
    return std::string(what) + " failed VkResult=" + std::to_string(static_cast<int>(result));
}

bool recv_exact(int fd, void *dst, size_t size) {
    auto *p = static_cast<uint8_t *>(dst);
    while (size > 0) {
        const ssize_t n = recv(fd, p, size, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= static_cast<size_t>(n);
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
    VkCommandBuffer command_ = VK_NULL_HANDLE;
    VkSemaphore acquireSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderSemaphore_ = VK_NULL_HANDLE;

    PFN_vkGetMemoryFdPropertiesKHR getMemoryFdProperties_ = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties_ = nullptr;

    bool hasDrmModifier_ = false;
    ImportedImage source_{};
    std::array<AhbImage, kAhbCount> ahb_{};
    uint32_t ahbIndex_ = 0;
    uint64_t frames_ = 0;

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
        app.pApplicationName = "Vessel Wayland Presenter";
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

        const std::array<const char *, 4> required = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
            VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
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
        getAhbProperties_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (!getMemoryFdProperties_ || !getAhbProperties_) {
            status_ = "external-memory Vulkan entry points unavailable";
            return false;
        }

        if (!createSwapchainLocked()) return false;
        if (!createAhbRingLocked()) return false;

        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cp.queueFamilyIndex = queueFamily_;
        cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        vr = vkCreateCommandPool(device_, &cp, nullptr, &commandPool_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkCreateCommandPool", vr); return false; }
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = commandPool_;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = 1;
        vr = vkAllocateCommandBuffers(device_, &cai, &command_);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkAllocateCommandBuffers", vr); return false; }
        VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if ((vr = vkCreateSemaphore(device_, &sci, nullptr, &acquireSemaphore_)) != VK_SUCCESS ||
            (vr = vkCreateSemaphore(device_, &sci, nullptr, &renderSemaphore_)) != VK_SUCCESS) {
            status_ = vkerr("vkCreateSemaphore", vr);
            return false;
        }

        status_ = std::string("ready:Vulkan+dma-buf+AHardwareBuffer") + (hasDrmModifier_ ? "+drm-modifier" : "");
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
        uint32_t imageCount = std::max(3u, caps.minImageCount);
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
        VkResult vr2 = vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_);
        if (vr2 != VK_SUCCESS) { status_ = vkerr("vkCreateSwapchainKHR", vr2); return false; }
        uint32_t count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, nullptr);
        swapImages_.resize(count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, swapImages_.data());
        return true;
    }

    uint32_t pickMemoryType(uint32_t bits, VkMemoryPropertyFlags preferred = 0) const {
        VkPhysicalDeviceMemoryProperties props{};
        vkGetPhysicalDeviceMemoryProperties(physical_, &props);
        for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & preferred) == preferred) return i;
        }
        for (uint32_t i = 0; i < props.memoryTypeCount; ++i) if (bits & (1u << i)) return i;
        return UINT32_MAX;
    }

    bool createAhbRingLocked() {
        for (auto &slot : ahb_) {
            AHardwareBuffer_Desc desc{};
            desc.width = swapExtent_.width;
            desc.height = swapExtent_.height;
            desc.layers = 1;
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            desc.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
            if (!AHardwareBuffer_isSupported(&desc)) {
                status_ = "RGBA8 GPU AHardwareBuffer unsupported";
                return false;
            }
            if (AHardwareBuffer_allocate(&desc, &slot.buffer) != 0 || !slot.buffer) {
                status_ = "AHardwareBuffer_allocate failed";
                return false;
            }
            VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
            external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
            VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.pNext = &external;
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
            imageInfo.extent = {swapExtent_.width, swapExtent_.height, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            VkResult vr = vkCreateImage(device_, &imageInfo, nullptr, &slot.image);
            if (vr != VK_SUCCESS) { status_ = vkerr("AHB vkCreateImage", vr); return false; }
            VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
            vr = getAhbProperties_(device_, slot.buffer, &props);
            if (vr != VK_SUCCESS) { status_ = vkerr("vkGetAndroidHardwareBufferPropertiesANDROID", vr); return false; }
            const uint32_t memoryType = pickMemoryType(props.memoryTypeBits);
            if (memoryType == UINT32_MAX) { status_ = "no memory type for AHardwareBuffer"; return false; }
            VkImportAndroidHardwareBufferInfoANDROID importInfo{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
            importInfo.buffer = slot.buffer;
            VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
            dedicated.pNext = &importInfo;
            dedicated.image = slot.image;
            VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            mai.pNext = &dedicated;
            mai.allocationSize = props.allocationSize;
            mai.memoryTypeIndex = memoryType;
            vr = vkAllocateMemory(device_, &mai, nullptr, &slot.memory);
            if (vr != VK_SUCCESS) { status_ = vkerr("AHB vkAllocateMemory", vr); return false; }
            vr = vkBindImageMemory(device_, slot.image, slot.memory, 0);
            if (vr != VK_SUCCESS) { status_ = vkerr("AHB vkBindImageMemory", vr); return false; }
        }
        return true;
    }

    VkFormat formatForFourcc(uint32_t value) const {
        if (value == DRM_FORMAT_ABGR8888 || value == DRM_FORMAT_XBGR8888) return VK_FORMAT_R8G8B8A8_UNORM;
        if (value == DRM_FORMAT_ARGB8888 || value == DRM_FORMAT_XRGB8888) return VK_FORMAT_B8G8R8A8_UNORM;
        return VK_FORMAT_UNDEFINED;
    }

    void destroySourceLocked() {
        if (device_ != VK_NULL_HANDLE && queue_ != VK_NULL_HANDLE) vkQueueWaitIdle(queue_);
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
            status_ = "KWin exported a DRM modifier but Android Vulkan lacks VK_EXT_image_drm_format_modifier";
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

        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        VkSubresourceLayout plane{};
        plane.offset = msg.offset;
        plane.rowPitch = msg.stride;
        VkImageDrmFormatModifierExplicitCreateInfoEXT modifierInfo{VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT};
        modifierInfo.drmFormatModifier = msg.modifier;
        modifierInfo.drmFormatModifierPlaneCount = 1;
        modifierInfo.pPlaneLayouts = &plane;
        if (msg.modifier != kDrmModifierLinear) external.pNext = &modifierInfo;

        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.pNext = &external;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {msg.width, msg.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = msg.modifier == kDrmModifierLinear ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT;
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
        if (vr != VK_SUCCESS) { status_ = vkerr("source vkAllocateMemory", vr); source_.fd = -1; close(fd); destroySourceLocked(); return false; }
        // Vulkan consumed the fd on successful import.
        source_.fd = -1;
        vr = vkBindImageMemory(device_, source_.image, source_.memory, 0);
        if (vr != VK_SUCCESS) { status_ = vkerr("source vkBindImageMemory", vr); destroySourceLocked(); return false; }
        status_ = "source-imported:" + std::to_string(msg.width) + "x" + std::to_string(msg.height);
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
        uint32_t swapIndex = 0;
        VkResult vr = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, acquireSemaphore_, VK_NULL_HANDLE, &swapIndex);
        if (vr == VK_ERROR_OUT_OF_DATE_KHR) {
            destroyVulkanLocked();
            status_ = "swapchain-out-of-date";
            return false;
        }
        if (vr != VK_SUCCESS && vr != VK_SUBOPTIMAL_KHR) { status_ = vkerr("vkAcquireNextImageKHR", vr); return false; }

        vkResetCommandBuffer(command_, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(command_, &bi);

        AhbImage &mid = ahb_[ahbIndex_++ % kAhbCount];
        imageBarrier(command_, source_.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                     VK_QUEUE_FAMILY_EXTERNAL, queueFamily_);
        imageBarrier(command_, mid.image,
                     mid.initialized ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     mid.initialized ? VK_ACCESS_TRANSFER_READ_BIT : 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                     queueFamily_, queueFamily_);

        VkImageBlit first{};
        first.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        first.srcSubresource.layerCount = 1;
        first.srcOffsets[1] = {static_cast<int32_t>(source_.width), static_cast<int32_t>(source_.height), 1};
        first.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        first.dstSubresource.layerCount = 1;
        first.dstOffsets[1] = {static_cast<int32_t>(swapExtent_.width), static_cast<int32_t>(swapExtent_.height), 1};
        vkCmdBlitImage(command_, source_.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       mid.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &first, VK_FILTER_LINEAR);

        imageBarrier(command_, mid.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, queueFamily_, queueFamily_);
        imageBarrier(command_, swapImages_[swapIndex], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     0, VK_ACCESS_TRANSFER_WRITE_BIT, queueFamily_, queueFamily_);

        VkImageCopy copy{};
        copy.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.srcSubresource.layerCount = 1;
        copy.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.dstSubresource.layerCount = 1;
        copy.extent = {swapExtent_.width, swapExtent_.height, 1};
        vkCmdCopyImage(command_, mid.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapImages_[swapIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

        imageBarrier(command_, swapImages_[swapIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                     VK_ACCESS_TRANSFER_WRITE_BIT, 0, queueFamily_, queueFamily_);
        imageBarrier(command_, source_.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                     VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                     queueFamily_, VK_QUEUE_FAMILY_EXTERNAL);
        vkEndCommandBuffer(command_);

        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &acquireSemaphore_;
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command_;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &renderSemaphore_;
        vr = vkQueueSubmit(queue_, 1, &submit, VK_NULL_HANDLE);
        if (vr != VK_SUCCESS) { status_ = vkerr("vkQueueSubmit", vr); return false; }
        VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores = &renderSemaphore_;
        pi.swapchainCount = 1;
        pi.pSwapchains = &swapchain_;
        pi.pImageIndices = &swapIndex;
        vr = vkQueuePresentKHR(queue_, &pi);
        if (vr != VK_SUCCESS && vr != VK_SUBOPTIMAL_KHR) { status_ = vkerr("vkQueuePresentKHR", vr); return false; }
        // The producer calls glFinish before frame notification. Waiting here keeps
        // buffer ownership simple and deterministic until explicit sync_fd handoff
        // is negotiated by both ends.
        vkQueueWaitIdle(queue_);
        mid.initialized = true;
        ++frames_;
        if (frames_ <= 3 || frames_ % 120 == 0) {
            status_ = "presenting-gpu-only frames=" + std::to_string(frames_);
            logi(status_);
        }
        return true;
    }

    void destroyVulkanLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySourceLocked();
        if (device_ != VK_NULL_HANDLE) {
            for (auto &slot : ahb_) {
                if (slot.image != VK_NULL_HANDLE) vkDestroyImage(device_, slot.image, nullptr);
                if (slot.memory != VK_NULL_HANDLE) vkFreeMemory(device_, slot.memory, nullptr);
                if (slot.buffer) AHardwareBuffer_release(slot.buffer);
                slot = {};
            }
            if (acquireSemaphore_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, acquireSemaphore_, nullptr);
            if (renderSemaphore_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, renderSemaphore_, nullptr);
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
        command_ = VK_NULL_HANDLE;
        acquireSemaphore_ = VK_NULL_HANDLE;
        renderSemaphore_ = VK_NULL_HANDLE;
        getMemoryFdProperties_ = nullptr;
        getAhbProperties_ = nullptr;
        hasDrmModifier_ = false;
        ahbIndex_ = 0;
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
        logi("abstract AF_UNIX frame server ready");
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
