package com.example.dreamlinux

import android.view.Surface

/**
 * Same-UID graphics frontend. Standard vhost-user-gpu owns EDID/cursor control.
 * AHardwareBuffer is only the cross-process GPU handoff; the final frame is drawn
 * straight into the Android Surface BufferQueue with EGL/GLES and SurfaceFlinger.
 */
object VesselWaylandPresenter {
    @Volatile private var started = false
    @Volatile private var everPresented = false

    init { System.loadLibrary("vessel_wayland_presenter") }

    @JvmStatic private external fun nativeConfigure(path: String, width: Int, height: Int, dpi: Int, refresh: Float)
    @JvmStatic private external fun nativeStart()
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeStatus(): String
    @JvmStatic private external fun nativeCursorX(): Int
    @JvmStatic private external fun nativeCursorY(): Int
    @JvmStatic private external fun nativeCursorHotX(): Int
    @JvmStatic private external fun nativeCursorHotY(): Int
    @JvmStatic private external fun nativeCursorVisible(): Boolean
    @JvmStatic private external fun nativeCursorSerial(): Long
    @JvmStatic private external fun nativeCursorPixels(): IntArray

    @JvmStatic private external fun nativeAhbConfigure(width: Int, height: Int)
    @JvmStatic private external fun nativeAhbStart()
    @JvmStatic private external fun nativeAhbStop()
    @JvmStatic private external fun nativeAhbAttachSurface(surface: Surface)
    @JvmStatic private external fun nativeAhbSurfaceChanged(width: Int, height: Int)
    @JvmStatic private external fun nativeAhbDetachSurface()
    @JvmStatic private external fun nativeAhbStatus(): String
    @JvmStatic private external fun nativeAhbGuestWidth(): Int
    @JvmStatic private external fun nativeAhbGuestHeight(): Int

    @Synchronized
    fun configure(path: String, width: Int, height: Int, dpi: Int, refresh: Float) {
        nativeConfigure(path, width, height, dpi, refresh)
        nativeAhbConfigure(width, height)
        if (!started) {
            nativeStart()
            nativeAhbStart()
            started = true
        }
    }

    fun attach(surface: Surface) = nativeAhbAttachSurface(surface)
    fun surfaceChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) nativeAhbSurfaceChanged(width, height)
    }
    fun detach() = nativeAhbDetachSurface()

    fun status(): String {
        if (!started) return "not-started"
        val ahb = runCatching { nativeAhbStatus() }.getOrElse { "presenter-error:${it.message}" }
        val standard = runCatching { nativeStatus() }.getOrElse { "presenter-error:${it.message}" }
        val s = when {
            ahb == "presenting-native-surface" -> "presenting-native-surface"
            ahb.startsWith("presenter-error") -> ahb
            standard.startsWith("presenter-error") || standard.contains("failed") -> standard
            else -> ahb
        }
        if (s.startsWith("presenting-native-surface")) everPresented = true
        return if (everPresented && (s == "surface-detached" || s.contains("waiting-for-surface"))) {
            "presenting-retained:$s"
        } else {
            s
        }
    }

    fun resetPresentationLatch() { everPresented = false }
    fun cursorX() = nativeCursorX()
    fun cursorY() = nativeCursorY()
    fun cursorHotX() = nativeCursorHotX()
    fun cursorHotY() = nativeCursorHotY()
    fun cursorVisible() = nativeCursorVisible()
    fun cursorSerial() = nativeCursorSerial()
    fun guestWidth() = nativeAhbGuestWidth()
    fun guestHeight() = nativeAhbGuestHeight()
    fun cursorPixels() = nativeCursorPixels()

    fun shutdown() {
        if (started) {
            nativeAhbStop()
            nativeStop()
        }
        started = false
        everPresented = false
    }
}
