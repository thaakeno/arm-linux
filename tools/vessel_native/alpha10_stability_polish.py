#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
ACTIVITY = ROOT / "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
VIEW = ROOT / "app/src/main/java/com/example/dreamlinux/LinuxDesktopView.kt"
CONFIG = ROOT / "app/src/main/java/com/example/dreamlinux/VesselExperimentConfig.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


def replace_optional(text: str, old: str, new: str) -> str:
    return text.replace(old, new) if old in text else text


def replace_region(text: str, start: str, end: str, new: str, label: str) -> str:
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: region missing start={a} end={b}")
    return text[:a] + new + text[b:]


# ---------------------------------------------------------------------------
# Stable defaults. v49 physical testing proved the display path at ~115 fps,
# but a forced 6-vCPU restart was killed with rc=137. Use four vCPUs as the
# stable profile, request the highest 120 Hz mode by default, and migrate the
# old edge defaults once while keeping every experiment switch available.
# ---------------------------------------------------------------------------
CONFIG.write_text(r'''package com.example.dreamlinux

import android.content.Context
import android.content.SharedPreferences

object VesselExperimentConfig {
    private const val PREFS = "vessel_experiment_lab_v1"
    private const val STABILITY_MIGRATION = "stable_profile_v50"

    private fun prefs(context: Context): SharedPreferences {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(STABILITY_MIGRATION, false)) {
            val oldCpu = p.getInt("vcpus", 4)
            val editor = p.edit()
            // The old edge profile defaulted to 1 CPU and physical testing also
            // hit an rc=137 kill after explicitly selecting 6. Migrate either
            // extreme to the stable four-core profile once; 6 remains selectable.
            if (oldCpu == 1 || oldCpu == 6) editor.putInt("vcpus", 4)
            if (!p.contains("refresh_hz")) editor.putInt("refresh_hz", 120)
            editor.putBoolean(STABILITY_MIGRATION, true).apply()
        }
        return p
    }

    fun vcpus(context: Context): Int = prefs(context).getInt("vcpus", 4).let { if (it in listOf(1, 2, 4, 6)) it else 4 }
    fun setVcpus(context: Context, value: Int) { prefs(context).edit().putInt("vcpus", value.coerceIn(1, 6)).apply() }

    fun memoryMb(context: Context): Int = prefs(context).getInt("memory_mb", 0).let { if (it in listOf(0, 2048, 3072, 4096)) it else 0 }
    fun setMemoryMb(context: Context, value: Int) { prefs(context).edit().putInt("memory_mb", value).apply() }

    fun refreshHz(context: Context): Int = prefs(context).getInt("refresh_hz", 120).let { if (it in listOf(60, 90, 120)) it else 120 }
    fun setRefreshHz(context: Context, value: Int) { prefs(context).edit().putInt("refresh_hz", value).apply() }

    fun resolutionPercent(context: Context): Int = prefs(context).getInt("resolution_percent", 67).let { if (it in listOf(67, 83, 100)) it else 67 }
    fun setResolutionPercent(context: Context, value: Int) { prefs(context).edit().putInt("resolution_percent", value).apply() }

    fun flipDisplayY(context: Context): Boolean = prefs(context).getBoolean("flip_display_y", true)
    fun setFlipDisplayY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("flip_display_y", value).apply() }

    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)
    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean(STABILITY_MIGRATION, true)
            .putInt("vcpus", 4)
            .putInt("refresh_hz", 120)
            .apply()
    }
}
''')

# ---------------------------------------------------------------------------
# Cursor alpha + low-latency presenter queue.
# The standard GPU cursor frontend was forcibly OR'ing 0xff alpha into every
# 64x64 cursor pixel, exactly explaining the black rectangle seen on-device.
# ---------------------------------------------------------------------------
native_presenter = ROOT / "app/src/main/cpp/vessel_native_presenter.cpp"
if native_presenter.exists():
    p = native_presenter.read_text()
    old = "cursorPixels_[i]=0xff000000u|(v&0x00ffffffu);"
    new = "cursorPixels_[i]=v;"
    if old in p:
        p = p.replace(old, new, 1)
    elif new not in p:
        raise SystemExit("cursor alpha anchor missing")
    native_presenter.write_text(p)

