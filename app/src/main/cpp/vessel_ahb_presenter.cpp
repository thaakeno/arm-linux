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
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VesselAhbPresenter";
constexpr uint32_t MAGIC = 0x42484156u;
constexpr uint32_t VERSION = 3;
constexpr uint32_t MSG_REGISTER_FRAME = 1;
constexpr uint32_t MSG_FRAME = 2;
constexpr uint32_t MSG_DISABLE = 3;
constexpr uint32_t FRAME_SLOTS = 3;
constexpr uint32_t FRAMES_IN_FLIGHT = 3;
constexpr uint8_t FENCE_TAG = 0xF3;

#pragma pack(push, 1)
struct AhbMessage {
    uint32_t magic;
    uint32_t version;
    uint32_t type;
    uint32_t scanout_id;
    uint32_t slot;
    uint32_t serial;
    uint32_t width;
    uint32_t height;
};
struct AckMessage {
    uint32_t magic;
    uint32_t version;
    uint32_t scanout_id;
    uint32_t slot;
    uint32_t serial;
    uint32_t ok;
};
#pragma pack(pop)

struct SourceSlot {
    AHardwareBuffer* buffer = nullptr;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct Frame {
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkSemaphore acquire = VK_NULL_HANDLE;
    VkSemaphore render = VK_NULL_HANDLE;
    VkSemaphore producer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool pending = false;
    uint32_t scanout = 0;
    uint32_t slot = 0;
    uint32_t serial = 0;
};

void emit_log(int priority, const char* level, const std::string& message) {
    __android_log_print(priority, TAG, "%s", message.c_str());
    std::fprintf(stderr, "[AHB-P/%s] %s\n", level, message.c_str());
    std::fflush(stderr);
}
void logi(const std::string& message) { emit_log(ANDROID_LOG_INFO, "I", message); }
void loge(const std::string& message) { emit_log(ANDROID_LOG_ERROR, "E", message); }

std::string vk_error(const char* what, VkResult result) {
    return std::string(what) + " VkResult=" + std::to_string(static_cast<int>(result));
}

bool recv_all(int fd, void* data, size_t size) {
    auto* p = static_cast<uint8_t*>(data);
    while (size) {
        const ssize_t n = recv(fd, p, size, MSG_WAITALL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += static_cast<size_t>(n);
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool send_ack(int fd, uint32_t scanout, uint32_t slot, uint32_t serial, bool ok) {
    if (fd < 0 || serial == 0) return true;
    const AckMessage ack{MAGIC, VERSION, scanout, slot, serial, ok ? 1u : 0u};
    const auto* p = reinterpret_cast<const uint8_t*>(&ack);
    size_t left = sizeof(ack);
    while (left) {
        const ssize_t n = send(fd, p + sizeof(ack) - left, left, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        left -= static_cast<size_t>(n);
    }
    return true;
}

int recv_fence_fd(int socket_fd) {
    uint8_t tag = 0;
    iovec io{&tag, sizeof(tag)};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr msg{};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n;
    do { n = recvmsg(socket_fd, &msg, MSG_CMSG_CLOEXEC); } while (n < 0 && errno == EINTR);
    if (n != static_cast<ssize_t>(sizeof(tag)) || tag != FENCE_TAG) return -1;
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS && cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int fd = -1;
            std::memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
            return fd;
        }
    }
    return -1;
}

bool wait_sync_fd(int fd) {
    if (fd < 0) return false;
    pollfd pfd{fd, POLLIN, 0};
    for (;;) {
        const int rc = poll(&pfd, 1, -1);
        if (rc < 0 && errno == EINTR) continue;
        return rc > 0 && !(pfd.revents & (POLLNVAL | POLLHUP));
    }
}

class AhbPresenter {
public:
    void configure(uint32_t width, uint32_t height) {
        std::lock_guard<std::mutex> guard(lock_);
        preferred_width_ = std::clamp(width, 640u, 3840u);
        preferred_height_ = std::clamp(height, 480u, 2160u);
    }

    void start() {
        std::lock_guard<std::mutex> guard(lock_);
        if (server_.joinable()) return;
        stop_ = false;
        server_ = std::thread([this] { server_loop(); });
    }

    void stop() {
        stop_ = true;
        int listen_fd = -1, client_fd = -1;
        {
            std::lock_guard<std::mutex> guard(lock_);
            listen_fd = listen_fd_;
            client_fd = client_fd_;
            listen_fd_ = -1;
            client_fd_ = -1;
        }
        if (client_fd >= 0) shutdown(client_fd, SHUT_RDWR);
        if (listen_fd >= 0) shutdown(listen_fd, SHUT_RDWR);
        if (server_.joinable()) server_.join();
        std::lock_guard<std::mutex> guard(lock_);
        if (device_) vkDeviceWaitIdle(device_);
        destroy_vulkan_locked();
        release_window_locked();
        clear_sources_locked();
        status_ = "stopped";
    }

    void attach(JNIEnv* env, jobject surface) {
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        if (!window) {
            std::lock_guard<std::mutex> guard(lock_);
            status_ = "presenter-error:ahb-surface";
            return;
        }
        std::lock_guard<std::mutex> guard(lock_);
        if (window_ == window && device_) {
            ANativeWindow_release(window);
            return;
        }
        if (device_) {
            finish_pending_locked(client_fd_, true);
            vkDeviceWaitIdle(device_);
        }
        destroy_vulkan_locked();
        release_window_locked();
        window_ = window;
        status_ = "surface-attached";
        logi("Android surface attached");
        if (latest_slot_ < FRAME_SLOTS && sources_[latest_slot_].buffer) {
            if (create_vulkan_locked() && present_locked(-1, 0, latest_slot_, 0, -1, false)) {
                status_ = "presenting-ahardwarebuffer";
                logi("repainted retained GPU frame after surface attach");
            }
        }
    }

    void detach() {
        std::lock_guard<std::mutex> guard(lock_);
        if (!window_ && !device_) return;
        if (device_) {
            finish_pending_locked(client_fd_, true);
            vkDeviceWaitIdle(device_);
        }
        destroy_vulkan_locked();
        release_window_locked();
        status_ = has_source_locked() ? "frame-ready-waiting-for-surface" : "surface-detached";
    }

    std::string status() {
        std::lock_guard<std::mutex> guard(lock_);
        return status_;
    }

    uint32_t width() {
        std::lock_guard<std::mutex> guard(lock_);
        return latest_width_ ? latest_width_ : preferred_width_;
    }

    uint32_t height() {
        std::lock_guard<std::mutex> guard(lock_);
        return latest_height_ ? latest_height_ : preferred_height_;
    }

private:
    std::mutex lock_;
    std::atomic<bool> stop_{false};
    std::thread server_;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    std::string status_ = "not-started";
    uint32_t preferred_width_ = 1920;
    uint32_t preferred_height_ = 1080;
    uint32_t latest_width_ = 0;
    uint32_t latest_height_ = 0;
    uint32_t latest_slot_ = UINT32_MAX;
    ANativeWindow* window_ = nullptr;
    std::array<SourceSlot, FRAME_SLOTS> sources_{};

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queue_family_ = UINT32_MAX;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkExtent2D extent_{};
    std::vector<VkImage> swapchain_images_;
    VkCommandPool command_pool_ = VK_NULL_HANDLE;
    std::array<Frame, FRAMES_IN_FLIGHT> frames_{};
    uint32_t frame_number_ = 0;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_properties_ = nullptr;
    PFN_vkImportSemaphoreFdKHR import_semaphore_fd_ = nullptr;

    static bool has_extension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
        return std::any_of(extensions.begin(), extensions.end(), [&](const auto& ext) { return strcmp(ext.extensionName, name) == 0; });
    }

    bool has_source_locked() const {
        return std::any_of(sources_.begin(), sources_.end(), [](const auto& s) { return s.buffer != nullptr; });
    }

    void release_window_locked() {
        if (window_) { ANativeWindow_release(window_); window_ = nullptr; }
    }

    void destroy_source_import_locked(SourceSlot& source) {
        if (device_) {
            if (source.image) vkDestroyImage(device_, source.image, nullptr);
            if (source.memory) vkFreeMemory(device_, source.memory, nullptr);
        }
        source.image = VK_NULL_HANDLE;
        source.memory = VK_NULL_HANDLE;
    }

    void clear_source_locked(SourceSlot& source) {
        destroy_source_import_locked(source);
        if (source.buffer) AHardwareBuffer_release(source.buffer);
        source = {};
    }

    void clear_sources_locked() {
        for (auto& source : sources_) clear_source_locked(source);
        latest_width_ = 0;
        latest_height_ = 0;
        latest_slot_ = UINT32_MAX;
    }

    void destroy_imports_locked() {
        for (auto& source : sources_) destroy_source_import_locked(source);
    }

    uint32_t memory_type(uint32_t bits) const {
        VkPhysicalDeviceMemoryProperties properties{};
        vkGetPhysicalDeviceMemoryProperties(physical_, &properties);
        for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) if (bits & (1u << i)) return i;
        return UINT32_MAX;
    }

    void image_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout, VkImageLayout new_layout,
                       VkAccessFlags src_access, VkAccessFlags dst_access, uint32_t src_family, uint32_t dst_family) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = src_access;
        barrier.dstAccessMask = dst_access;
        barrier.oldLayout = old_layout;
        barrier.newLayout = new_layout;
        barrier.srcQueueFamilyIndex = src_family;
        barrier.dstQueueFamilyIndex = dst_family;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                             0, nullptr, 0, nullptr, 1, &barrier);
    }

    bool create_vulkan_locked() {
        if (device_) return true;
        if (!window_) { status_ = "frame-ready-waiting-for-surface"; return false; }
        const char* instance_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Vessel AHardwareBuffer Presenter";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ii.pApplicationInfo = &app;
        ii.enabledExtensionCount = 2;
        ii.ppEnabledExtensionNames = instance_extensions;
        VkResult result = vkCreateInstance(&ii, nullptr, &instance_);
        if (result != VK_SUCCESS) { status_ = vk_error("vkCreateInstance", result); return false; }

        VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        si.window = window_;
        result = vkCreateAndroidSurfaceKHR(instance_, &si, nullptr, &surface_);
        if (result != VK_SUCCESS) { status_ = vk_error("vkCreateAndroidSurfaceKHR", result); return false; }

        uint32_t physical_count = 0;
        vkEnumeratePhysicalDevices(instance_, &physical_count, nullptr);
        std::vector<VkPhysicalDevice> devices(physical_count);
        vkEnumeratePhysicalDevices(instance_, &physical_count, devices.data());
        for (VkPhysicalDevice candidate : devices) {
            uint32_t queue_count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queue_count, nullptr);
            std::vector<VkQueueFamilyProperties> queues(queue_count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queue_count, queues.data());
            for (uint32_t i = 0; i < queue_count; ++i) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &present);
                if ((queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) { physical_ = candidate; queue_family_ = i; break; }
            }
            if (physical_) break;
        }
        if (!physical_) { status_ = "presenter-error:no-present-queue"; return false; }

        uint32_t ext_count = 0;
        vkEnumerateDeviceExtensionProperties(physical_, nullptr, &ext_count, nullptr);
        std::vector<VkExtensionProperties> extensions(ext_count);
        vkEnumerateDeviceExtensionProperties(physical_, nullptr, &ext_count, extensions.data());
        const char* required[] = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        };
        for (const char* ext : required) {
            if (!has_extension(extensions, ext)) { status_ = std::string("presenter-error:missing-") + ext; return false; }
        }

        const float priority = 1.0f;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex = queue_family_;
        qi.queueCount = 1;
        qi.pQueuePriorities = &priority;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        di.queueCreateInfoCount = 1;
        di.pQueueCreateInfos = &qi;
        di.enabledExtensionCount = 4;
        di.ppEnabledExtensionNames = required;
        result = vkCreateDevice(physical_, &di, nullptr, &device_);
        if (result != VK_SUCCESS) { status_ = vk_error("vkCreateDevice", result); return false; }
        vkGetDeviceQueue(device_, queue_family_, 0, &queue_);
        get_ahb_properties_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        import_semaphore_fd_ = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(vkGetDeviceProcAddr(device_, "vkImportSemaphoreFdKHR"));
        if (!get_ahb_properties_ || !import_semaphore_fd_) { status_ = "presenter-error:missing-external-sync-entrypoint"; return false; }

        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps);
        uint32_t format_count = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &format_count, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(format_count);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &format_count, formats.data());
        if (formats.empty()) { status_ = "presenter-error:no-surface-format"; return false; }
        VkSurfaceFormatKHR chosen = formats.front();
        for (const auto& f : formats) {
            if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                chosen = f;
                break;
            }
        }

        extent_ = caps.currentExtent;
        if (extent_.width == UINT32_MAX) {
            extent_.width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
            extent_.height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
            extent_.width = std::clamp(extent_.width, caps.minImageExtent.width, caps.maxImageExtent.width);
            extent_.height = std::clamp(extent_.height, caps.minImageExtent.height, caps.maxImageExtent.height);
        }

        uint32_t mode_count = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &mode_count, nullptr);
        std::vector<VkPresentModeKHR> modes(mode_count);
        vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &mode_count, modes.data());
        VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR;
        if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) present_mode = VK_PRESENT_MODE_MAILBOX_KHR;

        uint32_t image_count = std::max(FRAMES_IN_FLIGHT, caps.minImageCount);
        if (caps.maxImageCount) image_count = std::min(image_count, caps.maxImageCount);
        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface = surface_;
        sci.minImageCount = image_count;
        sci.imageFormat = chosen.format;
        sci.imageColorSpace = chosen.colorSpace;
        sci.imageExtent = extent_;
        sci.imageArrayLayers = 1;
        sci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform = caps.currentTransform;
        sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        sci.presentMode = present_mode;
        sci.clipped = VK_TRUE;
        result = vkCreateSwapchainKHR(device_, &sci, nullptr, &swapchain_);
        if (result != VK_SUCCESS) { status_ = vk_error("vkCreateSwapchainKHR", result); return false; }
        uint32_t swap_count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &swap_count, nullptr);
        swapchain_images_.resize(swap_count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &swap_count, swapchain_images_.data());

        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pci.queueFamilyIndex = queue_family_;
        result = vkCreateCommandPool(device_, &pci, nullptr, &command_pool_);
        if (result != VK_SUCCESS) { status_ = vk_error("vkCreateCommandPool", result); return false; }
        std::array<VkCommandBuffer, FRAMES_IN_FLIGHT> commands{};
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = command_pool_;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = FRAMES_IN_FLIGHT;
        result = vkAllocateCommandBuffers(device_, &cai, commands.data());
        if (result != VK_SUCCESS) { status_ = vk_error("vkAllocateCommandBuffers", result); return false; }
        VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (uint32_t i = 0; i < FRAMES_IN_FLIGHT; ++i) {
            frames_[i].cmd = commands[i];
            if (vkCreateSemaphore(device_, &sem, nullptr, &frames_[i].acquire) != VK_SUCCESS ||
                vkCreateSemaphore(device_, &sem, nullptr, &frames_[i].render) != VK_SUCCESS ||
                vkCreateSemaphore(device_, &sem, nullptr, &frames_[i].producer) != VK_SUCCESS ||
                vkCreateFence(device_, &fi, nullptr, &frames_[i].fence) != VK_SUCCESS) {
                status_ = "presenter-error:frame-sync-create";
                return false;
            }
        }
        logi(std::string("Vulkan presenter ready presentMode=") + (present_mode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "FIFO") + " frames=3 producerSyncFd=1");
        status_ = "ahb-vulkan-ready";
        return true;
    }

    bool import_source_locked(uint32_t slot_index) {
        auto& source = sources_[slot_index];
        if (source.image) return true;
        if (!source.buffer || !create_vulkan_locked()) return false;
        VkAndroidHardwareBufferFormatPropertiesANDROID fp{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        props.pNext = &fp;
        VkResult result = get_ahb_properties_(device_, source.buffer, &props);
        if (result != VK_SUCCESS) { status_ = vk_error("vkGetAndroidHardwareBufferPropertiesANDROID", result); return false; }
        if (fp.format == VK_FORMAT_UNDEFINED) { status_ = "presenter-error:ahb-external-format-not-transferable"; return false; }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(source.buffer, &desc);
        source.width = desc.width;
        source.height = desc.height;
        VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ii.pNext = &ext;
        ii.imageType = VK_IMAGE_TYPE_2D;
        ii.format = fp.format;
        ii.extent = {source.width, source.height, 1};
        ii.mipLevels = 1;
        ii.arrayLayers = 1;
        ii.samples = VK_SAMPLE_COUNT_1_BIT;
        ii.tiling = VK_IMAGE_TILING_OPTIMAL;
        ii.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        result = vkCreateImage(device_, &ii, nullptr, &source.image);
        if (result != VK_SUCCESS) { status_ = vk_error("vkCreateImage(ahb)", result); return false; }
        VkMemoryRequirements req{};
        vkGetImageMemoryRequirements(device_, source.image, &req);
        const uint32_t type = memory_type(req.memoryTypeBits & props.memoryTypeBits);
        if (type == UINT32_MAX) { status_ = "presenter-error:no-ahb-memory-type"; return false; }
        VkImportAndroidHardwareBufferInfoANDROID import{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        import.buffer = source.buffer;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &import;
        dedicated.image = source.image;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.pNext = &dedicated;
        mai.allocationSize = props.allocationSize;
        mai.memoryTypeIndex = type;
        result = vkAllocateMemory(device_, &mai, nullptr, &source.memory);
        if (result != VK_SUCCESS) { status_ = vk_error("vkAllocateMemory(ahb)", result); return false; }
        result = vkBindImageMemory(device_, source.image, source.memory, 0);
        if (result != VK_SUCCESS) { status_ = vk_error("vkBindImageMemory(ahb)", result); return false; }
        return true;
    }

    bool finish_frame_locked(int fd, Frame& frame, bool wait) {
        if (!frame.pending) return true;
        VkResult result = wait ? vkWaitForFences(device_, 1, &frame.fence, VK_TRUE, UINT64_MAX) : vkGetFenceStatus(device_, frame.fence);
        if (!wait && result == VK_NOT_READY) return true;
        if (result != VK_SUCCESS) { status_ = vk_error("frame fence", result); return false; }
        const bool sent = frame.serial == 0 || fd < 0 || send_ack(fd, frame.scanout, frame.slot, frame.serial, true);
        frame.pending = false;
        frame.serial = 0;
        return sent;
    }

    bool finish_pending_locked(int fd, bool wait_all) {
        for (auto& frame : frames_) {
            if (!frame.pending) continue;
            if (wait_all) {
                if (!finish_frame_locked(fd, frame, true)) return false;
            } else {
                VkResult result = vkGetFenceStatus(device_, frame.fence);
                if (result == VK_SUCCESS) {
                    if (!finish_frame_locked(fd, frame, true)) return false;
                } else if (result != VK_NOT_READY) {
                    status_ = vk_error("vkGetFenceStatus", result);
                    return false;
                }
            }
        }
        return true;
    }

    bool import_producer_fence_locked(Frame& frame, int fence_fd) {
        if (fence_fd < 0) return true;
        VkImportSemaphoreFdInfoKHR info{VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR};
        info.semaphore = frame.producer;
        info.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
        info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        info.fd = fence_fd;
        const VkResult result = import_semaphore_fd_(device_, &info);
        if (result != VK_SUCCESS) {
            close(fence_fd);
            status_ = vk_error("vkImportSemaphoreFdKHR", result);
            return false;
        }
        return true; // Vulkan owns fence_fd after successful SYNC_FD import.
    }

    bool recreate_swapchain_locked() {
        if (!window_) return false;
        if (device_) {
            finish_pending_locked(client_fd_, true);
            vkDeviceWaitIdle(device_);
        }
        destroy_vulkan_locked();
        return create_vulkan_locked();
    }

    bool present_locked(int fd, uint32_t scanout, uint32_t slot_index, uint32_t serial, int producer_fence_fd, bool allow_recreate = true) {
        auto& source = sources_[slot_index];
        latest_width_ = source.width;
        latest_height_ = source.height;
        latest_slot_ = slot_index;
        if (!window_) {
            bool ok = true;
            if (producer_fence_fd >= 0) {
                ok = wait_sync_fd(producer_fence_fd);
                close(producer_fence_fd);
            }
            status_ = "frame-ready-waiting-for-surface";
            return send_ack(fd, scanout, slot_index, serial, ok);
        }
        if (!import_source_locked(slot_index)) {
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            send_ack(fd, scanout, slot_index, serial, false);
            return false;
        }

        Frame& frame = frames_[frame_number_++ % FRAMES_IN_FLIGHT];
        if (frame.pending && !finish_frame_locked(fd, frame, true)) {
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            return false;
        }
        vkWaitForFences(device_, 1, &frame.fence, VK_TRUE, UINT64_MAX);
        vkResetFences(device_, 1, &frame.fence);

        uint32_t image_index = 0;
        VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, frame.acquire, VK_NULL_HANDLE, &image_index);
        if (result == VK_ERROR_OUT_OF_DATE_KHR && allow_recreate) {
            if (!recreate_swapchain_locked()) {
                if (producer_fence_fd >= 0) close(producer_fence_fd);
                send_ack(fd, scanout, slot_index, serial, false);
                return false;
            }
            return present_locked(fd, scanout, slot_index, serial, producer_fence_fd, false);
        }
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            status_ = vk_error("vkAcquireNextImageKHR", result);
            send_ack(fd, scanout, slot_index, serial, false);
            return false;
        }

        const bool has_producer_fence = producer_fence_fd >= 0;
        if (has_producer_fence && !import_producer_fence_locked(frame, producer_fence_fd)) {
            send_ack(fd, scanout, slot_index, serial, false);
            return false;
        }

        vkResetCommandBuffer(frame.cmd, 0);
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(frame.cmd, &begin);
        image_barrier(frame.cmd, source.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                      0, VK_ACCESS_TRANSFER_READ_BIT, VK_QUEUE_FAMILY_FOREIGN_EXT, queue_family_);
        image_barrier(frame.cmd, swapchain_images_[image_index], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                      0, VK_ACCESS_TRANSFER_WRITE_BIT, queue_family_, queue_family_);
        VkClearColorValue black{{0, 0, 0, 1}};
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        vkCmdClearColorImage(frame.cmd, swapchain_images_[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
        const double sx = static_cast<double>(extent_.width) / source.width;
        const double sy = static_cast<double>(extent_.height) / source.height;
        const double scale = std::min(sx, sy);
        const int32_t dw = static_cast<int32_t>(std::max(1.0, source.width * scale));
        const int32_t dh = static_cast<int32_t>(std::max(1.0, source.height * scale));
        const int32_t dx = (static_cast<int32_t>(extent_.width) - dw) / 2;
        const int32_t dy = (static_cast<int32_t>(extent_.height) - dh) / 2;
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = {static_cast<int32_t>(source.width), static_cast<int32_t>(source.height), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[0] = {dx, dy, 0};
        blit.dstOffsets[1] = {dx + dw, dy + dh, 1};
        vkCmdBlitImage(frame.cmd, source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapchain_images_[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
        image_barrier(frame.cmd, source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                      VK_ACCESS_TRANSFER_READ_BIT, 0, queue_family_, VK_QUEUE_FAMILY_FOREIGN_EXT);
        image_barrier(frame.cmd, swapchain_images_[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                      VK_ACCESS_TRANSFER_WRITE_BIT, 0, queue_family_, queue_family_);
        vkEndCommandBuffer(frame.cmd);

        std::array<VkSemaphore, 2> waits{frame.acquire, frame.producer};
        std::array<VkPipelineStageFlags, 2> wait_stages{VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = has_producer_fence ? 2u : 1u;
        submit.pWaitSemaphores = waits.data();
        submit.pWaitDstStageMask = wait_stages.data();
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &frame.cmd;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &frame.render;
        result = vkQueueSubmit(queue_, 1, &submit, frame.fence);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkQueueSubmit", result);
            send_ack(fd, scanout, slot_index, serial, false);
            return false;
        }
        VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores = &frame.render;
        pi.swapchainCount = 1;
        pi.pSwapchains = &swapchain_;
        pi.pImageIndices = &image_index;
        result = vkQueuePresentKHR(queue_, &pi);
        frame.pending = true;
        frame.scanout = scanout;
        frame.slot = slot_index;
        frame.serial = serial;
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
            status_ = "presenting-ahardwarebuffer";
            return true;
        }
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkQueuePresentKHR", result);
            return false;
        }
        status_ = "presenting-ahardwarebuffer";
        return true;
    }

    void destroy_vulkan_locked() {
        if (device_) vkDeviceWaitIdle(device_);
        destroy_imports_locked();
        if (device_) {
            for (auto& frame : frames_) {
                if (frame.acquire) vkDestroySemaphore(device_, frame.acquire, nullptr);
                if (frame.render) vkDestroySemaphore(device_, frame.render, nullptr);
                if (frame.producer) vkDestroySemaphore(device_, frame.producer, nullptr);
                if (frame.fence) vkDestroyFence(device_, frame.fence, nullptr);
                frame = {};
            }
            if (command_pool_) vkDestroyCommandPool(device_, command_pool_, nullptr);
            if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            vkDestroyDevice(device_, nullptr);
        }
        if (surface_ && instance_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        if (instance_) vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
        surface_ = VK_NULL_HANDLE;
        physical_ = VK_NULL_HANDLE;
        device_ = VK_NULL_HANDLE;
        queue_ = VK_NULL_HANDLE;
        queue_family_ = UINT32_MAX;
        swapchain_ = VK_NULL_HANDLE;
        swapchain_images_.clear();
        command_pool_ = VK_NULL_HANDLE;
        get_ahb_properties_ = nullptr;
        import_semaphore_fd_ = nullptr;
        frame_number_ = 0;
    }

    bool register_source_locked(uint32_t slot, AHardwareBuffer* incoming, uint32_t width, uint32_t height) {
        if (slot >= FRAME_SLOTS || !incoming) return false;
        auto& source = sources_[slot];
        destroy_source_import_locked(source);
        if (source.buffer) AHardwareBuffer_release(source.buffer);
        source.buffer = incoming;
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(incoming, &desc);
        source.width = desc.width ? desc.width : width;
        source.height = desc.height ? desc.height : height;
        latest_width_ = source.width;
        latest_height_ = source.height;
        latest_slot_ = slot;
        logi("registered source slot=" + std::to_string(slot) + " size=" + std::to_string(source.width) + "x" + std::to_string(source.height));
        return true;
    }

    bool handle_message(int fd, const AhbMessage& msg) {
        if (msg.magic != MAGIC || msg.version != VERSION || msg.scanout_id != 0) {
            send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
            return false;
        }
        if (msg.type == MSG_REGISTER_FRAME) {
            if (msg.slot >= FRAME_SLOTS) { send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false); return false; }
            AHardwareBuffer* incoming = nullptr;
            if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &incoming) != 0 || !incoming) {
                send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
                return false;
            }
            const int fence_fd = recv_fence_fd(fd);
            if (fence_fd < 0) {
                AHardwareBuffer_release(incoming);
                send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
                return false;
            }
            std::lock_guard<std::mutex> guard(lock_);
            if (!register_source_locked(msg.slot, incoming, msg.width, msg.height)) {
                AHardwareBuffer_release(incoming);
                close(fence_fd);
                send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
                return false;
            }
            return present_locked(fd, msg.scanout_id, msg.slot, msg.serial, fence_fd);
        }
        if (msg.type == MSG_FRAME) {
            const int fence_fd = recv_fence_fd(fd);
            if (fence_fd < 0) { send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false); return false; }
            std::lock_guard<std::mutex> guard(lock_);
            if (msg.slot >= FRAME_SLOTS || !sources_[msg.slot].buffer) {
                close(fence_fd);
                send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
                return false;
            }
            latest_slot_ = msg.slot;
            return present_locked(fd, msg.scanout_id, msg.slot, msg.serial, fence_fd);
        }
        if (msg.type == MSG_DISABLE) {
            std::lock_guard<std::mutex> guard(lock_);
            finish_pending_locked(fd, true);
            if (device_) vkDeviceWaitIdle(device_);
            clear_sources_locked();
            status_ = window_ ? "surface-attached" : "surface-detached";
            return send_ack(fd, msg.scanout_id, UINT32_MAX, msg.serial, true);
        }
        send_ack(fd, msg.scanout_id, msg.slot, msg.serial, false);
        return false;
    }

    void handle_client(int fd) {
        {
            std::lock_guard<std::mutex> guard(lock_);
            client_fd_ = fd;
        }
        while (!stop_) {
            {
                std::lock_guard<std::mutex> guard(lock_);
                if (device_ && !finish_pending_locked(fd, false)) break;
            }
            pollfd pfd{fd, POLLIN, 0};
            const int prc = poll(&pfd, 1, 2);
            if (prc < 0 && errno == EINTR) continue;
            if (prc < 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (prc == 0) continue;
            AhbMessage msg{};
            if (!recv_all(fd, &msg, sizeof(msg))) break;
            if (!handle_message(fd, msg)) break;
        }
        std::lock_guard<std::mutex> guard(lock_);
        if (device_) {
            vkDeviceWaitIdle(device_);
            for (auto& frame : frames_) { frame.pending = false; frame.serial = 0; }
        }
        if (client_fd_ == fd) client_fd_ = -1;
        if (has_source_locked()) status_ = window_ ? "presenting-ahardwarebuffer" : "frame-ready-waiting-for-surface";
    }

    void server_loop() {
        const std::string name = "vessel-ahb-" + std::to_string(getuid());
        const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (server < 0) { std::lock_guard<std::mutex> guard(lock_); status_ = "presenter-error:ahb-socket-create"; return; }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) { close(server); std::lock_guard<std::mutex> guard(lock_); status_ = "presenter-error:ahb-socket-name"; return; }
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, name.data(), name.size());
        const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (bind(server, reinterpret_cast<sockaddr*>(&addr), len) != 0 || listen(server, 2) != 0) {
            const std::string error = strerror(errno);
            close(server);
            std::lock_guard<std::mutex> guard(lock_);
            status_ = "presenter-error:ahb-bind:" + error;
            return;
        }
        {
            std::lock_guard<std::mutex> guard(lock_);
            listen_fd_ = server;
            status_ = "ahb-socket-ready";
        }
        logi("Android HardwareBuffer protocol 3 side channel ready " + name + " syncfd=1");
        while (!stop_) {
            const int client = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
            if (client < 0) {
                if (errno == EINTR) continue;
                if (stop_) break;
                continue;
            }
            handle_client(client);
            close(client);
        }
        close(server);
        std::lock_guard<std::mutex> guard(lock_);
        if (listen_fd_ == server) listen_fd_ = -1;
    }
};

AhbPresenter g_ahb;
} // namespace

extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbConfigure(JNIEnv*, jclass, jint width, jint height) { g_ahb.configure(static_cast<uint32_t>(width), static_cast<uint32_t>(height)); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStart(JNIEnv*, jclass) { g_ahb.start(); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStop(JNIEnv*, jclass) { g_ahb.stop(); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbAttachSurface(JNIEnv* env, jclass, jobject surface) { g_ahb.attach(env, surface); }
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbDetachSurface(JNIEnv*, jclass) { g_ahb.detach(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStatus(JNIEnv* env, jclass) { const std::string status = g_ahb.status(); return env->NewStringUTF(status.c_str()); }
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbGuestWidth(JNIEnv*, jclass) { return static_cast<jint>(g_ahb.width()); }
extern "C" JNIEXPORT jint JNICALL Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbGuestHeight(JNIEnv*, jclass) { return static_cast<jint>(g_ahb.height()); }
