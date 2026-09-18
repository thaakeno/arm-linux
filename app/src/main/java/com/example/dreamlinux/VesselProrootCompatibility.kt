package com.example.dreamlinux

import android.os.Process
import java.io.File

data class VesselDesktopReadiness(
    val ready: Boolean,
    val reason: String,
)

object VesselProrootDesktopProfile {
    const val RELEASE = "5.13.3"
    const val KWIN_ARCHIVE =
        "kwin_anland-5.13-debian-4_6.3.6-95.zip"
    const val KWIN_SHA256 =
        "56ce1da27b640c977bad5ca0b7b13196e609b5fc419703429e51805ec05e4ee4"
    const val XWAYLAND_ARCHIVE =
        "xwayland_24.1.6-91_arm64.deb"
    const val XWAYLAND_SHA256 =
        "59f9c7486d6a10ad50a13622bf1d1bbf5accd015d630e4b2b0152a80577dcc64"
    const val MARKER = "/usr/lib/vessel/desktop/session.env"
    const val STARTER = "/usr/local/libexec/vessel-start-plasma"
    const val PROBE = "/usr/local/libexec/vessel-compat-probe"

    fun identity(androidUid: Int = Process.myUid()): VesselProrootIdentity =
        VesselProrootIdentity(
            fakeRoot = false,
            user = "vessel",
            logName = "vessel",
            home = "/home/vessel",
            workingDirectory = "/home/vessel",
            runtimeDirectory = "/run/user/" + androidUid,
        )

    fun readiness(rootfs: File): VesselDesktopReadiness {
        for (path in listOf(MARKER, STARTER, PROBE)) {
            val file = guestFile(rootfs, path)
            if (!file.isFile || file.length() <= 0L) {
                return VesselDesktopReadiness(false, "Desktop runtime file is missing: " + path)
            }
        }

        val required = listOf(
            "/usr/bin/kwin_wayland",
            "/usr/bin/startplasma-wayland",
            "/usr/bin/Xwayland",
            "/usr/bin/dbus-daemon",
            "/usr/bin/dbus-run-session",
            "/usr/bin/python3",
            "/usr/bin/pulseaudio",
        )
        for (path in required) {
            val file = guestFile(rootfs, path)
            if (!file.isFile || file.length() <= 0L) {
                return VesselDesktopReadiness(false, "Desktop dependency is missing: " + path)
            }
        }

        val marker = runCatching { guestFile(rootfs, MARKER).readText() }.getOrDefault("")
        if (!marker.lineSequence().any { it.trim() == "release=" + RELEASE } ||
            !marker.lineSequence().any { it.trim() == "kwin_sha256=" + KWIN_SHA256 } ||
            !marker.lineSequence().any { it.trim() == "xwayland_sha256=" + XWAYLAND_SHA256 }
        ) {
            return VesselDesktopReadiness(false, "Desktop runtime marker does not match Vessel Phase 4")
        }

        return VesselDesktopReadiness(true, "Plasma Wayland · direct KGSL · Vessel native display")
    }

    fun sessionEnvironment(androidUid: Int, guestSocketPath: String): Map<String, String> =
        linkedMapOf(
            "XDG_SESSION_TYPE" to "wayland",
            "XDG_CURRENT_DESKTOP" to "KDE",
            "XDG_SESSION_DESKTOP" to "KDE",
            "DESKTOP_SESSION" to "plasma",
            "QT_QPA_PLATFORM" to "wayland",
            "GDK_BACKEND" to "wayland,x11",
            "VESSEL_DISPLAY_SOCKET" to guestSocketPath,
            "ANLAND_SOCKET" to guestSocketPath,
            "ANLAND" to "1",
            "ANLAND_NO_DRM_DEVICE" to "1",
            "VESSEL_AUDIO_HOST" to "127.0.0.1",
        )

    private fun guestFile(rootfs: File, guestPath: String): File =
        File(rootfs, guestPath.removePrefix("/"))
}
