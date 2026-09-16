#!/usr/bin/env python3
from __future__ import annotations

import base64
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


def replace_region(text: str, start: str, end: str, new: str, label: str) -> str:
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: region missing start={a} end={b}")
    return text[:a] + new + text[b:]


# The v50-v52 control-agent experiments were correct about moving post-boot
# commands away from tty0, but they made *starting* that helper a mandatory
# second tty RPC immediately before the already-proven Wayland launch. Physical
# logs consistently show audio finishing (__VESSEL_7__:0) and that next RPC
# never completing. Boot must not depend on an auxiliary control daemon.
#
# Prepare the helper and its connection parameters inside the already-proven
# audio bootstrap RPC, then launch the helper from the single authoritative
# Wayland prep transaction *after* KWin/Plasma is ready. util-linux `setsid -f`
# always forks; without `-w` its parent does not wait for the long-lived child.
# All stdio is redirected before exec, so the agent cannot keep the UML command
# pipe open. Agent launch is deliberately best-effort: a control-plane failure
# is diagnostics/UI degradation, never a reason to tear down a working desktop.
CONTROL_DISPATCH = r'''#!/bin/bash
set +e
cfg=/run/vessel/control-agent.env
pidfile=/run/vessel-control-agent.pid
agent=/usr/local/lib/vessel/control_agent.py
log=/tmp/vessel-control-agent.log

if [ ! -r "$cfg" ] || [ ! -x "$agent" ]; then
  echo VESSEL_CONTROL_AGENT_SKIPPED=not-prepared
  exit 0
fi
if ! command -v setsid >/dev/null 2>&1; then
  echo VESSEL_CONTROL_AGENT_SKIPPED=setsid-missing
  exit 0
fi

. "$cfg"
if [ -z "${VESSEL_CONTROL_PORT:-}" ] || [ -z "${VESSEL_CONTROL_TOKEN:-}" ]; then
  echo VESSEL_CONTROL_AGENT_SKIPPED=bad-config
  exit 0
fi

old_pid=$(cat "$pidfile" 2>/dev/null || true)
if [ -n "$old_pid" ] && [ -r "/proc/$old_pid/cmdline" ]; then
  if tr '\000' ' ' <"/proc/$old_pid/cmdline" 2>/dev/null | grep -Fq "$agent"; then
    kill "$old_pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$old_pid" 2>/dev/null || break
      sleep .05
    done
    kill -KILL "$old_pid" 2>/dev/null || true
  fi
fi
rm -f "$pidfile"
: >"$log"

# -f always forks. We intentionally do NOT pass -w, so setsid itself returns
# immediately while the root control agent lives in a separate session.
setsid -f /usr/bin/python3 "$agent" \
  "${VESSEL_CONTROL_HOST:-10.0.2.2}" "$VESSEL_CONTROL_PORT" "$VESSEL_CONTROL_TOKEN" \
  </dev/null >>"$log" 2>&1
spawn_rc=$?
if [ "$spawn_rc" -eq 0 ]; then
  echo VESSEL_CONTROL_AGENT_DISPATCHED
else
  echo VESSEL_CONTROL_AGENT_SKIPPED=setsid-rc-$spawn_rc
fi
exit 0
'''
CONTROL_DISPATCH_B64 = base64.b64encode(CONTROL_DISPATCH.encode()).decode()

text = CONTROLLER.read_text()

