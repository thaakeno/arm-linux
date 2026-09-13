package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Compatibility class name retained for the Compose screen. There is no VNC/RFB
 * code here: Vessel VFRM1 is drawn directly to an Android SurfaceView and input
 * is sent over Vessel's native input bridge.
 */
class VncFramebufferView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: VncFramebufferView? = null
        private const val FRAME_PORT = 47636
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
    }

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    @Volatile private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2.1f }
    private val cursorPath = Path()
    private val contentRect = RectF()
    private var pointerMode = PointerMode.DIRECT
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var twoFingerY = 0f
    private var twoFingerDownAt = 0L
    private var twoFingerMoved = false
    private var mouseLeftDown = false
    private var dragging = false
    private var lastTapAt = 0L
    private var localCursorX = 0f
    private var localCursorY = 0f
    @Volatile private var lastFrameSeq = 0L

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        setZOrderOnTop(false)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setPointerMode(mode: PointerMode) {
        if (pointerMode == mode) return
        if (dragging) {
            VesselInputClient.button(BTN_LEFT, false)
            dragging = false
        }
        pointerMode = mode
        ensureCursorInitialized()
        drawLatest()
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        active = this
        requestFocus()
        ensureCursorInitialized()
        startFrameReader()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ensureCursorInitialized()
        drawLatest()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        if (dragging || mouseLeftDown) VesselInputClient.button(BTN_LEFT, false)
        dragging = false
        mouseLeftDown = false
        if (active === this) active = null
    }

    private fun ensureCursorInitialized() {
        if (localCursorX > 0f && localCursorY > 0f) return
        localCursorX = width.coerceAtLeast(1) / 2f
        localCursorY = height.coerceAtLeast(1) / 2f
    }

    private fun startFrameReader() {
        if (!running.compareAndSet(false, true)) return
        readerThread = Thread({ frameLoop() }, "vessel-native-frame-client").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun frameLoop() {
        var backoff = 30L
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.receiveBufferSize = 1024 * 1024
                    socket.connect(InetSocketAddress("127.0.0.1", FRAME_PORT), 1200)
                    val input = BufferedInputStream(socket.getInputStream(), 1024 * 1024)
                    val header = JSONObject(readLine(input, 4096))
                    require(header.optString("magic") == "VFRM1") { "Unexpected Vessel frame protocol" }
                    val frameWidth = header.getInt("width")
                    val frameHeight = header.getInt("height")
                    require(frameWidth in 320..7680 && frameHeight in 240..4320)
                    require(header.optString("format") == "RGBA8888") { "Unexpected Vessel pixel format" }
                    backoff = 30L

                    while (running.get()) {
                        val prefix = readExact(input, 16)
                        val meta = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
                        val seq = meta.long
                        val compressed = meta.int
                        val rawSize = meta.int
                        require(compressed in 1..(32 * 1024 * 1024))
                        require(rawSize == frameWidth * frameHeight * 4)
                        val payload = readExact(input, compressed)
                        val raw = inflate(payload, rawSize)
                        val target = bitmap?.takeIf { it.width == frameWidth && it.height == frameHeight }
                            ?: Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888).also { bitmap = it }
                        target.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
                        lastFrameSeq = seq
                        drawLatest()
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                if (!running.get()) break
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(500L)
            }
        }
    }

    @Synchronized
    private fun drawLatest() {
        if (!holder.surface.isValid) return
        val frame = bitmap ?: return
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= 23) holder.lockHardwareCanvas() else holder.lockCanvas()
            canvas.drawColor(Color.BLACK)

            // Preserve the Linux desktop's aspect ratio. The old stretch-to-fill
            // path distorted every window whenever the Android view was portrait.
            val sx = canvas.width.toFloat() / frame.width.toFloat()
            val sy = canvas.height.toFloat() / frame.height.toFloat()
            val scale = minOf(sx, sy)
            val dw = frame.width * scale
            val dh = frame.height * scale
            val left = (canvas.width - dw) * 0.5f
            val top = (canvas.height - dh) * 0.5f
            contentRect.set(left, top, left + dw, top + dh)
            val dst = Rect(left.roundToInt(), top.roundToInt(), (left + dw).roundToInt(), (top + dh).roundToInt())
            canvas.drawBitmap(frame, null, dst, paint)

            if (pointerMode == PointerMode.TRACKPAD) drawCursor(canvas)
        } catch (_: Throwable) {
        } finally {
            if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val bounds = if (!contentRect.isEmpty) contentRect else RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        val x = localCursorX.coerceIn(bounds.left, bounds.right)
        val y = localCursorY.coerceIn(bounds.top, bounds.bottom)
        cursorPath.reset()
        cursorPath.moveTo(x, y)
        cursorPath.lineTo(x + 2.5f, y + 20f)
        cursorPath.lineTo(x + 7.5f, y + 14f)
        cursorPath.lineTo(x + 13f, y + 25f)
        cursorPath.lineTo(x + 17f, y + 23f)
        cursorPath.lineTo(x + 11.5f, y + 12f)
        cursorPath.lineTo(x + 20f, y + 11f)
        cursorPath.close()
        canvas.drawPath(cursorPath, cursorFill)
        canvas.drawPath(cursorPath, cursorStroke)
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String {
        val out = StringBuilder()
        while (out.length < limit) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("frame stream closed")
            if (b == '\n'.code) return out.toString()
            out.append(b.toChar())
        }
        error("frame header too large")
    }

    private fun readExact(input: BufferedInputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(out, offset, size - offset)
            if (n < 0) throw java.io.EOFException("frame stream closed")
            offset += n
        }
        return out
    }

    private fun inflate(payload: ByteArray, rawSize: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(payload)
            val out = ByteArray(rawSize)
            var offset = 0
            while (!inflater.finished() && offset < rawSize) {
                val n = inflater.inflate(out, offset, rawSize - offset)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else offset += n
            }
            require(offset == rawSize) { "short Vessel frame: $offset/$rawSize" }
            out
        } finally {
            inflater.end()
        }
    }

    private fun normalizedTouch(x: Float, y: Float): Pair<Float, Float> {
        val r = if (!contentRect.isEmpty) contentRect else RectF(0f, 0f, width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat())
        return Pair(((x - r.left) / r.width()).coerceIn(0f, 1f), ((y - r.top) / r.height()).coerceIn(0f, 1f))
    }

    private fun accelerated(dx: Float, dy: Float): Pair<Float, Float> {
        val speed = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gain = 1.45f + (speed / 18f).coerceIn(0f, 1.35f)
        return Pair(dx * gain, dy * gain)
    }

    private fun moveTrackpad(dx: Float, dy: Float) {
        if (abs(dx) + abs(dy) < 0.15f) return
        val (sx, sy) = accelerated(dx, dy)
        VesselInputClient.relative(sx, sy)
        val r = if (!contentRect.isEmpty) contentRect else RectF(0f, 0f, width.toFloat(), height.toFloat())
        localCursorX = (localCursorX + sx).coerceIn(r.left, r.right)
        localCursorY = (localCursorY + sy).coerceIn(r.top, r.bottom)
        drawLatest()
    }

    private fun handlePhysicalMouse(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val left = (e.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                if (left != mouseLeftDown) {
                    mouseLeftDown = left
                    VesselInputClient.button(BTN_LEFT, left)
                }
                val dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) moveTrackpad(dx, dy)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus()
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE && handlePhysicalMouse(e)) return true

        when (pointerMode) {
            PointerMode.DIRECT -> {
                val (nx, ny) = normalizedTouch(e.x, e.y)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> VesselInputClient.absolute(nx, ny, true)
                    MotionEvent.ACTION_MOVE -> VesselInputClient.absolute(nx, ny, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselInputClient.absolute(nx, ny, false)
                }
            }
            PointerMode.TRACKPAD -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                    downAt = SystemClock.uptimeMillis(); moved = false; twoFingerMoved = false
                    if (downAt - lastTapAt in 1..260) {
                        dragging = true
                        VesselInputClient.button(BTN_LEFT, true)
                        lastTapAt = 0L
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    twoFingerY = (e.getY(0) + e.getY(1)) * 0.5f
                    twoFingerDownAt = SystemClock.uptimeMillis()
                    twoFingerMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y = (e.getY(0) + e.getY(1)) * 0.5f
                        val dy = y - twoFingerY
                        if (abs(dy) > 2.5f) {
                            val steps = (abs(dy) / 12f).coerceAtLeast(1f).roundToInt()
                            VesselInputClient.scroll(0, if (dy < 0) steps else -steps)
                            twoFingerY = y
                            twoFingerMoved = true
                        }
                    } else {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        moveTrackpad(dx, dy)
                        moved = moved || abs(e.x - downX) + abs(e.y - downY) > 7f
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.pointerCount == 2 && !twoFingerMoved && SystemClock.uptimeMillis() - twoFingerDownAt < 260) {
                        VesselInputClient.button(BTN_RIGHT, true)
                        VesselInputClient.button(BTN_RIGHT, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        VesselInputClient.button(BTN_LEFT, false)
                        dragging = false
                    } else if (!moved && SystemClock.uptimeMillis() - downAt < 260) {
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
            if (e.action == MotionEvent.ACTION_HOVER_MOVE || e.action == MotionEvent.ACTION_MOVE) {
                val dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) moveTrackpad(dx, dy)
            }
            val vy = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hx = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (abs(vy) >= 0.05f || abs(hx) >= 0.05f) {
                VesselInputClient.scroll(hx.roundToInt(), vy.roundToInt())
            }
            val left = (e.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
            if (left != mouseLeftDown) {
                mouseLeftDown = left
                VesselInputClient.button(BTN_LEFT, left)
            }
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
                text?.forEach { sendCharacter(it) }
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
            ' ' -> 57; '\n' -> 28; '\t' -> 15
            '.' -> 52; ',' -> 51; '-' -> 12; '=' -> 13; '/' -> 53; ';' -> 39; '\'' -> 40
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
