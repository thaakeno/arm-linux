package com.example.dreamlinux

import android.content.Context
import android.content.SharedPreferences

object VesselExperimentConfig {
    private const val PREFS = "vessel_experiment_lab_v1"
    private const val STABILITY_MIGRATION = "stable_profile_v50"
    private const val RUNTIME_CUTOVER_MIGRATION = "runtime_cutover_v6"

    private fun prefs(context: Context): SharedPreferences {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(STABILITY_MIGRATION, false)) {
            val oldCpu = p.getInt("vcpus", 4)
            val editor = p.edit()
            // The old edge profile defaulted to 1 CPU and physical testing also
            // hit an rc=137 kill after explicitly selecting 6. Migrate either
            // extreme to the stable four-core profile once; 6 remains selectable.
            if (oldCpu == 1 || oldCpu == 6) editor.putInt("vcpus", 4)
            if (!p.contains("refresh_hz")) editor.putInt("refresh_hz", 120)
            editor.putBoolean(STABILITY_MIGRATION, true).apply()
        }
        if (!p.getBoolean(RUNTIME_CUTOVER_MIGRATION, false)) {
            // Phase 6 production cutover. Existing installs move to proroot once.
            // UML remains an explicit recovery choice and is never selected by a
            // silent runtime failure.
            p.edit()
                .putString("runtime_backend", VesselRuntimeFactory.ACTIVE_BACKEND_ID)
                .putBoolean(RUNTIME_CUTOVER_MIGRATION, true)
                .apply()
        }
        return p
    }

    fun runtimeBackend(context: Context): String =
        prefs(context).getString("runtime_backend", VesselRuntimeFactory.ACTIVE_BACKEND_ID)
            .let { if (it == VesselRuntimeFactory.RECOVERY_BACKEND_ID) VesselRuntimeFactory.RECOVERY_BACKEND_ID else VesselRuntimeFactory.ACTIVE_BACKEND_ID }

    fun setRuntimeBackend(context: Context, value: String) {
        val normalized = if (value == VesselRuntimeFactory.RECOVERY_BACKEND_ID) {
            VesselRuntimeFactory.RECOVERY_BACKEND_ID
        } else {
            VesselRuntimeFactory.ACTIVE_BACKEND_ID
        }
        prefs(context).edit().putString("runtime_backend", normalized).apply()
    }

    fun vcpus(context: Context): Int = prefs(context).getInt("vcpus", 4).let { if (it in listOf(1, 2, 4, 6)) it else 4 }
    fun setVcpus(context: Context, value: Int) { prefs(context).edit().putInt("vcpus", value.coerceIn(1, 6)).apply() }

    fun memoryMb(context: Context): Int = prefs(context).getInt("memory_mb", 0).let { if (it in listOf(0, 2048, 3072, 4096)) it else 0 }
    fun setMemoryMb(context: Context, value: Int) { prefs(context).edit().putInt("memory_mb", value).apply() }

    fun refreshHz(context: Context): Int = prefs(context).getInt("refresh_hz", 120).let { if (it in listOf(60, 90, 120)) it else 120 }
    fun setRefreshHz(context: Context, value: Int) { prefs(context).edit().putInt("refresh_hz", value).apply() }

    fun resolutionPercent(context: Context): Int = prefs(context).getInt("resolution_percent", 67).let { if (it in listOf(67, 83, 100)) it else 67 }
    fun setResolutionPercent(context: Context, value: Int) { prefs(context).edit().putInt("resolution_percent", value).apply() }

    fun flipDisplayY(context: Context): Boolean = prefs(context).getBoolean("flip_display_y", true)
    fun setFlipDisplayY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("flip_display_y", value).apply() }

    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)
    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }

    fun desktopBackend(context: Context): String = prefs(context).getString("desktop_backend", "wayland").let { if (it == "x11") "x11" else "wayland" }
    fun setDesktopBackend(context: Context, value: String) { prefs(context).edit().putString("desktop_backend", if (value == "x11") "x11" else "wayland").apply() }
    fun hostGl(context: Context): String = prefs(context).getString("host_gl", "system").let { if (it == "angle") "angle" else "system" }
    fun setHostGl(context: Context, value: String) { prefs(context).edit().putString("host_gl", if (value == "angle") "angle" else "system").apply() }
    fun firefoxDmabuf(context: Context): Boolean = prefs(context).getBoolean("firefox_dmabuf", false)
    fun setFirefoxDmabuf(context: Context, value: Boolean) { prefs(context).edit().putBoolean("firefox_dmabuf", value).apply() }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean(STABILITY_MIGRATION, true)
            .putBoolean(RUNTIME_CUTOVER_MIGRATION, true)
            .putString("runtime_backend", VesselRuntimeFactory.ACTIVE_BACKEND_ID)
            .putInt("vcpus", 4)
            .putInt("refresh_hz", 120)
            .apply()
    }
}
