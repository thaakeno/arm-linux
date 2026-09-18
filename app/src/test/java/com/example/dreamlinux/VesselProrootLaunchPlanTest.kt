package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselProrootLaunchPlanTest {
    @Test
    fun buildsUpstreamCompatibleArgvWithoutPerAppWrapper() {
        val plan = VesselProrootContract.build(
            launcherPath = "/native/libproroot.so",
            runtimeLibraryDir = "/native",
            rootfsPath = "/data/rootfs",
            prorootTmpPath = "/data/runtime/proroot-tmp",
            hostWorkingDirectory = "/data",
            binds = listOf(
                VesselProrootBind("/dev", "/dev"),
                VesselProrootBind("/data/tmp", "/tmp"),
                VesselProrootBind("/data/shm", "/dev/shm"),
            ),
            guestArgv = listOf("/bin/bash", "-l"),
        )

        assertEquals("/native/libproroot.so", plan.argv.first())
        assertTrue(plan.argv.containsAll(listOf("-r", "/data/rootfs", "-0", "--link2symlink", "-w", "/root")))
        assertTrue(plan.argv.contains("/dev:/dev"))
        assertTrue(plan.argv.contains("/data/tmp:/tmp"))
        assertTrue(plan.argv.contains("/data/shm:/dev/shm"))
        assertEquals(listOf("/bin/bash", "-l"), plan.argv.takeLast(2))
    }

    @Test
    fun keepsAndroidLinkerStateOutOfGuestEnvironment() {
        val plan = VesselProrootContract.shell(
            launcherPath = "/native/libproroot.so",
            runtimeLibraryDir = "/native",
            rootfsPath = "/data/rootfs",
            prorootTmpPath = "/data/runtime/proroot-tmp",
            hostWorkingDirectory = "/data",
            binds = emptyList(),
            command = "true",
        )
        val env = mutableMapOf(
            "LD_PRELOAD" to "/android/host-hook.so",
            "LD_LIBRARY_PATH" to "/android/libs",
            "ANDROID_ROOT" to "/system",
        )

        plan.applyEnvironment(env)

        assertFalse(env.containsKey("LD_PRELOAD"))
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertEquals("/system", env["ANDROID_ROOT"])
        assertEquals("/native/libproroot-runtime.so", env["PROROOT_LIB_PATH"])
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals("/run/user/0", env["XDG_RUNTIME_DIR"])
    }


    @Test
    fun mergesSharedGpuEnvironmentWithoutTouchingRuntimeKeys() {
        val plan = VesselProrootContract.build(
            launcherPath = "/native/libproroot.so",
            runtimeLibraryDir = "/native",
            rootfsPath = "/data/rootfs",
            prorootTmpPath = "/data/runtime/proroot-tmp",
            hostWorkingDirectory = "/data",
            binds = emptyList(),
            guestArgv = listOf("/bin/bash", "-l"),
            guestEnvironment = mapOf(
                "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
                "TURNIP_KMD" to "kgsl",
            ),
        )

        assertEquals("kgsl", plan.environment["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("kgsl", plan.environment["TURNIP_KMD"])
        assertEquals("/native/libproroot-runtime.so", plan.environment["PROROOT_LIB_PATH"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGuestLdLibraryPathOverrides() {
        VesselProrootContract.build(
            launcherPath = "/native/libproroot.so",
            runtimeLibraryDir = "/native",
            rootfsPath = "/data/rootfs",
            prorootTmpPath = "/data/runtime/proroot-tmp",
            hostWorkingDirectory = "/data",
            binds = emptyList(),
            guestArgv = listOf("/bin/true"),
            guestEnvironment = mapOf("LD_LIBRARY_PATH" to "/tmp/mesa"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRelativeGuestBindPaths() {
        VesselProrootBind("/dev", "dev")
    }
}
