package com.example.dreamlinux

import android.view.Surface

/** Same-UID vhost-user-gpu frontend. Only DMA-BUF scanout is accepted. */
object VesselWaylandPresenter {
    @Volatile private var started = false
    @Volatile private var everPresented = false

    init { System.loadLibrary("vessel_wayland_presenter") }

    @JvmStatic private external fun nativeConfigure(path: String, width: Int, height: Int, dpi: Int, refresh: Float)
    @JvmStatic private external fun nativeStart()
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeAttachSurface(surface: Surface)
    @JvmStatic private external fun nativeDetachSurface()
    @JvmStatic private external fun nativeStatus(): String
    @JvmStatic private external fun nativeCursorX(): Int
    @JvmStatic private external fun nativeCursorY(): Int
    @JvmStatic private external fun nativeCursorHotX(): Int
    @JvmStatic private external fun nativeCursorHotY(): Int
    @JvmStatic private external fun nativeCursorVisible(): Boolean
    @JvmStatic private external fun nativeCursorSerial(): Long
    @JvmStatic private external fun nativeGuestWidth(): Int
    @JvmStatic private external fun nativeGuestHeight(): Int
    @JvmStatic private external fun nativeCursorPixels(): IntArray

    @Synchronized
    fun configure(path: String, width: Int, height: Int, dpi: Int, refresh: Float) {
        nativeConfigure(path, width, height, dpi, refresh)
        if (!started) { nativeStart(); started = true }
    }

    fun attach(surface: Surface) = nativeAttachSurface(surface)
    fun detach() = nativeDetachSurface()
    fun status(): String {
        if (!started) return "not-started"
        val s = runCatching { nativeStatus() }.getOrElse { "presenter-error:${it.message}" }
        if (s.startsWith("presenting-dmabuf")) everPresented = true
        return if (everPresented && (s == "surface-detached" || s == "waiting-for-surface")) "presenting-retained:$s" else s
    }
    fun resetPresentationLatch() { everPresented = false }
    fun cursorX() = nativeCursorX()
    fun cursorY() = nativeCursorY()
    fun cursorHotX() = nativeCursorHotX()
    fun cursorHotY() = nativeCursorHotY()
    fun cursorVisible() = nativeCursorVisible()
    fun cursorSerial() = nativeCursorSerial()
    fun guestWidth() = nativeGuestWidth()
    fun guestHeight() = nativeGuestHeight()
    fun cursorPixels() = nativeCursorPixels()
    fun shutdown() { if (started) nativeStop(); started = false; everPresented = false }
}
