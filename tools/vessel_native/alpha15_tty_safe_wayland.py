#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
ACTIVITY = ROOT / "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
ASSETS = ROOT / "app/src/main/assets/vessel"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


# v56 physical boot recovery:
# stage small files while tty0 is still healthy, then execute one tiny final
# Wayland command. No tty RPC is sent after VESSEL_WAYLAND_READY.
ASSETS.mkdir(parents=True, exist_ok=True)

(ASSETS / "consolekit_shim.py").write_text(r'''#!/usr/bin/python3
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
    candidates = ['/dev/dri/card0', '/dev/dri/renderD128']
    for base in ('/dev/input', '/dev/dri'):
        try:
            candidates.extend(os.path.join(base, name) for name in os.listdir(base))
        except OSError:
            pass
    for path in candidates:
        try:
            st = os.stat(path)
        except OSError:
            continue
        if stat.S_ISCHR(st.st_mode) and os.major(st.st_rdev) == major_num and os.minor(st.st_rdev) == minor_num:
            return path
    raise dbus.exceptions.DBusException(
        'org.freedesktop.ConsoleKit.Error.Failed',
        f'device {major_num}:{minor_num} not found',
    )

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
            return dbus.Struct(
                (dbus.String('seat0'), dbus.ObjectPath(SEAT)),
                signature='so',
                variant_level=1,
            )
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
''')

(ASSETS / "wayland_session_stable.sh").write_text(r'''#!/bin/bash
set -e
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export XDG_SESSION_TYPE=wayland
export XDG_SESSION_DESKTOP=KDE
export XDG_CURRENT_DESKTOP=KDE
export DESKTOP_SESSION=plasmawayland
export XDG_SESSION_CLASS=user
export XDG_SEAT=seat0
export XDG_VTNR=1
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=5
export LIBGL_ALWAYS_SOFTWARE=0
export GALLIUM_DRIVER=virgl
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM=wayland
export CLUTTER_BACKEND=wayland
export SDL_VIDEODRIVER=wayland
export MOZ_ENABLE_WAYLAND=1
export MOZ_WEBRENDER=1
export MOZ_ACCELERATED=1
unset DISPLAY MOZ_X11_EGL
exec startplasma-wayland
''')

(ASSETS / "launch_wayland_stable.py").write_text(r'''#!/usr/bin/python3
import subprocess

uid = subprocess.check_output(['/usr/bin/id', '-u', 'vessel'], text=True).strip()
command = (
    f'XDG_RUNTIME_DIR=/run/user/{uid} XDG_SEAT=seat0 XDG_VTNR=1 '
    'exec dbus-run-session -- /usr/local/bin/vessel-plasma-session'
)
log = open('/tmp/vessel-plasma.log', 'ab', buffering=0)
proc = subprocess.Popen(
    ['/usr/bin/su', '-l', 'vessel', '-c', command],
    stdin=subprocess.DEVNULL,
    stdout=log,
    stderr=subprocess.STDOUT,
    cwd='/home/vessel',
    close_fds=True,
    start_new_session=True,
)
print(f'VESSEL_WAYLAND_PID={proc.pid}', flush=True)
''')

