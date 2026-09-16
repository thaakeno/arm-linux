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


def replace_region(text: str, start: str, end: str, new: str, label: str, search_from: int = 0) -> str:
    a = text.find(start, search_from)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: region missing (start={a}, end={b})")
    return text[:a] + new + text[b:]


text = CONTROLLER.read_text()

# Alpha7 fixes the physical-device v46 failure at the source: no generated
# Kotlin command is allowed to carry an escaped shell command-substitution into
# bash.  The default cleanup uses a bounded find over /run/user instead.
text = text.replace("v46-bookworm-kwin-r1", "v47-live-runtime-updater-r1")

cleanup_start = '            val cleanup = "pkill -u vessel -x kwin_x11'
cleanup_end = '            val cleanupResult = guestBlocking(cleanup, 45)\n'
a = text.find(cleanup_start)
b = text.find(cleanup_end, a + 1) if a >= 0 else -1
if a < 0 or b < 0:
    raise SystemExit(f"alpha7 cleanup region not found (start={a}, end={b})")
cleanup_new = r'''            val cleanup = VesselUpdateManager.runtimeScript(context, "wayland-cleanup.sh", """
                #!/bin/bash
                set -eu
                pkill -u vessel -x kwin_x11 2>/dev/null || true
                pkill -u vessel -x kwin_wayland 2>/dev/null || true
                pkill -u vessel -x plasmashell 2>/dev/null || true
                pkill -x Xorg 2>/dev/null || true
                rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
                find /run/user -maxdepth 2 -type s -name 'wayland-*' -delete 2>/dev/null || true
                : >/tmp/vessel-plasma.log
                echo VESSEL_WAYLAND_CLEAN
            """.trimIndent())
'''
text = text[:a] + cleanup_new + text[b:]

# Install optional live overrides after the known-good prep command.  The APK's
# built-in scripts always remain as fallback, and every hot file is supplied by
# VesselUpdateManager's allow-listed, checksum-verified atomic runtime bundle.
prep_anchor = '''        check(prepResult.first == 0) { "desktop prep failed: ${prepResult.second.takeLast(8000)}" }

        if (backend == "wayland") {
'''
prep_replacement = r'''        check(prepResult.first == 0) { "desktop prep failed: ${prepResult.second.takeLast(8000)}" }

        if (backend == "wayland") {
            val liveFiles = listOf(
                "wayland-session.sh" to "/usr/local/bin/vessel-plasma-session",
                "launch-wayland.py" to "/usr/local/lib/vessel/launch_wayland.py",
            )
            for ((liveName, guestPath) in liveFiles) {
                val liveText = VesselUpdateManager.runtimeScriptOrNull(context, liveName) ?: continue
                val liveB64 = Base64.getEncoder().encodeToString(liveText.toByteArray())
                val installLive = guestBlocking("printf '%s' '$liveB64' | base64 -d >'$guestPath'; chmod 0755 '$guestPath'", 30)
                check(installLive.first == 0) { "Live runtime install failed for $liveName: ${installLive.second.takeLast(6000)}" }
                append("[update] live runtime override installed: $liveName\\n")
            }
            VesselUpdateManager.runtimeScriptOrNull(context, "wayland-postprep.sh")?.let { postPrep ->
                val post = guestBlocking(postPrep, 45)
                check(post.first == 0) { "Live Wayland post-prep failed: ${post.second.takeLast(8000)}" }
            }
'''
text = replace_once(text, prep_anchor, prep_replacement, "alpha7 live override insertion")

# Replace the generated readiness command as well.  It contains no Kotlin-level
# shell escaping and can itself be changed through the hot-runtime channel.
dispatch_at = text.find('            val dispatchResult = guestBlocking(')
check_at = text.find('            val check = guestBlocking(', dispatch_at + 1) if dispatch_at >= 0 else -1
check_end = text.find('            check(check.first == 0)', check_at + 1) if check_at >= 0 else -1
if dispatch_at < 0 or check_at < 0 or check_end < 0:
    raise SystemExit(f"alpha7 readiness region not found ({dispatch_at}, {check_at}, {check_end})")
