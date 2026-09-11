#include <jni.h>
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
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "native_cube_vert_spv.h"
#include "native_cube_frag_spv.h"

namespace {

using Clock = std::chrono::steady_clock;

void checkVk(VkResult result, const char* what) {
    if (result != VK_SUCCESS) {
        throw std::runtime_error(std::string(what) + " failed (VkResult=" + std::to_string(result) + ")");
    }
}

struct Vertex {
    float px, py, pz;
    float r, g, b;
};

constexpr std::array<Vertex, 36> kCubeVertices = {{
    // Front
    {-1,-1, 1, 0.18f,0.95f,0.72f}, { 1,-1, 1, 0.18f,0.95f,0.72f}, { 1, 1, 1, 0.18f,0.95f,0.72f},
    {-1,-1, 1, 0.18f,0.95f,0.72f}, { 1, 1, 1, 0.18f,0.95f,0.72f}, {-1, 1, 1, 0.18f,0.95f,0.72f},
    // Back
    { 1,-1,-1, 0.20f,0.52f,1.00f}, {-1,-1,-1, 0.20f,0.52f,1.00f}, {-1, 1,-1, 0.20f,0.52f,1.00f},
    { 1,-1,-1, 0.20f,0.52f,1.00f}, {-1, 1,-1, 0.20f,0.52f,1.00f}, { 1, 1,-1, 0.20f,0.52f,1.00f},
    // Right
    { 1,-1, 1, 0.92f,0.35f,0.40f}, { 1,-1,-1, 0.92f,0.35f,0.40f}, { 1, 1,-1, 0.92f,0.35f,0.40f},
    { 1,-1, 1, 0.92f,0.35f,0.40f}, { 1, 1,-1, 0.92f,0.35f,0.40f}, { 1, 1, 1, 0.92f,0.35f,0.40f},
    // Left
    {-1,-1,-1, 0.64f,0.36f,1.00f}, {-1,-1, 1, 0.64f,0.36f,1.00f}, {-1, 1, 1, 0.64f,0.36f,1.00f},
    {-1,-1,-1, 0.64f,0.36f,1.00f}, {-1, 1, 1, 0.64f,0.36f,1.00f}, {-1, 1,-1, 0.64f,0.36f,1.00f},
    // Top
    {-1, 1, 1, 1.00f,0.76f,0.22f}, { 1, 1, 1, 1.00f,0.76f,0.22f}, { 1, 1,-1, 1.00f,0.76f,0.22f},
    {-1, 1, 1, 1.00f,0.76f,0.22f}, { 1, 1,-1, 1.00f,0.76f,0.22f}, {-1, 1,-1, 1.00f,0.76f,0.22f},
    // Bottom
    {-1,-1,-1, 0.20f,0.78f,0.92f}, { 1,-1,-1, 0.20f,0.78f,0.92f}, { 1,-1, 1, 0.20f,0.78f,0.92f},
    {-1,-1,-1, 0.20f,0.78f,0.92f}, { 1,-1, 1, 0.20f,0.78f,0.92f}, {-1,-1, 1, 0.20f,0.78f,0.92f},
}};

struct PushConstants {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
};

class VulkanCubeRenderer {
public:
    explicit VulkanCubeRenderer(ANativeWindow* window) : window_(window) {
        if (!window_) throw std::runtime_error("ANativeWindow_fromSurface returned null");
    }

    ~VulkanCubeRenderer() {
        stop();
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    void start() {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) return;
        thread_ = std::thread([this] { renderLoop(); });
    }

    void stop() {
        if (!running_.exchange(false)) return;
        if (thread_.joinable()) thread_.join();
    }

    void rotate(float dxDegrees, float dyDegrees) {
        yaw_.store(yaw_.load(std::memory_order_relaxed) + dxDegrees * 0.01745329252f, std::memory_order_relaxed);
        float nextPitch = pitch_.load(std::memory_order_relaxed) + dyDegrees * 0.01745329252f;
        const float limit = 1.55334303f;
        pitch_.store(std::clamp(nextPitch, -limit, limit), std::memory_order_relaxed);
        lastInteractionNs_.store(nowNs(), std::memory_order_relaxed);
    }

