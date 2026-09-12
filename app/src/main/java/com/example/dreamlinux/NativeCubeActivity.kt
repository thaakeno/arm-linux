package com.example.dreamlinux

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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

class NativeCubeActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var stats: TextView
    private lateinit var logs: TextView
    private lateinit var controlsCard: View
    private lateinit var logCard: LinearLayout
    private lateinit var qualityLabel: TextView
    private lateinit var qualitySeek: SeekBar
    private lateinit var wetnessLabel: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var bounceLabel: TextView
    private lateinit var rtButton: Button
    private lateinit var pathButton: Button
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
    private var quality = 100
    private var wetness = .86f
    private var exposureEv = -.18f
    private var gummyBounce = .80f
    private var heroMaterial = 1
    private var stressMode = false
    private var physicsEnabled = true
    private var rtEnabled = true
    private var pathTracing = false
    private var interactMode = false
    private var lightFocusMode = false
    private var statsExpanded = true
    private var lastNativeTail = ""
    private var lastStatusSnapshot = ""
    private var lastStatusSnapshotMs = 0L
    private var lightR = 1f
    private var lightG = .62f
    private var lightB = .31f

    private val statsPoll = object : Runnable {
        override fun run() {
            val h = rendererHandle
            if (h != 0L && nativeLoaded) {
                val status = runCatching { nativeStatus(h) }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                val nativeLog = runCatching { nativeLogs(h) }.getOrDefault("")
                val isError = status.startsWith("ERROR:")
                stats.background = panel(if (isError) 0xE02C1111.toInt() else 0xD6080D0C.toInt(), if (isError) 0xFFFF6C63.toInt() else 0xFF277C6B.toInt())
                val gesture = when {
                    lightFocusMode -> "LIGHT · drag orb"
                    interactMode -> "PHYSICS · drag / throw"
                    else -> "CAMERA · orbit / pinch"
                }
                val lines = status.lines()
                stats.text = if (statsExpanded) "$status\n$gesture" else lines.firstOrNull().orEmpty()
                if (::rtButton.isInitialized) {
                    val bypassed = status.contains("RT BYPASSED") || status.contains("RT OFF")
                    setButtonState(rtButton, rtEnabled && !bypassed, rtEnabled && bypassed, if (!rtEnabled) "◇ HWRT OFF" else if (bypassed) "◇ HWRT LIMITED" else "◇ HWRT ACTIVE")
                }
                if (::pathButton.isInitialized) setButtonState(pathButton, pathTracing, false, if (pathTracing) "✦ PATH TRACE ON" else "✦ PATH TRACE")
                absorbNativeLog(nativeLog)
                val now = System.currentTimeMillis()
                if (now - lastStatusSnapshotMs > 1200 && status != lastStatusSnapshot) {
                    lastStatusSnapshot = status
                    lastStatusSnapshotMs = now
                    appendLog("PERF  ${lines.firstOrNull().orEmpty()}")
                }
                logs.text = logHistory.takeLast(28).joinToString("\n")
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        nativeLoaded = try { System.loadLibrary("vessel_vulkan"); true } catch (_: Throwable) { false }

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = 8.7f
            setLineSpacing(0f, 1.0f)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            text = "Vulkan Studio v7.7\nInitializing Adreno renderer…"
            background = panel(0xD6080D0C.toInt(), 0xFF277C6B.toInt())
            setOnClickListener { statsExpanded = !statsExpanded }
        }
        logs = TextView(this).apply {
            setTextColor(0xFFE2F0EC.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 7.6f
            setPadding(dp(10), dp(7), dp(10), dp(8))
            setTextIsSelectable(true)
            text = "renderer not started"
        }
        surfaceView = SurfaceView(this).apply { background = null; holder.setFormat(PixelFormat.OPAQUE) }

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!interactMode && !lightFocusMode && rendererHandle != 0L) nativeZoom(rendererHandle, detector.scaleFactor)
                return true
            }
        })
        surfaceView.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            val h = rendererHandle
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y
                    if (h != 0L && (interactMode || lightFocusMode)) {
                        val nx = e.x / max(surfaceView.width, 1); val ny = e.y / max(surfaceView.height, 1)
                        if (lightFocusMode) nativeGrabLightStart(h, nx, ny) else nativeGrabStart(h, nx, ny)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && e.pointerCount == 1 && h != 0L) {
                    if (interactMode || lightFocusMode) nativeGrabMove(h, e.x / max(surfaceView.width, 1), e.y / max(surfaceView.height, 1))
                    else nativeRotate(h, -(e.x - lastX) * (132f / max(surfaceView.width, 1)), -(e.y - lastY) * (96f / max(surfaceView.height, 1)))
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if ((interactMode || lightFocusMode) && h != 0L) nativeGrabEnd(h)
            }
            true
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                if (!nativeLoaded || rendererHandle != 0L) return
                rendererHandle = runCatching { nativeCreate(holder.surface) }.getOrDefault(0L)
                if (rendererHandle == 0L) { showFatal("Vulkan renderer creation failed"); return }
                applyAllNativeSettings()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                if (rendererHandle != 0L) nativeResize(rendererHandle, max(width, 1), max(height, 1))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = destroyRenderer()
        })

        controlsCard = buildControls().apply { visibility = View.GONE; alpha = 0f; translationY = dp(12).toFloat() }
        logCard = buildLogCard().apply { visibility = View.GONE; alpha = 0f; translationY = dp(12).toFloat() }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7); topMargin = dp(7) })
        val dock = buildQuickBar()
        root.addView(dock, FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(7) })
        val panelWidth = minOf(dp(350), (resources.displayMetrics.widthPixels * .40f).toInt())
        val panelHeight = resources.displayMetrics.heightPixels - dp(78)
        root.addView(controlsCard, FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(64) })
        root.addView(logCard, FrameLayout.LayoutParams(minOf(dp(410), (resources.displayMetrics.widthPixels * .48f).toInt()), dp(190), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(64) })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun applyAllNativeSettings() {
        val h = rendererHandle; if (h == 0L) return
        nativeResize(h, max(surfaceView.width, 1), max(surfaceView.height, 1))
        nativeSetQuality(h, quality); nativeSetStress(h, stressMode); nativeSetPhysics(h, physicsEnabled); nativeSetRt(h, rtEnabled)
        nativeSetWetness(h, wetness); nativeSetExposure(h, exposureEv); nativeSetHeroMaterial(h, heroMaterial)
        nativeSetGummyBounce(h, gummyBounce); nativeSetLightColor(h, lightR, lightG, lightB); nativeSetPathTracing(h, pathTracing)
    }

    private fun buildQuickBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = panel(0xE4070C0A.toInt(), 0xFF245F53.toInt())
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(iconButton("⚙  SET", "Renderer controls") { if (::logCard.isInitialized) logCard.visibility = View.GONE; togglePanel(controlsCard) }, LinearLayout.LayoutParams(dp(78), dp(48)))
        addView(iconButton("▤  LOG", "Vulkan log") { if (::controlsCard.isInitialized) controlsCard.visibility = View.GONE; togglePanel(logCard) }, LinearLayout.LayoutParams(dp(78), dp(48)))
        addView(iconButton("↺", "Reset physical scene") { val h=rendererHandle;if(h!=0L){nativeResetPhysics(h);nativeSetGummyBounce(h,gummyBounce);nativeSetLightColor(h,lightR,lightG,lightB)} }, LinearLayout.LayoutParams(dp(50), dp(48)))
    }

    private fun iconButton(label: String, description: String, click: () -> Unit) = TextView(this).apply {
        text = label; contentDescription = description; gravity = Gravity.CENTER; textSize = 8.7f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextColor(0xFFF2FBF8.toInt()); setPadding(dp(7), dp(5), dp(7), dp(5)); background = panel(0xC6101714.toInt(), 0xFF263A34.toInt())
        setOnClickListener { click() }
    }

    private fun togglePanel(view: View) {
        if (view.visibility == View.VISIBLE) view.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(110).withEndAction { view.visibility = View.GONE }.start()
        else { view.visibility = View.VISIBLE; view.alpha = 0f; view.translationY = dp(10).toFloat(); view.animate().alpha(1f).translationY(0f).setDuration(145).start() }
    }

    private fun makeButton(label: String, active: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
        text = label; textSize = 8.8f; isAllCaps = false; setTextColor(Color.WHITE); minHeight = dp(48); minWidth = dp(48); setPadding(dp(7), dp(3), dp(7), dp(3)); stateListAnimator = null
        setButtonState(this, active); setOnClickListener { alpha=.78f; animate().alpha(1f).setDuration(90).start(); click(this) }
    }

    private fun setButtonState(button: Button, active: Boolean, warning: Boolean = false, label: String? = null) {
        if (label != null) button.text = label
        button.background = panel(when { warning -> 0xE079431A.toInt(); active -> 0xE214695C.toInt(); else -> 0xDE0A100E.toInt() }, when { warning -> 0xFFDB8E49.toInt(); active -> 0xFF40C5AA.toInt(); else -> 0xFF293A35.toInt() })
    }

    private fun buildControls(): ScrollView {
        fun section(t: String) = TextView(this).apply { text = t; textSize = 7.2f; letterSpacing = .15f; setTextColor(0xFF6BC4AC.toInt()); setPadding(0, dp(10), 0, dp(3)); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }) } }
        fun slider(value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply { max = 100; progress = value; minHeight = dp(42); setPadding(0, 0, 0, 0); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onChange(p); override fun onStartTrackingTouch(s: SeekBar?) = Unit; override fun onStopTrackingTouch(s: SeekBar?) = Unit }) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); background = panel(0xF2070C0A.toInt(), 0xFF286C5D.toInt())
            addView(TextView(this@NativeCubeActivity).apply { text = "VULKAN STUDIO  //  v7.7"; textSize = 12.5f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) })
            addView(TextView(this@NativeCubeActivity).apply { text = "ADRENO 840 · 120 HZ HYBRID · HWRT · PATH INSPECT"; textSize = 6.9f; letterSpacing = .08f; setTextColor(0xFF839A93.toInt()) })

            addView(section("RENDER"))
            qualityLabel = label("QUALITY  $quality / 100"); addView(qualityLabel)
            qualitySeek = slider(quality) { quality = it; qualityLabel.text = "QUALITY  $quality / 100"; if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality) }; addView(qualitySeek, LinearLayout.LayoutParams(-1, dp(42)))
            addView(row(makeButton("△ EFFICIENT") { setQualityPreset(55) }, makeButton("▣ 120 HZ") { setQualityPreset(78) }, makeButton("◆ MAX") { setQualityPreset(100) }))

            addView(section("LIGHTING"))
            rtButton = makeButton("◇ HWRT ACTIVE", true) { b ->
                rtEnabled = !rtEnabled
                if (!rtEnabled && pathTracing) { pathTracing = false; if (rendererHandle != 0L) nativeSetPathTracing(rendererHandle, false) }
                setButtonState(b, rtEnabled, false, if (rtEnabled) "◇ HWRT ACTIVE" else "◇ HWRT OFF")
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            pathButton = makeButton("✦ PATH TRACE") { b ->
                pathTracing = !pathTracing
                if (pathTracing) { rtEnabled = true; setButtonState(rtButton, true, false, "◇ HWRT ACTIVE"); if (rendererHandle != 0L) nativeSetRt(rendererHandle, true) }
                setButtonState(b, pathTracing, false, if (pathTracing) "✦ PATH TRACE ON" else "✦ PATH TRACE")
                if (rendererHandle != 0L) nativeSetPathTracing(rendererHandle, pathTracing)
            }
            addView(row(rtButton, pathButton))
            addView(TextView(this@NativeCubeActivity).apply { text = "Hybrid targets 120 Hz. Path Trace uses one stochastic GI bounce at 0.70x with stable IBL blending for mobile inspection quality."; textSize = 7.2f; setTextColor(0xFF9FB2AC.toInt()); setPadding(dp(1), dp(4), dp(1), dp(3)) })

            addView(section("PHYSICS"))
            physicsButton = makeButton("● PHYSICS ON", true) { b -> physicsEnabled = !physicsEnabled; setButtonState(b, physicsEnabled, false, if (physicsEnabled) "● PHYSICS ON" else "○ PHYSICS OFF"); if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled) }
            interactButton = makeButton("⌖ ORBIT") { b -> interactMode = !interactMode; lightFocusMode = false; setButtonState(b, interactMode, false, if (interactMode) "✥ INTERACT" else "⌖ ORBIT"); updateInteractionHint() }
            addView(row(physicsButton, interactButton))
            stressButton = makeButton("▦ COURTYARD") { b -> stressMode = !stressMode; setButtonState(b, stressMode, stressMode, if (stressMode) "▦ STRESS LAB" else "▦ COURTYARD"); if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode) }
            addView(stressButton, LinearLayout.LayoutParams(-1, dp(48)))

            addView(section("SOFT BODY"))
            bounceLabel = label(String.format(Locale.US, "GUMMY REBOUND  %.2f", gummyBounce)); addView(bounceLabel)
            addView(slider(((gummyBounce - .38f) / .56f * 100).toInt().coerceIn(0,100)) { p -> gummyBounce = .38f + p / 100f * .56f; bounceLabel.text = String.format(Locale.US, "GUMMY REBOUND  %.2f", gummyBounce); if (rendererHandle != 0L) nativeSetGummyBounce(rendererHandle, gummyBounce) }, LinearLayout.LayoutParams(-1, dp(42)))
            addView(TextView(this@NativeCubeActivity).apply { text = "Slime: high adhesion, zero resting rebound, damped viscoelastic recovery. Gummy: high elastic rebound with directional squash."; textSize = 7.2f; setTextColor(0xFF9FB2AC.toInt()); setPadding(dp(1), 0, dp(1), dp(4)) })

            addView(section("LIGHT ORB"))
            addView(row(makeButton("◐ WARM", true) { setLight(1f, .62f, .31f) }, makeButton("○ WHITE") { setLight(1f, .96f, .88f) }, makeButton("◇ ICE") { setLight(.38f, .68f, 1f) }, makeButton("✦ NEON") { setLight(.75f, .25f, 1f) }))
            addView(makeButton("◎ MOVE LIGHT") { b -> lightFocusMode = !lightFocusMode; interactMode = lightFocusMode || interactMode; setButtonState(b, lightFocusMode); setButtonState(interactButton, interactMode, false, if (interactMode) "✥ INTERACT" else "⌖ ORBIT"); updateInteractionHint(lightFocusMode) }, LinearLayout.LayoutParams(-1, dp(48)))

            addView(section("ENVIRONMENT"))
            addView(row(makeButton("☀ DAY") { applyScenePreset(-.10f, .82f, 1f, .90f, .78f) }, makeButton("◐ GOLDEN") { applyScenePreset(-.38f, .92f, 1f, .52f, .22f) }, makeButton("☾ NIGHT") { applyScenePreset(-1.15f, .96f, .32f, .58f, 1f) }))
            heroButton = makeButton("⬡ VOLCANIC", true) { b -> heroMaterial = 1 - heroMaterial; val v = heroMaterial == 1; setButtonState(b, v, false, if (v) "⬡ VOLCANIC" else "▧ CONCRETE"); if (rendererHandle != 0L) nativeSetHeroMaterial(rendererHandle, heroMaterial) }
            addView(heroButton, LinearLayout.LayoutParams(-1, dp(48)))
            wetnessLabel = label("WET STONE  ${(wetness * 100).toInt()}%"); addView(wetnessLabel)
            addView(slider((wetness * 100).toInt()) { wetness = it / 100f; wetnessLabel.text = "WET STONE  $it%"; if (rendererHandle != 0L) nativeSetWetness(rendererHandle, wetness) }, LinearLayout.LayoutParams(-1, dp(42)))
            exposureLabel = label(String.format(Locale.US, "EXPOSURE  %+.2f EV", exposureEv)); addView(exposureLabel)
            addView(slider((((exposureEv + 1.5f) / 2.5f) * 100).toInt().coerceIn(0, 100)) { p -> exposureEv = -1.5f + p / 100f * 2.5f; exposureLabel.text = String.format(Locale.US, "EXPOSURE  %+.2f EV", exposureEv); if (rendererHandle != 0L) nativeSetExposure(rendererHandle, exposureEv) }, LinearLayout.LayoutParams(-1, dp(42)))

            addView(section("CONTROL"))
            interactionHint = label("").apply { maxLines = 2 }; addView(interactionHint); updateInteractionHint()
            addView(makeButton("↺ RESET PHYSICS") { if (rendererHandle != 0L) { nativeResetPhysics(rendererHandle); nativeSetGummyBounce(rendererHandle, gummyBounce); nativeSetLightColor(rendererHandle, lightR, lightG, lightB) } }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        return ScrollView(this).apply { isFillViewport = true; background = panel(0xF2070C0A.toInt(), 0xFF286C5D.toInt()); addView(body) }
    }

    private fun label(textValue: String) = TextView(this).apply { text = textValue; textSize = 8.1f; setTextColor(0xFFC7D8D2.toInt()); setPadding(dp(1), dp(2), dp(1), dp(2)) }
    private fun setLight(r: Float, g: Float, b: Float) { lightR = r; lightG = g; lightB = b; if (rendererHandle != 0L) nativeSetLightColor(rendererHandle, r, g, b); appendLog(String.format(Locale.US, "light -> %.2f %.2f %.2f", r, g, b)) }
    private fun applyScenePreset(ev: Float, wet: Float, r: Float, g: Float, b: Float) { exposureEv = ev; wetness = wet; if (::exposureLabel.isInitialized) exposureLabel.text = String.format(Locale.US, "EXPOSURE  %+.2f EV", exposureEv); if (::wetnessLabel.isInitialized) wetnessLabel.text = "WET STONE  ${(wetness * 100).toInt()}%"; setLight(r, g, b); if (rendererHandle != 0L) { nativeSetExposure(rendererHandle, exposureEv); nativeSetWetness(rendererHandle, wetness) } }
    private fun setQualityPreset(v: Int) { quality = v.coerceIn(0, 100); qualityLabel.text = "QUALITY  $quality / 100"; if (::qualitySeek.isInitialized && qualitySeek.progress != quality) qualitySeek.progress = quality; if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality); appendLog("preset -> Q$quality") }
    private fun updateInteractionHint(light: Boolean = false) { if (!::interactionHint.isInitialized) return; interactionHint.text = when { light || lightFocusMode -> "Drag anywhere to reposition the emissive light orb."; interactMode -> "Grab a body, drag naturally, release quickly to fling; release still to drop under gravity."; else -> "Orbit the camera and pinch to inspect material detail." } }

    private fun buildLogCard(): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(logs, FrameLayout.LayoutParams(-1, -2)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panel(0xF2070C0A.toInt(), 0xFF286C5D.toInt())
            addView(LinearLayout(this@NativeCubeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(3), dp(3), dp(3))
                addView(TextView(this@NativeCubeActivity).apply { text = "▤  VULKAN TELEMETRY"; textSize = 7.8f; letterSpacing = .1f; setTextColor(0xFF70C8B0.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton("⧉ COPY", "Copy log") { copyLog() }, LinearLayout.LayoutParams(dp(76), dp(44))); addView(iconButton("×", "Close") { togglePanel(logCard) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun copyLog() { val text = stats.text.toString() + "\n\nVULKAN LOG\n" + logHistory.joinToString("\n"); getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vulkan Studio log", text)); Toast.makeText(this, "Vulkan log copied", Toast.LENGTH_SHORT).show() }
    private fun absorbNativeLog(text: String) { val lines = text.lineSequence().filter { it.isNotBlank() }.toList(); if (lines.isEmpty()) return; var start = 0; if (lastNativeTail.isNotEmpty()) { val i = lines.indexOfLast { it == lastNativeTail }; if (i >= 0) start = i + 1 }; for (i in start until lines.size) appendLog(lines[i]); lastNativeTail = lines.last() }
    private fun appendLog(line: String) { if (line.isBlank() || logHistory.lastOrNull() == line) return; logHistory.addLast(line); while (logHistory.size > 520) logHistory.removeFirst() }
    private fun panel(color: Int, strokeColor: Int? = null) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = 0f; if (strokeColor != null) setStroke(dp(1), strokeColor) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enterImmersiveMode() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); destroyRenderer(); super.onDestroy() }
    private fun enterImmersiveMode() { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
    private fun destroyRenderer() { val h = rendererHandle; rendererHandle = 0L; if (h != 0L && nativeLoaded) runCatching { nativeDestroy(h) } }
    private fun showFatal(message: String) { runOnUiThread { stats.text = "Native Vulkan error\n$message"; stats.background = panel(0xE6321216.toInt(), 0xFFFF776F.toInt()) } }

    private external fun nativeCreate(surface: Surface): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeRotate(handle: Long, yawDegrees: Float, pitchDegrees: Float)
    private external fun nativeZoom(handle: Long, scaleFactor: Float)
    private external fun nativeSetStress(handle: Long, enabled: Boolean)
    private external fun nativeSetPhysics(handle: Long, enabled: Boolean)
    private external fun nativeSetRt(handle: Long, enabled: Boolean)
    private external fun nativeSetPathTracing(handle: Long, enabled: Boolean)
    private external fun nativeSetQuality(handle: Long, quality: Int)
    private external fun nativeSetWetness(handle: Long, value: Float)
    private external fun nativeSetExposure(handle: Long, value: Float)
    private external fun nativeSetHeroMaterial(handle: Long, material: Int)
    private external fun nativeSetGummyBounce(handle: Long, value: Float)
    private external fun nativeSetLightColor(handle: Long, r: Float, g: Float, b: Float)
    private external fun nativeResetPhysics(handle: Long)
    private external fun nativeGrabStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabLightStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabMove(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabEnd(handle: Long)
    private external fun nativeStatus(handle: Long): String
    private external fun nativeLogs(handle: Long): String
}
