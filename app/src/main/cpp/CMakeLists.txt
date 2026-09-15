cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)

# Vessel protocol 38 Android presentation path. Linux 3D is rendered through
# virtio-gpu/VirGL on host ANGLE/Vulkan/Adreno. Standard vhost-user-gpu RGB
# scanout updates cross the Termux/Vessel sandbox boundary over loopback TCP,
# then this native Vulkan presenter uploads/blits them into the SurfaceFlinger
# swapchain. The existing dma-buf import path remains available for a future
# same-UID/Binder FD transport.
add_library(vessel_wayland_presenter SHARED vessel_wayland_presenter_v3.cpp)
target_compile_features(vessel_wayland_presenter PRIVATE cxx_std_17)
# Android Vulkan declarations in vulkan.h are gated on VK_USE_PLATFORM_ANDROID_KHR.
target_compile_definitions(vessel_wayland_presenter PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
# Vulkan structs intentionally initialize sType and then fill only the fields used.
target_compile_options(vessel_wayland_presenter PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers)
target_link_libraries(vessel_wayland_presenter PRIVATE android log vulkan)
