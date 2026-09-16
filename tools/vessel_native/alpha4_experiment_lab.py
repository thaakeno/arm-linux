#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]


def patch_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one patch anchor, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


def patch_all(rel: str, old: str, new: str, minimum: int = 1) -> None:
    path = ROOT / rel
    text = path.read_text()
    if new in text and old not in text:
        return
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{rel}: expected at least {minimum} patch anchors, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new))


# Runtime-selectable experiment settings. All settings are intentionally local,
# persistent and reversible so one APK can A/B the phone instead of rebuilding
# for every theory.
config = ROOT / "app/src/main/java/com/example/dreamlinux/VesselExperimentConfig.kt"
config.write_text(r'''package com.example.dreamlinux

import android.content.Context

object VesselExperimentConfig {
    private const val PREFS = "vessel_experiment_lab_v1"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun vcpus(context: Context): Int = prefs(context).getInt("vcpus", 1).let { if (it in listOf(1, 2, 4, 6)) it else 1 }
    fun setVcpus(context: Context, value: Int) { prefs(context).edit().putInt("vcpus", value.coerceIn(1, 6)).apply() }

    fun memoryMb(context: Context): Int = prefs(context).getInt("memory_mb", 0).let { if (it in listOf(0, 2048, 3072, 4096)) it else 0 }
    fun setMemoryMb(context: Context, value: Int) { prefs(context).edit().putInt("memory_mb", value).apply() }

    fun refreshHz(context: Context): Int = prefs(context).getInt("refresh_hz", 60).let { if (it in listOf(60, 90, 120)) it else 60 }
    fun setRefreshHz(context: Context, value: Int) { prefs(context).edit().putInt("refresh_hz", value).apply() }

    fun resolutionPercent(context: Context): Int = prefs(context).getInt("resolution_percent", 67).let { if (it in listOf(67, 83, 100)) it else 67 }
    fun setResolutionPercent(context: Context, value: Int) { prefs(context).edit().putInt("resolution_percent", value).apply() }

    // Current AHB scanout is vertically inverted on the physical device.
    // Keep the fix enabled by default but expose the switch so the phone can
    // prove the orientation instead of baking another guess into the renderer.
    fun flipDisplayY(context: Context): Boolean = prefs(context).getBoolean("flip_display_y", true)
    fun setFlipDisplayY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("flip_display_y", value).apply() }

    // Normally false after the display flip. This is independent so cursor / input
    // semantics can be A/B tested if the virtio-input coordinate convention differs.
    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)
    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }

    fun reset(context: Context) { prefs(context).edit().clear().apply() }
}
''')

controller = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
patch_once(
    controller,
    '''        ((totalMb * 3) / 10).coerceIn(2048, 4096)\n    }\n\n    private val umlBin get() = File(nativeDir, "libvessel_uml.so")\n''',
    '''        val automatic = ((totalMb * 3) / 10).coerceIn(2048, 4096)\n        VesselExperimentConfig.memoryMb(context).takeIf { it > 0 } ?: automatic\n    }\n\n    val selectedVcpus: Int\n        get() = VesselExperimentConfig.vcpus(context)\n\n    private val umlBin get() = File(nativeDir, "libvessel_uml.so")\n''',
)
patch_once(controller, '.put("vcpus", UML_VCPUS)', '.put("vcpus", selectedVcpus)')
patch_all(controller, '$UML_VCPUS vCPUs', '$selectedVcpus vCPUs')
patch_all(controller, '$UML_VCPUS vCPU', '$selectedVcpus vCPU')
patch_once(controller, '"mem=${guestMemoryMb}M", "ncpus=$UML_VCPUS", "seccomp=on",', '"mem=${guestMemoryMb}M", "ncpus=$selectedVcpus", "seccomp=on",')
patch_once(
    controller,
    '''        pb.environment()["RUST_LOG"] = "info"\n        val p = pb.start()\n''',
    '''        pb.environment()["RUST_LOG"] = "info"\n        pb.environment()["VESSEL_FLIP_Y"] = if (VesselExperimentConfig.flipDisplayY(context)) "1" else "0"\n        val p = pb.start()\n''',
)

service = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
patch_once(
    service,
    '''        val targetWidth = min(physicalLong, 1920)\n        val targetHeight = ((physicalShort.toDouble() * targetWidth / physicalLong)\n            .roundToInt().coerceAtLeast(720) / 2) * 2\n        guestWidth = (targetWidth / 8) * 8\n        guestHeight = targetHeight\n        guestDpi = 120\n        guestRefresh = (mode?.refreshRate ?: 60f).coerceIn(60f, 120f)\n''',
    '''        val resolutionPercent = VesselExperimentConfig.resolutionPercent(this)\n        val targetWidth = ((min(physicalLong, 1920) * resolutionPercent) / 100).coerceAtLeast(640)\n        val targetHeight = ((physicalShort.toDouble() * targetWidth / physicalLong)\n            .roundToInt().coerceAtLeast(480) / 2) * 2\n        guestWidth = (targetWidth / 8) * 8\n        guestHeight = targetHeight\n        guestDpi = 120\n        val requestedRefresh = VesselExperimentConfig.refreshHz(this).toFloat()\n        guestRefresh = min(mode?.refreshRate ?: 60f, requestedRefresh).coerceAtLeast(30f)\n''',
)
patch_once(service, 'vcpus = VesselRuntimeController.UML_VCPUS,', 'vcpus = runtime.selectedVcpus,')
patch_once(
    service,
    '''                applyState(started)\n                ensureWorkstation(op)\n''',
    '''                applyState(started)\n                // The Linux machine is usable as soon as the guest shell is ready.\n                // Plasma validation/repair is post-boot work and must not lock the\n                // Terminal or Apps tabs behind one global UI busy bit.\n                state.value = state.value.copy(\n                    busy = false,\n                    stage = "postboot_setup",\n                    progressDetail = "Linux ready · validating desktop extras in background",\n                    message = "Linux ready",\n                )\n                ensureWorkstation(op)\n''',
)
patch_once(
    service,
    '''    fun runGuestCommand(command: String) {\n        if (state.value.busy || !state.value.running || !state.value.guestReady || command.isBlank()) return\n        state.value = state.value.copy(busy = true, terminalOutput = state.value.terminalOutput + "\\n$ $command\\n")\n        scope.launch(Dispatchers.IO) {\n''',
    '''    fun runGuestCommand(command: String) {\n        if (!state.value.running || !state.value.guestReady || command.isBlank()) return\n        state.value = state.value.copy(terminalOutput = state.value.terminalOutput + "\\n$ $command\\n")\n        scope.launch(Dispatchers.IO) {\n''',
)
patch_once(
    service,
    '''            state.value = state.value.copy(busy = false)\n        }\n    }\n\n    fun runGpuDiagnostics()''',
    '''        }\n    }\n\n    fun runGpuDiagnostics()''',
)
patch_once(
    service,
    '''        if (state.value.busy) {\n            appStore.value = appStore.value.copy(loading = false, error = "Wait for the current Debian operation to finish")\n            return\n        }\n''',
    '''        // Discovery uses the controller's serialized guest command channel,\n        // so it can safely queue behind post-boot work without disabling the UI.\n''',
)
patch_once(service, 'assets.open("vessel/app_discovery.py")', 'assets.open("vessel/app_discovery_fast.py")')
patch_once(
    service,
    '''                    90,\n                )\n                check(result.optBoolean("ok")) { "AppStream discovery returned rc=${result.optInt("rc", -1)}" }\n''',
    '''                    30,\n                )\n                check(result.optBoolean("ok")) { "Debian app discovery returned rc=${result.optInt("rc", -1)}" }\n''',
)
patch_once(service, 'error = t.message ?: "Could not query AppStream"', 'error = t.message ?: "Could not query Debian apps"')

