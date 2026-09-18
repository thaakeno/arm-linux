package com.example.dreamlinux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Phase-1 proroot backend scaffold.
 *
 * It owns the filesystem/runtime contract but is intentionally not selectable.
 * Terminal/PTTY lifetime is Phase 2 and direct desktop/GPU activation is Phase 3.
 * Keeping this gate explicit prevents a partially implemented backend from
 * becoming the user's Linux machine by accident.
 */
class VesselProrootRuntimeBackend(
    context: Context,
    private val progress: (String, Int, String) -> Unit,
) : VesselRuntimeBackend, VesselPtyRuntimeProvider, VesselDirectGpuRuntimeProvider {
    companion object {
        const val REVISION = "proroot-direct-gpu-v3"
        const val DISPLAY_TRANSPORT = "proroot-direct-kgsl-v1+vessel-native-surface-pending"
    }

    private val layout = VesselProrootLayout(context)

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

    // No fixed guest-RAM allocation exists for a shared-kernel runtime.
    override val guestMemoryMb = 0
    override val processorCount: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    override val graphicsSummary =
        "Freedreno OpenGL/ES + Turnip Vulkan → direct KGSL · no VirGL/Zink default"
    override val internetSummary = "Android shared-kernel networking · activation pending"

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

    private fun directGpuEnvironment(): Map<String, String> {
        val readiness = directGpuReadiness()
        check(readiness.ready) { readiness.reason }
        return VesselDirectGpuProfile.environment(readiness.icdGuestPath)
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

    /**
     * Explicit integrity check for the later packaging phase. This is not called
     * from UI polling because hashing native libraries every second wastes power.
     */
    fun officialRuntimeHashesMatch(): Boolean =
        VesselProrootContract.EXPECTED_SHA256.all { (name, expected) ->
            val file = File(layout.nativeLibraryDir, name)
            file.isFile && sha256(file).equals(expected, ignoreCase = true)
        }

    fun shellLaunchPlan(
        command: String,
        diagnostics: Boolean = false,
        includeSharedStorage: Boolean = true,
    ): VesselProrootLaunchPlan {
        check(layout.prepareHostLayout()) { "Could not prepare Vessel proroot storage" }
        layout.prepareGuestMountPointsIfReady()
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

    /**
     * Real interactive PTYs use the exact same runtime contract as later
     * desktop processes. No terminal-only rootfs or application wrappers.
     */
    override fun terminalLaunchPlan(
        includeSharedStorage: Boolean,
    ): VesselProrootLaunchPlan {
        check(layout.prepareHostLayout()) { "Could not prepare Vessel proroot storage" }
        check(hostAssetsReady()) { "Official proroot runtime libraries are not packaged" }
        check(layout.rootfsReady()) { "Vessel proroot directory rootfs is not installed" }
        check(officialRuntimeHashesMatch()) { "proroot runtime integrity check failed" }
        check(layout.prepareGuestMountPointsIfReady()) { "Could not prepare Linux runtime mount points" }

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

    override fun configureDisplay(width: Int, height: Int, dpi: Int, refresh: Float) {
        displayWidth = width.coerceIn(640, 3840)
        displayHeight = height.coerceIn(480, 2160)
        displayDpi = dpi.coerceIn(72, 480)
        displayRefresh = refresh.coerceIn(30f, 240f)
    }

    override suspend fun resizeDesktop(width: Int, height: Int, dpi: Int, refresh: Float): Boolean {
        configureDisplay(width, height, dpi, refresh)
        return true
    }

    override fun input(type: String, values: Map<String, Any>) {
        error("proroot desktop input is gated until the direct GPU phase")
    }

    override suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        val storage = layout.prepareHostLayout()
        baseState(storage)
            .put("rootfsReady", layout.rootfsReady())
            .put("runtimeAssetsReady", hostAssetsReady())
            .put("prorootVersion", VesselProrootContract.VERSION)
            .put("rootfsDir", layout.rootfsDir.absolutePath)
            .put("prorootTmpDir", layout.prorootTmpDir.absolutePath)
            .also { state ->
                val gpu = directGpuReadiness()
                state.put("directGpuReady", gpu.ready)
                state.put("directGpuReason", gpu.reason)
                state.put("gpuDevice", VesselDirectGpuProfile.DEVICE)
                state.put("mesaVersion", VesselDirectGpuProfile.MESA_VERSION)
                state.put("vulkanIcd", gpu.icdGuestPath)
            }
    }

    override suspend fun startDesktop(): JSONObject = withContext(Dispatchers.IO) {
        val gpu = directGpuReadiness()
        progress(
            "proroot_direct_gpu",
            if (gpu.ready) 100 else 0,
            if (gpu.ready) "direct KGSL GPU runtime ready; desktop session waits for Phase 4"
            else gpu.reason,
        )
        error("proroot desktop/session activation is intentionally gated until Phase 4")
    }

    override suspend fun guest(command: String, timeoutSeconds: Int): JSONObject =
        withContext(Dispatchers.IO) {
            error("proroot command execution is intentionally gated until PTY/process lifetime is implemented")
        }

    override suspend fun stop(): JSONObject = status()

    private fun baseState(ok: Boolean): JSONObject = JSONObject()
        .put("ok", ok)
        .put("backend", "PROROOT_SCAFFOLD")
        .put("protocolVersion", 1)
        .put("runtimeRevision", revision)
        .put("displayTransport", displayTransport)
        .put("rendererMode", "freedreno-turnip-kgsl-direct")
        .put("translationLayer", "none")
        .put("gpuOnly", true)
        .put("softwareFallback", false)
        .put("running", false)
        .put("guestReady", false)
        .put("desktopReady", false)
        .put("frameContentValidated", false)
        .put("inputConnected", false)
        .put("machineDir", machineDir.absolutePath)
        .put("guestMemoryMb", 0)
        .put("displayWidth", displayWidth)
        .put("displayHeight", displayHeight)
        .put("displayDpi", displayDpi)
        .put("displayRefresh", displayRefresh.toDouble())
        .put("uptimeMs", 0L)
        .put("lastError", "")

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
