package com.example.dreamlinux

import java.io.File

/**
 * Rootless system-type D-Bus for the proroot desktop.
 *
 * Android gives every process in Vessel the same real app UID. Proroot can
 * present guest identities, but it cannot grant CAP_SETUID/CAP_SETGID.
 * Debian's stock --system configuration contains <user>messagebus</user>,
 * which makes dbus-daemon try to change UID/drop capabilities and therefore
 * fail with EPERM. Vessel instead keeps the daemon under the Android app UID.
 *
 * This bus deliberately does not enable system-service activation: service
 * files can request arbitrary Unix users, which a rootless Android app cannot
 * switch to. Desktop software still gets a real system-type bus at the
 * standard socket and receives an immediate ServiceUnknown for unavailable
 * host daemons instead of a broken/hanging activation attempt.
 */
internal object VesselRootlessSystemBus {
    const val GUEST_SOCKET = "/run/dbus/system_bus_socket"
    const val GUEST_CONFIG = "/run/vessel-system-bus.conf"
    const val PROBE_NAME = "org.vessel.RootlessSystemBusProbe"

    fun configText(): String =
        """
        <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
         "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
        <busconfig>
          <type>system</type>
          <listen>unix:path=$GUEST_SOCKET</listen>
          <auth>EXTERNAL</auth>

          <!--
            There is intentionally no <user> element here. All peers belong to
            Vessel's Android sandbox UID, even when proroot exposes different
            guest identities. The Android sandbox is the security boundary.
          -->
          <policy context="default">
            <allow user="*"/>
            <allow own="*"/>
            <allow send_destination="*"/>
            <allow receive_sender="*"/>
          </policy>
        </busconfig>
        """.trimIndent() + "\n"

    fun writeConfig(hostRunDir: File): File {
        check(hostRunDir.isDirectory || hostRunDir.mkdirs()) {
            "Could not prepare Vessel /run backing directory"
        }
        val config = File(hostRunDir, "vessel-system-bus.conf")
        config.writeText(configText())
        return config
    }

    fun launchCommand(): String =
        """
        set -e
        install -d -m 755 /run/dbus
        rm -f $GUEST_SOCKET /run/dbus/pid
        exec dbus-daemon --config-file=$GUEST_CONFIG --nofork --nopidfile --nosyslog
        """.trimIndent()

    fun probeCommand(): String =
        """
        set -e
        test -S $GUEST_SOCKET
        reply=${'$'}(dbus-send --system --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.RequestName string:$PROBE_NAME uint32:0)
        printf '%s\\n' "${'$'}reply"
        printf '%s\\n' "${'$'}reply" | grep -Eq 'uint32[[:space:]]+1'
        echo VESSEL_SYSTEM_DBUS_OK
        """.trimIndent()
}
