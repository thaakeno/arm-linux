package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselProrootProcessHostTest {
    @Test
    fun nonInteractiveLauncherUsesAndroidSetsidAndPreservesProrootArgv() {
        val plan = VesselProrootLaunchPlan(
            argv = listOf(
                "/data/app/example/lib/arm64/libproroot.so",
                "-r",
                "/data/user/0/example/files/rootfs",
                "/bin/true",
            ),
            environment = mapOf("PROROOT_TMP_DIR" to "/data/user/0/example/cache/proroot"),
            hostWorkingDirectory = "/data/user/0/example/files",
        )

        val command = VesselProrootProcessHost.supervisorCommand(plan)

        assertEquals("/system/bin/setsid", command[0])
        assertEquals("/system/bin/sh", command[1])
        assertEquals("-c", command[2])
        assertTrue(command[3].contains("VESSEL_START"))
        assertTrue(command[3].contains("exec \"\$@\""))
        assertEquals("vessel-proroot-supervisor", command[4])
        assertEquals(plan.argv, command.drop(5))
    }

    @Test
    fun androidProcessPidParsingIsStrict() {
        assertEquals(
            12345,
            VesselProrootProcessHost.androidPid(
                "java.lang.ProcessImpl",
                "Process[pid=12345, hasExited=false]",
            ),
        )
        assertEquals(
            54321,
            VesselProrootProcessHost.androidPid(
                "java.lang.ProcessManager\$ProcessImpl",
                "Process[pid=54321, hasExited=false]",
            ),
        )
        assertEquals(
            -1,
            VesselProrootProcessHost.androidPid(
                "example.UntrustedProcess",
                "Process[pid=12345, hasExited=false]",
            ),
        )
        assertEquals(
            -1,
            VesselProrootProcessHost.androidPid(
                "java.lang.ProcessImpl",
                "Process[pid=1, hasExited=false]",
            ),
        )
    }
}
