package com.example.dreamlinux

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Shared-kernel ARM64 Linux backend.
 *
 * App compatibility is implemented once at the runtime/session boundary:
 * identity, procfs visibility, shared memory, D-Bus, direct KGSL graphics and
 * compositor presentation. Linux applications are never selected by name here.
 */
class VesselProrootRuntimeBackend(
    context: Context,
    private val progress: (String, Int, String) -> Unit,
) : VesselRuntimeBackend,
    VesselPtyRuntimeProvider,
    VesselDirectGpuRuntimeProvider,
    VesselDesktopRuntimeProvider {

    companion object {
        const val REVISION = "proroot-desktop-compat-v4"
        const val DISPLAY_TRANSPORT = "proroot-kgsl-dmabuf-fence-ahb-v1"
    }

    private val appContext = context.applicationContext
    private val layout = VesselProrootLayout(appContext)
    private val procCompat = VesselProcCompat(appContext, layout.procCompatDir)
    private val lifecycleLock = Any()

    @Volatile private var desktopProcess: VesselManagedProrootProcess? = null
    @Volatile private var systemBusProcess: VesselManagedProrootProcess? = null
    @Volatile private var running = false
    @Volatile private var desktopReady = false
    @Volatile private var startedAt = 0L
    @Volatile private var lastError = ""

    @Volatile private var displayWidth = 1280
    @Volatile private var displayHeight = 720
    @Volatile private var displayDpi = 120
    @Volatile private var displayRefresh = 120f

    override val kind = VesselRuntimeKind.PROROOT
    override val id = "proroot"
    override val displayName = "proroot · shared Android kernel"
    override val revision = REVISION
    override val displayTransport = DISPLAY_TRANSPORT
    override val machineDir: File get() = layout.baseDir

    // Shared-kernel Linux has no fixed guest-RAM allocation.
    override val guestMemoryMb = 0
    override val processorCount: Int get() =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    override val graphicsSummary =
        "Wayland/KWin → Freedreno/Turnip KGSL → DMA-BUF/native fence → Vessel AHB Surface"
    override val internetSummary = "Android shared kernel · direct sockets/DNS"

    override fun hasStorageAccess(): Boolean = layout.prepareHostLayout()

    override fun hostAssetsReady(): Boolean =
        layout.runtimeLibraries().all { it.isFile && it.length() > 0L }

    fun rootfsReady(): Boolean = layout.rootfsReady()

    override fun directGpuReadiness(): VesselGpuReadiness {
        val device = File(VesselDirectGpuProfile.DEVICE)
        return VesselDirectGpuProfile.readiness(
            rootfs = layout.rootfsDir,
            deviceExists = device.exists(),
            deviceReadable = device.canRead(),
            deviceWritable = device.canWrite(),
        )
    }

    override fun desktopReadiness(verifyIntegrity: Boolean): VesselDesktopReadiness {
        if (!layout.prepareHostLayout()) {
            return VesselDesktopReadiness(false, "Vessel cannot prepare its private runtime storage")
        }
        if (!hostAssetsReady()) {
            return VesselDesktopReadiness(false, "proroot runtime libraries are not packaged yet")
        }
        if (verifyIntegrity && !officialRuntimeHashesMatch()) {
            return VesselDesktopReadiness(false, "proroot runtime integrity check failed")
        }
        if (!layout.rootfsReady()) {
            return VesselDesktopReadiness(false, "proroot directory rootfs is not installed yet")
        }

        val gpu = directGpuReadiness()
        if (!gpu.ready) return VesselDesktopReadiness(false, gpu.reason)

        val desktop = VesselProrootDesktopProfile.readiness(layout.rootfsDir)
        if (!desktop.ready) return desktop

        val uid = Process.myUid()
        val gid = Os.getgid()
        if (!layout.prepareDesktopIdentity(uid, gid)) {
            return VesselDesktopReadiness(false, "Could not prepare the normal Linux desktop identity")
        }
        if (!procCompat.prepare()) {
            return VesselDesktopReadiness(false, "Could not prepare procfs compatibility files")
        }
        if (layout.desktopHostSocket(uid).absolutePath.toByteArray().size >= 104) {
            return VesselDesktopReadiness(false, "Vessel display socket path exceeds AF_UNIX limits")
        }

        return VesselDesktopReadiness(
            true,
            "Plasma Wayland · direct KGSL · native DMA-BUF/fence display",
        )
    }

    override fun terminalReadiness(verifyIntegrity: Boolean): VesselTerminalRuntimeReadiness {
        val result = when {
            !layout.prepareHostLayout() ->
                VesselTerminalRuntimeReadiness(false, "Vessel cannot prepare its private terminal storage")
            !hostAssetsReady() ->
                VesselTerminalRuntimeReadiness(false, "proroot runtime libraries are not packaged yet")
            !layout.rootfsReady() ->
                VesselTerminalRuntimeReadiness(false, "proroot directory rootfs is not installed yet")
            verifyIntegrity && !officialRuntimeHashesMatch() ->
                VesselTerminalRuntimeReadiness(false, "proroot runtime integrity check failed")
            else -> {
                val gpu = directGpuReadiness()
                if (!gpu.ready) {
                    VesselTerminalRuntimeReadiness(false, gpu.reason)
                } else {
                    VesselTerminalRuntimeReadiness(
                        true,
                        "Native PTY · direct Freedreno/Turnip KGSL · bash",
                    )
                }
            }
        }
        return result
    }

    fun officialRuntimeHashesMatch(): Boolean =
        VesselProrootContract.EXPECTED_SHA256.all { (name, expected) ->
            val file = File(layout.nativeLibraryDir, name)
            file.isFile && sha256(file).equals(expected, ignoreCase = true)
        }

    private fun directGpuEnvironment(): Map<String, String> {
        val readiness = directGpuReadiness()
        check(readiness.ready) { readiness.reason }
        return VesselDirectGpuProfile.environment(readiness.icdGuestPath)
    }

    fun shellLaunchPlan(
        command: String,
        diagnostics: Boolean = false,
        includeSharedStorage: Boolean = true,
    ): VesselProrootLaunchPlan {
        check(layout.prepareHostLayout()) { "Could not prepare Vessel proroot storage" }
        check(hostAssetsReady()) { "Official proroot runtime libraries are not packaged" }
        check(layout.rootfsReady()) { "Vessel proroot directory rootfs is not installed" }
        check(layout.prepareGuestMountPointsIfReady()) { "Could not prepare Linux runtime mount points" }

        return VesselProrootContract.shell(
            launcherPath = File(layout.nativeLibraryDir, "libproroot.so").absolutePath,
            runtimeLibraryDir = layout.nativeLibraryDir.absolutePath,
            rootfsPath = layout.rootfsDir.absolutePath,
            prorootTmpPath = layout.prorootTmpDir.absolutePath,
            hostWorkingDirectory = layout.baseDir.absolutePath,
            binds = layout.binds(includeSharedStorage),
            command = command,
            diagnosticsLogPath = if (diagnostics) layout.diagnosticsLog.absolutePath else null,
            guestEnvironment = directGpuEnvironment(),
        )
    }

    override fun terminalLaunchPlan(
        includeSharedStorage: Boolean,
    ): VesselProrootLaunchPlan {
        check(terminalReadiness(verifyIntegrity = true).ready) {
            terminalReadiness(verifyIntegrity = false).reason
        }
        return VesselProrootContract.build(
            launcherPath = File(layout.nativeLibraryDir, "libproroot.so").absolutePath,
            runtimeLibraryDir = layout.nativeLibraryDir.absolutePath,
            rootfsPath = layout.rootfsDir.absolutePath,
            prorootTmpPath = layout.prorootTmpDir.absolutePath,
            hostWorkingDirectory = layout.baseDir.absolutePath,
            binds = layout.binds(includeSharedStorage),
            guestArgv = listOf("/bin/bash", "-l"),
            guestEnvironment = directGpuEnvironment(),
        )
    }

    private fun desktopLaunchPlan(
        guestArgv: List<String>,
        includeSharedStorage: Boolean = true,
    ): VesselProrootLaunchPlan {
        val uid = Process.myUid()
        val gid = Os.getgid()
        check(layout.prepareDesktopIdentity(uid, gid)) { "Could not prepare desktop user" }
        check(procCompat.prepare()) { "Could not prepare procfs compatibility overlay" }

        val identity = VesselProrootDesktopProfile.identity(uid)
        val environment = LinkedHashMap<String, String>()
        environment.putAll(directGpuEnvironment())
        environment.putAll(
            VesselProrootDesktopProfile.sessionEnvironment(
                androidUid = uid,
                guestSocketPath = layout.desktopGuestSocket(uid),
            ),
        )

        return VesselProrootContract.build(
            launcherPath = File(layout.nativeLibraryDir, "libproroot.so").absolutePath,
            runtimeLibraryDir = layout.nativeLibraryDir.absolutePath,
            rootfsPath = layout.rootfsDir.absolutePath,
            prorootTmpPath = layout.prorootTmpDir.absolutePath,
            hostWorkingDirectory = layout.baseDir.absolutePath,
            guestWorkingDirectory = identity.workingDirectory,
            identity = identity,
            binds = layout.binds(
                includeSharedStorage = includeSharedStorage,
                identityOverlay = true,
                procCompat = procCompat.binds,
            ),
            guestArgv = guestArgv,
            guestEnvironment = environment,
        )
    }

    override fun configureDisplay(width: Int, height: Int, dpi: Int, refresh: Float) {
        displayWidth = width.coerceIn(640, 3840)
        displayHeight = height.coerceIn(480, 2160)
        displayDpi = dpi.coerceIn(72, 480)
        displayRefresh = refresh.coerceIn(30f, 240f)
        if (running) {
            VesselProrootDisplayBridge.configure(displayWidth, displayHeight, displayRefresh)
        }
    }

    override suspend fun resizeDesktop(
        width: Int,
        height: Int,
        dpi: Int,
        refresh: Float,
    ): Boolean = withContext(Dispatchers.IO) {
        configureDisplay(width, height, dpi, refresh)
        running
    }

    override fun input(type: String, values: Map<String, Any>) {
        if (!running) return
        VesselVirtioInput.send(type, values)
    }

    override suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        val storage = layout.prepareHostLayout()
        val gpu = directGpuReadiness()
        val desktop = if (layout.rootfsReady()) {
            VesselProrootDesktopProfile.readiness(layout.rootfsDir)
        } else {
            VesselDesktopReadiness(false, "rootfs missing")
        }
        val process = desktopProcess
        if (running && process != null && !process.isAlive()) {
            running = false
            desktopReady = false
            process.exitCodeOrNull()?.let { rc ->
                if (rc != 0 && lastError.isBlank()) lastError = "Plasma session exited rc=" + rc
            }
        }
        val presenter = VesselWaylandPresenter.status()
        val bridge = VesselProrootDisplayBridge.status()
        baseState(storage)
            .put("rootfsReady", layout.rootfsReady())
            .put("runtimeAssetsReady", hostAssetsReady())
            .put("prorootVersion", VesselProrootContract.VERSION)
            .put("rootfsDir", layout.rootfsDir.absolutePath)
            .put("prorootTmpDir", layout.prorootTmpDir.absolutePath)
            .put("directGpuReady", gpu.ready)
            .put("directGpuReason", gpu.reason)
            .put("gpuDevice", VesselDirectGpuProfile.DEVICE)
            .put("mesaVersion", VesselDirectGpuProfile.MESA_VERSION)
            .put("vulkanIcd", gpu.icdGuestPath)
            .put("desktopRuntimeReady", desktop.ready)
            .put("desktopRuntimeReason", desktop.reason)
            .put("displayBridge", bridge)
            .put("presenter", presenter)
            .put("audioTransport", VesselAudioBridge.status())
            .put("logTail", process?.outputTail().orEmpty())
    }

    override suspend fun startDesktop(): JSONObject = withContext(Dispatchers.IO) {
        val alreadyRunning = synchronized(lifecycleLock) {
            running && desktopProcess?.isAlive() == true
        }
        if (alreadyRunning) return@withContext status()

        synchronized(lifecycleLock) {
            stopInternal()
            lastError = ""
        }

        val readiness = desktopReadiness(verifyIntegrity = true)
        check(readiness.ready) { readiness.reason }

        progress("proroot_compat", 72, "Preparing generic Linux ABI/session compatibility")
        prepareAudioBridge()
        procCompat.start()

        val uid = Process.myUid()
        val socket = layout.desktopHostSocket(uid)
        VesselWaylandPresenter.resetPresentationLatch()
        check(
            VesselProrootDisplayBridge.start(
                context = appContext,
                socket = socket,
                width = displayWidth,
                height = displayHeight,
                refresh = displayRefresh,
            ),
        ) { "Could not start Vessel native compositor display bridge" }

        try {
            progress("proroot_dbus", 76, "Starting Linux system D-Bus")
            startSystemBus()

            progress("proroot_probe", 80, "Checking shared memory, procfs, IPC and D-Bus semantics")
            val probePlan = desktopLaunchPlan(listOf(VesselProrootDesktopProfile.PROBE))
            val probe = VesselProrootProcessRunner.run(
                probePlan,
                timeoutSeconds = 20,
                logFile = File(layout.diagnosticsDir, "compat-probe.log"),
            )
            check(probe.exitCode == 0 && probe.output.contains("VESSEL_COMPAT_OK=desktop-runtime")) {
                "Linux compatibility probe failed: " + probe.output.takeLast(6000)
            }

            progress("proroot_plasma", 86, "Launching normal-user Plasma Wayland session")
            val plan = desktopLaunchPlan(listOf(VesselProrootDesktopProfile.STARTER))
            val process = VesselProrootProcessNative.spawn(plan, layout.desktopLog)
            synchronized(lifecycleLock) {
                desktopProcess = process
                running = true
                startedAt = SystemClock.elapsedRealtime()
            }
            watchDesktop(process)

            val deadline = SystemClock.elapsedRealtime() + 30_000L
            var bridge = VesselProrootDisplayBridge.status()
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!process.isAlive()) {
                    error(
                        "Plasma exited before the native display became ready: " +
                            process.outputTail().takeLast(6000),
                    )
                }
                bridge = VesselProrootDisplayBridge.status()
                if (bridge == "presenting-proroot-dmabuf") break
                Thread.sleep(50)
            }
            check(bridge == "presenting-proroot-dmabuf") {
                "KWin did not connect to Vessel's DMA-BUF display bridge: " + bridge
            }

            desktopReady = true
            progress("proroot_ready", 100, "Plasma Wayland is using direct KGSL + native Android presentation")
            status()
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            synchronized(lifecycleLock) { stopInternal() }
            throw error
        }
    }

    override suspend fun guest(command: String, timeoutSeconds: Int): JSONObject =
        withContext(Dispatchers.IO) {
            val result = VesselProrootProcessRunner.run(
                shellLaunchPlan(command),
                timeoutSeconds = timeoutSeconds,
                logFile = File(layout.diagnosticsDir, "guest-command.log"),
            )
            JSONObject()
                .put("ok", result.exitCode == 0)
                .put("exitCode", result.exitCode)
                .put("output", result.output)
        }

    override suspend fun stop(): JSONObject = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) { stopInternal() }
        status()
    }

    private fun prepareAudioBridge() {
        val port = VesselAudioBridge.port()
        check(port > 0) { "Android AudioTrack bridge is not running" }

        val helper = File(layout.rootfsDir, "usr/local/lib/vessel/audio_pipe.py")
        helper.parentFile?.mkdirs()
        appContext.assets.open("vessel/guest_audio_pipe.py").use { input ->
            helper.outputStream().use { output -> input.copyTo(output) }
        }
        runCatching { Os.chmod(helper.absolutePath, 0x1ED) } // 0755

        val asound = File(layout.rootfsDir, "etc/asound.conf")
        asound.parentFile?.mkdirs()
        asound.writeText(
            """
            pcm.vessel_raw {
                type file
                slave.pcm "null"
                file "|/usr/bin/env VESSEL_AUDIO_HOST=127.0.0.1 /usr/bin/python3 /usr/local/lib/vessel/audio_pipe.py $port 48000 2 16 S16_LE"
                format "raw"
            }
            pcm.vessel {
                type plug
                slave {
                    pcm "vessel_raw"
                    format S16_LE
                    rate 48000
                    channels 2
                }
            }
            pcm.!default {
                type plug
                slave.pcm "vessel"
            }
            """.trimIndent() + "\n",
        )

        val pulse = File(layout.rootfsDir, "home/vessel/.config/pulse/default.pa")
        pulse.parentFile?.mkdirs()
        pulse.writeText(
            """
            .include /etc/pulse/default.pa
            load-module module-alsa-sink device=vessel sink_name=vessel sink_properties=device.description=Vessel_Android_Audio
            set-default-sink vessel
            """.trimIndent() + "\n",
        )
    }

    private fun startSystemBus() {
        systemBusProcess?.takeIf { it.isAlive() }?.let { return }
        val command = """
            set -e
            install -d -m 755 /run/dbus
            rm -f /run/dbus/system_bus_socket /run/dbus/pid
            exec dbus-daemon --system --nofork --nopidfile
        """.trimIndent()
        val process = VesselProrootProcessNative.spawn(
            shellLaunchPlan(command, includeSharedStorage = false),
            File(layout.diagnosticsDir, "system-dbus.log"),
        )
        systemBusProcess = process

        val socket = File(layout.guestRunDir, "dbus/system_bus_socket")
        val deadline = SystemClock.elapsedRealtime() + 5000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!process.isAlive()) {
                error("System D-Bus exited: " + process.outputTail().takeLast(3000))
            }
            if (socket.exists()) return
            Thread.sleep(25)
        }
        error("System D-Bus socket did not become ready")
    }

    private fun watchDesktop(process: VesselManagedProrootProcess) {
        Thread({
            while (process.isAlive()) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            synchronized(lifecycleLock) {
                if (desktopProcess !== process) return@synchronized
                val rc = process.exitCodeOrNull()
                running = false
                desktopReady = false
                if (rc != null && rc != 0 && lastError.isBlank()) {
                    lastError = "Plasma session exited rc=" + rc
                }
                desktopProcess = null
                runCatching { systemBusProcess?.close(3000) }
                systemBusProcess = null
                VesselProrootDisplayBridge.stop()
                procCompat.stop()
            }
        }, "vessel-proroot-desktop-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopInternal() {
        desktopReady = false
        running = false
        val desktop = desktopProcess
        desktopProcess = null
        if (desktop != null) {
            runCatching { desktop.close(5000) }
                .onFailure { lastError = it.message ?: "Could not stop Plasma session cleanly" }
        }
        val dbus = systemBusProcess
        systemBusProcess = null
        if (dbus != null) runCatching { dbus.close(3000) }
        VesselProrootDisplayBridge.stop()
        procCompat.stop()
        File(layout.guestRunDir, "dbus/system_bus_socket").delete()
    }

    private fun baseState(ok: Boolean): JSONObject {
        val presenter = VesselWaylandPresenter.status()
        val bridge = VesselProrootDisplayBridge.status()
        val frameReady = presenter.startsWith("presenting-native-surface") ||
            presenter.startsWith("presenting-retained")
        return JSONObject()
            .put("ok", ok)
            .put("backend", "PROROOT")
            .put("protocolVersion", 4)
            .put("runtimeRevision", revision)
            .put("displayTransport", displayTransport)
            .put("rendererMode", "freedreno-turnip-kgsl-direct")
            .put("translationLayer", "none")
            .put("gpuOnly", true)
            .put("softwareFallback", false)
            .put("running", running)
            .put("guestReady", hostAssetsReady() && layout.rootfsReady())
            .put("desktopReady", desktopReady)
            .put("frameContentValidated", frameReady)
            .put("inputConnected", bridge == "presenting-proroot-dmabuf")
            .put("machineDir", machineDir.absolutePath)
            .put("guestMemoryMb", 0)
            .put("processorCount", processorCount)
            .put("displayWidth", displayWidth)
            .put("displayHeight", displayHeight)
            .put("displayDpi", displayDpi)
            .put("displayRefresh", displayRefresh.toDouble())
            .put("uptimeMs", if (running) SystemClock.elapsedRealtime() - startedAt else 0L)
            .put("lastError", lastError)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