    void zoom(float scaleFactor) {
        if (!(scaleFactor > 0.01f)) return;
        float next = cameraDistance_.load(std::memory_order_relaxed) / scaleFactor;
        cameraDistance_.store(std::clamp(next, 3.2f, 11.0f), std::memory_order_relaxed);
        lastInteractionNs_.store(nowNs(), std::memory_order_relaxed);
    }

    void requestResize() { resizeRequested_.store(true, std::memory_order_relaxed); }

    std::string status() const {
        std::lock_guard<std::mutex> lock(statusMutex_);
        return status_;
    }

private:
    static int64_t nowNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count();
    }

    void setStatus(const std::string& value) {
        std::lock_guard<std::mutex> lock(statusMutex_);
        status_ = value;
    }

    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) {
        VkPhysicalDeviceMemoryProperties memory{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memory);
        for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
            if ((typeBits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & properties) == properties) return i;
        }
        throw std::runtime_error("No suitable Vulkan memory type");
    }

    bool deviceHasSwapchain(VkPhysicalDevice physical) {
        uint32_t count = 0;
        vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr);
        std::vector<VkExtensionProperties> extensions(count);
        vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, extensions.data());
        for (const auto& ext : extensions) {
            if (std::strcmp(ext.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0) return true;
        }
        return false;
    }

    bool findQueueFamily(VkPhysicalDevice physical, uint32_t* outIndex) {
        uint32_t count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
        std::vector<VkQueueFamilyProperties> props(count);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, props.data());
        for (uint32_t i = 0; i < count; ++i) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(physical, i, surface_, &present);
            if ((props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                *outIndex = i;
                return true;
            }
        }
        return false;
    }

    void initVulkan() {
        const std::array<const char*, 2> instanceExtensions = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        appInfo.pApplicationName = "Vessel Native Vulkan Cube";
        appInfo.applicationVersion = 1;
        appInfo.pEngineName = "Vessel";
        appInfo.engineVersion = 1;
        appInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = static_cast<uint32_t>(instanceExtensions.size());
        instanceInfo.ppEnabledExtensionNames = instanceExtensions.data();
        checkVk(vkCreateInstance(&instanceInfo, nullptr, &instance_), "vkCreateInstance");

        VkAndroidSurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        surfaceInfo.window = window_;
        checkVk(vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_), "vkCreateAndroidSurfaceKHR");

        uint32_t deviceCount = 0;
        checkVk(vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr), "vkEnumeratePhysicalDevices(count)");
        if (deviceCount == 0) throw std::runtime_error("No Vulkan physical device found");
        std::vector<VkPhysicalDevice> devices(deviceCount);
        checkVk(vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data()), "vkEnumeratePhysicalDevices");

        for (VkPhysicalDevice candidate : devices) {
            uint32_t q = 0;
            if (deviceHasSwapchain(candidate) && findQueueFamily(candidate, &q)) {
                physicalDevice_ = candidate;
                queueFamily_ = q;
                break;
            }
        }
        if (physicalDevice_ == VK_NULL_HANDLE) throw std::runtime_error("No Vulkan graphics+present queue with VK_KHR_swapchain");

        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice_, &properties);
        gpuName_ = properties.deviceName;

        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = queueFamily_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;

        const char* deviceExtension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = &deviceExtension;
        checkVk(vkCreateDevice(physicalDevice_, &deviceInfo, nullptr, &device_), "vkCreateDevice");
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);

        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamily_;
        checkVk(vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_), "vkCreateCommandPool");

        VkCommandBufferAllocateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        commandInfo.commandPool = commandPool_;
        commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandInfo.commandBufferCount = 1;
        checkVk(vkAllocateCommandBuffers(device_, &commandInfo, &commandBuffer_), "vkAllocateCommandBuffers");

        createVertexBuffer();
        createSyncObjects();
        recreateSwapchain();
    }

    void createVertexBuffer() {
        VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bufferInfo.size = sizeof(kCubeVertices);
        bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        checkVk(vkCreateBuffer(device_, &bufferInfo, nullptr, &vertexBuffer_), "vkCreateBuffer(vertex)");

        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device_, vertexBuffer_, &requirements);
        VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocInfo.allocationSize = requirements.size;
        allocInfo.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        checkVk(vkAllocateMemory(device_, &allocInfo, nullptr, &vertexMemory_), "vkAllocateMemory(vertex)");
        checkVk(vkBindBufferMemory(device_, vertexBuffer_, vertexMemory_, 0), "vkBindBufferMemory(vertex)");

        void* mapped = nullptr;
        checkVk(vkMapMemory(device_, vertexMemory_, 0, sizeof(kCubeVertices), 0, &mapped), "vkMapMemory(vertex)");
        std::memcpy(mapped, kCubeVertices.data(), sizeof(kCubeVertices));
        vkUnmapMemory(device_, vertexMemory_);
    }

    void createSyncObjects() {
        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        checkVk(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailable_), "vkCreateSemaphore(imageAvailable)");
        checkVk(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &renderFinished_), "vkCreateSemaphore(renderFinished)");
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        checkVk(vkCreateFence(device_, &fenceInfo, nullptr, &frameFence_), "vkCreateFence");
    }

    VkSurfaceFormatKHR chooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) {
        for (const auto& format : formats) {
            if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) return format;
        }
        return formats.front();
    }

    VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(VkCompositeAlphaFlagsKHR supported) {
        const std::array<VkCompositeAlphaFlagBitsKHR, 4> choices = {
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
        };
        for (auto choice : choices) if (supported & choice) return choice;
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    void recreateSwapchain() {
        if (device_ == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(device_);
        destroySwapchain();

        VkSurfaceCapabilitiesKHR caps{};
        checkVk(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

        uint32_t formatCount = 0;
        checkVk(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr), "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
        if (formatCount == 0) throw std::runtime_error("Android Vulkan surface has no formats");
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        checkVk(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data()), "vkGetPhysicalDeviceSurfaceFormatsKHR");
        const auto format = chooseSurfaceFormat(formats);
        swapchainFormat_ = format.format;

        if (caps.currentExtent.width != UINT32_MAX) {
            swapchainExtent_ = caps.currentExtent;
        } else {
            const uint32_t width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
            const uint32_t height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
            swapchainExtent_.width = std::clamp(width, caps.minImageExtent.width, caps.maxImageExtent.width);
            swapchainExtent_.height = std::clamp(height, caps.minImageExtent.height, caps.maxImageExtent.height);
        }

        uint32_t imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0) imageCount = std::min(imageCount, caps.maxImageCount);

        VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        info.surface = surface_;
        info.minImageCount = imageCount;
        info.imageFormat = format.format;
        info.imageColorSpace = format.colorSpace;
        info.imageExtent = swapchainExtent_;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.preTransform = caps.currentTransform;
        info.compositeAlpha = chooseCompositeAlpha(caps.supportedCompositeAlpha);
        info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        info.clipped = VK_TRUE;
        checkVk(vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain_), "vkCreateSwapchainKHR");

        uint32_t actualCount = 0;
        checkVk(vkGetSwapchainImagesKHR(device_, swapchain_, &actualCount, nullptr), "vkGetSwapchainImagesKHR(count)");
        swapchainImages_.resize(actualCount);
        checkVk(vkGetSwapchainImagesKHR(device_, swapchain_, &actualCount, swapchainImages_.data()), "vkGetSwapchainImagesKHR");

        imageViews_.resize(actualCount);
        for (uint32_t i = 0; i < actualCount; ++i) {
            VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = swapchainImages_[i];
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = swapchainFormat_;
            viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.layerCount = 1;
            checkVk(vkCreateImageView(device_, &viewInfo, nullptr, &imageViews_[i]), "vkCreateImageView");
        }

        createRenderPassAndPipeline();

        framebuffers_.resize(actualCount);
        for (uint32_t i = 0; i < actualCount; ++i) {
            VkImageView attachment = imageViews_[i];
            VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fbInfo.renderPass = renderPass_;
            fbInfo.attachmentCount = 1;
            fbInfo.pAttachments = &attachment;
            fbInfo.width = swapchainExtent_.width;
            fbInfo.height = swapchainExtent_.height;
            fbInfo.layers = 1;
            checkVk(vkCreateFramebuffer(device_, &fbInfo, nullptr, &framebuffers_[i]), "vkCreateFramebuffer");
        }
        resizeRequested_.store(false, std::memory_order_relaxed);
    }

    VkShaderModule createShaderModule(const uint32_t* code, size_t bytes) {
        VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        info.codeSize = bytes;
        info.pCode = code;
        VkShaderModule module = VK_NULL_HANDLE;
        checkVk(vkCreateShaderModule(device_, &info, nullptr, &module), "vkCreateShaderModule");
        return module;
    }

    void createRenderPassAndPipeline() {
        VkAttachmentDescription color{};
        color.format = swapchainFormat_;
        color.samples = VK_SAMPLE_COUNT_1_BIT;
        color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorRef{};
        colorRef.attachment = 0;
        colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorRef;

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rpInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rpInfo.attachmentCount = 1;
        rpInfo.pAttachments = &color;
        rpInfo.subpassCount = 1;
        rpInfo.pSubpasses = &subpass;
        rpInfo.dependencyCount = 1;
        rpInfo.pDependencies = &dependency;
        checkVk(vkCreateRenderPass(device_, &rpInfo, nullptr, &renderPass_), "vkCreateRenderPass");

        const VkShaderModule vert = createShaderModule(native_cube_vert_spv, native_cube_vert_spv_size);
        const VkShaderModule frag = createShaderModule(native_cube_frag_spv, native_cube_frag_spv_size);

        VkPipelineShaderStageCreateInfo vertStage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        vertStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertStage.module = vert;
        vertStage.pName = "main";
        VkPipelineShaderStageCreateInfo fragStage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        fragStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragStage.module = frag;
        fragStage.pName = "main";
        const std::array<VkPipelineShaderStageCreateInfo, 2> stages = {vertStage, fragStage};

        VkVertexInputBindingDescription binding{};
        binding.binding = 0;
        binding.stride = sizeof(Vertex);
        binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
        const std::array<VkVertexInputAttributeDescription, 2> attrs = {{
            {0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(Vertex, px)},
            {1, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(Vertex, r)},
        }};
        VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        vertexInput.vertexBindingDescriptionCount = 1;
        vertexInput.pVertexBindingDescriptions = &binding;
        vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(attrs.size());
        vertexInput.pVertexAttributeDescriptions = attrs.data();

        VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;
        const std::array<VkDynamicState, 2> dynamicStates = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dynamic.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamic.pDynamicStates = dynamicStates.data();

        VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = VK_CULL_MODE_NONE;
        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        raster.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo msaa{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        msaa.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        blend.attachmentCount = 1;
        blend.pAttachments = &blendAttachment;

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
        pushRange.size = sizeof(PushConstants);
        VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        checkVk(vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_), "vkCreatePipelineLayout");

        VkGraphicsPipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pipelineInfo.stageCount = static_cast<uint32_t>(stages.size());
        pipelineInfo.pStages = stages.data();
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &assembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &raster;
        pipelineInfo.pMultisampleState = &msaa;
        pipelineInfo.pColorBlendState = &blend;
        pipelineInfo.pDynamicState = &dynamic;
        pipelineInfo.layout = pipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;
        const VkResult pipelineResult = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline_);
        vkDestroyShaderModule(device_, vert, nullptr);
        vkDestroyShaderModule(device_, frag, nullptr);
        checkVk(pipelineResult, "vkCreateGraphicsPipelines");
    }

    void recordCommandBuffer(uint32_t imageIndex) {
        VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        checkVk(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer");

        VkClearValue clear{};
        clear.color = {{0.012f, 0.018f, 0.016f, 1.0f}};
        VkRenderPassBeginInfo renderInfo{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        renderInfo.renderPass = renderPass_;
        renderInfo.framebuffer = framebuffers_[imageIndex];
        renderInfo.renderArea.extent = swapchainExtent_;
        renderInfo.clearValueCount = 1;
        renderInfo.pClearValues = &clear;
        vkCmdBeginRenderPass(commandBuffer_, &renderInfo, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);

        VkViewport viewport{};
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.maxDepth = 1.0f;
        VkRect2D scissor{};
        scissor.extent = swapchainExtent_;
        vkCmdSetViewport(commandBuffer_, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer_, 0, 1, &scissor);

        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(commandBuffer_, 0, 1, &vertexBuffer_, &offset);
        PushConstants push{};
        push.yaw = yaw_.load(std::memory_order_relaxed);
        push.pitch = pitch_.load(std::memory_order_relaxed);
        push.aspect = static_cast<float>(swapchainExtent_.width) / static_cast<float>(std::max(1u, swapchainExtent_.height));
        push.cameraDistance = cameraDistance_.load(std::memory_order_relaxed);
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(push), &push);
        vkCmdDraw(commandBuffer_, static_cast<uint32_t>(kCubeVertices.size()), 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer_);
        checkVk(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer");
    }

    void drawFrame() {
        checkVk(vkWaitForFences(device_, 1, &frameFence_, VK_TRUE, UINT64_MAX), "vkWaitForFences");

        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, imageAvailable_, VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
            recreateSwapchain();
            return;
        }
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) checkVk(acquire, "vkAcquireNextImageKHR");

        checkVk(vkResetFences(device_, 1, &frameFence_), "vkResetFences");
        checkVk(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer");
        recordCommandBuffer(imageIndex);

        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &imageAvailable_;
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commandBuffer_;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &renderFinished_;
        checkVk(vkQueueSubmit(queue_, 1, &submit, frameFence_), "vkQueueSubmit");

        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &renderFinished_;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &imageIndex;
        const VkResult result = vkQueuePresentKHR(queue_, &present);
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR || resizeRequested_.load(std::memory_order_relaxed)) {
            recreateSwapchain();
        } else if (result != VK_SUCCESS) {
            checkVk(result, "vkQueuePresentKHR");
        }
    }

    void renderLoop() {
        try {
            setStatus("Vulkan · initializing Android surface…");
            initVulkan();
            setStatus("Vulkan · " + gpuName_ + " · starting frames…");

            auto lastFrame = Clock::now();
            auto statStart = lastFrame;
            uint32_t frames = 0;
            while (running_.load(std::memory_order_relaxed)) {
                const auto now = Clock::now();
                const float dt = std::chrono::duration<float>(now - lastFrame).count();
                lastFrame = now;
                const int64_t sinceInteraction = nowNs() - lastInteractionNs_.load(std::memory_order_relaxed);
                if (sinceInteraction > 220000000LL) {
                    yaw_.store(yaw_.load(std::memory_order_relaxed) + std::min(dt, 0.05f) * 0.55f, std::memory_order_relaxed);
                }

                drawFrame();
                ++frames;
                const float elapsed = std::chrono::duration<float>(now - statStart).count();
                if (elapsed >= 0.5f) {
                    const float fps = static_cast<float>(frames) / elapsed;
                    setStatus("Vulkan · " + gpuName_ + " · " + std::to_string(fps).substr(0, 5) + " FPS · direct Android Surface");
                    statStart = now;
                    frames = 0;
                }
            }
        } catch (const std::exception& e) {
            setStatus(std::string("ERROR: Vulkan renderer: ") + e.what());
        } catch (...) {
            setStatus("ERROR: Vulkan renderer: unknown native exception");
        }
        cleanupVulkan();
    }

    void destroySwapchain() {
        if (device_ == VK_NULL_HANDLE) return;
        for (auto framebuffer : framebuffers_) if (framebuffer) vkDestroyFramebuffer(device_, framebuffer, nullptr);
        framebuffers_.clear();
        if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
        pipeline_ = VK_NULL_HANDLE;
        if (pipelineLayout_) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        pipelineLayout_ = VK_NULL_HANDLE;
        if (renderPass_) vkDestroyRenderPass(device_, renderPass_, nullptr);
        renderPass_ = VK_NULL_HANDLE;
        for (auto view : imageViews_) if (view) vkDestroyImageView(device_, view, nullptr);
        imageViews_.clear();
        swapchainImages_.clear();
        if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }

    void cleanupVulkan() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySwapchain();
        if (device_ != VK_NULL_HANDLE && frameFence_) vkDestroyFence(device_, frameFence_, nullptr);
        frameFence_ = VK_NULL_HANDLE;
        if (device_ != VK_NULL_HANDLE && renderFinished_) vkDestroySemaphore(device_, renderFinished_, nullptr);
        renderFinished_ = VK_NULL_HANDLE;
        if (device_ != VK_NULL_HANDLE && imageAvailable_) vkDestroySemaphore(device_, imageAvailable_, nullptr);
        imageAvailable_ = VK_NULL_HANDLE;
        if (device_ != VK_NULL_HANDLE && vertexBuffer_) vkDestroyBuffer(device_, vertexBuffer_, nullptr);
        vertexBuffer_ = VK_NULL_HANDLE;
        if (device_ != VK_NULL_HANDLE && vertexMemory_) vkFreeMemory(device_, vertexMemory_, nullptr);
        vertexMemory_ = VK_NULL_HANDLE;
        if (device_ != VK_NULL_HANDLE && commandPool_) vkDestroyCommandPool(device_, commandPool_, nullptr);
        commandPool_ = VK_NULL_HANDLE;
        commandBuffer_ = VK_NULL_HANDLE;
        if (device_) vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        if (instance_ != VK_NULL_HANDLE && surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
        if (instance_) vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }

    ANativeWindow* window_ = nullptr;
    std::atomic<bool> running_{false};
    std::thread thread_;
    mutable std::mutex statusMutex_;
    std::string status_ = "Vulkan · waiting for Surface…";

    std::atomic<float> yaw_{-0.49f};
    std::atomic<float> pitch_{0.38f};
    std::atomic<float> cameraDistance_{6.2f};
    std::atomic<int64_t> lastInteractionNs_{0};
    std::atomic<bool> resizeRequested_{false};

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    std::string gpuName_ = "Android Vulkan GPU";

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> imageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;

    VkBuffer vertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory vertexMemory_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    VkSemaphore renderFinished_ = VK_NULL_HANDLE;
    VkFence frameFence_ = VK_NULL_HANDLE;
};

VulkanCubeRenderer* fromHandle(jlong handle) {
    return reinterpret_cast<VulkanCubeRenderer*>(static_cast<intptr_t>(handle));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    try {
        auto* renderer = new VulkanCubeRenderer(window);
        renderer->start();
        return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer));
    } catch (...) {
        ANativeWindow_release(window);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_NativeCubeActivity_nativeResize(JNIEnv*, jobject, jlong handle) {
    if (auto* renderer = fromHandle(handle)) renderer->requestResize();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate(JNIEnv*, jobject, jlong handle, jfloat dx, jfloat dy) {
    if (auto* renderer = fromHandle(handle)) renderer->rotate(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom(JNIEnv*, jobject, jlong handle, jfloat scale) {
    if (auto* renderer = fromHandle(handle)) renderer->zoom(scale);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus(JNIEnv* env, jobject, jlong handle) {
    const std::string text = fromHandle(handle) ? fromHandle(handle)->status() : "Vulkan · renderer not started";
    return env->NewStringUTF(text.c_str());
}