(ASSETS / "wayland_boot_stable.sh").write_text(r'''#!/bin/bash
set -eu

fail() {
  rc="$1"
  echo VESSEL_WAYLAND_DIAG
  id vessel 2>/dev/null || true
  ls -l /dev/dri 2>/dev/null || true
  ls -la /run/user/* 2>/dev/null || true
  ls -ld /tmp/.X11-unix 2>/dev/null || true
  timeout 2s dbus-send --system --print-reply --dest=org.freedesktop.DBus / \
    org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null || true
  cat /tmp/vessel-dbus.log 2>/dev/null || true
  cat /tmp/vessel-consolekit.log 2>/dev/null || true
  tail -300 /tmp/vessel-plasma.log 2>/dev/null || true
  exit "$rc"
}

echo VESSEL_WAYLAND_STAGE=preflight
install -d -m 755 /run/dbus /run/user /usr/local/lib/vessel /usr/local/bin
mountpoint -q /tmp || mount -t tmpfs -o mode=1777,size=256m tmpfs /tmp
install -d -m 1777 /tmp/.X11-unix
mountpoint -q /run/user || mount -t tmpfs -o mode=0755,size=64m tmpfs /run/user
dbus-uuidgen --ensure=/etc/machine-id

id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel
for group in video render input; do
  getent group "$group" >/dev/null || groupadd "$group"
done
usermod -a -G video,render,input vessel
uid=$(id -u vessel)
gid=$(id -g vessel)
install -d -m 700 -o "$uid" -g "$gid" "/run/user/$uid"
test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1
test ! -c /dev/dri/card0 || { chgrp video /dev/dri/card0 || true; chmod 0660 /dev/dri/card0 || true; }
test ! -c /dev/dri/renderD128 || { chgrp render /dev/dri/renderD128 || true; chmod 0660 /dev/dri/renderD128 || true; }
for node in /dev/input/event*; do
  [ -e "$node" ] || continue
  chgrp input "$node" 2>/dev/null || true
  chmod 0660 "$node" 2>/dev/null || true
done

# DRM/input were already validated by Android. No global udev coldplug here.
echo VESSEL_WAYLAND_STAGE=dbus
dbus_ok=0
if [ -S /run/dbus/system_bus_socket ]; then
  timeout 2s dbus-send --system --print-reply --dest=org.freedesktop.DBus / \
    org.freedesktop.DBus.ListNames >/dev/null 2>&1 && dbus_ok=1 || true
fi
if [ "$dbus_ok" -ne 1 ]; then
  pkill -x dbus-daemon 2>/dev/null || true
  rm -f /run/dbus/system_bus_socket /run/dbus/pid
  : >/tmp/vessel-dbus.log
  timeout 8s dbus-daemon --system --fork >>/tmp/vessel-dbus.log 2>&1 || fail 48
fi
timeout 3s dbus-send --system --print-reply --dest=org.freedesktop.DBus / \
  org.freedesktop.DBus.ListNames >/dev/null 2>&1 || fail 48
echo VESSEL_WAYLAND_STAGE=dbus-ready

pkill -f '/usr/local/lib/vessel/consolekit_shim.py' 2>/dev/null || true
: >/tmp/vessel-consolekit.log
setsid -f /usr/bin/python3 /usr/local/lib/vessel/consolekit_shim.py \
  </dev/null >/tmp/vessel-consolekit.log 2>&1 || fail 47
consolekit=0
for _ in $(seq 1 60); do
  if timeout 1s dbus-send --system --print-reply --dest=org.freedesktop.DBus / \
       org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null | \
       grep -q 'boolean true'; then
    consolekit=1
    break
  fi
  sleep .1
done
[ "$consolekit" -eq 1 ] || fail 47
echo VESSEL_WAYLAND_STAGE=consolekit-ready

pkill -u vessel -x kwin_x11 2>/dev/null || true
pkill -u vessel -x kwin_wayland 2>/dev/null || true
pkill -u vessel -x plasmashell 2>/dev/null || true
pkill -x Xorg 2>/dev/null || true
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
find "/run/user/$uid" -maxdepth 1 -type s -name 'wayland-*' -delete 2>/dev/null || true
: >/tmp/vessel-plasma.log

echo VESSEL_WAYLAND_BOOTSTRAP_BEGIN
/usr/bin/python3 /usr/local/lib/vessel/launch_wayland.py || fail 44
echo VESSEL_WAYLAND_STAGE=launched

last=''
ready=0
for _ in $(seq 1 600); do
  kwin=0
  plasma=0
  socket=0
  pgrep -u vessel -x kwin_wayland >/dev/null 2>&1 && kwin=1 || true
  pgrep -u vessel -x plasmashell >/dev/null 2>&1 && plasma=1 || true
  find "/run/user/$uid" -maxdepth 1 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q . && socket=1 || true
  if [ "$kwin" -eq 1 ] && [ "$plasma" -eq 1 ] && [ "$socket" -eq 1 ]; then
    ready=1
    break
  fi
  if [ "$kwin" -eq 0 ]; then
    stage=kwin
  elif [ "$plasma" -eq 0 ]; then
    stage=plasma
  else
    stage=socket
  fi
  if [ "$stage" != "$last" ]; then
    echo "VESSEL_WAYLAND_STAGE=$stage"
    last="$stage"
  fi
  sleep .1
done
[ "$ready" -eq 1 ] || fail 44

echo VESSEL_WAYLAND_STAGE=desktop-ready

# Control RPC is auxiliary; it can never block or fail desktop readiness.
cfg=/run/vessel/control-agent.env
agent=/usr/local/lib/vessel/control_agent.py
if [ -r "$cfg" ] && [ -x "$agent" ] && command -v setsid >/dev/null 2>&1; then
  . "$cfg"
  pkill -f '/usr/local/lib/vessel/control_agent.py' 2>/dev/null || true
  : >/tmp/vessel-control-agent.log
  setsid -f /usr/bin/python3 "$agent" \
    "${VESSEL_CONTROL_HOST:-10.0.2.2}" "$VESSEL_CONTROL_PORT" "$VESSEL_CONTROL_TOKEN" \
    </dev/null >/tmp/vessel-control-agent.log 2>&1 || true
  echo VESSEL_CONTROL_AGENT_DISPATCHED
fi

echo VESSEL_WAYLAND_READY
exit 0
''')

controller = CONTROLLER.read_text()

