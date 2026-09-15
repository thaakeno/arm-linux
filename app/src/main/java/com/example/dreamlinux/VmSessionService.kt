package com.example.dreamlinux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class RuntimeBackend { UML_VIRTIO_GPU, AVF_LEGACY }

data class SessionState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val guestReady: Boolean = false,
    val displayReady: Boolean = false,
    val frameReachedApp: Boolean = false,
    val cid: Int = -1,
    val name: String = "Vessel Debian",
    val mode: String = "uml",
    val stage: String = "idle",
    val api: String = "Termux RUN_COMMAND + loopback control",
    val vmRoot: String = "",
    val console: String = "",
    val terminal: String = "",
    val debianTerminal: String = "",
    val message: String = "Ready to start Vessel",
    val busy: Boolean = false,
    val debianStarting: Boolean = false,
    val debianInstalled: Boolean = true,
    val debianInstalling: Boolean = false,
    val installProgress: Double = -1.0,
    val installBytes: Long = 0L,
    val installTotal: Long = -1L,
    // Compatibility names retained for older Compose code. In protocol 38 these
    // mean "a frame was actually presented on the Android Surface".
    val kdeInstalled: Boolean = false,
    val kdeInstalling: Boolean = false,
    val kdeStage: String = "not started",
    val desktopName: String = "VirtIO GPU direct display",
    val rendererMode: String = "virgl-opengl",
    val presenterStatus: String = "starting",
    val runtimeRevision: String = "",
    val displayTransport: String = "",
    val capabilities: String = "Not checked",
    val graphics: String = "VirtIO GPU · VirGL · ANGLE/Vulkan · Adreno",
    val internetReady: Boolean = false,
    val internetStage: String = "offline",
    val progressPhase: String = "idle",
    val progressPercent: Int = -1,
    val progressDetail: String = "Runtime stopped",
    val lastError: String = "",
    val uptimeMs: Long = 0L,
    val backend: RuntimeBackend = RuntimeBackend.UML_VIRTIO_GPU,
)

