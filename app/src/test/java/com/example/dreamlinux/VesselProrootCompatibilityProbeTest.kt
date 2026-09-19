package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselProrootCompatibilityProbeTest {
    @Test
    fun procSelfExeProbeUsesDocumentedReadlinkContractNotAndroidAccessCheck() {
        val argv = VesselProrootCompatibilityProbe.kernelArgv()
        assertEquals("/usr/bin/python3", argv.first())
        assertEquals("-c", argv[1])

        val script = argv[2]
        assertTrue(script.contains("""os.readlink("/proc/self/exe")"""))
        assertTrue(script.contains("VESSEL_COMPAT_OK=proc-self-exe:"))
        assertFalse(script.contains("""os.access("/proc/self/exe""""))
        assertFalse(script.contains("""-r /proc/self/exe"""))
    }

    @Test
    fun kernelProbeCoversGenericSharedKernelPrimitives() {
        val script = VesselProrootCompatibilityProbe.kernelPython
        assertTrue(script.contains("os.memfd_create"))
        assertTrue(script.contains("socket.SCM_RIGHTS"))
        assertTrue(script.contains("socket.SCM_CREDENTIALS"))
        assertTrue(script.contains("socket.SO_PEERCRED"))
        assertTrue(script.contains("subprocess.run"))
        assertTrue(script.contains("VESSEL_COMPAT_OK=kernel-ipc"))
    }

    @Test
    fun sessionProbeStartsRealSessionBusAndOwnsAName() {
        val argv = VesselProrootCompatibilityProbe.sessionBusArgv()
        assertEquals("/usr/bin/dbus-run-session", argv.first())
        val shell = argv.last()
        assertTrue(shell.contains("dbus-send --session --print-reply"))
        assertTrue(shell.contains("org.freedesktop.DBus.RequestName"))
        assertTrue(shell.contains("VESSEL_COMPAT_OK=dbus-session"))
    }
}
