package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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

/** Embedded RFB 3.8 display with phone-grade direct touch and precision trackpad input. */
class VncFramebufferView(context: Context) : View(context) {
    companion object { @Volatile var active: VncFramebufferView? = null }
    enum class PointerMode { DIRECT, TRACKPAD }

    private val running = AtomicBoolean(false)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wireLock = Any()
    private val density = resources.displayMetrics.density
    private val slop = 7f * density
    private val displayRect = RectF()

    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var mode = PointerMode.DIRECT
    @Volatile private var message = "Waiting for Plasma desktop"
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var cursorX = 0
    private var cursorY = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private var dragging = false
    private var maxPointers = 1
    private var twoLastX = 0f
    private var twoLastY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var physicalMask = 0

    init {
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true; keepScreenOn = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        active = this
        start()
    }

    fun setPointerMode(value: PointerMode) {
        if (mode == value) return
        releaseButtons(); mode = value
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun showKeyboard() {
        requestFocus()
        post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun tapKey(keysym: Int) { sendKey(true, keysym); sendKey(false, keysym) }

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(attrs: EditorInfo): InputConnection {
        attrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        attrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { ch -> val ks = if (ch.code < 0x100) ch.code else 0x01000000 or ch.code; sendKey(true, ks); sendKey(false, ks) }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { tapKey(0xff08) }
                return true
            }
        }
    }

    override fun onDetachedFromWindow() { stop(); if (active === this) active = null; super.onDetachedFromWindow() }

