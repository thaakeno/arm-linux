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
    private var gummyBounce = .76f
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
                stats.background = rounded(if (isError) 0xD7341717.toInt() else 0xBA07100D.toInt(), 14f, if (isError) 0x99FF746C.toInt() else 0x684DEFD0)
                val gesture = when {
                    lightFocusMode -> "LIGHT ORB · drag to place"
                    interactMode -> "OBJECT PHYSICS · drag / throw"
                    else -> "CAMERA · orbit / pinch"
                }
                val lines = status.lines()
                stats.text = if (statsExpanded) "$status\n$gesture" else lines.firstOrNull().orEmpty()
                if (::rtButton.isInitialized) {
                    val bypassed = status.contains("RT BYPASSED") || status.contains("RT OFF")
                    setButtonState(rtButton, rtEnabled && !bypassed, rtEnabled && bypassed, if (!rtEnabled) "HWRT OFF" else if (bypassed) "HWRT LIMITED" else "HWRT ACTIVE")
                }
                if (::pathButton.isInitialized) setButtonState(pathButton, pathTracing, false, if (pathTracing) "PATH TRACE ON" else "PATH TRACE")
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
        nativeLoaded = try { System.loadLibrary("vessel_vulkan"); true } catch (t: Throwable) { false }

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 9.2f
            setLineSpacing(0f, .98f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            text = "Vulkan Studio v7.3\nInitializing Adreno renderer…"
            background = rounded(0xBA07100D.toInt(), 14f, 0x684DEFD0)
            setOnClickListener { statsExpanded = !statsExpanded }
        }
        logs = TextView(this).apply {
            setTextColor(0xFFE4F4EE.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 7.8f
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                if (!nativeLoaded || rendererHandle != 0L) return
                rendererHandle = runCatching { nativeCreate(holder.surface) }.getOrDefault(0L)
                if (rendererHandle == 0L) { showFatal("Vulkan renderer creation failed"); return }
                applyAllNativeSettings()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                if (rendererHandle != 0L) nativeResize(rendererHandle, max(width, 1), max(height, 1))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = destroyRenderer()
        })

        controlsCard = buildControls().apply { visibility = View.GONE; alpha = 0f; translationX = dp(16).toFloat() }
        logCard = buildLogCard().apply { visibility = View.GONE; alpha = 0f; translationY = dp(12).toFloat() }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7); topMargin = dp(7) })
        root.addView(buildQuickBar(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(7); rightMargin = dp(7) })
        val panelWidth = minOf(dp(330), (resources.displayMetrics.widthPixels * .43f).toInt())
        root.addView(controlsCard, FrameLayout.LayoutParams(panelWidth, resources.displayMetrics.heightPixels - dp(54), Gravity.TOP or Gravity.END).apply { topMargin = dp(44); rightMargin = dp(7) })
        root.addView(logCard, FrameLayout.LayoutParams(minOf(dp(390), (resources.displayMetrics.widthPixels * .50f).toInt()), dp(178), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(7) })
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
        background = rounded(0xB2070D0B.toInt(), 14f, 0x604DEFD0)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(iconButton("SET", "Renderer controls") { togglePanel(controlsCard, true) }, LinearLayout.LayoutParams(dp(58), dp(48)))
        addView(iconButton("LOG", "Vulkan log") { togglePanel(logCard, false) }, LinearLayout.LayoutParams(dp(58), dp(48)))
    }

    private fun iconButton(label: String, description: String, click: () -> Unit) = TextView(this).apply {
        text = label; contentDescription = description; gravity = Gravity.CENTER; textSize = 9f
        setTextColor(0xFFF0FFF9.toInt()); setPadding(dp(8), dp(5), dp(8), dp(5)); background = rounded(0x8B121A16.toInt(), 10f, 0x4B4DEFD0)
        setOnClickListener { click() }
    }

    private fun togglePanel(view: View, horizontal: Boolean) {
        if (view.visibility == View.VISIBLE) view.animate().alpha(0f).translationX(if (horizontal) dp(12).toFloat() else 0f).translationY(if (horizontal) 0f else dp(10).toFloat()).setDuration(120).withEndAction { view.visibility = View.GONE }.start()
        else { view.visibility = View.VISIBLE; view.alpha = 0f; view.translationX = if (horizontal) dp(12).toFloat() else 0f; view.translationY = if (horizontal) 0f else dp(10).toFloat(); view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(165).start() }
    }

    private fun makeButton(label: String, active: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
        text = label; textSize = 9.2f; isAllCaps = false; setTextColor(Color.WHITE); minHeight = dp(48); minWidth = dp(48); setPadding(dp(6), dp(4), dp(6), dp(4))
        setButtonState(this, active); setOnClickListener { animate().scaleX(.97f).scaleY(.97f).setDuration(55).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(75).start() }.start(); click(this) }
    }

    private fun setButtonState(button: Button, active: Boolean, warning: Boolean = false, label: String? = null) {
        if (label != null) button.text = label
        button.background = rounded(when { warning -> 0xD08A4B1D.toInt(); active -> 0xE2157969.toInt(); else -> 0xD00B1411.toInt() }, 12f, when { warning -> 0xB9EF9950.toInt(); active -> 0xB05EEDD0.toInt(); else -> 0x58485A53 })
    }

    private fun buildControls(): ScrollView {
        fun section(t: String) = TextView(this).apply { text = t; textSize = 7.6f; letterSpacing = .12f; setTextColor(0xFF72C8B0.toInt()); setPadding(0, dp(9), 0, dp(3)) }
        fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) }) } }
        fun slider(value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply { max = 100; progress = value; minHeight = dp(44); setPadding(0, 0, 0, 0); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onChange(p); override fun onStartTrackingTouch(s: SeekBar?) = Unit; override fun onStopTrackingTouch(s: SeekBar?) = Unit }) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(12), dp(14)); background = rounded(0xF0070C0A.toInt(), 17f, 0x724DEFD0)
            addView(TextView(this@NativeCubeActivity).apply { text = "VULKAN STUDIO  /  v7.3"; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) })
            addView(TextView(this@NativeCubeActivity).apply { text = "ADRENO LAB · HWRT · PATH TRACE · SOFT BODY"; textSize = 7.2f; letterSpacing = .07f; setTextColor(0xFF8CA69E.toInt()) })

            addView(section("PERFORMANCE"))
            qualityLabel = label("Quality  $quality / 100"); addView(qualityLabel)
            qualitySeek = slider(quality) { quality = it; qualityLabel.text = "Quality  $quality / 100"; if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality) }; addView(qualitySeek, LinearLayout.LayoutParams(-1, dp(44)))
            addView(row(makeButton("Efficiency") { setQualityPreset(55) }, makeButton("120 Hz") { setQualityPreset(78) }, makeButton("MAX") { setQualityPreset(100) }))

            addView(section("RAY TRACING / PATHS"))
            rtButton = makeButton("HWRT ACTIVE", true) { b ->
                rtEnabled = !rtEnabled
                if (!rtEnabled && pathTracing) { pathTracing = false; if (rendererHandle != 0L) nativeSetPathTracing(rendererHandle, false) }
                setButtonState(b, rtEnabled, false, if (rtEnabled) "HWRT ACTIVE" else "HWRT OFF")
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            pathButton = makeButton("PATH TRACE") { b ->
                pathTracing = !pathTracing
                if (pathTracing) { rtEnabled = true; setButtonState(rtButton, true, false, "HWRT ACTIVE"); if (rendererHandle != 0L) nativeSetRt(rendererHandle, true) }
                setButtonState(b, pathTracing, false, if (pathTracing) "PATH TRACE ON" else "PATH TRACE")
                if (rendererHandle != 0L) nativeSetPathTracing(rendererHandle, pathTracing)
            }
            addView(row(rtButton, pathButton))
            addView(TextView(this@NativeCubeActivity).apply { text = "Path Trace is the real multi-bounce inspection mode. It is intentionally separate from the 120 Hz hybrid target."; textSize = 7.4f; setTextColor(0xFFA9BBB5.toInt()); setPadding(dp(2), dp(4), dp(2), dp(4)) })

            addView(section("PHYSICS"))
            physicsButton = makeButton("Physics ON", true) { b -> physicsEnabled = !physicsEnabled; setButtonState(b, physicsEnabled, false, if (physicsEnabled) "Physics ON" else "Physics OFF"); if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled) }
            interactButton = makeButton("Orbit") { b -> interactMode = !interactMode; lightFocusMode = false; setButtonState(b, interactMode, false, if (interactMode) "Interact" else "Orbit"); updateInteractionHint() }
            addView(row(physicsButton, interactButton))
            stressButton = makeButton("Courtyard") { b -> stressMode = !stressMode; setButtonState(b, stressMode, stressMode, if (stressMode) "Stress Lab" else "Courtyard"); if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode) }
            addView(stressButton, LinearLayout.LayoutParams(-1, dp(52)))

            addView(section("GUMMY / SLIME"))
            bounceLabel = label(String.format(Locale.US, "Gummy rebound  %.2f", gummyBounce)); addView(bounceLabel)
            addView(slider(((gummyBounce - .30f) / .66f * 100).toInt()) { p -> gummyBounce = .30f + p / 100f * .66f; bounceLabel.text = String.format(Locale.US, "Gummy rebound  %.2f", gummyBounce); if (rendererHandle != 0L) nativeSetGummyBounce(rendererHandle, gummyBounce) }, LinearLayout.LayoutParams(-1, dp(44)))
            addView(TextView(this@NativeCubeActivity).apply { text = "Green slime uses slower viscoelastic recovery, high surface friction and stronger directional squash."; textSize = 7.4f; setTextColor(0xFFA9BBB5.toInt()); setPadding(dp(2), 0, dp(2), dp(5)) })

            addView(section("LIGHT ORB"))
            addView(row(makeButton("Warm", true) { setLight(1f, .62f, .31f) }, makeButton("White") { setLight(1f, .96f, .88f) }, makeButton("Ice") { setLight(.38f, .68f, 1f) }, makeButton("Neon") { setLight(.75f, .25f, 1f) }))
            addView(makeButton("Move light orb") { b -> lightFocusMode = !lightFocusMode; interactMode = lightFocusMode || interactMode; setButtonState(b, lightFocusMode); setButtonState(interactButton, interactMode, false, if (interactMode) "Interact" else "Orbit"); updateInteractionHint(lightFocusMode) }, LinearLayout.LayoutParams(-1, dp(52)))

            addView(section("ENVIRONMENT"))
            addView(row(makeButton("Day") { applyScenePreset(-.10f, .78f, 1f, .90f, .78f) }, makeButton("Golden") { applyScenePreset(-.38f, .90f, 1f, .52f, .22f) }, makeButton("Night") { applyScenePreset(-1.15f, .94f, .32f, .58f, 1f) }))
            heroButton = makeButton("Volcanic", true) { b -> heroMaterial = 1 - heroMaterial; val v = heroMaterial == 1; setButtonState(b, v, false, if (v) "Volcanic" else "Concrete"); if (rendererHandle != 0L) nativeSetHeroMaterial(rendererHandle, heroMaterial) }
            addView(heroButton, LinearLayout.LayoutParams(-1, dp(52)))
            wetnessLabel = label("Wet pavement  ${(wetness * 100).toInt()}%"); addView(wetnessLabel)
            addView(slider((wetness * 100).toInt()) { wetness = it / 100f; wetnessLabel.text = "Wet pavement  $it%"; if (rendererHandle != 0L) nativeSetWetness(rendererHandle, wetness) }, LinearLayout.LayoutParams(-1, dp(44)))
            exposureLabel = label(String.format(Locale.US, "Exposure  %+.2f EV", exposureEv)); addView(exposureLabel)
            addView(slider((((exposureEv + 1.5f) / 2.5f) * 100).toInt().coerceIn(0, 100)) { p -> exposureEv = -1.5f + p / 100f * 2.5f; exposureLabel.text = String.format(Locale.US, "Exposure  %+.2f EV", exposureEv); if (rendererHandle != 0L) nativeSetExposure(rendererHandle, exposureEv) }, LinearLayout.LayoutParams(-1, dp(44)))

            addView(section("INTERACTION"))
            interactionHint = label("").apply { maxLines = 2 }; addView(interactionHint); updateInteractionHint()
            addView(makeButton("Reset physical scene") { if (rendererHandle != 0L) { nativeResetPhysics(rendererHandle); nativeSetGummyBounce(rendererHandle, gummyBounce); nativeSetLightColor(rendererHandle, lightR, lightG, lightB) } }, LinearLayout.LayoutParams(-1, dp(54)))
        }
        return ScrollView(this).apply { isFillViewport = true; background = rounded(0xF0070C0A.toInt(), 17f, 0x724DEFD0); addView(body) }
    }

    private fun label(textValue: String) = TextView(this).apply { text = textValue; textSize = 8.4f; setTextColor(0xFFC9D9D4.toInt()); setPadding(dp(1), dp(2), dp(1), dp(2)) }
    private fun setLight(r: Float, g: Float, b: Float) { lightR = r; lightG = g; lightB = b; if (rendererHandle != 0L) nativeSetLightColor(rendererHandle, r, g, b); appendLog(String.format(Locale.US, "light -> %.2f %.2f %.2f", r, g, b)) }
    private fun applyScenePreset(ev: Float, wet: Float, r: Float, g: Float, b: Float) { exposureEv = ev; wetness = wet; if (::exposureLabel.isInitialized) exposureLabel.text = String.format(Locale.US, "Exposure  %+.2f EV", exposureEv); if (::wetnessLabel.isInitialized) wetnessLabel.text = "Wet pavement  ${(wetness * 100).toInt()}%"; setLight(r, g, b); if (rendererHandle != 0L) { nativeSetExposure(rendererHandle, exposureEv); nativeSetWetness(rendererHandle, wetness) } }
    private fun setQualityPreset(v: Int) { quality = v.coerceIn(0, 100); qualityLabel.text = "Quality  $quality / 100"; if (::qualitySeek.isInitialized && qualitySeek.progress != quality) qualitySeek.progress = quality; if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality); appendLog("preset -> Q$quality") }
    private fun updateInteractionHint(light: Boolean = false) { if (!::interactionHint.isInitialized) return; interactionHint.text = when { light || lightFocusMode -> "Drag to reposition the physical emissive orb."; interactMode -> "Grab a body, drag naturally, release to throw."; else -> "Orbit camera and pinch to inspect material detail." } }

    private fun buildLogCard(): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(logs, FrameLayout.LayoutParams(-1, -2)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(0xF0070C0A.toInt(), 14f, 0x584DEFD0)
            addView(LinearLayout(this@NativeCubeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(5), dp(5), dp(3))
                addView(TextView(this@NativeCubeActivity).apply { text = "VULKAN TELEMETRY"; textSize = 8f; letterSpacing = .1f; setTextColor(0xFF80CBB5.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton("COPY", "Copy log") { copyLog() }, LinearLayout.LayoutParams(dp(68), dp(48))); addView(iconButton("X", "Close") { togglePanel(logCard, false) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun copyLog() { val text = stats.text.toString() + "\n\nVULKAN LOG\n" + logHistory.joinToString("\n"); getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vulkan Studio log", text)); Toast.makeText(this, "Vulkan log copied", Toast.LENGTH_SHORT).show() }
    private fun absorbNativeLog(text: String) { val lines = text.lineSequence().filter { it.isNotBlank() }.toList(); if (lines.isEmpty()) return; var start = 0; if (lastNativeTail.isNotEmpty()) { val i = lines.indexOfLast { it == lastNativeTail }; if (i >= 0) start = i + 1 }; for (i in start until lines.size) appendLog(lines[i]); lastNativeTail = lines.last() }
    private fun appendLog(line: String) { if (line.isBlank() || logHistory.lastOrNull() == line) return; logHistory.addLast(line); while (logHistory.size > 520) logHistory.removeFirst() }
    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radiusDp.toInt()).toFloat(); if (strokeColor != null) setStroke(dp(1), strokeColor) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enterImmersiveMode() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); destroyRenderer(); super.onDestroy() }
    private fun enterImmersiveMode() { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
    private fun destroyRenderer() { val h = rendererHandle; rendererHandle = 0L; if (h != 0L && nativeLoaded) runCatching { nativeDestroy(h) } }
    private fun showFatal(message: String) { runOnUiThread { stats.text = "Native Vulkan error\n$message"; stats.background = rounded(0xE6321216.toInt(), 13f, 0x99FF776F.toInt()) } }

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