check_new = r'''            val checkCommand = VesselUpdateManager.runtimeScript(context, "wayland-check.sh", """
                #!/bin/bash
                for i in $(seq 1 600); do
                  if pgrep -u vessel -x kwin_wayland >/dev/null && pgrep -u vessel -x plasmashell >/dev/null && find /run/user -maxdepth 2 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q .; then
                    echo VESSEL_WAYLAND_READY
                    exit 0
                  fi
                  sleep .1
                done
                echo VESSEL_WAYLAND_DIAG
                id vessel || true
                dpkg-query -W kwin-wayland kwin-common plasma-workspace-wayland 2>/dev/null || true
                ls -l /dev/dri 2>/dev/null || true
                ls -la /run/user/* 2>/dev/null || true
                ls -ld /tmp/.X11-unix 2>/dev/null || true
                dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null || true
                cat /tmp/vessel-consolekit.log 2>/dev/null || true
                tail -400 /tmp/vessel-plasma.log 2>/dev/null || true
                exit 44
            """.trimIndent())
            val check = guestBlocking(checkCommand, 75)
'''
text = text[:check_at] + check_new + text[check_end:]

# Surface which hot runtime is active in diagnostics/status JSON.
base_anchor = '        .put("runtimeRevision", REVISION)\n'
text = replace_once(
    text,
    base_anchor,
    base_anchor + '        .put("hotRuntimeRevision", VesselUpdateManager.currentRuntimeRevision(context))\n',
    "alpha7 status hot runtime",
)
CONTROLLER.write_text(text)

if SERVICE.exists():
    service_text = SERVICE.read_text().replace("v46-bookworm-kwin-r1", "v47-live-runtime-updater-r1")
    SERVICE.write_text(service_text)

activity = ACTIVITY.read_text()
activity = replace_once(
    activity,
    'import androidx.compose.runtime.remember\n',
    'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n',
    "activity coroutine scope import",
)
activity = replace_once(
    activity,
    'import kotlinx.coroutines.delay\n',
    'import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch\n',
    "activity launch import",
)
activity = replace_once(
    activity,
    '''        VesselHostDebug.initialize(this)
        startForegroundService(Intent(this, VmSessionService::class.java))
''',
    '''        VesselHostDebug.initialize(this)
        VesselUpdateManager.initialize(this)
        startForegroundService(Intent(this, VmSessionService::class.java))
''',
    "activity updater init",
)
activity = replace_once(
    activity,
    '''        VmSessionService.active?.refreshAvailability()
    }

    override fun onWindowFocusChanged''',
    '''        VmSessionService.active?.refreshAvailability()
        VesselUpdateManager.resumePendingInstall(this)
    }

    override fun onWindowFocusChanged''',
    "activity pending installer resume",
)
activity = replace_once(
    activity,
    '''    private fun SystemPage(state: SessionState) {
        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()
        LaunchedEffect(state.guestReady, state.running) { VmSessionService.active?.refreshSystemStats() }
''',
    '''    private fun SystemPage(state: SessionState) {
        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()
        val updates by VesselUpdateManager.state.collectAsStateWithLifecycle()
        val updateScope = rememberCoroutineScope()
        LaunchedEffect(state.guestReady, state.running) { VmSessionService.active?.refreshSystemStats() }
''',
    "system page updater state",
)
update_card_anchor = '''            Button(onClick = { VmSessionService.active?.runGpuDiagnostics() }, enabled = state.guestReady && !state.busy) { Text("Run GPU + input diagnostics") }
'''
update_card = r'''            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Updates", fontWeight = FontWeight.SemiBold)
                    Metric(Icons.Default.Bolt, "App", "${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_COMMIT}")
                    Metric(Icons.Default.Terminal, "Live runtime", updates.runtimeRevision)
                    if (updates.busy) LinearProgressIndicator(progress = { updates.progressPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(updates.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { updateScope.launch { runCatching { VesselUpdateManager.checkRuntimeUpdate(this@VesselActivity) } } },
                            enabled = !updates.busy,
                        ) { Text("Update runtime") }
                        OutlinedButton(
                            onClick = { updateScope.launch { runCatching { VesselUpdateManager.checkAndInstallAppUpdate(this@VesselActivity) } } },
                            enabled = !updates.busy,
                        ) { Text("Update app") }
                        OutlinedButton(
                            onClick = { updateScope.launch { runCatching { VesselUpdateManager.rollbackRuntime(this@VesselActivity) } } },
                            enabled = !updates.busy && updates.canRollback,
                        ) { Text("Rollback runtime") }
                    }
                    Text("Runtime updates replace only allow-listed Wayland bootstrap scripts and apply on the next Linux start. Kotlin/native changes use the Android APK updater.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { VmSessionService.active?.runGpuDiagnostics() }, enabled = state.guestReady && !state.busy) { Text("Run GPU + input diagnostics") }
'''
activity = replace_once(activity, update_card_anchor, update_card, "system update card")
ACTIVITY.write_text(activity)

print("[alpha7] fixed Wayland cleanup escaping + atomic live runtime + in-app updater UI applied")
