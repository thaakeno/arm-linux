cmake_minimum_required(VERSION 3.22.1)
project(vessel_linux_native)

# Vessel is a Linux runtime. Native code is limited to runtime transport and
# the Android Surface desktop presenter; no game/demo renderers live here.
add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(vessel_desktop SHARED native_desktop_surface.cpp)
target_compile_features(vessel_desktop PRIVATE cxx_std_17)
target_compile_options(vessel_desktop PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vessel_desktop PRIVATE android log)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)
