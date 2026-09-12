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
    private lateinit var lightIntensityLabel: TextView
    private lateinit var lightHazeLabel: TextView
    private lateinit var lightBounceLabel: TextView
    private lateinit var rtButton: Button
    private lateinit var pathButton: Button
    private lateinit var stressButton: Button
    private lateinit var physicsButton: Button
    private lateinit var interactButton: Button
    private lateinit var heroButton: Button
    private lateinit var moveLightButton: Button
    private lateinit var interactionHint: TextView

    private val qualityButtons = mutableListOf<Button>()
    private val lightButtons = mutableListOf<Button>()
    private val sceneButtons = mutableListOf<Button>()
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
    private var lightIntensity = 1.0f
    private var lightHaze = .35f
    private var lightBounce = .55f

    private val statsPoll = object : Runnable {
        override fun run() {
            val h = rendererHandle
            if (h != 0L && nativeLoaded) {
                val status = runCatching { nativeStatus(h) }
                    .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                val nativeLog = runCatching { nativeLogs(h) }.getOrDefault("")
                val isError = status.startsWith("ERROR:")
                stats.background = panel(
                    if (isError) 0xE02C1111.toInt() else 0xD6080D0C.toInt(),
                    if (isError) 0xFFFF6C63.toInt() else 0xFF277C6B.toInt(),
                    5
                )
                val gesture = when {
                    lightFocusMode -> "LIGHT · drag / throw orb"
                    interactMode -> "PHYSICS · drag / throw"
                    else -> "CAMERA · orbit / pinch"
                }
                val lines = status.lines()
                stats.text = if (statsExpanded) "$status\n$gesture" else lines.firstOrNull().orEmpty()
                if (::rtButton.isInitialized) {
                    val bypassed = status.contains("RT BYPASSED") || status.contains("RT OFF")
                    setButtonState(
                        rtButton,
                        rtEnabled && !bypassed,
                        rtEnabled && bypassed,
                        if (!rtEnabled) "HWRT OFF" else if (bypassed) "HWRT LIMITED" else "HWRT ACTIVE"
                    )
                }
                if (::pathButton.isInitialized) {
                    setButtonState(pathButton, pathTracing, false, if (pathTracing) "PATH QUALITY ON" else "PATH QUALITY")
                }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }
        nativeLoaded = try {
            System.loadLibrary("vessel_vulkan")
            true
        } catch (_: Throwable) {
            false
        }

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = 8.4f
            setLineSpacing(0f, 1.0f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            text = "Vulkan Studio v8.0\nInitializing Adreno renderer…"
            background = panel(0xD6080D0C.toInt(), 0xFF277C6B.toInt(), 5)
            setOnClickListener { statsExpanded = !statsExpanded }
        }
        logs = TextView(this).apply {
            setTextColor(0xFFE2F0EC.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 7.4f
            setPadding(dp(9), dp(7), dp(9), dp(8))
            setTextIsSelectable(true)
            text = "renderer not started"
        }
        surfaceView = SurfaceView(this).apply {
            background = null
            holder.setFormat(PixelFormat.OPAQUE)
        }

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!interactMode && !lightFocusMode && rendererHandle != 0L) {
                    nativeZoom(rendererHandle, detector.scaleFactor)
                }
                return true
            }
        })

        surfaceView.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            val h = rendererHandle
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    if (h != 0L && (interactMode || lightFocusMode)) {
                        val nx = e.x / max(surfaceView.width, 1)
                        val ny = e.y / max(surfaceView.height, 1)
                        if (lightFocusMode) nativeGrabLightStart(h, nx, ny) else nativeGrabStart(h, nx, ny)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && e.pointerCount == 1 && h != 0L) {
                    if (interactMode || lightFocusMode) {
                        nativeGrabMove(h, e.x / max(surfaceView.width, 1), e.y / max(surfaceView.height, 1))
                    } else {
                        nativeRotate(
                            h,
                            -(e.x - lastX) * (132f / max(surfaceView.width, 1)),
                            -(e.y - lastY) * (96f / max(surfaceView.height, 1))
                        )
                    }
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if ((interactMode || lightFocusMode) && h != 0L) nativeGrabEnd(h)
                }
            }
            true
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                }
                if (!nativeLoaded || rendererHandle != 0L) return
                rendererHandle = runCatching { nativeCreate(holder.surface) }.getOrDefault(0L)
                if (rendererHandle == 0L) {
                    showFatal("Vulkan renderer creation failed")
                    return
                }
                applyAllNativeSettings()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                }
                if (rendererHandle != 0L) nativeResize(rendererHandle, max(width, 1), max(height, 1))
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = destroyRenderer()
        })

        controlsCard = buildControls().apply {
            visibility = View.GONE
            alpha = 0f
            translationY = dp(10).toFloat()
        }
        logCard = buildLogCard().apply {
            visibility = View.GONE
            alpha = 0f
            translationY = dp(10).toFloat()
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(stats, FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(7)
            topMargin = dp(7)
        })
        val dock = buildQuickBar()
        root.addView(dock, FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(7)
            bottomMargin = dp(7)
        })
        val panelWidth = minOf(dp(306), (resources.displayMetrics.widthPixels * .34f).toInt())
        val panelHeight = resources.displayMetrics.heightPixels - dp(72)
        root.addView(controlsCard, FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(7)
            bottomMargin = dp(59)
        })
        root.addView(logCard, FrameLayout.LayoutParams(
            minOf(dp(390), (resources.displayMetrics.widthPixels * .44f).toInt()),
            dp(180),
            Gravity.BOTTOM or Gravity.START
        ).apply {
            leftMargin = dp(7)
            bottomMargin = dp(59)
        })
        setContentView(root)
        handler.post(statsPoll)
    }

    private fun applyAllNativeSettings() {
        val h = rendererHandle
        if (h == 0L) return
        nativeResize(h, max(surfaceView.width, 1), max(surfaceView.height, 1))
        nativeSetQuality(h, quality)
        nativeSetStress(h, stressMode)
        nativeSetPhysics(h, physicsEnabled)
        nativeSetRt(h, rtEnabled)
        nativeSetWetness(h, wetness)
        nativeSetExposure(h, exposureEv)
        nativeSetHeroMaterial(h, heroMaterial)
        nativeSetGummyBounce(h, gummyBounce)
        nativeSetLightIntensity(h, lightIntensity)
        nativeSetLightHaze(h, lightHaze)
        nativeSetLightBounce(h, lightBounce)
        nativeSetLightColor(h, lightR, lightG, lightB)
        nativeSetPathTracing(h, pathTracing)
    }

    private fun buildQuickBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = panel(0xE4070C0A.toInt(), 0xFF245F53.toInt(), 7)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(iconButton("SET", "Renderer controls") {
            if (::logCard.isInitialized) logCard.visibility = View.GONE
            togglePanel(controlsCard)
        }, LinearLayout.LayoutParams(dp(70), dp(44)))
        addView(iconButton("LOG", "Vulkan log") {
            if (::controlsCard.isInitialized) controlsCard.visibility = View.GONE
            togglePanel(logCard)
        }, LinearLayout.LayoutParams(dp(70), dp(44)))
        addView(iconButton("RESET", "Reset physical scene") { resetScene() }, LinearLayout.LayoutParams(dp(60), dp(44)))
    }

    private fun iconButton(label: String, description: String, click: () -> Unit) = TextView(this).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = 8.4f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextColor(0xFFF2FBF8.toInt())
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = panel(0xC6101714.toInt(), 0xFF263A34.toInt(), 5)
        setOnClickListener { click() }
    }

    private fun togglePanel(view: View) {
        if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(100).withEndAction {
                view.visibility = View.GONE
            }.start()
        } else {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = dp(8).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(130).start()
        }
    }

    private fun makeButton(label: String, active: Boolean = false, click: (Button) -> Unit) = Button(this).apply {
        text = label
        textSize = 8.1f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = dp(42)
        minWidth = dp(44)
        setPadding(dp(5), dp(2), dp(5), dp(2))
        stateListAnimator = null
        setButtonState(this, active)
        setOnClickListener {
            alpha = .72f
            animate().alpha(1f).setDuration(85).start()
            click(this)
        }
    }

    private fun setButtonState(button: Button, active: Boolean, warning: Boolean = false, label: String? = null) {
        if (label != null) button.text = label
        button.isSelected = active
        button.background = panel(
            when {
                warning -> 0xE079431A.toInt()
                active -> 0xE214695C.toInt()
                else -> 0xE20A100E.toInt()
            },
            when {
                warning -> 0xFFDB8E49.toInt()
                active -> 0xFF43D2B3.toInt()
                else -> 0xFF31443E.toInt()
            },
            6
        )
    }

    private fun selectGroup(group: List<Button>, selected: Button) {
        group.forEach { setButtonState(it, it === selected) }
    }

    private fun buildControls(): ScrollView {
        fun section(t: String) = TextView(this).apply {
            text = t
            textSize = 6.9f
            letterSpacing = .14f
            setTextColor(0xFF6BC4AC.toInt())
            setPadding(0, dp(8), 0, dp(2))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (i < views.lastIndex) marginEnd = dp(4)
                })
            }
        }
        fun slider(value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
            max = 100
            progress = value
            minHeight = dp(34)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(11))
            background = panel(0xF2070C0A.toInt(), 0xFF286C5D.toInt(), 8)

            addView(TextView(this@NativeCubeActivity).apply {
                text = "VULKAN STUDIO  //  v8.0"
                textSize = 11.8f
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@NativeCubeActivity).apply {
                text = "ADRENO 840 · 120 HZ · STABLE HWRT · PATH QUALITY"
                textSize = 6.5f
                letterSpacing = .06f
                setTextColor(0xFF839A93.toInt())
            })

            addView(section("RENDER"))
            qualityLabel = label("QUALITY  $quality / 100")
            addView(qualityLabel)
            qualitySeek = slider(quality) {
                quality = it
                qualityLabel.text = "QUALITY  $quality / 100"
                if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
            }
            addView(qualitySeek, LinearLayout.LayoutParams(-1, dp(34)))
            val qEff = makeButton("EFFICIENT") { setQualityPreset(55); selectGroup(qualityButtons, it) }
            val q120 = makeButton("120 HZ") { setQualityPreset(78); selectGroup(qualityButtons, it) }
            val qMax = makeButton("MAX", true) { setQualityPreset(100); selectGroup(qualityButtons, it) }
            qualityButtons.addAll(listOf(qEff, q120, qMax))
            addView(row(qEff, q120, qMax))

            addView(section("LIGHTING"))
            rtButton = makeButton("HWRT ACTIVE", true) { b ->
                rtEnabled = !rtEnabled
                if (!rtEnabled && pathTracing) {
                    pathTracing = false
                    setButtonState(pathButton, false, false, "PATH QUALITY")
                    if (rendererHandle != 0L) nativeSetPathTracing(rendererHandle, false)
                }
                setButtonState(b, rtEnabled, false, if (rtEnabled) "HWRT ACTIVE" else "HWRT OFF")
                if (rendererHandle != 0L) nativeSetRt(rendererHandle, rtEnabled)
            }
            pathButton = makeButton("PATH QUALITY") { b ->
                pathTracing = !pathTracing
                if (pathTracing) {
                    rtEnabled = true
                    setButtonState(rtButton, true, false, "HWRT ACTIVE")
                    if (rendererHandle != 0L) nativeSetRt(rendererHandle, true)
                }
                setButtonState(b, pathTracing, false, if (pathTracing) "PATH QUALITY ON" else "PATH QUALITY")
                if (rendererHandle != 0L) nativeSetPathTracing(rendererHandle, pathTracing)
            }
            addView(row(rtButton, pathButton))
            addView(note("Hybrid uses stable sparse ray queries. Path Quality uses one clamped indirect ray at 0.60x with stable IBL to suppress the previous salt-and-pepper noise."))

            addView(section("PHYSICS"))
            physicsButton = makeButton("PHYSICS ON", true) { b ->
                physicsEnabled = !physicsEnabled
                setButtonState(b, physicsEnabled, false, if (physicsEnabled) "PHYSICS ON" else "PHYSICS OFF")
                if (rendererHandle != 0L) nativeSetPhysics(rendererHandle, physicsEnabled)
            }
            interactButton = makeButton("ORBIT") { b ->
                interactMode = !interactMode
                lightFocusMode = false
                setButtonState(b, interactMode, false, if (interactMode) "INTERACT" else "ORBIT")
                if (::moveLightButton.isInitialized) setButtonState(moveLightButton, false)
                updateInteractionHint()
            }
            addView(row(physicsButton, interactButton))
            stressButton = makeButton("COURTYARD") { b ->
                stressMode = !stressMode
                setButtonState(b, stressMode, stressMode, if (stressMode) "STRESS LAB" else "COURTYARD")
                if (rendererHandle != 0L) nativeSetStress(rendererHandle, stressMode)
            }
            addView(stressButton, LinearLayout.LayoutParams(-1, dp(42)))

            addView(section("SOFT BODY"))
            bounceLabel = label(String.format(Locale.US, "GUMMY REBOUND  %.2f", gummyBounce))
            addView(bounceLabel)
            addView(slider(((gummyBounce - .38f) / .56f * 100).toInt().coerceIn(0, 100)) { p ->
                gummyBounce = .38f + p / 100f * .56f
                bounceLabel.text = String.format(Locale.US, "GUMMY REBOUND  %.2f", gummyBounce)
                if (rendererHandle != 0L) nativeSetGummyBounce(rendererHandle, gummyBounce)
            }, LinearLayout.LayoutParams(-1, dp(34)))
            addView(note("Rubber/gummy use collision-matched deformation. Slime relaxes while dragged and stays inside its physics envelope instead of visually clipping."))

            addView(section("LIGHT ORB"))
            val warm = makeButton("WARM", true) { setLight(1f, .62f, .31f); selectGroup(lightButtons, it) }
            val white = makeButton("WHITE") { setLight(1f, .96f, .88f); selectGroup(lightButtons, it) }
            val ice = makeButton("ICE") { setLight(.38f, .68f, 1f); selectGroup(lightButtons, it) }
            val neon = makeButton("NEON") { setLight(.75f, .25f, 1f); selectGroup(lightButtons, it) }
            lightButtons.addAll(listOf(warm, white, ice, neon))
            addView(row(warm, white, ice, neon))

            lightIntensityLabel = label(String.format(Locale.US, "INTENSITY  %.2fx", lightIntensity))
            addView(lightIntensityLabel)
            addView(slider(((lightIntensity - .25f) / 2f * 100).toInt().coerceIn(0, 100)) { p ->
                lightIntensity = .25f + p / 100f * 2f
                lightIntensityLabel.text = String.format(Locale.US, "INTENSITY  %.2fx", lightIntensity)
                if (rendererHandle != 0L) nativeSetLightIntensity(rendererHandle, lightIntensity)
            }, LinearLayout.LayoutParams(-1, dp(34)))

            lightHazeLabel = label("HAZE  ${(lightHaze * 100).toInt()}%")
            addView(lightHazeLabel)
            addView(slider((lightHaze * 100).toInt()) { p ->
                lightHaze = p / 100f
                lightHazeLabel.text = "HAZE  $p%"
                if (rendererHandle != 0L) nativeSetLightHaze(rendererHandle, lightHaze)
            }, LinearLayout.LayoutParams(-1, dp(34)))

            lightBounceLabel = label(String.format(Locale.US, "ORB BOUNCE  %.2f", lightBounce))
            addView(lightBounceLabel)
            addView(slider((lightBounce / .90f * 100).toInt()) { p ->
                lightBounce = p / 100f * .90f
                lightBounceLabel.text = String.format(Locale.US, "ORB BOUNCE  %.2f", lightBounce)
                if (rendererHandle != 0L) nativeSetLightBounce(rendererHandle, lightBounce)
            }, LinearLayout.LayoutParams(-1, dp(34)))

            moveLightButton = makeButton("MOVE / THROW LIGHT") { b ->
                lightFocusMode = !lightFocusMode
                interactMode = lightFocusMode
                setButtonState(b, lightFocusMode)
                setButtonState(interactButton, interactMode, false, if (interactMode) "INTERACT" else "ORBIT")
                updateInteractionHint(lightFocusMode)
            }
            addView(moveLightButton, LinearLayout.LayoutParams(-1, dp(42)))

            addView(section("ENVIRONMENT"))
            val day = makeButton("DAY") { applyScenePreset(-.10f, .82f, 1f, .90f, .78f); selectGroup(sceneButtons, it) }
            val golden = makeButton("GOLDEN", true) { applyScenePreset(-.38f, .92f, 1f, .52f, .22f); selectGroup(sceneButtons, it) }
            val night = makeButton("NIGHT") { applyScenePreset(-.92f, .97f, 1f, .44f, .16f); selectGroup(sceneButtons, it) }
            sceneButtons.addAll(listOf(day, golden, night))
            addView(row(day, golden, night))

            heroButton = makeButton("VOLCANIC", true) { b ->
                heroMaterial = 1 - heroMaterial
                val volcanic = heroMaterial == 1
                setButtonState(b, volcanic, false, if (volcanic) "VOLCANIC" else "CONCRETE")
                if (rendererHandle != 0L) nativeSetHeroMaterial(rendererHandle, heroMaterial)
            }
            addView(heroButton, LinearLayout.LayoutParams(-1, dp(42)))

            wetnessLabel = label("WET STONE  ${(wetness * 100).toInt()}%")
            addView(wetnessLabel)
            addView(slider((wetness * 100).toInt()) {
                wetness = it / 100f
                wetnessLabel.text = "WET STONE  $it%"
                if (rendererHandle != 0L) nativeSetWetness(rendererHandle, wetness)
            }, LinearLayout.LayoutParams(-1, dp(34)))

            exposureLabel = label(String.format(Locale.US, "EXPOSURE  %+.2f EV", exposureEv))
            addView(exposureLabel)
            addView(slider((((exposureEv + 1.5f) / 2.5f) * 100).toInt().coerceIn(0, 100)) { p ->
                exposureEv = -1.5f + p / 100f * 2.5f
                exposureLabel.text = String.format(Locale.US, "EXPOSURE  %+.2f EV", exposureEv)
                if (rendererHandle != 0L) nativeSetExposure(rendererHandle, exposureEv)
            }, LinearLayout.LayoutParams(-1, dp(34)))

            addView(section("CONTROL"))
            interactionHint = label("").apply { maxLines = 2 }
            addView(interactionHint)
            updateInteractionHint()
            addView(makeButton("RESET PHYSICS") { resetScene() }, LinearLayout.LayoutParams(-1, dp(42)))
        }

        return ScrollView(this).apply {
            isFillViewport = true
            background = panel(0xF2070C0A.toInt(), 0xFF286C5D.toInt(), 8)
            addView(body)
        }
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 7.8f
        setTextColor(0xFFC7D8D2.toInt())
        setPadding(dp(1), dp(1), dp(1), dp(1))
    }

    private fun note(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 6.8f
        setTextColor(0xFF9FB2AC.toInt())
        setPadding(dp(1), dp(3), dp(1), dp(2))
    }

    private fun setLight(r: Float, g: Float, b: Float) {
        lightR = r
        lightG = g
        lightB = b
        if (rendererHandle != 0L) nativeSetLightColor(rendererHandle, r, g, b)
        appendLog(String.format(Locale.US, "light -> %.2f %.2f %.2f", r, g, b))
    }

    private fun applyScenePreset(ev: Float, wet: Float, r: Float, g: Float, b: Float) {
        exposureEv = ev
        wetness = wet
        if (::exposureLabel.isInitialized) exposureLabel.text = String.format(Locale.US, "EXPOSURE  %+.2f EV", exposureEv)
        if (::wetnessLabel.isInitialized) wetnessLabel.text = "WET STONE  ${(wetness * 100).toInt()}%"
        setLight(r, g, b)
        if (rendererHandle != 0L) {
            nativeSetExposure(rendererHandle, exposureEv)
            nativeSetWetness(rendererHandle, wetness)
        }
    }

    private fun setQualityPreset(v: Int) {
        quality = v.coerceIn(0, 100)
        qualityLabel.text = "QUALITY  $quality / 100"
        if (::qualitySeek.isInitialized && qualitySeek.progress != quality) qualitySeek.progress = quality
        if (rendererHandle != 0L) nativeSetQuality(rendererHandle, quality)
        appendLog("preset -> Q$quality")
    }

    private fun resetScene() {
        val h = rendererHandle
        if (h != 0L) {
            nativeResetPhysics(h)
            nativeSetGummyBounce(h, gummyBounce)
            nativeSetLightIntensity(h, lightIntensity)
            nativeSetLightHaze(h, lightHaze)
            nativeSetLightBounce(h, lightBounce)
            nativeSetLightColor(h, lightR, lightG, lightB)
        }
    }

    private fun updateInteractionHint(light: Boolean = false) {
        if (!::interactionHint.isInitialized) return
        interactionHint.text = when {
            light || lightFocusMode -> "Drag the light orb, then release to throw it. Bounce, intensity and haze are configurable above."
            interactMode -> "Grab a body, drag naturally, release quickly to fling; soft bodies stay inside their collision envelope."
            else -> "Orbit the camera and pinch to inspect wet materials, fire lighting and shadows."
        }
    }

    private fun buildLogCard(): LinearLayout {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logs, FrameLayout.LayoutParams(-1, -2))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(3), dp(3), dp(3))
            addView(TextView(this@NativeCubeActivity).apply {
                text = "VULKAN TELEMETRY"
                textSize = 7.6f
                letterSpacing = .1f
                setTextColor(0xFF70C8B0.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton("COPY", "Copy log") { copyLog() }, LinearLayout.LayoutParams(dp(66), dp(40)))
            addView(iconButton("×", "Close") { togglePanel(logCard) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(0xF2070C0A.toInt(), 0xFF286C5D.toInt(), 8)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun copyLog() {
        val text = stats.text.toString() + "\n\nVULKAN LOG\n" + logHistory.joinToString("\n")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vulkan Studio log", text))
        Toast.makeText(this, "Vulkan log copied", Toast.LENGTH_SHORT).show()
    }

    private fun absorbNativeLog(text: String) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return
        var start = 0
        if (lastNativeTail.isNotEmpty()) {
            val i = lines.indexOfLast { it == lastNativeTail }
            if (i >= 0) start = i + 1
        }
        for (i in start until lines.size) appendLog(lines[i])
        lastNativeTail = lines.last()
    }

    private fun appendLog(line: String) {
        if (line.isBlank() || logHistory.lastOrNull() == line) return
        logHistory.addLast(line)
        while (logHistory.size > 520) logHistory.removeFirst()
    }

    private fun panel(color: Int, strokeColor: Int? = null, radius: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
            stats.text = "Native Vulkan error\n$message"
            stats.background = panel(0xE6321216.toInt(), 0xFFFF776F.toInt(), 5)
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
    private external fun nativeSetPathTracing(handle: Long, enabled: Boolean)
    private external fun nativeSetQuality(handle: Long, quality: Int)
    private external fun nativeSetWetness(handle: Long, value: Float)
    private external fun nativeSetExposure(handle: Long, value: Float)
    private external fun nativeSetHeroMaterial(handle: Long, material: Int)
    private external fun nativeSetGummyBounce(handle: Long, value: Float)
    private external fun nativeSetLightColor(handle: Long, r: Float, g: Float, b: Float)
    private external fun nativeSetLightIntensity(handle: Long, value: Float)
    private external fun nativeSetLightHaze(handle: Long, value: Float)
    private external fun nativeSetLightBounce(handle: Long, value: Float)
    private external fun nativeResetPhysics(handle: Long)
    private external fun nativeGrabStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabLightStart(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabMove(handle: Long, nx: Float, ny: Float)
    private external fun nativeGrabEnd(handle: Long)
    private external fun nativeStatus(handle: Long): String
    private external fun nativeLogs(handle: Long): String
}