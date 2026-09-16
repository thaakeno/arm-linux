#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
ACTIVITY = ROOT / "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# v57 physical-device recovery.
#
# 1a0d1ec was the only physically proven full Wayland boot. Its crucial
# property was not a particular timeout value: after Plasma package validation,
# Android sent ONE final authoritative tty0 RPC containing desktop prep,
# hot-runtime staging, compositor launch and the readiness probe. Once that RPC
# returned VESSEL_WAYLAND_READY, tty0 was never used again.
#
# Alpha13 accidentally left guest audio/control preparation as a separate tty
# RPC immediately before launchDesktop(), and the old Alpha15 tried to work
# around the resulting 72% stall by sending four *more* asset-staging RPCs.
# Physical logs now prove the first of those follow-up RPCs itself hangs after
# __VESSEL_7__:0. Stop trying to make follow-up tty commands reliable. Fold the
# service preparation into the same preparedDesktop command that already owns
# the proven Wayland startup transaction. There is then exactly one tty RPC
# after ensurePlasma() on the Wayland path.
# ---------------------------------------------------------------------------
controller = CONTROLLER.read_text()

# Turn Alpha13's executing helper into a pure command builder. This keeps all
# dynamic Android-side values (AudioTrack port, control port/token and packaged
# helper assets), but does not touch tty0 by itself.
controller = replace_once(
    controller,
    "    private fun setupGuestServicesBlocking() {\n",
    "    private fun guestServicesCommand(): String {\n",
    "service helper becomes command builder",
)

old_service_tail = '''        val result = guestBlocking(command, 45)
        check(result.first == 0 && result.second.contains("VESSEL_BOOT_SERVICES_READY")) {
            "Could not configure guest services: ${result.second.takeLast(7000)}"
        }
        append("[audio] guest PulseAudio/ALSA -> Android AudioTrack bridge configured port=$audioPort\\n")
        append("[control] helper/config prepared port=$controlPort; launch deferred to authoritative Wayland transaction\\n")
    }

'''
new_service_tail = '''        return command
    }

    // X11 is an experimental fallback and still has a usable boot tty after
    // launch. Keep its old service setup behavior without weakening Wayland's
    // strict one-final-RPC invariant.
    private fun setupGuestServicesBlocking() {
        val audioPort = VesselAudioBridge.port()
        val controlPort = VesselGuestAgent.port()
        val result = guestBlocking(guestServicesCommand(), 45)
        check(result.first == 0 && result.second.contains("VESSEL_BOOT_SERVICES_READY")) {
            "Could not configure guest services: ${result.second.takeLast(7000)}"
        }
        append("[audio] guest PulseAudio/ALSA -> Android AudioTrack bridge configured port=$audioPort\\n")
        append("[control] helper/config prepared port=$controlPort\\n")
    }

'''
controller = replace_once(
    controller,
    old_service_tail,
    new_service_tail,
    "service helper return instead of tty RPC",
)

# Alpha9/14 already build preparedDesktop as the single authoritative Wayland
# transaction. Put audio/control preparation at the front of that SAME command.
# The hot-runtime postprep and Alpha13's detached control-agent dispatch remain
# later in preparedDesktop, so the control helper is configured before dispatch.
controller = replace_once(
    controller,
    "        var preparedDesktop = prep\n",
    '''        var preparedDesktop = prep
        if (backend == "wayland") {
            preparedDesktop += "\\n" + guestServicesCommand()
            append("[desktop] guest services folded into final authoritative tty RPC\\n")
        }
''',
    "fold services into authoritative desktop RPC",
)

# Wayland must no longer execute service setup as command #7 and desktop as
# command #8. X11 keeps the legacy separate service command.
controller = replace_once(
    controller,
    '''            ensurePlasma()
            setupGuestServicesBlocking()
            launchDesktop()
''',
    '''            ensurePlasma()
            if (VesselExperimentConfig.desktopBackend(context) == "x11") {
                setupGuestServicesBlocking()
            }
            launchDesktop()
''',
    "remove separate pre-Wayland service RPC",
)

