package com.example.dreamlinux

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class NativeCubeActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var subtitle: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var rendererHandle = 0L
    private var nativeLoaded = false

    private var leftId = -1
    private var rightId = -1
    private var leftStartX = 0f
    private var leftStartY = 0f
    private var leftX = 0f
    private var leftY = 0f
    private var rightLastX = 0f
    private var rightLastY = 0f
    private var rightDownAt = 0L
    private var rightMoved = false
    private var lastFrameNs = 0L
    private var lastStepMs = 0L
    private var footstepIndex = 0
    private var lastEventBlob = ""
    private var subtitleToken = 0

    private lateinit var soundPool: SoundPool
    private val sounds = HashMap<String, Int>()

    private val gameLoop = object : Runnable {
        override fun run() {
            val h = rendererHandle
            val nowNs = System.nanoTime()
            if (lastFrameNs == 0L) lastFrameNs = nowNs
            val dt = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, .04f)
            lastFrameNs = nowNs
            if (h != 0L) {
                val dx = leftX - leftStartX
                val dy = leftY - leftStartY
                val radius = max(surfaceView.width, surfaceView.height) * .16f
                var strafe = (dx / radius).coerceIn(-1f, 1f)
                var forward = (-dy / radius).coerceIn(-1f, 1f)
                val l = sqrt(strafe * strafe + forward * forward)
                if (l > 1f) { strafe /= l; forward /= l }
                nativeMove(h, forward, strafe, dt)
                val moving = abs(strafe) + abs(forward) > .18f
                val now = System.currentTimeMillis()
                if (moving && now - lastStepMs > if (l > .72f) 330 else 430) {
                    lastStepMs = now
                    play(if ((footstepIndex++ and 1) == 0) "step1" else "step2", .48f, 1f)
                }
                val events = runCatching { nativeEvents(h) }.getOrDefault("")
                if (events.isNotBlank() && events != lastEventBlob) {
                    lastEventBlob = events
                    consumeEvents(events)
                }
            }
            handler.postDelayed(this, 8)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }
        nativeLoaded = runCatching { System.loadLibrary("vessel_vulkan"); true }.getOrDefault(false)
        initAudio()

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.OPAQUE)
            background = null
            setOnTouchListener(::onTouch)
        }
        subtitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 2f, Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            alpha = 0f
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addView(subtitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(28); rightMargin = dp(28); bottomMargin = dp(28)
        })
        setContentView(root)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                }
                if (!nativeLoaded || rendererHandle != 0L) return
                rendererHandle = nativeCreate(holder.surface)
                if (rendererHandle != 0L) {
                    nativeSetRt(rendererHandle, true)
                    nativeSetPathTracing(rendererHandle, false)
                    nativeSetQuality(rendererHandle, 100)
                    showSubtitle("Wake up. Emergency power is failing. Find the breaker room.", 5200)
                    play("alarm", .30f, .82f)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (rendererHandle != 0L) nativeResize(rendererHandle, max(width, 1), max(height, 1))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (rendererHandle != 0L) nativeDestroy(rendererHandle)
                rendererHandle = 0L
            }
        })
        handler.post(gameLoop)
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val w = max(v.width, 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                if (x < w * .46f && leftId == -1) {
                    leftId = id; leftStartX = x; leftStartY = y; leftX = x; leftY = y
                } else if (rightId == -1) {
                    rightId = id; rightLastX = x; rightLastY = y; rightDownAt = System.currentTimeMillis(); rightMoved = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i); val y = e.getY(i)
                    if (id == leftId) { leftX = x; leftY = y }
                    if (id == rightId) {
                        val dx = x - rightLastX; val dy = y - rightLastY
                        if (abs(dx) + abs(dy) > 2f) rightMoved = true
                        if (rendererHandle != 0L) nativeLook(rendererHandle, dx, dy)
                        rightLastX = x; rightLastY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (id == leftId) { leftId = -1; leftX = leftStartX; leftY = leftStartY }
                if (id == rightId) {
                    val tap = !rightMoved && System.currentTimeMillis() - rightDownAt < 230
                    rightId = -1
                    if (tap && rendererHandle != 0L) nativeInteract(rendererHandle)
                }
            }
        }
        return true
    }

    private fun initAudio() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        soundPool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        sounds["step1"] = soundPool.load(this, R.raw.footstep_concrete_1, 1)
        sounds["step2"] = soundPool.load(this, R.raw.footstep_concrete_2, 1)
        sounds["door"] = soundPool.load(this, R.raw.door_latch, 1)
        sounds["breaker"] = soundPool.load(this, R.raw.breaker_trip, 1)
        sounds["metal"] = soundPool.load(this, R.raw.impact_metal, 1)
        sounds["soft"] = soundPool.load(this, R.raw.impact_soft, 1)
        sounds["glass"] = soundPool.load(this, R.raw.impact_glass, 1)
        sounds["alarm"] = soundPool.load(this, R.raw.alarm, 1)
        sounds["power"] = soundPool.load(this, R.raw.power, 1)
    }

    private fun consumeEvents(blob: String) {
        blob.lines().takeLast(16).forEach { line ->
            when {
                line.startsWith("SUB:") -> showSubtitle(line.substringAfter("SUB:"), 4200)
                line.startsWith("SFX:DOOR") -> play("door", .78f, 1f)
                line.startsWith("SFX:BREAKER") -> play("breaker", .86f, .96f)
                line.startsWith("SFX:POWER") -> play("power", .82f, .92f)
                line.startsWith("SFX:METAL") -> play("metal", .62f, .92f + Math.random().toFloat() * .12f)
                line.startsWith("SFX:SOFT") -> play("soft", .64f, .88f + Math.random().toFloat() * .14f)
                line.startsWith("SFX:GLASS") -> play("glass", .72f, .94f)
                line.startsWith("SFX:ALARM") -> play("alarm", .36f, .82f)
            }
        }
    }

    private fun showSubtitle(text: String, duration: Long) {
        val token = ++subtitleToken
        subtitle.animate().cancel()
        subtitle.text = text
        subtitle.alpha = 0f
        subtitle.animate().alpha(1f).setDuration(180).start()
        handler.postDelayed({ if (token == subtitleToken) subtitle.animate().alpha(0f).setDuration(350).start() }, duration)
    }

    private fun play(name: String, volume: Float, rate: Float) {
        val id = sounds[name] ?: return
        soundPool.play(id, volume, volume, 1, 0, rate.coerceIn(.5f, 2f))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (rendererHandle != 0L) nativeDestroy(rendererHandle)
        rendererHandle = 0L
        soundPool.release()
        super.onDestroy()
    }

    private external fun nativeCreate(surface: Surface): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeLook(handle: Long, dx: Float, dy: Float)
    private external fun nativeMove(handle: Long, forward: Float, strafe: Float, dt: Float)
    private external fun nativeInteract(handle: Long)
    private external fun nativeEvents(handle: Long): String
    private external fun nativeSetRt(handle: Long, enabled: Boolean)
    private external fun nativeSetPathTracing(handle: Long, enabled: Boolean)
    private external fun nativeSetQuality(handle: Long, quality: Int)
}