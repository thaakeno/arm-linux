package com.example.dreamlinux

data class VesselTerminalRuntimeReadiness(
    val ready: Boolean,
    val reason: String,
)

/**
 * Capability interface for runtimes that can launch a host-owned PTY directly.
 * UML intentionally does not implement this; proroot does because its Linux
 * processes share the Android kernel and can attach straight to /dev/ptmx.
 */
interface VesselPtyRuntimeProvider {
    fun terminalReadiness(verifyIntegrity: Boolean = false): VesselTerminalRuntimeReadiness
    fun terminalLaunchPlan(includeSharedStorage: Boolean = true): VesselProrootLaunchPlan
}
