cmake_minimum_required(VERSION 3.22.1)
project(vessel_native)

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
    message(FATAL_ERROR "Bounce Quest requires glslc")
endif()

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")

function(bounce_shader SOURCE STAGE SYMBOL)
    get_filename_component(BASE "${SOURCE}" NAME)
    set(SPV "${GENERATED_DIR}/${BASE}.spv")
    set(HEADER "${GENERATED_DIR}/${SYMBOL}_spv.h")
    add_custom_command(
        OUTPUT "${SPV}" "${HEADER}"
        COMMAND "${GLSLC}" --target-env=vulkan1.2 -fshader-stage=${STAGE} "${CMAKE_CURRENT_SOURCE_DIR}/${SOURCE}" -o "${SPV}"
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py" "${SPV}" "${HEADER}" ${SYMBOL}_spv
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/${SOURCE}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.py"
        VERBATIM)
    set(BOUNCE_SHADER_OUTPUTS ${BOUNCE_SHADER_OUTPUTS} "${HEADER}" PARENT_SCOPE)
endfunction()

bounce_shader(engine/renderer/shaders/bounce.vert vert bounce_vert)
bounce_shader(engine/renderer/shaders/bounce.frag frag bounce_frag)
add_custom_target(bounce_quest_shaders DEPENDS ${BOUNCE_SHADER_OUTPUTS})

add_library(bounce_quest SHARED
    engine/renderer/BounceRenderer.cpp
    engine/physics/BallPhysics.cpp)
add_dependencies(bounce_quest bounce_quest_shaders)
target_compile_features(bounce_quest PRIVATE cxx_std_20)
target_compile_definitions(bounce_quest PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(bounce_quest PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wno-missing-field-initializers
    "-include${CMAKE_CURRENT_SOURCE_DIR}/vulkan_android_compat.h")
target_include_directories(bounce_quest PRIVATE
    "${GENERATED_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party"
    "${CMAKE_CURRENT_SOURCE_DIR}/engine")
target_link_libraries(bounce_quest PRIVATE android log vulkan)