    private fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ loop() }, "vessel-rfb").apply { isDaemon = true; start() }
    }
    private fun stop() { running.set(false); releaseButtons(); runCatching { socket?.close() }; socket = null }

    private fun loop() {
        var backoff = 80L
        while (running.get()) {
            try {
                Socket().use { s ->
                    s.tcpNoDelay = true; s.keepAlive = true; s.receiveBufferSize = 2 * 1024 * 1024
                    s.connect(InetSocketAddress("127.0.0.1", TermuxUmlController.VNC_PORT), 2200)
                    socket = s; backoff = 80L; session(s)
                }
            } catch (t: Throwable) { message = t.message ?: t.javaClass.simpleName; postInvalidate() }
            finally { socket = null; output = null }
            if (running.get()) { Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(900L) }
        }
    }

    private fun session(s: Socket) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1024 * 1024))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024)); output = out
        val version = ByteArray(12); input.readFully(version); check(String(version, Charsets.US_ASCII).startsWith("RFB "))
        synchronized(wireLock) { out.write("RFB 003.008\n".toByteArray()); out.flush() }
        val n = input.readUnsignedByte(); check(n > 0); val sec = ByteArray(n); input.readFully(sec); check(sec.any { (it.toInt() and 255) == 1 })
        synchronized(wireLock) { out.writeByte(1); out.flush() }; check(input.readInt() == 0)
        synchronized(wireLock) { out.writeByte(1); out.flush() }
        fbWidth = input.readUnsignedShort(); fbHeight = input.readUnsignedShort(); input.skipBytes(16)
        val nameLen = input.readInt(); if (nameLen in 0..65535) { val name = ByteArray(nameLen); input.readFully(name); message = String(name) }
        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888); cursorX = fbWidth / 2; cursorY = fbHeight / 2
        pixelFormat(out); encodings(out); update(out, false); sendPointer(0, cursorX, cursorY); postInvalidate()
        while (running.get()) when (input.readUnsignedByte()) {
            0 -> framebuffer(input, out)
            2 -> Unit
            3 -> { input.skipBytes(3); val len = input.readInt(); if (len in 0..1_048_576) input.skipBytes(len) else error("clipboard too large") }
            else -> error("unsupported RFB server message")
        }
    }

    private fun pixelFormat(out: DataOutputStream) = synchronized(wireLock) {
        out.writeByte(0); out.write(byteArrayOf(0,0,0)); out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255); out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(byteArrayOf(0,0,0)); out.flush()
    }
    private fun encodings(out: DataOutputStream) = synchronized(wireLock) { out.writeByte(2); out.writeByte(0); out.writeShort(3); out.writeInt(5); out.writeInt(1); out.writeInt(0); out.flush() }
    private fun update(out: DataOutputStream, incremental: Boolean) = synchronized(wireLock) { out.writeByte(3); out.writeByte(if (incremental) 1 else 0); out.writeShort(0); out.writeShort(0); out.writeShort(fbWidth); out.writeShort(fbHeight); out.flush() }

    private fun readPixel(input: DataInputStream): Int { val b=input.readUnsignedByte(); val g=input.readUnsignedByte(); val r=input.readUnsignedByte(); input.readUnsignedByte(); return -0x1000000 or (r shl 16) or (g shl 8) or b }
    private fun raw(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val bytes=ByteArray(w*4); val row=IntArray(w)
        repeat(h) { yy -> input.readFully(bytes); var p=0; for (xx in 0 until w) { val b=bytes[p++].toInt() and 255; val g=bytes[p++].toInt() and 255; val r=bytes[p++].toInt() and 255; p++; row[xx]=-0x1000000 or (r shl 16) or (g shl 8) or b }; synchronized(bmp) { bmp.setPixels(row,0,w,x,y+yy,w,1) } }
    }
    private fun copyRect(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) { val sx=input.readUnsignedShort(); val sy=input.readUnsignedShort(); val px=IntArray(w*h); synchronized(bmp) { bmp.getPixels(px,0,w,sx,sy,w,h); bmp.setPixels(px,0,w,x,y,w,h) } }
    private fun hextile(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val tile=IntArray(256); var bg=0; var fg=0; var bgValid=false; var fgValid=false; var ty=0
        while (ty<h) { val th=minOf(16,h-ty); var tx=0; while(tx<w) { val tw=minOf(16,w-tx); val sub=input.readUnsignedByte()
            if ((sub and 1)!=0) { raw(input,bmp,x+tx,y+ty,tw,th); bgValid=false; fgValid=false; tx+=16; continue }
            if ((sub and 2)!=0) { bg=readPixel(input); bgValid=true }; check(bgValid); Arrays.fill(tile,0,tw*th,bg)
            if ((sub and 4)!=0) { fg=readPixel(input); fgValid=true }
            if ((sub and 8)!=0) { val coloured=(sub and 16)!=0; repeat(input.readUnsignedByte()) { val c=if(coloured) readPixel(input) else { check(fgValid); fg }; val xy=input.readUnsignedByte(); val wh=input.readUnsignedByte(); val sx=xy ushr 4; val sy=xy and 15; val sw=(wh ushr 4)+1; val sh=(wh and 15)+1; for (yy in sy until sy+sh) Arrays.fill(tile,yy*tw+sx,yy*tw+sx+sw,c) } }
            synchronized(bmp) { bmp.setPixels(tile,0,tw,x+tx,y+ty,tw,th) }; if ((sub and 16)!=0) fgValid=false; tx+=16
        }; ty+=16 }
    }
    private fun framebuffer(input: DataInputStream, out: DataOutputStream) {
        input.readUnsignedByte(); val count=input.readUnsignedShort(); val bmp=bitmap ?: return
        repeat(count) { val x=input.readUnsignedShort(); val y=input.readUnsignedShort(); val w=input.readUnsignedShort(); val h=input.readUnsignedShort(); val enc=input.readInt(); check(x+w<=bmp.width && y+h<=bmp.height); when(enc) { 0->raw(input,bmp,x,y,w,h); 1->copyRect(input,bmp,x,y,w,h); 5->hextile(input,bmp,x,y,w,h); else->error("unexpected RFB encoding $enc") } }
        postInvalidateOnAnimation(); update(out,true)
    }

    private fun layoutDisplay() {
        if (fbWidth<=0 || fbHeight<=0 || width<=0 || height<=0) { displayRect.set(0f,0f,width.toFloat(),height.toFloat()); return }
        val src=fbWidth.toFloat()/fbHeight; val dst=width.toFloat()/height
        if (dst>src) { val w=height*src; val left=(width-w)/2; displayRect.set(left,0f,left+w,height.toFloat()) }
        else { val h=width/src; val top=(height-h)/2; displayRect.set(0f,top,width.toFloat(),top+h) }
    }
    private fun guestPoint(px: Float, py: Float): Pair<Int,Int> { layoutDisplay(); val x=(((px-displayRect.left)/displayRect.width())*fbWidth).roundToInt().coerceIn(0,(fbWidth-1).coerceAtLeast(0)); val y=(((py-displayRect.top)/displayRect.height())*fbHeight).roundToInt().coerceIn(0,(fbHeight-1).coerceAtLeast(0)); return x to y }
    override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.BLACK); layoutDisplay(); val bmp=bitmap; if (bmp!=null) synchronized(bmp) { canvas.drawBitmap(bmp,null,displayRect,paint) } else Paint(Paint.ANTI_ALIAS_FLAG).also { it.color=Color.LTGRAY; it.textAlign=Paint.Align.CENTER; it.textSize=14*density; canvas.drawText(message.take(72),width/2f,height/2f,it) } }

    private fun relative(dx: Float, dy: Float, mask: Int) {
        if (fbWidth<=0 || fbHeight<=0) return
        val speed=hypot(dx.toDouble(),dy.toDouble()).toFloat(); val gain=1.05f+(speed/30f).coerceAtMost(1.25f)
        cursorX=(cursorX+dx*gain*fbWidth/950f).roundToInt().coerceIn(0,fbWidth-1); cursorY=(cursorY+dy*gain*fbHeight/720f).roundToInt().coerceIn(0,fbHeight-1); sendPointer(mask,cursorX,cursorY)
    }
    private fun click(mask: Int) { sendPointer(mask,cursorX,cursorY); sendPointer(0,cursorX,cursorY); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    private fun scroll(dx: Float, dy: Float) {
        scrollX+=dx; scrollY+=dy; val step=18*density
        while(abs(scrollY)>=step) { val mask=if(scrollY<0)8 else 16; sendPointer(mask,cursorX,cursorY); sendPointer(0,cursorX,cursorY); scrollY += if(scrollY<0) step else -step }
        while(abs(scrollX)>=step) { val mask=if(scrollX<0)32 else 64; sendPointer(mask,cursorX,cursorY); sendPointer(0,cursorX,cursorY); scrollX += if(scrollX<0) step else -step }
    }
    private fun centroid(e: MotionEvent): Pair<Float,Float> { var x=0f; var y=0f; repeat(e.pointerCount) { x+=e.getX(it); y+=e.getY(it) }; return x/e.pointerCount to y/e.pointerCount }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus(); if (fbWidth<=0 || fbHeight<=0) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; lastX=e.x; lastY=e.y; downAt=SystemClock.uptimeMillis(); moved=false; dragging=false; maxPointers=1; scrollX=0f; scrollY=0f; if(mode==PointerMode.DIRECT) { val p=guestPoint(e.x,e.y); cursorX=p.first; cursorY=p.second; sendPointer(0,cursorX,cursorY) } }
            MotionEvent.ACTION_POINTER_DOWN -> { maxPointers=maxOf(maxPointers,e.pointerCount); if(e.pointerCount>=2) { val c=centroid(e); twoLastX=c.first; twoLastY=c.second } }
            MotionEvent.ACTION_MOVE -> {
                if (mode==PointerMode.DIRECT) {
                    val p=guestPoint(e.x,e.y); val distance=hypot((e.x-downX).toDouble(),(e.y-downY).toDouble()).toFloat(); if(distance>slop) moved=true
                    cursorX=p.first; cursorY=p.second
                    if (moved && !dragging) { dragging=true; sendPointer(1,cursorX,cursorY) } else sendPointer(if(dragging)1 else 0,cursorX,cursorY)
                } else if (e.pointerCount>=2) {
                    val c=centroid(e); scroll(c.first-twoLastX,c.second-twoLastY); twoLastX=c.first; twoLastY=c.second; moved=true
                } else {
                    val dx=e.x-lastX; val dy=e.y-lastY; if(abs(e.x-downX)+abs(e.y-downY)>slop) moved=true
                    if(!dragging && moved && SystemClock.uptimeMillis()-downAt>360) { dragging=true; sendPointer(1,cursorX,cursorY) }
                    relative(dx,dy,if(dragging)1 else 0); lastX=e.x; lastY=e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if(mode==PointerMode.DIRECT) { if(dragging) sendPointer(0,cursorX,cursorY) else click(1) }
                else { if(dragging) sendPointer(0,cursorX,cursorY) else if(!moved && SystemClock.uptimeMillis()-downAt<320) click(if(maxPointers>=2)4 else 1) }
                dragging=false
            }
            MotionEvent.ACTION_CANCEL -> releaseButtons()
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_MOUSE)==InputDevice.SOURCE_MOUSE) {
            if (e.action==MotionEvent.ACTION_HOVER_MOVE || e.action==MotionEvent.ACTION_MOVE) {
                val rx=e.getAxisValue(MotionEvent.AXIS_RELATIVE_X); val ry=e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if(rx!=0f || ry!=0f) relative(rx,ry,physicalMask) else { val p=guestPoint(e.x,e.y); cursorX=p.first; cursorY=p.second; sendPointer(physicalMask,cursorX,cursorY) }
            }
            val v=e.getAxisValue(MotionEvent.AXIS_VSCROLL); val h=e.getAxisValue(MotionEvent.AXIS_HSCROLL); if(v!=0f || h!=0f) scroll(-h*20*density,-v*20*density)
            val mask=(if((e.buttonState and MotionEvent.BUTTON_PRIMARY)!=0)1 else 0) or (if((e.buttonState and MotionEvent.BUTTON_TERTIARY)!=0)2 else 0) or (if((e.buttonState and MotionEvent.BUTTON_SECONDARY)!=0)4 else 0)
            if(mask!=physicalMask) { physicalMask=mask; sendPointer(mask,cursorX,cursorY) }
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val ks=androidKeysym(e)
        if(ks!=0) { sendKey(e.action==KeyEvent.ACTION_DOWN,ks); return true }
        return super.dispatchKeyEvent(e)
    }

    private fun androidKeysym(e: KeyEvent): Int = when(e.keyCode) {
        KeyEvent.KEYCODE_DEL->0xff08; KeyEvent.KEYCODE_TAB->0xff09; KeyEvent.KEYCODE_ENTER->0xff0d; KeyEvent.KEYCODE_ESCAPE->0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT->0xff51; KeyEvent.KEYCODE_DPAD_UP->0xff52; KeyEvent.KEYCODE_DPAD_RIGHT->0xff53; KeyEvent.KEYCODE_DPAD_DOWN->0xff54
        KeyEvent.KEYCODE_PAGE_UP->0xff55; KeyEvent.KEYCODE_PAGE_DOWN->0xff56; KeyEvent.KEYCODE_MOVE_HOME->0xff50; KeyEvent.KEYCODE_MOVE_END->0xff57; KeyEvent.KEYCODE_FORWARD_DEL->0xffff
        KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT->0xffe1; KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_CTRL_RIGHT->0xffe3; KeyEvent.KEYCODE_ALT_LEFT,KeyEvent.KEYCODE_ALT_RIGHT->0xffe9; KeyEvent.KEYCODE_META_LEFT,KeyEvent.KEYCODE_META_RIGHT->0xffeb
        else -> { val unicode=e.unicodeChar; if(unicode>0) unicode else 0 }
    }

    private fun sendPointer(mask: Int, x: Int, y: Int) { val out=output ?: return; synchronized(wireLock) { runCatching { out.writeByte(5); out.writeByte(mask); out.writeShort(x); out.writeShort(y); out.flush() } } }
    private fun sendKey(down: Boolean, keysym: Int) { val out=output ?: return; synchronized(wireLock) { runCatching { out.writeByte(4); out.writeByte(if(down)1 else 0); out.writeShort(0); out.writeInt(keysym); out.flush() } } }
    private fun releaseButtons() { if(dragging || physicalMask!=0) sendPointer(0,cursorX,cursorY); dragging=false; physicalMask=0 }
}
