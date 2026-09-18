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
) : VesselRuntimeBackend, VesselPtyRuntimeProvider {
    companion object {
        const val REVISION = "proroot-terminal-foundation-v2"
        const val DISPLAY_TRANSPORT = "proroot-native-surface-pending"
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
        "proroot foundation · direct Freedreno/Turnip + Vessel Surface activation pending"
    override val internetSummary = "Android shared-kernel networking · activation pending"

    override fun hasStorageAccess(): Boolean = layout.prepareHostLayout()

    override fun hostAssetsReady(): Boolean =
        layout.runtimeLibraries().all { it.isFile && it.length() > 0L }

    fun rootfsReady(): Boolean = layout.rootfsReady()

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
            else ->
                VesselTerminalRuntimeReadiness(true, "Native PTY · proroot · bash")
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
    }

    override suspend fun startDesktop(): JSONObject = withContext(Dispatchers.IO) {
        progress("proroot_foundation", 0, "proroot backend is staged but not activated")
        error("proroot desktop activation is intentionally gated until the direct GPU phase")
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
        .put("rendererMode", "direct-gpu-pending")
        .put("translationLayer", "none-planned")
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
