package com.example.dreamlinux

import android.content.Context

/**
 * Single construction point for Linux runtimes.
 *
 * Phase 1 keeps UML hard-selected. Do not make proroot selectable until PTY /
 * process lifetime, direct GPU presentation and desktop compatibility have
 * their own acceptance gates.
 */
object VesselRuntimeFactory {
    const val ACTIVE_BACKEND_ID = "uml"

    fun createActive(
        context: Context,
        progress: (String, Int, String) -> Unit,
    ): VesselRuntimeBackend = VesselUmlRuntimeBackend(context, progress)

    fun createProrootScaffold(
        context: Context,
        progress: (String, Int, String) -> Unit,
    ): VesselProrootRuntimeBackend = VesselProrootRuntimeBackend(context, progress)
}
