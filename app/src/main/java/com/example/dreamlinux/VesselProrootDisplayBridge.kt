package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max

/**
 * Android side of the compositor-level proroot display contract.
 *
 * API 36+ is true zero-copy: KWin renders into Vessel AHardwareBuffers and the
 * exact buffer + native render fence goes directly to SurfaceFlinger through
 * ASurfaceControl. Android 30-35 retains the Phase-4 GPU-blit presenter as a
 * compatibility fallback. Neither path reads pixels on the CPU.
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
    private external fun nativeZeroCopyAvailable(): Boolean
    private external fun nativeAttachSurface(surface: Surface): Boolean
    private external fun nativeDetachSurface()
    private external fun nativeSetEffectiveRefresh(refresh: Float)
    private external fun nativeFramesPresented(): Long
    private external fun nativeFramesReleased(): Long
    private external fun nativeEffectiveRefresh(): Float
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
    @Volatile private var zeroCopy = false
    @Volatile private var width = 1280
    @Volatile private var height = 720
    @Volatile private var maxRefreshHz = 60f
    @Volatile private var effectiveRefreshHz = 60f
    @Volatile private var pointerX = 640f
    @Volatile private var pointerY = 360f
    @Volatile private var touchDown = false
    @Volatile private var lastInteractionUptimeMs = 0L
    private var idleCheckScheduled = false

    private var appContext: Context? = null
    private var clipboard: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile private var lastGuestClipboard = ""

    private val idleRefreshRunnable = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (!started) {
                    idleCheckScheduled = false
                    return
                }
                val elapsed =
                    SystemClock.uptimeMillis() - lastInteractionUptimeMs
                val remaining =
                    VesselPerformancePolicy.IDLE_DELAY_MS - elapsed
                if (remaining > 0L) {
                    mainHandler.postDelayed(this, remaining)
                } else {
                    idleCheckScheduled = false
                    applyRefreshLocked(
                        VesselPerformancePolicy.idleRefresh(maxRefreshHz),
                    )
                }
            }
        }
    }

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
        maxRefreshHz = VesselPerformancePolicy.activeRefresh(refresh)
        effectiveRefreshHz = maxRefreshHz
        pointerX = this.width / 2f
        pointerY = this.height / 2f
        touchDown = false
        lastInteractionUptimeMs = SystemClock.uptimeMillis()

        val ok = nativeStart(
            socket.absolutePath,
            this.width,
            this.height,
            effectiveRefreshHz,
        )
        if (!ok) return@synchronized false

        zeroCopy = nativeZeroCopyAvailable()
        if (!zeroCopy) {
            VesselWaylandPresenter.configureSurfaceOnly(this.width, this.height)
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
        scheduleIdleLocked()
        LinuxDesktopView.active?.reattachPresenter()
        true
    }

    fun stop() = synchronized(lock) {
        stopLocked()
    }

    private fun stopLocked() {
        mainHandler.removeCallbacks(idleRefreshRunnable)
        idleCheckScheduled = false
        clipboardListener?.let { listener ->
            runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        }
        clipboardListener = null
        clipboard = null
        appContext = null
        lastGuestClipboard = ""
        touchDown = false
        if (started) {
            if (zeroCopy) runCatching { nativeDetachSurface() }
            runCatching { nativeStop() }
        }
        if (!zeroCopy) {
            VesselWaylandPresenter.shutdownSurfaceOnly()
        }
        started = false
        zeroCopy = false
    }

    fun configure(width: Int, height: Int, refresh: Float) = synchronized(lock) {
        if (!started) return@synchronized
        this.width = width.coerceIn(320, 4096)
        this.height = height.coerceIn(240, 4096)
        maxRefreshHz = VesselPerformancePolicy.activeRefresh(refresh)
        effectiveRefreshHz = maxRefreshHz
        lastInteractionUptimeMs = SystemClock.uptimeMillis()
        pointerX = pointerX.coerceIn(0f, this.width.toFloat())
        pointerY = pointerY.coerceIn(0f, this.height.toFloat())
        if (!zeroCopy) {
            VesselWaylandPresenter.configureSurfaceOnly(this.width, this.height)
        }
        nativeConfigure(this.width, this.height, effectiveRefreshHz)
        scheduleIdleLocked()
    }

    fun attachSurface(surface: Surface): Boolean = synchronized(lock) {
        if (!started || !zeroCopy || !surface.isValid) return@synchronized false
        val attached = nativeAttachSurface(surface)
        if (attached) {
            noteInteractiveLocked()
            return@synchronized true
        }

        // Native can downgrade this run if the public SurfaceControl path exists
        // but this concrete Surface/AHB combination cannot host it.
        zeroCopy = nativeZeroCopyAvailable()
        if (!zeroCopy) {
            VesselWaylandPresenter.configureSurfaceOnly(width, height)
        }
        false
    }

    fun detachSurface(): Boolean = synchronized(lock) {
        if (!started || !zeroCopy) return@synchronized false
        nativeDetachSurface()
        true
    }

    fun usesZeroCopyPresentation(): Boolean = started && zeroCopy

    fun presentationPath(): String = when {
        !started -> "stopped"
        zeroCopy -> "SurfaceControl/AHardwareBuffer zero-copy"
        else -> "EGL AHardwareBuffer GPU-blit fallback"
    }

    fun effectiveRefresh(): Float =
        if (!started) 0f else runCatching { nativeEffectiveRefresh() }.getOrDefault(effectiveRefreshHz)

    fun framesPresented(): Long =
        if (!started) 0L else runCatching { nativeFramesPresented() }.getOrDefault(0L)

    fun framesReleased(): Long =
        if (!started) 0L else runCatching { nativeFramesReleased() }.getOrDefault(0L)

    fun status(): String =
        if (!started) "proroot-display-stopped"
        else runCatching { nativeStatus() }.getOrElse { "proroot-display-error:" + it.message }

    private fun noteInteractive() = synchronized(lock) {
        if (started) noteInteractiveLocked()
    }

    private fun noteInteractiveLocked() {
        lastInteractionUptimeMs = SystemClock.uptimeMillis()
        applyRefreshLocked(VesselPerformancePolicy.activeRefresh(maxRefreshHz))
        scheduleIdleLocked()
    }

    private fun scheduleIdleLocked() {
        if (idleCheckScheduled) return
        idleCheckScheduled = true
        mainHandler.postDelayed(
            idleRefreshRunnable,
            VesselPerformancePolicy.IDLE_DELAY_MS,
        )
    }

    private fun applyRefreshLocked(refresh: Float) {
        val target = refresh.coerceIn(30f, 240f)
        if (abs(target - effectiveRefreshHz) < 0.1f) return
        effectiveRefreshHz = target
        nativeSetEffectiveRefresh(target)
        if (!zeroCopy) {
            LinuxDesktopView.active?.setProrootFallbackFrameRate(target)
        }
    }

    fun absoluteNormalized(x: Float, y: Float, down: Boolean): Boolean {
        if (!started) return false
        noteInteractive()
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
        noteInteractive()
        pointerX = (pointerX + dx).coerceIn(0f, max(1, width).toFloat())
        pointerY = (pointerY + dy).coerceIn(0f, max(1, height).toFloat())
        return nativePointer(pointerX, pointerY, dx, dy)
    }

    fun button(code: Int, down: Boolean): Boolean {
        if (!started) return false
        noteInteractive()
        return nativeButton(code, down)
    }

    fun key(code: Int, down: Boolean): Boolean {
        if (!started) return false
        noteInteractive()
        return nativeKey(code, down)
    }

    fun scroll(x: Float, y: Float): Boolean {
        if (!started) return false
        noteInteractive()
        var ok = true
        if (y != 0f) ok = nativeScroll(0, y) && ok
        if (x != 0f) ok = nativeScroll(1, x) && ok
        return ok
    }

    fun text(value: String): Boolean {
        if (value.isEmpty()) return true
        if (!started) return false
        noteInteractive()
        return nativeText(value.toByteArray(StandardCharsets.UTF_8))
    }

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
