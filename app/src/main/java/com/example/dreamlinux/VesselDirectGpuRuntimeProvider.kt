package com.example.dreamlinux

interface VesselDirectGpuRuntimeProvider {
    fun directGpuReadiness(): VesselGpuReadiness
}
