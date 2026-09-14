package com.example.dreamlinux

import android.view.Surface

/**
 * Native GPU presenter for Vessel's Wayland/Venus output path.
 *
 * Frames arrive as compositor dma-bufs. Native code imports them into Vulkan
 * and presents them to the existing Vessel SurfaceView without VNC, screenshots
 * or CPU framebuffer copies. The Linux compositor is intentionally independent
 * from this Android presentation layer.
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
