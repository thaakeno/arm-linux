package com.example.dreamlinux

import org.json.JSONObject
import java.io.File

enum class VesselRuntimeKind {
    UML,
    PROROOT,
}

/**
 * Stable host-facing runtime seam.
 *
 * Phase 1 deliberately keeps the active implementation on UML. Future runtime
 * work plugs in below this interface instead of teaching the Android UI about
 * UML, proroot, PTYs, or individual Linux applications.
 */
interface VesselRuntimeBackend {
    val kind: VesselRuntimeKind
    val id: String
    val displayName: String
    val revision: String
    val displayTransport: String
    val machineDir: File
    val guestMemoryMb: Int
    val graphicsSummary: String
    val internetSummary: String

    fun hasStorageAccess(): Boolean
    fun hostAssetsReady(): Boolean

    fun configureDisplay(width: Int, height: Int, dpi: Int, refresh: Float)
    suspend fun resizeDesktop(width: Int, height: Int, dpi: Int, refresh: Float): Boolean

    fun input(type: String, values: Map<String, Any>)

    suspend fun status(): JSONObject
    suspend fun startDesktop(): JSONObject
    suspend fun guest(command: String, timeoutSeconds: Int = 45): JSONObject
    suspend fun stop(): JSONObject
}
