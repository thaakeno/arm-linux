package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.max

/**
 * Android side of the compositor-level proroot display contract.
 *
 * KWin renders directly into Vessel-owned AHardwareBuffers through imported
 * DMA-BUFs. This bridge forwards the selected buffer + native render fence into
 * the existing native Surface presenter; it never reads framebuffer pixels.
 */
object VesselProrootDisplayBridge {
    init { System.loadLibrary("vessel_wayland_presenter") }

    private external fun nativeStart(
        socketPath: String,
        width: Int,
        height: Int,
        refresh: Float,
    ): Boolean
    private external fun nativeStop()
    private external fun nativeConfigure(width: Int, height: Int, refresh: Float)
    private external fun nativeStatus(): String
    private external fun nativeTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean
    private external fun nativePointer(x: Float, y: Float, dx: Float, dy: Float): Boolean
    private external fun nativeButton(code: Int, down: Boolean): Boolean
    private external fun nativeScroll(axis: Int, value: Float): Boolean
    private external fun nativeKey(code: Int, down: Boolean): Boolean
    private external fun nativeText(value: ByteArray): Boolean
    private external fun nativeClipboard(value: ByteArray): Boolean

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var started = false
    @Volatile private var width = 1280
    @Volatile private var height = 720
    @Volatile private var pointerX = 640f
    @Volatile private var pointerY = 360f
    @Volatile private var touchDown = false

    private var appContext: Context? = null
    private var clipboard: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile private var lastGuestClipboard = ""

    fun start(
        context: Context,
        socket: File,
        width: Int,
        height: Int,
        refresh: Float,
    ): Boolean = synchronized(lock) {
        stopLocked()
        val parent = socket.parentFile
        check(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "Could not create proroot display socket directory"
        }
        socket.delete()

        this.width = width.coerceIn(320, 4096)
        this.height = height.coerceIn(240, 4096)
        pointerX = this.width / 2f
        pointerY = this.height / 2f
        touchDown = false

        VesselWaylandPresenter.configureSurfaceOnly(this.width, this.height)
        val ok = nativeStart(
            socket.absolutePath,
            this.width,
            this.height,
            refresh.coerceIn(1f, 240f),
        )
        if (!ok) {
            VesselWaylandPresenter.shutdownSurfaceOnly()
            return@synchronized false
        }

        appContext = context.applicationContext
        clipboard = appContext?.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val manager = clipboard ?: return@OnPrimaryClipChangedListener
            val clip = manager.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount <= 0) return@OnPrimaryClipChangedListener
            val value = clip.getItemAt(0).coerceToText(appContext).toString()
            if (value.isBlank() || value == lastGuestClipboard) return@OnPrimaryClipChangedListener
            nativeClipboard(value.toByteArray(StandardCharsets.UTF_8))
        }
        clipboardListener = listener
        clipboard?.addPrimaryClipChangedListener(listener)
        started = true
        LinuxDesktopView.active?.reattachPresenter()
        true
    }

    fun stop() = synchronized(lock) {
        stopLocked()
    }

    private fun stopLocked() {
        clipboardListener?.let { listener ->
            runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        }
        clipboardListener = null
        clipboard = null
        appContext = null
        lastGuestClipboard = ""
        touchDown = false
        if (started) runCatching { nativeStop() }
        started = false
        VesselWaylandPresenter.shutdownSurfaceOnly()
    }

    fun configure(width: Int, height: Int, refresh: Float) {
        if (!started) return
        this.width = width.coerceIn(320, 4096)
        this.height = height.coerceIn(240, 4096)
        pointerX = pointerX.coerceIn(0f, this.width.toFloat())
        pointerY = pointerY.coerceIn(0f, this.height.toFloat())
        VesselWaylandPresenter.configureSurfaceOnly(this.width, this.height)
        nativeConfigure(this.width, this.height, refresh.coerceIn(1f, 240f))
    }

    fun status(): String =
        if (!started) "proroot-display-stopped"
        else runCatching { nativeStatus() }.getOrElse { "proroot-display-error:" + it.message }

    fun absoluteNormalized(x: Float, y: Float, down: Boolean): Boolean {
        if (!started) return false
        pointerX = x.coerceIn(0f, 1f) * max(1, width).toFloat()
        pointerY = y.coerceIn(0f, 1f) * max(1, height).toFloat()
        val action = when {
            down && !touchDown -> 0
            down -> 2
            !down && touchDown -> 1
            else -> 2
        }
        val ok = nativeTouch(action, pointerX, pointerY, 0)
        touchDown = down
        return ok
    }

    fun relative(dx: Float, dy: Float): Boolean {
        if (!started) return false
        pointerX = (pointerX + dx).coerceIn(0f, max(1, width).toFloat())
        pointerY = (pointerY + dy).coerceIn(0f, max(1, height).toFloat())
        return nativePointer(pointerX, pointerY, dx, dy)
    }

    fun button(code: Int, down: Boolean): Boolean =
        started && nativeButton(code, down)

    fun key(code: Int, down: Boolean): Boolean =
        started && nativeKey(code, down)

    fun scroll(x: Float, y: Float): Boolean {
        if (!started) return false
        var ok = true
        if (y != 0f) ok = nativeScroll(0, y) && ok
        if (x != 0f) ok = nativeScroll(1, x) && ok
        return ok
    }

    fun text(value: String): Boolean =
        value.isEmpty() || (started && nativeText(value.toByteArray(StandardCharsets.UTF_8)))

    @Suppress("unused")
    fun onGuestClipboardFromNative(value: ByteArray) {
        if (value.isEmpty()) return
        val text = value.toString(StandardCharsets.UTF_8)
        if (text.isEmpty()) return
        lastGuestClipboard = text
        val context = appContext ?: return
        mainHandler.post {
            val manager = clipboard ?: context.getSystemService(ClipboardManager::class.java)
            manager?.setPrimaryClip(ClipData.newPlainText("Vessel Linux", text))
        }
    }
}
