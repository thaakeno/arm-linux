package com.example.dreamlinux

import android.view.Surface

/**
 * Android native presentation frontend.
 *
 * UML uses both the standard vhost-user-gpu control/cursor channel and the
 * AHardwareBuffer scanout presenter. The proroot backend uses only the AHB
 * presenter because KWin itself is the display producer.
 */
object VesselWaylandPresenter {
    @Volatile private var standardStarted = false
    @Volatile private var ahbStarted = false
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

    /** Current UML path: vhost metadata/cursor + AHB scanout. */
    @Synchronized
    fun configure(path: String, width: Int, height: Int, dpi: Int, refresh: Float) {
        nativeConfigure(path, width, height, dpi, refresh)
        ensureAhb(width, height)
        if (!standardStarted) {
            nativeStart()
            standardStarted = true
        }
    }

    /**
     * Proroot path: only the native AHB Surface presenter. No unused vhost
     * control/cursor frontend is started.
     */
    @Synchronized
    fun configureSurfaceOnly(width: Int, height: Int) {
        ensureAhb(width, height)
    }

    @Synchronized
    private fun ensureAhb(width: Int, height: Int) {
        nativeAhbConfigure(width, height)
        if (!ahbStarted) {
            nativeAhbStart()
            ahbStarted = true
        }
    }

    fun attach(surface: Surface) {
        if (ahbStarted) nativeAhbAttachSurface(surface)
    }

    fun surfaceChanged(width: Int, height: Int) {
        if (ahbStarted && width > 0 && height > 0) nativeAhbSurfaceChanged(width, height)
    }

    fun detach() {
        if (ahbStarted) nativeAhbDetachSurface()
    }

    fun status(): String {
        if (!ahbStarted && !standardStarted) return "not-started"
        val ahb = if (ahbStarted) {
            runCatching { nativeAhbStatus() }.getOrElse { "presenter-error:" + it.message }
        } else {
            "ahb-not-started"
        }
        val standard = if (standardStarted) {
            runCatching { nativeStatus() }.getOrElse { "presenter-error:" + it.message }
        } else {
            ""
        }
        val value = when {
            ahb.startsWith("presenter-error") -> ahb
            standard.startsWith("presenter-error") || standard.contains("failed") -> standard
            else -> ahb
        }
        if (value.startsWith("presenting-native-surface")) everPresented = true
        return if (everPresented &&
            (value == "surface-detached" || value.contains("waiting-for-surface"))
        ) {
            "presenting-retained:" + value
        } else {
            value
        }
    }

    fun resetPresentationLatch() { everPresented = false }

    fun cursorX() = if (standardStarted) nativeCursorX() else 0
    fun cursorY() = if (standardStarted) nativeCursorY() else 0
    fun cursorHotX() = if (standardStarted) nativeCursorHotX() else 0
    fun cursorHotY() = if (standardStarted) nativeCursorHotY() else 0
    fun cursorVisible() = standardStarted && nativeCursorVisible()
    fun cursorSerial() = if (standardStarted) nativeCursorSerial() else 0L
    fun guestWidth() = if (ahbStarted) nativeAhbGuestWidth() else 1
    fun guestHeight() = if (ahbStarted) nativeAhbGuestHeight() else 1
    fun cursorPixels() = if (standardStarted) nativeCursorPixels() else IntArray(0)

    @Synchronized
    fun shutdownSurfaceOnly() {
        if (ahbStarted && !standardStarted) {
            nativeAhbStop()
            ahbStarted = false
            everPresented = false
        }
    }

    @Synchronized
    fun shutdown() {
        if (ahbStarted) nativeAhbStop()
        if (standardStarted) nativeStop()
        ahbStarted = false
        standardStarted = false
        everPresented = false
    }
}
