package com.example.dreamlinux

import android.os.Process
import java.io.File

data class VesselDesktopReadiness(
    val ready: Boolean,
    val reason: String,
)

object VesselProrootDesktopProfile {
    const val RELEASE = "vessel-direct-6.3.6-96"
    const val KWIN_ARCHIVE =
        "kwin_vessel-direct_6.3.6-96_arm64.zip"
    const val KWIN_SHA256 =
        "8cd81fc9d6d063be92dd9b874413ee2c64a6d536c4066630469455d2324e53a9"
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

    /**
     * Readiness required to reuse an already-installed persistent rootfs.
     *
     * Desktop payloads that Vessel can migrate in-place (KWin, Xwayland policy,
     * QML repair state) are intentionally NOT part of this gate. Bootstrap must
     * only replace a 1.4 GiB rootfs when the base Debian/Mesa install is actually
     * unusable, never just because the APK carries a newer compositor payload.
     */
    fun baseReadiness(rootfs: File): VesselDesktopReadiness {
        val required = listOf(
            STARTER,
            "/usr/bin/startplasma-wayland",
            "/usr/bin/dbus-daemon",
            "/usr/bin/dbus-run-session",
            "/usr/bin/dbus-send",
            "/usr/bin/python3",
            "/usr/bin/pulseaudio",
        )
        for (path in required) {
            val file = guestFile(rootfs, path)
            if (!file.isFile || file.length() <= 0L) {
                return VesselDesktopReadiness(false, "Base desktop dependency is missing: " + path)
            }
        }
        return VesselDesktopReadiness(true, "Existing Debian desktop rootfs is reusable")
    }

    fun readiness(rootfs: File): VesselDesktopReadiness {
        val base = baseReadiness(rootfs)
        if (!base.ready) return base

        // The compatibility probe is APK-owned at runtime so an old large
        // rootfs cannot pin Vessel to stale Android/procfs assumptions.
        val marker = guestFile(rootfs, MARKER)
        if (!marker.isFile || marker.length() <= 0L) {
            return VesselDesktopReadiness(false, "Desktop runtime file is missing: " + MARKER)
        }

        val directKwinMarker = guestFile(rootfs, "/usr/lib/vessel/desktop/direct-kwin-build.txt")
        if (!directKwinMarker.isFile ||
            directKwinMarker.readText().trim() != RELEASE
        ) {
            return VesselDesktopReadiness(
                false,
                "Direct KWin payload does not match APK pin " + RELEASE,
            )
        }

        for (path in listOf("/usr/bin/kwin_wayland", "/usr/bin/Xwayland")) {
            val file = guestFile(rootfs, path)
            if (!file.isFile || file.length() <= 0L) {
                return VesselDesktopReadiness(false, "Desktop dependency is missing: " + path)
            }
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
            // The Anland KWin bundle is overlaid onto Debian's multiarch Qt6 tree.
            // Make that tree explicit so effects such as Overview can resolve
            // org.kde.plasma.core from plasma-desktoptheme.
            "QML_IMPORT_PATH" to "/usr/lib/aarch64-linux-gnu/qt6/qml",
            "QML2_IMPORT_PATH" to "/usr/lib/aarch64-linux-gnu/qt6/qml",
            "GDK_BACKEND" to "wayland,x11",
            "VESSEL_DISPLAY_SOCKET" to guestSocketPath,
            "ANLAND_SOCKET" to guestSocketPath,
            "ANLAND" to "1",
            "ANLAND_NO_DRM_DEVICE" to "1",
            // Vessel ships an APK-owned Xwayland patched for Android's app
            // seccomp boundary. Keep KWin's normal --xwayland path enabled.
            "VESSEL_DISABLE_XWAYLAND" to "0",
            "VESSEL_AUDIO_HOST" to "127.0.0.1",
            "DBUS_SYSTEM_BUS_ADDRESS" to "unix:path=" + VesselRootlessSystemBus.GUEST_SOCKET,
        )

    private fun guestFile(rootfs: File, guestPath: String): File =
        File(rootfs, guestPath.removePrefix("/"))
}
