package com.example.dreamlinux

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.system.Os
import android.view.Display
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
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GuestApp(
    val packageName: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    val installedSizeKb: Long = 0,
    val appstreamId: String = "",
    val iconBase64: String = "",
    val category: String = "Other",
    val releaseTimestamp: Long = 0,
    val popularityRank: Int = 10_000,
)

data class AppStoreState(
    val apps: List<GuestApp> = emptyList(),
    val query: String = "",
    val sort: String = "POPULAR",
    val category: String = "All",
    val loading: Boolean = false,
    val busyPackage: String = "",
    val operationProgress: Int = 0,
    val operationDetail: String = "",
    val error: String = "",
)

data class MachineStats(
    val guestDiskUsedMb: Long = 0,
    val guestDiskFreeMb: Long = 0,
    val guestRamUsedMb: Long = 0,
    val guestRamTotalMb: Long = 0,
    val packageCount: Int = 0,
    val guestUptimeSeconds: Long = 0,
    val diskVirtualMb: Long = 0,
    val diskPhysicalMb: Long = 0,
    val hostFreeMb: Long = 0,
    val vcpus: Int = 6,
    val loading: Boolean = false,
    val error: String = "",
)

data class SessionState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val guestReady: Boolean = false,
    val displayReady: Boolean = false,
    val inputReady: Boolean = false,
    val frameReachedApp: Boolean = false,
    val name: String = "Vessel Debian",
    val message: String = "Ready",
    val busy: Boolean = false,
    val stage: String = "idle",
    val progressPercent: Int = 0,
    val progressDetail: String = "Runtime stopped",
    val lastError: String = "",
    val console: String = "",
    val terminalOutput: String = "",
    val graphics: String = "VirtIO GPU · VirGL · Android Surface · Adreno",
    val presenterStatus: String = "not-started",
    val rendererMode: String = "virgl-opengl",
    val translationLayer: String = "VirGL",
    val displayTransport: String = "vhost-user-gpu-ahb-native-surface-v3",
    val runtimeRevision: String = "v60-private-rootfs-fast-storage-r1",
    val machinePath: String = "Vessel private storage",
    val internetStage: String = "UML vector net · passt",
    val uptimeMs: Long = 0L,
    val storageReady: Boolean = false,
    val hostAssetsReady: Boolean = false,
    val guestMemoryMb: Int = 0,
    val guestDisplayWidth: Int = 1920,
    val guestDisplayHeight: Int = 1080,
)

class VmSessionService : Service() {
    companion object {
        val state = MutableStateFlow(SessionState())
        val appStore = MutableStateFlow(AppStoreState())
        val machineStats = MutableStateFlow(MachineStats())
        val diagnostics = MutableStateFlow("No diagnostics collected yet.")
        @Volatile var active: VmSessionService? = null

        private val PACKAGE_RE = Regex("[a-z0-9][a-z0-9+.-]{0,127}")
        private val DEFAULT_APPS = linkedMapOf(
            "firefox-esr" to "Firefox",
            "konsole" to "Konsole",
            "dolphin" to "Dolphin",
            "okular" to "Okular",
            "ark" to "Ark",
            "gwenview" to "Gwenview",
            "kcalc" to "KCalc",
            "systemsettings" to "System Settings",
            "kate" to "Kate",
        )
        private val DESKTOP_RUNTIME_PACKAGES = listOf(
            "kde-plasma-desktop", "plasma-workspace", "plasma-desktop", "plasma-framework", "kwin-x11", "kwin-wayland", "plasma-workspace-wayland", "xwayland", "qtwayland5", "systemsettings",
            "qml-module-org-kde-qqc2desktopstyle", "qml-module-org-kde-kirigami2", "qml-module-org-kde-kitemmodels",
            "qml-module-org-kde-kquickcontrolsaddons", "qml-module-qtquick-controls", "qml-module-qtquick-controls2", "qml-module-qtquick-layouts",
            "qml-module-qtquick-window2", "qml-module-qtquick2", "qml-module-qtquick-templates2", "qml-module-qtgraphicaleffects", "qml-module-qt-labs-platform",
            "plasma-integration", "plasma-pa", "pulseaudio", "pulseaudio-utils", "alsa-utils", "kactivitymanagerd", "libkf5service-data", "breeze", "breeze-icon-theme", "hicolor-icon-theme",
            "desktop-file-utils", "xdg-user-dirs", "shared-mime-info", "menu", "appstream", "apt-config-icons", "apt-config-icons-large", "apt-config-icons-hidpi", "packagekit", "packagekit-tools", "policykit-1", "plasma-discover", "librsvg2-bin", "python3-yaml",
            "fonts-noto-core", "fonts-noto-color-emoji", "fonts-dejavu-core", "fonts-liberation", "plasma-workspace-wallpapers",
        )
        val APP_SORTS = listOf("POPULAR", "NEW", "HOT_WEEK", "HOT_MONTH", "HOT_YEAR", "INSTALLED", "SIZE", "AZ")
        val APP_CATEGORIES = listOf("All", "Internet", "Office", "Media", "Graphics", "Utilities", "Games", "Development", "Other")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: VesselRuntimeController
    private var guestWidth = 1920
    private var guestHeight = 1080
    private var guestDpi = 120
    private var guestRefresh = 60f
    @Volatile private var operationGeneration = 0L
    @Volatile private var resizeGeneration = 0L
    @Volatile private var discoveryHelperInstalled = false
    private var lastStatsAt = 0L
    private var lastResizeWidth = 0
    private var lastResizeHeight = 0

    private fun nextOperation(): Long = synchronized(this) { ++operationGeneration }

    override fun onCreate() {
        super.onCreate()
        active = this
        VesselGuestAgent.start()
        VesselAudioBridge.start(this)
        runtime = VesselRuntimeController(this) { phase, pct, detail ->
            state.value = state.value.copy(
                stage = phase,
                progressPercent = pct,
                progressDetail = detail,
                message = detail,
            )
        }
        configureStableLandscape()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("vessel-runtime", "Vessel Linux runtime", NotificationManager.IMPORTANCE_LOW),
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, VesselActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            1,
            NotificationCompat.Builder(this, "vessel-runtime")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Vessel")
                .setContentText("Self-contained ARM64 Linux runtime")
                .setOngoing(true)
                .setContentIntent(pi)
                .build(),
        )
        refreshAvailability()
        scope.launch {
            while (isActive) {
                refreshState()
                val now = android.os.SystemClock.elapsedRealtime()
                if (
                    state.value.running && state.value.guestReady && !state.value.busy &&
                    VesselGuestAgent.isConnected() && now - lastStatsAt > 30_000
                ) {
                    lastStatsAt = now
                    refreshSystemStats(silent = true)
                }
                delay(if (state.value.running || state.value.busy) 1000 else 2000)
            }
        }
    }

