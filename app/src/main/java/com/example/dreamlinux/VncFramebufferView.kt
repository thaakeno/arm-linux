package com.example.dreamlinux

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Vessel desktop surface.
 *
 * The historical class name is kept so the Compose UI does not need a second
 * display widget, but this is no longer a VNC/GLSurfaceView implementation.
 * Frames are decoded and posted by a native ANativeWindow presenter. Android
 * input is forwarded independently, so pointer latency is not tied to frame
 * delivery.
 */
class VncFramebufferView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        init { System.loadLibrary("vessel_desktop") }
        @Volatile var active: VncFramebufferView? = null
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112
    }

    private external fun nativeAttach(surface: Surface)
    private external fun nativeDetach()
    private external fun nativeSetCursorVisible(visible: Boolean)
    private external fun nativeCursorAbsolute(x: Float, y: Float)
    private external fun nativeCursorRelative(dx: Float, dy: Float)

    @Volatile private var pointerMode = PointerMode.DIRECT
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var moved = false
    private var dragging = false
    private var twoFingerX = 0f
    private var twoFingerY = 0f
    private var twoFingerDownAt = 0L
    private var twoFingerMoved = false
    private var mouseLeftDown = false
    private var mouseRightDown = false
    private var mouseMiddleDown = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        releaseButtons()
        nativeDetach()
        if (active === this) active = null
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFocus()
        nativeAttach(holder.surface)
        nativeSetCursorVisible(pointerMode == PointerMode.TRACKPAD)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        nativeAttach(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = nativeDetach()

    fun setPointerMode(mode: PointerMode) {
        if (pointerMode == mode) return
        releaseButtons()
        pointerMode = mode
        nativeSetCursorVisible(mode == PointerMode.TRACKPAD)
    }

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun tapKey(keysym: Int) {
        val code = when (keysym) {
            0xff1b -> 1; 0xff09 -> 15; 0xffe3 -> 29; 0xffe9 -> 56; 0xffeb -> 125
            0xff51 -> 105; 0xff52 -> 103; 0xff53 -> 106; 0xff54 -> 108
            else -> 0
        }
        if (code != 0) {
            VesselInputClient.key(code, true)
            VesselInputClient.key(code, false)
        }
    }

    private fun accelerated(dx: Float, dy: Float): Pair<Float, Float> {
        val speed = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gain = 1.05f + (speed / 18f).coerceIn(0f, 1.65f)
        return dx * gain to dy * gain
    }

    private fun moveTrackpad(dx: Float, dy: Float) {
        if (abs(dx) + abs(dy) < 0.05f) return
        val (sx, sy) = accelerated(dx, dy)
        VesselInputClient.relative(sx, sy)
        nativeCursorRelative(sx, sy)
    }

    private fun updatePhysicalButtons(e: MotionEvent) {
        val left = (e.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        val right = (e.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        val middle = (e.buttonState and MotionEvent.BUTTON_TERTIARY) != 0
        if (left != mouseLeftDown) { mouseLeftDown = left; VesselInputClient.button(BTN_LEFT, left) }
        if (right != mouseRightDown) { mouseRightDown = right; VesselInputClient.button(BTN_RIGHT, right) }
        if (middle != mouseMiddleDown) { mouseMiddleDown = middle; VesselInputClient.button(BTN_MIDDLE, middle) }
    }

    private fun releaseButtons() {
        if (dragging || mouseLeftDown) VesselInputClient.button(BTN_LEFT, false)
        if (mouseRightDown) VesselInputClient.button(BTN_RIGHT, false)
        if (mouseMiddleDown) VesselInputClient.button(BTN_MIDDLE, false)
        dragging = false
        mouseLeftDown = false
        mouseRightDown = false
        mouseMiddleDown = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus()
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            updatePhysicalButtons(e)
            val dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            if (dx != 0f || dy != 0f) moveTrackpad(dx, dy)
            return true
        }

        when (pointerMode) {
            PointerMode.DIRECT -> {
                val nx = (e.x / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                val ny = (e.y / height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                nativeSetCursorVisible(false)
                nativeCursorAbsolute(nx, ny)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> VesselInputClient.absolute(nx, ny, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselInputClient.absolute(nx, ny, false)
                }
            }
            PointerMode.TRACKPAD -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    nativeSetCursorVisible(true)
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                    downAt = SystemClock.uptimeMillis(); moved = false; twoFingerMoved = false
                    if (downAt - lastTapAt in 1..320) {
                        dragging = true
                        VesselInputClient.button(BTN_LEFT, true)
                        lastTapAt = 0L
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    twoFingerX = (e.getX(0) + e.getX(1)) * .5f
                    twoFingerY = (e.getY(0) + e.getY(1)) * .5f
                    twoFingerDownAt = SystemClock.uptimeMillis()
                    twoFingerMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val x = (e.getX(0) + e.getX(1)) * .5f
                        val y = (e.getY(0) + e.getY(1)) * .5f
                        val dx = x - twoFingerX; val dy = y - twoFingerY
                        if (abs(dx) + abs(dy) > 1f) {
                            val sx = (dx / 9f).toInt()
                            val sy = (-dy / 9f).toInt()
                            if (sx != 0 || sy != 0) VesselInputClient.scroll(sx, sy)
                            twoFingerX = x; twoFingerY = y; twoFingerMoved = true
                        }
                    } else {
                        val dx = e.x - lastX; val dy = e.y - lastY
                        val distance = abs(e.x - downX) + abs(e.y - downY)
                        if (!dragging && !moved && SystemClock.uptimeMillis() - downAt > 190 && distance > 3f) {
                            dragging = true
                            VesselInputClient.button(BTN_LEFT, true)
                        }
                        moveTrackpad(dx, dy)
                        moved = moved || distance > 5f
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.pointerCount == 2 && !twoFingerMoved && SystemClock.uptimeMillis() - twoFingerDownAt < 300) {
                        VesselInputClient.button(BTN_RIGHT, true)
                        VesselInputClient.button(BTN_RIGHT, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        VesselInputClient.button(BTN_LEFT, false)
                        dragging = false
                    } else if (!moved && SystemClock.uptimeMillis() - downAt < 320) {
                        VesselInputClient.button(BTN_LEFT, true)
                        VesselInputClient.button(BTN_LEFT, false)
                        lastTapAt = SystemClock.uptimeMillis()
                    }
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            nativeSetCursorVisible(true)
            updatePhysicalButtons(e)
            if (e.action == MotionEvent.ACTION_HOVER_MOVE || e.action == MotionEvent.ACTION_MOVE) {
                val dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) moveTrackpad(dx, dy)
            }
            val vy = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hx = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (abs(vy) >= .05f || abs(hx) >= .05f) VesselInputClient.scroll(hx.toInt(), vy.toInt())
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = androidToLinuxKey(event.keyCode)
        if (code != 0) {
            VesselInputClient.key(code, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach(::sendCharacter)
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = this@VncFramebufferView.dispatchKeyEvent(event)
        }
    }

    private fun sendCharacter(ch: Char) {
        val lower = ch.lowercaseChar()
        val code = when (lower) {
            in 'a'..'z' -> intArrayOf(30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44)[lower - 'a']
            '1' -> 2; '2' -> 3; '3' -> 4; '4' -> 5; '5' -> 6; '6' -> 7; '7' -> 8; '8' -> 9; '9' -> 10; '0' -> 11
            ' ' -> 57; '\n' -> 28; '\t' -> 15; '.' -> 52; ',' -> 51; '-' -> 12; '=' -> 13; '/' -> 53; ';' -> 39; '\'' -> 40
            else -> 0
        }
        if (code == 0) return
        val shift = ch.isUpperCase()
        if (shift) VesselInputClient.key(42, true)
        VesselInputClient.key(code, true); VesselInputClient.key(code, false)
        if (shift) VesselInputClient.key(42, false)
    }

    private fun androidToLinuxKey(k: Int): Int = when (k) {
        KeyEvent.KEYCODE_ESCAPE -> 1; KeyEvent.KEYCODE_1 -> 2; KeyEvent.KEYCODE_2 -> 3; KeyEvent.KEYCODE_3 -> 4; KeyEvent.KEYCODE_4 -> 5; KeyEvent.KEYCODE_5 -> 6; KeyEvent.KEYCODE_6 -> 7; KeyEvent.KEYCODE_7 -> 8; KeyEvent.KEYCODE_8 -> 9; KeyEvent.KEYCODE_9 -> 10; KeyEvent.KEYCODE_0 -> 11
        KeyEvent.KEYCODE_DEL -> 14; KeyEvent.KEYCODE_TAB -> 15; KeyEvent.KEYCODE_Q -> 16; KeyEvent.KEYCODE_W -> 17; KeyEvent.KEYCODE_E -> 18; KeyEvent.KEYCODE_R -> 19; KeyEvent.KEYCODE_T -> 20; KeyEvent.KEYCODE_Y -> 21; KeyEvent.KEYCODE_U -> 22; KeyEvent.KEYCODE_I -> 23; KeyEvent.KEYCODE_O -> 24; KeyEvent.KEYCODE_P -> 25; KeyEvent.KEYCODE_ENTER -> 28
        KeyEvent.KEYCODE_CTRL_LEFT -> 29; KeyEvent.KEYCODE_A -> 30; KeyEvent.KEYCODE_S -> 31; KeyEvent.KEYCODE_D -> 32; KeyEvent.KEYCODE_F -> 33; KeyEvent.KEYCODE_G -> 34; KeyEvent.KEYCODE_H -> 35; KeyEvent.KEYCODE_J -> 36; KeyEvent.KEYCODE_K -> 37; KeyEvent.KEYCODE_L -> 38; KeyEvent.KEYCODE_SHIFT_LEFT -> 42; KeyEvent.KEYCODE_Z -> 44; KeyEvent.KEYCODE_X -> 45; KeyEvent.KEYCODE_C -> 46; KeyEvent.KEYCODE_V -> 47; KeyEvent.KEYCODE_B -> 48; KeyEvent.KEYCODE_N -> 49; KeyEvent.KEYCODE_M -> 50; KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
        KeyEvent.KEYCODE_ALT_LEFT -> 56; KeyEvent.KEYCODE_SPACE -> 57; KeyEvent.KEYCODE_F1 -> 59; KeyEvent.KEYCODE_F2 -> 60; KeyEvent.KEYCODE_F3 -> 61; KeyEvent.KEYCODE_F4 -> 62; KeyEvent.KEYCODE_F5 -> 63; KeyEvent.KEYCODE_F6 -> 64; KeyEvent.KEYCODE_F7 -> 65; KeyEvent.KEYCODE_F8 -> 66; KeyEvent.KEYCODE_F9 -> 67; KeyEvent.KEYCODE_F10 -> 68; KeyEvent.KEYCODE_F11 -> 87; KeyEvent.KEYCODE_F12 -> 88
        KeyEvent.KEYCODE_HOME -> 102; KeyEvent.KEYCODE_DPAD_UP -> 103; KeyEvent.KEYCODE_PAGE_UP -> 104; KeyEvent.KEYCODE_DPAD_LEFT -> 105; KeyEvent.KEYCODE_DPAD_RIGHT -> 106; KeyEvent.KEYCODE_MOVE_END -> 107; KeyEvent.KEYCODE_DPAD_DOWN -> 108; KeyEvent.KEYCODE_PAGE_DOWN -> 109; KeyEvent.KEYCODE_INSERT -> 110; KeyEvent.KEYCODE_FORWARD_DEL -> 111; KeyEvent.KEYCODE_CTRL_RIGHT -> 97; KeyEvent.KEYCODE_ALT_RIGHT -> 100; KeyEvent.KEYCODE_META_LEFT -> 125; KeyEvent.KEYCODE_META_RIGHT -> 126
        else -> 0
    }
}