class VmSessionService : Service() {
    companion object {
        val state = MutableStateFlow(SessionState())
        var active: VmSessionService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var uml: TermuxUmlController
    private var requestedWidth = 1280
    private var requestedHeight = 720
    private var requestedDpi = 120

    override fun onCreate() {
        super.onCreate()
        active = this
        uml = TermuxUmlController(this)

        // Initialize the native presenter + protocol-38 TCP bridge immediately.
        // This does NOT boot Linux. Start Linux remains an explicit user action.
        val initialPresenter = VesselWaylandPresenter.status()
        state.value = state.value.copy(presenterStatus = initialPresenter)

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("vessel-runtime", "Vessel Linux runtime", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VesselActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            1,
            NotificationCompat.Builder(this, "vessel-runtime")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Vessel")
                .setContentText("Rootless ARM64 Linux · VirtIO GPU")
                .setOngoing(true)
                .setContentIntent(open)
                .build(),
        )

        updateAvailability()
        scope.launch {
            while (isActive) {
                refresh(silent = true)
                val s = state.value
                delay(if (s.running || s.busy) 350 else 1_500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Deliberately passive. Opening Vessel must never boot Linux, fetch git,
        // reset a worktree or alter the runtime. Only the Start button boots it.
        updateAvailability()
        return START_STICKY
    }

    override fun onDestroy() {
        if (active === this) active = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateAvailability() {
        val installed = uml.isTermuxInstalled()
        val permission = uml.hasRunCommandPermission()
        val connected = installed && permission
        val current = state.value
        state.value = if (connected) {
            current.copy(
                connected = true,
                capabilities = if (current.capabilities == "Not checked")
                    "Termux ready · protocol 38 launches only on Start"
                else current.capabilities,
            )
        } else {
            current.copy(
                connected = false,
                running = false,
                guestReady = false,
                displayReady = false,
                frameReachedApp = false,
                kdeInstalled = false,
                uptimeMs = 0L,
                stage = "runtime_setup",
                message = if (!installed)
                    "Install Termux to host the rootless UML runtime"
                else
                    "Grant Vessel permission to run commands in Termux",
                capabilities = "Termux=${if (installed) "installed" else "missing"} · RUN_COMMAND=${if (permission) "granted" else "missing"}",
            )
        }
    }

    /** Permission/setup refresh only. This never starts the daemon. */
    fun connectRuntime() = updateAvailability()

    private fun shortError(raw: String): String {
        val e = raw.trim()
        if (e.isBlank()) return ""
        return when {
            e.contains("runtime missing", ignoreCase = true) || e.contains("protocol 38 runtime did not start", ignoreCase = true) ->
                "Protocol 38 runtime is missing or outdated in ~/vessel-poc-runtime"
            e.contains("disk is already in use", ignoreCase = true) || e.contains("Failed to lock", ignoreCase = true) ->
                "Debian disk is already in use by another UML process"
            e.contains("virtio-gpu DRM renderer", ignoreCase = true) ->
                "VirtIO GPU did not initialize inside Debian"
            e.contains("No VirtIO GPU frame", ignoreCase = true) ->
                "GPU rendered, but no frame reached Vessel"
            e.contains("kmscube", ignoreCase = true) ->
                e.lineSequence().firstOrNull()?.take(180) ?: "Direct DRM display test failed"
            e.contains("UML exited", ignoreCase = true) -> "Debian UML exited during startup"
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

    private fun detailedRuntimeLog(obj: JSONObject, rawError: String, presenter: String): String = buildString {
        append(obj.optString("logTail", state.value.console).takeLast(160_000))
        if (rawError.isNotBlank()) append("\n\n[Vessel error]\n").append(rawError)
        append("\n\n[Vessel protocol 38]\n")
        append("protocol=").append(obj.optInt("protocolVersion", 0)).append('\n')
        append("revision=").append(obj.optString("runtimeRevision", "unknown")).append('\n')
        append("renderer=").append(obj.optString("renderer", "unknown")).append('\n')
        append("transport=").append(obj.optString("displayTransport", "unknown")).append('\n')
        append("translationLayer=").append(obj.optString("translationLayer", "unknown")).append('\n')
        append("androidPresenter=").append(presenter)
    }.takeLast(200_000)

    private fun applyRuntime(obj: JSONObject, fallbackMessage: String? = null) {
        val ok = obj.optBoolean("ok", false)
        val running = obj.optBoolean("running", false)
        val guest = obj.optBoolean("guestReady", false)
        val frameReached = obj.optBoolean("desktopReady", false)
        val phase = obj.optString("progressPhase", state.value.progressPhase)
        val candidateError = obj.optString("error").ifBlank { obj.optString("lastError") }
        val rawError = if (!ok || phase == "error") candidateError else ""
        val error = shortError(rawError)
        val presenter = VesselWaylandPresenter.status()
        val actuallyPresented = presenter.startsWith("presenting-")
        val percent = obj.optInt("progressPercent", state.value.progressPercent)
        val rawDetail = obj.optString("progressDetail", state.value.progressDetail).ifBlank { state.value.progressDetail }
        val detail = when (phase) {
            "queued" -> "Starting Vessel VirtIO GPU runtime"
            "uml_boot" -> "Booting Debian ARM64"
            "command_agent" -> "Connecting Debian control channel"
            "gpu_probe" -> "Checking VirtIO GPU / VirGL"
            "debian_ready" -> "Debian + VirtIO GPU ready"
            "display_deps" -> "Preparing direct DRM display"
            "display_start" -> "Starting accelerated DRM/KMS scanout"
            "display_frame" -> "Waiting for GPU frame in Vessel"
            "desktop_ready" -> if (actuallyPresented) "GPU frame visible on Android Surface" else "GPU frame reached Vessel; open Desktop to present it"
            else -> rawDetail
        }
        val uptime = obj.optLong("uptimeMs", if (running) state.value.uptimeMs else 0L)
        val renderer = obj.optString("renderer", "Mesa VirGL -> ANGLE/Vulkan -> Adreno")
        val desktopName = obj.optString("desktopName", "VirtIO GPU direct display")
        val message = when {
            error.isNotBlank() -> error
            actuallyPresented -> "$desktopName visible · ${formatUptime(uptime)}"
            frameReached -> "GPU frame reached Vessel · open Desktop"
            detail.isNotBlank() && running -> "$detail · ${formatUptime(uptime)}"
            fallbackMessage != null -> fallbackMessage
            guest -> "Debian ARM64 + VirtIO GPU ready · ${formatUptime(uptime)}"
            running -> "Booting Debian ARM64 · ${formatUptime(uptime)}"
            else -> "Ready to start Vessel"
        }

        state.value = state.value.copy(
            connected = uml.isTermuxInstalled() && uml.hasRunCommandPermission(),
            running = running,
            guestReady = guest,
            displayReady = actuallyPresented,
            frameReachedApp = frameReached,
            debianStarting = running && !guest,
            kdeInstalled = actuallyPresented,
            kdeInstalling = running && !actuallyPresented,
            kdeStage = when {
                actuallyPresented -> "Android Surface presenting GPU frames"
                frameReached -> "Frame reached Vessel; waiting for Surface"
                running -> detail
                else -> "not started"
            },
            desktopName = desktopName,
            rendererMode = obj.optString("rendererMode", "virgl-opengl"),
            presenterStatus = presenter,
            runtimeRevision = obj.optString("runtimeRevision", state.value.runtimeRevision),
            displayTransport = obj.optString("displayTransport", state.value.displayTransport),
            internetReady = guest,
            internetStage = if (guest) "NAT via umnet/passt" else "offline",
            vmRoot = obj.optString("runtimeDir", state.value.vmRoot),
            console = detailedRuntimeLog(obj, rawError, presenter),
            progressPhase = phase,
            progressPercent = percent,
            progressDetail = detail,
            lastError = error,
            uptimeMs = if (running) uptime else 0L,
            stage = when {
                actuallyPresented -> "display_presented"
                frameReached -> "frame_reached_app"
                phase.isNotBlank() && phase != "idle" -> phase
                guest -> "debian_ready"
                running -> "uml_boot"
                else -> "runtime_ready"
            },
            message = message,
            graphics = if (guest) renderer else state.value.graphics,
            capabilities = if (ok)
                "Rootless UML · VirtIO GPU · VirGL · ANGLE/Vulkan · Adreno · Android Vulkan Surface"
            else state.value.capabilities,
        )
    }

    private suspend fun refresh(silent: Boolean) {
        updateAvailability()
        if (!state.value.connected) return
        try {
            applyRuntime(uml.status())
        } catch (t: Throwable) {
            // A missing daemon while Linux is stopped is normal. Most
            // importantly, a passive refresh never calls ensureDaemon().
            if (!state.value.running) {
                state.value = state.value.copy(
                    presenterStatus = VesselWaylandPresenter.status(),
                    message = if (state.value.connected) "Ready to start Vessel" else state.value.message,
                )
            } else if (!silent) {
                val msg = shortError(t.message ?: "Runtime status unavailable")
                state.value = state.value.copy(message = msg, lastError = msg)
            }
        }
    }

    fun startVm() = startDebian(requestedWidth, requestedHeight, requestedDpi, 120)

    fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int) {
        @Suppress("UNUSED_VARIABLE") val ignoredRefreshRate = refreshRate
        requestedWidth = width.coerceIn(800, 2560)
        requestedHeight = height.coerceIn(540, 1600)
        requestedDpi = dpi.coerceIn(96, 240)
        operation("Starting Vessel Linux") {
            state.value = state.value.copy(
                debianStarting = true,
                kdeInstalling = true,
                displayReady = false,
                frameReachedApp = false,
                kdeInstalled = false,
                lastError = "",
                progressPhase = "queued",
                progressPercent = 1,
                progressDetail = "Starting Vessel VirtIO GPU runtime",
                message = "Starting Vessel Linux…",
            )
            applyRuntime(
                uml.startDesktopAsync(requestedWidth, requestedHeight, requestedDpi),
                "Vessel startup launched",
            )
        }
    }

    fun startDebianDiagnostic() = operation("Starting Debian") {
        applyRuntime(uml.start(), "Debian diagnostic guest ready")
    }

    fun installDebian() = startVm()
    fun installKde() = startVm()

    fun stopVm() = operation("Stopping Linux") {
        applyRuntime(uml.stop(), "Linux stopped; disk retained")
        state.value = state.value.copy(
            running = false,
            guestReady = false,
            displayReady = false,
            frameReachedApp = false,
            kdeInstalled = false,
            kdeInstalling = false,
            internetReady = false,
            uptimeMs = 0L,
        )
    }

    fun debianConsole(command: String) = operation("Running command") {
        val result = uml.guest(command, 90)
        val output = result.optString("output")
        state.value = state.value.copy(
            debianTerminal = (state.value.debianTerminal + "\n# $command\n$output").takeLast(256_000),
            message = "Command completed",
        )
        applyRuntime(result, "Command completed")
    }

    fun shell(command: String) = debianConsole(command)

    fun launchDesktopApp(name: String) = operation("Running $name") {
        applyRuntime(uml.desktopAction(name), "$name launched")
    }

    fun launchFirefox() = Unit

    fun runGuestVulkanProbe() = operation("Running GPU diagnostics") {
        val result = uml.guest(
            "echo '=== DRM ==='; ls -l /dev/dri 2>&1; " +
                "echo '=== EGL ==='; EGL_PLATFORM=surfaceless eglinfo 2>&1 | " +
                "grep -Ei 'EGL vendor|EGL version|driver name|Device #0' | head -40; " +
                "echo '=== display relay ==='; cat /proc/cmdline",
            45,
        )
        val output = result.optString("output")
        state.value = state.value.copy(
            debianTerminal = (state.value.debianTerminal + "\n# GPU diagnostics\n$output").takeLast(256_000),
            message = "GPU diagnostics completed",
        )
        applyRuntime(result, "GPU diagnostics completed")
    }

    fun probeCapabilities() = operation("Checking runtime") {
        val runtime = uml.ensureDaemon()
        applyRuntime(runtime, "Protocol 38 runtime connected")
    }

    fun attachSurface(surface: android.view.Surface) = VesselWaylandPresenter.attach(surface)
    fun detachSurface(surface: android.view.Surface? = null) {
        @Suppress("UNUSED_VARIABLE") val ignored = surface
        VesselWaylandPresenter.detach()
    }
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
                    console = (state.value.console + "\n\n[Vessel error]\n" + raw).takeLast(200_000),
                    debianTerminal = (state.value.debianTerminal + "\nERROR: $msg\n").takeLast(256_000),
                )
            } finally {
                state.value = state.value.copy(busy = false)
                refresh(silent = true)
            }
        }
    }
}
