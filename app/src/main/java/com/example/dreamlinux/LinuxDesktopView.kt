package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.min

/** Android Surface + Protocol 39 virtio-input + guest cursor overlay. */
class LinuxDesktopView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: LinuxDesktopView? = null
        private const val LEFT = 0x110
        private const val RIGHT = 0x111
        private const val MIDDLE = 0x112
    }

    private val viewConfig = ViewConfiguration.get(context)
    private val touchSlop = viewConfig.scaledTouchSlop.toFloat()
    private val touchSlopSq = touchSlop * touchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    @Volatile private var pointerMode = PointerMode.TRACKPAD
    private var surfaceAttached = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var maxPointers = 1
    private var scrollX = 0f
    private var scrollY = 0f
    private var dragging = false
    private var movedBeyondTap = false
    private var predictedCursorX = Float.NaN
    private var predictedCursorY = Float.NaN
    private var cursorPredictionUntil = 0L

    private val surfaceView = object : SurfaceView(context) {
        override fun onCheckIsTextEditor() = true
        override fun onCreateInputConnection(a: EditorInfo): InputConnection {
            a.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            a.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            return object : BaseInputConnection(this, false) {
                override fun commitText(t: CharSequence?, n: Int): Boolean {
                    t?.toString()?.takeIf { it.isNotEmpty() }?.let(VesselVirtioInput::text)
                    return true
                }

                override fun sendKeyEvent(e: KeyEvent): Boolean = handleKey(e) || super.sendKeyEvent(e)

                override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                    repeat(before.coerceAtMost(32)) { VesselVirtioInput.tapKey(14) }
                    repeat(after.coerceAtMost(32)) { VesselVirtioInput.tapKey(111) }
                    return true
                }
            }
        }
    }.apply {
        setBackgroundColor(Color.TRANSPARENT)
        holder.addCallback(this@LinuxDesktopView)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = false
        setOnTouchListener { _, event -> touch(event) }
        setOnGenericMotionListener { _, event -> generic(event) }
        setOnKeyListener { _, _, event -> handleKey(event) }
    }

    private val cursorView = object : View(context), Choreographer.FrameCallback {
        private var lastSerial = -1L
        private var bitmap: Bitmap? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDetachedFromWindow() {
            Choreographer.getInstance().removeFrameCallback(this)
            super.onDetachedFromWindow()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (pointerMode == PointerMode.TRACKPAD) {
                val serial = VesselWaylandPresenter.cursorSerial()
                if (serial != lastSerial) {
                    lastSerial = serial
                    val pixels = VesselWaylandPresenter.cursorPixels()
                    if (pixels.size == 4096) bitmap = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
                }
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Direct touch behaves like a real touchscreen: no mouse cursor is drawn.
            if (pointerMode != PointerMode.TRACKPAD || !VesselWaylandPresenter.cursorVisible()) return
            val b = bitmap ?: return
            val gw = VesselWaylandPresenter.guestWidth().coerceAtLeast(1)
            val gh = VesselWaylandPresenter.guestHeight().coerceAtLeast(1)
            val scale = min(width.toFloat() / gw, height.toFloat() / gh)
            val ox = (width - gw * scale) / 2f
            val oy = (height - gh * scale) / 2f
            val predictionActive = SystemClock.uptimeMillis() <= cursorPredictionUntil && predictedCursorX.isFinite() && predictedCursorY.isFinite()
            val cursorX = if (predictionActive) predictedCursorX else VesselWaylandPresenter.cursorX().toFloat()
            val rawY = if (predictionActive) predictedCursorY else VesselWaylandPresenter.cursorY().toFloat()
            val guestY = if (VesselExperimentConfig.invertPointerY(context)) gh.toFloat() - rawY else rawY
            val x = ox + (cursorX - VesselWaylandPresenter.cursorHotX()) * scale
            val y = oy + (guestY - VesselWaylandPresenter.cursorHotY()) * scale
            canvas.drawBitmap(b, null, android.graphics.RectF(x, y, x + 64 * scale, y + 64 * scale), null)
        }
    }.apply {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cursorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
    }

    override fun onDetachedFromWindow() {
        releaseDrag()
        if (active === this) active = null
        detachSurfaceOnce()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceView.requestFocus()
        if (!surfaceAttached) {
            VesselWaylandPresenter.attach(holder.surface)
            surfaceAttached = true
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // A resize can keep the same ANativeWindow object. Tell native Vulkan the
        // new extent without detaching the retained AHardwareBuffer frame.
        VesselWaylandPresenter.surfaceChanged(width, height)
        val refresh = (display?.supportedModes?.maxOfOrNull { it.refreshRate }
            ?: display?.refreshRate ?: 60f)
            .coerceAtMost(VesselExperimentConfig.refreshHz(context).toFloat())
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(refresh, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseDrag()
        detachSurfaceOnce()
    }

    private fun detachSurfaceOnce() {
        if (surfaceAttached) {
            surfaceAttached = false
            VesselWaylandPresenter.detach()
        }
    }

    fun setPointerMode(mode: PointerMode) {
        if (pointerMode != mode) {
            releaseDrag()
            VesselVirtioInput.resetFractions()
            pointerMode = mode
            cursorView.invalidate()
        }
    }

    fun showKeyboard() {
        surfaceView.requestFocus()
        surfaceView.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(surfaceView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun tapKey(code: Int) = VesselVirtioInput.tapKey(code)

    private fun mapped(x: Float, y: Float): Pair<Float, Float> {
        val gw = VesselWaylandPresenter.guestWidth().coerceAtLeast(1)
        val gh = VesselWaylandPresenter.guestHeight().coerceAtLeast(1)
        val scale = min(width.toFloat() / gw, height.toFloat() / gh).coerceAtLeast(0.0001f)
        val ox = (width - gw * scale) / 2f
        val oy = (height - gh * scale) / 2f
        val nx = ((x - ox) / (gw * scale)).coerceIn(0f, 1f)
        val rawY = ((y - oy) / (gh * scale)).coerceIn(0f, 1f)
        val ny = if (VesselExperimentConfig.invertPointerY(context)) 1f - rawY else rawY
        return nx to ny
    }

    private fun displacementSq(x: Float, y: Float): Float {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy
    }

    private fun beginCursorPrediction() {
        predictedCursorX = VesselWaylandPresenter.cursorX().toFloat()
        predictedCursorY = VesselWaylandPresenter.cursorY().toFloat()
        cursorPredictionUntil = SystemClock.uptimeMillis() + 50L
    }

    private fun predictCursorRelative(dx: Float, dy: Float) {
        if (!predictedCursorX.isFinite() || !predictedCursorY.isFinite() || SystemClock.uptimeMillis() > cursorPredictionUntil) {
            beginCursorPrediction()
        }
        val gw = VesselWaylandPresenter.guestWidth().coerceAtLeast(1)
        val gh = VesselWaylandPresenter.guestHeight().coerceAtLeast(1)
        val scale = min(width.toFloat() / gw, height.toFloat() / gh).coerceAtLeast(0.0001f)
        predictedCursorX = (predictedCursorX + (dx * 1.25f) / scale).coerceIn(0f, gw.toFloat())
        predictedCursorY = (predictedCursorY + (dy * 1.25f * if (VesselExperimentConfig.invertPointerY(context)) -1f else 1f) / scale).coerceIn(0f, gh.toFloat())
        cursorPredictionUntil = SystemClock.uptimeMillis() + 50L
        cursorView.invalidate()
    }

    private fun touch(e: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        surfaceView.requestFocus()

        if (pointerMode == PointerMode.DIRECT) {
            releaseDrag()
            val (x, y) = mapped(e.x, e.y)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> VesselVirtioInput.absoluteNormalized(x, y, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselVirtioInput.absoluteNormalized(x, y, false)
            }
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseDrag()
                lastX = e.x
                lastY = e.y
                downX = e.x
                downY = e.y
                downAt = SystemClock.uptimeMillis()
                maxPointers = 1
                movedBeyondTap = false
                scrollX = e.x
                scrollY = e.y
                beginCursorPrediction()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                scrollX = avgX(e)
                scrollY = avgY(e)
            }

            MotionEvent.ACTION_MOVE -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.pointerCount >= 2) {
                    val ax = avgX(e)
                    val ay = avgY(e)
                    val dx = ax - scrollX
                    val dy = ay - scrollY
                    if (dx * dx + dy * dy > touchSlopSq / 9f) movedBeyondTap = true
                    VesselVirtioInput.scrollPrecise(-dx / 8f, -dy / 8f)
                    scrollX = ax
                    scrollY = ay
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    predictCursorRelative(dx, dy)
                    if (displacementSq(e.x, e.y) > touchSlopSq) movedBeyondTap = true
                    if (!dragging && !movedBeyondTap && SystemClock.uptimeMillis() - downAt >= longPressMs) {
                        VesselVirtioInput.button(LEFT, true)
                        dragging = true
                    }
                    VesselVirtioInput.relative(dx * 1.25f, dy * 1.25f * if (VesselExperimentConfig.invertPointerY(context)) -1f else 1f)
                    lastX = e.x
                    lastY = e.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                val remaining = (0 until e.pointerCount).filter { it != e.actionIndex }
                if (remaining.isNotEmpty()) {
                    lastX = e.getX(remaining[0])
                    lastY = e.getY(remaining[0])
                }
            }

            MotionEvent.ACTION_UP -> {
                cursorPredictionUntil = SystemClock.uptimeMillis() + 50L
                if (dragging) {
                    releaseDrag()
                } else if (!movedBeyondTap && displacementSq(e.x, e.y) <= touchSlopSq) {
                    val button = if (maxPointers >= 2) RIGHT else LEFT
                    VesselVirtioInput.button(button, true)
                    VesselVirtioInput.button(button, false)
                }
            }

            MotionEvent.ACTION_CANCEL -> releaseDrag()
        }
        return true
    }

    private fun generic(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            when (e.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    VesselVirtioInput.scrollPrecise(
                        e.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        e.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    )
                    return true
                }
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val button = when (e.actionButton) {
                        MotionEvent.BUTTON_PRIMARY -> LEFT
                        MotionEvent.BUTTON_SECONDARY -> RIGHT
                        MotionEvent.BUTTON_TERTIARY -> MIDDLE
                        else -> return false
                    }
                    VesselVirtioInput.button(button, e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
                    return true
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val (x, y) = mapped(e.x, e.y)
                    VesselVirtioInput.absoluteNormalized(x, y, false)
                    return true
                }
            }
        }
        return false
    }

    private fun releaseDrag() {
        if (dragging) {
            VesselVirtioInput.button(LEFT, false)
            dragging = false
        }
    }

    private fun avgX(e: MotionEvent) =
        (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount

    private fun avgY(e: MotionEvent) =
        (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

    private fun handleKey(e: KeyEvent): Boolean {
        val code = when (e.keyCode) {
            KeyEvent.KEYCODE_ESCAPE->1;KeyEvent.KEYCODE_1->2;KeyEvent.KEYCODE_2->3;KeyEvent.KEYCODE_3->4;KeyEvent.KEYCODE_4->5
            KeyEvent.KEYCODE_5->6;KeyEvent.KEYCODE_6->7;KeyEvent.KEYCODE_7->8;KeyEvent.KEYCODE_8->9;KeyEvent.KEYCODE_9->10;KeyEvent.KEYCODE_0->11
            KeyEvent.KEYCODE_DEL->14;KeyEvent.KEYCODE_TAB->15;KeyEvent.KEYCODE_Q->16;KeyEvent.KEYCODE_W->17;KeyEvent.KEYCODE_E->18;KeyEvent.KEYCODE_R->19
            KeyEvent.KEYCODE_T->20;KeyEvent.KEYCODE_Y->21;KeyEvent.KEYCODE_U->22;KeyEvent.KEYCODE_I->23;KeyEvent.KEYCODE_O->24;KeyEvent.KEYCODE_P->25
            KeyEvent.KEYCODE_ENTER->28;KeyEvent.KEYCODE_CTRL_LEFT->29;KeyEvent.KEYCODE_A->30;KeyEvent.KEYCODE_S->31;KeyEvent.KEYCODE_D->32
            KeyEvent.KEYCODE_F->33;KeyEvent.KEYCODE_G->34;KeyEvent.KEYCODE_H->35;KeyEvent.KEYCODE_J->36;KeyEvent.KEYCODE_K->37;KeyEvent.KEYCODE_L->38
            KeyEvent.KEYCODE_SHIFT_LEFT->42;KeyEvent.KEYCODE_Z->44;KeyEvent.KEYCODE_X->45;KeyEvent.KEYCODE_C->46;KeyEvent.KEYCODE_V->47
            KeyEvent.KEYCODE_B->48;KeyEvent.KEYCODE_N->49;KeyEvent.KEYCODE_M->50;KeyEvent.KEYCODE_SHIFT_RIGHT->54;KeyEvent.KEYCODE_ALT_LEFT->56
            KeyEvent.KEYCODE_SPACE->57;KeyEvent.KEYCODE_CTRL_RIGHT->97;KeyEvent.KEYCODE_ALT_RIGHT->100;KeyEvent.KEYCODE_DPAD_UP->103
            KeyEvent.KEYCODE_DPAD_LEFT->105;KeyEvent.KEYCODE_DPAD_RIGHT->106;KeyEvent.KEYCODE_DPAD_DOWN->108;KeyEvent.KEYCODE_FORWARD_DEL->111
            KeyEvent.KEYCODE_META_LEFT->125;KeyEvent.KEYCODE_META_RIGHT->126
            else -> return false
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> VesselVirtioInput.key(code, true)
            KeyEvent.ACTION_UP -> VesselVirtioInput.key(code, false)
            else -> return false
        }
        return true
    }
}
