package com.example.dreamlinux

import android.view.Surface

/**
 * Native Vulkan presenter for Vessel's Android SurfaceView.
 *
 * Protocol 38 keeps Linux rendering on virtio-gpu/VirGL/ANGLE/Adreno. The
 * cross-app frame stream enters Vessel over loopback TCP and is proxied from
 * inside this UID to the native presenter. The native side performs the final
 * Vulkan upload/blit/present to SurfaceFlinger.
 */
object VesselWaylandPresenter {
    init {
        System.loadLibrary("vessel_wayland_presenter")
        nativeStart()
        VesselFrameTcpBridge.start()
    }

    @JvmStatic private external fun nativeStart()
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeAttachSurface(surface: Surface)
    @JvmStatic private external fun nativeDetachSurface()
    @JvmStatic private external fun nativeStatus(): String

    fun attach(surface: Surface) = nativeAttachSurface(surface)
    fun detach() = nativeDetachSurface()
    fun status(): String = runCatching { nativeStatus() }.getOrElse { "presenter-error:${it.message}" }
    fun shutdown() {
        VesselFrameTcpBridge.stop()
        nativeStop()
    }
}