for rel in ("tools/vessel_native/vessel_surface_presenter_v6.cpp", "app/src/main/cpp/vessel_surface_presenter.cpp"):
    path = ROOT / rel
    if path.exists():
        p = path.read_text()
        if "constexpr size_t MAX_QUEUED_FRAMES = 5;" in p:
            p = p.replace("constexpr size_t MAX_QUEUED_FRAMES = 5;", "constexpr size_t MAX_QUEUED_FRAMES = 2;", 1)
        path.write_text(p)

# Ask SurfaceFlinger for the configured high-refresh presentation rate rather
# than blindly copying the currently selected Android mode.
view = VIEW.read_text()
old_refresh = '''        val refresh = display?.refreshRate ?: 60f
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(refresh, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
'''
new_refresh = '''        val refresh = (display?.supportedModes?.maxOfOrNull { it.refreshRate }
            ?: display?.refreshRate ?: 60f)
            .coerceAtMost(VesselExperimentConfig.refreshHz(context).toFloat())
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(refresh, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
'''
view = replace_once(view, old_refresh, new_refresh, "surface refresh request")
VIEW.write_text(view)

# ---------------------------------------------------------------------------
# Controller: make tty0 boot-only, then move every post-KWin command to a
# reconnecting passt control agent; add Android audio, process-tree cleanup and
# rc=137 diagnostics.
# ---------------------------------------------------------------------------
text = CONTROLLER.read_text()
text = replace_once(
    text,
    "import android.os.Environment\n",
    "import android.os.Environment\nimport android.system.Os\nimport android.system.OsConstants\n",
    "controller os imports",
)

text = replace_once(
    text,
    "    @Volatile private var guestShellReady = CompletableFuture<Unit>()\n",
    "    @Volatile private var guestShellReady = CompletableFuture<Unit>()\n    @Volatile private var useGuestAgent = false\n",
    "controller guest agent state",
)

# Stable native-process cleanup. Killing only the umnet Process object is not
# enough to guarantee its passt/UML descendants died after a force-stop/restart.
cleanup_helper = r'''
    private val staleRuntimeNeedles = listOf(
        "libvessel_uml.so",
        "libvessel_umnet.so",
        "libvessel_passt.so",
        "libvessel_vhost_gpu.so",
        "libvessel_vhost_gpu_system.so",
        "libvessel_vhost_gpu_angle.so",
        "libvessel_vhost_input.so",
    )

    private fun killStaleNativeRuntimeProcesses(reason: String) {
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        var killed = 0
        File("/proc").listFiles().orEmpty().forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val uid = runCatching {
                File(dir, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
                }
            }.getOrNull()
            if (uid != myUid) return@forEach
            val command = runCatching {
                File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrDefault("")
            if (staleRuntimeNeedles.none { command.contains(it) }) return@forEach
            runCatching {
                Os.kill(pid, OsConstants.SIGKILL)
                killed++
                append("[host] killed stale Vessel native pid=$pid reason=$reason cmd=${command.take(180)}\\n")
            }
        }
        if (killed > 0) Thread.sleep(120)
    }

'''
text = replace_once(
    text,
    "    private fun startInputBackends() {\n",
    cleanup_helper + "    private fun startInputBackends() {\n",
    "stale native process cleanup helper",
)

# Every command after launchDesktop() uses the dedicated agent. Never silently
# fall back to the graphical tty: that is the v49 Apps/post-boot deadlock.
guest_anchor = "    ): Pair<Int, String> = synchronized(commandLock) {\n        val future: CompletableFuture<Pair<Int, String>>\n"
guest_new = '''    ): Pair<Int, String> = synchronized(commandLock) {
        if (useGuestAgent) {
            return@synchronized try {
                VesselGuestAgent.execute(command, timeoutSeconds, onLine)
            } catch (t: Throwable) {
                append("[control] guest-agent command failed: ${t.message}\\n")
                throw t
            }
        }
        val future: CompletableFuture<Pair<Int, String>>
'''
text = replace_once(text, guest_anchor, guest_new, "post-boot guest agent routing")

# Add transport/audio state to diagnostics JSON.
text = replace_once(
    text,
    '        .put("inputSender", VesselVirtioInput.status())\n',
    '        .put("inputSender", VesselVirtioInput.status())\n        .put("controlTransport", if (useGuestAgent) VesselGuestAgent.status() else "boot-tty")\n        .put("audioTransport", VesselAudioBridge.status())\n',
    "status control audio fields",
)

