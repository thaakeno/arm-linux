package com.example.dreamlinux

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VesselRootfsReleaseContractTest {
    @Test
    fun acceptsCurrentProductionManifestContract() {
        assertNull(
            VesselRootfsReleaseContract.incompatibility(
                schema = 2,
                runtime = "proroot",
                debian = "trixie",
                arch = "arm64",
                compression = "zstd",
                transport = "github-release-chunks-v1",
                mesaVersion = VesselDirectGpuProfile.MESA_VERSION,
                desktopRelease = VesselProrootDesktopProfile.RELEASE,
                hardLinksFlattened = true,
                capabilities = VesselRootfsReleaseContract.REQUIRED_CAPABILITIES,
            ),
        )
    }

    @Test
    fun rejectsBeforeDownloadWhenServerAndApkDrift() {
        assertNotNull(
            VesselRootfsReleaseContract.incompatibility(
                schema = 2,
                runtime = "proroot",
                debian = "trixie",
                arch = "arm64",
                compression = "zstd",
                transport = "github-release-chunks-v1",
                mesaVersion = "wrong",
                desktopRelease = VesselProrootDesktopProfile.RELEASE,
                hardLinksFlattened = true,
                capabilities = VesselRootfsReleaseContract.REQUIRED_CAPABILITIES,
            ),
        )
        assertNotNull(
            VesselRootfsReleaseContract.incompatibility(
                schema = 2,
                runtime = "proroot",
                debian = "trixie",
                arch = "arm64",
                compression = "zstd",
                transport = "github-release-chunks-v1",
                mesaVersion = VesselDirectGpuProfile.MESA_VERSION,
                desktopRelease = VesselProrootDesktopProfile.RELEASE,
                hardLinksFlattened = false,
                capabilities = VesselRootfsReleaseContract.REQUIRED_CAPABILITIES,
            ),
        )
        assertNotNull(
            VesselRootfsReleaseContract.incompatibility(
                schema = 2,
                runtime = "proroot",
                debian = "trixie",
                arch = "arm64",
                compression = "zstd",
                transport = "github-release-chunks-v1",
                mesaVersion = VesselDirectGpuProfile.MESA_VERSION,
                desktopRelease = VesselProrootDesktopProfile.RELEASE,
                hardLinksFlattened = true,
                capabilities = VesselRootfsReleaseContract.REQUIRED_CAPABILITIES - "plasma-qml-core",
            ),
        )
    }
}
