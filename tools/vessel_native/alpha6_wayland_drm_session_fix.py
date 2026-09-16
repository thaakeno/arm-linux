#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, got {count}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1))


# KWin 5.27's DRM backend cannot use its NoopSession to open /dev/dri/card0:
# NoopSession::openRestricted() always returns -1.  Vessel deliberately has no
# systemd-logind, so provide the small subset of ConsoleKit's D-Bus API that
# KWin already supports.  The shim runs as root and passes real DRM/input fds
# to the unprivileged compositor over D-Bus SCM_RIGHTS.
replace_once(
    CONTROLLER,
    '"for p in kwin-wayland plasma-workspace-wayland xwayland qtwayland5 ',
    '"for p in kwin-wayland plasma-workspace-wayland xwayland qtwayland5 python3-dbus python3-gi ',
)
replace_once(
    CONTROLLER,
    '"kde-plasma-desktop plasma-workspace plasma-desktop kwin-x11 kwin-wayland plasma-workspace-wayland xwayland qtwayland5 wayland-utils systemsettings " +',
    '"kde-plasma-desktop plasma-workspace plasma-desktop kwin-x11 kwin-wayland plasma-workspace-wayland xwayland qtwayland5 wayland-utils systemsettings python3-dbus python3-gi " +',
)

replace_once(
    CONTROLLER,
    '''            mountpoint -q /tmp || mount -t tmpfs -o mode=1777,size=256m tmpfs /tmp
            mkdir -p /run/user
''',
    '''            mountpoint -q /tmp || mount -t tmpfs -o mode=1777,size=256m tmpfs /tmp
            # tmpfs replaced the old /tmp tree, so recreate the Xwayland socket directory.
            mkdir -p /tmp/.X11-unix
            chmod 1777 /tmp/.X11-unix
            mkdir -p /run/user
''',
)

replace_once(
    CONTROLLER,
    '''            mkdir -p /run/user/${'$'}uid; chown ${'$'}uid:${'$'}gid /run/user/${'$'}uid; chmod 700 /run/user/${'$'}uid
            test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1
            rm -f /etc/X11/xorg.conf.d/99-vessel.conf
''',
    '''            mkdir -p /run/user/${'$'}uid; chown ${'$'}uid:${'$'}gid /run/user/${'$'}uid; chmod 700 /run/user/${'$'}uid
            test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1
            # KWin 5.27 requires a real session object for DRM/input fd acquisition.
            # Vessel has no logind PID1, so expose a minimal ConsoleKit-compatible
            # session broker that opens devices as root and passes the fds via D-Bus.
            install -d -m 755 /usr/local/lib/vessel
            cat >/usr/local/lib/vessel/consolekit_shim.py <<'VCK'
            #!/usr/bin/python3
            import os
            import stat
            import dbus
            import dbus.service
            from dbus.mainloop.glib import DBusGMainLoop
            from gi.repository import GLib

            SERVICE = 'org.freedesktop.ConsoleKit'
            MANAGER = '/org/freedesktop/ConsoleKit/Manager'
            SESSION = '/org/freedesktop/ConsoleKit/Session1'
            SEAT = '/org/freedesktop/ConsoleKit/Seat1'
            MANAGER_IF = 'org.freedesktop.ConsoleKit.Manager'
            SESSION_IF = 'org.freedesktop.ConsoleKit.Session'
            SEAT_IF = 'org.freedesktop.ConsoleKit.Seat'
            PROPS_IF = 'org.freedesktop.DBus.Properties'

            def device_path(major_num, minor_num):
                preferred = ['/dev/dri/card0', '/dev/dri/renderD128']
                for base in ('/dev/input', '/dev/dri'):
                    try:
                        preferred.extend(os.path.join(base, x) for x in os.listdir(base))
                    except OSError:
                        pass
                for path in preferred:
                    try:
                        st = os.stat(path)
                    except OSError:
                        continue
                    if stat.S_ISCHR(st.st_mode) and os.major(st.st_rdev) == major_num and os.minor(st.st_rdev) == minor_num:
                        return path
                raise dbus.exceptions.DBusException('org.freedesktop.ConsoleKit.Error.Failed', f'device {major_num}:{minor_num} not found')

            class Manager(dbus.service.Object):
                @dbus.service.method(MANAGER_IF, in_signature='u', out_signature='o')
                def GetSessionByPID(self, pid):
                    return dbus.ObjectPath(SESSION)

            class Session(dbus.service.Object):
                @dbus.service.method(SESSION_IF, in_signature='b', out_signature='')
                def TakeControl(self, force):
                    return

                @dbus.service.method(SESSION_IF, in_signature='', out_signature='')
                def ReleaseControl(self):
                    return

                @dbus.service.method(SESSION_IF, in_signature='', out_signature='')
                def Activate(self):
                    return

                @dbus.service.method(SESSION_IF, in_signature='uu', out_signature='h')
                def TakeDevice(self, major_num, minor_num):
                    path = device_path(int(major_num), int(minor_num))
                    fd = os.open(path, os.O_RDWR | os.O_CLOEXEC | os.O_NONBLOCK)
                    try:
                        return dbus.types.UnixFd(fd)
                    finally:
                        os.close(fd)

                @dbus.service.method(SESSION_IF, in_signature='uu', out_signature='')
                def ReleaseDevice(self, major_num, minor_num):
                    return

                @dbus.service.method(PROPS_IF, in_signature='ss', out_signature='v')
                def Get(self, interface, prop):
                    if interface != SESSION_IF:
                        raise dbus.exceptions.DBusException('org.freedesktop.DBus.Error.InvalidArgs', 'unknown interface')
                    if prop == 'active':
                        return dbus.Boolean(True, variant_level=1)
                    if prop == 'VTNr':
                        return dbus.UInt32(1, variant_level=1)
                    if prop == 'Seat':
                        return dbus.Struct((dbus.String('seat0'), dbus.ObjectPath(SEAT)), signature='so', variant_level=1)
                    raise dbus.exceptions.DBusException('org.freedesktop.DBus.Error.InvalidArgs', 'unknown property')

            class Seat(dbus.service.Object):
                @dbus.service.method(SEAT_IF, in_signature='u', out_signature='')
                def SwitchTo(self, terminal):
                    return

            DBusGMainLoop(set_as_default=True)
            bus = dbus.SystemBus()
            name = dbus.service.BusName(SERVICE, bus=bus, do_not_queue=True)
            Manager(bus, MANAGER)
            Session(bus, SESSION)
            Seat(bus, SEAT)
            print('VESSEL_CONSOLEKIT_READY', flush=True)
            GLib.MainLoop().run()
            VCK
            chmod 0755 /usr/local/lib/vessel/consolekit_shim.py
            pkill -f '/usr/local/lib/vessel/consolekit_shim.py' 2>/dev/null || true
            nohup /usr/bin/python3 /usr/local/lib/vessel/consolekit_shim.py >/tmp/vessel-consolekit.log 2>&1 &
            for i in ${'$'}(seq 1 80); do
              dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | grep -q 'boolean true' && break
              sleep .1
            done
            dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | grep -q 'boolean true' || { cat /tmp/vessel-consolekit.log; exit 47; }
            # Keep ordinary unix permissions sane too; the broker still owns the fd handoff.
            test ! -c /dev/dri/card0 || { chgrp video /dev/dri/card0 || true; chmod 0660 /dev/dri/card0 || true; }
            test ! -c /dev/dri/renderD128 || { chgrp render /dev/dri/renderD128 || true; chmod 0660 /dev/dri/renderD128 || true; }
            rm -f /etc/X11/xorg.conf.d/99-vessel.conf
''',
)

