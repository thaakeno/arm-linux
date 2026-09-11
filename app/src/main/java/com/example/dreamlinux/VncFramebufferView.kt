package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Embedded RFB 3.8 client for Vessel's KDE desktop. */
class VncFramebufferView(context: Context) : View(context) {
    companion object { @Volatile var active: VncFramebufferView? = null }

    enum class PointerMode { DIRECT, TRACKPAD }

    private val running = AtomicBoolean(false)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wireLock = Any()
    private val density = resources.displayMetrics.density
    private val touchSlop = 8f * density

    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var lastError = "Waiting for Plasma desktop"
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var pointerMode = PointerMode.TRACKPAD
    private var cursorGuestX = 0
    private var cursorGuestY = 0
    private var lastPointerSentMs = 0L

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartedMs = 0L
    private var moved = false
    private var dragging = false
    private var maxPointers = 1
    private var scrollAccumulator = 0f

    private val directChip = RectF()
    private val trackpadChip = RectF()
    private val trackpadRect = RectF()
    private val leftButtonRect = RectF()
    private val rightButtonRect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
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
        cursorGuestX = fbWidth / 2
        cursorGuestY = fbHeight / 2
        sendPixelFormat(out)
        sendEncodings(out)
        requestUpdate(out, false)
        postInvalidate()

