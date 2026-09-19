package com.example.dreamlinux

import java.io.File

data class VesselGpuReadiness(
    val ready: Boolean,
    val reason: String,
    val icdGuestPath: String = "",
)

/**
 * One direct-Adreno graphics contract for every proroot process.
 *
 * The production rootfs installs Mesa into normal Debian /usr paths. We do not
 * use a /tmp overlay or LD_LIBRARY_PATH: browsers, helper processes and desktop
 * apps inherit a normal distro layout and only the driver-selection variables.
 */
object VesselDirectGpuProfile {
    const val DEVICE = "/dev/kgsl-3d0"
    const val MESA_VERSION = "26.3.0-devel-20260824"
    const val RELEASE_TAG = "mesa-26.3.0-devel-20260824"
    const val DISTRO = "debian_trixie_arm64"
    const val ARCHIVE_NAME =
        "mesa-for-android-container_26.3.0-devel-20260824_debian_trixie_arm64.tar.gz"
    const val ARCHIVE_SHA256 =
        "c014cf66bdbff96417ee30d34f006cf51df64ae04893d599711b0b6b73b52ccf"
    const val ARCHIVE_URL =
        "https://github.com/lfdevs/mesa-for-android-container/releases/download/" +
            RELEASE_TAG + "/" + ARCHIVE_NAME

    const val LIBDIR = "/usr/lib/aarch64-linux-gnu"
    const val DRI_DIR = LIBDIR + "/dri"
    const val KGSL_DRI = DRI_DIR + "/kgsl_dri.so"
    const val TURNIP_LIBRARY = LIBDIR + "/libvulkan_freedreno.so"
    const val MARKER = "/usr/lib/vessel/direct-gpu/mesa.env"

    val ICD_CANDIDATES = listOf(
        "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        "/usr/share/vulkan/icd.d/freedreno_icd.json",
    )

    /** Rootfs-only GPU contract used to decide whether persistent Linux data is reusable. */
    fun rootfsReadiness(rootfs: File): VesselGpuReadiness {
        val marker = guestFile(rootfs, MARKER)
        if (!marker.isFile) {
            return VesselGpuReadiness(
                false,
                "Pinned Mesa " + MESA_VERSION + " is not installed in the rootfs",
            )
        }
        if (marker.length() <= 0L) {
            return VesselGpuReadiness(false, "Direct-GPU Mesa marker is empty")
        }

        for (path in listOf(KGSL_DRI, TURNIP_LIBRARY)) {
            val file = guestFile(rootfs, path)
            if (!file.isFile || file.length() <= 0L) {
                return VesselGpuReadiness(false, "Direct-GPU rootfs file is missing: " + path)
            }
        }

        val icd = ICD_CANDIDATES.firstOrNull { path ->
            val file = guestFile(rootfs, path)
            file.isFile && file.length() > 0L
        } ?: return VesselGpuReadiness(false, "Freedreno Vulkan ICD manifest is missing")

        return VesselGpuReadiness(
            ready = true,
            reason = "Freedreno OpenGL/ES + Turnip Vulkan · direct KGSL",
            icdGuestPath = icd,
        )
    }

    fun readiness(
        rootfs: File,
        deviceExists: Boolean,
        deviceReadable: Boolean,
        deviceWritable: Boolean,
    ): VesselGpuReadiness {
        if (!deviceExists) {
            return VesselGpuReadiness(false, DEVICE + " is missing on the Android host")
        }
        if (!deviceReadable || !deviceWritable) {
            return VesselGpuReadiness(false, DEVICE + " is not readable+writable by Vessel")
        }
        return rootfsReadiness(rootfs)
    }

    fun environment(icdGuestPath: String): Map<String, String> {
        require(icdGuestPath in ICD_CANDIDATES) {
            "Unexpected Freedreno ICD path: " + icdGuestPath
        }
        return linkedMapOf(
            "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
            "GALLIUM_DRIVER" to "freedreno",
            "FD_FORCE_KGSL" to "1",
            "TURNIP_KMD" to "kgsl",
            "XWAYLAND_FORCE_KGSL_SURFACELESS" to "1",
            "LIBGL_DRIVERS_PATH" to DRI_DIR,
            "VK_DRIVER_FILES" to icdGuestPath,
            "VK_ICD_FILENAMES" to icdGuestPath,
        )
    }

    private fun guestFile(rootfs: File, guestPath: String): File {
        require(guestPath.startsWith('/'))
        return File(rootfs, guestPath.removePrefix("/"))
    }
}