stable_methods = r'''
    private fun stageWaylandAsset(assetName: String, guestPath: String, mode: String = "0755") {
        val bytes = context.assets.open("vessel/$assetName").use { it.readBytes() }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val command =
            "install -d -m 755 /usr/local/lib/vessel /usr/local/bin; " +
                "printf '%s' '$encoded' | base64 -d >'$guestPath'; chmod $mode '$guestPath'; " +
                "echo VESSEL_STAGE_ASSET_OK"
        val (rc, out) = guestBlocking(command, 20)
        check(rc == 0 && out.contains("VESSEL_STAGE_ASSET_OK")) {
            "Could not stage $assetName: ${out.takeLast(3000)}"
        }
    }

    private fun launchStableWayland() {
        progress("desktop", 72, "Preparing Plasma Wayland")
        append("[desktop] v56 tty-safe Wayland bootstrap: staging small boot files before compositor launch\\n")

        stageWaylandAsset("consolekit_shim.py", "/usr/local/lib/vessel/consolekit_shim.py")
        stageWaylandAsset("wayland_session_stable.sh", "/usr/local/bin/vessel-plasma-session")
        stageWaylandAsset("launch_wayland_stable.py", "/usr/local/lib/vessel/launch_wayland.py")
        stageWaylandAsset("wayland_boot_stable.sh", "/usr/local/lib/vessel/wayland_boot.sh")

        // Final tty0 transaction: tiny command, all real work lives in staged files.
        val result = guestBlocking("/usr/local/lib/vessel/wayland_boot.sh", 90) { raw ->
            when {
                raw.contains("VESSEL_WAYLAND_STAGE=preflight") ->
                    progress("desktop_preflight", 73, "Preparing Wayland runtime")
                raw.contains("VESSEL_WAYLAND_STAGE=dbus-ready") ->
                    progress("desktop_dbus", 76, "Wayland system bus ready")
                raw.contains("VESSEL_WAYLAND_STAGE=consolekit-ready") ->
                    progress("desktop_session", 79, "DRM session broker ready")
                raw.contains("VESSEL_WAYLAND_STAGE=launched") ->
                    progress("desktop_launch", 82, "KWin Wayland launched")
                raw.contains("VESSEL_WAYLAND_STAGE=kwin") ->
                    progress("desktop_kwin", 84, "Waiting for KWin Wayland")
                raw.contains("VESSEL_WAYLAND_STAGE=plasma") ->
                    progress("desktop_plasma", 86, "Starting Plasma shell")
                raw.contains("VESSEL_WAYLAND_STAGE=socket") ->
                    progress("desktop_socket", 87, "Waiting for Wayland display socket")
                raw.contains("VESSEL_WAYLAND_STAGE=desktop-ready") ->
                    progress("desktop_ready", 88, "Plasma Wayland ready")
            }
        }
        check(result.first == 0 && result.second.contains("VESSEL_WAYLAND_READY")) {
            "Plasma Wayland bootstrap failed: ${result.second.takeLast(12000)}"
        }
        append("[desktop] v56 native KWin Wayland + Plasma ready; tty0 retired for this boot\\n")
    }

'''

controller = replace_once(
    controller,
    "    private fun launchDesktop() {\n",
    stable_methods + "    private fun launchDesktop() {\n",
    "insert tty-safe Wayland launcher",
)
controller = replace_once(
    controller,
    "        val backend = VesselExperimentConfig.desktopBackend(context)\n",
    r'''        val backend = VesselExperimentConfig.desktopBackend(context)
        if (backend == "wayland") {
            launchStableWayland()
            return
        }
''',
    "route Wayland through tty-safe launcher",
)
controller = controller.replace(
    "VesselGuestAgent.waitUntilConnected(8_000)",
    "VesselGuestAgent.waitUntilConnected(20_000)",
)
controller = controller.replace("v55-restored-single-rpc-r1", "v56-tty-safe-wayland-r1")
CONTROLLER.write_text(controller)

service = SERVICE.read_text()
service = service.replace(
    "VesselGuestAgent.waitUntilConnected(8_000)",
    "VesselGuestAgent.waitUntilConnected(20_000)",
)
service = service.replace("v55-restored-single-rpc-r1", "v56-tty-safe-wayland-r1")
SERVICE.write_text(service)

# Restore elapsed setup/running time. uptimeMs was never removed from runtime
# state; only its rendering disappeared from the startup cards.
activity = ACTIVITY.read_text()
activity = replace_once(
    activity,
    'Text(if (state.running || state.busy) state.message else "Persistent Linux PC on your phone", color = MaterialTheme.colorScheme.onSurfaceVariant)',
    'Text(if (state.running || state.busy) "${state.message} · ${uptime(state.uptimeMs)}" else "Persistent Linux PC on your phone", color = MaterialTheme.colorScheme.onSurfaceVariant)',
    "machine elapsed timer",
)
activity = replace_once(
    activity,
    'Text("${state.message} · ${state.progressPercent.coerceIn(0, 100)}%", maxLines = 3, overflow = TextOverflow.Ellipsis)',
    'Text("${state.message} · ${state.progressPercent.coerceIn(0, 100)}% · ${uptime(state.uptimeMs)}", maxLines = 3, overflow = TextOverflow.Ellipsis)',
    "display startup elapsed timer",
)
activity = replace_once(
    activity,
    'Text("${state.progressPercent.coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)',
    'Text("${state.progressPercent.coerceIn(0, 100)}% · ${uptime(state.uptimeMs)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)',
    "machine progress elapsed timer",
)
ACTIVITY.write_text(activity)

print("[alpha15] staged tiny Wayland boot RPC, removed boot udev coldplug, added 72-88% live stages, control handoff, and elapsed timer")
