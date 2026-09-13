package com.example.dreamlinux

import android.content.Context
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
import kotlin.math.abs

/**
 * Final Vessel desktop view: Android Surface + direct Linux evdev input.
 * The display bridge owns the Surface; input never goes through VNC/RFB.
 */
class NativeLinuxSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    enum class PointerMode { DIRECT, TRACKPAD }
    var pointerMode = PointerMode.DIRECT

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var twoFingerY = 0f
    private var primaryDown = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFocus()
        VmSessionService.active?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        VmSessionService.active?.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VmSessionService.active?.detachSurface(holder.surface)
    }

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return BaseInputConnection(this, false)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus()
        val w = width.coerceAtLeast(1).toFloat(); val h = height.coerceAtLeast(1).toFloat()
        when (pointerMode) {
            PointerMode.DIRECT -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> VesselInputClient.absolute(e.x / w, e.y / h, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselInputClient.absolute(e.x / w, e.y / h, false)
            }
            PointerMode.TRACKPAD -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX=e.x; lastY=e.y; downX=e.x; downY=e.y; downAt=SystemClock.uptimeMillis(); moved=false }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) twoFingerY=(e.getY(0)+e.getY(1))*0.5f
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y=(e.getY(0)+e.getY(1))*0.5f
                        val dy=y-twoFingerY
                        if (abs(dy)>5f) { VesselInputClient.scroll(0, if (dy<0) 1 else -1); twoFingerY=y }
                    } else {
                        val dx=e.x-lastX; val dy=e.y-lastY
                        if (abs(dx)+abs(dy)>1.2f) { VesselInputClient.relative(dx*1.35f,dy*1.35f); moved = moved || abs(e.x-downX)+abs(e.y-downY)>8f }
                        lastX=e.x; lastY=e.y
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved && SystemClock.uptimeMillis()-downAt<260) {
                    VesselInputClient.button(0x110,true); VesselInputClient.button(0x110,false)
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            if (e.action == MotionEvent.ACTION_HOVER_MOVE || e.action == MotionEvent.ACTION_MOVE) {
                VesselInputClient.relative(e.getAxisValue(MotionEvent.AXIS_RELATIVE_X), e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y))
            }
            val vy=e.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt(); val hx=e.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt()
            if (vy!=0 || hx!=0) VesselInputClient.scroll(hx,vy)
            val left=(e.buttonState and MotionEvent.BUTTON_PRIMARY)!=0
            if (left!=primaryDown) { primaryDown=left; VesselInputClient.button(0x110,left) }
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

    private fun androidToLinuxKey(k: Int): Int = when (k) {
        KeyEvent.KEYCODE_ESCAPE->1; KeyEvent.KEYCODE_1->2; KeyEvent.KEYCODE_2->3; KeyEvent.KEYCODE_3->4; KeyEvent.KEYCODE_4->5; KeyEvent.KEYCODE_5->6; KeyEvent.KEYCODE_6->7; KeyEvent.KEYCODE_7->8; KeyEvent.KEYCODE_8->9; KeyEvent.KEYCODE_9->10; KeyEvent.KEYCODE_0->11
        KeyEvent.KEYCODE_DEL->14; KeyEvent.KEYCODE_TAB->15; KeyEvent.KEYCODE_Q->16; KeyEvent.KEYCODE_W->17; KeyEvent.KEYCODE_E->18; KeyEvent.KEYCODE_R->19; KeyEvent.KEYCODE_T->20; KeyEvent.KEYCODE_Y->21; KeyEvent.KEYCODE_U->22; KeyEvent.KEYCODE_I->23; KeyEvent.KEYCODE_O->24; KeyEvent.KEYCODE_P->25; KeyEvent.KEYCODE_ENTER->28
        KeyEvent.KEYCODE_CTRL_LEFT->29; KeyEvent.KEYCODE_A->30; KeyEvent.KEYCODE_S->31; KeyEvent.KEYCODE_D->32; KeyEvent.KEYCODE_F->33; KeyEvent.KEYCODE_G->34; KeyEvent.KEYCODE_H->35; KeyEvent.KEYCODE_J->36; KeyEvent.KEYCODE_K->37; KeyEvent.KEYCODE_L->38; KeyEvent.KEYCODE_SHIFT_LEFT->42; KeyEvent.KEYCODE_Z->44; KeyEvent.KEYCODE_X->45; KeyEvent.KEYCODE_C->46; KeyEvent.KEYCODE_V->47; KeyEvent.KEYCODE_B->48; KeyEvent.KEYCODE_N->49; KeyEvent.KEYCODE_M->50; KeyEvent.KEYCODE_SHIFT_RIGHT->54
        KeyEvent.KEYCODE_ALT_LEFT->56; KeyEvent.KEYCODE_SPACE->57; KeyEvent.KEYCODE_F1->59; KeyEvent.KEYCODE_F2->60; KeyEvent.KEYCODE_F3->61; KeyEvent.KEYCODE_F4->62; KeyEvent.KEYCODE_F5->63; KeyEvent.KEYCODE_F6->64; KeyEvent.KEYCODE_F7->65; KeyEvent.KEYCODE_F8->66; KeyEvent.KEYCODE_F9->67; KeyEvent.KEYCODE_F10->68; KeyEvent.KEYCODE_F11->87; KeyEvent.KEYCODE_F12->88
        KeyEvent.KEYCODE_HOME->102; KeyEvent.KEYCODE_DPAD_UP->103; KeyEvent.KEYCODE_PAGE_UP->104; KeyEvent.KEYCODE_DPAD_LEFT->105; KeyEvent.KEYCODE_DPAD_RIGHT->106; KeyEvent.KEYCODE_ENDCALL->107; KeyEvent.KEYCODE_DPAD_DOWN->108; KeyEvent.KEYCODE_PAGE_DOWN->109; KeyEvent.KEYCODE_INSERT->110; KeyEvent.KEYCODE_FORWARD_DEL->111; KeyEvent.KEYCODE_CTRL_RIGHT->97; KeyEvent.KEYCODE_ALT_RIGHT->100; KeyEvent.KEYCODE_META_LEFT->125; KeyEvent.KEYCODE_META_RIGHT->126
        else->0
    }
}
