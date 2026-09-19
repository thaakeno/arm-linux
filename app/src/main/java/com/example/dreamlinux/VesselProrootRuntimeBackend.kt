package com.example.dreamlinux

import android.content.Context
import android.net.ConnectivityManager
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
        const val REVISION = "proroot-production-v10"
        const val DISPLAY_TRANSPORT = "proroot-kgsl-surfacecontrol-ahb-fence-v2"
    }

    private val appContext = context.applicationContext
    private val layout = VesselProrootLayout(appContext)
    private val procCompat = VesselProcCompat(appContext, layout.procCompatDir)
    private val startupJournal = VesselStartupJournal(appContext)
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
        "Wayland/KWin → Freedreno/Turnip KGSL → DMA-BUF/native fence → " +
            "SurfaceControl zero-copy (API 36+) / GPU-blit fallback"
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
        if (!File(layout.rootfsDir, "var/cache/vessel/proroot-production-v1").isFile) {
            return VesselDesktopReadiness(false, "Phase-6 production rootfs marker is missing")
        }
        if (!prepareRootlessDesktopPolicy()) {
            return VesselDesktopReadiness(false, "Could not prepare rootless Plasma session policy")
        }
        if (!prepareDns()) {
            return VesselDesktopReadiness(false, "Could not prepare Android network DNS for Linux")
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

    /**
     * Patch session policy from the APK rather than requiring a 1.4 GiB rootfs
     * replacement for every Android compatibility iteration.
     *
     * Plasma must use its classic non-systemd startup in this rootless session.
     * Android 16 also SIGSYS-kills the currently pinned Anland XWayland, so the
     * wrapper strips --xwayland while VESSEL_DISABLE_XWAYLAND=1. Native Wayland
     * applications continue to work; XWayland can be re-enabled once its blocked
     * syscall is identified and fixed.
     */
    private fun prepareRootlessDesktopPolicy(): Boolean = runCatching {
        val xdg = File(layout.rootfsDir, "etc/xdg")
        check(xdg.isDirectory || xdg.mkdirs()) { "Could not create /etc/xdg" }
        val startKdeRc = File(xdg, "startkderc")
        val existing = if (startKdeRc.isFile) startKdeRc.readText() else ""
        val systemdBoot = Regex("""(?m)^\s*systemdBoot\s*=.*$""")
        val next = when {
            systemdBoot.containsMatchIn(existing) ->
                existing.replace(systemdBoot, "systemdBoot=false")
            existing.contains("[General]") ->
                existing.replace("[General]", "[General]\nsystemdBoot=false")
            existing.isBlank() ->
                "[General]\nsystemdBoot=false\n"
            else ->
                existing.trimEnd() + "\n\n[General]\nsystemdBoot=false\n"
        }
        if (!startKdeRc.isFile || startKdeRc.readText() != next) {
            startKdeRc.writeText(next)
            runCatching { Os.chmod(startKdeRc.absolutePath, 0x1A4) } // 0644
        }

        val wrapper = listOf(
            "#!/bin/bash",
            "set -euo pipefail",
            ": \"\${VESSEL_DISPLAY_SOCKET:?VESSEL_DISPLAY_SOCKET is required}\"",
            "export ANLAND=1",
            "export ANLAND_SOCKET=\"\$VESSEL_DISPLAY_SOCKET\"",
            "export ANLAND_NO_DRM_DEVICE=1",
            "export EGL_PLATFORM=surfaceless",
            "args=()",
            "for arg in \"\$@\"; do",
            "  if [[ \"\${VESSEL_DISABLE_XWAYLAND:-0}\" == \"1\" && \"\$arg\" == \"--xwayland\" ]]; then",
            "    continue",
            "  fi",
            "  args+=(\"\$arg\")",
            "done",
            "exec /usr/bin/kwin_wayland \"\${args[@]}\"",
        ).joinToString("\n", postfix = "\n")
        listOf(
            "usr/local/lib/vessel/kwin-wrapper/kwin_wayland",
            "usr/local/lib/vessel/kwin-wrapper/kwin_wayland_wrapper",
        ).forEach { relative ->
            val target = File(layout.rootfsDir, relative)
            check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                "Could not create KWin wrapper directory"
            }
            if (!target.isFile || target.readText() != wrapper) {
                target.writeText(wrapper)
                Os.chmod(target.absolutePath, 0x1ED) // 0755
            }
        }

        // Plasma 6 still performs a handful of org.freedesktop.systemd1 calls
        // even in classic (systemdBoot=false) mode. Debian ships a D-Bus
        // activation file for systemd --user, which cannot work in Vessel's
        // rootless Android process tree. Remove only the activation entry so
        // those calls fail fast instead of spawning a doomed user manager.
        listOf(
            "usr/share/dbus-1/services/org.freedesktop.systemd1.service",
            // xdg-document-portal always requires a FUSE mount at
            // /run/user/$UID/doc. Android's app sandbox denies /dev/fuse, so
            // activating it can only fail and delays the session.
            "usr/share/dbus-1/services/org.freedesktop.portal.Documents.service",
        ).forEach { relative ->
            val service = File(layout.rootfsDir, relative)
            if (service.isFile) {
                val disabled = File(service.parentFile, service.name + ".vessel-disabled")
                if (disabled.exists()) disabled.delete()
                check(service.renameTo(disabled)) {
                    "Could not disable unsupported rootless D-Bus activation: /" + relative
                }
            }
        }

        // Force the KDE portal backend and explicitly disable interfaces that
        // require PipeWire/FUSE in this runtime. Normal Wayland/KDE operation
        // and Android AudioTrack remain independent of these portals.
        val portalConfig = File(
            layout.rootfsDir,
            "home/vessel/.config/xdg-desktop-portal/portals.conf",
        )
        check(portalConfig.parentFile?.isDirectory == true || portalConfig.parentFile?.mkdirs() == true) {
            "Could not create rootless portal config directory"
        }
        val portalPolicy = """
            [preferred]
            default=kde
            org.freedesktop.impl.portal.ScreenCast=none
            org.freedesktop.impl.portal.RemoteDesktop=none
            org.freedesktop.impl.portal.Lockdown=none
        """.trimIndent() + "\n"
        if (!portalConfig.isFile || portalConfig.readText() != portalPolicy) {
            portalConfig.writeText(portalPolicy)
            Os.chmod(portalConfig.absolutePath, 0x1A4) // 0644
        }

        true
    }.getOrDefault(false)

    private fun prepareDns(): Boolean {
        if (!layout.prepareHostLayout()) return false
        val resolver = File(layout.guestRunDir, "resolv.conf")
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val link = if (manager != null && network != null) {
            manager.getLinkProperties(network)
        } else {
            null
        }
        val dns = link?.dnsServers
            ?.mapNotNull { it.hostAddress?.substringBefore('%') }
            ?.distinct()
            .orEmpty()
        val servers = if (dns.isNotEmpty()) dns else listOf("8.8.8.8", "1.1.1.1")
        return runCatching {
            resolver.writeText(
                buildString {
                    servers.forEach { append("nameserver ").append(it).append('\n') }
                    append("options timeout:2 attempts:2\n")
                },
            )
            Os.chmod(resolver.absolutePath, 0x1A4) // 0644
            true
        }.getOrDefault(false)
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
        check(prepareDns()) { "Could not prepare Android network DNS for Linux" }

        return VesselProrootContract.shell(
            launcherPath = File(layout.nativeLibraryDir, "libproroot.so").absolutePath,
            runtimeLibraryDir = layout.nativeLibraryDir.absolutePath,
            rootfsPath = layout.rootfsDir.absolutePath,
            prorootTmpPath = layout.prorootTmpDir.absolutePath,
            hostWorkingDirectory = layout.baseDir.absolutePath,
            binds = layout.binds(includeSharedStorage),
            command = command,
            diagnosticsLogPath = layout.diagnosticsLog.absolutePath,
            guestEnvironment = directGpuEnvironment(),
        )
    }

    override fun terminalLaunchPlan(
        includeSharedStorage: Boolean,
    ): VesselProrootLaunchPlan {
        check(terminalReadiness(verifyIntegrity = true).ready) {
            terminalReadiness(verifyIntegrity = false).reason
        }
        check(prepareDns()) { "Could not prepare Android network DNS for Linux" }
        return VesselProrootContract.build(
            launcherPath = File(layout.nativeLibraryDir, "libproroot.so").absolutePath,
            runtimeLibraryDir = layout.nativeLibraryDir.absolutePath,
            rootfsPath = layout.rootfsDir.absolutePath,
            prorootTmpPath = layout.prorootTmpDir.absolutePath,
            hostWorkingDirectory = layout.baseDir.absolutePath,
            binds = layout.binds(includeSharedStorage),
            guestArgv = listOf("/bin/bash", "-l"),
            diagnosticsLogPath = layout.diagnosticsLog.absolutePath,
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
        check(prepareDns()) { "Could not prepare Android network DNS for Linux" }

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
            diagnosticsLogPath = layout.diagnosticsLog.absolutePath,
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
            .put(
                "productionRootfs",
                File(layout.rootfsDir, "var/cache/vessel/proroot-production-v1").isFile,
            )
            .put("displayBridge", bridge)
            .put("presenter", presenter)
            .put("presentationPath", VesselProrootDisplayBridge.presentationPath())
            .put("zeroCopyPresentation", VesselProrootDisplayBridge.usesZeroCopyPresentation())
            .put("effectiveRefreshHz", VesselProrootDisplayBridge.effectiveRefresh().toDouble())
            .put("framesPresented", VesselProrootDisplayBridge.framesPresented())
            .put("framesReleased", VesselProrootDisplayBridge.framesReleased())
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

        startupJournal.begin()
        try {
            startupJournal.mark("readiness.begin")
            val readiness = desktopReadiness(verifyIntegrity = true)
            check(readiness.ready) { readiness.reason }
            startupJournal.mark("readiness.ok", readiness.reason)

            progress("proroot_smoke", 68, "Proving proroot can enter the Linux rootfs")
            startupJournal.mark("proroot.smoke.begin")
            val smoke = VesselProrootProcessRunner.run(
                shellLaunchPlan(
                    "printf 'VESSEL_PROROOT_SMOKE_OK\\n'; " +
                        "test -r /proc/self/exe; test -w /dev/shm; " +
                        "test -e /dev/kgsl-3d0",
                    diagnostics = true,
                    includeSharedStorage = false,
                ),
                timeoutSeconds = 10,
                logFile = File(layout.diagnosticsDir, "proroot-smoke.log"),
            )
            check(smoke.exitCode == 0 && smoke.output.contains("VESSEL_PROROOT_SMOKE_OK")) {
                "Proroot smoke test failed rc=" + smoke.exitCode + ": " +
                    smoke.output.takeLast(5000)
            }
            startupJournal.mark("proroot.smoke.ok")

            // Android can expose /proc/self/exe as readable while still denying
            // readlink(2). Prove proroot's documented PROROOT_GUEST_EXE
            // emulation before starting D-Bus, KWin or any desktop process.
            progress("proroot_proc_self", 70, "Validating Linux /proc/self/exe semantics")
            startupJournal.mark("proc.self-exe.begin")
            val procSelfExe = VesselProrootProcessRunner.run(
                desktopLaunchPlan(
                    listOf("/usr/bin/readlink", "/proc/self/exe"),
                    includeSharedStorage = false,
                ),
                timeoutSeconds = 8,
                logFile = File(layout.diagnosticsDir, "proc-self-exe.log"),
            )
            val procSelfExePath = procSelfExe.output
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("/") }
                .orEmpty()
            check(
                procSelfExe.exitCode == 0 &&
                    procSelfExePath == "/usr/bin/readlink"
            ) {
                "proroot /proc/self/exe emulation failed rc=" +
                    procSelfExe.exitCode + " path=" +
                    procSelfExePath.ifBlank { "(none)" } + ": " +
                    procSelfExe.output.takeLast(4000)
            }
            startupJournal.mark("proc.self-exe.ok", procSelfExePath)

            progress("proroot_compat", 72, "Preparing generic Linux ABI/session compatibility")
            startupJournal.mark("audio.prepare.begin")
            prepareAudioBridge()
            startupJournal.mark("audio.prepare.ok")
            procCompat.start()
            startupJournal.mark("proc.compat.ok")

            progress("proroot_dbus", 76, "Starting Linux system D-Bus")
            startupJournal.mark("dbus.begin")
            startSystemBus()
            startupJournal.mark("dbus.ok")

            progress("proroot_probe", 80, "Checking procfs, shared memory, IPC and session D-Bus")
            startupJournal.mark("compat.probe.begin")

            val kernelProbe = VesselProrootProcessRunner.run(
                desktopLaunchPlan(
                    VesselProrootCompatibilityProbe.kernelArgv(),
                    includeSharedStorage = false,
                ),
                timeoutSeconds = 20,
                logFile = File(layout.diagnosticsDir, "compat-kernel-probe.log"),
            )
            check(
                kernelProbe.exitCode == 0 &&
                    kernelProbe.output.contains("VESSEL_COMPAT_OK=proc-self-exe:") &&
                    kernelProbe.output.contains("VESSEL_COMPAT_OK=kernel-ipc")
            ) {
                "Linux kernel compatibility probe failed rc=" + kernelProbe.exitCode + ": " +
                    kernelProbe.output.takeLast(6000)
            }
            startupJournal.mark("compat.kernel.ok")

            val sessionBusProbe = VesselProrootProcessRunner.run(
                desktopLaunchPlan(
                    VesselProrootCompatibilityProbe.sessionBusArgv(),
                    includeSharedStorage = false,
                ),
                timeoutSeconds = 12,
                logFile = File(layout.diagnosticsDir, "compat-session-dbus.log"),
            )
            check(
                sessionBusProbe.exitCode == 0 &&
                    sessionBusProbe.output.contains("VESSEL_COMPAT_OK=dbus-session")
            ) {
                "Linux session D-Bus probe failed rc=" + sessionBusProbe.exitCode + ": " +
                    sessionBusProbe.output.takeLast(6000)
            }
            startupJournal.mark("compat.probe.ok")

            // The on-device log showed org.kde.plasma.core missing. Debian
            // provides it in plasma-desktoptheme; repair stale production
            // rootfs installs in-place instead of forcing a 1.4 GiB redownload.
            progress("proroot_plasma_qml", 82, "Validating Plasma 6 QML runtime")
            ensurePlasmaQmlCore()

            val uid = Process.myUid()
            val socket = layout.desktopHostSocket(uid)
            VesselWaylandPresenter.resetPresentationLatch()
            startupJournal.mark("display.bridge.begin")
            check(
                VesselProrootDisplayBridge.start(
                    context = appContext,
                    socket = socket,
                    width = displayWidth,
                    height = displayHeight,
                    refresh = displayRefresh,
                ),
            ) { "Could not start Vessel native compositor display bridge" }
            startupJournal.mark(
                "display.bridge.ok",
                VesselProrootDisplayBridge.status(),
            )

            progress("proroot_plasma", 86, "Launching optimized normal-user Plasma Wayland session")
            startupJournal.mark("plasma.spawn.begin")
            val plan = desktopLaunchPlan(listOf(VesselProrootDesktopProfile.STARTER))
            val process = VesselProrootProcessHost.spawn(plan, layout.desktopLog)
            synchronized(lifecycleLock) {
                desktopProcess = process
                running = true
                startedAt = SystemClock.elapsedRealtime()
            }
            watchDesktop(process)
            startupJournal.mark("plasma.spawn.ok", "pid=" + process.pid)

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
                if (bridge.startsWith("presenting-proroot-")) break
                Thread.sleep(50)
            }
            check(bridge.startsWith("presenting-proroot-")) {
                "KWin did not reach Vessel's native presentation path: " + bridge
            }

            desktopReady = true
            startupJournal.mark("desktop.ready", bridge)
            progress(
                "proroot_ready",
                100,
                "Plasma Wayland is using direct KGSL + " +
                    VesselProrootDisplayBridge.presentationPath(),
            )
            status()
        } catch (error: Throwable) {
            startupJournal.failure(error)
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

    private fun ensurePlasmaQmlCore() {
        val qmlCore = File(
            layout.rootfsDir,
            "usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/plasma/core/qmldir",
        )
        if (qmlCore.isFile && qmlCore.length() > 0L) return

        startupJournal.mark("plasma.qml.repair.begin")
        val repair = VesselProrootProcessRunner.run(
            shellLaunchPlan(
                "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Use-Pty=0 -o APT::Color=0 install -y --reinstall plasma-desktoptheme",
                diagnostics = true,
                includeSharedStorage = false,
            ),
            timeoutSeconds = 90,
            logFile = File(layout.diagnosticsDir, "plasma-qml-repair.log"),
        )
        check(
            repair.exitCode == 0 &&
                qmlCore.isFile &&
                qmlCore.length() > 0L
        ) {
            "Plasma QML core is missing and automatic repair failed rc=" +
                repair.exitCode + ": " + repair.output.takeLast(6000)
        }
        startupJournal.mark("plasma.qml.repair.ok")
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

        val config = VesselRootlessSystemBus.writeConfig(layout.guestRunDir)
        runCatching { Os.chmod(config.absolutePath, 0x1A4) } // 0644
        startupJournal.mark("dbus.config.ok", config.absolutePath)

        val process = VesselProrootProcessHost.spawn(
            shellLaunchPlan(
                VesselRootlessSystemBus.launchCommand(),
                diagnostics = true,
                includeSharedStorage = false,
            ),
            File(layout.diagnosticsDir, "system-dbus.log"),
        )
        systemBusProcess = process

        val socket = File(layout.guestRunDir, "dbus/system_bus_socket")
        val deadline = SystemClock.elapsedRealtime() + 5000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!process.isAlive()) {
                error(
                    "Rootless system D-Bus exited: " +
                        process.outputTail().takeLast(4000),
                )
            }
            if (socket.exists()) break
            Thread.sleep(25)
        }
        check(socket.exists()) {
            "Rootless system D-Bus socket did not become ready"
        }

        val probe = VesselProrootProcessRunner.run(
            shellLaunchPlan(
                VesselRootlessSystemBus.probeCommand(),
                diagnostics = true,
                includeSharedStorage = false,
            ),
            timeoutSeconds = 8,
            logFile = File(layout.diagnosticsDir, "system-dbus-probe.log"),
        )
        check(
            probe.exitCode == 0 &&
                probe.output.contains("VESSEL_SYSTEM_DBUS_OK") &&
                process.isAlive()
        ) {
            "Rootless system D-Bus probe failed rc=" + probe.exitCode + ": " +
                probe.output.takeLast(4000) + "\n[daemon]\n" +
                process.outputTail().takeLast(3000)
        }
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
        File(layout.guestRunDir, "vessel-system-bus.conf").delete()
    }

    private fun baseState(ok: Boolean): JSONObject {
        val presenter = VesselWaylandPresenter.status()
        val bridge = VesselProrootDisplayBridge.status()
        // For the production proroot backend the native bridge is authoritative.
        // A stale retained presenter or a one-time successful startup must never
        // keep the UI on VISIBLE after KWin disconnects.
        val frameReady = bridge.startsWith("presenting-proroot-")
        return JSONObject()
            .put("ok", ok)
            .put("backend", "PROROOT")
            .put("protocolVersion", 5)
            .put("runtimeRevision", revision)
            .put("displayTransport", displayTransport)
            .put("rendererMode", "freedreno-turnip-kgsl-direct")
            .put("translationLayer", "none")
            .put("gpuOnly", true)
            .put("softwareFallback", false)
            .put("running", running)
            .put("guestReady", hostAssetsReady() && layout.rootfsReady())
            .put("desktopReady", desktopReady && frameReady)
            .put("frameContentValidated", frameReady)
            .put("inputConnected", bridge.startsWith("presenting-proroot-"))
            .put("machineDir", machineDir.absolutePath)
            .put("guestMemoryMb", 0)
            .put("processorCount", processorCount)
            .put("displayWidth", displayWidth)
            .put("displayHeight", displayHeight)
            .put("displayDpi", displayDpi)
            .put("displayRefresh", displayRefresh.toDouble())
            .put("effectiveDisplayRefresh", VesselProrootDisplayBridge.effectiveRefresh().toDouble())
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
