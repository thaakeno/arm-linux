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
    private lateinit var controlsCard: LinearLayout
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
                    if (status.startsWith("ERROR:")) 0xDB3B171A.toInt() else 0xB80A0E0D.toInt(),
                    14f,
                    0x354FD1B5
                )
                val gesture = if (interactMode) "Drag objects · release to throw" else "Orbit · pinch zoom"
                stats.text = "$status\n$gesture"
                absorbNativeLog(logText)
                val now = System.currentTimeMillis()
                if (now - lastStatusSnapshotMs > 1_000L && status != lastStatusSnapshot) {
                    lastStatusSnapshot = status
                    lastStatusSnapshotMs = now
                    appendLog("PERF  ${status.lineSequence().firstOrNull().orEmpty()}")
                }
                logs.text = logHistory.takeLast(16).joinToString("\n")
                val merged = "$status\n${logHistory.joinToString("\n")}".takeLast(120_000)
                if (merged != lastPublishedNativeLog) {
                    lastPublishedNativeLog = merged
                    val s = VmSessionService.state.value
                    val marker = "[Native Vulkan Studio]"
                    val old = s.console.substringBeforeLast(marker).trimEnd()
                    VmSessionService.state.value = s.copy(
                        console = (old + "\n\n$marker\n" + merged).takeLast(200_000)
                    )
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
            textSize = 10.4f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Vessel Vulkan Studio v5\nWaiting for Android Surface…"
        }
        logs = TextView(this).apply {
            setTextColor(0xFFE8F0EC.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 8.6f
            setPadding(dp(11), dp(8), dp(11), dp(9))
            setTextIsSelectable(true)
            text = "renderer not started"
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
                    if (!interactMode && rendererHandle != 0L) nativeZoom(rendererHandle, detector.scaleFactor)
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
                            nativeRotate(
                                handle,
                                -(event.x - lastX) * (145f / max(surfaceView.width, 1)),
                                -(event.y - lastY) * (105f / max(surfaceView.height, 1))
                            )
                        }
                        lastX = event.x; lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    lastX = event.getX(0); lastY = event.getY(0); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

        controlsCard = buildControls().apply { visibility = View.GONE }
        logCard = buildLogCard().apply { visibility = View.GONE }
        val quickBar = buildQuickBar()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10); topMargin = dp(9) })
        root.addView(quickBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(9); rightMargin = dp(10) })
        root.addView(controlsCard, FrameLayout.LayoutParams(dp(270), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(52); rightMargin = dp(10) })
        root.addView(logCard, FrameLayout.LayoutParams(dp(360), dp(176), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(10); bottomMargin = dp(10) })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun buildQuickBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(0xA8080C0B.toInt(), 14f, 0x284FD1B5)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(hudButton("Tune") { controlsCard.visibility = if (controlsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        addView(hudButton("Logs") { logCard.visibility = if (logCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
    }

    private fun hudButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 9.4f; setTextColor(Color.WHITE)
        minHeight = 0; minWidth = 0; setPadding(dp(10), dp(5), dp(10), dp(5))
        background = rounded(0xB4141B18.toInt(), 11f, 0x204FD1B5)
        setOnClickListener { click() }
    }

    private fun buildControls(): LinearLayout {
        fun button(label: String, accent: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
            text = label; textSize = 9.2f; isAllCaps = false; setTextColor(Color.WHITE)
            minHeight = 0; minWidth = 0; setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(if (accent) 0xD51A6D5E.toInt() else 0xC8141B18.toInt(), 11f, if (accent) 0x705DE0BE else 0x25485A54)
            setOnClickListener { click(this) }
        }
        fun section(text: String) = TextView(this).apply {
            this.text = text; textSize = 8.2f; setTextColor(0xFF8FA39B.toInt()); setPadding(0, dp(4), 0, dp(3)); letterSpacing = 0.08f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(10))
            background = rounded(0xD70A0E0D.toInt(), 16f, 0x354FD1B5)
            addView(TextView(this@NativeCubeActivity).apply {
                text = "VULKAN STUDIO v5"; textSize = 11.6f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(section("GRAPHICS"))
            qualityLabel = TextView(this@NativeCubeActivity).apply { setTextColor(0xFFEAF7F1.toInt()); textSize = 9.6f; text = "Quality $quality / 100" }
            addView(qualityLabel)
            addView(SeekBar(this@NativeCubeActivity).apply {
                max = 100; progress = quality
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        quality = progress; qualityLabel.text = "Quality $quality / 100"; if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
            val presets = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            presets.addView(button("Low") { setQualityPreset(15) }); presets.addView(button("120Hz") { setQualityPreset(62) }); presets.addView(button("Ultra", true) { setQualityPreset(100) }); addView(presets)
            addView(section("RENDER"))
            val row1 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            modeButton = button("Quality") { b -> stressMode = !stressMode; b.text = if (stressMode) "Stress" else "Quality"; if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode) }
            physicsButton = button("Physics on") { b -> physicsEnabled = !physicsEnabled; b.text = if (physicsEnabled) "Physics on" else "Physics off"; if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled) }
            row1.addView(modeButton); row1.addView(physicsButton); addView(row1)
            val row2 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            rtButton = button("RT on", true) { b -> rtEnabled = !rtEnabled; b.text = if (rtEnabled) "RT on" else "RT off"; if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled) }
            interactButton = button("Orbit") { b -> interactMode = !interactMode; b.text = if (interactMode) "Interact" else "Orbit"; updateInteractionHint(); if (!interactMode && rendererHandle != 0L) nativeGrabEnd(rendererHandle) }
            row2.addView(rtButton); row2.addView(interactButton); addView(row2)
            addView(section("INTERACTION"))
            interactionHint = TextView(this@NativeCubeActivity).apply { textSize = 8.2f; setTextColor(0xFFA7B7B0.toInt()); setPadding(dp(2), 0, dp(2), dp(4)) }
            addView(interactionHint); updateInteractionHint()
            val row3 = LinearLayout(this@NativeCubeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row3.addView(button("Move light", true) { interactMode = true; interactButton.text = "Interact"; updateInteractionHint(true) })
            row3.addView(button("Reset") { if (rendererHandle != 0L) nativeResetPhysics(rendererHandle) }); addView(row3)
        }
    }

    private fun buildLogCard(): LinearLayout {
        val header = TextView(this).apply { text = "VULKAN LOG · retained"; textSize = 8.7f; setTextColor(0xFF9FB2AA.toInt()); setPadding(dp(11), dp(7), dp(8), dp(3)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(logs, FrameLayout.LayoutParams(-1, -2)) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(3), dp(6), dp(6))
            addView(hudButton("Copy") {
                val text = buildString { append(stats.text); append("\n\nVULKAN LOG\n"); append(logHistory.joinToString("\n")) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vessel Vulkan Studio logs", text))
                Toast.makeText(this@NativeCubeActivity, "Full Vulkan log copied", Toast.LENGTH_SHORT).show()
            })
            addView(hudButton("Clear") { logHistory.clear(); appendLog("log history cleared"); logs.text = logHistory.joinToString("\n") })
            addView(hudButton("Close") { logCard.visibility = View.GONE })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(0xD5080C0B.toInt(), 14f, 0x304FD1B5)
            addView(header); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); addView(actions)
        }
    }

    private fun setQualityPreset(value: Int) {
        quality = value.coerceIn(0, 100); qualityLabel.text = "Quality $quality / 100"
        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
        appendLog("preset -> Q$quality")
    }

    private fun updateInteractionHint(lightFocus: Boolean = false) {
        if (!::interactionHint.isInitialized) return
        interactionHint.text = when {
            lightFocus -> "Drag the warm light orb. Pause physics to park it."
            interactMode -> "Touch a body, drag, release to throw."
            else -> "Orbit mode. Interact grabs objects."
        }
    }

    private fun absorbNativeLog(text: String) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList(); if (lines.isEmpty()) return
        val tail = lines.last(); if (tail == lastNativeTail && lines.size == 1) return
        var start = 0
        if (lastNativeTail.isNotEmpty()) { val idx = lines.indexOfLast { it == lastNativeTail }; if (idx >= 0) start = idx + 1 }
        for (i in start until lines.size) appendLog(lines[i]); lastNativeTail = tail
    }

    private fun appendLog(line: String) {
        if (line.isBlank() || logHistory.lastOrNull() == line) return
        logHistory.addLast(line); while (logHistory.size > 300) logHistory.removeFirst()
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radiusDp.toInt()).toFloat(); if (strokeColor != null) setStroke(dp(1), strokeColor) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enterImmersiveMode() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); destroyRenderer(); super.onDestroy() }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun destroyRenderer() {
        val h = rendererHandle; rendererHandle = 0L
        if (h != 0L && nativeLoaded) runCatching { nativeDestroy(h) }
    }

    private fun showFatal(message: String) {
        runOnUiThread { stats.background = rounded(0xE63B171A.toInt(), 14f, 0x99FF7D73.toInt()); stats.text = "Native Vulkan error\n$message\nPress Back to return to Vessel" }
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