activity = "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
patch_once(
    activity,
    'Metric(Icons.Default.Memory, "Memory", "${if (state.guestMemoryMb > 0) state.guestMemoryMb else 4096} MiB UML guest · ${VesselRuntimeController.UML_VCPUS} vCPU")',
    'Metric(Icons.Default.Memory, "Memory", "${if (state.guestMemoryMb > 0) state.guestMemoryMb else 4096} MiB UML guest · ${VesselExperimentConfig.vcpus(this@VesselActivity)} vCPU")',
)
patch_once(activity, 'LaunchedEffect(state.guestReady, state.busy, state.stage) {', 'LaunchedEffect(state.guestReady, state.stage) {')
patch_once(
    activity,
    'if (state.guestReady && !state.busy && store.apps.isEmpty() && !store.loading) {',
    'if (state.guestReady && store.apps.isEmpty() && !store.loading) {',
)
patch_once(activity, 'Text("Real AppStream apps · Debian ARM64"', 'Text("Fast Debian package discovery · ARM64"')
patch_once(
    activity,
    'val canRun = if (hostMode) !hostState.busy else state.running && state.guestReady && !state.busy',
    'val canRun = if (hostMode) !hostState.busy else state.running && state.guestReady',
)
patch_once(
    activity,
    '''    private fun SystemPage(state: SessionState) {\n        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()\n        LaunchedEffect(state.guestReady, state.running) { VmSessionService.active?.refreshSystemStats() }\n''',
    '''    private fun SystemPage(state: SessionState) {\n        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()\n        var expVcpus by remember { mutableIntStateOf(VesselExperimentConfig.vcpus(this@VesselActivity)) }\n        var expMemory by remember { mutableIntStateOf(VesselExperimentConfig.memoryMb(this@VesselActivity)) }\n        var expRefresh by remember { mutableIntStateOf(VesselExperimentConfig.refreshHz(this@VesselActivity)) }\n        var expResolution by remember { mutableIntStateOf(VesselExperimentConfig.resolutionPercent(this@VesselActivity)) }\n        var expFlipY by remember { mutableStateOf(VesselExperimentConfig.flipDisplayY(this@VesselActivity)) }\n        var expPointerY by remember { mutableStateOf(VesselExperimentConfig.invertPointerY(this@VesselActivity)) }\n        LaunchedEffect(state.guestReady, state.running) { VmSessionService.active?.refreshSystemStats() }\n''',
)
experiment_card = r'''            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Experiment lab", fontWeight = FontWeight.SemiBold)
                    Text("One APK, same machine, change one variable and restart Linux. Defaults favor stability so we can stop guessing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("UML vCPU", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(1, 2, 4, 6).forEach { value ->
                            FilterChip(selected = expVcpus == value, onClick = { expVcpus = value; VesselExperimentConfig.setVcpus(this@VesselActivity, value) }, label = { Text("$value CPU") })
                        }
                    }
                    Text("Guest memory", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(0 to "Auto", 2048 to "2 GB", 3072 to "3 GB", 4096 to "4 GB").forEach { (value, label) ->
                            FilterChip(selected = expMemory == value, onClick = { expMemory = value; VesselExperimentConfig.setMemoryMb(this@VesselActivity, value) }, label = { Text(label) })
                        }
                    }
                    Text("Refresh cap", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(60, 90, 120).forEach { value ->
                            FilterChip(selected = expRefresh == value, onClick = { expRefresh = value; VesselExperimentConfig.setRefreshHz(this@VesselActivity, value) }, label = { Text("$value Hz") })
                        }
                    }
                    Text("Guest resolution", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(67 to "~720p", 83 to "Balanced", 100 to "Native cap").forEach { (value, label) ->
                            FilterChip(selected = expResolution == value, onClick = { expResolution = value; VesselExperimentConfig.setResolutionPercent(this@VesselActivity, value) }, label = { Text(label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(selected = expFlipY, onClick = { expFlipY = !expFlipY; VesselExperimentConfig.setFlipDisplayY(this@VesselActivity, expFlipY) }, label = { Text("Fix display Y flip") })
                        FilterChip(selected = expPointerY, onClick = { expPointerY = !expPointerY; VesselExperimentConfig.setInvertPointerY(this@VesselActivity, expPointerY) }, label = { Text("Invert pointer Y") })
                    }
                    Text("CPU, RAM, refresh, resolution and display orientation apply on the next Linux start.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        VesselExperimentConfig.reset(this@VesselActivity)
                        expVcpus = VesselExperimentConfig.vcpus(this@VesselActivity)
                        expMemory = VesselExperimentConfig.memoryMb(this@VesselActivity)
                        expRefresh = VesselExperimentConfig.refreshHz(this@VesselActivity)
                        expResolution = VesselExperimentConfig.resolutionPercent(this@VesselActivity)
                        expFlipY = VesselExperimentConfig.flipDisplayY(this@VesselActivity)
                        expPointerY = VesselExperimentConfig.invertPointerY(this@VesselActivity)
                    }) { Text("Reset experiment defaults") }
                }
            }
'''
patch_once(
    activity,
    '''            ElevatedCard(shape = RoundedCornerShape(22.dp)) {\n                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {\n                    Text("Storage", fontWeight = FontWeight.SemiBold)\n''',
    experiment_card + '''            ElevatedCard(shape = RoundedCornerShape(22.dp)) {\n                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {\n                    Text("Storage", fontWeight = FontWeight.SemiBold)\n''',
)