        while (running.get()) {
            when (input.readUnsignedByte()) {
                0 -> readFramebufferUpdate(input, out)
                2 -> Unit
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
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(16); out.writeByte(8); out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0)); out.flush()
    }

    private fun sendEncodings(out: DataOutputStream) = synchronized(wireLock) {
        out.writeByte(2); out.writeByte(0); out.writeShort(2)
        out.writeInt(5)
        out.writeInt(0)
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
                if ((sub and 2) != 0) { bg = readPixel(input); bgValid = true }
                check(bgValid) { "Hextile background missing" }
                Arrays.fill(tile, 0, tw * th, bg)
                if ((sub and 4) != 0) { fg = readPixel(input); fgValid = true }
                val any = (sub and 8) != 0
                val coloured = (sub and 16) != 0
                if (any) {
                    val count = input.readUnsignedByte()
                    repeat(count) {
                        val color = if (coloured) readPixel(input) else {
                            check(fgValid) { "Hextile foreground missing" }; fg
                        }
                        val xy = input.readUnsignedByte()
                        val wh = input.readUnsignedByte()
                        val sx = xy ushr 4
                        val sy = xy and 0x0f
                        val sw = (wh ushr 4) + 1
                        val sh = (wh and 0x0f) + 1
                        check(sx + sw <= tw && sy + sh <= th) { "Invalid Hextile subrectangle" }
                        for (yy in sy until sy + sh) Arrays.fill(tile, yy * tw + sx, yy * tw + sx + sw, color)
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
        val dw = bmp.width * fit
        val dh = bmp.height * fit
        return RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    private fun mapToGuest(px: Float, py: Float): Pair<Int, Int> {
        val r = fittedRect() ?: return 0 to 0
        val x = (((px - r.left) / r.width()) * fbWidth).roundToInt().coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        val y = (((py - r.top) / r.height()) * fbHeight).roundToInt().coerceIn(0, (fbHeight - 1).coerceAtLeast(0))
        return x to y
    }

    private fun guestToView(x: Int, y: Int): Pair<Float, Float>? {
        val r = fittedRect() ?: return null
        if (fbWidth <= 0 || fbHeight <= 0) return null
        return (r.left + x.toFloat() / fbWidth * r.width()) to (r.top + y.toFloat() / fbHeight * r.height())
    }

    private fun updateControlRects() {
        val pad = 12f * density
        val chipH = 34f * density
        val chipW = 82f * density
        directChip.set(pad, pad, pad + chipW, pad + chipH)
        trackpadChip.set(directChip.right + 7f * density, pad, directChip.right + 7f * density + chipW, pad + chipH)

        val margin = 14f * density
        val h = 142f * density
        trackpadRect.set(margin, height - h - margin, width - margin, height - margin)
        val buttonH = 34f * density
        leftButtonRect.set(trackpadRect.left + 8f * density, trackpadRect.bottom - buttonH - 8f * density, trackpadRect.centerX() - 4f * density, trackpadRect.bottom - 8f * density)
        rightButtonRect.set(trackpadRect.centerX() + 4f * density, trackpadRect.bottom - buttonH - 8f * density, trackpadRect.right - 8f * density, trackpadRect.bottom - 8f * density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap
        val rect = fittedRect()
        if (bmp != null && rect != null) {
            synchronized(bmp) { canvas.drawBitmap(bmp, null, rect, paint) }
        } else {
            textPaint.textSize = 18f * density
            textPaint.color = Color.LTGRAY
            canvas.drawText(lastError.take(70), 20f * density, 42f * density, textPaint)
        }

        updateControlRects()
        drawModeChips(canvas)
        if (pointerMode == PointerMode.TRACKPAD) drawTrackpad(canvas)
        drawLocalCursor(canvas)
    }

    private fun drawModeChips(canvas: Canvas) {
        fun chip(rect: RectF, label: String, selected: Boolean) {
            paint.color = if (selected) Color.argb(235, 28, 83, 63) else Color.argb(190, 20, 25, 23)
            canvas.drawRoundRect(rect, 18f * density, 18f * density, paint)
            if (!selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                paint.color = Color.argb(150, 120, 150, 138)
                canvas.drawRoundRect(rect, 18f * density, 18f * density, paint)
                paint.style = Paint.Style.FILL
            }
            textPaint.color = Color.WHITE
            textPaint.textSize = 12f * density
            textPaint.textAlign = Paint.Align.CENTER
            val y = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, rect.centerX(), y, textPaint)
        }
        chip(directChip, "Direct", pointerMode == PointerMode.DIRECT)
        chip(trackpadChip, "Trackpad", pointerMode == PointerMode.TRACKPAD)
    }

    private fun drawTrackpad(canvas: Canvas) {
        paint.color = Color.argb(225, 18, 22, 21)
        canvas.drawRoundRect(trackpadRect, 20f * density, 20f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = Color.argb(110, 139, 232, 190)
        canvas.drawRoundRect(trackpadRect, 20f * density, 20f * density, paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.argb(220, 235, 242, 239)
        textPaint.textSize = 12f * density
        canvas.drawText("TRACKPAD", trackpadRect.centerX(), trackpadRect.top + 25f * density, textPaint)
        textPaint.color = Color.argb(155, 220, 228, 224)
        textPaint.textSize = 10f * density
        canvas.drawText("Move · tap to click · two-finger scroll", trackpadRect.centerX(), trackpadRect.top + 45f * density, textPaint)

        paint.color = Color.argb(180, 33, 41, 38)
        canvas.drawRoundRect(leftButtonRect, 12f * density, 12f * density, paint)
        canvas.drawRoundRect(rightButtonRect, 12f * density, 12f * density, paint)
        textPaint.color = Color.argb(210, 235, 242, 239)
        textPaint.textSize = 10f * density
        canvas.drawText("Left click", leftButtonRect.centerX(), leftButtonRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        canvas.drawText("Right click", rightButtonRect.centerX(), rightButtonRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawLocalCursor(canvas: Canvas) {
        val p = guestToView(cursorGuestX, cursorGuestY) ?: return
        val x = p.first
        val y = p.second
        val s = 18f * density
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + s)
            lineTo(x + 5.2f * density, y + 12f * density)
            lineTo(x + 9.2f * density, y + 20f * density)
            lineTo(x + 12.5f * density, y + 18.2f * density)
            lineTo(x + 8.5f * density, y + 10.7f * density)
            lineTo(x + 15f * density, y + 10.5f * density)
            close()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.argb(220, 0, 0, 0)
        canvas.drawPath(path, paint)
        paint.strokeWidth = 1.4f * density
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun setMode(mode: PointerMode) {
        if (pointerMode == mode) return
        pointerMode = mode
        dragging = false
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        invalidate()
    }

    private fun click(mask: Int) {
        sendPointer(mask, cursorGuestX, cursorGuestY)
        sendPointer(0, cursorGuestX, cursorGuestY)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun moveTrackpad(dx: Float, dy: Float) {
        val r = fittedRect() ?: return
        if (r.width() <= 0f || r.height() <= 0f) return
        val sensitivity = 1.45f
        cursorGuestX = (cursorGuestX + dx / r.width() * fbWidth * sensitivity).roundToInt().coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        cursorGuestY = (cursorGuestY + dy / r.height() * fbHeight * sensitivity).roundToInt().coerceIn(0, (fbHeight - 1).coerceAtLeast(0))
        sendPointer(if (dragging) 1 else 0, cursorGuestX, cursorGuestY)
        invalidate()
    }

    private fun scrollTrackpad(dy: Float) {
        scrollAccumulator += dy
        val threshold = 18f * density
        while (abs(scrollAccumulator) >= threshold) {
            val mask = if (scrollAccumulator > 0) 16 else 8
            sendPointer(mask, cursorGuestX, cursorGuestY)
            sendPointer(0, cursorGuestX, cursorGuestY)
            scrollAccumulator += if (scrollAccumulator > 0) -threshold else threshold
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (fbWidth <= 0 || fbHeight <= 0) return true
        updateControlRects()

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (directChip.contains(event.x, event.y)) { setMode(PointerMode.DIRECT); return true }
            if (trackpadChip.contains(event.x, event.y)) { setMode(PointerMode.TRACKPAD); return true }
            if (pointerMode == PointerMode.TRACKPAD && leftButtonRect.contains(event.x, event.y)) { click(1); return true }
            if (pointerMode == PointerMode.TRACKPAD && rightButtonRect.contains(event.x, event.y)) { click(4); return true }

            touchStartX = event.x
            touchStartY = event.y
            lastTouchX = event.x
            lastTouchY = event.y
            touchStartedMs = SystemClock.uptimeMillis()
            moved = false
            dragging = false
            maxPointers = 1
            scrollAccumulator = 0f

            if (pointerMode == PointerMode.DIRECT) {
                val p = mapToGuest(event.x, event.y)
                cursorGuestX = p.first
                cursorGuestY = p.second
                sendPointer(0, cursorGuestX, cursorGuestY)
                invalidate()
            }
            return true
        }

        maxPointers = maxOf(maxPointers, event.pointerCount)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastTouchX = event.getX(0)
                lastTouchY = event.getY(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.getX(0)
                val y = event.getY(0)
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                if (hypot((x - touchStartX).toDouble(), (y - touchStartY).toDouble()) > touchSlop) moved = true

                if (pointerMode == PointerMode.TRACKPAD) {
                    if (event.pointerCount >= 2) {
                        scrollTrackpad(dy)
                    } else {
                        if (!dragging && moved && SystemClock.uptimeMillis() - touchStartedMs > 360) {
                            dragging = true
                            sendPointer(1, cursorGuestX, cursorGuestY)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        moveTrackpad(dx, dy)
                    }
                } else {
                    val p = mapToGuest(x, y)
                    cursorGuestX = p.first
                    cursorGuestY = p.second
                    if (!dragging && moved && SystemClock.uptimeMillis() - touchStartedMs > 360) {
                        dragging = true
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPointerSentMs >= 12) {
                        sendPointer(if (dragging) 1 else 0, cursorGuestX, cursorGuestY)
                        lastPointerSentMs = now
                    }
                    invalidate()
                }
                lastTouchX = x
                lastTouchY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    sendPointer(0, cursorGuestX, cursorGuestY)
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                    if (pointerMode == PointerMode.DIRECT) {
                        val p = mapToGuest(event.x, event.y)
                        cursorGuestX = p.first
                        cursorGuestY = p.second
                    }
                    click(if (maxPointers >= 2) 4 else 1)
                }
                dragging = false
                return true
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_POINTER) != 0) {
            val p = mapToGuest(event.x, event.y)
            cursorGuestX = p.first
            cursorGuestY = p.second
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    sendPointer(0, cursorGuestX, cursorGuestY)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) {
                        sendPointer(if (v > 0) 8 else 16, cursorGuestX, cursorGuestY)
                        sendPointer(0, cursorGuestX, cursorGuestY)
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
        if (down) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
