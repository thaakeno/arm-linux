#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Physical-device recovery rule:
#
# 1a0d1ec was the proven boot. It succeeded because desktop preparation,
# hot-runtime installation, KWin/Plasma launch and the Wayland-ready probe lived
# in ONE final tty transaction. Splitting that transaction later reintroduced
# the exact class of tty/daemon lifetime bug that the single-RPC design solved.
# Keep that architecture and only make potentially risky prep operations bounded.
# ---------------------------------------------------------------------------
text = CONTROLLER.read_text()

# Never recursively walk the persistent Firefox profile on every boot. A real
# browsing profile can contain thousands of files and make startup time depend on
# user data. Only fix ownership of the files/directories Vessel itself creates.
text = replace_once(
    text,
    "            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config\n",
    """            chown vessel:vessel /home/vessel/.mozilla /home/vessel/.mozilla/firefox /home/vessel/.mozilla/firefox/vessel.default /home/vessel/.config
            chown vessel:vessel /home/vessel/.mozilla/firefox/profiles.ini /home/vessel/.mozilla/firefox/vessel.default/user.js
            echo VESSEL_PREP_STAGE=profile-ready
""",
    "remove recursive persistent-profile chown",
)

# systemd-udevd is useful for the desktop session but Vessel does not run a real
# systemd PID1. On a phone boot it must therefore be best-effort and bounded.
# The DRM and input device nodes were already verified before launchDesktop().
udev_daemon_old = "            (pgrep -x systemd-udevd >/dev/null || (/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log || /usr/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log))\n"
udev_daemon_new = """            if ! pgrep -x systemd-udevd >/dev/null 2>&1; then
              timeout 6s /lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log || timeout 6s /usr/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log || true
            fi
            echo VESSEL_PREP_STAGE=udevd-ready
"""
text = replace_once(text, udev_daemon_old, udev_daemon_new, "bound udev daemon startup")

text = replace_once(
    text,
    "            udevadm trigger --action=add || true\n            udevadm settle --timeout=10 || true\n",
    """            echo VESSEL_PREP_STAGE=udev
            timeout 8s udevadm trigger --action=add || true
            timeout 12s udevadm settle --timeout=10 || true
            echo VESSEL_PREP_STAGE=udev-ready
""",
    "bound udev coldplug",
)

# D-Bus and the ConsoleKit compatibility broker are required by Bookworm KWin,
# but neither daemon is allowed to own the UML boot tty or block it indefinitely.
text = replace_once(
    text,
    "            dbus-daemon --system --fork 2>/tmp/vessel-dbus.log || { echo VESSEL_DBUS_START_FAILED; cat /tmp/vessel-dbus.log; exit 48; }\n",
    "            timeout 8s dbus-daemon --system --fork 2>/tmp/vessel-dbus.log || { echo VESSEL_DBUS_START_FAILED; cat /tmp/vessel-dbus.log; exit 48; }\n",
    "bound system dbus startup",
)
text = replace_once(
    text,
    "            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames >/dev/null 2>&1 || { echo VESSEL_DBUS_HEALTHCHECK_FAILED; cat /tmp/vessel-dbus.log 2>/dev/null || true; exit 48; }\n",
    """            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames >/dev/null 2>&1 || { echo VESSEL_DBUS_HEALTHCHECK_FAILED; cat /tmp/vessel-dbus.log 2>/dev/null || true; exit 48; }
            echo VESSEL_PREP_STAGE=dbus-ready
""",
    "dbus stage marker",
)
text = replace_once(
    text,
    "            nohup /usr/bin/python3 /usr/local/lib/vessel/consolekit_shim.py >/tmp/vessel-consolekit.log 2>&1 &\n",
    "            setsid -f /usr/bin/python3 /usr/local/lib/vessel/consolekit_shim.py </dev/null >/tmp/vessel-consolekit.log 2>&1\n",
    "detach ConsoleKit shim",
)
text = replace_once(
    text,
    "            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | grep -q 'boolean true' || { cat /tmp/vessel-consolekit.log; exit 47; }\n",
    """            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | grep -q 'boolean true' || { cat /tmp/vessel-consolekit.log; exit 47; }
            echo VESSEL_PREP_STAGE=consolekit-ready
""",
    "ConsoleKit stage marker",
)

# Keep alpha9's proven single authoritative tty transaction. This is the key
# regression fix: do NOT split prep and Wayland bootstrap into separate guest
# RPCs. Hot v49 Wayland files + post-prep hook + nonblocking control-agent
# dispatch remain folded into preparedDesktop by alpha9/alpha13.
text = replace_once(
    text,
    "        val prepResult = guestBlocking(preparedDesktop, 90)\n",
    """        append("[desktop] authoritative single-RPC prep + Wayland bootstrap\\n")
        val prepResult = guestBlocking(preparedDesktop, 120)
""",
    "single authoritative Wayland transaction",
)

text = text.replace("v53-nonblocking-control-plane-r1", "v55-restored-single-rpc-r1")
CONTROLLER.write_text(text)

# ---------------------------------------------------------------------------
# Apps: the first successful graphics build exposed another tty problem: the
# App page sent post-boot commands through the graphical tty and could sit on a
# spinner until a 180s timeout. Alpha10 moved post-boot work to the reconnecting
# guest agent. Make discovery explicitly wait for that authenticated transport,
# fail fast instead of spinning forever, and keep the local AppStream cache.
# ---------------------------------------------------------------------------
service = SERVICE.read_text()
service = replace_once(
    service,
    """        scope.launch(Dispatchers.IO) {
            try {
                installDiscoveryHelper()
""",
    """        scope.launch(Dispatchers.IO) {
            try {
                if (!VesselGuestAgent.waitUntilConnected(8_000)) {
                    appStore.value = appStore.value.copy(
                        loading = false,
                        error = "Debian app service is still connecting. Tap Search or a filter to retry.",
                    )
                    return@launch
                }
                installDiscoveryHelper()
""",
    "Apps wait for authenticated control transport",
)

# Cold local AppStream parsing should normally take seconds on four vCPUs. Keep
# enough headroom for a large catalog, but do not leave the UI looking hung for
# two minutes if the guest helper is unhealthy.
service = service.replace(
    """                    120,
                )
                check(result.optBoolean("ok")) { "Debian app discovery returned rc=${result.optInt("rc", -1)}" }
""",
    """                    60,
                )
                check(result.optBoolean("ok")) { "Debian app discovery returned rc=${result.optInt("rc", -1)}" }
""",
    1,
)
service = service.replace("v53-nonblocking-control-plane-r1", "v55-restored-single-rpc-r1")
SERVICE.write_text(service)

print("[alpha14] restored proven single-RPC Wayland boot; bounded udev/dbus/ConsoleKit; Apps wait on the post-boot control agent and fail fast")
