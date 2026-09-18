#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "vessel_proroot_surfacecontrol.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <dlfcn.h>
#include <mutex>
#include <new>
#include <unistd.h>

namespace {

constexpr const char* TAG = "VesselProrootSurface";

using BufferReleaseFn = void (*)(void*, int);
using SetBufferWithReleaseFn = void (*)(
    ASurfaceTransaction*,
    ASurfaceControl*,
    AHardwareBuffer*,
    int,
    void*,
    BufferReleaseFn);
using SetBackPressureFn = void (*)(
    ASurfaceTransaction*,
    ASurfaceControl*,
    bool);
using SetGeometryFn = void (*)(
    ASurfaceTransaction*,
    ASurfaceControl*,
    const ARect*,
    const ARect*,
    int32_t);

struct ReleaseContext {
    VesselProrootBufferReleaseCallback callback = nullptr;
    void* opaque = nullptr;
    uint32_t slot = 0;
    uint64_t generation = 0;
};

class SurfaceControlPresenter {
public:
    bool available() {
        std::lock_guard<std::mutex> guard(lock_);
        resolve_locked();
        return set_buffer_with_release_ != nullptr && set_geometry_ != nullptr;
    }

    bool attach(
        JNIEnv* env,
        jobject surface,
        uint32_t buffer_width,
        uint32_t buffer_height,
        float refresh_hz) {

        if (!env || !surface) return false;
        ANativeWindow* next_window = ANativeWindow_fromSurface(env, surface);
        if (!next_window) return false;

        ASurfaceControl* next_control =
            ASurfaceControl_createFromWindow(next_window, "VesselProrootZeroCopy");
        if (!next_control) {
            ANativeWindow_release(next_window);
            return false;
        }

        std::lock_guard<std::mutex> guard(lock_);
        resolve_locked();
        if (!set_buffer_with_release_ || !set_geometry_) {
            ASurfaceControl_release(next_control);
            ANativeWindow_release(next_window);
            return false;
        }

        detach_locked();
        window_ = next_window;
        control_ = next_control;
        buffer_width_ = std::max(1u, buffer_width);
        buffer_height_ = std::max(1u, buffer_height);
        surface_width_ = static_cast<uint32_t>(
            std::max(1, ANativeWindow_getWidth(window_)));
        surface_height_ = static_cast<uint32_t>(
            std::max(1, ANativeWindow_getHeight(window_)));
        refresh_hz_ = std::clamp(refresh_hz, 1.0f, 240.0f);

        apply_metadata_locked();
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "zero-copy SurfaceControl attached %ux%u buffer=%ux%u rate=%.2f",
            surface_width_,
            surface_height_,
            buffer_width_,
            buffer_height_,
            refresh_hz_);
        return true;
    }

    void detach() {
        std::lock_guard<std::mutex> guard(lock_);
        detach_locked();
    }

    void configure(uint32_t buffer_width, uint32_t buffer_height, float refresh_hz) {
        std::lock_guard<std::mutex> guard(lock_);
        buffer_width_ = std::max(1u, buffer_width);
        buffer_height_ = std::max(1u, buffer_height);
        refresh_hz_ = std::clamp(refresh_hz, 1.0f, 240.0f);
        if (window_) {
            surface_width_ = static_cast<uint32_t>(
                std::max(1, ANativeWindow_getWidth(window_)));
            surface_height_ = static_cast<uint32_t>(
                std::max(1, ANativeWindow_getHeight(window_)));
        }
        if (control_) apply_metadata_locked();
    }

