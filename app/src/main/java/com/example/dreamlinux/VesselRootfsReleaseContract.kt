package com.example.dreamlinux

/**
 * Compatibility gate for a published production rootfs.
 *
 * Release identity is checked before downloading gigabytes. Once the manifest
 * and complete archive SHA-256 are verified, runtime readiness checks actual
 * capabilities/files rather than reparsing duplicate marker metadata.
 */
object VesselRootfsReleaseContract {
    const val MANIFEST_SCHEMA = 2
    const val RUNTIME = "proroot"
    const val DEBIAN = "trixie"
    const val ARCH = "arm64"
    const val COMPRESSION = "zstd"
    const val TRANSPORT = "github-release-chunks-v1"
    val REQUIRED_CAPABILITIES = setOf(
        "rootless-proroot",
        "plasma-wayland",
        "plasma-qml-core",
        "ksvg-qml",
        "direct-kgsl",
        "native-surfacecontrol",
    )

    fun incompatibility(
        schema: Int,
        runtime: String,
        debian: String,
        arch: String,
        compression: String,
        transport: String,
        mesaVersion: String,
        desktopRelease: String,
        hardLinksFlattened: Boolean,
        capabilities: Set<String>,
    ): String? = when {
        schema != MANIFEST_SCHEMA ->
            "Unsupported rootfs manifest schema " + schema
        runtime != RUNTIME ->
            "Unexpected rootfs runtime " + runtime
        debian != DEBIAN ->
            "Unexpected rootfs distribution " + debian
        arch != ARCH ->
            "Unexpected rootfs architecture " + arch
        compression != COMPRESSION ->
            "Unexpected rootfs compression " + compression
        transport != TRANSPORT ->
            "Unexpected rootfs transport " + transport
        mesaVersion != VesselDirectGpuProfile.MESA_VERSION ->
            "Rootfs Mesa " + mesaVersion +
                " does not match APK pin " + VesselDirectGpuProfile.MESA_VERSION
        desktopRelease != VesselProrootDesktopProfile.RELEASE ->
            "Rootfs desktop " + desktopRelease +
                " does not match APK pin " + VesselProrootDesktopProfile.RELEASE
        !hardLinksFlattened ->
            "Rootfs still contains Android-incompatible hard links"
        !capabilities.containsAll(REQUIRED_CAPABILITIES) ->
            "Rootfs is missing required capabilities: " +
                (REQUIRED_CAPABILITIES - capabilities).sorted().joinToString(", ")
        else -> null
    }
}
