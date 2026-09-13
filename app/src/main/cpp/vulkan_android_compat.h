#pragma once

// Preload the headers used by the Vulkan renderer before Studio v4 temporarily
// exposes the v3 backend's private members in the same translation unit. This keeps
// the access shim from touching private/protected tokens inside libc++ headers.
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
