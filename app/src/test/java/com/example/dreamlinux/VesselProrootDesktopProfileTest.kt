package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselProrootDesktopProfileTest {
    @Test
    fun normalDesktopEnvironmentIsWaylandAndDoesNotPoisonApplicationEgl() {
        val env = VesselProrootDesktopProfile.sessionEnvironment(
            androidUid = 10234,
            guestSocketPath = "/run/user/10234/vessel/display.sock",
        )

        assertEquals("wayland", env["XDG_SESSION_TYPE"])
        assertEquals("wayland", env["QT_QPA_PLATFORM"])
        assertEquals("/run/user/10234/vessel/display.sock", env["ANLAND_SOCKET"])
        assertEquals("1", env["ANLAND_NO_DRM_DEVICE"])
        assertEquals("127.0.0.1", env["VESSEL_AUDIO_HOST"])
        assertFalse(env.containsKey("EGL_PLATFORM"))
        assertFalse(env.keys.any { it.contains("FIREFOX", ignoreCase = true) })
        assertFalse(env.keys.any { it.contains("CHROME", ignoreCase = true) })
        assertFalse(env.values.any { it.contains("--no-sandbox", ignoreCase = true) })
    }

    @Test
    fun desktopIdentityMapsRealKernelUidInsteadOfFakeRoot() {
        val identity = VesselProrootDesktopProfile.identity(10234)
        assertFalse(identity.fakeRoot)
        assertEquals("vessel", identity.user)
        assertEquals("/home/vessel", identity.home)
        assertEquals("/run/user/10234", identity.runtimeDirectory)
    }

    @Test
    fun displaySocketPathUsesRuntimeDirectory() {
        val env = VesselProrootDesktopProfile.sessionEnvironment(
            androidUid = 10100,
            guestSocketPath = "/run/user/10100/vessel/display.sock",
        )
        assertTrue(env.getValue("VESSEL_DISPLAY_SOCKET").startsWith("/run/user/10100/"))
    }
}
