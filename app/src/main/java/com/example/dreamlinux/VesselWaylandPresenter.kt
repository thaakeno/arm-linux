package com.example.dreamlinux

import android.view.Surface

/**
 * Native Vulkan presenter for Vessel's Android SurfaceView.
 *
 * Protocol 38 keeps Linux rendering on virtio-gpu/VirGL/ANGLE/Adreno. The
 * cross-app frame stream enters Vessel over loopback TCP and is proxied from
 * inside this UID to the native presenter. The native side performs the final
 * Vulkan upload/blit/present to SurfaceFlinger.
 *
 * A successfully presented frame is latched independently from the temporary
 * SurfaceView attachment. Tab changes and activity backgrounding are allowed to
 * detach the Android Surface without downgrading the already-proven Linux boot
 * pipeline back to 96%. The native presenter retains the latest frame and
 * replays it when a new Surface is attached.
 */
object VesselWaylandPresenter {
    @Volatile private var everPresentedFrame = false

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

    fun status(): String {
        val raw = runCatching { nativeStatus() }
            .getOrElse { "presenter-error:${it.message}" }
        if (raw.startsWith("presenting-")) {
            everPresentedFrame = true
            return raw
        }
        return if (
            everPresentedFrame && (
                raw.startsWith("surface-") ||
                    raw.startsWith("waiting-for-surface") ||
                    raw.startsWith("ready:")
                )
        ) {
            "presenting-retained:$raw"
        } else {
            raw
        }
    }

    /** A fresh host frame stream means a fresh presentation generation. */
    fun resetPresentationLatch() {
        everPresentedFrame = false
    }

    fun shutdown() {
        everPresentedFrame = false
        VesselFrameTcpBridge.stop()
        nativeStop()
    }
}
