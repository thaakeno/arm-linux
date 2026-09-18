package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Test

class VesselPerformancePolicyTest {
    @Test
    fun interactiveModeUsesConfiguredCeiling() {
        assertEquals(120f, VesselPerformancePolicy.activeRefresh(120f), 0.001f)
        assertEquals(240f, VesselPerformancePolicy.activeRefresh(500f), 0.001f)
        assertEquals(30f, VesselPerformancePolicy.activeRefresh(1f), 0.001f)
    }

    @Test
    fun idleModeNeverRaisesRefreshAndCapsAtSixty() {
        assertEquals(60f, VesselPerformancePolicy.idleRefresh(120f), 0.001f)
        assertEquals(60f, VesselPerformancePolicy.idleRefresh(60f), 0.001f)
        assertEquals(48f, VesselPerformancePolicy.idleRefresh(48f), 0.001f)
    }
}
