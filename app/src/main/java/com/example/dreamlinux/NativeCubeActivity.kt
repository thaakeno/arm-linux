package com.example.dreamlinux

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Vulkan-only native Android Surface benchmark for Vessel.
 *
 * Rendering is performed by native Vulkan through VK_KHR_android_surface directly into the
 * SurfaceView's Android Surface. There is intentionally no OpenGL/OpenGL ES fallback here.
 */
class NativeCubeActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var surfaceView: SurfaceView
    private val handler = Handler(Looper.getMainLooper())
    private var rendererHandle = 0L
    private var nativeLoaded = false
    private var lastX = 0f
    private var lastY = 0f

    private val statsPoll = object : Runnable {
        override fun run() {
            val handle = rendererHandle
            if (handle != 0L && nativeLoaded) {
                val text = runCatching { nativeStatus(handle) }
                    .getOrElse { "ERROR: Vulkan status: ${it.message ?: it.javaClass.simpleName}" }
                if (text.startsWith("ERROR:")) {
                    stats.setBackgroundColor(0xCC3B171A.toInt())
                } else {
                    stats.setBackgroundColor(0xAA050807.toInt())
                }
                stats.text = "$text\nDrag to rotate · Pinch to zoom"
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC050807.toInt())
            textSize = 13f
            setPadding(28, 18, 28, 18)
            text = "Vessel Native Vulkan\nWaiting for Android Surface…\nDrag to rotate · Pinch to zoom"
        }

        nativeLoaded = try {
            System.loadLibrary("vessel_vulkan")
            true
        } catch (t: Throwable) {
            showFatal("Could not load Vulkan native library: ${t.message ?: t.javaClass.simpleName}")
            false
        }

        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val handle = rendererHandle
                if (handle != 0L) nativeZoom(handle, detector.scaleFactor)
                return true
            }
        })

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
                        if (handle != 0L) nativeRotate(handle, dx * 0.32f, dy * 0.32f)
                        lastX = event.x
                        lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
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
                    stats.text = "Vessel Native Vulkan\nCreating VkInstance + Android swapchain…"
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val handle = rendererHandle
                if (handle != 0L) runCatching { nativeResize(handle) }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                destroyRenderer()
            }
        })

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
                topMargin = 28
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun destroyRenderer() {
        val handle = rendererHandle
        rendererHandle = 0L
        if (handle != 0L && nativeLoaded) {
            runCatching { nativeDestroy(handle) }
        }
    }

    private fun showFatal(message: String) {
        runOnUiThread {
            stats.setBackgroundColor(0xCC3B171A.toInt())
            stats.text = "Native Vulkan error\n$message\nPress Back to return to Vessel"
        }
    }

    private external fun nativeCreate(surface: Surface): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResize(handle: Long)
    private external fun nativeRotate(handle: Long, dxDegrees: Float, dyDegrees: Float)
    private external fun nativeZoom(handle: Long, scaleFactor: Float)
    private external fun nativeStatus(handle: Long): String
}
