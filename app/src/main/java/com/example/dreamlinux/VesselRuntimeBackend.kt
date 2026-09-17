package com.example.dreamlinux

import org.json.JSONObject
import java.io.File

/**
 * One Linux execution backend behind the Vessel UI. UML stays the portable/rootless
 * backend; Gunyah is an opt-in hardware-virtualized backend for supported Qualcomm phones.
 */
interface VesselRuntimeBackend {
    val backendId: String
    val backendLabel: String
    val runtimeRevision: String
    val displayTransport: String
    val machineDir: File
    val guestMemoryMb: Int
    val selectedVcpus: Int

    fun hasStorageAccess(): Boolean
    fun hostAssetsReady(): Boolean
    fun configureDisplay(width: Int, height: Int, dpi: Int, refresh: Float)
    suspend fun resizeDesktop(width: Int, height: Int, dpi: Int, refresh: Float): Boolean
    suspend fun status(): JSONObject
    suspend fun startDesktop(): JSONObject
    suspend fun guest(command: String, timeoutSeconds: Int = 45): JSONObject
    suspend fun stop(): JSONObject
    fun input(type: String, values: Map<String, Any>)
}