# Audio and agent bootstrap run while tty0 is still healthy. PulseAudio then
# autostarts inside the vessel KDE user session and exposes the Android-backed
# ALSA sink as its default sink.
bootstrap_methods = r'''
    private fun setupGuestAudioBlocking() {
        val port = VesselAudioBridge.port()
        check(port > 0) { "Android audio bridge did not start" }
        val helper = context.assets.open("vessel/guest_audio_pipe.py").use { it.readBytes() }
        val helperB64 = Base64.getEncoder().encodeToString(helper)
        val command = """
            set -e
            install -d -m 755 /usr/local/lib/vessel
            printf '%s' '$helperB64' | base64 -d >/usr/local/lib/vessel/audio_pipe.py
            chmod 0755 /usr/local/lib/vessel/audio_pipe.py
            cat >/etc/asound.conf <<'VESSEL_ASOUND'
            pcm.vessel_raw {
                type file
                slave.pcm "null"
                file "|/usr/bin/python3 /usr/local/lib/vessel/audio_pipe.py $port 48000 2 16 S16_LE"
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
        """.trimIndent()
        val result = guestBlocking(command, 45)
        check(result.first == 0) { "Could not configure Android audio bridge: ${result.second.takeLast(5000)}" }
        append("[audio] guest PulseAudio/ALSA -> Android AudioTrack bridge configured port=$port\\n")
    }

    private fun startGuestControlAgentBlocking() {
        val port = VesselGuestAgent.port()
        check(port > 0) { "Android guest-control server did not start" }
        val helper = context.assets.open("vessel/guest_control_agent.py").use { it.readBytes() }
        val helperB64 = Base64.getEncoder().encodeToString(helper)
        val token = VesselGuestAgent.token()
        val command = """
            set -e
            install -d -m 755 /usr/local/lib/vessel
            printf '%s' '$helperB64' | base64 -d >/usr/local/lib/vessel/control_agent.py
            chmod 0755 /usr/local/lib/vessel/control_agent.py
            pkill -f '/usr/local/lib/vessel/control_agent.py' 2>/dev/null || true
            nohup /usr/bin/python3 /usr/local/lib/vessel/control_agent.py 10.0.2.2 $port '$token' >/tmp/vessel-control-agent.log 2>&1 </dev/null &
            echo VESSEL_CONTROL_AGENT_STARTED
        """.trimIndent()
        val result = guestBlocking(command, 20)
        check(result.first == 0) { "Could not start Debian control agent: ${result.second.takeLast(5000)}" }
        append("[control] persistent Debian control agent launched port=$port\\n")
    }

'''
text = replace_once(
    text,
    "    private fun displayModeCommand(): String {\n",
    bootstrap_methods + "    private fun displayModeCommand(): String {\n",
    "audio/control bootstrap methods",
)

# Audio packages are part of the workstation contract.
text = text.replace(
    "plasma-pa kactivitymanagerd libkf5service-data",
    "plasma-pa pulseaudio pulseaudio-utils alsa-utils kactivitymanagerd libkf5service-data",
)
text = text.replace(
    "plasma-pa kactivitymanagerd",
    "plasma-pa pulseaudio pulseaudio-utils alsa-utils kactivitymanagerd",
)

# Start clean, then switch transport only after the single authoritative
# Wayland launch has returned. All service post-boot calls then use the agent.
text = replace_once(
    text,
    "            assertAssets()\n            ensureDisk()\n",
    '''            assertAssets()
            useGuestAgent = false
            VesselGuestAgent.resetConnection()
            killStaleNativeRuntimeProcesses("pre-start")
            ensureDisk()
''',
    "pre-start cleanup",
)
text = replace_once(
    text,
    "            ensurePlasma()\n            launchDesktop()\n            progress(\"frame\", 88, \"Waiting for Android native Surface frame\")\n",
    '''            ensurePlasma()
            setupGuestAudioBlocking()
            startGuestControlAgentBlocking()
            launchDesktop()
            useGuestAgent = true
            append("[control] post-boot RPC switched from UML tty to ${VesselGuestAgent.status()}\\n")
            progress("frame", 88, "Waiting for Android native Surface frame")
''',
    "post-plasma stable services",
)

