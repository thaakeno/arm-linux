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

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VesselAhbPresenter";
constexpr uint32_t MAGIC = 0x42484156u; // "VAHB" little-endian
constexpr uint32_t VERSION = 1;
constexpr uint32_t MSG_SCANOUT = 1;
constexpr uint32_t MSG_UPDATE = 2;
constexpr uint32_t MSG_DISABLE = 3;
constexpr uint32_t FRAMES_IN_FLIGHT = 3;

#pragma pack(push, 1)
struct AhbMessage {
    uint32_t magic;
    uint32_t version;
    uint32_t type;
    uint32_t scanout_id;
    uint32_t width;
    uint32_t height;
};
#pragma pack(pop)

struct Frame {
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkSemaphore acquire = VK_NULL_HANDLE;
    VkSemaphore render = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
};

void logi(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
}

void loge(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

std::string vk_error(const char* what, VkResult result) {
    return std::string(what) + " VkResult=" + std::to_string(static_cast<int>(result));
}

bool recv_all(int fd, void* data, size_t size) {
    auto* p = static_cast<uint8_t*>(data);
    while (size > 0) {
        const ssize_t n = recv(fd, p, size, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += static_cast<size_t>(n);
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool send_ack(int fd, bool ok) {
    const uint8_t value = ok ? 1 : 0;
    while (true) {
        const ssize_t n = send(fd, &value, sizeof(value), MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        return n == static_cast<ssize_t>(sizeof(value));
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
        int listen_fd = -1;
        int client_fd = -1;
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
        destroy_vulkan();
        release_window();
        clear_buffer();
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
        destroy_vulkan();
        release_window();
        window_ = window;
        status_ = "surface-attached";
        if (buffer_) present_locked();
    }

    void detach() {
        std::lock_guard<std::mutex> guard(lock_);
        destroy_vulkan();
        release_window();
        status_ = buffer_ ? "frame-ready-waiting-for-surface" : "surface-detached";
    }

    std::string status() {
        std::lock_guard<std::mutex> guard(lock_);
        return status_;
    }

    uint32_t width() {
        std::lock_guard<std::mutex> guard(lock_);
        return width_ ? width_ : preferred_width_;
    }

    uint32_t height() {
        std::lock_guard<std::mutex> guard(lock_);
        return height_ ? height_ : preferred_height_;
    }

private:
    std::mutex lock_;
    std::atomic<bool> stop_{false};
    std::thread server_;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    std::string status_ = "not-started";
    uint32_t preferred_width_ = 1280;
    uint32_t preferred_height_ = 720;
    uint32_t width_ = 0;
    uint32_t height_ = 0;
    AHardwareBuffer* buffer_ = nullptr;
    ANativeWindow* window_ = nullptr;

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

    VkImage source_image_ = VK_NULL_HANDLE;
    VkDeviceMemory source_memory_ = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_properties_ = nullptr;

    static bool has_extension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
        return std::any_of(extensions.begin(), extensions.end(), [&](const auto& ext) {
            return strcmp(ext.extensionName, name) == 0;
        });
    }

    void release_window() {
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    void destroy_imported_image() {
        if (device_) {
            if (source_image_) vkDestroyImage(device_, source_image_, nullptr);
            if (source_memory_) vkFreeMemory(device_, source_memory_, nullptr);
        }
        source_image_ = VK_NULL_HANDLE;
        source_memory_ = VK_NULL_HANDLE;
    }

    void clear_buffer() {
        destroy_imported_image();
        if (buffer_) AHardwareBuffer_release(buffer_);
        buffer_ = nullptr;
        width_ = 0;
        height_ = 0;
    }

    uint32_t memory_type(uint32_t bits) const {
        VkPhysicalDeviceMemoryProperties properties{};
        vkGetPhysicalDeviceMemoryProperties(physical_, &properties);
        for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) != 0) return i;
        }
        return UINT32_MAX;
    }

    void image_barrier(
        VkCommandBuffer cmd,
        VkImage image,
        VkImageLayout old_layout,
        VkImageLayout new_layout,
        VkAccessFlags src_access,
        VkAccessFlags dst_access,
        uint32_t src_family,
        uint32_t dst_family) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = src_access;
        barrier.dstAccessMask = dst_access;
        barrier.oldLayout = old_layout;
        barrier.newLayout = new_layout;
        barrier.srcQueueFamilyIndex = src_family;
        barrier.dstQueueFamilyIndex = dst_family;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &barrier);
    }

    bool create_vulkan() {
        if (device_) return true;
        if (!window_) {
            status_ = "frame-ready-waiting-for-surface";
            return false;
        }

        const char* instance_extensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Vessel AHardwareBuffer Presenter";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instance_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instance_info.pApplicationInfo = &app;
        instance_info.enabledExtensionCount = 2;
        instance_info.ppEnabledExtensionNames = instance_extensions;
        VkResult result = vkCreateInstance(&instance_info, nullptr, &instance_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkCreateInstance", result);
            return false;
        }

        VkAndroidSurfaceCreateInfoKHR surface_info{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        surface_info.window = window_;
        result = vkCreateAndroidSurfaceKHR(instance_, &surface_info, nullptr, &surface_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkCreateAndroidSurfaceKHR", result);
            return false;
        }

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
                if ((queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                    physical_ = candidate;
                    queue_family_ = i;
                    break;
                }
            }
            if (physical_) break;
        }
        if (!physical_) {
            status_ = "presenter-error:no-present-queue";
            return false;
        }

        uint32_t extension_count = 0;
        vkEnumerateDeviceExtensionProperties(physical_, nullptr, &extension_count, nullptr);
        std::vector<VkExtensionProperties> extensions(extension_count);
        vkEnumerateDeviceExtensionProperties(physical_, nullptr, &extension_count, extensions.data());
        const char* required[] = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        };
        for (const char* extension : required) {
            if (!has_extension(extensions, extension)) {
                status_ = std::string("presenter-error:missing-") + extension;
                return false;
            }
        }

        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queue_info{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queue_info.queueFamilyIndex = queue_family_;
        queue_info.queueCount = 1;
        queue_info.pQueuePriorities = &priority;
        VkDeviceCreateInfo device_info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        device_info.queueCreateInfoCount = 1;
        device_info.pQueueCreateInfos = &queue_info;
        device_info.enabledExtensionCount = 3;
        device_info.ppEnabledExtensionNames = required;
        result = vkCreateDevice(physical_, &device_info, nullptr, &device_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkCreateDevice", result);
            return false;
        }
        vkGetDeviceQueue(device_, queue_family_, 0, &queue_);
        get_ahb_properties_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (!get_ahb_properties_) {
            status_ = "presenter-error:no-vkGetAndroidHardwareBufferPropertiesANDROID";
            return false;
        }

        VkSurfaceCapabilitiesKHR capabilities{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &capabilities);
        uint32_t format_count = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &format_count, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(format_count);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &format_count, formats.data());
        if (formats.empty()) {
            status_ = "presenter-error:no-surface-format";
            return false;
        }
        VkSurfaceFormatKHR chosen = formats.front();
        for (const auto& candidate : formats) {
            if ((candidate.format == VK_FORMAT_R8G8B8A8_UNORM || candidate.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                candidate.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                chosen = candidate;
                break;
            }
        }

        extent_ = capabilities.currentExtent;
        if (extent_.width == UINT32_MAX) {
            extent_.width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
            extent_.height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
            extent_.width = std::clamp(extent_.width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
            extent_.height = std::clamp(extent_.height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
        }
        uint32_t image_count = std::max(FRAMES_IN_FLIGHT, capabilities.minImageCount);
        if (capabilities.maxImageCount != 0) image_count = std::min(image_count, capabilities.maxImageCount);

        VkSwapchainCreateInfoKHR swapchain_info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        swapchain_info.surface = surface_;
        swapchain_info.minImageCount = image_count;
        swapchain_info.imageFormat = chosen.format;
        swapchain_info.imageColorSpace = chosen.colorSpace;
        swapchain_info.imageExtent = extent_;
        swapchain_info.imageArrayLayers = 1;
        swapchain_info.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        swapchain_info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchain_info.preTransform = capabilities.currentTransform;
        swapchain_info.compositeAlpha =
            (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        swapchain_info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchain_info.clipped = VK_TRUE;
        result = vkCreateSwapchainKHR(device_, &swapchain_info, nullptr, &swapchain_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkCreateSwapchainKHR", result);
            return false;
        }
        uint32_t swap_count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &swap_count, nullptr);
        swapchain_images_.resize(swap_count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &swap_count, swapchain_images_.data());

        VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pool_info.queueFamilyIndex = queue_family_;
        result = vkCreateCommandPool(device_, &pool_info, nullptr, &command_pool_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkCreateCommandPool", result);
            return false;
        }
        std::array<VkCommandBuffer, FRAMES_IN_FLIGHT> commands{};
        VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        alloc.commandPool = command_pool_;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = FRAMES_IN_FLIGHT;
        result = vkAllocateCommandBuffers(device_, &alloc, commands.data());
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkAllocateCommandBuffers", result);
            return false;
        }
        VkSemaphoreCreateInfo semaphore_info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkFenceCreateInfo fence_info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (uint32_t i = 0; i < FRAMES_IN_FLIGHT; ++i) {
            frames_[i].cmd = commands[i];
            vkCreateSemaphore(device_, &semaphore_info, nullptr, &frames_[i].acquire);
            vkCreateSemaphore(device_, &semaphore_info, nullptr, &frames_[i].render);
            vkCreateFence(device_, &fence_info, nullptr, &frames_[i].fence);
        }
        status_ = "ahb-vulkan-ready";
        return true;
    }

    bool import_buffer() {
        if (source_image_) return true;
        if (!buffer_ || !create_vulkan()) return false;

        VkAndroidHardwareBufferFormatPropertiesANDROID format_properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        properties.pNext = &format_properties;
        VkResult result = get_ahb_properties_(device_, buffer_, &properties);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkGetAndroidHardwareBufferPropertiesANDROID", result);
            return false;
        }
        if (format_properties.format == VK_FORMAT_UNDEFINED) {
            status_ = "presenter-error:ahb-external-format-not-transferable";
            return false;
        }

        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer_, &desc);
        width_ = desc.width;
        height_ = desc.height;

        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo image_info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        image_info.pNext = &external;
        image_info.imageType = VK_IMAGE_TYPE_2D;
        image_info.format = format_properties.format;
        image_info.extent = {width_, height_, 1};
        image_info.mipLevels = 1;
        image_info.arrayLayers = 1;
        image_info.samples = VK_SAMPLE_COUNT_1_BIT;
        image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
        image_info.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        result = vkCreateImage(device_, &image_info, nullptr, &source_image_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkCreateImage(ahb)", result);
            return false;
        }

        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device_, source_image_, &requirements);
        const uint32_t type = memory_type(requirements.memoryTypeBits & properties.memoryTypeBits);
        if (type == UINT32_MAX) {
            status_ = "presenter-error:no-ahb-memory-type";
            return false;
        }

        VkImportAndroidHardwareBufferInfoANDROID import_info{
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        import_info.buffer = buffer_;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &import_info;
        dedicated.image = source_image_;
        VkMemoryAllocateInfo memory_info{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        memory_info.pNext = &dedicated;
        memory_info.allocationSize = properties.allocationSize;
        memory_info.memoryTypeIndex = type;
        result = vkAllocateMemory(device_, &memory_info, nullptr, &source_memory_);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkAllocateMemory(ahb)", result);
            return false;
        }
        result = vkBindImageMemory(device_, source_image_, source_memory_, 0);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkBindImageMemory(ahb)", result);
            return false;
        }
        return true;
    }

    bool present_locked() {
        if (!window_) {
            status_ = "frame-ready-waiting-for-surface";
            return true;
        }
        if (!import_buffer()) return false;

        Frame& frame = frames_[frame_number_++ % FRAMES_IN_FLIGHT];
        vkWaitForFences(device_, 1, &frame.fence, VK_TRUE, UINT64_MAX);
        vkResetFences(device_, 1, &frame.fence);

        uint32_t image_index = 0;
        VkResult result = vkAcquireNextImageKHR(
            device_, swapchain_, UINT64_MAX, frame.acquire, VK_NULL_HANDLE, &image_index);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            status_ = vk_error("vkAcquireNextImageKHR", result);
            return false;
        }

        vkResetCommandBuffer(frame.cmd, 0);
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(frame.cmd, &begin);

        image_barrier(
            frame.cmd, source_image_, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            0, VK_ACCESS_TRANSFER_READ_BIT, VK_QUEUE_FAMILY_FOREIGN_EXT, queue_family_);
        image_barrier(
            frame.cmd, swapchain_images_[image_index], VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            queue_family_, queue_family_);

        VkClearColorValue black{{0.0f, 0.0f, 0.0f, 1.0f}};
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        vkCmdClearColorImage(
            frame.cmd, swapchain_images_[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            &black, 1, &range);

        const double sx = static_cast<double>(extent_.width) / static_cast<double>(width_);
        const double sy = static_cast<double>(extent_.height) / static_cast<double>(height_);
        const double scale = std::min(sx, sy);
        const int32_t dst_width = static_cast<int32_t>(std::max(1.0, width_ * scale));
        const int32_t dst_height = static_cast<int32_t>(std::max(1.0, height_ * scale));
        const int32_t dst_x = (static_cast<int32_t>(extent_.width) - dst_width) / 2;
        const int32_t dst_y = (static_cast<int32_t>(extent_.height) - dst_height) / 2;
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = {static_cast<int32_t>(width_), static_cast<int32_t>(height_), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[0] = {dst_x, dst_y, 0};
        blit.dstOffsets[1] = {dst_x + dst_width, dst_y + dst_height, 1};
        vkCmdBlitImage(
            frame.cmd,
            source_image_,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            swapchain_images_[image_index],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &blit,
            VK_FILTER_LINEAR);

        image_barrier(
            frame.cmd, source_image_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_ACCESS_TRANSFER_READ_BIT, 0, queue_family_, VK_QUEUE_FAMILY_FOREIGN_EXT);
        image_barrier(
            frame.cmd, swapchain_images_[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
            queue_family_, queue_family_);
        vkEndCommandBuffer(frame.cmd);

        const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &frame.acquire;
        submit.pWaitDstStageMask = &wait_stage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &frame.cmd;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &frame.render;
        result = vkQueueSubmit(queue_, 1, &submit, frame.fence);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkQueueSubmit", result);
            return false;
        }

        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &frame.render;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &image_index;
        result = vkQueuePresentKHR(queue_, &present);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            status_ = vk_error("vkQueuePresentKHR", result);
            return false;
        }

        // The producer is ANGLE in another process. The side-channel ACK is
        // intentionally not sent until this fence signals, so the producer
        // cannot overwrite the shared AHardwareBuffer while Vulkan is reading it.
        result = vkWaitForFences(device_, 1, &frame.fence, VK_TRUE, UINT64_MAX);
        if (result != VK_SUCCESS) {
            status_ = vk_error("vkWaitForFences", result);
            return false;
        }
        status_ = "presenting-ahardwarebuffer";
        return true;
    }

    void destroy_vulkan() {
        if (device_) vkDeviceWaitIdle(device_);
        destroy_imported_image();
        if (device_) {
            for (auto& frame : frames_) {
                if (frame.acquire) vkDestroySemaphore(device_, frame.acquire, nullptr);
                if (frame.render) vkDestroySemaphore(device_, frame.render, nullptr);
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
        frame_number_ = 0;
    }

    void handle_client(int fd) {
        {
            std::lock_guard<std::mutex> guard(lock_);
            client_fd_ = fd;
        }
        while (!stop_) {
            AhbMessage message{};
            if (!recv_all(fd, &message, sizeof(message))) break;
            if (message.magic != MAGIC || message.version != VERSION || message.scanout_id != 0) {
                send_ack(fd, false);
                break;
            }

            if (message.type == MSG_SCANOUT) {
                AHardwareBuffer* incoming = nullptr;
                if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &incoming) != 0 || !incoming) {
                    send_ack(fd, false);
                    break;
                }
                bool ok = false;
                {
                    std::lock_guard<std::mutex> guard(lock_);
                    destroy_imported_image();
                    if (buffer_) AHardwareBuffer_release(buffer_);
                    buffer_ = incoming;
                    AHardwareBuffer_Desc desc{};
                    AHardwareBuffer_describe(buffer_, &desc);
                    width_ = desc.width ? desc.width : message.width;
                    height_ = desc.height ? desc.height : message.height;
                    status_ = window_ ? "ahb-scanout-ready" : "frame-ready-waiting-for-surface";
                    ok = !window_ || present_locked();
                }
                if (!send_ack(fd, ok)) break;
            } else if (message.type == MSG_UPDATE) {
                bool ok = false;
                {
                    std::lock_guard<std::mutex> guard(lock_);
                    ok = buffer_ && (!window_ || present_locked());
                    if (buffer_ && !window_) status_ = "frame-ready-waiting-for-surface";
                }
                if (!send_ack(fd, ok)) break;
            } else if (message.type == MSG_DISABLE) {
                {
                    std::lock_guard<std::mutex> guard(lock_);
                    clear_buffer();
                    status_ = window_ ? "surface-attached" : "surface-detached";
                }
                if (!send_ack(fd, true)) break;
            } else {
                send_ack(fd, false);
                break;
            }
        }
        std::lock_guard<std::mutex> guard(lock_);
        if (client_fd_ == fd) client_fd_ = -1;
    }

    void server_loop() {
        const std::string name = "vessel-ahb-" + std::to_string(getuid());
        const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (server < 0) {
            std::lock_guard<std::mutex> guard(lock_);
            status_ = "presenter-error:ahb-socket-create";
            return;
        }
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        if (name.size() + 1 >= sizeof(addr.sun_path)) {
            close(server);
            std::lock_guard<std::mutex> guard(lock_);
            status_ = "presenter-error:ahb-socket-name";
            return;
        }
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
        logi("Android HardwareBuffer side channel ready " + name);

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

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbConfigure(
    JNIEnv*, jclass, jint width, jint height) {
    g_ahb.configure(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStart(JNIEnv*, jclass) {
    g_ahb.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStop(JNIEnv*, jclass) {
    g_ahb.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbAttachSurface(
    JNIEnv* env, jclass, jobject surface) {
    g_ahb.attach(env, surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbDetachSurface(JNIEnv*, jclass) {
    g_ahb.detach();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbStatus(JNIEnv* env, jclass) {
    const std::string status = g_ahb.status();
    return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbGuestWidth(JNIEnv*, jclass) {
    return static_cast<jint>(g_ahb.width());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_dreamlinux_VesselWaylandPresenter_nativeAhbGuestHeight(JNIEnv*, jclass) {
    return static_cast<jint>(g_ahb.height());
}
