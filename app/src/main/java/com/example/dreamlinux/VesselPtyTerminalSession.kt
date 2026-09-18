package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * One real PTY. Terminal emulation is upstream Termux; Vessel owns runtime
 * launch policy, clipboard integration and verified process lifetime.
 */
class VesselPtyTerminalSession private constructor(
    context: Context,
) : TerminalSessionClient {
    interface Listener {
        fun onOutput() {}
        fun onTitle(title: String) {}
        fun onExit(status: Int) {}
        fun onBell() {}
    }

    companion object {
        private const val TAG = "VesselTerminal"
        private const val TRANSCRIPT_ROWS = 3000

        fun start(
            context: Context,
            plan: VesselProrootLaunchPlan,
            columns: Int = 80,
            rows: Int = 24,
        ): VesselPtyTerminalSession {
            val wrapper = VesselPtyTerminalSession(context.applicationContext)
            val argv = plan.argv.toTypedArray()
            require(argv.isNotEmpty()) { "PTY launch argv is empty" }

            val environment = System.getenv().toMutableMap()
            plan.applyEnvironment(environment)
            val env = environment.entries
                .asSequence()
                .filter { it.key.isNotEmpty() && '\u0000' !in it.key && '\u0000' !in it.value }
                .sortedBy { it.key }
                .map { it.key + "=" + it.value }
                .toList()
                .toTypedArray()

            val terminal = TerminalSession(
                argv[0],
                plan.hostWorkingDirectory,
                argv,
                env,
                TRANSCRIPT_ROWS,
                wrapper,
            )
            wrapper.terminal = terminal

            try {
                terminal.initializeEmulator(columns.coerceAtLeast(4), rows.coerceAtLeast(2))
                val pid = terminal.pid
                val birthStat = VesselPtyNative.takeIdentity(pid)
                    ?: throw IOException("PTY identity handshake was not available for pid=" + pid)
                wrapper.identity = VesselSessionProcessCloser.captureLeader(pid, birthStat)
                wrapper.title = terminal.title.orEmpty()
                return wrapper
            } catch (error: Throwable) {
                val pid = terminal.pid
                if (pid > 1) {
                    val birthStat = runCatching { VesselPtyNative.takeIdentity(pid) }.getOrNull()
                    val identity = birthStat?.let {
                        runCatching { VesselSessionProcessCloser.captureLeader(pid, it) }.getOrNull()
                    }
                    if (identity != null) {
                        runCatching { VesselSessionProcessCloser.close(identity, 1500) }
                    } else {
                        runCatching { terminal.finishIfRunning() }
                    }
                }
                throw error
            }
        }
    }

    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile private var terminal: TerminalSession? = null
    @Volatile private var identity: VesselProcessIdentity? = null

    @Volatile var title: String = ""
        private set
    @Volatile var exitStatus: Int? = null
        private set
    @Volatile var cleanupWarning: String = ""
        internal set

    val pid: Int get() = terminal?.pid ?: -1
    val sessionId: Int get() = identity?.session ?: -1
    val isRunning: Boolean get() = terminal?.isRunning == true

    internal fun rawSession(): TerminalSession? = terminal

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun write(text: String) {
        if (text.isNotEmpty() && isRunning) terminal?.write(text)
    }

    fun sendKey(keyCode: Int) {
        val raw = terminal ?: return
        val emulator = raw.emulator ?: return
        val sequence = KeyHandler.getCode(
            keyCode,
            0,
            emulator.isCursorKeysApplicationMode(),
            emulator.isKeypadApplicationMode(),
        )
        if (sequence != null) raw.write(sequence)
    }

    fun sendControl(codePoint: Char) {
        if (!isRunning) return
        val upper = codePoint.uppercaseChar().code
        if (upper in 64..95) terminal?.write((upper - 64).toChar().toString())
    }

    fun resize(columns: Int, rows: Int) {
        terminal?.updateSize(columns.coerceAtLeast(4), rows.coerceAtLeast(2))
    }

    fun transcript(): String =
        terminal?.emulator?.screen?.transcriptTextWithoutJoinedLines.orEmpty()

    fun clearHistory() {
        terminal?.emulator?.screen?.clearTranscript()
        notifyOutput()
    }

    @Throws(IOException::class, InterruptedException::class)
    fun closeAndWait(timeoutMs: Long = 5000) {
        val captured = identity
        if (captured != null) {
            VesselSessionProcessCloser.close(captured, timeoutMs)
        } else {
            terminal?.finishIfRunning()
        }

        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(250)
        while (terminal?.isRunning == true && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
        if (terminal?.isRunning == true) throw IOException("PTY launcher has not exited yet")
        if (captured != null) VesselSessionProcessCloser.requireSessionEmpty(captured.session)
    }

    internal fun verifyNoSessionMembers() {
        val captured = identity ?: return
        VesselSessionProcessCloser.requireSessionEmpty(captured.session)
    }

    override fun onTextChanged(changedSession: TerminalSession?) = notifyOutput()

    override fun onTitleChanged(changedSession: TerminalSession?) {
        title = changedSession?.title.orEmpty()
        listeners.forEach { it.onTitle(title) }
    }

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        exitStatus = finishedSession?.exitStatus ?: -1
        listeners.forEach { it.onExit(exitStatus ?: -1) }
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        if (text.isNullOrEmpty()) return
        appContext.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Vessel terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString().orEmpty()
        if (text.isNotEmpty()) write(text)
    }

    override fun onBell(session: TerminalSession?) {
        listeners.forEach { it.onBell() }
    }

    override fun onColorsChanged(session: TerminalSession?) = notifyOutput()
    override fun onTerminalCursorStateChange(state: Boolean) = notifyOutput()
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {
        Log.e(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logWarn(tag: String?, message: String?) {
        Log.w(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logInfo(tag: String?, message: String?) {
        Log.i(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logDebug(tag: String?, message: String?) {
        Log.d(TAG, tag.orEmpty() + ": " + message.orEmpty())
    }
    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.w(TAG, tag.orEmpty() + ": " + message.orEmpty() + " " + e?.message.orEmpty())
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.w(TAG, tag.orEmpty() + ": " + e?.message.orEmpty())
    }

    private fun notifyOutput() {
        listeners.forEach { it.onOutput() }
    }
}