# Capture useful host memory information whenever UML dies unexpectedly. rc=137
# means SIGKILL but deliberately does not pretend to know whether LMKD, manual
# cleanup or another kernel policy issued it.
text = replace_once(
    text,
    '                append("[host] UML launcher exited rc=$rc\\n")\n',
    '''                if (!stopping) {
                    val memory = ActivityManager.MemoryInfo()
                    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
                    val signalHint = if (rc == 137) "SIGKILL/137" else "exit=$rc"
                    append("[crash] UML $signalHint; hostAvailMiB=${memory.availMem / (1024 * 1024)} lowMemory=${memory.lowMemory} thresholdMiB=${memory.threshold / (1024 * 1024)} control=${VesselGuestAgent.status()} audio=${VesselAudioBridge.status()}\\n")
                }
                append("[host] UML launcher exited rc=$rc\\n")
''',
    "UML crash diagnostics",
)

# Best-effort graceful desktop teardown through the stable agent, then kill the
# complete same-UID native tree so Stop -> Start and Android force-stop cannot
# leave an old UML/passt/vhost process behind.
text = replace_once(
    text,
    "    private fun stopBlocking() {\n        stopping = true\n",
    '''    private fun stopBlocking() {
        stopping = true
        if (useGuestAgent && VesselGuestAgent.isConnected()) {
            runCatching {
                VesselGuestAgent.execute("sync; pkill -u vessel -x plasmashell 2>/dev/null || true; pkill -u vessel -x kwin_wayland 2>/dev/null || true", 5)
            }
        }
        useGuestAgent = false
        VesselGuestAgent.resetConnection()
''',
    "stable stop prelude",
)
text = replace_once(
    text,
    "        stopInputBackends()\n        gpuSocket.delete()\n    }\n",
    '''        stopInputBackends()
        gpuSocket.delete()
        killStaleNativeRuntimeProcesses("stop")
    }
''',
    "stable stop descendant cleanup",
)

text = text.replace("v49-wayland-single-rpc-r1", "v50-stable-control-audio-ui-r1")
CONTROLLER.write_text(text)

# ---------------------------------------------------------------------------
# Service: start host control/audio servers, use the richer cached Apps catalog,
# remove the Wayland/X11 profile mismatch, slow background stats polling and add
# one-tap crash diagnostics.
# ---------------------------------------------------------------------------
service = SERVICE.read_text()
service = replace_once(
    service,
    "import android.app.Service\n",
    "import android.app.Service\nimport android.app.ActivityManager\n",
    "service ActivityManager import",
)
service = replace_once(
    service,
    "        val machineStats = MutableStateFlow(MachineStats())\n",
    "        val machineStats = MutableStateFlow(MachineStats())\n        val diagnostics = MutableStateFlow(\"No diagnostics collected yet.\")\n",
    "diagnostics state",
)
service = service.replace(
    '"plasma-integration", "plasma-pa", "kactivitymanagerd",',
    '"plasma-integration", "plasma-pa", "pulseaudio", "pulseaudio-utils", "alsa-utils", "kactivitymanagerd",',
)
service = service.replace(
    'val APP_SORTS = listOf("POPULAR", "NEW", "INSTALLED", "SIZE", "AZ")',
    'val APP_SORTS = listOf("POPULAR", "NEW", "HOT_WEEK", "HOT_MONTH", "HOT_YEAR", "INSTALLED", "SIZE", "AZ")',
)
service = replace_once(
    service,
    "        active = this\n        runtime = VesselRuntimeController(this) { phase, pct, detail ->\n",
    '''        active = this
        VesselGuestAgent.start()
        VesselAudioBridge.start(this)
        runtime = VesselRuntimeController(this) { phase, pct, detail ->
''',
    "service host bridges start",
)
service = replace_once(
    service,
    "        VesselWaylandPresenter.shutdown()\n        super.onDestroy()\n",
    '''        VesselWaylandPresenter.shutdown()
        VesselGuestAgent.stop()
        VesselAudioBridge.stop()
        super.onDestroy()
''',
    "service host bridges stop",
)
# alpha4 has already changed this block when alpha10 runs.
service = replace_once(
    service,
    '''        val requestedRefresh = VesselExperimentConfig.refreshHz(this).toFloat()
        guestRefresh = min(mode?.refreshRate ?: 60f, requestedRefresh).coerceAtLeast(30f)
''',
    '''        val requestedRefresh = VesselExperimentConfig.refreshHz(this).toFloat()
        val maxSupportedRefresh = display?.supportedModes?.maxOfOrNull { it.refreshRate }
            ?: mode?.refreshRate ?: 60f
        guestRefresh = min(maxSupportedRefresh, requestedRefresh).coerceAtLeast(30f)
''',
    "service highest refresh mode",
)
service = service.replace("now - lastStatsAt > 5000", "now - lastStatsAt > 15000")
service = service.replace('assets.open("vessel/app_discovery_fast.py")', 'assets.open("vessel/app_discovery_v3.py")')
# alpha4 set this first discovery timeout to 30; give cold AppStream cache build
# room to finish without ever blocking the UI thread.
service = service.replace(
    '''                    30,
                )
                check(result.optBoolean("ok")) { "Debian app discovery returned rc=${result.optInt("rc", -1)}" }
''',
    '''                    120,
                )
                check(result.optBoolean("ok")) { "Debian app discovery returned rc=${result.optInt("rc", -1)}" }
''',
    1,
)
service = service.replace('error = t.message ?: "Could not query Debian apps"', 'error = t.message ?: "Could not query Debian app catalog"')

