package com.example.dreamlinux

interface VesselDesktopRuntimeProvider {
    fun desktopReadiness(verifyIntegrity: Boolean = false): VesselDesktopReadiness
}