    bool present(
        AHardwareBuffer* buffer,
        int acquire_fence_fd,
        uint32_t slot,
        uint64_t generation,
        VesselProrootBufferReleaseCallback callback,
        void* opaque) {

        if (!buffer || !callback) return false;

        std::lock_guard<std::mutex> guard(lock_);
        resolve_locked();
        if (!control_ || !set_buffer_with_release_) return false;

        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        if (!desc.width || !desc.height) return false;

        auto* context = new (std::nothrow) ReleaseContext{
            callback,
            opaque,
            slot,
            generation,
        };
        if (!context) return false;

        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        if (!transaction) {
            delete context;
            return false;
        }

        const ARect source{
            0,
            0,
            static_cast<int32_t>(desc.width),
            static_cast<int32_t>(desc.height),
        };
        const ARect destination = letterbox_locked(desc.width, desc.height);

        if (!set_geometry_) {
            ASurfaceTransaction_delete(transaction);
            delete context;
            return false;
        }
        set_geometry_(
            transaction,
            control_,
            &source,
            &destination,
            0);
        ASurfaceTransaction_setBufferTransparency(
            transaction,
            control_,
            ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setVisibility(
            transaction,
            control_,
            ASURFACE_TRANSACTION_VISIBILITY_SHOW);

        // API 36: SurfaceFlinger consumes KWin's native render fence directly and
        // invokes release_callback only when this exact AHardwareBuffer can be
        // rendered into again.
        set_buffer_with_release_(
            transaction,
            control_,
            buffer,
            acquire_fence_fd,
            context,
            &SurfaceControlPresenter::release_trampoline);

        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
        return true;
    }

    bool attached() const {
        std::lock_guard<std::mutex> guard(lock_);
        return control_ != nullptr;
    }

private:
    mutable std::mutex lock_;
    void* libandroid_ = nullptr;
    SetBufferWithReleaseFn set_buffer_with_release_ = nullptr;
    SetBackPressureFn set_back_pressure_ = nullptr;
    SetGeometryFn set_geometry_ = nullptr;
    ANativeWindow* window_ = nullptr;
    ASurfaceControl* control_ = nullptr;
    uint32_t buffer_width_ = 1280;
    uint32_t buffer_height_ = 720;
    uint32_t surface_width_ = 1280;
    uint32_t surface_height_ = 720;
    float refresh_hz_ = 60.0f;

    static void release_trampoline(void* opaque, int release_fence_fd) {
        auto* context = static_cast<ReleaseContext*>(opaque);
        if (!context) {
            if (release_fence_fd >= 0) close(release_fence_fd);
            return;
        }
        const auto callback = context->callback;
        void* callback_opaque = context->opaque;
        const uint32_t slot = context->slot;
        const uint64_t generation = context->generation;
        delete context;

        if (callback) {
            callback(callback_opaque, slot, generation, release_fence_fd);
        } else if (release_fence_fd >= 0) {
            close(release_fence_fd);
        }
    }

    void resolve_locked() {
        if (set_buffer_with_release_) return;
        if (!libandroid_) {
            libandroid_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        }
        void* symbol = dlsym(
            libandroid_ ? libandroid_ : RTLD_DEFAULT,
            "ASurfaceTransaction_setBufferWithRelease");
        set_buffer_with_release_ =
            reinterpret_cast<SetBufferWithReleaseFn>(symbol);
        set_back_pressure_ = reinterpret_cast<SetBackPressureFn>(
            dlsym(
                libandroid_ ? libandroid_ : RTLD_DEFAULT,
                "ASurfaceTransaction_setEnableBackPressure"));
        set_geometry_ = reinterpret_cast<SetGeometryFn>(
            dlsym(
                libandroid_ ? libandroid_ : RTLD_DEFAULT,
                "ASurfaceTransaction_setGeometry"));
    }

    ARect letterbox_locked(uint32_t source_width, uint32_t source_height) const {
        const double sx =
            static_cast<double>(surface_width_) / std::max(1u, source_width);
        const double sy =
            static_cast<double>(surface_height_) / std::max(1u, source_height);
        const double scale = std::min(sx, sy);
        const int32_t width = std::max(
            1,
            static_cast<int32_t>(std::llround(source_width * scale)));
        const int32_t height = std::max(
            1,
            static_cast<int32_t>(std::llround(source_height * scale)));
        const int32_t left =
            (static_cast<int32_t>(surface_width_) - width) / 2;
        const int32_t top =
            (static_cast<int32_t>(surface_height_) - height) / 2;
        return ARect{left, top, left + width, top + height};
    }

    void apply_metadata_locked() {
        if (!control_) return;
        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        if (!transaction) return;
        ASurfaceTransaction_setBufferTransparency(
            transaction,
            control_,
            ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setVisibility(
            transaction,
            control_,
            ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        if (set_back_pressure_) {
            // KWin already has a bounded triple-buffer queue. Requiring each
            // submitted buffer to be presented avoids rendering frames that
            // SurfaceFlinger would immediately drop.
            set_back_pressure_(transaction, control_, true);
        }
        ASurfaceTransaction_setFrameRate(
            transaction,
            control_,
            refresh_hz_,
            ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
    }

    void detach_locked() {
        if (control_) {
            ASurfaceTransaction* transaction = ASurfaceTransaction_create();
            if (transaction) {
                ASurfaceTransaction_setVisibility(
                    transaction,
                    control_,
                    ASURFACE_TRANSACTION_VISIBILITY_HIDE);
                ASurfaceTransaction_apply(transaction);
                ASurfaceTransaction_delete(transaction);
            }
            ASurfaceControl_release(control_);
            control_ = nullptr;
        }
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }
};

SurfaceControlPresenter g_presenter;

} // namespace

bool vessel_proroot_surfacecontrol_available() {
    return g_presenter.available();
}

bool vessel_proroot_surfacecontrol_attach(
    JNIEnv* env,
    jobject surface,
    uint32_t buffer_width,
    uint32_t buffer_height,
    float refresh_hz) {
    return g_presenter.attach(
        env,
        surface,
        buffer_width,
        buffer_height,
        refresh_hz);
}

void vessel_proroot_surfacecontrol_detach() {
    g_presenter.detach();
}

void vessel_proroot_surfacecontrol_configure(
    uint32_t buffer_width,
    uint32_t buffer_height,
    float refresh_hz) {
    g_presenter.configure(buffer_width, buffer_height, refresh_hz);
}

bool vessel_proroot_surfacecontrol_present(
    AHardwareBuffer* buffer,
    int acquire_fence_fd,
    uint32_t slot,
    uint64_t generation,
    VesselProrootBufferReleaseCallback callback,
    void* opaque) {
    return g_presenter.present(
        buffer,
        acquire_fence_fd,
        slot,
        generation,
        callback,
        opaque);
}

bool vessel_proroot_surfacecontrol_attached() {
    return g_presenter.attached();
}
