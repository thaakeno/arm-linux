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
import java.util.Locale
import kotlin.math.max

/** Native Android Vulkan reference benchmark used for the future Debian/Venus A/B test. */
class NativeCubeActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var stats: TextView
    private lateinit var logs: TextView
    private lateinit var controlsCard: LinearLayout
    private lateinit var logCard: LinearLayout
    private lateinit var qualityLabel: TextView
    private lateinit var qualitySeek: SeekBar
    private lateinit var wetnessLabel: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var rtButton: Button
    private lateinit var stressButton: Button
    private lateinit var physicsButton: Button
    private lateinit var interactButton: Button
    private lateinit var heroButton: Button
    private lateinit var interactionHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val logHistory = ArrayDeque<String>()
    private var rendererHandle = 0L
    private var nativeLoaded = false
    private var lastX = 0f
    private var lastY = 0f
    private var quality = 72
    private var wetness = .68f
    private var exposureEv = -.18f
    private var heroMaterial = 1
    private var stressMode = false
    private var physicsEnabled = true
    private var rtEnabled = true
    private var interactMode = false
    private var lightFocusMode = false
    private var statsExpanded = true
    private var lastNativeTail = ""
    private var lastPublishedNativeLog = ""
    private var lastStatusSnapshot = ""
    private var lastStatusSnapshotMs = 0L

    private val statsPoll = object : Runnable {
        override fun run() {
            val handle = rendererHandle
            if (handle != 0L && nativeLoaded) {
                val status = runCatching { nativeStatus(handle) }
                    .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                val nativeLog = runCatching { nativeLogs(handle) }
                    .getOrElse { "log read failed: ${it.message ?: it.javaClass.simpleName}" }
                val isError = status.startsWith("ERROR:")
                stats.background = rounded(
                    if (isError) 0xD7341717.toInt() else 0xA90A0D0C.toInt(),
                    11f,
                    if (isError) 0x99FF746C.toInt() else 0x354ADBC0
                )
                val gesture = when {
                    lightFocusMode -> "Light focus · drag anywhere · release to place"
                    interactMode -> "Interact · drag objects · release to throw"
                    else -> "Orbit · pinch zoom"
                }
                val lines = status.lines()
                stats.text = if (statsExpanded) "$status\n$gesture" else lines.firstOrNull().orEmpty()

                if (::rtButton.isInitialized) {
                    val bypassed = status.contains("RT BYPASSED") || status.contains("RT OFF")
                    when {
                        !rtEnabled -> setButtonState(rtButton, false, false, "RT OFF")
                        bypassed -> setButtonState(rtButton, false, true, "RT BYPASSED")
                        else -> setButtonState(rtButton, true, false, "RT ACTIVE")
                    }
                }

                absorbNativeLog(nativeLog)
                val now = System.currentTimeMillis()
                if (now - lastStatusSnapshotMs >= 1_200L && status != lastStatusSnapshot) {
                    lastStatusSnapshot = status
                    lastStatusSnapshotMs = now
                    appendLog("PERF  ${lines.firstOrNull().orEmpty()}")
                }
                logs.text = logHistory.takeLast(24).joinToString("\n")
                val merged = "$status\n${logHistory.joinToString("\n")}".takeLast(180_000)
                if (merged != lastPublishedNativeLog) {
                    lastPublishedNativeLog = merged
                    val state = VmSessionService.state.value
                    val marker = "[Native Vulkan Studio]"
                    val old = state.console.substringBeforeLast(marker).trimEnd()
                    VmSessionService.state.value = state.copy(
                        console = (old + "\n\n$marker\n" + merged).takeLast(240_000)
                    )
                }
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 8.6f
            setLineSpacing(0f, .95f)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            text = "Vulkan Studio v7\nLoading coherent courtyard…"
            background = rounded(0xA90A0D0C.toInt(), 11f, 0x354ADBC0)
            setOnClickListener {
                statsExpanded = !statsExpanded
                animate().scaleX(.98f).scaleY(.98f).setDuration(55).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
            }
        }
        logs = TextView(this).apply {
            setTextColor(0xFFE6F0EB.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 7.3f
            setLineSpacing(0f, .95f)
            setPadding(dp(8), dp(5), dp(8), dp(6))
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
            val h = rendererHandle
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    if (h != 0L && (interactMode || lightFocusMode)) {
                        val nx = event.x / max(surfaceView.width, 1)
                        val ny = event.y / max(surfaceView.height, 1)
                        if (lightFocusMode) nativeGrabLightStart(h, nx, ny) else nativeGrabStart(h, nx, ny)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && h != 0L) {
                        if (interactMode || lightFocusMode) {
                            nativeGrabMove(h, event.x / max(surfaceView.width, 1), event.y / max(surfaceView.height, 1))
                        } else {
                            nativeRotate(
                                h,
                                -(event.x - lastX) * (132f / max(surfaceView.width, 1)),
                                -(event.y - lastY) * (96f / max(surfaceView.height, 1))
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
                    if ((interactMode || lightFocusMode) && h != 0L) nativeGrabEnd(h)
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
                    val h = rendererHandle
                    nativeResize(h, max(surfaceView.width, 1), max(surfaceView.height, 1))
                    nativeSetQuality(h, quality)
                    nativeSetStress(h, stressMode)
                    nativeSetPhysics(h, physicsEnabled)
                    nativeSetRt(h, rtEnabled)
                    nativeSetWetness(h, wetness)
                    nativeSetExposure(h, exposureEv)
                    nativeSetHeroMaterial(h, heroMaterial)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (rendererHandle != 0L) nativeResize(rendererHandle, max(width, 1), max(height, 1))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = destroyRenderer()
        })

        controlsCard = buildControls().apply {
            visibility = View.GONE
            alpha = 0f
            translationX = dp(12).toFloat()
        }
        logCard = buildLogCard().apply {
            visibility = View.GONE
            alpha = 0f
            translationY = dp(10).toFloat()
        }
        val quickBar = buildQuickBar()

        val panelWidth = minOf(dp(206), (resources.displayMetrics.widthPixels * .27f).toInt())
        val logWidth = minOf(dp(310), (resources.displayMetrics.widthPixels * .42f).toInt())
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6); topMargin = dp(6) })
        root.addView(quickBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })
        root.addView(controlsCard, FrameLayout.LayoutParams(panelWidth, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(35); rightMargin = dp(6) })
        root.addView(logCard, FrameLayout.LayoutParams(logWidth, dp(142), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(6); bottomMargin = dp(6) })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun buildQuickBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(0x8F080C0A.toInt(), 11f, 0x344ADBC0)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(iconButton("⚙", "Renderer controls") { togglePanel(controlsCard, true) })
        addView(iconButton("▤", "Vulkan log") { togglePanel(logCard, false) })
    }

    private fun iconButton(icon: String, description: String, click: () -> Unit) = TextView(this).apply {
        text = icon
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(0xFFF1FFF9.toInt())
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = rounded(0x7D111814.toInt(), 8f, 0x274ADBC0)
        setOnClickListener { click() }
    }

    private fun togglePanel(view: View, horizontal: Boolean) {
        if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f)
                .translationX(if (horizontal) dp(10).toFloat() else 0f)
                .translationY(if (horizontal) 0f else dp(8).toFloat())
                .setDuration(130)
                .withEndAction { view.visibility = View.GONE }
                .start()
        } else {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationX = if (horizontal) dp(10).toFloat() else 0f
            view.translationY = if (horizontal) 0f else dp(8).toFloat()
            view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(165).start()
        }
    }

    private fun makeButton(label: String, active: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
        text = label
        textSize = 7.6f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = 0
        minWidth = 0
        setPadding(dp(4), dp(2), dp(4), dp(2))
        setButtonState(this, active)
        setOnClickListener {
            animate().scaleX(.96f).scaleY(.96f).setDuration(50).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            click(this)
        }
    }

    private fun setButtonState(button: Button, active: Boolean, warning: Boolean = false, label: String? = null) {
        if (label != null) button.text = label
        val fill = when {
            warning -> 0xC8864E20.toInt()
            active -> 0xC5147767.toInt()
            else -> 0xB6101713.toInt()
        }
        val stroke = when {
            warning -> 0x99E99A55.toInt()
            active -> 0x795DE8C7
            else -> 0x29445650
        }
        button.background = rounded(fill, 8f, stroke)
    }

    private fun buildControls(): LinearLayout {
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(3) }) }
        }
        fun section(text: String) = TextView(this).apply {
            this.text = text
            textSize = 6.7f
            setTextColor(0xFF8FA79E.toInt())
            setPadding(0, dp(3), 0, dp(1))
            letterSpacing = .10f
        }
        fun slider(progress: Int, change: (Int) -> Unit) = SeekBar(this).apply {
            max = 100
            this.progress = progress
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) = change(value)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(7), dp(7))
            background = rounded(0xD4070B09.toInt(), 13f, 0x424ADBC0)

            addView(LinearLayout(this@NativeCubeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@NativeCubeActivity).apply {
                    text = "VULKAN STUDIO"
                    textSize = 9.8f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@NativeCubeActivity).apply {
                    text = "v7"
                    textSize = 7f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFD8F7ED.toInt())
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    background = rounded(0x70455F57, 6f)
                })
            })
            addView(TextView(this@NativeCubeActivity).apply {
                text = "Local HWRT · physical puddles · safe stress"
                textSize = 6.7f
                setTextColor(0xFF9BA9A4.toInt())
            })

            addView(section("QUALITY"))
            qualityLabel = TextView(this@NativeCubeActivity).apply {
                text = "Quality  $quality / 100"
                textSize = 7.5f
                setTextColor(0xFFEAF7F1.toInt())
            }
            addView(qualityLabel)
            qualitySeek = slider(quality) { value ->
                quality = value
                qualityLabel.text = "Quality  $quality / 100"
                if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
            }
            addView(qualitySeek, LinearLayout.LayoutParams(-1, dp(23)))
            addView(row(
                makeButton("Low") { setQualityPreset(28) },
                makeButton("120 Hz") { setQualityPreset(68) },
                makeButton("Ultra") { setQualityPreset(100) }
            ))

            addView(section("RAY TRACING + PHYSICS"))
            rtButton = makeButton("RT ACTIVE", true) { b ->
                rtEnabled = !rtEnabled
                setButtonState(b, rtEnabled, false, if (rtEnabled) "RT ACTIVE" else "RT OFF")
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            physicsButton = makeButton("Physics ON", true) { b ->
                physicsEnabled = !physicsEnabled
                setButtonState(b, physicsEnabled, false, if (physicsEnabled) "Physics ON" else "Physics OFF")
                if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled)
            }
            addView(row(rtButton, physicsButton))
            stressButton = makeButton("Courtyard") { b ->
                stressMode = !stressMode
                setButtonState(b, stressMode, stressMode, if (stressMode) "Safe Stress" else "Courtyard")
                if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode)
            }
            interactButton = makeButton("Orbit") { b ->
                interactMode = !interactMode
                lightFocusMode = false
                setButtonState(b, interactMode, false, if (interactMode) "Interact" else "Orbit")
                updateInteractionHint()
                if (!interactMode && rendererHandle != 0L) nativeGrabEnd(rendererHandle)
            }
            addView(row(stressButton, interactButton))

            addView(section("SCENE MATERIAL"))
            heroButton = makeButton("Volcanic rock", true) { b ->
                heroMaterial = 1 - heroMaterial
                val volcanic = heroMaterial == 1
                setButtonState(b, volcanic, false, if (volcanic) "Volcanic rock" else "Concrete")
                if (rendererHandle != 0L) nativeSetHeroMaterial(rendererHandle, heroMaterial)
            }
            val lightButton = makeButton("Move light") { b ->
                lightFocusMode = !lightFocusMode
                interactMode = lightFocusMode || interactMode
                setButtonState(b, lightFocusMode)
                setButtonState(interactButton, interactMode, false, if (interactMode) "Interact" else "Orbit")
                updateInteractionHint(lightFocusMode)
            }
            addView(row(heroButton, lightButton))

            wetnessLabel = TextView(this@NativeCubeActivity).apply {
                text = "Puddles  ${Math.round(wetness * 100)}%"
                textSize = 7.2f
                setTextColor(0xFFB9C8C2.toInt())
            }
            addView(wetnessLabel)
            addView(slider(Math.round(wetness * 100)) { value ->
                wetness = value / 100f
                wetnessLabel.text = "Puddles  $value%"
                if (rendererHandle != 0L) nativeSetWetness(rendererHandle, wetness)
            }, LinearLayout.LayoutParams(-1, dp(21)))

            val exposureProgress = (((exposureEv + 1.5f) / 2.5f) * 100f).toInt().coerceIn(0, 100)
            exposureLabel = TextView(this@NativeCubeActivity).apply {
                text = String.format(Locale.US, "Exposure  %+.2f EV", exposureEv)
                textSize = 7.2f
                setTextColor(0xFFB9C8C2.toInt())
            }
            addView(exposureLabel)
            addView(slider(exposureProgress) { value ->
                exposureEv = -1.5f + value / 100f * 2.5f
                exposureLabel.text = String.format(Locale.US, "Exposure  %+.2f EV", exposureEv)
                if (rendererHandle != 0L) nativeSetExposure(rendererHandle, exposureEv)
            }, LinearLayout.LayoutParams(-1, dp(21)))

            addView(section("INTERACTION"))
            interactionHint = TextView(this@NativeCubeActivity).apply {
                textSize = 6.7f
                setTextColor(0xFFA9B8B2.toInt())
                maxLines = 2
            }
            addView(interactionHint)
            updateInteractionHint()
            addView(makeButton("Reset scene") {
                if (rendererHandle != 0L) nativeResetPhysics(rendererHandle)
            }, LinearLayout.LayoutParams(-1, dp(30)))
        }
    }

    private fun buildLogCard(): LinearLayout {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(1))
            addView(TextView(this@NativeCubeActivity).apply {
                text = "VULKAN LOG · V7"
                textSize = 7f
                setTextColor(0xFFAFC4BB.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton("×", "Close log") { togglePanel(logCard, false) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logs, FrameLayout.LayoutParams(-1, -2))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(1), dp(4), dp(4))
            addView(smallAction("Copy") {
                val text = buildString {
                    append(stats.text)
                    append("\n\nVULKAN LOG\n")
                    append(logHistory.joinToString("\n"))
                }
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Vessel Vulkan Studio v7 logs", text))
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
            background = rounded(0xD3070B09.toInt(), 12f, 0x384ADBC0)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(actions)
        }
    }

    private fun smallAction(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 7f
        setTextColor(Color.WHITE)
        setPadding(dp(9), dp(3), dp(9), dp(3))
        background = rounded(0xA8111714.toInt(), 7f, 0x254ADBC0)
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
            lightFocus || lightFocusMode -> "Drag anywhere to reposition the physical warm key light."
            interactMode -> "Touch an object, drag naturally, release to throw."
            else -> "Orbit the camera; pinch to zoom."
        }
    }

    private fun absorbNativeLog(text: String) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return
        val tail = lines.last()
        if (tail == lastNativeTail && lines.size == 1) return
        var start = 0
        if (lastNativeTail.isNotEmpty()) {
            val index = lines.indexOfLast { it == lastNativeTail }
            if (index >= 0) start = index + 1
        }
        for (i in start until lines.size) appendLog(lines[i])
        lastNativeTail = tail
    }

    private fun appendLog(line: String) {
        if (line.isBlank() || logHistory.lastOrNull() == line) return
        logHistory.addLast(line)
        while (logHistory.size > 520) logHistory.removeFirst()
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
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
            stats.background = rounded(0xE6321216.toInt(), 11f, 0x99FF776F.toInt())
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
    private external fun nativeSetWetness(handle: Long, value: Float)
    private external fun nativeSetExposure(handle: Long, value: Float)
    private external fun nativeSetHeroMaterial(handle: Long, material: Int)
    private external fun nativeResetPhysics(handle: Long)
    private external fun nativeGrabStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabLightStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabMove(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabEnd(handle: Long)
    private external fun nativeStatus(handle: Long): String
    private external fun nativeLogs(handle: Long): String
}