services = r'''    private fun setupGuestServicesBlocking() {
        val audioPort = VesselAudioBridge.port()
        check(audioPort > 0) { "Android audio bridge did not start" }
        val audioHelper = context.assets.open("vessel/guest_audio_pipe.py").use { it.readBytes() }
        val audioHelperB64 = Base64.getEncoder().encodeToString(audioHelper)

        val controlPort = VesselGuestAgent.port()
        check(controlPort > 0) { "Android guest-control server did not start" }
        val controlHelper = context.assets.open("vessel/guest_control_agent.py").use { it.readBytes() }
        val controlHelperB64 = Base64.getEncoder().encodeToString(controlHelper)
        val token = VesselGuestAgent.token()

        // One proven boot-tty transaction installs both service bridges. It
        // starts no long-lived process, so it cannot hold the completion marker.
        val command = """
            set -e
            install -d -m 755 /usr/local/lib/vessel
            printf '%s' '$audioHelperB64' | base64 -d >/usr/local/lib/vessel/audio_pipe.py
            chmod 0755 /usr/local/lib/vessel/audio_pipe.py
            cat >/etc/asound.conf <<'VESSEL_ASOUND'
            pcm.vessel_raw {
                type file
                slave.pcm "null"
                file "|/usr/bin/python3 /usr/local/lib/vessel/audio_pipe.py $audioPort 48000 2 16 S16_LE"
                format "raw"
            }
            pcm.vessel {
                type plug
                slave {
                    pcm "vessel_raw"
                    format S16_LE
                    rate 48000
                    channels 2
                }
            }
            pcm.!default {
                type plug
                slave.pcm "vessel"
            }
            ctl.!default {
                type hw
                card 0
            }
            VESSEL_ASOUND
            install -d -m 700 -o vessel -g vessel /home/vessel/.config/pulse /home/vessel/.config/autostart
            cat >/home/vessel/.config/pulse/default.pa <<'VESSEL_PULSE'
            .include /etc/pulse/default.pa
            load-module module-alsa-sink device=vessel sink_name=vessel sink_properties=device.description=Vessel_Android_Audio
            set-default-sink vessel
            VESSEL_PULSE
            cat >/home/vessel/.config/autostart/vessel-audio.desktop <<'VESSEL_AUDIO_DESKTOP'
            [Desktop Entry]
            Type=Application
            Name=Vessel Android Audio
            Exec=/bin/sh -lc 'pulseaudio --start --exit-idle-time=-1'
            OnlyShowIn=KDE;
            X-KDE-autostart-after=panel
            VESSEL_AUDIO_DESKTOP
            chown -R vessel:vessel /home/vessel/.config/pulse /home/vessel/.config/autostart
            echo VESSEL_AUDIO_READY

            printf '%s' '$controlHelperB64' | base64 -d >/usr/local/lib/vessel/control_agent.py
            chmod 0755 /usr/local/lib/vessel/control_agent.py
            install -d -m 755 /run/vessel
            cat >/run/vessel/control-agent.env <<'VESSEL_CONTROL_ENV'
            VESSEL_CONTROL_HOST=10.0.2.2
            VESSEL_CONTROL_PORT=$controlPort
            VESSEL_CONTROL_TOKEN='$token'
            VESSEL_CONTROL_ENV
            chmod 0600 /run/vessel/control-agent.env
            rm -f /run/vessel-control-agent.pid
            command -v setsid >/dev/null 2>&1 || echo VESSEL_CONTROL_WARNING=setsid-missing
            echo VESSEL_CONTROL_AGENT_PREPARED
            echo VESSEL_BOOT_SERVICES_READY
        """.trimIndent()
        val result = guestBlocking(command, 45)
        check(result.first == 0 && result.second.contains("VESSEL_BOOT_SERVICES_READY")) {
            "Could not configure guest services: ${result.second.takeLast(7000)}"
        }
        append("[audio] guest PulseAudio/ALSA -> Android AudioTrack bridge configured port=$audioPort\\n")
        append("[control] helper/config prepared port=$controlPort; launch deferred to authoritative Wayland transaction\\n")
    }

'''
text = replace_region(
    text,
    "    private fun setupGuestAudioBlocking() {\n",
    "    private fun displayModeCommand(): String {\n",
    services,
    "replace blocking audio/control bootstrap",
)

# Append a nonblocking agent dispatch to the same single RPC that already owns
# Wayland startup. This runs after whichever postprep script (built-in or hot)
# reports KWin/Plasma ready, so stale hot-runtime bundles cannot reintroduce the
# separate-RPC timeout.
old_post = '''            val postB64 = Base64.getEncoder().encodeToString(effectivePostPrep.toByteArray())
            preparedDesktop += "\\nprintf '%s' '$postB64' | base64 -d | /bin/bash"
            waylandReadyInPrep = true
'''
new_post = '''            val postB64 = Base64.getEncoder().encodeToString(effectivePostPrep.toByteArray())
            preparedDesktop += "\\nprintf '%s' '$postB64' | base64 -d | /bin/bash"
            preparedDesktop += "\\nprintf '%s' '__CONTROL_DISPATCH_B64__' | base64 -d | /bin/bash"
            waylandReadyInPrep = true
'''.replace("__CONTROL_DISPATCH_B64__", CONTROL_DISPATCH_B64)
text = replace_once(text, old_post, new_post, "fold nonblocking control dispatch into Wayland RPC")

old_sequence = '''            ensurePlasma()
            setupGuestAudioBlocking()
            startGuestControlAgentBlocking()
            launchDesktop()
            useGuestAgent = true
            append("[control] post-boot RPC switched from UML tty to ${VesselGuestAgent.status()}\\n")
            progress("frame", 88, "Waiting for Android native Surface frame")
'''
new_sequence = '''            ensurePlasma()
            setupGuestServicesBlocking()
            launchDesktop()
            // From this point forward tty0 is never used for app/terminal/stats
            // commands. The agent reconnects independently and cannot fail boot.
            useGuestAgent = true
            if (VesselGuestAgent.waitUntilConnected(8_000)) {
                append("[control] persistent Debian control agent connected; post-boot RPC left tty0 permanently\\n")
            } else {
                append("[control] desktop is ready but agent is still reconnecting (${VesselGuestAgent.status()}); boot continues and post-boot calls stay off tty0\\n")
            }
            progress("frame", 88, "Waiting for Android native Surface frame")
'''
text = replace_once(text, old_sequence, new_sequence, "nonblocking post-Wayland control activation")

text = text.replace("v52-posix-spawn-control-agent-r1", "v53-nonblocking-control-plane-r1")
CONTROLLER.write_text(text)

if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace(
        "v52-posix-spawn-control-agent-r1",
        "v53-nonblocking-control-plane-r1",
    ))

print("[alpha13] control agent prepared with audio, detached by setsid -f inside authoritative Wayland RPC; boot no longer depends on control plane")