# Wayland profile validation must accept kwin_wayland; the old condition still
# demanded kwin_x11 even after the runtime switched to Wayland.
service = service.replace(
    "if pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null; then ready=1; break; fi",
    "if pgrep -u vessel -x plasmashell >/dev/null && { pgrep -u vessel -x kwin_wayland >/dev/null || pgrep -u vessel -x kwin_x11 >/dev/null; }; then ready=1; break; fi",
)

# Add an actionable diagnostic bundle that captures host memory, the stable RPC
# state, audio, guest load/OOM/DRM messages, Plasma and Firefox crash artifacts.
diag_method = r'''
    fun collectCrashDiagnostics() {
        diagnostics.value = "Collecting host + Debian diagnostics…"
        scope.launch(Dispatchers.IO) {
            val memory = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val host = buildString {
                appendLine("=== VESSEL HOST ===")
                appendLine("app=${BuildConfig.VERSION_NAME} commit=${BuildConfig.GIT_COMMIT}")
                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
                appendLine("hostAvailMiB=${memory.availMem / (1024 * 1024)} lowMemory=${memory.lowMemory} thresholdMiB=${memory.threshold / (1024 * 1024)}")
                appendLine("control=${VesselGuestAgent.status()}")
                appendLine("audio=${VesselAudioBridge.status()}")
                appendLine("presenter=${VesselWaylandPresenter.status()}")
                appendLine("vcpus=${runtime.selectedVcpus} guestRamMiB=${runtime.guestMemoryMb}")
            }
            val guest = if (state.value.running && state.value.guestReady) {
                runCatching {
                    runtime.guest(
                        """
                        printf '=== GUEST LOAD ===\\n'; cat /proc/loadavg; free -m
                        printf '\\n=== PROCESSES ===\\n'; ps -eo pid,ppid,stat,pcpu,pmem,comm --sort=-pcpu | head -35
                        printf '\\n=== AUDIO ===\\n'; su -l vessel -c 'pactl info; pactl list short sinks' 2>&1 || true
                        printf '\\n=== KERNEL ERRORS ===\\n'; dmesg 2>&1 | grep -Ei 'oom|out of memory|killed process|segfault|drm|virtio|virgl|gpu|rcu|napi|stall' | tail -160
                        printf '\\n=== PLASMA ===\\n'; tail -220 /tmp/vessel-plasma.log 2>/dev/null || true
                        printf '\\n=== FIREFOX CRASH ARTIFACTS ===\\n'; find /home/vessel/.mozilla -type f \\( -path '*/minidumps/*' -o -path '*/Crash Reports/*' \\) -printf '%TY-%Tm-%Td %TT %p\\n' 2>/dev/null | sort | tail -80 || true
                        """.trimIndent(),
                        45,
                    ).optString("output")
                }.getOrElse { "Guest diagnostics failed: ${it.message}" }
            } else {
                "Guest is not running."
            }
            diagnostics.value = (host + "\\n" + guest + "\\n\\n=== RUNTIME LOG TAIL ===\\n" + state.value.console.takeLast(18_000)).takeLast(80_000)
        }
    }

'''
service = replace_once(
    service,
    "    private fun hostDiskStats(): Triple<Long, Long, Long> {\n",
    diag_method + "    private fun hostDiskStats(): Triple<Long, Long, Long> {\n",
    "diagnostic collector",
)
service = service.replace("v49-wayland-single-rpc-r1", "v50-stable-control-audio-ui-r1")
SERVICE.write_text(service)

