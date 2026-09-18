package com.example.dreamlinux

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Zero-behavior-change adapter around the proven UML runtime.
 *
 * VesselRuntimeController intentionally remains untouched in Phase 1. This
 * adapter is the compatibility boundary that lets later phases add a second
 * runtime without destabilizing the current machine.
 */
class VesselUmlRuntimeBackend(
    private val context: Context,
    progress: (String, Int, String) -> Unit,
) : VesselRuntimeBackend {
    private val controller = VesselRuntimeController(context, progress)

    override val kind = VesselRuntimeKind.UML
    override val id = "uml"
    override val displayName = "UML · full Linux kernel"
    override val revision: String get() = VesselRuntimeController.REVISION
    override val displayTransport: String get() = VesselRuntimeController.DISPLAY_TRANSPORT
    override val machineDir: File get() = controller.machineDir
    override val guestMemoryMb: Int get() = controller.guestMemoryMb
    override val processorCount: Int get() = controller.selectedVcpus
    override val graphicsSummary: String
        get() = if (VesselExperimentConfig.desktopBackend(context) == "wayland") {
            "KDE Plasma/Wayland → Mesa VirGL → virglrenderer → " +
                "${VesselExperimentConfig.hostGl(context).uppercase()} EGL → async AHB → SurfaceFlinger"
        } else {
            "KDE Plasma/X11 fallback → Mesa VirGL → virglrenderer → " +
                "${VesselExperimentConfig.hostGl(context).uppercase()} EGL → async AHB"
        }
    override val internetSummary = "UML vector net · passt"

    override fun hasStorageAccess(): Boolean = controller.hasStorageAccess()
    override fun hostAssetsReady(): Boolean = controller.hostAssetsReady()

    override fun configureDisplay(width: Int, height: Int, dpi: Int, refresh: Float) =
        controller.configureDisplay(width, height, dpi, refresh)

    override suspend fun resizeDesktop(width: Int, height: Int, dpi: Int, refresh: Float): Boolean =
        controller.resizeDesktop(width, height, dpi, refresh)

    override fun input(type: String, values: Map<String, Any>) = controller.input(type, values)

    override suspend fun status(): JSONObject = controller.status()
    override suspend fun startDesktop(): JSONObject = controller.startDesktop()
    override suspend fun guest(command: String, timeoutSeconds: Int): JSONObject =
        controller.guest(command, timeoutSeconds)

    override suspend fun stop(): JSONObject = controller.stop()
}
