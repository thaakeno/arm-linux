cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)

# Final desktop presentation path. KWin/Venus exports a dma-buf and Android
# imports it directly into Vulkan, blits GPU->GPU into the SurfaceFlinger
# swapchain, and keeps three command/sync contexts in flight. No screenshot,
# CPU framebuffer, VNC, AHardwareBuffer staging copy, or per-frame queue idle.
add_library(vessel_wayland_presenter SHARED vessel_wayland_presenter_v2.cpp)
target_compile_features(vessel_wayland_presenter PRIVATE cxx_std_17)
# Android Vulkan declarations in vulkan.h are gated on VK_USE_PLATFORM_ANDROID_KHR.
target_compile_definitions(vessel_wayland_presenter PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
# Vulkan structs intentionally initialize sType and then fill only the fields used.
target_compile_options(vessel_wayland_presenter PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers)
target_link_libraries(vessel_wayland_presenter PRIVATE android log vulkan)
