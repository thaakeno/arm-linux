package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class VesselRootlessSystemBusTest {
    @Test
    fun configIsValidSystemBusWithoutPrivilegeDropOrActivation() {
        val config = VesselRootlessSystemBus.configText()

        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(config)))

        assertEquals(0, document.getElementsByTagName("user").length)
        assertEquals(0, document.getElementsByTagName("servicehelper").length)
        assertEquals(0, document.getElementsByTagName("standard_system_servicedirs").length)
        assertEquals(0, document.getElementsByTagName("fork").length)

        assertTrue(config.contains("<type>system</type>"))
        assertTrue(config.contains("unix:path=/run/dbus/system_bus_socket"))
        assertTrue(config.contains("<auth>EXTERNAL</auth>"))
        assertTrue(config.contains("<allow user=\"*\"/>"))
        assertTrue(config.contains("<allow own=\"*\"/>"))
        assertFalse(config.contains("<user>messagebus</user>"))
    }

    @Test
    fun runtimeProbeTestsRealBusNameOwnership() {
        val probe = VesselRootlessSystemBus.probeCommand()
        assertTrue(probe.contains("dbus-send --system --print-reply"))
        assertTrue(probe.contains("org.freedesktop.DBus.RequestName"))
        assertTrue(probe.contains(VesselRootlessSystemBus.PROBE_NAME))
        assertTrue(probe.contains("VESSEL_SYSTEM_DBUS_OK"))
    }
}
