namespace { struct Vec3; Vec3 operator*(float s,Vec3 a); }

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-parameter"
#include "native_vulkan_studio_v7_part1.inc"
#include "native_vulkan_studio_v7_part2.inc"
#include "native_vulkan_studio_v7_part3.inc"
#include "native_vulkan_studio_v7_part4.inc"
#include "native_vulkan_studio_v7_part5.inc"
#pragma clang diagnostic pop

namespace { Vec3 operator*(float s,Vec3 a){return a*s;} }

extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPathTracing(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV7(h))r->setPathTracing(v);}
