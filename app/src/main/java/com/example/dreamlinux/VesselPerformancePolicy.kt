package com.example.dreamlinux

import kotlin.math.min

/**
 * Battery policy for the shared-kernel desktop.
 *
 * Interactive work uses the user's configured refresh ceiling. Once input has
 * been quiet for a short window, the compositor and SurfaceFlinger hint drop to
 * at most 60 Hz. There is no frame polling timer; one delayed callback performs
 * the transition.
 */
object VesselPerformancePolicy {
    const val IDLE_DELAY_MS = 1800L
    const val IDLE_MAX_HZ = 60f

    fun activeRefresh(maxRefreshHz: Float): Float =
        maxRefreshHz.coerceIn(30f, 240f)

    fun idleRefresh(maxRefreshHz: Float): Float =
        min(activeRefresh(maxRefreshHz), IDLE_MAX_HZ)
}
