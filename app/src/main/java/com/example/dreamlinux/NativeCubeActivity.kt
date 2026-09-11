package com.example.dreamlinux

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

/** Persistent native Vulkan diagnostic kept in the finished Vessel app. */
class NativeCubeActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var logs: TextView
    private lateinit var logCard: LinearLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var qualityLabel: TextView
    private lateinit var modeButton: Button
    private lateinit var physicsButton: Button
    private lateinit var rtButton: Button
    private lateinit var interactButton: Button
    private lateinit var interactionHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var rendererHandle = 0L
    private var nativeLoaded = false
    private var lastX = 0f
    private var lastY = 0f
    private var stressMode = false
    private var physicsEnabled = true
    private var rtEnabled = true
    private var interactMode = false
    private var quality = 72
    private var lastPublishedNativeLog = ""
    private val logHistory = ArrayDeque<String>()
    private var lastNativeTail = ""
    private var lastStatusSnapshot = ""
    private var lastStatusSnapshotMs = 0L

    private val statsPoll = object : Runnable {
        override fun run() {
            val handle = rendererHandle
            if (handle != 0L && nativeLoaded) {
                val status = runCatching { nativeStatus(handle) }
                    .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                val logText = runCatching { nativeLogs(handle) }
                    .getOrElse { "log read failed: ${it.message ?: it.javaClass.simpleName}" }

                stats.background = rounded(
                    if (status.startsWith("ERROR:")) 0xE63B171A.toInt() else 0xD40A0E0D.toInt(),
                    18f,
                    0x334FD1B5
                )
                val gesture = if (interactMode) {
                    "Drag objects or the warm light orb · release to throw"
                } else {
                    "Drag to orbit · pinch to zoom"
                }
                stats.text = "$status\n$gesture"

                absorbNativeLog(logText)
                val now = System.currentTimeMillis()
                if (now - lastStatusSnapshotMs > 1_000L && status != lastStatusSnapshot) {
                    lastStatusSnapshot = status
                    lastStatusSnapshotMs = now
                    appendLog("PERF  ${status.lineSequence().firstOrNull().orEmpty()}")
                }
                logs.text = logHistory.takeLast(24).joinToString("\n")

                val merged = "$status\n${logHistory.joinToString("\n")}".takeLast(120_000)
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
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 12.5f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            text = "Vessel Vulkan Studio\nWaiting for Android Surface…"
        }

        logs = TextView(this).apply {
            setTextColor(0xFFE8F0EC.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 9.3f
            setPadding(dp(14), dp(10), dp(14), dp(12))
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
                    lastX = event.x
                    lastY = event.y
                    if (interactMode && handle != 0L) {
                        nativeGrabStart(
                            handle,
                            event.x / max(surfaceView.width, 1),
                            event.y / max(surfaceView.height, 1)
                        )
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && handle != 0L) {
                        if (interactMode) {
                            nativeGrabMove(
                                handle,
                                event.x / max(surfaceView.width, 1),
                                event.y / max(surfaceView.height, 1)
                            )
                        } else {
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            nativeRotate(
                                handle,
                                -dx * (145f / max(surfaceView.width, 1)),
                                -dy * (105f / max(surfaceView.height, 1))
                            )
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
            override fun surfaceDestroyed(holder: SurfaceHolder) = destroyRenderer()
        })

        val controls = buildControls()
        logCard = buildLogCard()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(16)
            topMargin = dp(14)
        })
        root.addView(controls, FrameLayout.LayoutParams(dp(338), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14)
            rightMargin = dp(16)
        })
        root.addView(logCard, FrameLayout.LayoutParams(dp(430), dp(230), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16)
            bottomMargin = dp(16)
        })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun buildControls(): LinearLayout {
        fun button(label: String, accent: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
            text = label
            textSize = 10.2f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = 0
            minWidth = 0
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(
                if (accent) 0xE01A6D5E.toInt() else 0xD51A211F.toInt(),
                14f,
                if (accent) 0x885DE0BE.toInt() else 0x33485A54
            )
            setOnClickListener { click(this) }
        }

        fun section(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9.5f
            setTextColor(0xFF8FA39B.toInt())
            setPadding(0, dp(6), 0, dp(5))
            letterSpacing = 0.08f
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = rounded(0xD70A0E0D.toInt(), 20f, 0x3D4FD1B5)

            addView(TextView(this@NativeCubeActivity).apply {
                text = "VULKAN STUDIO"
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@NativeCubeActivity).apply {
                text = "Hybrid RT · physics · material lab"
                textSize = 9.5f
                setTextColor(0xFFA7B7B0.toInt())
                setPadding(0, 0, 0, dp(8))
            })

            addView(section("GRAPHICS"))
            qualityLabel = TextView(this@NativeCubeActivity).apply {
                setTextColor(0xFFEAF7F1.toInt())
                textSize = 11.5f
                text = "Quality  $quality / 100"
            }
            addView(qualityLabel)
            addView(SeekBar(this@NativeCubeActivity).apply {
                max = 100
                progress = quality
                minimumWidth = dp(300)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        quality = progress
                        qualityLabel.text = "Quality  $quality / 100"
                        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })

            val presetRow = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            presetRow.addView(button("Low") { setQualityPreset(15) })
            presetRow.addView(button("Realtime") { setQualityPreset(62) })
            presetRow.addView(button("Ultra RT", accent = true) { setQualityPreset(100) })
            addView(presetRow)

            addView(section("RENDER + PHYSICS"))
            val row1 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            modeButton = button("Quality") { b ->
                stressMode = !stressMode
                b.text = if (stressMode) "Stress" else "Quality"
                b.background = rounded(
                    if (stressMode) 0xE0B35332.toInt() else 0xD51A211F.toInt(),
                    14f,
                    if (stressMode) 0x99FF9A68.toInt() else 0x33485A54
                )
                if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode)
            }
            physicsButton = button("Physics on") { b ->
                physicsEnabled = !physicsEnabled
                b.text = if (physicsEnabled) "Physics on" else "Physics off"
                if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled)
            }
            row1.addView(modeButton)
            row1.addView(physicsButton)
            addView(row1)

            val row2 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            rtButton = button("RT on", accent = true) { b ->
                rtEnabled = !rtEnabled
                b.text = if (rtEnabled) "RT on" else "RT off"
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            interactButton = button("Orbit") { b ->
                interactMode = !interactMode
                b.text = if (interactMode) "Interact" else "Orbit"
                updateInteractionHint()
                if (!interactMode && rendererHandle != 0L) nativeGrabEnd(rendererHandle)
            }
            row2.addView(rtButton)
            row2.addView(interactButton)
            addView(row2)

            addView(section("INTERACTION"))
            interactionHint = TextView(this@NativeCubeActivity).apply {
                textSize = 9.5f
                setTextColor(0xFFA7B7B0.toInt())
                setPadding(dp(3), 0, dp(3), dp(7))
            }
            addView(interactionHint)
            updateInteractionHint()

            val row3 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row3.addView(button("Move light", accent = true) {
                interactMode = true
                interactButton.text = "Interact"
                updateInteractionHint(lightFocus = true)
                Toast.makeText(
                    this@NativeCubeActivity,
                    "Drag the warm glowing sphere. Turn Physics off to park it in the air.",
                    Toast.LENGTH_LONG
                ).show()
            })
            row3.addView(button("Reset scene") {
                if (rendererHandle != 0L) nativeResetPhysics(rendererHandle)
            })
            addView(row3)
        }
    }

    private fun buildLogCard(): LinearLayout {
        val header = TextView(this).apply {
            text = "LIVE VULKAN LOG  ·  retained history"
            textSize = 10f
            setTextColor(0xFF9FB2AA.toInt())
            setPadding(dp(14), dp(10), dp(10), dp(4))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logs, FrameLayout.LayoutParams(-1, -2))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(Button(this@NativeCubeActivity).apply {
                text = "COPY LOGS"
                isAllCaps = false
                textSize = 9.5f
                setOnClickListener {
                    val text = buildString {
                        append(stats.text)
                        append("\n\nLIVE VULKAN LOG\n")
                        append(logHistory.joinToString("\n"))
                    }
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Vessel Vulkan Studio logs", text))
                    Toast.makeText(this@NativeCubeActivity, "Full Vulkan log history copied", Toast.LENGTH_SHORT).show()
                }
            })
            addView(Button(this@NativeCubeActivity).apply {
                text = "Clear"
                isAllCaps = false
                textSize = 9.5f
                setOnClickListener {
                    logHistory.clear()
                    appendLog("log history cleared")
                    logs.text = logHistory.joinToString("\n")
                }
            })
            addView(Button(this@NativeCubeActivity).apply {
                text = "Hide"
                isAllCaps = false
                textSize = 9.5f
                setOnClickListener {
                    logCard.visibility = View.GONE
                    Toast.makeText(this@NativeCubeActivity, "Logs hidden. Reopen the benchmark to show them again.", Toast.LENGTH_SHORT).show()
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xDE080C0B.toInt(), 18f, 0x334FD1B5)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(actions)
        }
    }

    private fun setQualityPreset(value: Int) {
        quality = value.coerceIn(0, 100)
        qualityLabel.text = "Quality  $quality / 100"
        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
        appendLog("preset -> Q$quality")
    }

    private fun updateInteractionHint(lightFocus: Boolean = false) {
        if (!::interactionHint.isInitialized) return
        interactionHint.text = when {
            lightFocus -> "Light lab: drag the warm emissive sphere to move the real ray-traced light source."
            interactMode -> "Touch a sphere to grab it. Release with motion to throw it."
            else -> "Orbit mode. Switch to Interact to grab physics objects or the light."
        }
    }

    private fun absorbNativeLog(text: String) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return
        val tail = lines.last()
        if (tail == lastNativeTail && lines.size == 1) return
        var start = 0
        if (lastNativeTail.isNotEmpty()) {
            val idx = lines.indexOfLast { it == lastNativeTail }
            if (idx >= 0) start = idx + 1
        }
        for (i in start until lines.size) appendLog(lines[i])
        lastNativeTail = tail
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        if (logHistory.lastOrNull() == line) return
        logHistory.addLast(line)
        while (logHistory.size > 240) logHistory.removeFirst()
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
            stats.background = rounded(0xE63B171A.toInt(), 18f, 0x99FF7D73.toInt())
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