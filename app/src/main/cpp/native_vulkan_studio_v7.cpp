namespace { struct Vec3; Vec3 operator*(float s,Vec3 a); }

#include "native_vulkan_studio_v7_part1.inc"
#include "native_vulkan_studio_v7_part2.inc"
#include "native_vulkan_studio_v7_part3.inc"
#include "native_vulkan_studio_v7_part4.inc"
#include "native_vulkan_studio_v7_part5.inc"

namespace { Vec3 operator*(float s,Vec3 a){return a*s;} }