# Keep the user-visible progress moving during the one long, correct transaction
# instead of looking frozen at 72%. These are observation-only callbacks; they
# do not create additional guest commands.
controller = replace_once(
    controller,
    '''        append("[desktop] authoritative single-RPC prep + Wayland bootstrap\\n")
        val prepResult = guestBlocking(preparedDesktop, 120)
''',
    '''        append("[desktop] v57 authoritative single-RPC services + prep + Wayland bootstrap\\n")
        val prepResult = guestBlocking(preparedDesktop, 150) { raw ->
            when {
                raw.contains("VESSEL_AUDIO_READY") ->
                    progress("desktop_services", 73, "Linux audio bridge prepared")
                raw.contains("VESSEL_CONTROL_AGENT_PREPARED") ->
                    progress("desktop_control", 74, "Linux control service prepared")
                raw.contains("VESSEL_PREP_STAGE=profile-ready") ->
                    progress("desktop_profile", 75, "Desktop profile ready")
                raw.contains("VESSEL_PREP_STAGE=udevd-ready") ->
                    progress("desktop_devices", 76, "Desktop device service ready")
                raw.contains("VESSEL_PREP_STAGE=udev-ready") ->
                    progress("desktop_devices", 77, "Desktop devices ready")
                raw.contains("VESSEL_PREP_STAGE=dbus-ready") ->
                    progress("desktop_dbus", 79, "Wayland system bus ready")
                raw.contains("VESSEL_PREP_STAGE=consolekit-ready") ->
                    progress("desktop_session", 81, "DRM session broker ready")
                raw.contains("VESSEL_WAYLAND_BOOTSTRAP_BEGIN") ->
                    progress("desktop_launch", 83, "Launching KWin Wayland")
                raw.contains("VESSEL_CONTROL_AGENT_DISPATCHED") ->
                    progress("desktop_control_live", 87, "Desktop control service starting")
                raw.contains("VESSEL_WAYLAND_READY") ->
                    progress("desktop_ready", 88, "Plasma Wayland ready")
            }
        }
''',
    "live progress inside authoritative RPC",
)

controller = controller.replace("v55-restored-single-rpc-r1", "v57-one-final-tty-rpc-r1")
CONTROLLER.write_text(controller)

service = SERVICE.read_text()
service = service.replace("v55-restored-single-rpc-r1", "v57-one-final-tty-rpc-r1")
SERVICE.write_text(service)

# Restore elapsed setup/running time. uptimeMs remained in runtime state; only
# its rendering disappeared from the cards during UI polish.
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

# Build-time physical-architecture invariants. Fail CI before Gradle if a later
# patch ever reintroduces the exact ping-pong regression.
final_controller = CONTROLLER.read_text()
required = [
    'preparedDesktop += "\\n" + guestServicesCommand()',
    'v57 authoritative single-RPC services + prep + Wayland bootstrap',
    'val prepResult = guestBlocking(preparedDesktop, 150)',
    'VESSEL_WAYLAND_READY',
]
for needle in required:
    if needle not in final_controller:
        raise SystemExit(f"v57 invariant missing: {needle}")
for forbidden in (
    "stageWaylandAsset(",
    "launchStableWayland(",
    "v56 tty-safe Wayland bootstrap",
):
    if forbidden in final_controller:
        raise SystemExit(f"v57 forbidden post-service tty path survived: {forbidden}")

wayland_sequence = '''            ensurePlasma()
            if (VesselExperimentConfig.desktopBackend(context) == "x11") {
                setupGuestServicesBlocking()
            }
            launchDesktop()
'''
if wayland_sequence not in final_controller:
    raise SystemExit("v57 startup sequence invariant missing")

if "VesselGuestAgent.waitUntilConnected(8_000)" not in SERVICE.read_text():
    raise SystemExit("Apps/control transport invariant missing")

print("[alpha15] v57: audio/control + desktop prep + Wayland readiness share ONE final tty RPC; no staging RPCs; live progress/timer preserved")
