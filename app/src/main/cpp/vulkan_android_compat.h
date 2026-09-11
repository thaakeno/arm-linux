#pragma once

#include <vulkan/vulkan.h>

// Android's NDK Vulkan stub for lower API levels does not export every Vulkan 1.2
// core entry point at link time, even when the device runtime supports it. Resolve
// buffer device address through the loader instead, with the KHR alias as fallback.
static inline VkDeviceAddress vesselGetBufferDeviceAddressCompat(
    VkDevice device,
    const VkBufferDeviceAddressInfo* info) {
    auto fp = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
        vkGetDeviceProcAddr(device, "vkGetBufferDeviceAddress"));
    if (fp) {
        return fp(device, info);
    }

    auto fpKhr = reinterpret_cast<PFN_vkGetBufferDeviceAddressKHR>(
        vkGetDeviceProcAddr(device, "vkGetBufferDeviceAddressKHR"));
    if (fpKhr) {
        return fpKhr(device,
                     reinterpret_cast<const VkBufferDeviceAddressInfoKHR*>(info));
    }

    return 0;
}

#define vkGetBufferDeviceAddress vesselGetBufferDeviceAddressCompat
