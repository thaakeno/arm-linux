cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

# Vessel is a Linux runtime. The old Vulkan Studio / Bounce Quest renderers are
# intentionally gone from the APK. Native code here is limited to runtime
# transport and the future zero-copy Linux display bridge.
add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)
