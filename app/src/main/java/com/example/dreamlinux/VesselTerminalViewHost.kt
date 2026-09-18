package com.example.dreamlinux

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * Thin Android View host around upstream TerminalView. Vessel does not duplicate
 * VT parsing, selection, scrollback or IME logic.
 */
class VesselTerminalViewHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TerminalViewClient, VesselPtyTerminalSession.Listener {
    companion object {
        private const val TAG = "VesselTerminalView"
        private const val MIN_FONT_SP = 9f
        private const val MAX_FONT_SP = 24f
    }

    private val terminalView = TerminalView(context, null)
    private var bound: VesselPtyTerminalSession? = null
    private var fontSp = 13f

    init {
        setBackgroundColor(Color.BLACK)
        terminalView.setTerminalViewClient(this)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        addView(
            terminalView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        applyFont()
    }

    fun bind(session: VesselPtyTerminalSession?) {
        if (bound === session) return
        bound?.removeListener(this)
        bound = session
        session?.addListener(this)
        terminalView.attachSession(session?.rawSession())
        terminalView.onScreenUpdated()
        if (session != null) terminalView.requestFocus()
    }

    fun showKeyboard() {
        if (bound?.isRunning != true) return
        terminalView.requestFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDetachedFromWindow() {
        bound?.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bound?.addListener(this)
    }

    override fun onOutput() {
        terminalView.postOnAnimation { terminalView.onScreenUpdated() }
    }

    override fun onTitle(title: String) = Unit
    override fun onExit(status: Int) = onOutput()
    override fun onBell() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onScale(scale: Float): Float {
        when {
            scale > 1.06f -> fontSp = (fontSp + 1f).coerceAtMost(MAX_FONT_SP)
            scale < 0.94f -> fontSp = (fontSp - 1f).coerceAtLeast(MIN_FONT_SP)
            else -> return 1f
        }
        applyFont()
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = bound?.isRunning == true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = bound?.isRunning != true
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = bound?.isRunning != true

    override fun onEmulatorSet() = onOutput()

    override fun logError(tag: String?, message: String?) {
        Log.e(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logWarn(tag: String?, message: String?) {
        Log.w(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logInfo(tag: String?, message: String?) {
        Log.i(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.w(TAG, tag.orEmpty() + ": " + message.orEmpty() + " " + e?.message.orEmpty())
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.w(TAG, tag.orEmpty() + ": " + e?.message.orEmpty())
    }

    private fun applyFont() {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSp,
            resources.displayMetrics,
        ).roundToInt()
        terminalView.setTextSize(px)
    }
}
