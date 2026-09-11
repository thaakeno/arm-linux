package com.example.dreamlinux

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Persistent Vulkan-only graphics diagnostic for Vessel.
 *
 * This benchmark intentionally stays in the finished app even after the Linux desktop transport
 * is native. It exercises the same Android Surface + Vulkan + Adreno presentation path directly,
 * with no OpenGL, VNC or Termux:X11 fallback.
 */
class NativeCubeActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var logs: TextView
    private lateinit var modeChip: TextView
    private lateinit var surfaceView: SurfaceView
    private val handler = Handler(Looper.getMainLooper())
    private var rendererHandle = 0L
    private var nativeLoaded = false
    private var lastX = 0f
    private var lastY = 0f
    private var stressMode = false

    private val statsPoll = object : Runnable {
        override fun run() {
            val handle = rendererHandle
            if (handle != 0L && nativeLoaded) {
                val text = runCatching { nativeStatus(handle) }
                    .getOrElse { "ERROR: Vulkan status: ${it.message ?: it.javaClass.simpleName}" }
                stats.setBackgroundColor(
                    if (text.startsWith("ERROR:")) 0xCC3B171A.toInt() else 0xB3050807.toInt()
                )
                stats.text = "$text\nDrag to orbit · Pinch to zoom"

                val logText = runCatching { nativeLogs(handle) }
                    .getOrElse { "log read failed: ${it.message ?: it.javaClass.simpleName}" }
                logs.text = "LIVE VULKAN LOG\n$logText"
            }
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC050807.toInt())
            textSize = 12.5f
            setPadding(24, 14, 24, 14)
            text = "Vessel Vulkan Studio\nWaiting for Android Surface…\nDrag to orbit · Pinch to zoom"
        }

        logs = TextView(this).apply {
            setTextColor(0xFFD7E0DB.toInt())
            setBackgroundColor(0xA8050807.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setPadding(18, 12, 18, 12)
            maxLines = 11
            text = "LIVE VULKAN LOG\nrenderer not started"
        }

        modeChip = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC18362B.toInt())
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(24, 14, 24, 14)
            text = "QUALITY"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                stressMode = !stressMode
                text = if (stressMode) "STRESS" else "QUALITY"
                setBackgroundColor(
                    if (stressMode) 0xCCD35B34.toInt() else 0xCC18362B.toInt()
                )
                val handle = rendererHandle
                if (handle != 0L) runCatching { nativeSetStress(handle, stressMode) }
            }
        }

        nativeLoaded = try {
            System.loadLibrary("vessel_vulkan")
            true
        } catch (t: Throwable) {
            showFatal("Could not load Vulkan native library: ${t.message ?: t.javaClass.simpleName}")
            false
        }

        surfaceView = SurfaceView(this).apply {
            background = null
            holder.setFormat(PixelFormat.OPAQUE)
        }

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val handle = rendererHandle
                    if (handle != 0L) nativeZoom(handle, detector.scaleFactor)
                    return true
                }
            }
        )

        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val handle = rendererHandle
                        if (handle != 0L) {
                            val yawDegrees = -dx * (145f / max(surfaceView.width, 1))
                            val pitchDegrees = -dy * (105f / max(surfaceView.height, 1))
                            nativeRotate(handle, yawDegrees, pitchDegrees)
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!nativeLoaded || rendererHandle != 0L) return
                    val surface: Surface = holder.surface
                    rendererHandle = runCatching { nativeCreate(surface) }.getOrElse {
                        showFatal("Vulkan renderer creation failed: ${it.message ?: it.javaClass.simpleName}")
                        0L
                    }
                    if (rendererHandle == 0L) {
                        showFatal("Vulkan renderer creation returned no native handle")
                    } else {
                        nativeResize(
                            rendererHandle,
                            max(surfaceView.width, 1),
                            max(surfaceView.height, 1)
                        )
                        nativeSetStress(rendererHandle, stressMode)
                        stats.text = "Vessel Vulkan Studio\nCreating Vulkan scene…"
                    }
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    val handle = rendererHandle
                    if (handle != 0L) {
                        runCatching { nativeResize(handle, max(width, 1), max(height, 1)) }
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    destroyRenderer()
                }
            }
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            stats,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 28
                topMargin = 22
            }
        )
        root.addView(
            modeChip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 22
                rightMargin = 28
            }
        )
        root.addView(
            logs,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = 28
                bottomMargin = 26
            }
        )

        setContentView(root)
        handler.post(statsPoll)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        destroyRenderer()
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun destroyRenderer() {
        val handle = rendererHandle
        rendererHandle = 0L
        if (handle != 0L && nativeLoaded) runCatching { nativeDestroy(handle) }
    }

    private fun showFatal(message: String) {
        runOnUiThread {
            stats.setBackgroundColor(0xCC3B171A.toInt())
            stats.text = "Native Vulkan error\n$message\nPress Back to return to Vessel"
            logs.text = "LIVE VULKAN LOG\n$message"
        }
    }

    private external fun nativeCreate(surface: Surface): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeRotate(handle: Long, dxDegrees: Float, dyDegrees: Float)
    private external fun nativeZoom(handle: Long, scaleFactor: Float)
    private external fun nativeSetStress(handle: Long, enabled: Boolean)
    private external fun nativeStatus(handle: Long): String
    private external fun nativeLogs(handle: Long): String
}
