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

/** Permanent native Android Vulkan benchmark and future Debian A/B reference renderer. */
class NativeCubeActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var logs: TextView
    private lateinit var logCard: LinearLayout
    private lateinit var controlsCard: LinearLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var qualityLabel: TextView
    private lateinit var qualitySeek: SeekBar
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
    private var lightFocusMode = false
    private var statsExpanded = true
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
                    if (status.startsWith("ERROR:")) 0xD9321216.toInt() else 0xA90A0D0C.toInt(),
                    12f,
                    if (status.startsWith("ERROR:")) 0x99FF776F.toInt() else 0x384ADBC0
                )
                val gesture = when {
                    lightFocusMode -> "Move light · drag anywhere · release to place/throw"
                    interactMode -> "Interact · drag objects · release to throw"
                    else -> "Orbit · pinch zoom"
                }
                val lines = status.lines()
                stats.text = if (statsExpanded) "$status\n$gesture" else lines.firstOrNull().orEmpty()
                absorbNativeLog(logText)
                val now = System.currentTimeMillis()
                if (now - lastStatusSnapshotMs > 1_000L && status != lastStatusSnapshot) {
                    lastStatusSnapshot = status
                    lastStatusSnapshotMs = now
                    appendLog("PERF  ${lines.firstOrNull().orEmpty()}")
                }
                logs.text = logHistory.takeLast(18).joinToString("\n")
                val merged = "$status\n${logHistory.joinToString("\n")}".takeLast(160_000)
                if (merged != lastPublishedNativeLog) {
                    lastPublishedNativeLog = merged
                    val s = VmSessionService.state.value
                    val marker = "[Native Vulkan Studio]"
                    val old = s.console.substringBeforeLast(marker).trimEnd()
                    VmSessionService.state.value = s.copy(
                        console = (old + "\n\n$marker\n" + merged).takeLast(220_000)
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
            textSize = 9.2f
            setLineSpacing(0f, 0.96f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            text = "Vessel Vulkan Studio v6\nLoading photoreal courtyard…"
            setOnClickListener {
                statsExpanded = !statsExpanded
                animate().scaleX(if (statsExpanded) 1.015f else .985f).scaleY(if (statsExpanded) 1.015f else .985f).setDuration(90).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            }
        }
        logs = TextView(this).apply {
            setTextColor(0xFFE8F0EC.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 7.8f
            setLineSpacing(0f, .94f)
            setPadding(dp(9), dp(6), dp(9), dp(7))
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
                    if (!interactMode && !lightFocusMode && rendererHandle != 0L) {
                        nativeZoom(rendererHandle, detector.scaleFactor)
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
                    if (handle != 0L && (interactMode || lightFocusMode)) {
                        val nx = event.x / max(surfaceView.width, 1)
                        val ny = event.y / max(surfaceView.height, 1)
                        if (lightFocusMode) nativeGrabLightStart(handle, nx, ny) else nativeGrabStart(handle, nx, ny)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && handle != 0L) {
                        if (interactMode || lightFocusMode) {
                            nativeGrabMove(handle, event.x / max(surfaceView.width, 1), event.y / max(surfaceView.height, 1))
                        } else {
                            nativeRotate(
                                handle,
                                -(event.x - lastX) * (145f / max(surfaceView.width, 1)),
                                -(event.y - lastY) * (105f / max(surfaceView.height, 1))
                            )
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if ((interactMode || lightFocusMode) && handle != 0L) nativeGrabEnd(handle)
                    true
                }
                else -> true
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!nativeLoaded || rendererHandle != 0L) return
                rendererHandle = runCatching { nativeCreate(holder.surface) }.getOrElse {
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

        controlsCard = buildControls().apply { visibility = View.GONE; alpha = 0f; translationX = dp(16).toFloat() }
        logCard = buildLogCard().apply { visibility = View.GONE; alpha = 0f; translationY = dp(12).toFloat() }
        val quickBar = buildQuickBar()

        val panelWidth = minOf(dp(224), (resources.displayMetrics.widthPixels * .31f).toInt())
        val logWidth = minOf(dp(326), (resources.displayMetrics.widthPixels * .48f).toInt())
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7); topMargin = dp(7) })
        root.addView(quickBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(7); rightMargin = dp(7) })
        root.addView(controlsCard, FrameLayout.LayoutParams(panelWidth, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(39); rightMargin = dp(7) })
        root.addView(logCard, FrameLayout.LayoutParams(logWidth, dp(148), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(7) })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun buildQuickBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(0x93070A09.toInt(), 12f, 0x304ADBC0)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(iconButton("≡", "Tune renderer") { togglePanel(controlsCard, horizontal = true) })
        addView(iconButton("▤", "Vulkan log") { togglePanel(logCard, horizontal = false) })
    }

    private fun iconButton(icon: String, description: String, click: () -> Unit) = TextView(this).apply {
        text = icon
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(0xFFF3FFFA.toInt())
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = rounded(0x8E111714.toInt(), 9f, 0x254ADBC0)
        setOnClickListener { click() }
    }

    private fun togglePanel(view: View, horizontal: Boolean) {
        if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f)
                .translationX(if (horizontal) dp(14).toFloat() else 0f)
                .translationY(if (horizontal) 0f else dp(10).toFloat())
                .setDuration(150)
                .withEndAction { view.visibility = View.GONE }
                .start()
        } else {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationX = if (horizontal) dp(14).toFloat() else 0f
            view.translationY = if (horizontal) 0f else dp(10).toFloat()
            view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(180).start()
        }
    }

    private fun buildControls(): LinearLayout {
        fun button(label: String, accent: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
            text = label
            textSize = 8.1f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = 0
            minWidth = 0
            setPadding(dp(5), dp(3), dp(5), dp(3))
            background = rounded(if (accent) 0xC8177767.toInt() else 0xB8111714.toInt(), 9f, if (accent) 0x795DE8C7 else 0x29445650)
            setOnClickListener {
                animate().scaleX(.96f).scaleY(.96f).setDuration(55).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(85).start()
                }.start()
                click(this)
            }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(35), 1f).apply { marginEnd = dp(3) }) }
        }
        fun section(text: String) = TextView(this).apply {
            this.text = text
            textSize = 7.2f
            setTextColor(0xFF8FA79E.toInt())
            setPadding(0, dp(4), 0, dp(2))
            letterSpacing = .10f
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(8), dp(8))
            background = rounded(0xD7070B09.toInt(), 14f, 0x3B4ADBC0)
            addView(LinearLayout(this@NativeCubeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@NativeCubeActivity).apply {
                    text = "VULKAN STUDIO"
                    textSize = 10.4f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@NativeCubeActivity).apply {
                    text = "v6"
                    textSize = 7.4f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFD8F7ED.toInt())
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = rounded(0x75455F57, 7f)
                })
            })
            addView(TextView(this@NativeCubeActivity).apply {
                text = "Photoreal courtyard · HW ray queries"
                textSize = 7.2f
                setTextColor(0xFF9BA9A4.toInt())
                setPadding(0, dp(1), 0, dp(2))
            })
            addView(section("GRAPHICS"))
            qualityLabel = TextView(this@NativeCubeActivity).apply {
                setTextColor(0xFFEAF7F1.toInt())
                textSize = 8.1f
                text = "Quality  $quality / 100"
            }
            addView(qualityLabel)
            qualitySeek = SeekBar(this@NativeCubeActivity).apply {
                max = 100
                progress = quality
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        quality = progress
                        qualityLabel.text = "Quality  $quality / 100"
                        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            addView(qualitySeek, LinearLayout.LayoutParams(-1, dp(28)))
            addView(row(
                button("Low") { setQualityPreset(20) },
                button("120Hz") { setQualityPreset(68) },
                button("Ultra RT", true) { setQualityPreset(100) }
            ))
            addView(section("RENDER + PHYSICS"))
            modeButton = button("Courtyard") { b ->
                stressMode = !stressMode
                b.text = if (stressMode) "Stress" else "Courtyard"
                if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode)
            }
            physicsButton = button("Physics ON") { b ->
                physicsEnabled = !physicsEnabled
                b.text = if (physicsEnabled) "Physics ON" else "Physics OFF"
                if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled)
            }
            addView(row(modeButton, physicsButton))
            rtButton = button("RT ON", true) { b ->
                rtEnabled = !rtEnabled
                b.text = if (rtEnabled) "RT ON" else "RT OFF"
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            interactButton = button("Orbit") { b ->
                interactMode = !interactMode
                lightFocusMode = false
                b.text = if (interactMode) "Interact" else "Orbit"
                updateInteractionHint()
                if (!interactMode && rendererHandle != 0L) nativeGrabEnd(rendererHandle)
            }
            addView(row(rtButton, interactButton))
            addView(section("INTERACTION"))
            interactionHint = TextView(this@NativeCubeActivity).apply {
                textSize = 7.1f
                setTextColor(0xFFA9B8B2.toInt())
                setPadding(dp(1), 0, dp(1), dp(3))
                maxLines = 2
            }
            addView(interactionHint)
            updateInteractionHint()
            addView(row(
                button("Light", true) {
                    lightFocusMode = true
                    interactMode = true
                    interactButton.text = "Interact"
                    updateInteractionHint(true)
                },
                button("Reset") { if (rendererHandle != 0L) nativeResetPhysics(rendererHandle) }
            ))
        }
    }

    private fun buildLogCard(): LinearLayout {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(5), dp(2))
            addView(TextView(this@NativeCubeActivity).apply {
                text = "VULKAN LOG · V6"
                textSize = 7.4f
                setTextColor(0xFFAFC4BB.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton("×", "Close log") { togglePanel(logCard, horizontal = false) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logs, FrameLayout.LayoutParams(-1, -2))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(2), dp(5), dp(5))
            addView(smallAction("Copy") {
                val text = buildString {
                    append(stats.text)
                    append("\n\nVULKAN LOG\n")
                    append(logHistory.joinToString("\n"))
                }
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Vessel Vulkan Studio logs", text))
                Toast.makeText(this@NativeCubeActivity, "Full Vulkan log copied", Toast.LENGTH_SHORT).show()
            })
            addView(smallAction("Clear") {
                logHistory.clear()
                appendLog("log history cleared")
                logs.text = logHistory.joinToString("\n")
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xD3070B09.toInt(), 13f, 0x384ADBC0)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(actions)
        }
    }

    private fun smallAction(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 7.5f
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = rounded(0xA8111714.toInt(), 8f, 0x254ADBC0)
        setOnClickListener { click() }
    }

    private fun setQualityPreset(value: Int) {
        quality = value.coerceIn(0, 100)
        qualityLabel.text = "Quality  $quality / 100"
        if (::qualitySeek.isInitialized && qualitySeek.progress != quality) qualitySeek.progress = quality
        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
        appendLog("preset -> Q$quality")
    }

    private fun updateInteractionHint(lightFocus: Boolean = false) {
        if (!::interactionHint.isInitialized) return
        interactionHint.text = when {
            lightFocus || lightFocusMode -> "Light focus: drag anywhere to move the physical warm key light."
            interactMode -> "Touch an object, drag naturally, release to throw."
            else -> "Orbit mode. Pinch to zoom; Interact grabs physics objects."
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
        if (line.isBlank() || logHistory.lastOrNull() == line) return
        logHistory.addLast(line)
        while (logHistory.size > 420) logHistory.removeFirst()
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
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun destroyRenderer() {
        val h = rendererHandle
        rendererHandle = 0L
        if (h != 0L && nativeLoaded) runCatching { nativeDestroy(h) }
    }

    private fun showFatal(message: String) {
        runOnUiThread {
            stats.background = rounded(0xE6321216.toInt(), 12f, 0x99FF776F.toInt())
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
    private external fun nativeGrabLightStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabMove(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabEnd(handle: Long)
    private external fun nativeStatus(handle: Long): String
    private external fun nativeLogs(handle: Long): String
}
