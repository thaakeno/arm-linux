package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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

/**
 * Compatibility class name retained so the existing Compose screen does not
 * need a migration commit. This implementation contains no VNC/RFB code.
 *
 * Display: Vessel VFRM1 -> Android SurfaceView.
 * Input: Android touch/trackpad/keyboard -> protocol-25 native input bridge.
 */
class VncFramebufferView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: VncFramebufferView? = null
        private const val FRAME_PORT = 47636
    }

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 1f, Color.BLACK)
    }
    private var pointerMode = PointerMode.DIRECT
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var twoFingerY = 0f
    private var mouseLeftDown = false
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
        pointerMode = mode
        if (localCursorX == 0f && width > 0) {
            localCursorX = width / 2f
            localCursorY = height / 2f
        }
        drawLatest()
    }

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun tapKey(keysym: Int) {
        val code = when (keysym) {
            0xff1b -> 1      // Esc
            0xff09 -> 15     // Tab
            0xffe3 -> 29     // Ctrl
            0xffe9 -> 56     // Alt
            0xffeb -> 125    // Super
            0xff51 -> 105    // Left
            0xff52 -> 103    // Up
            0xff53 -> 106    // Right
            0xff54 -> 108    // Down
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
        localCursorX = width / 2f
        localCursorY = height / 2f
        startFrameReader()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (localCursorX <= 0f || localCursorY <= 0f) {
            localCursorX = width / 2f
            localCursorY = height / 2f
        }
        drawLatest()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        if (active === this) active = null
    }

    private fun startFrameReader() {
        if (!running.compareAndSet(false, true)) return
        readerThread = Thread({ frameLoop() }, "vessel-native-frame-client").apply {
            isDaemon = true
            start()
        }
    }

    private fun frameLoop() {
        var backoff = 80L
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress("127.0.0.1", FRAME_PORT), 1500)
                    val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
                    val headerLine = readLine(input, 4096)
                    val header = JSONObject(headerLine)
                    require(header.optString("magic") == "VFRM1") { "Unexpected Vessel frame protocol" }
                    val frameWidth = header.getInt("width")
                    val frameHeight = header.getInt("height")
                    require(frameWidth in 320..7680 && frameHeight in 240..4320)
                    require(header.optString("format") == "BGRA8888")
                    backoff = 80L

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
                backoff = (backoff * 2).coerceAtMost(1000L)
            }
        }
    }

    private fun drawLatest() {
        if (!holder.surface.isValid) return
        val frame = bitmap ?: return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            canvas.drawColor(Color.BLACK)
            // The native desktop is presented directly into the Android surface.
            // Scale to the actual surface bounds so the old RFB letterbox bars are
            // impossible; fullscreen landscape naturally remains aspect-correct.
            canvas.drawBitmap(frame, null, Rect(0, 0, canvas.width, canvas.height), paint)
            if (pointerMode == PointerMode.TRACKPAD) {
                val x = localCursorX.coerceIn(0f, canvas.width.toFloat())
                val y = localCursorY.coerceIn(0f, canvas.height.toFloat())
                canvas.drawCircle(x, y, 7f, cursorPaint)
                canvas.drawCircle(x, y, 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
        } catch (_: Throwable) {
        } finally {
            if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
        }
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus()
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        when (pointerMode) {
            PointerMode.DIRECT -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> VesselInputClient.absolute(e.x / w, e.y / h, true)
                MotionEvent.ACTION_MOVE -> VesselInputClient.absolute(e.x / w, e.y / h, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselInputClient.absolute(e.x / w, e.y / h, false)
            }
            PointerMode.TRACKPAD -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                    downAt = SystemClock.uptimeMillis(); moved = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    twoFingerY = (e.getY(0) + e.getY(1)) * 0.5f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y = (e.getY(0) + e.getY(1)) * 0.5f
                        val dy = y - twoFingerY
                        if (abs(dy) > 5f) {
                            VesselInputClient.scroll(0, if (dy < 0) 1 else -1)
                            twoFingerY = y
                        }
                    } else {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        if (abs(dx) + abs(dy) > 0.8f) {
                            val sx = dx * 1.35f
                            val sy = dy * 1.35f
                            VesselInputClient.relative(sx, sy)
                            localCursorX = (localCursorX + sx).coerceIn(0f, w)
                            localCursorY = (localCursorY + sy).coerceIn(0f, h)
                            moved = moved || abs(e.x - downX) + abs(e.y - downY) > 8f
                            drawLatest()
                        }
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved && SystemClock.uptimeMillis() - downAt < 280) {
                    VesselInputClient.button(0x110, true)
                    VesselInputClient.button(0x110, false)
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
                if (dx != 0f || dy != 0f) {
                    VesselInputClient.relative(dx, dy)
                    localCursorX = (localCursorX + dx).coerceIn(0f, width.toFloat())
                    localCursorY = (localCursorY + dy).coerceIn(0f, height.toFloat())
                    drawLatest()
                }
            }
            val vy = e.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
            val hx = e.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt()
            if (vy != 0 || hx != 0) VesselInputClient.scroll(hx, vy)
            val left = (e.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
            if (left != mouseLeftDown) {
                mouseLeftDown = left
                VesselInputClient.button(0x110, left)
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
