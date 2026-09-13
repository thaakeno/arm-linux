package com.example.dreamlinux

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Vessel's low-latency Linux desktop surface.
 *
 * VFRM2 sends raw damaged tiles from the guest. Tiles are uploaded directly to
 * one persistent GL texture with glTexSubImage2D, so there is no full-frame
 * zlib inflate, Android Bitmap copy, Canvas rescale, or fake local cursor.
 * The real X cursor is part of the Linux desktop stream.
 */
class VncFramebufferView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    enum class PointerMode { DIRECT, TRACKPAD }

    companion object {
        @Volatile var active: VncFramebufferView? = null
        private const val FRAME_PORT = 47636
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112
    }

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    @Volatile private var pointerMode = PointerMode.DIRECT

    private var textureId = 0
    private var program = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    @Volatile private var contentLeft = 0f
    @Volatile private var contentTop = 0f
    @Volatile private var contentWidth = 1f
    @Volatile private var contentHeight = 1f

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var twoFingerY = 0f
    private var twoFingerX = 0f
    private var twoFingerDownAt = 0L
    private var twoFingerMoved = false
    private var dragging = false
    private var lastTapAt = 0L
    private var mouseLeftDown = false
    private var mouseRightDown = false
    private var mouseMiddleDown = false

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    // Xvfb's first row is the top row; OpenGL's texture origin is bottom-left.
    private val texCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0)
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
        requestFocus()
        startFrameReader()
    }

    override fun onDetachedFromWindow() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        releaseButtons()
        if (active === this) active = null
        super.onDetachedFromWindow()
    }

    fun setPointerMode(mode: PointerMode) {
        if (pointerMode == mode) return
        if (dragging) {
            VesselInputClient.button(BTN_LEFT, false)
            dragging = false
        }
        pointerMode = mode
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(
            "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0); }",
            "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; void main(){ gl_FragColor=texture2D(uTex,vUv); }"
        )
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        if (frameWidth > 0 && frameHeight > 0) allocateTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        updateViewport()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (textureId == 0 || frameWidth <= 0 || frameHeight <= 0 || program == 0) return
        val vpX = contentLeft.roundToInt()
        val vpTop = contentTop.roundToInt()
        val vpW = contentWidth.roundToInt().coerceAtLeast(1)
        val vpH = contentHeight.roundToInt().coerceAtLeast(1)
        val vpY = (surfaceHeight - vpTop - vpH).coerceAtLeast(0)
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        vertices.position(0); texCoords.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    private fun allocateTexture() {
        if (textureId == 0 || frameWidth <= 0 || frameHeight <= 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            frameWidth, frameHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        updateViewport()
    }

    private fun updateViewport() {
        if (frameWidth <= 0 || frameHeight <= 0) {
            contentLeft = 0f; contentTop = 0f
            contentWidth = surfaceWidth.toFloat(); contentHeight = surfaceHeight.toFloat()
            return
        }
        val scale = minOf(surfaceWidth.toFloat() / frameWidth, surfaceHeight.toFloat() / frameHeight)
        val w = frameWidth * scale
        val h = frameHeight * scale
        contentLeft = (surfaceWidth - w) * 0.5f
        contentTop = (surfaceHeight - h) * 0.5f
        contentWidth = w
        contentHeight = h
        requestRender()
    }

    private fun uploadFull(width: Int, height: Int, bytes: ByteArray) {
        queueEvent {
            if (frameWidth != width || frameHeight != height) {
                frameWidth = width; frameHeight = height
                allocateTexture()
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); position(0) }
            )
            requestRender()
        }
    }

    private data class Tile(val x: Int, val y: Int, val w: Int, val h: Int, val data: ByteArray)

    private fun uploadTiles(tiles: List<Tile>) {
        if (tiles.isEmpty()) return
        queueEvent {
            if (textureId == 0 || frameWidth <= 0 || frameHeight <= 0) return@queueEvent
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            for (t in tiles) {
                val buf = ByteBuffer.allocateDirect(t.data.size)
                buf.put(t.data).position(0)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, t.x, t.y, t.w, t.h,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
                )
            }
            requestRender()
        }
    }

    private fun startFrameReader() {
        if (!running.compareAndSet(false, true)) return
        readerThread = Thread({ frameLoop() }, "vessel-vfrm2-client").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun frameLoop() {
        var backoff = 20L
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.receiveBufferSize = 4 * 1024 * 1024
                    socket.connect(InetSocketAddress("127.0.0.1", FRAME_PORT), 1000)
                    val input = BufferedInputStream(socket.getInputStream(), 2 * 1024 * 1024)
                    val header = JSONObject(readLine(input, 4096))
                    require(header.optString("magic") == "VFRM2") { "Unexpected Vessel frame protocol" }
                    val width = header.getInt("width")
                    val height = header.getInt("height")
                    require(width in 320..7680 && height in 240..4320)
                    require(header.optString("format") == "RGBA8888")
                    frameWidth = width; frameHeight = height
                    queueEvent { allocateTexture() }
                    backoff = 20L
                    while (running.get()) {
                        val prefix = readExact(input, 13)
                        val meta = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
                        val kind = meta.get().toInt().toChar()
                        meta.long // sequence, useful for diagnostics later
                        val value = meta.int
                        when (kind) {
                            'F' -> {
                                require(value == width * height * 4)
                                uploadFull(width, height, readExact(input, value))
                            }
                            'D' -> {
                                require(value in 0..8192)
                                val tiles = ArrayList<Tile>(value)
                                repeat(value) {
                                    val th = ByteBuffer.wrap(readExact(input, 12)).order(ByteOrder.BIG_ENDIAN)
                                    val x = th.short.toInt() and 0xffff
                                    val y = th.short.toInt() and 0xffff
                                    val w = th.short.toInt() and 0xffff
                                    val h = th.short.toInt() and 0xffff
                                    val size = th.int
                                    require(w > 0 && h > 0 && size == w * h * 4)
                                    tiles.add(Tile(x, y, w, h, readExact(input, size)))
                                }
                                uploadTiles(tiles)
                            }
                            else -> error("Unknown VFRM2 record $kind")
                        }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                if (!running.get()) break
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(350L)
            }
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

    private fun normalizedTouch(x: Float, y: Float): Pair<Float, Float> = Pair(
        ((x - contentLeft) / contentWidth.coerceAtLeast(1f)).coerceIn(0f, 1f),
        ((y - contentTop) / contentHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
    )

    private fun accelerated(dx: Float, dy: Float): Pair<Float, Float> {
        val speed = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gain = 1.15f + (speed / 22f).coerceIn(0f, 1.45f)
        return Pair(dx * gain, dy * gain)
    }

    private fun moveTrackpad(dx: Float, dy: Float) {
        if (abs(dx) + abs(dy) < 0.08f) return
        val (sx, sy) = accelerated(dx, dy)
        VesselInputClient.relative(sx, sy)
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
        if (mouseLeftDown || dragging) VesselInputClient.button(BTN_LEFT, false)
        if (mouseRightDown) VesselInputClient.button(BTN_RIGHT, false)
        if (mouseMiddleDown) VesselInputClient.button(BTN_MIDDLE, false)
        mouseLeftDown = false; mouseRightDown = false; mouseMiddleDown = false; dragging = false
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
                    if (downAt - lastTapAt in 1..300) {
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
                        if (abs(dx) + abs(dy) > 1.5f) {
                            val sx = (dx / 11f).roundToInt()
                            val sy = (-dy / 11f).roundToInt()
                            if (sx != 0 || sy != 0) VesselInputClient.scroll(sx, sy)
                            twoFingerX = x; twoFingerY = y; twoFingerMoved = true
                        }
                    } else {
                        val dx = e.x - lastX; val dy = e.y - lastY
                        val distance = abs(e.x - downX) + abs(e.y - downY)
                        // Long-press + move behaves like holding a real left mouse button.
                        if (!dragging && !moved && SystemClock.uptimeMillis() - downAt > 170 && distance > 3f) {
                            dragging = true
                            VesselInputClient.button(BTN_LEFT, true)
                        }
                        moveTrackpad(dx, dy)
                        moved = moved || distance > 6f
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.pointerCount == 2 && !twoFingerMoved && SystemClock.uptimeMillis() - twoFingerDownAt < 280) {
                        VesselInputClient.button(BTN_RIGHT, true); VesselInputClient.button(BTN_RIGHT, false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        VesselInputClient.button(BTN_LEFT, false); dragging = false
                    } else if (!moved && SystemClock.uptimeMillis() - downAt < 300) {
                        VesselInputClient.button(BTN_LEFT, true); VesselInputClient.button(BTN_LEFT, false)
                        lastTapAt = SystemClock.uptimeMillis()
                    }
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            updatePhysicalButtons(e)
            if (e.action == MotionEvent.ACTION_HOVER_MOVE || e.action == MotionEvent.ACTION_MOVE) {
                val dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) moveTrackpad(dx, dy)
            }
            val vy = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hx = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (abs(vy) >= .05f || abs(hx) >= .05f) VesselInputClient.scroll(hx.roundToInt(), vy.roundToInt())
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

    private fun createProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }
}
