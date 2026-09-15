cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)

# The presenter itself is the vhost-user-gpu display frontend. It receives
# virglrenderer DMA-BUF FDs via SCM_RIGHTS inside Vessel's own UID and imports
# them directly into Vulkan. The same library also owns the local Android ->
# vhost-user virtio-input sender. There is intentionally no RGB/SHM or guest
# networking input fallback.
add_library(vessel_wayland_presenter SHARED
    vessel_native_presenter.cpp
    vessel_virtio_input.cpp
)
target_compile_features(vessel_wayland_presenter PRIVATE cxx_std_17)
target_compile_definitions(vessel_wayland_presenter PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(vessel_wayland_presenter PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers -Wno-misleading-indentation)
target_link_libraries(vessel_wayland_presenter PRIVATE android log vulkan)

# Loaded into vhost-device-gpu before virglrenderer. Termux's Android libepoxy
# exposes epoxy_set_library_path(); this selects the ANGLE DSOs packaged next
# to the runtime without fake SONAME aliases or a Termux filesystem prefix.
add_library(vessel_angle_select SHARED vessel_angle_select.c)
target_compile_options(vessel_angle_select PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vessel_angle_select PRIVATE dl)