# Make failure output immediately identify whether KWin saw the fake ConsoleKit
# session, the DRM node, and the Xwayland socket directory.
replace_once(
    CONTROLLER,
    '''            val check = guestBlocking("for i in \\$(seq 1 240); do pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null && break; sleep .1; done; pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null || { tail -200 /tmp/vessel-plasma.log 2>/dev/null; exit 44; }; test -S /run/user/\\$(id -u vessel)/wayland-0 || { ls -la /run/user/\\$(id -u vessel); tail -200 /tmp/vessel-plasma.log; exit 46; }; echo VESSEL_WAYLAND_READY", 45)
''',
    '''            val check = guestBlocking("for i in \\$(seq 1 240); do pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null && break; sleep .1; done; pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null || { echo VESSEL_WAYLAND_DIAG; id vessel; ls -l /dev/dri 2>/dev/null; ls -ld /tmp/.X11-unix 2>/dev/null; dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null; cat /tmp/vessel-consolekit.log 2>/dev/null; tail -240 /tmp/vessel-plasma.log 2>/dev/null; exit 44; }; test -S /run/user/\\$(id -u vessel)/wayland-0 || { ls -la /run/user/\\$(id -u vessel); cat /tmp/vessel-consolekit.log 2>/dev/null; tail -240 /tmp/vessel-plasma.log; exit 46; }; echo VESSEL_WAYLAND_READY", 45)
''',
)

# Distinguish this device-tested DRM-session fix from the first alpha6 image.
text = CONTROLLER.read_text().replace(
    'v41-wayland-async-surface-system-egl-r1',
    'v42-wayland-consolekit-drm-session-r1',
)
CONTROLLER.write_text(text)
if SERVICE.exists():
    text = SERVICE.read_text().replace(
        'v41-wayland-async-surface-system-egl-r1',
        'v42-wayland-consolekit-drm-session-r1',
    )
    SERVICE.write_text(text)

print('[alpha6-drm-session] KWin ConsoleKit fd broker + Xwayland tmpfs socket fix applied')
