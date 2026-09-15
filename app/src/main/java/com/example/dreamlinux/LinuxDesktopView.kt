package com.example.dreamlinux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/** Android Surface + real evdev input + guest cursor overlay. */
class LinuxDesktopView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }
    companion object { @Volatile var active: LinuxDesktopView? = null; private const val LEFT=0x110;private const val RIGHT=0x111;private const val MIDDLE=0x112;private const val DRAG_MS=350L }

    @Volatile private var pointerMode=PointerMode.TRACKPAD
    private var lastX=0f;private var lastY=0f;private var downX=0f;private var downY=0f;private var downAt=0L;private var maxPointers=1;private var travel=0f;private var scrollX=0f;private var scrollY=0f;private var dragging=false

    private val surfaceView=object:SurfaceView(context){
        override fun onCheckIsTextEditor()=true
        override fun onCreateInputConnection(a:EditorInfo):InputConnection{
            a.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;a.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            return object:BaseInputConnection(this,false){
                override fun commitText(t:CharSequence?,n:Int):Boolean{t?.toString()?.takeIf{it.isNotEmpty()}?.let(VesselInputClient::text);return true}
                override fun sendKeyEvent(e:KeyEvent):Boolean=handleKey(e)||super.sendKeyEvent(e)
                override fun deleteSurroundingText(before:Int,after:Int):Boolean{repeat(before.coerceAtMost(32)){tap(14)};repeat(after.coerceAtMost(32)){tap(111)};return true}
            }
        }
    }.apply{setBackgroundColor(Color.TRANSPARENT);holder.addCallback(this@LinuxDesktopView);isFocusable=true;isFocusableInTouchMode=true;keepScreenOn=false;setOnTouchListener{_,e->touch(e)};setOnGenericMotionListener{_,e->generic(e)};setOnKeyListener{_,_,e->handleKey(e)}}

    private val cursorView=object:View(context),Choreographer.FrameCallback{
        private var lastSerial=-1L;private var bitmap:Bitmap?=null
        override fun onAttachedToWindow(){super.onAttachedToWindow();Choreographer.getInstance().postFrameCallback(this)}
        override fun onDetachedFromWindow(){Choreographer.getInstance().removeFrameCallback(this);super.onDetachedFromWindow()}
        override fun doFrame(frameTimeNanos:Long){val s=VesselWaylandPresenter.cursorSerial();if(s!=lastSerial){lastSerial=s;val p=VesselWaylandPresenter.cursorPixels();if(p.size==4096)bitmap=Bitmap.createBitmap(p,64,64,Bitmap.Config.ARGB_8888)};invalidate();Choreographer.getInstance().postFrameCallback(this)}
        override fun onDraw(c:Canvas){super.onDraw(c);if(!VesselWaylandPresenter.cursorVisible())return;val b=bitmap?:return;val gw=VesselWaylandPresenter.guestWidth().coerceAtLeast(1);val gh=VesselWaylandPresenter.guestHeight().coerceAtLeast(1);val scale=min(width.toFloat()/gw,height.toFloat()/gh);val ox=(width-gw*scale)/2f;val oy=(height-gh*scale)/2f;val x=ox+(VesselWaylandPresenter.cursorX()-VesselWaylandPresenter.cursorHotX())*scale;val y=oy+(VesselWaylandPresenter.cursorY()-VesselWaylandPresenter.cursorHotY())*scale;c.drawBitmap(b,null,android.graphics.RectF(x,y,x+64*scale,y+64*scale),null)}
    }.apply{setBackgroundColor(Color.TRANSPARENT);isClickable=false;isFocusable=false}

    init{setBackgroundColor(Color.BLACK);addView(surfaceView,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));addView(cursorView,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))}
    override fun onAttachedToWindow(){super.onAttachedToWindow();active=this}
    override fun onDetachedFromWindow(){releaseDrag();if(active===this)active=null;VesselWaylandPresenter.detach();super.onDetachedFromWindow()}
    override fun surfaceCreated(h:SurfaceHolder){surfaceView.requestFocus();VesselWaylandPresenter.attach(h.surface)}
    override fun surfaceChanged(h:SurfaceHolder,format:Int,w:Int,ht:Int){val refresh=context.display?.refreshRate?:60f;val dpi=resources.displayMetrics.densityDpi;if(Build.VERSION.SDK_INT>=30)runCatching{h.surface.setFrameRate(refresh,Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)};VmSessionService.active?.configureDisplay(w,ht,dpi,refresh);VesselWaylandPresenter.attach(h.surface)}
    override fun surfaceDestroyed(h:SurfaceHolder){releaseDrag();VesselWaylandPresenter.detach()}

    fun setPointerMode(m:PointerMode){if(pointerMode!=m)releaseDrag();pointerMode=m}
    fun showKeyboard(){surfaceView.requestFocus();surfaceView.post{(context.getSystemService(Context.INPUT_METHOD_SERVICE)as?InputMethodManager)?.showSoftInput(surfaceView,InputMethodManager.SHOW_IMPLICIT)}}
    private fun mapped(x:Float,y:Float):Pair<Float,Float>{val gw=VesselWaylandPresenter.guestWidth().coerceAtLeast(1);val gh=VesselWaylandPresenter.guestHeight().coerceAtLeast(1);val scale=min(width.toFloat()/gw,height.toFloat()/gh);val ox=(width-gw*scale)/2f;val oy=(height-gh*scale)/2f;return (((x-ox)/(gw*scale)).coerceIn(0f,1f)) to (((y-oy)/(gh*scale)).coerceIn(0f,1f))}
    private fun touch(e:MotionEvent):Boolean{if(width<=0||height<=0)return true;surfaceView.requestFocus();if(pointerMode==PointerMode.DIRECT){releaseDrag();val(x,y)=mapped(e.x,e.y);when(e.actionMasked){MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->VesselInputClient.absolute(x,y,true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->VesselInputClient.absolute(x,y,false)};return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{releaseDrag();lastX=e.x;lastY=e.y;downX=e.x;downY=e.y;downAt=SystemClock.uptimeMillis();maxPointers=1;travel=0f;scrollX=e.x;scrollY=e.y}
            MotionEvent.ACTION_POINTER_DOWN->{releaseDrag();maxPointers=maxOf(maxPointers,e.pointerCount);scrollX=avgX(e);scrollY=avgY(e)}
            MotionEvent.ACTION_MOVE->{maxPointers=maxOf(maxPointers,e.pointerCount);if(e.pointerCount>=2){val ax=avgX(e);val ay=avgY(e);val dx=ax-scrollX;val dy=ay-scrollY;travel+=abs(dx)+abs(dy);VesselInputClient.scrollPrecise(-dx/8f,-dy/8f);scrollX=ax;scrollY=ay}else{val dx=e.x-lastX;val dy=e.y-lastY;val density=resources.displayMetrics.density;if(!dragging&&SystemClock.uptimeMillis()-downAt>=DRAG_MS&&travel<14f*density){VesselInputClient.button(LEFT,true);dragging=true};travel+=abs(dx)+abs(dy);VesselInputClient.relative(dx*1.35f,dy*1.35f);lastX=e.x;lastY=e.y}}
            MotionEvent.ACTION_POINTER_UP->{maxPointers=maxOf(maxPointers,e.pointerCount);val rem=(0 until e.pointerCount).filter{it!=e.actionIndex};if(rem.isNotEmpty()){lastX=e.getX(rem[0]);lastY=e.getY(rem[0])}}
            MotionEvent.ACTION_UP->{if(dragging)releaseDrag()else{val d=resources.displayMetrics.density;val moved=maxOf(travel,abs(e.x-downX)+abs(e.y-downY));if(SystemClock.uptimeMillis()-downAt<DRAG_MS&&moved<14f*d){val b=if(maxPointers>=2)RIGHT else LEFT;VesselInputClient.button(b,true);VesselInputClient.button(b,false)}}}
            MotionEvent.ACTION_CANCEL->releaseDrag()
        };return true}
    private fun generic(e:MotionEvent):Boolean{if((e.source and InputDevice.SOURCE_MOUSE)==InputDevice.SOURCE_MOUSE){when(e.actionMasked){MotionEvent.ACTION_SCROLL->{VesselInputClient.scrollPrecise(e.getAxisValue(MotionEvent.AXIS_HSCROLL),e.getAxisValue(MotionEvent.AXIS_VSCROLL));return true};MotionEvent.ACTION_BUTTON_PRESS,MotionEvent.ACTION_BUTTON_RELEASE->{val b=when(e.actionButton){MotionEvent.BUTTON_PRIMARY->LEFT;MotionEvent.BUTTON_SECONDARY->RIGHT;MotionEvent.BUTTON_TERTIARY->MIDDLE;else->return false};VesselInputClient.button(b,e.actionMasked==MotionEvent.ACTION_BUTTON_PRESS);return true};MotionEvent.ACTION_HOVER_MOVE->{val(x,y)=mapped(e.x,e.y);VesselInputClient.absolute(x,y,false);return true}}};return false}
    private fun releaseDrag(){if(dragging){VesselInputClient.button(LEFT,false);dragging=false}}
    private fun avgX(e:MotionEvent)=(0 until e.pointerCount).sumOf{e.getX(it).toDouble()}.toFloat()/e.pointerCount
    private fun avgY(e:MotionEvent)=(0 until e.pointerCount).sumOf{e.getY(it).toDouble()}.toFloat()/e.pointerCount
    private fun tap(c:Int){VesselInputClient.key(c,true);VesselInputClient.key(c,false)}
    private fun handleKey(e:KeyEvent):Boolean{val c=when(e.keyCode){KeyEvent.KEYCODE_ESCAPE->1;KeyEvent.KEYCODE_1->2;KeyEvent.KEYCODE_2->3;KeyEvent.KEYCODE_3->4;KeyEvent.KEYCODE_4->5;KeyEvent.KEYCODE_5->6;KeyEvent.KEYCODE_6->7;KeyEvent.KEYCODE_7->8;KeyEvent.KEYCODE_8->9;KeyEvent.KEYCODE_9->10;KeyEvent.KEYCODE_0->11;KeyEvent.KEYCODE_DEL->14;KeyEvent.KEYCODE_TAB->15;KeyEvent.KEYCODE_Q->16;KeyEvent.KEYCODE_W->17;KeyEvent.KEYCODE_E->18;KeyEvent.KEYCODE_R->19;KeyEvent.KEYCODE_T->20;KeyEvent.KEYCODE_Y->21;KeyEvent.KEYCODE_U->22;KeyEvent.KEYCODE_I->23;KeyEvent.KEYCODE_O->24;KeyEvent.KEYCODE_P->25;KeyEvent.KEYCODE_ENTER->28;KeyEvent.KEYCODE_CTRL_LEFT->29;KeyEvent.KEYCODE_A->30;KeyEvent.KEYCODE_S->31;KeyEvent.KEYCODE_D->32;KeyEvent.KEYCODE_F->33;KeyEvent.KEYCODE_G->34;KeyEvent.KEYCODE_H->35;KeyEvent.KEYCODE_J->36;KeyEvent.KEYCODE_K->37;KeyEvent.KEYCODE_L->38;KeyEvent.KEYCODE_SHIFT_LEFT->42;KeyEvent.KEYCODE_Z->44;KeyEvent.KEYCODE_X->45;KeyEvent.KEYCODE_C->46;KeyEvent.KEYCODE_V->47;KeyEvent.KEYCODE_B->48;KeyEvent.KEYCODE_N->49;KeyEvent.KEYCODE_M->50;KeyEvent.KEYCODE_SHIFT_RIGHT->54;KeyEvent.KEYCODE_ALT_LEFT->56;KeyEvent.KEYCODE_SPACE->57;KeyEvent.KEYCODE_CTRL_RIGHT->97;KeyEvent.KEYCODE_ALT_RIGHT->100;KeyEvent.KEYCODE_DPAD_UP->103;KeyEvent.KEYCODE_DPAD_LEFT->105;KeyEvent.KEYCODE_DPAD_RIGHT->106;KeyEvent.KEYCODE_DPAD_DOWN->108;KeyEvent.KEYCODE_FORWARD_DEL->111;KeyEvent.KEYCODE_META_LEFT->125;KeyEvent.KEYCODE_META_RIGHT->126;else->return false};when(e.action){KeyEvent.ACTION_DOWN->VesselInputClient.key(c,true);KeyEvent.ACTION_UP->VesselInputClient.key(c,false);else->return false};return true}
}
