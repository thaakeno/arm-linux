package com.example.dreamlinux

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/** Persistent native Vulkan diagnostic kept in the finished Vessel app. */
class NativeCubeActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var logs: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var qualityLabel: TextView
    private lateinit var modeButton: Button
    private lateinit var physicsButton: Button
    private lateinit var rtButton: Button
    private lateinit var interactButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var rendererHandle = 0L
    private var nativeLoaded = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var stressMode = false
    private var physicsEnabled = true
    private var rtEnabled = true
    private var interactMode = false
    private var quality = 72
    private var lastPublishedNativeLog = ""

    private val statsPoll = object : Runnable {
        override fun run() {
            val handle = rendererHandle
            if (handle != 0L && nativeLoaded) {
                val status = runCatching { nativeStatus(handle) }
                    .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                val logText = runCatching { nativeLogs(handle) }
                    .getOrElse { "log read failed: ${it.message ?: it.javaClass.simpleName}" }

                stats.setBackgroundColor(
                    if (status.startsWith("ERROR:")) 0xD63B171A.toInt() else 0xB3050807.toInt()
                )
                stats.text = "$status\n${if (interactMode) "Drag objects · release to throw" else "Drag to orbit · pinch to zoom"}"
                logs.text = "LIVE VULKAN LOG\n$logText"

                // Mirror native renderer output into Vessel's normal runtime console so the
                // main app's Live runtime card is no longer blank after a Studio session.
                val merged = "$status\n$logText"
                if (merged != lastPublishedNativeLog) {
                    lastPublishedNativeLog = merged
                    val s = VmSessionService.state.value
                    val marker = "[Native Vulkan Studio]"
                    val old = s.console.substringBeforeLast(marker).trimEnd()
                    val combined = (old + "\n\n$marker\n" + merged).takeLast(200_000)
                    VmSessionService.state.value = s.copy(console = combined)
                }
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
            text = "Vessel Vulkan Studio v3\nWaiting for Android Surface…"
        }

        logs = TextView(this).apply {
            setTextColor(0xFFE1E8E4.toInt())
            setBackgroundColor(0xB20A0E0C.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setPadding(18, 12, 18, 12)
            maxLines = 13
            setTextIsSelectable(true)
            text = "LIVE VULKAN LOG\nrenderer not started"
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
                    if (!interactMode) {
                        val handle = rendererHandle
                        if (handle != 0L) nativeZoom(handle, detector.scaleFactor)
                    }
                    return true
                }
            }
        )

        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            val handle = rendererHandle
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downX = event.x; downY = event.y
                    if (interactMode && handle != 0L) {
                        nativeGrabStart(handle, event.x / max(surfaceView.width, 1), event.y / max(surfaceView.height, 1))
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && handle != 0L) {
                        if (interactMode) {
                            nativeGrabMove(handle, event.x / max(surfaceView.width, 1), event.y / max(surfaceView.height, 1))
                        } else {
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            nativeRotate(
                                handle,
                                -dx * (145f / max(surfaceView.width, 1)),
                                -dy * (105f / max(surfaceView.height, 1))
                            )
                        }
                        lastX = event.x; lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    lastX = event.getX(0); lastY = event.getY(0); true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (interactMode && handle != 0L) nativeGrabEnd(handle)
                    true
                }
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
                    nativeResize(rendererHandle, max(surfaceView.width, 1), max(surfaceView.height, 1))
                    nativeSetQuality(rendererHandle, quality)
                    nativeSetStress(rendererHandle, stressMode)
                    nativeSetPhysics(rendererHandle, physicsEnabled)
                    nativeSetRt(rendererHandle, rtEnabled)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (rendererHandle != 0L) nativeResize(rendererHandle, max(width, 1), max(height, 1))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { destroyRenderer() }
        })

        val controls = buildControls()
        val logBar = buildLogBar()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = 24; topMargin = 20 })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = 18; rightMargin = 22 })
        root.addView(logs, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = 22; bottomMargin = 74 })
        root.addView(logBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = 22; bottomMargin = 20 })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun buildControls(): LinearLayout {
        fun button(label: String, click: (Button) -> Unit) = Button(this).apply {
            text = label
            textSize = 10.5f
            setTextColor(Color.WHITE)
            minHeight = 0; minWidth = 0
            setPadding(18, 8, 18, 8)
            setBackgroundColor(0xD51B3029.toInt())
            setOnClickListener { click(this) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xA8070B09.toInt())

            qualityLabel = TextView(this@NativeCubeActivity).apply {
                setTextColor(Color.WHITE); textSize = 11f; text = "GRAPHICS  $quality / 100"
            }
            addView(qualityLabel)
            addView(SeekBar(this@NativeCubeActivity).apply {
                max = 100; progress = quality; minimumWidth = 320
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        quality = progress
                        qualityLabel.text = "GRAPHICS  $quality / 100"
                        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })

            val row1 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            modeButton = button("QUALITY") { b ->
                stressMode = !stressMode
                b.text = if (stressMode) "STRESS" else "QUALITY"
                b.setBackgroundColor(if (stressMode) 0xFFD05B35.toInt() else 0xD51B3029.toInt())
                if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode)
            }
            physicsButton = button("PHYSICS ON") { b ->
                physicsEnabled = !physicsEnabled
                b.text = if (physicsEnabled) "PHYSICS ON" else "PHYSICS OFF"
                if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled)
            }
            row1.addView(modeButton); row1.addView(physicsButton); addView(row1)

            val row2 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            rtButton = button("RT ON") { b ->
                rtEnabled = !rtEnabled
                b.text = if (rtEnabled) "RT ON" else "RT OFF"
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            interactButton = button("ORBIT") { b ->
                interactMode = !interactMode
                b.text = if (interactMode) "INTERACT" else "ORBIT"
                if (!interactMode && rendererHandle != 0L) nativeGrabEnd(rendererHandle)
            }
            row2.addView(rtButton); row2.addView(interactButton); addView(row2)

            addView(button("RESET PHYSICS") {
                if (rendererHandle != 0L) nativeResetPhysics(rendererHandle)
            })
        }
    }

    private fun buildLogBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(this@NativeCubeActivity).apply {
            text = "COPY LOGS"; textSize = 10f
            setOnClickListener {
                val text = "${stats.text}\n\n${logs.text}"
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Vessel Vulkan Studio logs", text))
                Toast.makeText(this@NativeCubeActivity, "Vulkan logs copied", Toast.LENGTH_SHORT).show()
            }
        })
        addView(Button(this@NativeCubeActivity).apply {
            text = "HIDE LOG"; textSize = 10f
            setOnClickListener {
                logs.visibility = if (logs.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (logs.visibility == View.VISIBLE) "HIDE LOG" else "SHOW LOG"
            }
        })
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
        val h = rendererHandle
        rendererHandle = 0L
        if (h != 0L && nativeLoaded) runCatching { nativeDestroy(h) }
    }

    private fun showFatal(message: String) {
        runOnUiThread {
            stats.setBackgroundColor(0xDD3B171A.toInt())
            stats.text = "Native Vulkan error\n$message\nPress Back to return to Vessel"
        }
    }

    private external fun nativeCreate(surface: Surface): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeRotate(handle: Long, yawDegrees: Float, pitchDegrees: Float)
    private external fun nativeZoom(handle: Long, scaleFactor: Float)
    private external fun nativeSetStress(handle: Long, enabled: Boolean)
    private external fun nativeSetPhysics(handle: Long, enabled: Boolean)
    private external fun nativeSetRt(handle: Long, enabled: Boolean)
    private external fun nativeSetQuality(handle: Long, quality: Int)
    private external fun nativeResetPhysics(handle: Long)
    private external fun nativeGrabStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabMove(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabEnd(handle: Long)
    private external fun nativeStatus(handle: Long): String
    private external fun nativeLogs(handle: Long): String
}