view = "app/src/main/java/com/example/dreamlinux/LinuxDesktopView.kt"
patch_once(
    view,
    '''            val x = ox + (VesselWaylandPresenter.cursorX() - VesselWaylandPresenter.cursorHotX()) * scale\n            val y = oy + (VesselWaylandPresenter.cursorY() - VesselWaylandPresenter.cursorHotY()) * scale\n''',
    '''            val x = ox + (VesselWaylandPresenter.cursorX() - VesselWaylandPresenter.cursorHotX()) * scale\n            val rawY = VesselWaylandPresenter.cursorY()\n            val guestY = if (VesselExperimentConfig.invertPointerY(context)) gh - rawY else rawY\n            val y = oy + (guestY - VesselWaylandPresenter.cursorHotY()) * scale\n''',
)
patch_once(
    view,
    '''        return (((x - ox) / (gw * scale)).coerceIn(0f, 1f)) to\n            (((y - oy) / (gh * scale)).coerceIn(0f, 1f))\n''',
    '''        val nx = ((x - ox) / (gw * scale)).coerceIn(0f, 1f)\n        val rawY = ((y - oy) / (gh * scale)).coerceIn(0f, 1f)\n        val ny = if (VesselExperimentConfig.invertPointerY(context)) 1f - rawY else rawY\n        return nx to ny\n''',
)
patch_once(
    view,
    'VesselVirtioInput.relative(dx * 1.25f, dy * 1.25f)',
    'VesselVirtioInput.relative(dx * 1.25f, dy * 1.25f * if (VesselExperimentConfig.invertPointerY(context)) -1f else 1f)',
)

bridge = "tools/vessel_native/vessel_ahb_bridge.cpp"
patch_once(bridge, '#include <chrono>\n', '#include <chrono>\n#include <cstdlib>\n')
patch_once(
    bridge,
    '''void clear_egl_errors() { for (int i = 0; i < 16; ++i) if (eglGetError() == EGL_SUCCESS) return; }\n\nDamageRect full_damage''',
    '''void clear_egl_errors() { for (int i = 0; i < 16; ++i) if (eglGetError() == EGL_SUCCESS) return; }\n\nbool flip_y_enabled() {\n    const char* value = std::getenv("VESSEL_FLIP_Y");\n    return value == nullptr || std::strcmp(value, "0") != 0;\n}\n\nDamageRect full_damage''',
)
patch_once(
    bridge,
    '''    const GLint dst_x0 = static_cast<GLint>(damage.x);\n    const GLint dst_y0 = static_cast<GLint>(damage.y);\n    const GLint dst_x1 = static_cast<GLint>(damage.x + damage.width);\n    const GLint dst_y1 = static_cast<GLint>(damage.y + damage.height);\n    glBlitFramebuffer(src_x0, src_y0, src_x1, src_y1,\n                      dst_x0, dst_y0, dst_x1, dst_y1,\n''',
    '''    const GLint dst_x0 = static_cast<GLint>(damage.x);\n    const GLint dst_x1 = static_cast<GLint>(damage.x + damage.width);\n    const bool flip_y = flip_y_enabled();\n    const GLint dst_y0 = flip_y\n        ? static_cast<GLint>(slot.height - damage.y)\n        : static_cast<GLint>(damage.y);\n    const GLint dst_y1 = flip_y\n        ? static_cast<GLint>(slot.height - damage.y - damage.height)\n        : static_cast<GLint>(damage.y + damage.height);\n    glBlitFramebuffer(src_x0, src_y0, src_x1, src_y1,\n                      dst_x0, dst_y0, dst_x1, dst_y1,\n''',
)

print("[alpha4] experiment lab, display Y correction, non-blocking terminal/apps and fast discovery patches applied")