# ---------------------------------------------------------------------------
# UI: normal mode respects Android safe areas; fullscreen becomes a clean,
# aspect-correct desktop with a visible controls handle; normal display gets a
# live viewport-size slider; Apps gains useful time filters and System exposes
# audio/crash diagnostics.
# ---------------------------------------------------------------------------
activity = ACTIVITY.read_text()
activity = replace_once(activity, "import androidx.compose.foundation.layout.Box\n", "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.BoxWithConstraints\n", "BoxWithConstraints import")
activity = replace_once(activity, "import androidx.compose.foundation.layout.Arrangement\n", "import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.WindowInsets\nimport androidx.compose.foundation.layout.aspectRatio\n", "layout imports 1")
activity = replace_once(activity, "import androidx.compose.foundation.layout.width\n", "import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.layout.safeDrawing\nimport androidx.compose.foundation.layout.windowInsetsPadding\n", "layout imports 2")
activity = replace_once(activity, "import androidx.compose.material3.Scaffold\n", "import androidx.compose.material3.Scaffold\nimport androidx.compose.material3.Slider\n", "slider import")

activity = replace_once(
    activity,
    "class VesselActivity : ComponentActivity() {\n",
    "class VesselActivity : ComponentActivity() {\n    @Volatile private var fullscreenRequested = false\n",
    "fullscreen activity state",
)
activity = activity.replace("        immersive()\n        VesselHostDebug.initialize(this)", "        applySystemChrome(false)\n        preferHighRefresh()\n        VesselHostDebug.initialize(this)", 1)
activity = activity.replace("        immersive()\n        startForegroundService(Intent(this, VmSessionService::class.java))", "        applySystemChrome(fullscreenRequested)\n        preferHighRefresh()\n        startForegroundService(Intent(this, VmSessionService::class.java))", 1)
activity = activity.replace("        if (hasFocus) immersive()", "        if (hasFocus) applySystemChrome(fullscreenRequested)", 1)

old_immersive = '''    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
'''
new_chrome = '''    private fun applySystemChrome(fullscreen: Boolean) {
        fullscreenRequested = fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun preferHighRefresh() {
        val displayManager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        val current = display.mode
        val requested = VesselExperimentConfig.refreshHz(this).toFloat().coerceAtMost(120f)
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate <= requested + 0.5f }
            .maxByOrNull { it.refreshRate }
            ?: return
        if (best.modeId != current.modeId) {
            val attrs = window.attributes
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }
    }
'''
activity = replace_once(activity, old_immersive, new_chrome, "system chrome implementation")
activity = replace_once(
    activity,
    '''        LaunchedEffect(fullscreen) {
            immersive()
            requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
''',
    '''        LaunchedEffect(fullscreen) {
            applySystemChrome(fullscreen)
            preferHighRefresh()
            requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
''',
    "fullscreen system chrome effect",
)
activity = activity.replace("FullscreenDesktop { fullscreen = false }", "FullscreenDesktop(state) { fullscreen = false }", 1)

# Normal Display: aspect-correct preview instead of stretching a wide desktop
# into arbitrary app bounds, with a live presentation-size control.
desktop_page = r'''    @Composable
    private fun DesktopPage(state: SessionState, fullscreen: () -> Unit) {
        var mode by remember { mutableStateOf(LinuxDesktopView.PointerMode.DIRECT) }
        var displayScale by remember { mutableStateOf(1f) }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Linux display", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state.displayReady -> "Wayland · native GPU surface · ${uptime(state.uptimeMs)}"
                            state.frameReachedApp -> "Validated GPU frame reached Vessel"
                            state.running || state.busy -> state.progressDetail
                            else -> "Press Start Linux first"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.displayReady) StatusPill("VISIBLE", true)
            }
            if (state.running || state.busy) {
                DesktopControls(mode, { newMode -> mode = newMode; LinuxDesktopView.active?.setPointerMode(newMode) }, fullscreen)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val aspect = (state.guestDisplayWidth.coerceAtLeast(1).toFloat() / state.guestDisplayHeight.coerceAtLeast(1).toFloat()).coerceIn(1.2f, 3.0f)
                    Surface(
                        modifier = Modifier.fillMaxWidth(displayScale).aspectRatio(aspect).align(Alignment.Center),
                        color = Color.Black,
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 6.dp,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() } },
                                update = { view -> view.setPointerMode(mode); if (!view.hasFocus()) view.requestFocus() },
                            )
                            if (!state.displayReady) {
                                Surface(Modifier.align(Alignment.Center).padding(18.dp), shape = RoundedCornerShape(18.dp), color = Color(0xD9101512)) {
                                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        LinearProgressIndicator(progress = { state.progressPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.width(220.dp))
                                        Text("${state.message} · ${state.progressPercent.coerceIn(0, 100)}%", maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text("Presenter: ${state.presenterStatus}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Display size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = displayScale, onValueChange = { displayScale = it.coerceIn(0.68f, 1f) }, valueRange = 0.68f..1f, modifier = Modifier.weight(1f))
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Linux is stopped")
                            if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                            Button(onClick = { startLinux() }, enabled = !state.busy) { Text(if (state.storageReady) "Start Linux" else "Grant storage") }
                        }
                    }
                }
            }
        }
    }

'''
activity = replace_region(
    activity,
    "    @Composable\n    private fun DesktopPage(state: SessionState, fullscreen: () -> Unit) {\n",
    "    @Composable\n    private fun DesktopControls(",
    desktop_page,
    "desktop page",
)

