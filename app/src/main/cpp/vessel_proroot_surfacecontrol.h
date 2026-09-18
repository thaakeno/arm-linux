#pragma once

#include <jni.h>
#include <android/hardware_buffer.h>
#include <cstdint>

using VesselProrootBufferReleaseCallback =
    void (*)(void* opaque, uint32_t slot, uint64_t generation, int release_fence_fd);

bool vessel_proroot_surfacecontrol_available();

bool vessel_proroot_surfacecontrol_attach(
    JNIEnv* env,
    jobject surface,
    uint32_t buffer_width,
    uint32_t buffer_height,
    float refresh_hz);

void vessel_proroot_surfacecontrol_detach();

void vessel_proroot_surfacecontrol_configure(
    uint32_t buffer_width,
    uint32_t buffer_height,
    float refresh_hz);

bool vessel_proroot_surfacecontrol_present(
    AHardwareBuffer* buffer,
    int acquire_fence_fd,
    uint32_t slot,
    uint64_t generation,
    VesselProrootBufferReleaseCallback callback,
    void* opaque);

bool vessel_proroot_surfacecontrol_attached();
