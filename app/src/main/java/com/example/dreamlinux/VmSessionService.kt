package com.example.dreamlinux

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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GuestApp(
    val packageName: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    val installedSizeKb: Long = 0,
)

data class AppStoreState(
    val apps: List<GuestApp> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val busyPackage: String = "",
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
    val connected:Boolean=false,
    val running:Boolean=false,
    val guestReady:Boolean=false,
    val displayReady:Boolean=false,
    val inputReady:Boolean=false,
    val frameReachedApp:Boolean=false,
    val name:String="Vessel Debian",
    val message:String="Ready",
    val busy:Boolean=false,
    val stage:String="idle",
    val progressPercent:Int=0,
    val progressDetail:String="Runtime stopped",
    val lastError:String="",
    val console:String="",
    val terminalOutput:String="",
    val graphics:String="VirtIO GPU · VirGL · ANGLE · AHardwareBuffer · Vulkan · Adreno",
    val presenterStatus:String="not-started",
    val rendererMode:String="virgl-opengl",
    val translationLayer:String="VirGL",
    val displayTransport:String=VesselRuntimeController.DISPLAY_TRANSPORT,
    val runtimeRevision:String=VesselRuntimeController.REVISION,
    val machinePath:String="Download/LinuxPC/Vessel-Debian",
    val internetStage:String="UML vector net · passt",
    val uptimeMs:Long=0L,
    val storageReady:Boolean=false,
    val hostAssetsReady:Boolean=false,
    val guestMemoryMb:Int=0,
    val guestDisplayWidth:Int=1920,
    val guestDisplayHeight:Int=1080,
)

class VmSessionService : Service() {
    companion object {
        val state = MutableStateFlow(SessionState())
        val appStore = MutableStateFlow(AppStoreState())
        val machineStats = MutableStateFlow(MachineStats())
        @Volatile var active: VmSessionService? = null

        private val DEFAULT_APPS = linkedMapOf(
            "firefox-esr" to "Firefox",
            "konsole" to "Konsole",
            "dolphin" to "Dolphin",
            "okular" to "Okular",
            "ark" to "Ark",
            "gwenview" to "Gwenview",
            "kcalc" to "KCalc",
        )
        private val POPULAR_APPS = linkedMapOf(
            "firefox-esr" to "Firefox",
            "konsole" to "Konsole",
            "dolphin" to "Dolphin",
            "okular" to "Okular",
            "vlc" to "VLC",
            "gwenview" to "Gwenview",
            "ark" to "Ark",
            "kcalc" to "KCalc",
            "kate" to "Kate",
            "libreoffice-writer" to "LibreOffice Writer",
            "gimp" to "GIMP",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: VesselRuntimeController
    private var w = 1920
    private var h = 1080
    private var dpi = 120
    private var refresh = 60f
    @Volatile private var operationGeneration = 0L
    private var lastStatsAt = 0L

    private fun nextOperation(): Long = synchronized(this) { ++operationGeneration }

    override fun onCreate() {
        super.onCreate()
        active = this
        runtime = VesselRuntimeController(this) { phase, pct, detail ->
            state.value = state.value.copy(stage=phase, progressPercent=pct, progressDetail=detail, message=detail)
        }
        configureStableLandscape()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vessel-runtime", "Vessel Linux runtime", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, VesselActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
                if (state.value.running && state.value.guestReady && now - lastStatsAt > 5000) {
                    lastStatsAt = now
                    refreshSystemStats(silent = true)
                }
                delay(if (state.value.running || state.value.busy) 300 else 1500)
            }
        }
    }