# Compact one-row controls free vertical space on phones.
desktop_controls = r'''    @Composable
    private fun DesktopControls(mode: LinuxDesktopView.PointerMode, setMode: (LinuxDesktopView.PointerMode) -> Unit, fullscreen: () -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mode == LinuxDesktopView.PointerMode.DIRECT, onClick = { setMode(LinuxDesktopView.PointerMode.DIRECT) }, label = { Text("Touch") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) })
            FilterChip(selected = mode == LinuxDesktopView.PointerMode.TRACKPAD, onClick = { setMode(LinuxDesktopView.PointerMode.TRACKPAD) }, label = { Text("Trackpad") }, leadingIcon = { Icon(Icons.Default.Mouse, null) })
            AssistChip(onClick = { LinuxDesktopView.active?.showKeyboard() }, label = { Text("Keyboard") }, leadingIcon = { Icon(Icons.Default.Keyboard, null) })
            AssistChip(onClick = fullscreen, label = { Text("Fullscreen") }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) })
        }
    }

'''
activity = replace_region(
    activity,
    "    @Composable\n    private fun DesktopControls(",
    "    @Composable\n    private fun FullscreenDesktop(",
    desktop_controls,
    "desktop controls",
)

# Fullscreen keeps the guest aspect ratio inside cutout/gesture safe insets. The
# control handle never disappears, so the user is never trapped behind a black
# hidden overlay.
fullscreen = r'''    @Composable
    private fun FullscreenDesktop(state: SessionState, exit: () -> Unit) {
        var mode by remember { mutableStateOf(LinuxDesktopView.PointerMode.TRACKPAD) }
        var controlsVisible by remember { mutableStateOf(true) }
        var controlsEpoch by remember { mutableIntStateOf(0) }
        LaunchedEffect(controlsVisible, controlsEpoch) {
            if (controlsVisible) {
                delay(3200)
                controlsVisible = false
            }
        }
        Box(
            Modifier.fillMaxSize()
                .background(Color(0xff030504))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        ) {
            val aspect = (state.guestDisplayWidth.coerceAtLeast(1).toFloat() / state.guestDisplayHeight.coerceAtLeast(1).toFloat()).coerceIn(1.2f, 3.0f)
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect).align(Alignment.Center),
                color = Color.Black,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() } },
                    update = { view -> view.setPointerMode(mode); if (!view.hasFocus()) view.requestFocus() },
                )
            }

            if (controlsVisible) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter),
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xF4F3F7F4),
                    shadowElevation = 10.dp,
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            mode = LinuxDesktopView.PointerMode.DIRECT
                            LinuxDesktopView.active?.setPointerMode(mode)
                            controlsEpoch++
                        }) { Icon(Icons.Default.TouchApp, "Touch", tint = if (mode == LinuxDesktopView.PointerMode.DIRECT) Color(0xff0B6B4B) else Color(0xff18211D)) }
                        IconButton(onClick = {
                            mode = LinuxDesktopView.PointerMode.TRACKPAD
                            LinuxDesktopView.active?.setPointerMode(mode)
                            controlsEpoch++
                        }) { Icon(Icons.Default.Mouse, "Trackpad", tint = if (mode == LinuxDesktopView.PointerMode.TRACKPAD) Color(0xff0B6B4B) else Color(0xff18211D)) }
                        IconButton(onClick = { LinuxDesktopView.active?.showKeyboard(); controlsEpoch++ }) { Icon(Icons.Default.Keyboard, "Keyboard", tint = Color(0xff18211D)) }
                        IconButton(onClick = exit) { Icon(Icons.Default.CloseFullscreen, "Exit fullscreen", tint = Color(0xff18211D)) }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).clickable { controlsVisible = true; controlsEpoch++ },
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xF2F3F7F4),
                    shadowElevation = 8.dp,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.Tune, null, Modifier.size(18.dp), tint = Color(0xff18211D))
                        Text("Controls", color = Color(0xff18211D), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

'''
activity = replace_region(
    activity,
    "    @Composable\n    private fun FullscreenDesktop(",
    "    @Composable\n    private fun ExtraKeys()",
    fullscreen,
    "fullscreen desktop",
)

