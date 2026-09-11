cmake_minimum_required(VERSION 3.22.1)
project(dev1_linux_native)

add_library(dream_transport SHARED transport.cpp)
target_compile_features(dream_transport PRIVATE cxx_std_17)
target_compile_options(dream_transport PRIVATE -Wall -Wextra -Werror)

add_library(dev1_payload SHARED dev1_payload.cpp)
target_compile_features(dev1_payload PRIVATE cxx_std_17)
target_compile_options(dev1_payload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(dev1_payload PRIVATE dl)

find_package(Python3 REQUIRED COMPONENTS Interpreter)

set(GLSLC_CANDIDATES
    "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc"
    "${ANDROID_NDK}/shader-tools/darwin-x86_64/glslc"
    "${ANDROID_NDK}/shader-tools/darwin-arm64/glslc"
    "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe"
)
set(GLSLC "")
foreach(candidate IN LISTS GLSLC_CANDIDATES)
    if(EXISTS "${candidate}")
        set(GLSLC "${candidate}")
        break()
    endif()
endforeach()
if(NOT GLSLC)
    find_program(GLSLC glslc)
endif()
if(NOT GLSLC)
    message(FATAL_ERROR "Vessel Vulkan renderer requires glslc from the Android NDK shader-tools directory")
endif()

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")

set(VERT_SRC "${CMAKE_CURRENT_SOURCE_DIR}/shaders/native_cube.vert")
set(FRAG_SRC "${CMAKE_CURRENT_SOURCE_DIR}/shaders/native_cube.frag")
set(VERT_SPV "${GENERATED_DIR}/native_cube.vert.spv")
set(FRAG_SPV "${GENERATED_DIR}/native_cube.frag.spv")
set(VERT_HEADER "${GENERATED_DIR}/native_cube_vert_spv.h")
set(FRAG_HEADER "${GENERATED_DIR}/native_cube_frag_spv.h")

add_custom_command(
    OUTPUT "${VERT_SPV}" "${VERT_HEADER}"
    COMMAND "${GLSLC}" -fshader-stage=vert "${VERT_SRC}" -o "${VERT_SPV}"
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py" "${VERT_SPV}" "${VERT_HEADER}" native_cube_vert_spv
    DEPENDS "${VERT_SRC}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py"
    VERBATIM
)

add_custom_command(
    OUTPUT "${FRAG_SPV}" "${FRAG_HEADER}"
    COMMAND "${GLSLC}" -fshader-stage=frag "${FRAG_SRC}" -o "${FRAG_SPV}"
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py" "${FRAG_SPV}" "${FRAG_HEADER}" native_cube_frag_spv
    DEPENDS "${FRAG_SRC}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py"
    VERBATIM
)

add_custom_target(vessel_vulkan_shaders DEPENDS "${VERT_HEADER}" "${FRAG_HEADER}")

# Keep the Vulkan Studio benchmark as a first-class Vessel diagnostic even after
# the Linux desktop transport lands. It exercises the native Android Surface,
# Adreno Vulkan path, GPU timings, PBR-style shaders and stress geometry.
add_library(vessel_vulkan SHARED native_vulkan_studio.cpp)
add_dependencies(vessel_vulkan vessel_vulkan_shaders)
target_compile_features(vessel_vulkan PRIVATE cxx_std_17)
target_compile_definitions(vessel_vulkan PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(vessel_vulkan PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers)
target_include_directories(vessel_vulkan PRIVATE "${GENERATED_DIR}")
target_link_libraries(vessel_vulkan PRIVATE android log vulkan)
