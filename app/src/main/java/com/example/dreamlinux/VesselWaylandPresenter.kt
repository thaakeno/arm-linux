package com.example.dreamlinux

import android.view.Surface

/**
 * Native GPU presenter for the Wayland/Venus output path.
 *
 * Frames arrive as exported dma-bufs from the KWin Wayland compositor. Native
 * code imports them into Vulkan, copies them through a triple AHardwareBuffer
 * ring entirely on the GPU, then presents through the SurfaceView swapchain.
 */
object VesselWaylandPresenter {
    init {
        System.loadLibrary("vessel_wayland_presenter")
        nativeStart()
    }

    @JvmStatic private external fun nativeStart()
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeAttachSurface(surface: Surface)
    @JvmStatic private external fun nativeDetachSurface()
    @JvmStatic private external fun nativeStatus(): String

    fun attach(surface: Surface) = nativeAttachSurface(surface)
    fun detach() = nativeDetachSurface()
    fun status(): String = runCatching { nativeStatus() }.getOrElse { "presenter-error:${it.message}" }
    fun shutdown() = nativeStop()
}
