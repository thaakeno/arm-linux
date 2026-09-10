package com.example.dreamlinux

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

enum class RuntimeBackend { UML_VENUS, AVF_LEGACY }

data class SessionState(
    val connected:Boolean=false,
    val running:Boolean=false,
    val cid:Int=-1,
    val name:String="Vessel Debian",
    val mode:String="uml",
    val stage:String="idle",
    val api:String="Termux RUN_COMMAND + loopback control",
    val vmRoot:String="",
    val console:String="",
    val terminal:String="",
    val debianTerminal:String="",
    val message:String="Ready to start Vessel",
    val busy:Boolean=false,
    val debianStarting:Boolean=false,
    val debianInstalled:Boolean=true,
    val debianInstalling:Boolean=false,
    val installProgress:Double=-1.0,
    val installBytes:Long=0L,
    val installTotal:Long=-1L,
    val kdeInstalled:Boolean=false,
    val kdeInstalling:Boolean=false,
    val kdeStage:String="not started",
    val capabilities:String="Not checked",
    val graphics:String="Venus · virglrenderer · host Adreno GPU",
    val internetReady:Boolean=false,
    val internetStage:String="offline",
    val backend:RuntimeBackend=RuntimeBackend.UML_VENUS
)

/**
 * Foreground owner for the rootless UML + Venus session.
 *
 * The old AVF/Shizuku implementation is intentionally left in the repository
 * for archaeology and fallback work, but it is no longer the primary runtime.
 * Vessel now controls the exact Termux UML stack that was proven on-device and
 * exposes the Debian KDE desktop through the APK's built-in VNC client.
 */
