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


text = CONTROLLER.read_text()

# Physical alpha9 logs show the audio/control setup completing, then the giant
# desktop-prep + Wayland command timing out before VESSEL_WAYLAND_BOOTSTRAP_BEGIN.
# The prep still contained a recursive chown of the entire persistent Firefox
# profile. That is cheap on a fresh image but can become arbitrarily expensive
# after real browsing and makes later boots look randomly broken. Only chown the
# files/directories Vessel itself creates; Firefox-owned descendants stay owned
# by the vessel user naturally.
text = replace_once(
    text,
    "            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config\n",
    """            chown vessel:vessel /home/vessel/.mozilla /home/vessel/.mozilla/firefox /home/vessel/.mozilla/firefox/vessel.default /home/vessel/.config\n            chown vessel:vessel /home/vessel/.mozilla/firefox/profiles.ini /home/vessel/.mozilla/firefox/vessel.default/user.js\n            echo VESSEL_PREP_STAGE=profile-ready\n""",
    "remove recursive persistent-profile chown",
)

# Make the remaining host-device preparation bounded. udevadm trigger has no
# built-in timeout and a wedged coldplug must never keep the Android boot RPC
# open forever. The DRM/input nodes were already verified earlier in startup, so
# timing out this refresh is non-fatal.
text = replace_once(
    text,
    "            udevadm trigger --action=add || true\n            udevadm settle --timeout=10 || true\n",
    """            echo VESSEL_PREP_STAGE=udev\n            timeout 8s udevadm trigger --action=add || true\n            timeout 12s udevadm settle --timeout=10 || true\n            echo VESSEL_PREP_STAGE=udev-ready\n""",
    "bound udev coldplug",
)

# D-Bus is required for the ConsoleKit shim, but daemon startup should also be
# bounded and produce a stage marker before KWin is ever launched.
text = replace_once(
    text,
    "            dbus-daemon --system --fork 2>/tmp/vessel-dbus.log || { echo VESSEL_DBUS_START_FAILED; cat /tmp/vessel-dbus.log; exit 48; }\n",
    "            timeout 8s dbus-daemon --system --fork 2>/tmp/vessel-dbus.log || { echo VESSEL_DBUS_START_FAILED; cat /tmp/vessel-dbus.log; exit 48; }\n",
    "bound system dbus startup",
)
text = replace_once(
    text,
    "            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames >/dev/null 2>&1 || { echo VESSEL_DBUS_HEALTHCHECK_FAILED; cat /tmp/vessel-dbus.log 2>/dev/null || true; exit 48; }\n",
    """            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames >/dev/null 2>&1 || { echo VESSEL_DBUS_HEALTHCHECK_FAILED; cat /tmp/vessel-dbus.log 2>/dev/null || true; exit 48; }\n            echo VESSEL_PREP_STAGE=dbus-ready\n""",
    "dbus stage marker",
)

# The ConsoleKit broker is long-lived. Do not start it as an ordinary '&' child
# of the boot shell: detach it with setsid -f and close stdin so it cannot retain
# the command pipeline or delay the completion marker on any shell/pty variant.
text = replace_once(
    text,
    "            nohup /usr/bin/python3 /usr/local/lib/vessel/consolekit_shim.py >/tmp/vessel-consolekit.log 2>&1 &\n",
    "            setsid -f /usr/bin/python3 /usr/local/lib/vessel/consolekit_shim.py </dev/null >/tmp/vessel-consolekit.log 2>&1\n",
    "detach ConsoleKit shim",
)
text = replace_once(
    text,
    "            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | grep -q 'boolean true' || { cat /tmp/vessel-consolekit.log; exit 47; }\n",
    """            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | grep -q 'boolean true' || { cat /tmp/vessel-consolekit.log; exit 47; }\n            echo VESSEL_PREP_STAGE=consolekit-ready\n""",
    "ConsoleKit stage marker",
)

# Most importantly, never send one opaque mega-command that contains both all
# setup work and the compositor launch. Keep tty0 for exactly two bounded phases:
#   1) non-graphical prep while tty0 is known-good;
#   2) the authoritative Wayland bootstrap as the final tty transaction.
# Once phase 2 reports KWin/Plasma/Wayland ready, alpha9's existing early return
# guarantees no follow-up graphical-tty RPC is issued.
text = replace_once(
    text,
    "        var preparedDesktop = prep\n",
    "        var preparedDesktop = \"\"\n",
    "separate prep from Wayland bootstrap",
)
text = replace_once(
    text,
    "        val prepResult = guestBlocking(preparedDesktop, 90)\n",
    """        append("[desktop] prep stage 1/2: bounded non-graphical setup\\n")
        val prepResult = guestBlocking(prep, 75)
        if (prepResult.first == 0 && backend == "wayland") {
            check(preparedDesktop.isNotBlank()) { "Wayland bootstrap command was empty" }
            append("[desktop] prep stage 2/2: authoritative Wayland bootstrap\\n")
            val bootstrapResult = guestBlocking(preparedDesktop, 90)
            check(bootstrapResult.first == 0) {
                "Wayland bootstrap failed: ${bootstrapResult.second.takeLast(12000)}"
            }
        }
""",
    "two-phase Wayland boot",
)

text = text.replace("v53-nonblocking-control-plane-r1", "v54-staged-prep-no-recursive-chown-r1")
CONTROLLER.write_text(text)

if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace(
        "v53-nonblocking-control-plane-r1",
        "v54-staged-prep-no-recursive-chown-r1",
    ))

print("[alpha14] persistent-profile recursive chown removed; udev/dbus/ConsoleKit bounded; Wayland prep split into two deterministic RPCs")
