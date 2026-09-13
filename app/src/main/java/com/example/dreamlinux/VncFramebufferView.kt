package com.example.dreamlinux

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.termux.x11.MainActivity

/**
 * Compatibility host for the Compose desktop page.
 *
 * There is deliberately no framebuffer, RFB client, screenshot stream, fake
 * cursor, or Android-side pixel copier here anymore. The real desktop is owned
 * by the embedded Lorie X server activity, which renders directly to Android's
 * Surface/BufferQueue path.
 */
class VncFramebufferView(context: Context) : FrameLayout(context) {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: VncFramebufferView? = null
    }

    private var autoOpened = false

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        addView(TextView(context).apply {
            text = "Native X11 desktop\nTap to reopen"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { openNativeDesktop() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
        if (!autoOpened) {
            autoOpened = true
            post { openNativeDesktop() }
        }
    }

    override fun onDetachedFromWindow() {
        if (active === this) active = null
        super.onDetachedFromWindow()
    }

    private fun openNativeDesktop() {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    fun setPointerMode(@Suppress("UNUSED_PARAMETER") mode: PointerMode) = Unit
    fun showKeyboard() = openNativeDesktop()
    fun tapKey(@Suppress("UNUSED_PARAMETER") keysym: Int) = openNativeDesktop()
}
