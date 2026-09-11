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
    "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
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
    message(FATAL_ERROR "Vessel Vulkan Studio requires glslc")
endif()

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")

function(vessel_shader SOURCE STAGE SYMBOL)
    get_filename_component(BASE "${SOURCE}" NAME)
    set(SPV "${GENERATED_DIR}/${BASE}.spv")
    set(HEADER "${GENERATED_DIR}/${SYMBOL}_spv.h")
    add_custom_command(
        OUTPUT "${SPV}" "${HEADER}"
        COMMAND "${GLSLC}" --target-env=vulkan1.2 -fshader-stage=${STAGE} "${CMAKE_CURRENT_SOURCE_DIR}/${SOURCE}" -o "${SPV}"
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py" "${SPV}" "${HEADER}" ${SYMBOL}_spv
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/${SOURCE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py"
        VERBATIM)
    set(VESSEL_SHADER_OUTPUTS ${VESSEL_SHADER_OUTPUTS} "${HEADER}" PARENT_SCOPE)
endfunction()

vessel_shader(shaders/native_studio_v3.vert vert native_studio_v3_vert)
vessel_shader(shaders/native_studio_v3.frag frag native_studio_v3_frag)
vessel_shader(shaders/native_studio_v3_rt.frag frag native_studio_v3_rt_frag)
add_custom_target(vessel_vulkan_shaders DEPENDS ${VESSEL_SHADER_OUTPUTS})

add_library(vessel_vulkan SHARED native_vulkan_studio_v3.cpp)
add_dependencies(vessel_vulkan vessel_vulkan_shaders)
target_compile_features(vessel_vulkan PRIVATE cxx_std_20)
target_compile_definitions(vessel_vulkan PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(vessel_vulkan PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wno-missing-field-initializers
    "-include${CMAKE_CURRENT_SOURCE_DIR}/vulkan_android_compat.h")
target_include_directories(vessel_vulkan PRIVATE "${GENERATED_DIR}")
target_link_libraries(vessel_vulkan PRIVATE android log vulkan)
