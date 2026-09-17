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


# v57 physical-device recovery.
#
# 1a0d1ec was the only physically proven full Wayland boot. Its important
# invariant was ONE final tty0 transaction after package validation: prep,
# runtime staging, KWin/Plasma launch and VESSEL_WAYLAND_READY all happened in
# that same transaction. Once it returned, tty0 was retired.
#
# Alpha13 later introduced a separate audio/control tty RPC before desktop
# launch. Old Alpha15 then introduced four more tty RPCs to stage boot files.
# Physical run 172 proved the first RPC after the service transaction can hang
# forever even though __VESSEL_7__:0 was returned. Therefore do not try to make
# follow-up tty commands more patient: remove them. Audio/control preparation is
# folded into Alpha9/14's already-authoritative preparedDesktop command, leaving
# exactly one final Wayland tty RPC.
controller = CONTROLLER.read_text()

# Alpha13 generated setupGuestServicesBlocking(). Convert it into a pure command
# builder by replacing everything from its guestBlocking execution through the
# next method boundary. Use positional anchors instead of escaped log strings so
# generator changes cannot silently break this finalizer.
controller = replace_once(
    controller,
    "    private fun setupGuestServicesBlocking() {\n",
    "    private fun guestServicesCommand(): String {\n",
    "service helper becomes command builder",
)
method_start = controller.find("    private fun guestServicesCommand(): String {\n")
exec_start = controller.find("        val result = guestBlocking(command,", method_start)
next_method = controller.find("    private fun displayModeCommand(): String {\n", exec_start)
if method_start < 0 or exec_start < 0 or next_method < 0:
    raise SystemExit(
        f"service helper region missing method={method_start} exec={exec_start} next={next_method}"
    )
service_tail = '''        return command
    }

    // X11 is an experimental fallback. It does not use the strict Wayland
    // one-final-RPC path, so retain a blocking service setup helper for it.
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
controller = controller[:exec_start] + service_tail + controller[next_method:]

# Alpha9/14 already construct preparedDesktop and append the hot Wayland
# overrides, authoritative post-prep readiness probe and Alpha13's detached
# control-agent dispatch. Put guest services at the FRONT of that same command.
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

# Wayland must no longer execute a standalone service command before desktop.
# X11 keeps the compatibility helper above.
controller = replace_once(
    controller,
    "            setupGuestServicesBlocking()\n            launchDesktop()\n",
    '''            if (VesselExperimentConfig.desktopBackend(context) == "x11") {
                setupGuestServicesBlocking()
            }
            launchDesktop()
''',
    "remove separate pre-Wayland service RPC",
)

# Keep progress alive while the single final command runs. This callback only
# observes stdout from the existing transaction; it never writes another tty
# command. 150 s is a watchdog for the whole desktop bootstrap, not a workaround
# for additional RPCs.
progress_call = '''        append("[desktop] v57 authoritative ONE final tty RPC: services + prep + Wayland readiness\\n")
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
'''
controller = replace_once(
    controller,
    "        val prepResult = guestBlocking(preparedDesktop, 120)\n",
    progress_call,
    "observe the one authoritative Wayland RPC",
)

controller = controller.replace("v55-restored-single-rpc-r1", "v57-one-final-tty-rpc-r1")
CONTROLLER.write_text(controller)

service = SERVICE.read_text()
service = service.replace("v55-restored-single-rpc-r1", "v57-one-final-tty-rpc-r1")
SERVICE.write_text(service)

# Restore elapsed setup/running time. uptimeMs stayed in runtime state; UI polish
# only stopped rendering it. The old Alpha15 restoration was physically visible
# in run 172, so keep the exact proven UI change.
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

# CI-time architecture invariants. These deliberately fail the native rebuild
# before Gradle if another patch reintroduces the ping-pong TTY design.
final_controller = CONTROLLER.read_text()
required = (
    'private fun guestServicesCommand(): String',
    'preparedDesktop += "\\n" + guestServicesCommand()',
    'v57 authoritative ONE final tty RPC',
    'val prepResult = guestBlocking(preparedDesktop, 150)',
    'VESSEL_WAYLAND_READY',
)
for needle in required:
    if needle not in final_controller:
        raise SystemExit(f"v57 invariant missing: {needle}")
for forbidden in (
    "stageWaylandAsset(",
    "launchStableWayland(",
    "v56 tty-safe Wayland bootstrap",
):
    if forbidden in final_controller:
        raise SystemExit(f"v57 forbidden follow-up tty path survived: {forbidden}")

startup_anchor = '''            ensurePlasma()
            if (VesselExperimentConfig.desktopBackend(context) == "x11") {
                setupGuestServicesBlocking()
            }
            launchDesktop()
'''
if startup_anchor not in final_controller:
    raise SystemExit("v57 startup sequence invariant missing")

# There must be no unconditional service RPC in the startup sequence anymore.
start_idx = final_controller.find("            ensurePlasma()\n")
launch_idx = final_controller.find("            launchDesktop()\n", start_idx)
if start_idx < 0 or launch_idx < 0:
    raise SystemExit("v57 could not locate startup sequence")
startup_slice = final_controller[start_idx:launch_idx]
if "setupGuestServicesBlocking()" in startup_slice and 'desktopBackend(context) == "x11"' not in startup_slice:
    raise SystemExit("v57 Wayland still has a standalone pre-desktop service RPC")

if "VesselGuestAgent.waitUntilConnected(8_000)" not in SERVICE.read_text():
    raise SystemExit("Apps/control transport invariant missing")

print("[alpha15] v57: services + prep + Wayland readiness share ONE final tty RPC; no staging RPCs; Apps handoff + elapsed timer preserved")
