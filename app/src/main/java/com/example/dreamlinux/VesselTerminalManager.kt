package com.example.dreamlinux

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VesselTerminalTabState(
    val id: Long,
    val number: Int,
    val title: String,
    val running: Boolean,
    val closing: Boolean,
    val exitStatus: Int? = null,
    val cleanupWarning: String = "",
)

data class VesselTerminalState(
    val ready: Boolean = false,
    val availability: String = "Terminal runtime not initialized",
    val tabs: List<VesselTerminalTabState> = emptyList(),
    val selectedId: Long = 0L,
    val maxSessions: Int = 8,
    val lastError: String = "",
)

/**
 * Process-wide PTY registry. Sessions outlive Compose pages and Activity
 * recreation; selecting a different terminal never restarts its shell.
 */
object VesselTerminalManager {
    private const val MAX_SESSIONS = 8

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = VesselTerminalTabs<VesselPtyTerminalSession>()
    private val _state = MutableStateFlow(VesselTerminalState(maxSessions = MAX_SESSIONS))
    val state = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var backend: VesselPtyRuntimeProvider? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                backend = VesselRuntimeFactory.createProrootScaffold(context.applicationContext) { _, _, _ -> }
            }
            refreshAvailabilityLocked(verifyHashes = false)
            publishLocked()
        }
    }

    fun refreshAvailability() {
        synchronized(lock) {
            refreshAvailabilityLocked(verifyHashes = false)
            publishLocked()
        }
    }

    fun createTerminal(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PTY creation must run on Android main looper"
        }
        val context = appContext ?: return fail("Terminal manager is not initialized")
        val runtime = backend ?: return fail("proroot backend is not initialized")

        synchronized(lock) {
            if (tabs.size() >= MAX_SESSIONS) return failLocked("Maximum of " + MAX_SESSIONS + " terminals reached")
            refreshAvailabilityLocked(verifyHashes = true)
            if (!_state.value.ready) return false
        }

        return try {
            val session = VesselPtyTerminalSession.start(context, runtime.terminalLaunchPlan())
            session.addListener(object : VesselPtyTerminalSession.Listener {
                override fun onTitle(title: String) {
                    synchronized(lock) { publishLocked() }
                }

                override fun onExit(status: Int) {
                    synchronized(lock) { publishLocked() }
                    scope.launch {
                        runCatching { session.verifyNoSessionMembers() }
                            .onFailure { error ->
                                session.cleanupWarning = error.message ?: "Terminal child cleanup is still pending"
                            }
                        synchronized(lock) { publishLocked() }
                    }
                }
            })
            synchronized(lock) {
                tabs.add(session)
                _state.value = _state.value.copy(lastError = "")
                publishLocked()
            }
            true
        } catch (error: Throwable) {
            fail(error.message ?: "Could not start terminal")
        }
    }

    fun select(id: Long) {
        synchronized(lock) {
            if (tabs.select(id)) publishLocked()
        }
    }

    fun close(id: Long) {
        val session = synchronized(lock) {
            val tab = tabs.find(id) ?: return
            if (!tabs.beginClose(id)) return
            publishLocked()
            tab.value
        }

        scope.launch {
            val failure = runCatching { session.closeAndWait(5000) }.exceptionOrNull()
            synchronized(lock) {
                if (failure == null) {
                    tabs.remove(id)
                    _state.value = _state.value.copy(lastError = "")
                } else {
                    tabs.closeFailed(id)
                    _state.value = _state.value.copy(
                        lastError = failure.message ?: "Terminal did not close cleanly",
                    )
                }
                publishLocked()
            }
        }
    }

    fun requestShutdownAll() {
        val ids = synchronized(lock) { tabs.snapshot().map { it.id } }
        ids.forEach(::close)
    }

    fun selectedSession(): VesselPtyTerminalSession? = synchronized(lock) {
        tabs.current()?.value
    }

    fun session(id: Long): VesselPtyTerminalSession? = synchronized(lock) {
        tabs.find(id)?.value
    }

    fun writeSelected(text: String) {
        selectedSession()?.write(text)
    }

    fun sendKeySelected(keyCode: Int) {
        selectedSession()?.sendKey(keyCode)
    }

    fun sendControlSelected(letter: Char) {
        selectedSession()?.sendControl(letter)
    }

    fun pasteSelected(text: String) {
        if (text.isNotEmpty()) selectedSession()?.write(text)
    }

    fun transcript(id: Long = state.value.selectedId): String =
        session(id)?.transcript().orEmpty()

    fun clearHistory(id: Long = state.value.selectedId) {
        session(id)?.clearHistory()
    }

    fun extraKey(label: String) {
        when (label) {
            "ESC" -> sendKeySelected(KeyEvent.KEYCODE_ESCAPE)
            "TAB" -> sendKeySelected(KeyEvent.KEYCODE_TAB)
            "↑" -> sendKeySelected(KeyEvent.KEYCODE_DPAD_UP)
            "↓" -> sendKeySelected(KeyEvent.KEYCODE_DPAD_DOWN)
            "←" -> sendKeySelected(KeyEvent.KEYCODE_DPAD_LEFT)
            "→" -> sendKeySelected(KeyEvent.KEYCODE_DPAD_RIGHT)
            "^C" -> sendControlSelected('C')
            "^D" -> sendControlSelected('D')
            "^Z" -> sendControlSelected('Z')
            else -> writeSelected(label)
        }
    }

    private fun refreshAvailabilityLocked(verifyHashes: Boolean) {
        val readiness = backend?.terminalReadiness(verifyHashes)
            ?: VesselTerminalRuntimeReadiness(false, "proroot backend is not initialized")
        _state.value = _state.value.copy(
            ready = readiness.ready,
            availability = readiness.reason,
        )
    }

    private fun publishLocked() {
        val snapshots = tabs.snapshot()
        _state.value = _state.value.copy(
            tabs = snapshots.map { tab ->
                val session = tab.value
                VesselTerminalTabState(
                    id = tab.id,
                    number = tab.number,
                    title = session.title.ifBlank { "Terminal " + tab.number },
                    running = session.isRunning,
                    closing = tab.closing,
                    exitStatus = session.exitStatus,
                    cleanupWarning = session.cleanupWarning,
                )
            },
            selectedId = tabs.current()?.id ?: 0L,
            maxSessions = MAX_SESSIONS,
        )
    }

    private fun fail(message: String): Boolean = synchronized(lock) {
        failLocked(message)
    }

    private fun failLocked(message: String): Boolean {
        _state.value = _state.value.copy(lastError = message)
        publishLocked()
        return false
    }
}