    private fun configureStableLandscape() {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val physicalLong = max(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
        val physicalShort = min(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
        val resolutionPercent = VesselExperimentConfig.resolutionPercent(this)
        val targetWidth = ((min(physicalLong, 1920) * resolutionPercent) / 100).coerceAtLeast(640)
        val targetHeight = ((physicalShort.toDouble() * targetWidth / physicalLong)
            .roundToInt().coerceAtLeast(480) / 2) * 2
        guestWidth = (targetWidth / 8) * 8
        guestHeight = targetHeight
        guestDpi = 120
        val requestedRefresh = VesselExperimentConfig.refreshHz(this).toFloat()
        val maxSupportedRefresh = display?.supportedModes?.maxOfOrNull { it.refreshRate }
            ?: mode?.refreshRate ?: 60f
        guestRefresh = min(maxSupportedRefresh, requestedRefresh).coerceAtLeast(30f)
        runtime.configureDisplay(guestWidth, guestHeight, guestDpi, guestRefresh)
        state.value = state.value.copy(
            guestDisplayWidth = guestWidth,
            guestDisplayHeight = guestHeight,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshAvailability()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the UI away must not be interpreted as "Stop Linux".
        // START_STICKY normally recreates us; explicitly re-arm the foreground service as well
        // because some OEM task managers are aggressive about removing the task.
        runCatching { startForegroundService(Intent(this, VmSessionService::class.java)) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        scope.cancel()
        VesselWaylandPresenter.shutdown()
        VesselGuestAgent.stop()
        VesselAudioBridge.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun refreshAvailability() {
        val storage = runtime.hasStorageAccess()
        val assets = runtime.hostAssetsReady()
        val old = state.value
        state.value = old.copy(
            connected = storage && assets,
            storageReady = storage,
            hostAssetsReady = assets,
            machinePath = runtime.machineDir.absolutePath,
            guestMemoryMb = runtime.guestMemoryMb,
            runtimeRevision = VesselRuntimeController.REVISION,
            displayTransport = VesselRuntimeController.DISPLAY_TRANSPORT,
            guestDisplayWidth = guestWidth,
            guestDisplayHeight = guestHeight,
            message = when {
                !storage -> "Private Linux storage is unavailable"
                !assets -> "Native runtime assets missing from APK"
                old.running || old.busy -> old.message
                else -> "Ready to start Vessel"
            },
        )
    }

    private fun applyState(o: JSONObject) {
        val presenter = VesselWaylandPresenter.status()
        val err = o.optString("lastError")
        val presented = presenter.startsWith("presenting-native-surface") || presenter.startsWith("presenting-retained")
        val frame = o.optBoolean("frameContentValidated") || presented
        val stopping = state.value.stage == "stopping"
        state.value = state.value.copy(
            running = o.optBoolean("running"),
            guestReady = o.optBoolean("guestReady"),
            displayReady = o.optBoolean("desktopReady") || presented,
            frameReachedApp = frame,
            inputReady = o.optBoolean("inputConnected"),
            presenterStatus = presenter,
            console = o.optString("logTail", state.value.console),
            lastError = if (stopping) "" else err,
            uptimeMs = o.optLong("uptimeMs"),
            graphics = if (VesselExperimentConfig.desktopBackend(this) == "wayland") "KDE Plasma/Wayland → Mesa VirGL → virglrenderer → ${VesselExperimentConfig.hostGl(this).uppercase()} EGL → async AHB → SurfaceFlinger" else "KDE Plasma/X11 fallback → Mesa VirGL → virglrenderer → ${VesselExperimentConfig.hostGl(this).uppercase()} EGL → async AHB",
            rendererMode = o.optString("rendererMode", state.value.rendererMode),
            translationLayer = o.optString("translationLayer", state.value.translationLayer),
            displayTransport = VesselRuntimeController.DISPLAY_TRANSPORT,
            runtimeRevision = VesselRuntimeController.REVISION,
            guestMemoryMb = o.optInt("guestMemoryMb", state.value.guestMemoryMb),
            guestDisplayWidth = o.optInt("displayWidth", guestWidth),
            guestDisplayHeight = o.optInt("displayHeight", guestHeight),
            message = when {
                stopping -> "Stopping Linux"
                err.isNotBlank() -> err
                presented -> "Plasma visible · Android native Surface GPU path"
                o.optBoolean("guestReady") -> state.value.progressDetail
                else -> state.value.message
            },
        )
    }

    private suspend fun refreshState() {
        refreshAvailability()
        if (state.value.stage == "stopping") return
        if (!state.value.running && !state.value.busy) return
        runCatching { runtime.status() }.onSuccess(::applyState)
    }

    /**
     * Android SurfaceView geometry is presentation geometry, not the Linux monitor mode.
     * A portrait tab, IME, split screen, or a temporary Surface recreation must never turn
     * KWin's monitor into a tall 752x838 output. Landscape surfaces may still request a
     * matching monitor aspect; portrait surfaces keep the device's stable landscape aspect.
     */
    fun configureDisplay(width: Int, height: Int, densityDpi: Int, rate: Float) {
        if (width <= 0 || height <= 0 || densityDpi <= 0 || rate <= 0f) return

        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val physicalLong = max(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
        val physicalShort = min(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
        val stableAspect = physicalLong.toDouble() / physicalShort.coerceAtLeast(1)
        val viewportAspect = width.toDouble() / height.coerceAtLeast(1)
        val outputAspect = if (viewportAspect >= 1.15) viewportAspect.coerceIn(1.25, 2.40) else stableAspect.coerceIn(1.25, 2.40)

        val resolutionPercent = VesselExperimentConfig.resolutionPercent(this)
        val landscapePixels = max(width, height).coerceAtMost(1920)
        val targetWidth = ((((landscapePixels * resolutionPercent) / 100).coerceAtLeast(640)) / 8) * 8
        val targetHeight = (((targetWidth / outputAspect).roundToInt().coerceIn(480, 2160)) / 2) * 2
        if (targetWidth == lastResizeWidth && targetHeight == lastResizeHeight) return
        lastResizeWidth = targetWidth
        lastResizeHeight = targetHeight

        val targetDpi = densityDpi.coerceIn(96, 180)
        val generation = ++resizeGeneration
        scope.launch(Dispatchers.IO) {
            delay(220)
            if (generation != resizeGeneration) return@launch
            guestWidth = targetWidth
            guestHeight = targetHeight
            guestDpi = targetDpi
            guestRefresh = rate.coerceIn(30f, VesselExperimentConfig.refreshHz(this@VmSessionService).toFloat())
            runtime.resizeDesktop(guestWidth, guestHeight, guestDpi, guestRefresh)
            if (generation == resizeGeneration) {
                state.value = state.value.copy(
                    guestDisplayWidth = guestWidth,
                    guestDisplayHeight = guestHeight,
                )
            }
        }
    }

    fun sendInput(type: String, values: Map<String, Any>) = runtime.input(type, values)

    fun startVm() {
        if (state.value.busy) return
        refreshAvailability()
        if (!state.value.connected) return
        val op = nextOperation()
        discoveryHelperInstalled = false
        appStore.value = AppStoreState()
        state.value = state.value.copy(
            busy = true,
            lastError = "",
            stage = "starting",
            progressPercent = 1,
            progressDetail = "Starting self-contained Vessel runtime",
            message = "Starting Linux",
            terminalOutput = "",
        )
        scope.launch(Dispatchers.IO) {
            try {
                val started = runtime.startDesktop()
                if (op != operationGeneration) return@launch
                applyState(started)
                ensureWorkstation(op)
                if (op != operationGeneration) return@launch
                ensureDesktopProfile(op)
                if (op != operationGeneration) return@launch
                applyState(runtime.status())
                state.value = state.value.copy(
                    progressPercent = 100,
                    progressDetail = "Vessel workstation ready",
                    message = "Vessel workstation ready",
                    lastError = "",
                )
                if (VesselGuestAgent.isConnected()) {
                    runCatching { installDiscoveryHelper() }
                    refreshSystemStats(silent = true)
                }
            } catch (t: Throwable) {
                if (op != operationGeneration || state.value.stage == "stopping") return@launch
                runCatching { runtime.status() }.getOrNull()?.let(::applyState)
                val message = t.message ?: t.javaClass.simpleName
                state.value = state.value.copy(
                    lastError = message,
                    progressPercent = 0,
                    progressDetail = "Workstation setup failed",
                    message = message,
                    stage = "setup_error",
                )
            } finally {
                if (op == operationGeneration) {
                    val current = state.value
                    state.value = current.copy(
                        busy = false,
                        stage = when {
                            current.lastError.isNotBlank() -> "setup_error"
                            current.running -> "ready"
                            else -> "idle"
                        },
                    )
                }
            }
        }
    }

    private suspend fun ensureWorkstation(op: Long) {
        if (op != operationGeneration) return
        val fastMarker = runtime.guest(
            "test -f /var/cache/vessel/workstation-v58 && test -f /etc/xdg/menus/kf5-applications.menu && test -d /usr/share/icons/breeze",
            5,
        )
        if (fastMarker.optBoolean("ok")) return
        state.value = state.value.copy(
            stage = "workstation_validation",
            progressPercent = 97,
            progressDetail = "Validating complete Plasma workstation",
            message = "Validating desktop components",
        )
        val packages = (DESKTOP_RUNTIME_PACKAGES + DEFAULT_APPS.keys).distinct()
        val packageWords = packages.joinToString(" ") { shellQuote(it) }
        val validation = runtime.guest(
            """
            set -e
            missing=''
            for p in $packageWords; do
              dpkg-query -W -f='${'$'}{Status}' "${'$'}p" 2>/dev/null | grep -q 'install ok installed' || missing="${'$'}missing ${'$'}p"
            done
            if [ -n "${'$'}missing" ]; then echo "VESSEL_MISSING_PACKAGES=${'$'}missing"; exit 31; fi
            test -f /etc/xdg/menus/kf5-applications.menu
            qml=/usr/lib/aarch64-linux-gnu/qt5/qml
            test -f "${'$'}qml/QtQuick/Templates.2/qmldir"
            test -f "${'$'}qml/QtGraphicalEffects/qmldir"
            test -f "${'$'}qml/org/kde/kirigami.2/qmldir"
            test -f "${'$'}qml/Qt/labs/platform/qmldir"
            test -f "${'$'}qml/org/kde/plasma/private/volume/qmldir"
            test -d /usr/share/icons/breeze
            test -x /usr/bin/systemsettings || test -x /usr/bin/systemsettings5
            install -d -o vessel -g vessel /home/vessel/Desktop /home/vessel/.config
            install -d -m 755 /var/cache/vessel
            printf '%s\n' 'unset MOZ_X11_EGL' 'export MOZ_ENABLE_WAYLAND=1' 'export MOZ_WEBRENDER=1' 'export XCURSOR_THEME=Breeze' 'export XCURSOR_SIZE=18' 'export GDK_BACKEND=wayland' 'export QT_QPA_PLATFORM=wayland' >/etc/profile.d/vessel-gpu.sh
            chmod 0644 /etc/profile.d/vessel-gpu.sh
            update-desktop-database /usr/share/applications 2>/dev/null || true
            update-mime-database /usr/share/mime 2>/dev/null || true
            gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
            gtk-update-icon-cache -f -t /usr/share/icons/breeze 2>/dev/null || true
            uid=${'$'}(id -u vessel)
            su -l vessel -c "XDG_RUNTIME_DIR=/run/user/${'$'}uid xdg-user-dirs-update" 2>/dev/null || true
            su -l vessel -c "XDG_RUNTIME_DIR=/run/user/${'$'}uid kbuildsycoca5 --noincremental" >/tmp/vessel-sycoca.log 2>&1
            for f in firefox-esr org.kde.konsole org.kde.dolphin systemsettings; do
              src=/usr/share/applications/${'$'}f.desktop
              [ -f "${'$'}src" ] && install -m 755 -o vessel -g vessel "${'$'}src" /home/vessel/Desktop/ || true
            done
            touch /var/cache/vessel/workstation-v58
            echo VESSEL_WORKSTATION_READY
            """.trimIndent(),
            180,
        )
        if (!validation.optBoolean("ok")) {
            throw IllegalStateException("Workstation validation failed: ${validation.optString("output").takeLast(5000)}")
        }
    }

    private suspend fun ensureDesktopProfile(op: Long) {
        if (op != operationGeneration) return
        state.value = state.value.copy(
            stage = "desktop_profile",
            progressPercent = 99,
            progressDetail = "Validating live Plasma shell and Kickoff QML",
            message = "Finishing desktop validation",
        )
        val profile = runtime.guest(
            """
            set -e
            marker=/home/vessel/.config/.vessel-workstation-v58
            uid=${'$'}(id -u vessel)
            test -f /etc/xdg/menus/kf5-applications.menu
            test -f /usr/share/plasma/plasmoids/org.kde.plasma.kickoff/contents/ui/FullRepresentation.qml
            test -f /usr/share/plasma/plasmoids/org.kde.plasma.kickoff/contents/ui/NormalPage.qml
            if [ ! -f "${'$'}marker" ]; then
              su -l vessel -c "XDG_RUNTIME_DIR=/run/user/${'$'}uid kbuildsycoca5 --noincremental" >/tmp/vessel-sycoca.log 2>&1
            fi
            ready=0
            compositor=${if (VesselExperimentConfig.desktopBackend(this) == "wayland") "kwin_wayland" else "kwin_x11"}
            for i in ${'$'}(seq 1 120); do
              if pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x ${'$'}compositor >/dev/null; then ready=1; break; fi
              sleep .1
            done
            test "${'$'}ready" = 1
            sleep .25
            if grep -Eqi 'FullRepresentation unavailable|NormalPage unavailable|module .* is not installed' /tmp/vessel-plasma.log 2>/dev/null; then
              tail -160 /tmp/vessel-plasma.log
              exit 45
            fi
            touch "${'$'}marker"
            chown vessel:vessel "${'$'}marker"
            echo VESSEL_PROFILE_READY
            """.trimIndent(),
            45,
        )
        if (!profile.optBoolean("ok")) {
            throw IllegalStateException("Plasma profile/QML validation failed: ${profile.optString("output").takeLast(6000)}")
        }
    }

    fun stopVm() {
        val current = state.value
        if ((!current.running && !current.busy) || current.stage == "stopping") return
        val op = nextOperation()
        discoveryHelperInstalled = false
        state.value = current.copy(
            busy = true,
            stage = "stopping",
            progressDetail = "Stopping Linux safely",
            message = "Stopping Linux",
            lastError = "",
        )
        scope.launch(Dispatchers.IO) {
            val result = runCatching { runtime.stop() }
            if (op != operationGeneration) return@launch
            result.onSuccess(::applyState).onFailure {
                val msg = it.message.orEmpty()
                if (!msg.contains("Vessel stopped", ignoreCase = true)) {
                    state.value = state.value.copy(lastError = msg.ifBlank { "Stop failed" })
                }
            }
            state.value = state.value.copy(
                busy = false,
                running = false,
                guestReady = false,
                displayReady = false,
                inputReady = false,
                frameReachedApp = false,
                stage = "idle",
                progressPercent = 0,
                progressDetail = "Runtime stopped",
                message = "Linux stopped; disk retained",
                lastError = "",
            )
        }
    }

    fun debianConsole(command: String) = runGuestCommand(command)

    fun runGuestCommand(command: String) {
        if (!state.value.running || !state.value.guestReady || command.isBlank()) return
        state.value = state.value.copy(terminalOutput = state.value.terminalOutput + "\n$ $command\n")
        scope.launch(Dispatchers.IO) {
            runCatching { runtime.guest(command, 120) }
                .onSuccess { o ->
                    applyState(o)
                    val out = o.optString("output")
                    state.value = state.value.copy(
                        terminalOutput = (state.value.terminalOutput + out + if (out.endsWith("\n") || out.isBlank()) "" else "\n").takeLast(160_000),
                    )
                }
                .onFailure {
                    state.value = state.value.copy(
                        lastError = it.message ?: "Command failed",
                        terminalOutput = state.value.terminalOutput + "${it.message}\n",
                    )
                }
            state.value = state.value.copy(busy = false)
        }
    }

    fun runGpuDiagnostics() {
        runGuestCommand(
            "export DISPLAY=:0; printf '=== GL ===\\n'; LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl glxinfo -B 2>&1; " +
                "printf '\\n=== DRM ===\\n'; ls -l /dev/dri 2>&1; " +
                "printf '\\n=== INPUT ===\\n'; grep -E 'Name=\"Vessel (Trackpad|Touchscreen|Keyboard)\"' /proc/bus/input/devices 2>&1; " +
                "printf '\\n=== XINPUT ===\\n'; xinput list 2>&1; " +
                "printf '\\n=== PLASMA ===\\n'; ps -ef | grep -E 'kwin|plasmashell|Xorg' | grep -v grep 2>&1; " +
                "printf '\\n=== MEMORY ===\\n'; free -m 2>&1; " +
                "printf '\\n=== TIMER/RCU ===\\n'; dmesg 2>&1 | grep -Ei 'rcu.*stall|timer handling|starved' | tail -40",
        )
    }

    private suspend fun installDiscoveryHelper() {
        if (discoveryHelperInstalled) return
        val bytes = assets.open("vessel/app_discovery_v3.py").use { it.readBytes() }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val result = runtime.guest(
            "install -d -m 755 /usr/local/lib/vessel /var/cache/vessel; printf '%s' ${shellQuote(b64)} | base64 -d >/usr/local/lib/vessel/app_discovery.py; chmod 755 /usr/local/lib/vessel/app_discovery.py",
            20,
        )
        check(result.optBoolean("ok")) { "Could not install Vessel AppStream helper" }
        discoveryHelperInstalled = true
    }

    fun refreshApps(query: String = appStore.value.query, sort: String = appStore.value.sort, category: String = appStore.value.category) {
        if (!state.value.running || !state.value.guestReady) {
            appStore.value = appStore.value.copy(loading = false, error = "Start Linux to browse Debian apps")
            return
        }
        val normalizedSort = sort.uppercase().takeIf { it in APP_SORTS } ?: "POPULAR"
        val normalizedCategory = category.takeIf { it in APP_CATEGORIES } ?: "All"
        appStore.value = appStore.value.copy(query = query, sort = normalizedSort, category = normalizedCategory, loading = true, error = "")
        scope.launch(Dispatchers.IO) {
            try {
                if (!VesselGuestAgent.waitUntilConnected(1_500)) {
                    appStore.value = appStore.value.copy(
                        loading = false,
                        error = "Debian app service is reconnecting. Try again in a moment.",
                    )
                    return@launch
                }
                installDiscoveryHelper()
                val result = runtime.guest(
                    "/usr/local/lib/vessel/app_discovery.py ${shellQuote(query)} ${shellQuote(normalizedSort)} ${shellQuote(normalizedCategory)}",
                    30,
                )
                check(result.optBoolean("ok")) { "Debian app discovery returned rc=${result.optInt("rc", -1)}" }
                val apps = parseApps(result.optString("output"))
                appStore.value = appStore.value.copy(
                    apps = apps,
                    loading = false,
                    error = if (apps.isEmpty()) "No AppStream applications found" else "",
                )
            } catch (t: Throwable) {
                appStore.value = appStore.value.copy(loading = false, error = t.message ?: "Could not query Debian app catalog")
            }
        }
    }

    fun installApp(packageName: String) = changeApp(packageName, install = true)
    fun removeApp(packageName: String) = changeApp(packageName, install = false)

    private fun changeApp(packageName: String, install: Boolean) {
        if (!PACKAGE_RE.matches(packageName) || !state.value.running || !state.value.guestReady) return
        appStore.value = appStore.value.copy(
            busyPackage = packageName,
            operationProgress = 8,
            operationDetail = "Checking Debian package state",
            error = "",
        )
        scope.launch(Dispatchers.IO) {
            try {
                val policy = "export DEBIAN_FRONTEND=noninteractive SYSTEMD_OFFLINE=1; mkdir -p /usr/sbin /var/cache/vessel; " +
                    "printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d; chmod 755 /usr/sbin/policy-rc.d; "
                if (install) {
                    val freshLists = runtime.guest(
                        "find /var/lib/apt/lists -type f -mmin -360 -print -quit 2>/dev/null | grep -q .",
                        10,
                    ).optBoolean("ok")
                    if (!freshLists) {
                        appStore.value = appStore.value.copy(
                            operationProgress = -1,
                            operationDetail = "Refreshing Debian package metadata",
                        )
                        val update = runtime.guest(
                            policy + "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update",
                            600,
                        )
                        check(update.optBoolean("ok")) { "APT update returned rc=${update.optInt("rc", -1)}" }
                    }
                    appStore.value = appStore.value.copy(
                        operationProgress = -1,
                        operationDetail = "Downloading and installing $packageName",
                    )
                } else {
                    appStore.value = appStore.value.copy(
                        operationProgress = -1,
                        operationDetail = "Removing $packageName",
                    )
                }
                val action = if (install) {
                    "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y ${shellQuote(packageName)}"
                } else {
                    "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 remove -y ${shellQuote(packageName)}"
                }
                val result = runtime.guest(policy + action, 1800)
                check(result.optBoolean("ok")) { "APT returned rc=${result.optInt("rc", -1)}: ${result.optString("output").takeLast(1800)}" }
                appStore.value = appStore.value.copy(
                    operationProgress = 92,
                    operationDetail = "Refreshing desktop metadata",
                )
                runtime.guest(
                    "update-desktop-database /usr/share/applications 2>/dev/null || true; " +
                        "appstreamcli refresh-cache --force >/dev/null 2>&1 || true; " +
                        "rm -f /var/cache/vessel/app-catalog-v3.json /var/cache/vessel/app-catalog-v4.json /var/cache/vessel/app-catalog-v5.json; " +
                        "su -l vessel -c 'kbuildsycoca5 --noincremental' 2>/dev/null || true",
                    120,
                )
                refreshApps(appStore.value.query, appStore.value.sort, appStore.value.category)
                refreshSystemStats(silent = true)
            } catch (t: Throwable) {
                appStore.value = appStore.value.copy(error = t.message ?: "APT operation failed")
            } finally {
                appStore.value = appStore.value.copy(
                    busyPackage = "",
                    operationProgress = 0,
                    operationDetail = "",
                )
            }
        }
    }

    fun launchSystemInfo() {
        if (!state.value.running || !state.value.guestReady) return
        scope.launch(Dispatchers.IO) {
            try {
                var available = runtime.guest("command -v kinfocenter >/dev/null 2>&1", 10).optBoolean("ok")
                if (!available) {
                    state.value = state.value.copy(message = "Installing KDE Info Center once")
                    val result = runtime.guest(
                        "export DEBIAN_FRONTEND=noninteractive SYSTEMD_OFFLINE=1; " +
                            "find /var/lib/apt/lists -type f -mmin -360 -print -quit 2>/dev/null | grep -q . || apt-get -o Dpkg::Use-Pty=0 update >/dev/null; " +
                            "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y kinfocenter",
                        900,
                    )
                    available = result.optBoolean("ok")
                    check(available) { "Could not install kinfocenter: ${result.optString("output").takeLast(1600)}" }
                }
                val launched = runtime.guest(
                    """
                    set -e
                    pid=${'$'}(pgrep -u vessel -x plasmashell | head -1)
                    test -n "${'$'}pid"
                    envfile=/proc/${'$'}pid/environ
                    xdg=${'$'}(tr '\000' '\n' <"${'$'}envfile" | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -1)
                    way=${'$'}(tr '\000' '\n' <"${'$'}envfile" | sed -n 's/^WAYLAND_DISPLAY=//p' | head -1)
                    bus=${'$'}(tr '\000' '\n' <"${'$'}envfile" | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -1)
                    test -n "${'$'}xdg"; test -n "${'$'}way"; test -n "${'$'}bus"
                    su -l vessel -c "XDG_RUNTIME_DIR='${'$'}xdg' WAYLAND_DISPLAY='${'$'}way' DBUS_SESSION_BUS_ADDRESS='${'$'}bus' QT_QPA_PLATFORM=wayland XCURSOR_THEME=Breeze XCURSOR_SIZE=18 setsid -f kinfocenter >/tmp/vessel-kinfocenter.log 2>&1"
                    echo VESSEL_KINFOCENTER_STARTED
                    """.trimIndent(),
                    30,
                )
                check(launched.optBoolean("ok")) { "KInfoCenter launch failed: ${launched.optString("output").takeLast(1600)}" }
                state.value = state.value.copy(message = "KDE Info Center opened")
            } catch (t: Throwable) {
                state.value = state.value.copy(lastError = t.message ?: "Could not open KDE Info Center")
            }
        }
    }

    private fun decodeField(value: String): String = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrDefault("")

    private fun parseApps(raw: String): List<GuestApp> = raw.lineSequence().mapNotNull { line ->
        if (!line.startsWith("VESSEL_APP\t")) return@mapNotNull null
        val f = line.split('\t', limit = 11)
        if (f.size < 11 || !PACKAGE_RE.matches(f[1])) return@mapNotNull null
        GuestApp(
            packageName = f[1],
            installed = f[2] == "1",
            installedSizeKb = f[3].toLongOrNull() ?: 0,
            releaseTimestamp = f[4].toLongOrNull() ?: 0,
            popularityRank = f[5].toIntOrNull() ?: 10_000,
            category = decodeField(f[6]).ifBlank { "Other" },
            appstreamId = decodeField(f[7]),
            name = decodeField(f[8]).ifBlank { prettyPackageName(f[1]) },
            description = decodeField(f[9]).ifBlank { "Debian application" },
            iconBase64 = f[10],
        )
    }.toList()

    fun refreshSystemStats(silent: Boolean = false) {
        if (!state.value.running || !state.value.guestReady) {
            updateHostOnlyStats()
            return
        }
        if (!VesselGuestAgent.isConnected()) {
            updateHostOnlyStats()
            if (!silent) machineStats.value = machineStats.value.copy(error = "Debian control service is reconnecting")
            return
        }
        if (!silent) machineStats.value = machineStats.value.copy(loading = true, error = "")
        scope.launch(Dispatchers.IO) {
            try {
                val result = runtime.guest(
                    """
                    df -Pk / | awk 'NR==2 {printf "VESSEL_DF=%s,%s\n",${'$'}3,${'$'}4}'
                    free -m | awk '/^Mem:/ {printf "VESSEL_MEM=%s,%s\n",${'$'}3,${'$'}2}'
                    printf 'VESSEL_PKGS='; dpkg-query -W -f='${'$'}{binary:Package}\n' 2>/dev/null | wc -l
                    awk '{printf "VESSEL_UPTIME=%d\n",${'$'}1}' /proc/uptime
                    """.trimIndent(),
                    12,
                )
                var used = 0L
                var free = 0L
                var ramUsed = 0L
                var ramTotal = 0L
                var packages = 0
                var uptime = 0L
                result.optString("output").lineSequence().forEach { line ->
                    when {
                        line.contains("VESSEL_DF=") -> line.substringAfter("VESSEL_DF=").split(',').let {
                            if (it.size == 2) { used = (it[0].toLongOrNull() ?: 0) / 1024; free = (it[1].toLongOrNull() ?: 0) / 1024 }
                        }
                        line.contains("VESSEL_MEM=") -> line.substringAfter("VESSEL_MEM=").split(',').let {
                            if (it.size == 2) { ramUsed = it[0].toLongOrNull() ?: 0; ramTotal = it[1].toLongOrNull() ?: 0 }
                        }
                        line.contains("VESSEL_PKGS=") -> packages = line.substringAfter("VESSEL_PKGS=").trim().toIntOrNull() ?: 0
                        line.contains("VESSEL_UPTIME=") -> uptime = line.substringAfter("VESSEL_UPTIME=").trim().toLongOrNull() ?: 0
                    }
                }
                val host = hostDiskStats()
                machineStats.value = MachineStats(
                    guestDiskUsedMb = used,
                    guestDiskFreeMb = free,
                    guestRamUsedMb = ramUsed,
                    guestRamTotalMb = ramTotal,
                    packageCount = packages,
                    guestUptimeSeconds = uptime,
                    diskVirtualMb = host.first,
                    diskPhysicalMb = host.second,
                    hostFreeMb = host.third,
                    vcpus = runtime.selectedVcpus,
                )
            } catch (t: Throwable) {
                val host = hostDiskStats()
                machineStats.value = machineStats.value.copy(
                    diskVirtualMb = host.first,
                    diskPhysicalMb = host.second,
                    hostFreeMb = host.third,
                    loading = false,
                    error = if (silent) "" else t.message ?: "Could not read guest stats",
                )
            }
        }
    }

    private fun updateHostOnlyStats() {
        val host = hostDiskStats()
        machineStats.value = machineStats.value.copy(
            diskVirtualMb = host.first,
            diskPhysicalMb = host.second,
            hostFreeMb = host.third,
            loading = false,
        )
    }

    fun collectCrashDiagnostics() {
        diagnostics.value = "Collecting host + Debian diagnostics…"
        scope.launch(Dispatchers.IO) {
            val memory = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val host = buildString {
                appendLine("=== VESSEL HOST ===")
                appendLine("app=${BuildConfig.VERSION_NAME} commit=${BuildConfig.GIT_COMMIT}")
                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
                appendLine("hostAvailMiB=${memory.availMem / (1024 * 1024)} lowMemory=${memory.lowMemory} thresholdMiB=${memory.threshold / (1024 * 1024)}")
                appendLine("control=${VesselGuestAgent.status()}")
                appendLine("audio=${VesselAudioBridge.status()}")
                appendLine("presenter=${VesselWaylandPresenter.status()}")
                appendLine("vcpus=${runtime.selectedVcpus} guestRamMiB=${runtime.guestMemoryMb}")
            }
            val guest = if (state.value.running && state.value.guestReady && VesselGuestAgent.isConnected()) {
                runCatching {
                    runtime.guest(
                        """
                        printf '=== GUEST LOAD ===\\n'; cat /proc/loadavg; free -m
                        printf '\\n=== PROCESSES ===\\n'; ps -eo pid,ppid,stat,pcpu,pmem,comm --sort=-pcpu | head -35
                        printf '\\n=== AUDIO ===\\n'; su -l vessel -c 'pactl info; pactl list short sinks' 2>&1 || true
                        printf '\\n=== KERNEL ERRORS ===\\n'; dmesg 2>&1 | grep -Ei 'oom|out of memory|killed process|segfault|drm|virtio|virgl|gpu|rcu|napi|stall' | tail -160
                        printf '\\n=== PLASMA ===\\n'; tail -220 /tmp/vessel-plasma.log 2>/dev/null || true
                        printf '\\n=== FIREFOX CRASH ARTIFACTS ===\\n'; find /home/vessel/.mozilla -type f \\( -path '*/minidumps/*' -o -path '*/Crash Reports/*' \\) -printf '%TY-%Tm-%Td %TT %p\\n' 2>/dev/null | sort | tail -80 || true
                        """.trimIndent(),
                        30,
                    ).optString("output")
                }.getOrElse { "Guest diagnostics failed: ${it.message}" }
            } else {
                "Guest control service is not connected."
            }
            diagnostics.value = (host + "\n" + guest + "\n\n=== RUNTIME LOG TAIL ===\n" + state.value.console.takeLast(18_000)).takeLast(80_000)
        }
    }

    private fun hostDiskStats(): Triple<Long, Long, Long> {
        val disk = File(runtime.machineDir, "debian-docker.ext4")
        val virtualMb = if (disk.isFile) disk.length() / (1024L * 1024L) else 0L
        val physicalMb = if (disk.isFile) runCatching { Os.stat(disk.absolutePath).st_blocks * 512L / (1024L * 1024L) }.getOrDefault(virtualMb) else 0L
        val freeMb = runtime.machineDir.usableSpace / (1024L * 1024L)
        return Triple(virtualMb, physicalMb, freeMb)
    }

    fun expandDiskBy2GiB() {
        if (state.value.running || state.value.busy) {
            machineStats.value = machineStats.value.copy(error = "Stop Linux before expanding the disk")
            return
        }
        val disk = File(runtime.machineDir, "debian-docker.ext4")
        if (!disk.isFile) {
            machineStats.value = machineStats.value.copy(error = "Create the Vessel machine first")
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                check(runtime.machineDir.usableSpace > 512L * 1024L * 1024L) { "Android storage is too low" }
                RandomAccessFile(disk, "rw").use { file -> file.setLength(file.length() + 2L * 1024L * 1024L * 1024L) }
                updateHostOnlyStats()
                state.value = state.value.copy(message = "Disk expanded by 2 GiB; ext4 will grow on next boot")
            }.onFailure { machineStats.value = machineStats.value.copy(error = it.message ?: "Disk expansion failed") }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun prettyPackageName(value: String): String = value.split('-', '_').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}
