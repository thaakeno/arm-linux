package com.example.dreamlinux

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Compatibility class name for the Compose desktop page.
 *
 * This is NOT a VNC/framebuffer/screenshot view. It owns the real Android
 * Surface consumed by VesselWaylandPresenter. Android input is forwarded to
 * Linux /dev/uinput devices and consumed by KWin through evdev/libinput.
 */
class VncFramebufferView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: VncFramebufferView? = null
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112
    }

    @Volatile private var pointerMode = PointerMode.DIRECT
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var maxPointerCount = 1
    private var gestureTravel = 0f
    private var lastScrollX = 0f
    private var lastScrollY = 0f

    private val surfaceView = object : SurfaceView(context) {
        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    text?.toString()?.takeIf { it.isNotEmpty() }?.let(VesselInputClient::text)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean =
                    handleAndroidKey(event) || super.sendKeyEvent(event)

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceAtMost(32)) { tapLinuxKey(14) }
                    repeat(afterLength.coerceAtMost(32)) { tapLinuxKey(111) }
                    return true
                }
            }
        }
    }.apply {
        // SurfaceView owns a separate Surface layer behind the app window. Its
        // View placeholder must stay transparent so Android exposes the Vulkan
        // Surface instead of covering a valid Linux frame with black.
        setBackgroundColor(Color.TRANSPARENT)
        holder.addCallback(this@VncFramebufferView)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = false
        setOnTouchListener { _, event -> handleTouch(event) }
        setOnGenericMotionListener { _, event -> handleGenericMotion(event) }
        setOnKeyListener { _, _, event -> handleAndroidKey(event) }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
    }

    override fun onDetachedFromWindow() {
        if (active === this) active = null
        if (surfaceView.holder.surface.isValid) VesselWaylandPresenter.detach()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        VesselWaylandPresenter.attach(holder.surface)
        surfaceView.requestFocus()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        VesselWaylandPresenter.attach(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VesselWaylandPresenter.detach()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return true
        surfaceView.requestFocus()

        if (pointerMode == PointerMode.DIRECT) {
            val x = (event.x / width.toFloat()).coerceIn(0f, 1f)
            val y = (event.y / height.toFloat()).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> VesselInputClient.absolute(x, y, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselInputClient.absolute(x, y, false)
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downAt = SystemClock.uptimeMillis()
                maxPointerCount = 1
                gestureTravel = 0f
                lastScrollX = event.x
                lastScrollY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                lastScrollX = averageX(event)
                lastScrollY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                if (event.pointerCount >= 2) {
                    val avgX = averageX(event)
                    val avgY = averageY(event)
                    val dx = avgX - lastScrollX
                    val dy = avgY - lastScrollY
                    gestureTravel += abs(dx) + abs(dy)
                    // Android gestures are pixel deltas; Linux receives real
                    // REL_WHEEL/HWHEEL events through the uinput pointer.
                    VesselInputClient.scrollPrecise(-dx / 8f, -dy / 8f)
                    lastScrollX = avgX
                    lastScrollY = avgY
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    gestureTravel += abs(dx) + abs(dy)
                    VesselInputClient.relative(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                if (remaining.isNotEmpty()) {
                    lastX = event.getX(remaining[0])
                    lastY = event.getY(remaining[0])
                    lastScrollX = remaining.sumOf { event.getX(it).toDouble() }.toFloat() / remaining.size
                    lastScrollY = remaining.sumOf { event.getY(it).toDouble() }.toFloat() / remaining.size
                }
            }
            MotionEvent.ACTION_UP -> {
                val density = resources.displayMetrics.density
                val directTravel = abs(event.x - downX) + abs(event.y - downY)
                val moved = maxOf(gestureTravel, directTravel)
                if (SystemClock.uptimeMillis() - downAt < 350L && moved < 14f * density) {
                    val button = if (maxPointerCount >= 2) BTN_RIGHT else BTN_LEFT
                    VesselInputClient.button(button, true)
                    VesselInputClient.button(button, false)
                }
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    private fun averageX(event: MotionEvent): Float =
        (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount

    private fun averageY(event: MotionEvent): Float =
        (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount

    private fun handleGenericMotion(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                VesselInputClient.scrollPrecise(
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                )
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> BTN_LEFT
                    MotionEvent.BUTTON_SECONDARY -> BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> BTN_MIDDLE
                    else -> return false
                }
                VesselInputClient.button(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (width <= 0 || height <= 0) return true
                VesselInputClient.absolute(
                    (event.x / width.toFloat()).coerceIn(0f, 1f),
                    (event.y / height.toFloat()).coerceIn(0f, 1f),
                    false,
                )
                return true
            }
        }
        return false
    }

    private fun handleAndroidKey(event: KeyEvent): Boolean {
        val linux = androidToLinuxKey(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> VesselInputClient.key(linux, true)
            KeyEvent.ACTION_UP -> VesselInputClient.key(linux, false)
            else -> return false
        }
        return true
    }

    private fun tapLinuxKey(code: Int, shift: Boolean = false) {
        if (shift) VesselInputClient.key(42, true)
        VesselInputClient.key(code, true)
        VesselInputClient.key(code, false)
        if (shift) VesselInputClient.key(42, false)
    }

    private fun sendCharacter(c: Char) {
        val lower = c.lowercaseChar()
        val letter = when (lower) {
            'a' -> 30; 'b' -> 48; 'c' -> 46; 'd' -> 32; 'e' -> 18; 'f' -> 33
            'g' -> 34; 'h' -> 35; 'i' -> 23; 'j' -> 36; 'k' -> 37; 'l' -> 38
            'm' -> 50; 'n' -> 49; 'o' -> 24; 'p' -> 25; 'q' -> 16; 'r' -> 19
            's' -> 31; 't' -> 20; 'u' -> 22; 'v' -> 47; 'w' -> 17; 'x' -> 45
            'y' -> 21; 'z' -> 44
            else -> null
        }
        if (letter != null) {
            tapLinuxKey(letter, c.isUpperCase())
            return
        }
        when (c) {
            '1' -> tapLinuxKey(2); '2' -> tapLinuxKey(3); '3' -> tapLinuxKey(4); '4' -> tapLinuxKey(5)
            '5' -> tapLinuxKey(6); '6' -> tapLinuxKey(7); '7' -> tapLinuxKey(8); '8' -> tapLinuxKey(9)
            '9' -> tapLinuxKey(10); '0' -> tapLinuxKey(11)
            ' ' -> tapLinuxKey(57); '\n', '\r' -> tapLinuxKey(28); '\t' -> tapLinuxKey(15)
            '-' -> tapLinuxKey(12); '_' -> tapLinuxKey(12, true)
            '=' -> tapLinuxKey(13); '+' -> tapLinuxKey(13, true)
            '[' -> tapLinuxKey(26); '{' -> tapLinuxKey(26, true)
            ']' -> tapLinuxKey(27); '}' -> tapLinuxKey(27, true)
            ';' -> tapLinuxKey(39); ':' -> tapLinuxKey(39, true)
            '\'' -> tapLinuxKey(40); '"' -> tapLinuxKey(40, true)
            '`' -> tapLinuxKey(41); '~' -> tapLinuxKey(41, true)
            '\\' -> tapLinuxKey(43); '|' -> tapLinuxKey(43, true)
            ',' -> tapLinuxKey(51); '<' -> tapLinuxKey(51, true)
            '.' -> tapLinuxKey(52); '>' -> tapLinuxKey(52, true)
            '/' -> tapLinuxKey(53); '?' -> tapLinuxKey(53, true)
            '!' -> tapLinuxKey(2, true); '@' -> tapLinuxKey(3, true); '#' -> tapLinuxKey(4, true)
            '$' -> tapLinuxKey(5, true); '%' -> tapLinuxKey(6, true); '^' -> tapLinuxKey(7, true)
            '&' -> tapLinuxKey(8, true); '*' -> tapLinuxKey(9, true); '(' -> tapLinuxKey(10, true)
            ')' -> tapLinuxKey(11, true)
        }
    }

    private fun androidToLinuxKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_A -> 30; KeyEvent.KEYCODE_B -> 48; KeyEvent.KEYCODE_C -> 46; KeyEvent.KEYCODE_D -> 32
        KeyEvent.KEYCODE_E -> 18; KeyEvent.KEYCODE_F -> 33; KeyEvent.KEYCODE_G -> 34; KeyEvent.KEYCODE_H -> 35
        KeyEvent.KEYCODE_I -> 23; KeyEvent.KEYCODE_J -> 36; KeyEvent.KEYCODE_K -> 37; KeyEvent.KEYCODE_L -> 38
        KeyEvent.KEYCODE_M -> 50; KeyEvent.KEYCODE_N -> 49; KeyEvent.KEYCODE_O -> 24; KeyEvent.KEYCODE_P -> 25
        KeyEvent.KEYCODE_Q -> 16; KeyEvent.KEYCODE_R -> 19; KeyEvent.KEYCODE_S -> 31; KeyEvent.KEYCODE_T -> 20
        KeyEvent.KEYCODE_U -> 22; KeyEvent.KEYCODE_V -> 47; KeyEvent.KEYCODE_W -> 17; KeyEvent.KEYCODE_X -> 45
        KeyEvent.KEYCODE_Y -> 21; KeyEvent.KEYCODE_Z -> 44
        KeyEvent.KEYCODE_1 -> 2; KeyEvent.KEYCODE_2 -> 3; KeyEvent.KEYCODE_3 -> 4; KeyEvent.KEYCODE_4 -> 5
        KeyEvent.KEYCODE_5 -> 6; KeyEvent.KEYCODE_6 -> 7; KeyEvent.KEYCODE_7 -> 8; KeyEvent.KEYCODE_8 -> 9
        KeyEvent.KEYCODE_9 -> 10; KeyEvent.KEYCODE_0 -> 11
        KeyEvent.KEYCODE_ESCAPE -> 1; KeyEvent.KEYCODE_DEL -> 14; KeyEvent.KEYCODE_TAB -> 15
        KeyEvent.KEYCODE_ENTER -> 28; KeyEvent.KEYCODE_SPACE -> 57
        KeyEvent.KEYCODE_CTRL_LEFT -> 29; KeyEvent.KEYCODE_CTRL_RIGHT -> 97
        KeyEvent.KEYCODE_SHIFT_LEFT -> 42; KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
        KeyEvent.KEYCODE_ALT_LEFT -> 56; KeyEvent.KEYCODE_ALT_RIGHT -> 100
        KeyEvent.KEYCODE_META_LEFT -> 125; KeyEvent.KEYCODE_META_RIGHT -> 126
        KeyEvent.KEYCODE_DPAD_UP -> 103; KeyEvent.KEYCODE_DPAD_DOWN -> 108
        KeyEvent.KEYCODE_DPAD_LEFT -> 105; KeyEvent.KEYCODE_DPAD_RIGHT -> 106
        KeyEvent.KEYCODE_FORWARD_DEL -> 111; KeyEvent.KEYCODE_MOVE_HOME -> 102; KeyEvent.KEYCODE_MOVE_END -> 107
        KeyEvent.KEYCODE_PAGE_UP -> 104; KeyEvent.KEYCODE_PAGE_DOWN -> 109
        else -> null
    }

    fun setPointerMode(mode: PointerMode) {
        pointerMode = mode
    }

    fun showKeyboard() {
        surfaceView.requestFocus()
        surfaceView.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(surfaceView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun tapKey(keysym: Int) {
        val code = when (keysym) {
            0xff1b -> 1
            0xff09 -> 15
            0xff0d -> 28
            0xff08 -> 14
            0xffff -> 111
            0xff51 -> 105
            0xff52 -> 103
            0xff53 -> 106
            0xff54 -> 108
            0xffe1 -> 42
            0xffe3 -> 29
            0xffe9 -> 56
            0xffeb -> 125
            else -> if (keysym in 32..126) null else keysym.takeIf { it in 1..255 }
        }
        if (code != null) tapLinuxKey(code) else if (keysym in 32..126) sendCharacter(keysym.toChar())
    }
}