class VmSessionService : Service() {
    companion object {
        val state = MutableStateFlow(SessionState())
        var active: VmSessionService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var uml: TermuxUmlController
    private var requestedWidth = 1920
    private var requestedHeight = 1080
    private var requestedDpi = 144

    override fun onCreate() {
        super.onCreate()
        active = this
        uml = TermuxUmlController(this)

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("vessel-runtime", "Vessel Linux runtime", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            1,
            NotificationCompat.Builder(this, "vessel-runtime")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Vessel")
                .setContentText("Rootless ARM64 Linux runtime")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        )

        updateAvailability()
        scope.launch {
            while (isActive) {
                if (!state.value.busy) refresh(silent = true)
                delay(if (state.value.running) 900 else 1800)
            }
        }
        scope.launch { persistVerificationLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectRuntime()
        return START_STICKY
    }

    private fun updateAvailability() {
        val installed = uml.isTermuxInstalled()
        val permission = uml.hasRunCommandPermission()
        val message = when {
            !installed -> "Install Termux to host the rootless UML runtime"
            !permission -> "Grant Vessel permission to run commands in Termux"
            else -> "Runtime ready"
        }
        state.value = state.value.copy(
            connected = installed && permission,
            stage = if (installed && permission) "runtime_ready" else "runtime_setup",
            message = message,
            capabilities = "Termux=${if (installed) "installed" else "missing"} · RUN_COMMAND=${if (permission) "granted" else "missing"}"
        )
    }

    fun connectRuntime() {
        updateAvailability()
        if (!state.value.connected || state.value.busy) return
        operation("Connecting runtime") {
            val raw = uml.ensureDaemon()
            applyRuntime(raw, "Runtime connected")
        }
    }

    private fun applyRuntime(obj: JSONObject, fallbackMessage: String? = null) {
        val ok = obj.optBoolean("ok", false)
        val running = obj.optBoolean("running", false)
        val guest = obj.optBoolean("guestReady", false)
        val desktop = obj.optBoolean("desktopReady", false)
        val error = obj.optString("error").ifBlank { obj.optString("lastError") }
        val message = when {
            error.isNotBlank() -> error
            fallbackMessage != null -> fallbackMessage
            desktop -> "KDE Plasma is live"
            guest -> "Debian ARM64 is ready"
            running -> "Booting Debian ARM64"
            else -> "Runtime ready"
        }
        state.value = state.value.copy(
            connected = uml.isTermuxInstalled() && uml.hasRunCommandPermission(),
            running = running,
            debianStarting = running && !guest,
            kdeInstalled = desktop,
            kdeInstalling = state.value.kdeInstalling && !desktop,
            kdeStage = if (desktop) "KDE Plasma live · VNC ${TermuxUmlController.VNC_PORT}" else if (running) "desktop stopped" else "not started",
            internetReady = guest,
            internetStage = if (guest) "NAT via umnet/passt" else "offline",
            vmRoot = obj.optString("runtimeDir", state.value.vmRoot),
            console = obj.optString("logTail", state.value.console).takeLast(200_000),
            stage = when {
                desktop -> "desktop_ready"
                guest -> "debian_ready"
                running -> "uml_boot"
                else -> "runtime_ready"
            },
            message = message,
            graphics = if (guest) "Mesa Venus 26.2.2 · virglrenderer/Turnip · shared umshm transport" else state.value.graphics,
            capabilities = if (ok) "Rootless UML · Venus · VNC desktop · persistent ext4" else state.value.capabilities
        )
    }

    private suspend fun refresh(silent: Boolean = false) {
        updateAvailability()
        if (!state.value.connected) return
        try {
            val raw = uml.status()
            applyRuntime(raw)
        } catch (t: Throwable) {
            if (!silent) state.value = state.value.copy(message = t.message ?: "Runtime unavailable")
            if (state.value.running) state.value = state.value.copy(running = false, kdeInstalled = false)
        }
    }

    fun startVm() = startDebian(requestedWidth, requestedHeight, requestedDpi, 60)

    fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int) {
        requestedWidth = width.coerceIn(800, 3840)
        requestedHeight = height.coerceIn(600, 2160)
        requestedDpi = dpi.coerceIn(96, 240)
        operation("Starting Debian") {
            state.value = state.value.copy(debianStarting = true, message = "Booting ARM64 UML…")
            applyRuntime(uml.start(), "Debian ARM64 ready")
            state.value = state.value.copy(kdeInstalling = true, kdeStage = "starting Plasma", message = "Starting KDE Plasma…")
            val desktop = uml.startDesktop(requestedWidth, requestedHeight, requestedDpi)
            applyRuntime(desktop, "KDE Plasma is live")
            state.value = state.value.copy(kdeInstalling = false, kdeInstalled = true)
        }
    }

    fun startDebianDiagnostic() = operation("Starting diagnostic guest") {
        applyRuntime(uml.start(), "Debian diagnostic guest ready")
    }

    fun installDebian() = startDebian(requestedWidth, requestedHeight, requestedDpi, 60)

    fun installKde() = operation("Starting KDE Plasma") {
        state.value = state.value.copy(kdeInstalling = true, kdeStage = "installing/starting", message = "Preparing Plasma desktop…")
        applyRuntime(uml.startDesktop(requestedWidth, requestedHeight, requestedDpi), "KDE Plasma is live")
        state.value = state.value.copy(kdeInstalling = false, kdeInstalled = true)
    }

    fun stopVm() = operation("Stopping Linux") {
        applyRuntime(uml.stop(), "Linux stopped; disk retained")
        state.value = state.value.copy(kdeInstalled = false, kdeInstalling = false, internetReady = false)
    }

    fun debianConsole(command: String) = operation("Running command") {
        val result = uml.guest(command, 90)
        check(result.optBoolean("ok", false)) { result.optString("error", "Command failed") }
        val output = result.optString("output")
        state.value = state.value.copy(
            debianTerminal = (state.value.debianTerminal + "\n# $command\n$output").takeLast(256_000),
            message = "Command completed"
        )
        applyRuntime(result, "Command completed")
    }

    fun shell(command: String) = debianConsole(command)

    fun probeCapabilities() = operation("Checking runtime") {
        val termux = uml.isTermuxInstalled()
        val permission = uml.hasRunCommandPermission()
        val runtime = runCatching { uml.ensureDaemon() }.getOrNull()
        val text = buildString {
            append("Rootless UML ARM64")
            append(" · Termux=").append(if (termux) "yes" else "no")
            append(" · command permission=").append(if (permission) "yes" else "no")
            append(" · daemon=").append(if (runtime != null) "yes" else "no")
            if (runtime?.optBoolean("guestReady") == true) append(" · Venus guest=ready")
            if (runtime?.optBoolean("desktopReady") == true) append(" · Plasma=live")
        }
        state.value = state.value.copy(capabilities = text, message = text)
        runtime?.let { applyRuntime(it, text) }
    }

    fun attachSurface(surface: android.view.Surface) = Unit
    fun detachSurface(surface: android.view.Surface? = null) = Unit
    fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean = false
    fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean = false

    private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, message = label)
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                state.value = state.value.copy(
                    message = msg,
                    debianStarting = false,
                    kdeInstalling = false,
                    debianTerminal = (state.value.debianTerminal + "\nERROR: $msg\n").takeLast(256_000)
                )
            } finally {
                state.value = state.value.copy(busy = false)
                refresh(silent = true)
            }
        }
    }

    private suspend fun persistVerificationLoop() {
        while (scope.isActive) {
            val s = state.value
            withContext(Dispatchers.IO) {
                runCatching {
                    File(filesDir, "verification-runtime.json").writeText(
                        JSONObject()
                            .put("versionName", BuildConfig.VERSION_NAME)
                            .put("commit", BuildConfig.GIT_COMMIT)
                            .put("branch", BuildConfig.GIT_BRANCH)
                            .put("backend", s.backend.name)
                            .put("running", s.running)
                            .put("stage", s.stage)
                            .put("desktop", s.kdeInstalled)
                            .put("graphics", s.graphics)
                            .put("network", s.internetStage)
                            .put("updatedElapsedMs", SystemClock.elapsedRealtime())
                            .toString(2)
                    )
                }
            }
            delay(2_000)
        }
    }

    override fun onDestroy() {
        active = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