    private fun configureStableLandscape() {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val physicalLong = max(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
        val physicalShort = min(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
        val targetW = min(physicalLong, 1920)
        val targetH = ((physicalShort.toDouble() * targetW / physicalLong).roundToInt().coerceAtLeast(720) / 2) * 2
        w = (targetW / 8) * 8
        h = targetH
        // Linux desktop DPI must be desktop-like. Android's 400-500 dpi would make Plasma enormous.
        dpi = 120
        refresh = (mode?.refreshRate ?: 60f).coerceIn(60f, 120f)
        runtime.configureDisplay(w, h, dpi, refresh)
        state.value = state.value.copy(guestDisplayWidth=w, guestDisplayHeight=h)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        refreshAvailability()
        return START_STICKY
    }

    override fun onDestroy() {
        if (active === this) active = null
        scope.cancel()
        VesselWaylandPresenter.shutdown()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

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
            guestDisplayWidth = w,
            guestDisplayHeight = h,
            message = when {
                !storage -> "Grant file access for Download/LinuxPC"
                !assets -> "Native runtime assets missing from APK"
                old.running || old.busy -> old.message
                else -> "Ready to start Vessel"
            },
        )
    }

    private fun applyState(o: JSONObject) {
        val presenter = VesselWaylandPresenter.status()
        val err = o.optString("lastError")
        val frame = o.optBoolean("frameContentValidated") || presenter.startsWith("presenting-dmabuf")
        val stoppingNow = state.value.stage == "stopping"
        state.value = state.value.copy(
            running = o.optBoolean("running"),
            guestReady = o.optBoolean("guestReady"),
            displayReady = o.optBoolean("desktopReady") || presenter.startsWith("presenting-dmabuf"),
            frameReachedApp = frame,
            inputReady = o.optBoolean("inputConnected"),
            presenterStatus = presenter,
            console = o.optString("logTail", state.value.console),
            lastError = err,
            uptimeMs = o.optLong("uptimeMs"),
            graphics = o.optString("renderer", state.value.graphics),
            rendererMode = o.optString("rendererMode", state.value.rendererMode),
            translationLayer = o.optString("translationLayer", state.value.translationLayer),
            displayTransport = o.optString("displayTransport", state.value.displayTransport),
            runtimeRevision = o.optString("runtimeRevision", state.value.runtimeRevision),
            guestMemoryMb = o.optInt("guestMemoryMb", state.value.guestMemoryMb),
            guestDisplayWidth = o.optInt("displayWidth", w),
            guestDisplayHeight = o.optInt("displayHeight", h),
            message = when {
                stoppingNow -> "Stopping Linux"
                err.isNotBlank() -> err
                presenter.startsWith("presenting-dmabuf") -> "Plasma visible · AHardwareBuffer GPU path"
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

    /** Surface size is presentation-only. Guest XRandR stays at one stable landscape mode. */
    fun configureDisplay(width: Int, height: Int, densityDpi: Int, rate: Float) {
        // Kept as a compatibility hook for older UI code. Do not feed transient Surface sizes to XRandR.
        if (width <= 0 || height <= 0 || densityDpi <= 0 || rate <= 0f) return
    }

    fun sendInput(type: String, values: Map<String, Any>) = runtime.input(type, values)

    fun startVm() {
        if (state.value.busy) return
        refreshAvailability()
        if (!state.value.connected) return
        val op = nextOperation()
        state.value = state.value.copy(
            busy=true, lastError="", stage="starting", progressPercent=1,
            progressDetail="Starting self-contained Vessel runtime", message="Starting Linux", terminalOutput="",
        )
        scope.launch(Dispatchers.IO) {
            try {
                val o = runtime.startDesktop()
                if (op != operationGeneration) return@launch
                // Desktop packages are deliberately separate from the Plasma core check so an
                // existing persistent image also receives the workstation apps added by Vessel.
                ensureWorkstationApps(op)
                if (op != operationGeneration) return@launch
                ensureDesktopProfile(op)
                launch(Dispatchers.Main) {
                    if (op == operationGeneration) {
                        applyState(o)
                        state.value = state.value.copy(progressPercent=100, progressDetail="Vessel workstation ready", message="Vessel workstation ready")
                    }
                }
                refreshApps("")
                refreshSystemStats(silent = true)
            } catch (t: Throwable) {
                val snapshot = runCatching { runtime.status() }.getOrNull()
                launch(Dispatchers.Main) {
                    if (op != operationGeneration) return@launch
                    if (snapshot != null) applyState(snapshot)
                    state.value = state.value.copy(lastError=t.message ?: t.javaClass.simpleName, message=t.message ?: "Startup failed")
                }
            } finally {
                launch(Dispatchers.Main) {
                    if (op == operationGeneration) {
                        val current = state.value
                        state.value = current.copy(busy=false, stage=if (current.running) current.stage else "idle")
                    }
                }
            }
        }
    }

    private suspend fun ensureWorkstationApps(op: Long) {
        if (!state.value.running || !state.value.guestReady) return
        state.value = state.value.copy(stage="workstation_apps", progressPercent=95, progressDetail="Checking workstation apps", message="Checking workstation apps")
        val packages = DEFAULT_APPS.keys + "plasma-workspace-wallpapers"
        val quoted = packages.joinToString(" ") { shellQuote(it) }
        val check = runtime.guest(
            "missing=''; for p in $quoted; do dpkg-query -W -f='\${Status}' \"\$p\" 2>/dev/null | grep -q 'install ok installed' || missing=\"\$missing \$p\"; done; echo VESSEL_MISSING=\$missing",
            45,
        )
        val out = check.optString("output")
        val missing = out.lineSequence().firstOrNull { "VESSEL_MISSING=" in it }?.substringAfter("VESSEL_MISSING=")?.trim().orEmpty()
        if (missing.isNotBlank()) {
            state.value = state.value.copy(progressPercent=96, progressDetail="Installing Firefox and desktop apps", message="Installing workstation apps")
            val install = runtime.guest(
                "export DEBIAN_FRONTEND=noninteractive SYSTEMD_OFFLINE=1; " +
                    "mkdir -p /usr/sbin; printf '#!/bin/sh\\nexit 101\\n' >/usr/sbin/policy-rc.d; chmod 755 /usr/sbin/policy-rc.d; " +
                    "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update && " +
                    "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y --no-install-recommends $missing && apt-get clean",
                1800,
            )
            if (!install.optBoolean("ok")) {
                throw IllegalStateException("Workstation app installation failed; see Runtime log")
            }
        }
        if (op != operationGeneration) return
        runtime.guest(
            "install -d -o vessel -g vessel /home/vessel/Desktop; " +
                "for f in firefox-esr org.kde.konsole; do src=/usr/share/applications/\$f.desktop; [ -f \"\$src\" ] && install -m 755 -o vessel -g vessel \"\$src\" /home/vessel/Desktop/ || true; done",
            30,
        )
    }

    private suspend fun ensureDesktopProfile(op: Long) {
        if (op != operationGeneration || !state.value.running) return
        state.value = state.value.copy(progressPercent=98, progressDetail="Finishing Plasma desktop profile", message="Finishing desktop setup")
        // Reset only once for the P39 workstation profile. After this marker exists, Vessel
        // leaves every user customization untouched.
        val command = """
            marker=/home/vessel/.config/.vessel-p39-workstation-v1
            if [ ! -e "${'$'}marker" ]; then
              install -d -m 700 -o vessel -g vessel /home/vessel/.config
              pkill -u vessel -x plasmashell 2>/dev/null || true
              sleep .2
              rm -f /home/vessel/.config/plasma-org.kde.plasma.desktop-appletsrc
              su -l vessel -c 'lookandfeeltool -a org.kde.breeze.desktop >/tmp/vessel-lookandfeel.log 2>&1 || true'
              touch "${'$'}marker"
              chown vessel:vessel "${'$'}marker"
              pkill -u vessel -x startplasma-x11 2>/dev/null || true
              pkill -u vessel -x plasma_session 2>/dev/null || true
              pkill -u vessel -x ksmserver 2>/dev/null || true
              pkill -u vessel -x kded5 2>/dev/null || true
              pkill -u vessel -x kwin_x11 2>/dev/null || true
              nohup su -l vessel -c "DISPLAY=:0 XDG_RUNTIME_DIR=/run/user/${'$'}(id -u vessel) dbus-run-session -- /usr/local/bin/vessel-plasma-session" >/tmp/vessel-plasma-profile.log 2>&1 </dev/null &
            fi
        """.trimIndent()
        runtime.guest(command, 45)
    }

    fun stopVm() {
        val current = state.value
        if (!current.running && !current.busy) return
        if (current.stage == "stopping") return
        val op = nextOperation()
        state.value = current.copy(busy=true, stage="stopping", progressDetail="Stopping Linux safely", message="Stopping Linux", lastError="")
        scope.launch(Dispatchers.IO) {
            val r = runCatching { runtime.stop() }
            launch(Dispatchers.Main) {
                if (op != operationGeneration) return@launch
                r.onSuccess(::applyState).onFailure { state.value = state.value.copy(lastError=it.message ?: "Stop failed") }
                state.value = state.value.copy(
                    busy=false, running=false, guestReady=false, displayReady=false, inputReady=false, frameReachedApp=false,
                    stage="idle", progressPercent=0, progressDetail="Runtime stopped", message="Linux stopped; disk retained",
                )
            }
        }
    }

    fun debianConsole(command: String) = runGuestCommand(command)

    fun runGuestCommand(command: String) {
        if (state.value.busy || !state.value.running || !state.value.guestReady || command.isBlank()) return
        state.value = state.value.copy(busy=true, terminalOutput=state.value.terminalOutput + "\n$ $command\n")
        scope.launch(Dispatchers.IO) {
            val r = runCatching { runtime.guest(command, 90) }
            launch(Dispatchers.Main) {
                r.onSuccess { o ->
                    applyState(o)
                    val out = o.optString("output")
                    state.value = state.value.copy(
                        terminalOutput=(state.value.terminalOutput + out + if (out.endsWith("\n") || out.isBlank()) "" else "\n").takeLast(120_000),
                    )
                }.onFailure {
                    state.value = state.value.copy(lastError=it.message ?: "Command failed", terminalOutput=state.value.terminalOutput + "${it.message}\n")
                }
                state.value = state.value.copy(busy=false)
            }
        }
    }

    fun runGpuDiagnostics() {
        runGuestCommand(
            "DISPLAY=:0 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl glxinfo -B; " +
                "printf '\\nDRM:\\n'; ls -l /dev/dri; printf '\\nINPUT:\\n'; " +
                "grep -E 'Name=\"Vessel (Trackpad|Touchscreen|Keyboard)\"' /proc/bus/input/devices; " +
                "printf '\\nXINPUT:\\n'; DISPLAY=:0 xinput list",
        )
    }

    fun refreshApps(query: String) {
        if (!state.value.running || !state.value.guestReady) {
            appStore.value = appStore.value.copy(error="Start Linux to browse Debian apps")
            return
        }
        appStore.value = appStore.value.copy(query=query, loading=true, error="")
        scope.launch(Dispatchers.IO) {
            try {
                val packages: List<String> = if (query.isBlank()) {
                    POPULAR_APPS.keys.toList()
                } else {
                    val result = runtime.guest(
                        "apt-cache search --names-only ${shellQuote(query)} 2>/dev/null | awk -F' - ' '{print \\$1}' | head -30",
                        30,
                    )
                    result.optString("output").lineSequence()
                        .map { it.trim() }
                        .filter { PACKAGE_RE.matches(it) }
                        .distinct()
                        .take(30)
                        .toList()
                }
                if (packages.isEmpty()) {
                    appStore.value = AppStoreState(emptyList(), query, false, "", "No Debian apps found")
                    return@launch
                }
                val packageWords = packages.joinToString(" ") { shellQuote(it) }
                val result = runtime.guest(
                    """
                    for p in $packageWords; do
                      apt-cache show --no-all-versions "${'$'}p" >/tmp/vessel-app-meta 2>/dev/null || continue
                      desc=${'$'}(sed -n 's/^Description[^:]*: //p' /tmp/vessel-app-meta | head -1 | tr '\t' ' ')
                      size=${'$'}(sed -n 's/^Installed-Size: //p' /tmp/vessel-app-meta | head -1)
                      installed=0
                      dpkg-query -W -f='${'$'}{db:Status-Abbrev}' "${'$'}p" 2>/dev/null | grep -q '^ii' && installed=1 || true
                      printf 'VESSEL_APP\t%s\t%s\t%s\t%s\n' "${'$'}p" "${'$'}installed" "${'$'}{size:-0}" "${'$'}desc"
                    done
                    rm -f /tmp/vessel-app-meta
                    """.trimIndent(),
                    45,
                )
                val apps = parseApps(result.optString("output"))
                appStore.value = AppStoreState(apps, query, false, "", "")
            } catch (t: Throwable) {
                appStore.value = appStore.value.copy(loading=false, error=t.message ?: "Could not query APT")
            }
        }
    }

    fun installApp(packageName: String) = changeApp(packageName, install=true)
    fun removeApp(packageName: String) = changeApp(packageName, install=false)

    private fun changeApp(packageName: String, install: Boolean) {
        if (!PACKAGE_RE.matches(packageName) || !state.value.running || !state.value.guestReady) return
        appStore.value = appStore.value.copy(busyPackage=packageName, error="")
        scope.launch(Dispatchers.IO) {
            try {
                val action = if (install) {
                    "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update >/dev/null && apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y --no-install-recommends ${shellQuote(packageName)}"
                } else {
                    "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 remove -y ${shellQuote(packageName)}"
                }
                val result = runtime.guest(
                    "export DEBIAN_FRONTEND=noninteractive SYSTEMD_OFFLINE=1; mkdir -p /usr/sbin; printf '#!/bin/sh\\nexit 101\\n' >/usr/sbin/policy-rc.d; chmod 755 /usr/sbin/policy-rc.d; $action",
                    1800,
                )
                if (!result.optBoolean("ok")) error("APT returned rc=${result.optInt("rc", -1)}")
                refreshApps(appStore.value.query)
                refreshSystemStats(silent=true)
            } catch (t: Throwable) {
                appStore.value = appStore.value.copy(error=t.message ?: "APT operation failed")
            } finally {
                appStore.value = appStore.value.copy(busyPackage="")
            }
        }
    }

    private fun parseApps(raw: String): List<GuestApp> = raw.lineSequence().mapNotNull { line ->
        if (!line.startsWith("VESSEL_APP\t")) return@mapNotNull null
        val fields = line.split('\t', limit=5)
        if (fields.size < 5) return@mapNotNull null
        val pkg = fields[1]
        GuestApp(
            packageName=pkg,
            name=POPULAR_APPS[pkg] ?: prettyPackageName(pkg),
            description=fields[4].ifBlank { "Debian package" },
            installed=fields[2] == "1",
            installedSizeKb=fields[3].toLongOrNull() ?: 0,
        )
    }.toList()

    fun refreshSystemStats(silent: Boolean = false) {
        if (!state.value.running || !state.value.guestReady) {
            updateHostOnlyStats()
            return
        }
        if (!silent) machineStats.value = machineStats.value.copy(loading=true, error="")
        scope.launch(Dispatchers.IO) {
            try {
                val result = runtime.guest(
                    """
                    df -Pk / | awk 'NR==2 {printf "VESSEL_DF=%s,%s\n",${'$'}3,${'$'}4}'
                    free -m | awk '/^Mem:/ {printf "VESSEL_MEM=%s,%s\n",${'$'}3,${'$'}2}'
                    printf 'VESSEL_PKGS='; dpkg-query -W -f='${'$'}{binary:Package}\n' 2>/dev/null | wc -l
                    awk '{printf "VESSEL_UPTIME=%d\n",${'$'}1}' /proc/uptime
                    """.trimIndent(),
                    30,
                )
                var used=0L; var free=0L; var ramUsed=0L; var ramTotal=0L; var pkgs=0; var uptime=0L
                result.optString("output").lineSequence().forEach { line ->
                    when {
                        line.contains("VESSEL_DF=") -> line.substringAfter("VESSEL_DF=").split(',').let { if (it.size==2) { used=(it[0].toLongOrNull()?:0)/1024; free=(it[1].toLongOrNull()?:0)/1024 } }
                        line.contains("VESSEL_MEM=") -> line.substringAfter("VESSEL_MEM=").split(',').let { if (it.size==2) { ramUsed=it[0].toLongOrNull()?:0; ramTotal=it[1].toLongOrNull()?:0 } }
                        line.contains("VESSEL_PKGS=") -> pkgs=line.substringAfter("VESSEL_PKGS=").trim().toIntOrNull()?:0
                        line.contains("VESSEL_UPTIME=") -> uptime=line.substringAfter("VESSEL_UPTIME=").trim().toLongOrNull()?:0
                    }
                }
                val host = hostDiskStats()
                machineStats.value = MachineStats(used, free, ramUsed, ramTotal, pkgs, uptime, host.first, host.second, host.third, 6, false, "")
            } catch (t: Throwable) {
                val host = hostDiskStats()
                machineStats.value = machineStats.value.copy(
                    diskVirtualMb=host.first, diskPhysicalMb=host.second, hostFreeMb=host.third,
                    loading=false, error=t.message ?: "Could not read guest stats",
                )
            }
        }
    }

    private fun updateHostOnlyStats() {
        val host = hostDiskStats()
        machineStats.value = machineStats.value.copy(diskVirtualMb=host.first, diskPhysicalMb=host.second, hostFreeMb=host.third, loading=false)
    }

    private fun hostDiskStats(): Triple<Long, Long, Long> {
        val disk = File(runtime.machineDir, "debian-docker.ext4")
        val virtualMb = if (disk.isFile) disk.length() / (1024L * 1024L) else 0
        val physicalMb = if (disk.isFile) runCatching { Os.stat(disk.absolutePath).st_blocks * 512L / (1024L * 1024L) }.getOrDefault(virtualMb) else 0
        val freeMb = runtime.machineDir.usableSpace / (1024L * 1024L)
        return Triple(virtualMb, physicalMb, freeMb)
    }

    fun expandDiskBy2GiB() {
        if (state.value.running || state.value.busy) {
            machineStats.value = machineStats.value.copy(error="Stop Linux before expanding the disk")
            return
        }
        val disk = File(runtime.machineDir, "debian-docker.ext4")
        if (!disk.isFile) {
            machineStats.value = machineStats.value.copy(error="Create the Vessel machine first")
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val add = 2L * 1024L * 1024L * 1024L
                check(runtime.machineDir.usableSpace > 512L * 1024L * 1024L) { "Android storage is too low" }
                RandomAccessFile(disk, "rw").use { it.setLength(it.length() + add) }
                updateHostOnlyStats()
                state.value = state.value.copy(message="Disk expanded by 2 GiB; ext4 will grow on next boot")
            }.onFailure { machineStats.value = machineStats.value.copy(error=it.message ?: "Disk expansion failed") }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun prettyPackageName(value: String): String = value.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private companion object Validation {
        val PACKAGE_RE = Regex("[a-z0-9][a-z0-9+.-]{0,127}")
    }
}
