cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

# Real PTY backend for terminal-emulator. This is maintained source, not a
# generated/monkey-patched Termux file. It exports the exact JNI ABI expected
# by terminal-emulator 0.118.0 plus Vessel's identity-claim entry point.
add_library(termux SHARED third_party/termux/vessel_termux_pty.c)
target_compile_features(termux PRIVATE c_std_11)
target_compile_options(termux PRIVATE -Wall -Wextra -Werror -Os)
target_link_options(termux PRIVATE "-Wl,-z,max-page-size=16384")

add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)

# The presenter owns two same-UID host channels. Standard vhost-user-gpu keeps
# display metadata/cursor control, while the scanout path imports AHardwareBuffer
# into EGL/GLES and presents it straight to Android's native Surface BufferQueue.
# vessel_native_presenter.cpp still uses Android Vulkan symbols for the legacy
# control/cursor frontend, so keep the platform define/library without using a
# Vulkan swapchain in the new scanout presenter.
add_library(vessel_wayland_presenter SHARED
    vessel_native_presenter.cpp
    vessel_surface_presenter.cpp
    vessel_virtio_input.cpp
)
target_compile_features(vessel_wayland_presenter PRIVATE cxx_std_17)
target_compile_options(vessel_wayland_presenter PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers -Wno-misleading-indentation -Wno-unused-function)
target_compile_definitions(vessel_wayland_presenter PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_link_libraries(vessel_wayland_presenter PRIVATE android log EGL GLESv3 vulkan)

# Loaded into vhost-device-gpu before virglrenderer. Termux's Android libepoxy
# exposes epoxy_set_library_path(); this selects the ANGLE DSOs packaged next
# to the runtime without fake SONAME aliases or a Termux filesystem prefix.
add_library(vessel_angle_select SHARED vessel_angle_select.c)
target_compile_options(vessel_angle_select PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vessel_angle_select PRIVATE dl)
