package com.example.dreamlinux

import android.content.Context
import android.graphics.Color
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * Compatibility class name for the Compose desktop page.
 *
 * This is no longer a VNC/framebuffer/X11 view. It owns a real Android Surface
 * consumed by VesselWaylandPresenter, which imports KWin/Venus dma-bufs into
 * Vulkan/AHardwareBuffer and presents them through SurfaceFlinger.
 */
class VncFramebufferView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: VncFramebufferView? = null
    }

    private val surfaceView = SurfaceView(context).apply {
        setBackgroundColor(Color.BLACK)
        holder.addCallback(this@VncFramebufferView)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
    }

    override fun onDetachedFromWindow() {
        if (active === this) active = null
        if (surfaceView.holder.surface.isValid) VesselWaylandPresenter.detach()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        VesselWaylandPresenter.attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        VesselWaylandPresenter.attach(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VesselWaylandPresenter.detach()
    }

    fun setPointerMode(@Suppress("UNUSED_PARAMETER") mode: PointerMode) = Unit
    fun showKeyboard() = surfaceView.requestFocus()
    fun tapKey(@Suppress("UNUSED_PARAMETER") keysym: Int) = Unit
}
