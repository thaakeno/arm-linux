package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal RFB 3.8 client for the loopback-only TigerVNC session exposed by VmBridge. */
class VncFramebufferView(context: Context) : View(context) {
    companion object { @Volatile var active: VncFramebufferView? = null }

    private val running = AtomicBoolean(false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var lastError = "Waiting for Plasma VNC"
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var pointerMask = 0

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
        Thread({ connectionLoop() }, "dev1-vnc-client").also { it.isDaemon = true; it.start() }
    }

    private fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private fun connectionLoop() {
        while (running.get()) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", VmBridge.ANDROID_VNC_PORT), 2500)
                socket = s
                runSession(s)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                postInvalidate()
            } finally {
                runCatching { socket?.close() }
                socket = null
                output = null
            }
            if (running.get()) Thread.sleep(1200)
        }
    }

    private fun runSession(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        output = out

        val version = ByteArray(12)
        input.readFully(version)
        val banner = String(version, Charsets.US_ASCII)
        check(banner.startsWith("RFB ")) { "Not an RFB server: $banner" }
        out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); out.flush()

        val count = input.readUnsignedByte()
        check(count > 0) { "VNC server rejected connection: ${input.readInt()}" }
        val security = ByteArray(count)
        input.readFully(security)
        check(security.any { it.toInt() and 0xff == 1 }) { "VNC None security unavailable" }
        out.writeByte(1); out.flush()
        val securityResult = input.readInt()
        check(securityResult == 0) { "VNC security failed: $securityResult" }

        out.writeByte(1); out.flush() // shared session
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
        sendPixelFormat(out)
        sendEncodings(out)
        requestUpdate(out, false)
        postInvalidate()

        while (running.get()) {
            when (input.readUnsignedByte()) {
                0 -> readFramebufferUpdate(input, out)
                2 -> Unit // bell
                3 -> {
                    input.skipBytes(3)
                    val len = input.readInt()
                    if (len in 0..1_048_576) input.skipBytes(len)
                }
                else -> error("Unsupported VNC server message")
            }
        }
    }

    private fun sendPixelFormat(out: DataOutputStream) {
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0))
        out.writeByte(32)
        out.writeByte(24)
        out.writeByte(0)
        out.writeByte(1)
        out.writeShort(255)
        out.writeShort(255)
        out.writeShort(255)
        out.writeByte(16)
        out.writeByte(8)
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0))
        out.flush()
    }

    private fun sendEncodings(out: DataOutputStream) {
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(1)
        out.writeInt(0) // raw encoding only; deterministic and simple
        out.flush()
    }

    private fun requestUpdate(out: DataOutputStream, incremental: Boolean) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0)
        out.writeShort(fbWidth); out.writeShort(fbHeight)
        out.flush()
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
            check(encoding == 0) { "Unexpected VNC encoding $encoding" }
            val bytes = ByteArray(w * h * 4)
            input.readFully(bytes)
            val ints = IntArray(w * h)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in ints.indices) ints[i] = -0x1000000 or (buffer.int and 0x00ffffff)
            synchronized(bmp) { bmp.setPixels(ints, 0, w, x, y, w, h) }
        }
        postInvalidate()
        requestUpdate(out, true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val bmp = bitmap
        if (bmp != null) {
            synchronized(bmp) {
                val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (width - dw) / 2f
                val top = (height - dh) / 2f
                canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + dw, top + dh), paint)
            }
        } else {
            paint.textSize = 30f
            paint.color = android.graphics.Color.LTGRAY
            canvas.drawText(lastError.take(55), 28f, 56f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (fbWidth <= 0 || fbHeight <= 0) return true
        val bmp = bitmap ?: return true
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val left = (width - bmp.width * scale) / 2f
        val top = (height - bmp.height * scale) / 2f
        val x = ((event.x - left) / scale).toInt().coerceIn(0, fbWidth - 1)
        val y = ((event.y - top) / scale).toInt().coerceIn(0, fbHeight - 1)
        pointerMask = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            else -> pointerMask
        }
        sendPointer(pointerMask, x, y)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        sendAndroidKey(true, keyCode, event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        sendAndroidKey(false, keyCode, event)
        return true
    }

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

    private fun sendKey(down: Boolean, keysym: Int) = synchronized(this) {
        val out = output ?: return@synchronized
        runCatching {
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keysym)
            out.flush()
        }
    }

    private fun sendPointer(mask: Int, x: Int, y: Int) = synchronized(this) {
        val out = output ?: return@synchronized
        runCatching {
            out.writeByte(5)
            out.writeByte(mask)
            out.writeShort(x)
            out.writeShort(y)
            out.flush()
        }
    }
}