# Rich Apps filters. "Hot" is intentionally a local heuristic from AppStream
# release dates + curated desktop popularity, not fake global download telemetry.
activity = activity.replace('Text("Fast Debian package discovery · ARM64"', 'Text("Cached AppStream catalog · ARM64 · fast local search"')
old_sort = '''                    val label = when (sort) { "POPULAR" -> "Popular"; "NEW" -> "New"; "INSTALLED" -> "Installed"; "SIZE" -> "Size"; else -> "A–Z" }
'''
new_sort = '''                    val label = when (sort) {
                        "POPULAR" -> "Popular"
                        "NEW" -> "New"
                        "HOT_WEEK" -> "Hot week"
                        "HOT_MONTH" -> "Hot month"
                        "HOT_YEAR" -> "Hot year"
                        "INSTALLED" -> "Installed"
                        "SIZE" -> "Size"
                        else -> "A–Z"
                    }
'''
activity = replace_once(activity, old_sort, new_sort, "Apps sort labels")
activity = replace_once(
    activity,
    '''            if (!state.guestReady) ErrorStrip("Start Linux first. Vessel reads Debian's own AppStream catalog, so discovery always matches this ARM64 machine.")
''',
    '''            if (!state.guestReady) ErrorStrip("Start Linux first. Vessel reads Debian's own ARM64 catalog.")
            if (state.guestReady && store.sort.startsWith("HOT_")) {
                Text("Hot combines recent AppStream releases with a small curated popularity signal; it is not global install telemetry.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
''',
    "Apps hot explanation",
)

# Diagnostics + audio visibility in System page. alpha7 already added the update
# state after stats, so this one-line insertion is stable after the full patch stack.
activity = replace_once(
    activity,
    "        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()\n",
    "        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()\n        val diagnostics by VmSessionService.diagnostics.collectAsStateWithLifecycle()\n",
    "System diagnostics state",
)
activity = activity.replace(
    'Text("One APK, same machine, change one variable and restart Linux. Defaults favor stability so we can stop guessing.",',
    'Text("Stable default: 4 vCPU + 120 Hz request. Six CPUs stays available as an experiment, but it is no longer the restart default.",',
)
activity = activity.replace(
    'Metric(Icons.Default.DesktopWindows, "Presenter", state.presenterStatus)\n',
    'Metric(Icons.Default.DesktopWindows, "Presenter", state.presenterStatus)\n                    Metric(Icons.Default.Bolt, "Audio", VesselAudioBridge.status())\n                    Metric(Icons.Default.Terminal, "Control RPC", VesselGuestAgent.status())\n',
    1,
)

diag_card = r'''            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Crash diagnostics", fontWeight = FontWeight.SemiBold)
                            Text("Host memory, UML exit state, Wayland/GPU, PulseAudio and Firefox crash artifacts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { copyText("Vessel diagnostics", diagnostics) }, enabled = diagnostics.isNotBlank()) { Text("Copy") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { VmSessionService.active?.collectCrashDiagnostics() }) { Text("Collect") }
                        OutlinedButton(onClick = { VmSessionService.active?.runGpuDiagnostics() }, enabled = state.guestReady) { Text("GPU + input") }
                    }
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xff050706)) {
                        SelectionContainer {
                            Text(diagnostics.takeLast(6_000), Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 18, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
'''
activity = replace_once(
    activity,
    '''            Button(onClick = { VmSessionService.active?.runGpuDiagnostics() }, enabled = state.guestReady && !state.busy) { Text("Run GPU + input diagnostics") }
''',
    diag_card,
    "System diagnostics card",
)
ACTIVITY.write_text(activity)

print("[alpha10] stable 4-vCPU/120Hz profile, persistent control RPC, Android audio, restart cleanup, cursor alpha, rich Apps and safe fullscreen UI applied")
