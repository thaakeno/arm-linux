package com.example.dreamlinux

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselDirectGpuProfileTest {
    @Test
    fun productionEnvironmentUsesDirectKgslWithoutZinkOrLoaderOverlay() {
        val env = VesselDirectGpuProfile.environment(
            "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        )

        assertEquals("kgsl", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("freedreno", env["GALLIUM_DRIVER"])
        assertEquals("1", env["FD_FORCE_KGSL"])
        assertEquals("kgsl", env["TURNIP_KMD"])
        assertEquals("1", env["XWAYLAND_FORCE_KGSL_SURFACELESS"])
        assertEquals(
            "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
            env["VK_DRIVER_FILES"],
        )
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("EGL_PLATFORM"))
        assertFalse(env.values.any { it.equals("zink", ignoreCase = true) })
    }

    @Test
    fun rootfsReadinessIgnoresTemporaryHostKgslState() {
        val root = Files.createTempDirectory("vessel-gpu-rootfs-test").toFile()
        fun file(path: String, value: String = "x") {
            val target = File(root, path.removePrefix("/"))
            target.parentFile?.mkdirs()
            target.writeText(value)
        }

        file(VesselDirectGpuProfile.MARKER, "verified-rootfs-metadata\n")
        file(VesselDirectGpuProfile.KGSL_DRI)
        file(VesselDirectGpuProfile.TURNIP_LIBRARY)
        file("/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json", "{}")

        assertTrue(VesselDirectGpuProfile.rootfsReadiness(root).ready)
        assertFalse(
            VesselDirectGpuProfile.readiness(
                rootfs = root,
                deviceExists = false,
                deviceReadable = false,
                deviceWritable = false,
            ).ready,
        )
    }

    @Test
    fun readinessUsesVerifiedReleaseCapabilitiesNotDuplicatedMarkerIdentity() {
        val root = Files.createTempDirectory("vessel-gpu-test").toFile()
        fun file(path: String, value: String = "x") {
            val target = File(root, path.removePrefix("/"))
            target.parentFile?.mkdirs()
            target.writeText(value)
        }

        // Build identity is validated by the downloaded rootfs manifest + full
        // archive SHA before activation. Runtime readiness checks capabilities.
        file(VesselDirectGpuProfile.MARKER, "verified-rootfs-metadata\n")
        file(VesselDirectGpuProfile.KGSL_DRI)
        file(VesselDirectGpuProfile.TURNIP_LIBRARY)
        file("/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json", "{}")

        val ready = VesselDirectGpuProfile.readiness(
            rootfs = root,
            deviceExists = true,
            deviceReadable = true,
            deviceWritable = true,
        )
        assertTrue(ready.ready)

        File(root, VesselDirectGpuProfile.KGSL_DRI.removePrefix("/")).delete()
        val missing = VesselDirectGpuProfile.readiness(root, true, true, true)
        assertFalse(missing.ready)
        assertTrue(missing.reason.contains("kgsl_dri.so"))
    }
}
