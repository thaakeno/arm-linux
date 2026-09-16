#!/usr/bin/env python3
from pathlib import Path
import runpy
import sys

# Compatibility entry point kept because the native rebuild script already
# invokes this path. The hardened v2 patcher owns the alpha6 rewrite, then the
# device-tested DRM session repair layers on top of the generated runtime.
args = sys.argv[1:]
base = Path(__file__).with_name("alpha6_wayland_overhaul_v2.py")
sys.argv = [str(base), *args]
runpy.run_path(str(base), run_name="__main__")

fix = Path(__file__).with_name("alpha6_wayland_drm_session_fix.py")
sys.argv = [str(fix), *args]
runpy.run_path(str(fix), run_name="__main__")

# Alpha6.2: /run survives inside the persistent rootfs even though the daemon
# processes themselves do not survive a UML shutdown. A stale
# /run/dbus/system_bus_socket therefore looks valid to `test -S` while connect()
# returns ECONNREFUSED. Build a fresh system bus on every workstation boot,
# install the ConsoleKit policy before the daemon reads its config, and verify
# the bus by making a real round-trip before the fd broker is launched.
root = Path(args[0]).resolve() if args else Path(__file__).resolve().parents[2]
controller = root / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
service = root / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
text = controller.read_text()
old = """            test -S /run/dbus/system_bus_socket || dbus-daemon --system --fork
"""
new = """            # /run lives on the persistent rootfs, so a dead system bus can leave a
            # stale socket behind across UML boots. Recreate the bus instead of trusting
            # the socket inode, and load the ConsoleKit policy before startup.
            install -d -m 755 /run/dbus /etc/dbus-1/system.d
            cat >/etc/dbus-1/system.d/vessel-consolekit.conf <<'VDBUS'
            <!DOCTYPE busconfig PUBLIC \"-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN\"
             \"http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd\">
            <busconfig>
              <policy user=\"root\">
                <allow own=\"org.freedesktop.ConsoleKit\"/>
                <allow send_destination=\"org.freedesktop.ConsoleKit\"/>
                <allow receive_sender=\"org.freedesktop.ConsoleKit\"/>
              </policy>
              <policy context=\"default\">
                <allow send_destination=\"org.freedesktop.ConsoleKit\"/>
                <allow receive_sender=\"org.freedesktop.ConsoleKit\"/>
              </policy>
            </busconfig>
            VDBUS
            # No user session bus exists at this stage, so terminate any stale/system
            # dbus-daemon left by an earlier prep attempt and create one known-good bus.
            pkill -x dbus-daemon 2>/dev/null || true
            for i in ${'$'}(seq 1 40); do pgrep -x dbus-daemon >/dev/null || break; sleep .05; done
            pgrep -x dbus-daemon >/dev/null && pkill -KILL -x dbus-daemon 2>/dev/null || true
            rm -f /run/dbus/system_bus_socket /run/dbus/pid
            : >/tmp/vessel-dbus.log
            dbus-daemon --system --fork 2>/tmp/vessel-dbus.log || { echo VESSEL_DBUS_START_FAILED; cat /tmp/vessel-dbus.log; exit 48; }
            for i in ${'$'}(seq 1 80); do
              dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames >/dev/null 2>&1 && break
              sleep .1
            done
            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames >/dev/null 2>&1 || { echo VESSEL_DBUS_HEALTHCHECK_FAILED; cat /tmp/vessel-dbus.log 2>/dev/null || true; exit 48; }
"""
if text.count(old) != 1:
    raise SystemExit(f"{controller}: expected one fragile dbus startup anchor, got {text.count(old)}")
text = text.replace(old, new, 1)
text = text.replace(
    "v42-wayland-consolekit-drm-session-r1",
    "v43-wayland-dbus-recovery-r1",
)
controller.write_text(text)
if service.exists():
    service.write_text(service.read_text().replace(
        "v42-wayland-consolekit-drm-session-r1",
        "v43-wayland-dbus-recovery-r1",
    ))

print("[alpha6.2] fresh verified system D-Bus + ConsoleKit policy applied")
