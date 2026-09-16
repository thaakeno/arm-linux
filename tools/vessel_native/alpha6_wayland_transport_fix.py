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
        raise SystemExit(f"{label}: expected exactly one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


text = CONTROLLER.read_text()

# Debian Bookworm's KWin 5.27 no longer ships the old split
# kwin-wayland-backend-drm package; that package only exists in older Debian
# releases such as Bullseye.  Bookworm's kwin-wayland + kwin-common provide the
# current DRM-capable compositor stack, so do not inject the obsolete package
# into either validation or apt installation.
if "kwin-wayland-backend-drm" in text:
    raise SystemExit("obsolete kwin-wayland-backend-drm unexpectedly present before transport fix")

# The command RPC is line-oriented on the UML console.  Readline/PTY control
# sequences can prefix the completion token, and command output does not always
# end with a newline.  Detect a numeric token anywhere on the line and emit a
# leading newline before every marker so a completed command cannot be mistaken
# for a timeout merely because of terminal framing.
text = replace_once(
    text,
    '''                            val marker = pendingMarker
                            val trimmed = line.trim()
                            if (marker != null && trimmed.startsWith("$marker:")) {
                                val rc = trimmed.removePrefix("$marker:").toIntOrNull()
''',
    '''                            val marker = pendingMarker
                            val markerToken = marker?.let { "$it:" }
                            val markerAt = markerToken?.let { line.indexOf(it) } ?: -1
                            if (marker != null && markerToken != null && markerAt >= 0) {
                                val suffix = line.substring(markerAt + markerToken.length)
                                val rc = Regex("""^-?\\d+""").find(suffix)?.value?.toIntOrNull()
''',
    'guest RPC marker parser',
)
text = replace_once(
    text,
    '''                write("printf '%s' '$encoded' | base64 -d | /bin/bash; __vessel_rc=\\$?; printf '$marker:%s\\\\n' \\\"\\$__vessel_rc\\\"\\n")
''',
    '''                write("printf '%s' '$encoded' | base64 -d | /bin/bash; __vessel_rc=\\$?; printf '\\\\n$marker:%s\\\\n' \\\"\\$__vessel_rc\\\"\\n")
''',
    'guest RPC completion writer',
)

# Keep the pending console tail when a timeout happens.  This makes the next
# physical-device report show whether bash ran the command, whether a marker was
# mangled, or whether the guest really stopped scheduling the shell.
text = replace_once(
    text,
    '''        } catch (t: java.util.concurrent.TimeoutException) {
            append("[guest] command timeout after ${timeoutSeconds}s: ${command.lineSequence().firstOrNull()?.take(240) ?: "<empty>"}\\n")
            throw IllegalStateException("Guest command timed out after ${timeoutSeconds}s", t)
''',
    '''        } catch (t: java.util.concurrent.TimeoutException) {
            val buffered = synchronized(consoleLock) {
                if (pendingFuture === future) pendingOutput.takeLast(8000) else ""
            }
            append("[guest] command timeout after ${timeoutSeconds}s: ${command.lineSequence().firstOrNull()?.take(240) ?: "<empty>"}\\n")
            if (buffered.isNotBlank()) append("[guest] pending console output before timeout:\\n$buffered\\n")
            throw IllegalStateException("Guest command timed out after ${timeoutSeconds}s", t)
''',
    'guest RPC timeout diagnostics',
)

# Build the Wayland session and a real daemonizer inside the already-successful
# desktop preparation command.  This removes the extra nested-base64 RPC that
# timed out on the phone before KWin was even launched.  Python's Popen with
# start_new_session + DEVNULL + a file-backed stdout/stderr gives the Plasma
# session no inherited UML command-console fd, unlike nohup/setsid shell tricks.
text = replace_once(
    text,
    '''            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config
        """.trimIndent()
''',
    '''            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config
            cat >/usr/local/bin/vessel-plasma-session <<'VSESSION'
            #!/bin/bash
            set -e
            export XDG_RUNTIME_DIR=/run/user/${'$'}(id -u)
            export XDG_SESSION_TYPE=wayland XDG_SESSION_DESKTOP=KDE XDG_CURRENT_DESKTOP=KDE DESKTOP_SESSION=plasmawayland
            export XDG_SESSION_CLASS=user XDG_SEAT=seat0 XDG_VTNR=1 KDE_FULL_SESSION=true KDE_SESSION_VERSION=5
            export LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl
            export GDK_BACKEND=wayland QT_QPA_PLATFORM=wayland CLUTTER_BACKEND=wayland SDL_VIDEODRIVER=wayland
            export MOZ_ENABLE_WAYLAND=1 MOZ_WEBRENDER=1 MOZ_ACCELERATED=1
            unset DISPLAY MOZ_X11_EGL
            exec startplasma-wayland
            VSESSION
            chmod 0755 /usr/local/bin/vessel-plasma-session
            cat >/usr/local/lib/vessel/launch_wayland.py <<'VLAUNCH'
            #!/usr/bin/python3
            import os
            import subprocess
            import sys

            uid = subprocess.check_output(['/usr/bin/id', '-u', 'vessel'], text=True).strip()
            env_command = (
                f'XDG_RUNTIME_DIR=/run/user/{uid} XDG_SEAT=seat0 XDG_VTNR=1 '
                'exec dbus-run-session -- /usr/local/bin/vessel-plasma-session'
            )
            log = open('/tmp/vessel-plasma.log', 'ab', buffering=0)
            try:
                proc = subprocess.Popen(
                    ['/usr/bin/su', '-l', 'vessel', '-c', env_command],
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    cwd='/home/vessel',
                    close_fds=True,
                    start_new_session=True,
                )
            except Exception:
                log.close()
                raise
            print(f'VESSEL_WAYLAND_PID={proc.pid}', flush=True)
            VLAUNCH
            chmod 0755 /usr/local/lib/vessel/launch_wayland.py
        """.trimIndent()
''',
    'Wayland prep tail',
)

# Replace the v44 three-RPC session-script/cleanup/dispatch sequence.  Session
# installation now happens in prep, cleanup is bounded, and dispatch is a tiny
# command that invokes the fd-clean daemonizer.  Long timeouts are intentional
# on 1-vCPU UML but no background process can hold the command RPC open.
start = text.find('            val installSession = "printf')
end = text.find('            val check = guestBlocking(', start + 1) if start >= 0 else -1
if start < 0 or end < 0:
    raise SystemExit(f"Wayland launch region not found (start={start}, end={end})")
new_launch = r'''            val cleanup = "pkill -u vessel -x kwin_x11 2>/dev/null || true; pkill -u vessel -x kwin_wayland 2>/dev/null || true; pkill -u vessel -x plasmashell 2>/dev/null || true; pkill -x Xorg 2>/dev/null || true; rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 /run/user/\\$(id -u vessel)/wayland-*; : >/tmp/vessel-plasma.log; echo VESSEL_WAYLAND_CLEAN"
            val cleanupResult = guestBlocking(cleanup, 45)
            check(cleanupResult.first == 0 && cleanupResult.second.contains("VESSEL_WAYLAND_CLEAN")) { "Wayland cleanup failed: ${cleanupResult.second.takeLast(8000)}" }
            append("[desktop] Wayland stage 1/3: stale desktop state cleaned\\n")

            val dispatchResult = guestBlocking("/usr/bin/python3 /usr/local/lib/vessel/launch_wayland.py && echo VESSEL_WAYLAND_DISPATCHED", 45)
            check(dispatchResult.first == 0 && dispatchResult.second.contains("VESSEL_WAYLAND_DISPATCHED")) { "Plasma Wayland dispatch failed: ${dispatchResult.second.takeLast(10000)}" }
            append("[desktop] Wayland stage 2/3: fully detached Plasma session dispatched\\n")
'''
text = text[:start] + new_launch + text[end:]

# Give KWin/Plasma enough room on a single UML vCPU and accept any valid
# wayland-N socket.  If startup fails, dump the ConsoleKit broker, DRM nodes and
# the complete recent Plasma log in one error instead of returning a generic
# timeout.
old_check = '''            val check = guestBlocking("for i in \\$(seq 1 240); do pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null && break; sleep .1; done; pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null || { echo VESSEL_WAYLAND_DIAG; id vessel; ls -l /dev/dri 2>/dev/null; ls -ld /tmp/.X11-unix 2>/dev/null; dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null; cat /tmp/vessel-consolekit.log 2>/dev/null; tail -240 /tmp/vessel-plasma.log 2>/dev/null; exit 44; }; test -S /run/user/\\$(id -u vessel)/wayland-0 || { ls -la /run/user/\\$(id -u vessel); cat /tmp/vessel-consolekit.log 2>/dev/null; tail -240 /tmp/vessel-plasma.log; exit 46; }; echo VESSEL_WAYLAND_READY", 45)
'''
new_check = '''            val check = guestBlocking("ready=0; for i in \\$(seq 1 600); do if pgrep -u vessel -x kwin_wayland >/dev/null && pgrep -u vessel -x plasmashell >/dev/null && find /run/user/\\$(id -u vessel) -maxdepth 1 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q .; then ready=1; break; fi; sleep .1; done; test \\$ready -eq 1 || { echo VESSEL_WAYLAND_DIAG; id vessel; dpkg-query -W kwin-wayland kwin-common plasma-workspace-wayland 2>/dev/null; ls -l /dev/dri 2>/dev/null; ls -la /run/user/\\$(id -u vessel) 2>/dev/null; ls -ld /tmp/.X11-unix 2>/dev/null; dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null; cat /tmp/vessel-consolekit.log 2>/dev/null; tail -400 /tmp/vessel-plasma.log 2>/dev/null; exit 44; }; echo VESSEL_WAYLAND_READY", 75)
'''
text = replace_once(text, old_check, new_check, 'Wayland readiness probe')
text = replace_once(
    text,
    '            append("[desktop] native KWin Wayland + rootless Xwayland ready; Xorg/glamor retired\\n")',
    '            append("[desktop] Wayland stage 3/3: native KWin DRM + Plasma + Xwayland ready\\n")',
    'Wayland ready log',
)

text = text.replace('v44-wayland-detached-launch-r1', 'v46-bookworm-kwin-r1')
CONTROLLER.write_text(text)
if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace('v44-wayland-detached-launch-r1', 'v46-bookworm-kwin-r1'))

print('[alpha6.5] robust UML command markers + Bookworm-native KWin Wayland packaging + Python-detached bootstrap applied')
