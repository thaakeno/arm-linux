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

/** Phone-first embedded RFB 3.8 client with direct-touch and trackpad modes. */
class VncFramebufferView(context: Context) : View(context) {
    companion object { @Volatile var active: VncFramebufferView? = null }
    enum class PointerMode { DIRECT, TRACKPAD }

    private val running = AtomicBoolean(false)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wireLock = Any()
    private val density = resources.displayMetrics.density
    private val touchSlop = 7f * density

    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var lastError = "Waiting for Plasma desktop"
    @Volatile private var mode = PointerMode.DIRECT
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val displayRect = RectF()
    private var cursorX = 0
    private var cursorY = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var downMs = 0L
    private var moved = false
    private var dragging = false
    private var directButtonDown = false
    private var maxPointers = 1
    private var scrollX = 0f
    private var scrollY = 0f
    private var lastTapMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var physicalButtonMask = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        keepScreenOn = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        active = this
        start()
    }

    fun setPointerMode(newMode: PointerMode) {
        if (mode == newMode) return
        releaseButtons()
        mode = newMode
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun getPointerMode(): PointerMode = mode

    fun showKeyboard() {
        requestFocus()
        post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { ch ->
                    val cp = ch.code
                    val ks = if (cp < 0x100) cp else 0x01000000 or cp
                    sendKey(true, ks); sendKey(false, ks)
                }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { sendKey(true, 0xff08); sendKey(false, 0xff08) }
                return true
            }
        }
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
        releaseButtons()
        runCatching { socket?.close() }
        socket = null
    }

    private fun connectionLoop() {
        var backoff = 100L
        while (running.get()) {
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    receiveBufferSize = 2 * 1024 * 1024
                    sendBufferSize = 128 * 1024
                    connect(InetSocketAddress("127.0.0.1", TermuxUmlController.VNC_PORT), 2500)
                }
                socket = s
                backoff = 100L
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
                backoff = (backoff * 2).coerceAtMost(1000L)
            }
        }
    }

    private fun runSession(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1024 * 1024))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        output = out
        val version = ByteArray(12)
        input.readFully(version)
        check(String(version, Charsets.US_ASCII).startsWith("RFB ")) { "Not an RFB server" }
        synchronized(wireLock) { out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); out.flush() }
        val count = input.readUnsignedByte()
        check(count > 0) { "VNC server rejected the connection" }
        val security = ByteArray(count); input.readFully(security)
        check(security.any { it.toInt() and 0xff == 1 }) { "VNC None security unavailable" }
        synchronized(wireLock) { out.writeByte(1); out.flush() }
        check(input.readInt() == 0) { "VNC security negotiation failed" }
        synchronized(wireLock) { out.writeByte(1); out.flush() }
        fbWidth = input.readUnsignedShort(); fbHeight = input.readUnsignedShort(); input.skipBytes(16)
        val nameLen = input.readInt()
        if (nameLen in 0..65535) { val name = ByteArray(nameLen); input.readFully(name); lastError = String(name, Charsets.UTF_8) }
        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
        cursorX = fbWidth / 2; cursorY = fbHeight / 2
        sendPixelFormat(out); sendEncodings(out); requestUpdate(out, false); sendPointer(0, cursorX, cursorY); postInvalidate()
        while (running.get()) {
            when (input.readUnsignedByte()) {
                0 -> readFramebufferUpdate(input, out)
                2 -> Unit
                3 -> { input.skipBytes(3); val len = input.readInt(); if (len in 0..1_048_576) input.skipBytes(len) else error("Invalid clipboard size") }
                else -> error("Unsupported VNC server message")
            }
        }
    }

    private fun sendPixelFormat(out: DataOutputStream) = synchronized(wireLock) {
        out.writeByte(0); out.write(byteArrayOf(0,0,0)); out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255); out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(byteArrayOf(0,0,0)); out.flush()
    }

    private fun sendEncodings(out: DataOutputStream) = synchronized(wireLock) {
        out.writeByte(2); out.writeByte(0); out.writeShort(3); out.writeInt(5); out.writeInt(1); out.writeInt(0); out.flush()
    }

    private fun requestUpdate(out: DataOutputStream, incremental: Boolean) = synchronized(wireLock) {
        out.writeByte(3); out.writeByte(if (incremental) 1 else 0); out.writeShort(0); out.writeShort(0); out.writeShort(fbWidth); out.writeShort(fbHeight); out.flush()
    }

    private fun readPixel(input: DataInputStream): Int {
        val b=input.readUnsignedByte(); val g=input.readUnsignedByte(); val r=input.readUnsignedByte(); input.readUnsignedByte()
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }

    private fun decodeRaw(input: DataInputStream,bmp: Bitmap,x:Int,y:Int,w:Int,h:Int){
        val bytes=ByteArray(w*4); val row=IntArray(w)
        repeat(h){ yy -> input.readFully(bytes); var p=0; for(xx in 0 until w){ val b=bytes[p++].toInt() and 255; val g=bytes[p++].toInt() and 255; val r=bytes[p++].toInt() and 255; p++; row[xx]=-0x1000000 or (r shl 16) or (g shl 8) or b }; synchronized(bmp){ bmp.setPixels(row,0,w,x,y+yy,w,1) } }
    }

    private fun decodeCopyRect(input: DataInputStream,bmp:Bitmap,x:Int,y:Int,w:Int,h:Int){
        val sx=input.readUnsignedShort(); val sy=input.readUnsignedShort(); check(sx+w<=bmp.width && sy+h<=bmp.height); val px=IntArray(w*h)
        synchronized(bmp){ bmp.getPixels(px,0,w,sx,sy,w,h); bmp.setPixels(px,0,w,x,y,w,h) }
    }

    private fun decodeHextile(input: DataInputStream,bmp:Bitmap,x:Int,y:Int,w:Int,h:Int){
        val tile=IntArray(256); var bg=0; var fg=0; var bgValid=false; var fgValid=false; var ty=0
        while(ty<h){ val th=minOf(16,h-ty); var tx=0; while(tx<w){ val tw=minOf(16,w-tx); val sub=input.readUnsignedByte()
            if((sub and 1)!=0){ decodeRaw(input,bmp,x+tx,y+ty,tw,th); bgValid=false; fgValid=false; tx+=16; continue }
            if((sub and 2)!=0){ bg=readPixel(input); bgValid=true }; check(bgValid); Arrays.fill(tile,0,tw*th,bg)
            if((sub and 4)!=0){ fg=readPixel(input); fgValid=true }
            if((sub and 8)!=0){ val coloured=(sub and 16)!=0; repeat(input.readUnsignedByte()){ val color=if(coloured) readPixel(input) else { check(fgValid); fg }; val xy=input.readUnsignedByte(); val wh=input.readUnsignedByte(); val sx=xy ushr 4; val sy=xy and 15; val sw=(wh ushr 4)+1; val sh=(wh and 15)+1; for(yy in sy until sy+sh) Arrays.fill(tile,yy*tw+sx,yy*tw+sx+sw,color) } }
            synchronized(bmp){ bmp.setPixels(tile,0,tw,x+tx,y+ty,tw,th) }; if((sub and 16)!=0) fgValid=false; tx+=16 }
            ty+=16 }
    }

    private fun readFramebufferUpdate(input:DataInputStream,out:DataOutputStream){
        input.readUnsignedByte(); val n=input.readUnsignedShort(); val bmp=bitmap?:return
        repeat(n){ val x=input.readUnsignedShort(); val y=input.readUnsignedShort(); val w=input.readUnsignedShort(); val h=input.readUnsignedShort(); val enc=input.readInt(); check(x+w<=bmp.width && y+h<=bmp.height); when(enc){0->decodeRaw(input,bmp,x,y,w,h);1->decodeCopyRect(input,bmp,x,y,w,h);5->decodeHextile(input,bmp,x,y,w,h);else->error("Unexpected VNC encoding $enc")} }
        postInvalidateOnAnimation(); requestUpdate(out,true)
    }

    private fun layoutDisplay() {
        if (fbWidth <= 0 || fbHeight <= 0 || width <= 0 || height <= 0) { displayRect.set(0f,0f,width.toFloat(),height.toFloat()); return }
        val src = fbWidth.toFloat()/fbHeight
        val dst = width.toFloat()/height
        if (dst > src) { val w=height*src; val left=(width-w)/2f; displayRect.set(left,0f,left+w,height.toFloat()) }
        else { val h=width/src; val top=(height-h)/2f; displayRect.set(0f,top,width.toFloat(),top+h) }
    }

    private fun mapToGuest(px:Float,py:Float):Pair<Int,Int>{
        layoutDisplay(); val x=(((px-displayRect.left)/displayRect.width())*fbWidth).roundToInt().coerceIn(0,(fbWidth-1).coerceAtLeast(0)); val y=(((py-displayRect.top)/displayRect.height())*fbHeight).roundToInt().coerceIn(0,(fbHeight-1).coerceAtLeast(0)); return x to y
    }

    override fun onDraw(canvas:Canvas){
        canvas.drawColor(Color.BLACK); layoutDisplay(); val bmp=bitmap
        if(bmp!=null){ synchronized(bmp){ canvas.drawBitmap(bmp,null,displayRect,bitmapPaint) } }
        else { val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{ color=Color.LTGRAY; textAlign=Paint.Align.CENTER; textSize=14f*density }; canvas.drawText(lastError.take(64),width/2f,height/2f,p) }
    }

    private fun centroid(e:MotionEvent):Pair<Float,Float>{ var x=0f; var y=0f; for(i in 0 until e.pointerCount){x+=e.getX(i);y+=e.getY(i)}; return x/e.pointerCount to y/e.pointerCount }

    private fun relativeMove(dx:Float,dy:Float,button:Int){ if(fbWidth<=0||fbHeight<=0)return; val gain=(1.15f + (hypot(dx.toDouble(),dy.toDouble())/42.0).toFloat().coerceAtMost(1.0f)); cursorX=(cursorX+dx*gain*fbWidth/900f).roundToInt().coerceIn(0,fbWidth-1); cursorY=(cursorY+dy*gain*fbHeight/700f).roundToInt().coerceIn(0,fbHeight-1); sendPointer(button,cursorX,cursorY) }

    private fun doScroll(dx:Float,dy:Float){ scrollX+=dx; scrollY+=dy; val t=14f*density; while(abs(scrollY)>=t){ val m=if(scrollY>0)16 else 8; sendPointer(m,cursorX,cursorY); sendPointer(0,cursorX,cursorY); scrollY+=if(scrollY>0)-t else t }; while(abs(scrollX)>=t){ val m=if(scrollX>0)64 else 32; sendPointer(m,cursorX,cursorY); sendPointer(0,cursorX,cursorY); scrollX+=if(scrollX>0)-t else t } }

    private fun click(mask:Int){ sendPointer(mask,cursorX,cursorY); sendPointer(0,cursorX,cursorY); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private fun releaseButtons(){ if(directButtonDown||dragging||physicalButtonMask!=0) sendPointer(0,cursorX,cursorY); directButtonDown=false; dragging=false; physicalButtonMask=0 }

    override fun onTouchEvent(e:MotionEvent):Boolean{
        requestFocus(); if(fbWidth<=0||fbHeight<=0)return true
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{ downX=e.x;downY=e.y;lastX=e.x;lastY=e.y;val c=centroid(e);lastCentroidX=c.first;lastCentroidY=c.second;downMs=SystemClock.uptimeMillis();moved=false;dragging=false;maxPointers=1;scrollX=0f;scrollY=0f
                if(mode==PointerMode.DIRECT){ val p=mapToGuest(e.x,e.y);cursorX=p.first;cursorY=p.second;sendPointer(1,cursorX,cursorY);directButtonDown=true;performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }; return true }
            MotionEvent.ACTION_POINTER_DOWN->{ maxPointers=maxOf(maxPointers,e.pointerCount); if(directButtonDown){sendPointer(0,cursorX,cursorY);directButtonDown=false}; val c=centroid(e);lastCentroidX=c.first;lastCentroidY=c.second; return true }
            MotionEvent.ACTION_MOVE->{ maxPointers=maxOf(maxPointers,e.pointerCount); val c=centroid(e); if(e.pointerCount>=2){ doScroll(c.first-lastCentroidX,c.second-lastCentroidY); moved=true; lastCentroidX=c.first;lastCentroidY=c.second;return true }
                val x=e.x;val y=e.y;if(hypot((x-downX).toDouble(),(y-downY).toDouble())>touchSlop)moved=true
                if(mode==PointerMode.DIRECT){ val p=mapToGuest(x.coerceIn(displayRect.left,displayRect.right),y.coerceIn(displayRect.top,displayRect.bottom));cursorX=p.first;cursorY=p.second;sendPointer(if(directButtonDown)1 else 0,cursorX,cursorY) }
                else { var px=lastX;var py=lastY; for(h in 0 until e.historySize){ val hx=e.getHistoricalX(0,h);val hy=e.getHistoricalY(0,h);relativeMove(hx-px,hy-py,if(dragging)1 else 0);px=hx;py=hy }; if(!dragging&&moved&&SystemClock.uptimeMillis()-downMs>360){dragging=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)};relativeMove(x-px,y-py,if(dragging)1 else 0) }
                lastX=x;lastY=y;lastCentroidX=c.first;lastCentroidY=c.second;return true }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{ if(mode==PointerMode.DIRECT){ if(directButtonDown)sendPointer(0,cursorX,cursorY);directButtonDown=false }
                else if(dragging){sendPointer(0,cursorX,cursorY)} else if(e.actionMasked==MotionEvent.ACTION_UP&&!moved){ if(maxPointers>=3)click(2) else if(maxPointers>=2)click(4) else { val now=SystemClock.uptimeMillis();val dbl=now-lastTapMs<320&&hypot((e.x-lastTapX).toDouble(),(e.y-lastTapY).toDouble())<30f*density;click(1);if(dbl)click(1);lastTapMs=now;lastTapX=e.x;lastTapY=e.y } };dragging=false;return true }
        }
        return true
    }

    private fun androidButtonsToRfb(buttons:Int):Int { var m=0; if((buttons and MotionEvent.BUTTON_PRIMARY)!=0)m=m or 1; if((buttons and MotionEvent.BUTTON_TERTIARY)!=0)m=m or 2; if((buttons and MotionEvent.BUTTON_SECONDARY)!=0)m=m or 4; return m }

    override fun onGenericMotionEvent(e:MotionEvent):Boolean{
        if((e.source and InputDevice.SOURCE_CLASS_POINTER)!=0){ val p=mapToGuest(e.x.coerceIn(displayRect.left,displayRect.right),e.y.coerceIn(displayRect.top,displayRect.bottom));cursorX=p.first;cursorY=p.second
            when(e.actionMasked){ MotionEvent.ACTION_HOVER_MOVE,MotionEvent.ACTION_MOVE->{sendPointer(physicalButtonMask,cursorX,cursorY);return true}; MotionEvent.ACTION_SCROLL->{doScroll(-e.getAxisValue(MotionEvent.AXIS_HSCROLL)*24f*density,-e.getAxisValue(MotionEvent.AXIS_VSCROLL)*24f*density);return true}; MotionEvent.ACTION_BUTTON_PRESS,MotionEvent.ACTION_BUTTON_RELEASE->{physicalButtonMask=androidButtonsToRfb(e.buttonState);sendPointer(physicalButtonMask,cursorX,cursorY);return true} }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{sendAndroidKey(true,keyCode,event);return true}
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean{sendAndroidKey(false,keyCode,event);return true}

    fun sendAndroidKey(down:Boolean,keyCode:Int,event:KeyEvent?=null){ if(down)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);val ks=when(keyCode){KeyEvent.KEYCODE_ESCAPE->0xff1b;KeyEvent.KEYCODE_TAB->0xff09;KeyEvent.KEYCODE_ENTER->0xff0d;KeyEvent.KEYCODE_DEL->0xff08;KeyEvent.KEYCODE_FORWARD_DEL->0xffff;KeyEvent.KEYCODE_DPAD_LEFT->0xff51;KeyEvent.KEYCODE_DPAD_UP->0xff52;KeyEvent.KEYCODE_DPAD_RIGHT->0xff53;KeyEvent.KEYCODE_DPAD_DOWN->0xff54;KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_CTRL_RIGHT->0xffe3;KeyEvent.KEYCODE_ALT_LEFT,KeyEvent.KEYCODE_ALT_RIGHT->0xffe9;KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT->0xffe1;KeyEvent.KEYCODE_META_LEFT,KeyEvent.KEYCODE_META_RIGHT->0xffeb;else->event?.unicodeChar?.takeIf{it!=0}?:KeyEvent(keyCode,keyCode).unicodeChar};if(ks!=0){sendKey(down,if(ks<0x100)ks else ks)} }
    private fun sendKey(down:Boolean,keysym:Int){val out=output?:return;synchronized(wireLock){runCatching{out.writeByte(4);out.writeByte(if(down)1 else 0);out.writeShort(0);out.writeInt(keysym);out.flush()}}}
    private fun sendPointer(mask:Int,x:Int,y:Int){val out=output?:return;synchronized(wireLock){runCatching{out.writeByte(5);out.writeByte(mask);out.writeShort(x);out.writeShort(y);out.flush()}}}
}
