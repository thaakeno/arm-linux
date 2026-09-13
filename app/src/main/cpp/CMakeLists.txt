cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)

# Final desktop presentation path. KWin/Venus exports a dma-buf; this library
# imports it into Vulkan, copies GPU->GPU through a triple AHardwareBuffer ring,
# then presents to the app Surface/SurfaceFlinger. No CPU framebuffer path.
add_library(vessel_wayland_presenter SHARED vessel_wayland_presenter.cpp)
target_compile_features(vessel_wayland_presenter PRIVATE cxx_std_17)
target_compile_options(vessel_wayland_presenter PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vessel_wayland_presenter PRIVATE android log vulkan)
