package com.example.dreamlinux

import android.content.Context
import android.os.Environment
import java.io.File

object VesselRuntimeFactory {
    const val ACTIVE_BACKEND_ID = "proroot"
    const val RECOVERY_BACKEND_ID = "uml"

    fun selectedBackendId(context: Context): String =
        VesselExperimentConfig.runtimeBackend(context)

    fun umlRecoveryAvailable(context: Context): Boolean {
        val privateDisk = File(context.filesDir, "vessel-machine/debian-docker.ext4")
        val legacyDisk = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LinuxPC/Vessel-Debian/debian-docker.ext4",
        )
        val minimum = 512L * 1024L * 1024L
        return (privateDisk.isFile && privateDisk.length() > minimum) ||
            (legacyDisk.isFile && legacyDisk.length() > minimum && legacyDisk.canRead())
    }

    fun createActive(
        context: Context,
        progress: (String, Int, String) -> Unit,
    ): VesselRuntimeBackend = when (selectedBackendId(context)) {
        RECOVERY_BACKEND_ID -> VesselUmlRuntimeBackend(context, progress)
        else -> VesselProrootRuntimeBackend(context, progress)
    }

    fun createProroot(
        context: Context,
        progress: (String, Int, String) -> Unit = { _, _, _ -> },
    ): VesselProrootRuntimeBackend = VesselProrootRuntimeBackend(context, progress)

    fun createUmlRecovery(
        context: Context,
        progress: (String, Int, String) -> Unit = { _, _, _ -> },
    ): VesselUmlRuntimeBackend = VesselUmlRuntimeBackend(context, progress)
}
