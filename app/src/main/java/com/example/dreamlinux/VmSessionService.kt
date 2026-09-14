package com.example.dreamlinux

import android.app.*
import android.content.Intent
import android.os.IBinder
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
    val graphics:String="Mesa Zink + Venus · host Adreno GPU",
    val internetReady:Boolean=false,
    val internetStage:String="offline",
    val progressPhase:String="idle",
    val progressPercent:Int=-1,
    val progressDetail:String="Runtime ready",
    val lastError:String="",
    val uptimeMs:Long=0L,
    val backend:RuntimeBackend=RuntimeBackend.UML_VENUS
)

class VmSessionService : Service() {
    companion object {
        val state = MutableStateFlow(SessionState())
        var active: VmSessionService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var uml: TermuxUmlController
    private var requestedWidth = 1600
    private var requestedHeight = 720
    private var requestedDpi = 120

    private fun shortError(raw: String): String {
        val e = raw.trim()
        if (e.isBlank()) return ""
        return when {
            e.contains("Guest command transport timed out", ignoreCase = true) ||
                e.contains("completion marker", ignoreCase = true) ->
                "Guest command transport stalled after Debian boot"
            e.contains("Failed to lock", ignoreCase = true) || e.contains("disk is locked", ignoreCase = true) ->
                "Debian disk is already in use by another UML process"
            e.contains("Mesa Venus", ignoreCase = true) -> "Mesa Venus is missing or failed to initialize"
            e.contains("runtime daemon did not start", ignoreCase = true) -> "Vessel runtime daemon failed to start"
            e.contains("UML exited during boot", ignoreCase = true) -> "Debian UML exited during boot"
            e.contains("did not reach a shell", ignoreCase = true) -> "Debian did not reach a shell before timeout"
            else -> e.lineSequence().firstOrNull()?.take(180) ?: "Runtime failed"
        }
    }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0L) return "0s"
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return when {
            h > 0 -> "%dh %02dm %02ds".format(h, m, s)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "${s}s"
        }
    }

    private fun withDetailedErrorLog(base: String, rawError: String): String {
        if (rawError.isBlank()) return base.takeLast(200_000)
        val marker = "[Vessel error details]"
        if (base.contains(marker) && base.contains(rawError.take(120))) return base.takeLast(200_000)
        return (base.trimEnd() + "\n\n$marker\n" + rawError.trim()).takeLast(200_000)
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        uml = TermuxUmlController(this)

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("vessel-runtime", "Vessel Linux runtime", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VesselActivity::class.java),
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
                refresh(silent = true)
                delay(if (state.value.busy || state.value.running || state.value.progressPhase !in setOf("idle", "desktop_ready", "error")) 300 else 1200)
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
        val connected = installed && permission
        val current = state.value
        state.value = if (connected) {
            current.copy(connected = true)
        } else {
            current.copy(
                connected = false,
                running = false,
                kdeInstalled = false,
                uptimeMs = 0L,
                stage = "runtime_setup",
                message = if (!installed) "Install Termux to host the rootless UML runtime" else "Grant Vessel permission to run commands in Termux",
                capabilities = "Termux=${if (installed) "installed" else "missing"} · RUN_COMMAND=${if (permission) "granted" else "missing"}"
            )
        }
    }

    fun connectRuntime() {
        updateAvailability()
        if (!state.value.connected || state.value.busy) return
        operation("Connecting runtime") {
            applyRuntime(uml.ensureDaemon(), "Runtime connected")
        }
    }

    private fun applyRuntime(obj: JSONObject, fallbackMessage: String? = null) {
        val ok = obj.optBoolean("ok", false)
        val running = obj.optBoolean("running", false)
        val guest = obj.optBoolean("guestReady", false)
        val desktop = obj.optBoolean("desktopReady", false)
        val phase = obj.optString("progressPhase", state.value.progressPhase)
        val candidateError = obj.optString("error").ifBlank { obj.optString("lastError") }
        val rawError = if (!ok || phase == "error") candidateError else ""
        val error = shortError(rawError)
        val percent = obj.optInt("progressPercent", state.value.progressPercent)
        val rawDetail = obj.optString("progressDetail", state.value.progressDetail).ifBlank { state.value.progressDetail }
        val detail = when (phase) {
            "queued" -> "Starting Vessel Linux"
            "command_agent" -> "Connecting Debian control channel"
            "venus" -> "Starting Venus GPU transport"
            "debian_ready" -> "Debian + Venus ready"
            "compositor_prepare" -> "Preparing Vessel compositor"
            "compositor_deps" -> "Installing minimal Wayland runtime"
            "compositor_start" -> "Starting Vessel compositor"
            "compositor_ready" -> "Connecting native compositor to Android"
            "desktop_ready" -> "Vessel desktop live"
            else -> rawDetail
        }
        val uptime = obj.optLong("uptimeMs", if (running) state.value.uptimeMs else 0L)
        val compositorStarting = running && phase in setOf("compositor_prepare", "compositor_deps", "compositor_start", "compositor_ready")
        val message = when {
            error.isNotBlank() -> error
            desktop -> "Vessel desktop is live · ${formatUptime(uptime)}"
            detail.isNotBlank() && (state.value.busy || running || phase != "idle") -> "$detail · ${formatUptime(uptime)}"
            fallbackMessage != null -> fallbackMessage
            guest -> "Debian ARM64 ready · ${formatUptime(uptime)}"
            running -> "Booting Debian ARM64 · ${formatUptime(uptime)}"
            else -> "Runtime ready"
        }
        val rawLog = obj.optString("logTail", state.value.console)
        state.value = state.value.copy(
            connected = uml.isTermuxInstalled() && uml.hasRunCommandPermission(),
            running = running,
            debianStarting = running && !guest,
            kdeInstalled = desktop,
            kdeInstalling = !desktop && compositorStarting,
            kdeStage = when {
                desktop -> "Vessel compositor live · native dma-buf display · ${formatUptime(uptime)}"
                compositorStarting -> detail
                running && guest -> "Debian ready · compositor not started"
                else -> "not started"
            },
            internetReady = guest,
            internetStage = if (guest) "NAT via umnet/passt" else "offline",
            vmRoot = obj.optString("runtimeDir", state.value.vmRoot),
            console = withDetailedErrorLog(rawLog, rawError),
            progressPhase = phase,
            progressPercent = percent,
            progressDetail = detail,
            lastError = error,
            uptimeMs = if (running) uptime else 0L,
            stage = when {
                desktop -> "desktop_ready"
                phase.isNotBlank() && phase != "idle" -> phase
                guest -> "debian_ready"
                running -> "uml_boot"
                else -> "runtime_ready"
            },
            message = message,
            graphics = if (guest) "Mesa Zink + Venus · Adreno GPU · dma-buf/umshm" else state.value.graphics,
            capabilities = if (ok) "Rootless UML · Vessel Wayland compositor · native dma-buf · persistent ext4" else state.value.capabilities
        )
    }

    private suspend fun refresh(silent: Boolean = false) {
        updateAvailability()
        if (!state.value.connected) return
        try {
            applyRuntime(uml.status())
        } catch (t: Throwable) {
            if (!silent) {
                val raw = t.message ?: "Runtime status unavailable"
                val msg = shortError(raw)
                state.value = state.value.copy(
                    message = msg,
                    lastError = msg,
                    console = withDetailedErrorLog(state.value.console, raw)
                )
            }
        }
    }

    fun startVm() = startDebian(requestedWidth, requestedHeight, requestedDpi, 120)

    fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int) {
        requestedWidth = width.coerceIn(800, 3840)
        requestedHeight = height.coerceIn(540, 2160)
        requestedDpi = dpi.coerceIn(96, 240)
        operation("Starting Vessel Linux") {
            state.value = state.value.copy(
                debianStarting = true,
                kdeInstalling = true,
                lastError = "",
                progressPhase = "queued",
                progressPercent = 1,
                progressDetail = "Starting Vessel Linux",
                message = "Starting Vessel Linux…"
            )
            applyRuntime(
                uml.startDesktopAsync(requestedWidth, requestedHeight, requestedDpi),
                "Vessel startup launched"
            )
        }
    }

    fun startDebianDiagnostic() = operation("Starting diagnostic guest") {
        applyRuntime(uml.start(), "Debian diagnostic guest ready")
    }

    fun installDebian() = startDebian(requestedWidth, requestedHeight, requestedDpi, 120)

    fun installKde() = operation("Starting Vessel compositor") {
        state.value = state.value.copy(
            kdeInstalling = true,
            lastError = "",
            progressPhase = "queued",
            progressPercent = 1,
            progressDetail = "Starting Vessel compositor",
            message = "Starting Vessel compositor…"
        )
        applyRuntime(
            uml.startDesktopAsync(requestedWidth, requestedHeight, requestedDpi),
            "Vessel compositor startup launched"
        )
    }

    fun stopVm() = operation("Stopping Linux") {
        applyRuntime(uml.stop(), "Linux stopped; disk retained")
        state.value = state.value.copy(kdeInstalled = false, kdeInstalling = false, internetReady = false, uptimeMs = 0L)
    }

    fun debianConsole(command: String) = operation("Running command") {
        val result = uml.guest(command, 90)
        val output = result.optString("output")
        state.value = state.value.copy(
            debianTerminal = (state.value.debianTerminal + "\n# $command\n$output").takeLast(256_000),
            message = "Command completed"
        )
        applyRuntime(result, "Command completed")
    }

    fun shell(command: String) = debianConsole(command)

    fun probeCapabilities() = operation("Checking runtime") {
        val runtime = uml.ensureDaemon()
        val text = buildString {
            append("Rootless UML ARM64")
            append(" · Termux=").append(if (uml.isTermuxInstalled()) "yes" else "no")
            append(" · command permission=").append(if (uml.hasRunCommandPermission()) "yes" else "no")
            append(" · protocol=").append(runtime.optInt("protocolVersion", 0))
            append(" · display=").append(runtime.optString("displayTransport", "unknown"))
            if (runtime.optBoolean("guestReady")) append(" · Venus guest=ready")
            if (runtime.optBoolean("desktopReady")) append(" · compositor=live")
        }
        state.value = state.value.copy(capabilities = text, message = text)
        applyRuntime(runtime, text)
    }

    fun attachSurface(surface: android.view.Surface) = Unit
    fun detachSurface(surface: android.view.Surface? = null) = Unit
    fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean = false
    fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean = false

    private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, lastError = "", message = label)
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                val raw = t.message ?: t.javaClass.simpleName
                val msg = shortError(raw)
                state.value = state.value.copy(
                    message = msg,
                    lastError = msg,
                    debianStarting = false,
                    kdeInstalling = false,
                    console = withDetailedErrorLog(state.value.console, raw),
                    debianTerminal = (state.value.debianTerminal + "\nERROR: $msg\n").takeLast(256_000)
                )
            } finally {
                state.value = state.value.copy(busy = false)
                if (state.value.lastError.isBlank()) refresh(silent = true)
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
                            .put("progress", s.progressDetail)
                            .put("error", s.lastError)
                            .put("graphics", s.graphics)
                            .put("network", s.internetStage)
                            .put("uptimeMs", s.uptimeMs)
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
