package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Embedded RFB 3.8 client for Vessel's KDE desktop.
 *
 * Protocol 14 keeps the server private inside Debian and uses a persistent
 * reverse bridge. The viewer prefers Hextile rather than raw framebuffer
 * updates: static Plasma UI then costs a small fraction of the bytes and CPU
 * required by full 32-bpp raw rectangles.
 */
class VncFramebufferView(context: Context) : View(context) {
    companion object { @Volatile var active: VncFramebufferView? = null }

    private val running = AtomicBoolean(false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wireLock = Any()
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var lastError = "Waiting for Plasma desktop"
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var pointerMask = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastPointerSentMs = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            if (zoom == 1f) { panX = 0f; panY = 0f }
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = if (zoom > 1.05f) 1f else 2f
            if (zoom == 1f) { panX = 0f; panY = 0f }
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount >= 2 || zoom > 1f) {
                if (e2.pointerCount >= 2 && zoom <= 1.05f && abs(distanceY) > 3f) {
                    val (x, y) = mapToGuest(e2.x, e2.y)
                    sendPointer(if (distanceY > 0) 16 else 8, x, y)
                    sendPointer(0, x, y)
                } else {
                    panX -= distanceX
                    panY -= distanceY
                    constrainPan()
                    invalidate()
                }
                return true
            }
            return false
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        active = this
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        if (active === this) active = null
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ connectionLoop() }, "vessel-vnc-client").also { it.isDaemon = true; it.start() }
    }

    private fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private fun connectionLoop() {
        var backoff = 120L
        while (running.get()) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.receiveBufferSize = 1024 * 1024
                s.sendBufferSize = 128 * 1024
                s.connect(InetSocketAddress("127.0.0.1", TermuxUmlController.VNC_PORT), 2500)
                socket = s
                backoff = 120L
                runSession(s)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                postInvalidate()
            } finally {
                runCatching { socket?.close() }
                socket = null
                output = null
            }
            if (running.get()) {
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(1200L)
            }
        }
    }

    private fun runSession(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 512 * 1024))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        output = out

        val version = ByteArray(12)
        input.readFully(version)
        val banner = String(version, Charsets.US_ASCII)
        check(banner.startsWith("RFB ")) { "Not an RFB server: $banner" }
        synchronized(wireLock) {
            out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); out.flush()
        }

        val count = input.readUnsignedByte()
        check(count > 0) { "VNC server rejected the connection" }
        val security = ByteArray(count)
        input.readFully(security)
        check(security.any { it.toInt() and 0xff == 1 }) { "VNC None security unavailable" }
        synchronized(wireLock) { out.writeByte(1); out.flush() }
        check(input.readInt() == 0) { "VNC security negotiation failed" }

        synchronized(wireLock) { out.writeByte(1); out.flush() }
        fbWidth = input.readUnsignedShort()
        fbHeight = input.readUnsignedShort()
        val serverPixelFormat = ByteArray(16)
        input.readFully(serverPixelFormat)
        val nameLen = input.readInt()
        if (nameLen in 0..65535) {
            val name = ByteArray(nameLen)
            input.readFully(name)
            lastError = String(name, Charsets.UTF_8)
        }
        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
        zoom = 1f; panX = 0f; panY = 0f
        sendPixelFormat(out)
        sendEncodings(out)
        requestUpdate(out, false)
        postInvalidate()

        while (running.get()) {
            when (input.readUnsignedByte()) {
                0 -> readFramebufferUpdate(input, out)
                2 -> Unit // Bell
                3 -> {
                    input.skipBytes(3)
                    val len = input.readInt()
                    if (len in 0..1_048_576) input.skipBytes(len) else error("Invalid VNC clipboard size")
                }
                else -> error("Unsupported VNC server message")
            }
        }
    }

    private fun sendPixelFormat(out: DataOutputStream) = synchronized(wireLock) {
        out.writeByte(0); out.write(byteArrayOf(0, 0, 0))
        // 32bpp, depth 24, little endian, true colour, RGB shifts 16/8/0.
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(16); out.writeByte(8); out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0)); out.flush()
    }

    private fun sendEncodings(out: DataOutputStream) = synchronized(wireLock) {
        out.writeByte(2); out.writeByte(0); out.writeShort(2)
        out.writeInt(5) // Hextile: much cheaper for desktop UI than raw.
        out.writeInt(0) // Raw fallback.
        out.flush()
    }

    private fun requestUpdate(out: DataOutputStream, incremental: Boolean) = synchronized(wireLock) {
        out.writeByte(3); out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0)
        out.writeShort(fbWidth); out.writeShort(fbHeight)
        out.flush()
    }

    private fun readPixel(input: DataInputStream): Int {
        val b = input.readUnsignedByte()
        val g = input.readUnsignedByte()
        val r = input.readUnsignedByte()
        input.readUnsignedByte()
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }

    private fun decodeRaw(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val rowBytes = ByteArray(w * 4)
        val row = IntArray(w)
        repeat(h) { yy ->
            input.readFully(rowBytes)
            var p = 0
            for (xx in 0 until w) {
                val b = rowBytes[p++].toInt() and 0xff
                val g = rowBytes[p++].toInt() and 0xff
                val r = rowBytes[p++].toInt() and 0xff
                p++
                row[xx] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
            synchronized(bmp) { bmp.setPixels(row, 0, w, x, y + yy, w, 1) }
        }
    }

    private fun decodeHextile(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val tile = IntArray(16 * 16)
        var bg = 0
        var fg = 0
        var bgValid = false
        var fgValid = false

        var ty = 0
        while (ty < h) {
            val th = minOf(16, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(16, w - tx)
                val sub = input.readUnsignedByte()
                if ((sub and 1) != 0) {
                    decodeRaw(input, bmp, x + tx, y + ty, tw, th)
                    bgValid = false
                    fgValid = false
                    tx += 16
                    continue
                }

                if ((sub and 2) != 0) {
                    bg = readPixel(input)
                    bgValid = true
                }
                check(bgValid) { "Hextile background missing" }
                Arrays.fill(tile, 0, tw * th, bg)

                if ((sub and 4) != 0) {
                    fg = readPixel(input)
                    fgValid = true
                }

                val any = (sub and 8) != 0
                val coloured = (sub and 16) != 0
                if (any) {
                    val count = input.readUnsignedByte()
                    repeat(count) {
                        val color = if (coloured) readPixel(input) else {
                            check(fgValid) { "Hextile foreground missing" }
                            fg
                        }
                        val xy = input.readUnsignedByte()
                        val wh = input.readUnsignedByte()
                        val sx = xy ushr 4
                        val sy = xy and 0x0f
                        val sw = (wh ushr 4) + 1
                        val sh = (wh and 0x0f) + 1
                        check(sx + sw <= tw && sy + sh <= th) { "Invalid Hextile subrectangle" }
                        for (yy in sy until sy + sh) {
                            Arrays.fill(tile, yy * tw + sx, yy * tw + sx + sw, color)
                        }
                    }
                }
                synchronized(bmp) { bmp.setPixels(tile, 0, tw, x + tx, y + ty, tw, th) }
                if (coloured) fgValid = false
                tx += 16
            }
            ty += 16
        }
    }

    private fun readFramebufferUpdate(input: DataInputStream, out: DataOutputStream) {
        input.readUnsignedByte()
        val rectangles = input.readUnsignedShort()
        val bmp = bitmap ?: return
        repeat(rectangles) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            check(x + w <= bmp.width && y + h <= bmp.height) { "Invalid VNC rectangle" }
            when (encoding) {
                0 -> decodeRaw(input, bmp, x, y, w, h)
                5 -> decodeHextile(input, bmp, x, y, w, h)
                else -> error("Unexpected VNC encoding $encoding")
            }
        }
        postInvalidateOnAnimation()
        requestUpdate(out, true)
    }

    private fun fittedRect(): RectF? {
        val bmp = bitmap ?: return null
        if (width <= 0 || height <= 0) return null
        val fit = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * fit * zoom
        val dh = bmp.height * fit * zoom
        val left = (width - dw) / 2f + panX
        val top = (height - dh) / 2f + panY
        return RectF(left, top, left + dw, top + dh)
    }

    private fun constrainPan() {
        val bmp = bitmap ?: return
        val fit = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val excessX = ((bmp.width * fit * zoom - width) / 2f).coerceAtLeast(0f)
        val excessY = ((bmp.height * fit * zoom - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-excessX, excessX)
        panY = panY.coerceIn(-excessY, excessY)
    }

    private fun mapToGuest(px: Float, py: Float): Pair<Int, Int> {
        val r = fittedRect() ?: return 0 to 0
        val x = (((px - r.left) / r.width()) * fbWidth).toInt().coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        val y = (((py - r.top) / r.height()) * fbHeight).toInt().coerceIn(0, (fbHeight - 1).coerceAtLeast(0))
        return x to y
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val bmp = bitmap
        val rect = fittedRect()
        if (bmp != null && rect != null) {
            synchronized(bmp) { canvas.drawBitmap(bmp, null, rect, paint) }
        } else {
            paint.textSize = 30f
            paint.color = android.graphics.Color.LTGRAY
            canvas.drawText(lastError.take(70), 28f, 56f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (fbWidth <= 0 || fbHeight <= 0 || event.pointerCount > 1 || scaleDetector.isInProgress) return true
        val (x, y) = mapToGuest(event.x, event.y)
        pointerMask = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            else -> pointerMask
        }
        val now = SystemClock.uptimeMillis()
        if (event.actionMasked != MotionEvent.ACTION_MOVE || now - lastPointerSentMs >= 16) {
            sendPointer(pointerMask, x, y)
            lastPointerSentMs = now
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_POINTER) != 0) {
            val (x, y) = mapToGuest(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    sendPointer(0, x, y)
                    return true
                }
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) {
                        sendPointer(if (v > 0) 8 else 16, x, y)
                        sendPointer(0, x, y)
                        return true
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean { sendAndroidKey(true, keyCode, event); return true }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean { sendAndroidKey(false, keyCode, event); return true }

    fun sendAndroidKey(down: Boolean, keyCode: Int, event: KeyEvent? = null) {
        val keysym = when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> 0xff1b
            KeyEvent.KEYCODE_TAB -> 0xff09
            KeyEvent.KEYCODE_ENTER -> 0xff0d
            KeyEvent.KEYCODE_DEL -> 0xff08
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe3
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0xffe9
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe1
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> 0xffeb
            else -> event?.unicodeChar?.takeIf { it != 0 } ?: KeyEvent(keyCode, keyCode).unicodeChar
        }
        if (keysym != 0) sendKey(down, keysym)
    }

    private fun sendKey(down: Boolean, keysym: Int) {
        val out = output ?: return
        synchronized(wireLock) {
            runCatching {
                out.writeByte(4); out.writeByte(if (down) 1 else 0); out.writeShort(0); out.writeInt(keysym); out.flush()
            }
        }
    }

    private fun sendPointer(mask: Int, x: Int, y: Int) {
        val out = output ?: return
        synchronized(wireLock) {
            runCatching { out.writeByte(5); out.writeByte(mask); out.writeShort(x); out.writeShort(y); out.flush() }
        }
    }
}
