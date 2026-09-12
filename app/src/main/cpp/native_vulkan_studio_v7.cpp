namespace { struct Vec3; Vec3 operator*(float s,Vec3 a); }

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-parameter"
#include "native_vulkan_studio_v7_part1.inc"
#include "native_vulkan_studio_v7_part2.inc"
#include "native_vulkan_studio_v7_part3.inc"
#define VK_STRUCTURE_TYPE_PRESENT_INFO VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
#include "native_vulkan_studio_v7_part4.inc"
#undef VK_STRUCTURE_TYPE_PRESENT_INFO
#include "native_vulkan_studio_v7_part5.inc"
#pragma clang diagnostic pop

namespace { Vec3 operator*(float s,Vec3 a){return a*s;} }
