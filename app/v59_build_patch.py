#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def patch(path, old, new, *, count=1):
    p = ROOT / path
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"v59 patch anchor missing in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, count))


def patch_re(path, pattern, repl, *, count=1):
    p = ROOT / path
    text = p.read_text()
    new_text, n = re.subn(pattern, repl, text, count=count, flags=re.S)
    if n == 0:
        # Idempotency: accept a source tree already carrying the v59 markers.
        if "v59-private-storage-dynamic-display-r1" in text or "VESSEL_V59_PATCHED" in text:
            return
        raise SystemExit(f"v59 regex anchor missing in {path}: {pattern[:120]!r}")
    p.write_text(new_text)


# ---------------------------------------------------------------------------
# Runtime: put the hot ext4 image on Android's private filesystem, preserve a
# legacy Download/LinuxPC image with a sparse-aware one-time migration, enlarge
# the guest filesystem, and fall back to tty RPC if the persistent guest agent
# ever disconnects.
# ---------------------------------------------------------------------------
controller = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
patch(controller,
      'const val REVISION = "v58-smooth-pageflip-input-browser-r1"',
      'const val REVISION = "v59-private-storage-dynamic-display-r1"')
patch(controller,
      'private const val MIN_DISK_CAPACITY_BYTES = 6L * 1024L * 1024L * 1024L',
      'private const val MIN_DISK_CAPACITY_BYTES = 10L * 1024L * 1024L * 1024L')
patch(controller,
'''    val machineDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "LinuxPC/Vessel-Debian",
    )
    private val disk = File(machineDir, "debian-docker.ext4")
    private val persistentLog = File(machineDir, "vessel-runtime.log")
''',
'''    private val legacyMachineDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "LinuxPC/Vessel-Debian",
    )
    // The UML block device is deliberately on /data/user/... instead of
    // /storage/emulated/0.  The latter is Android's emulated/FUSE path and is
    // catastrophically slow for the random 4 KiB I/O a desktop ext4 image does.
    val machineDir: File = File(context.filesDir, "vessel-machine").apply { mkdirs() }
    private val disk = File(machineDir, "debian-docker.ext4")
    private val persistentLog = File(machineDir, "vessel-runtime.log")
''')
patch(controller,
      'fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()',
      'fun hasStorageAccess(): Boolean = true')

# Insert sparse-aware migration before ensureDisk().  Zero extents are sought
# over instead of written so a 10 GiB virtual disk does not become a 10 GiB
# physical copy.
patch(controller,
'''    private fun ensureDisk() {
        check(hasStorageAccess()) { "Storage access required for Download/LinuxPC" }
        check(machineDir.exists() || machineDir.mkdirs()) { "Cannot create ${machineDir.absolutePath}" }
''',
'''    private fun migrateLegacyDiskIfNeeded() {
        if (disk.isFile && disk.length() > 512L * 1024 * 1024) return
        val legacyDisk = File(legacyMachineDir, "debian-docker.ext4")
        if (!legacyDisk.isFile || legacyDisk.length() <= 512L * 1024 * 1024 || !legacyDisk.canRead()) return
        val tmp = File(machineDir, "debian-docker.ext4.migrating")
        tmp.delete()
        val total = legacyDisk.length().coerceAtLeast(1L)
        progress("disk_migrate", 5, "Moving Linux disk to fast private storage once")
        val buffer = ByteArray(1024 * 1024)
        RandomAccessFile(legacyDisk, "r").use { src ->
            RandomAccessFile(tmp, "rw").use { dst ->
                dst.setLength(0L)
                var done = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    var nonZero = false
                    var i = 0
                    while (i < n) {
                        if (buffer[i].toInt() != 0) { nonZero = true; break }
                        i++
                    }
                    if (nonZero) dst.write(buffer, 0, n) else dst.seek(dst.filePointer + n)
                    done += n
                    if ((done and ((64L * 1024 * 1024) - 1L)) < buffer.size) {
                        val pct = (5 + (done * 18L / total).toInt()).coerceIn(5, 23)
                        progress("disk_migrate", pct, "Migrating Linux disk to fast private storage")
                    }
                }
                dst.setLength(src.length())
            }
        }
        if (disk.exists()) check(disk.delete()) { "Cannot replace incomplete private Debian disk" }
        check(tmp.renameTo(disk)) { "Could not finish private Debian disk migration" }
        append("[storage] sparse-migrated legacy rootfs to ${disk.absolutePath}; legacy copy retained for safety\\n")
    }

    private fun ensureDisk() {
        check(machineDir.exists() || machineDir.mkdirs()) { "Cannot create ${machineDir.absolutePath}" }
        migrateLegacyDiskIfNeeded()
''')
patch(controller,
      'progress("disk_download", 4, "Downloading Debian once to Download/LinuxPC")',
      'progress("disk_download", 4, "Downloading Debian once to fast private storage")')

patch(controller,
'''        if (useGuestAgent) {
            return@synchronized try {
                VesselGuestAgent.execute(command, timeoutSeconds, onLine)
            } catch (t: Throwable) {
                append("[control] guest-agent command failed: ${t.message}\\n")
                throw t
            }
        }
''',
'''        if (useGuestAgent) {
            try {
                return@synchronized VesselGuestAgent.execute(command, timeoutSeconds, onLine)
            } catch (t: Throwable) {
                // Do not wedge Apps/Terminal/Stats behind a dead persistent RPC
                // socket. tty0 remains connected to the root shell for the life
                // of UML, so it is a reliable zero-restart fallback.
                append("[control] guest-agent failed (${t.message}); falling back to tty RPC\\n")
                useGuestAgent = false
            }
        }
''')
patch(controller,
'''            // From this point forward tty0 is never used for app/terminal/stats
            // commands. The agent reconnects independently and cannot fail boot.
            useGuestAgent = true
            if (VesselGuestAgent.waitUntilConnected(8_000)) {
                append("[control] persistent Debian control agent connected; post-boot RPC left tty0 permanently\\n")
            } else {
                append("[control] desktop is ready but agent is still reconnecting (${VesselGuestAgent.status()}); boot continues and post-boot calls stay off tty0\\n")
            }
''',
'''            // Prefer the persistent RPC agent, but never make the workstation
            // depend on it. A live tty command path is kept as the fallback.
            useGuestAgent = VesselGuestAgent.waitUntilConnected(3_000)
            if (useGuestAgent) {
                append("[control] persistent Debian control agent connected; tty fallback retained\\n")
            } else {
                append("[control] agent not connected (${VesselGuestAgent.status()}); using tty RPC fallback\\n")
            }
''')

# Firefox networking on this UML/passt topology: do not waste seconds trying
# IPv6/QUIC/DoH paths that the guest setup does not expose.
patch(controller,
      'user_pref("security.sandbox.content.level", 0);',
      'user_pref("security.sandbox.content.level", 0);\\nuser_pref("network.dns.disableIPv6", true);\\nuser_pref("network.http.http3.enable", false);\\nuser_pref("network.trr.mode", 5);')

# ---------------------------------------------------------------------------
# Service: live guest resizing, less background RPC churn, and app discovery
# that does not wait for the optional persistent agent.
# ---------------------------------------------------------------------------
service = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
patch(service,
      'val machinePath: String = "Download/LinuxPC/Vessel-Debian",',
      'val machinePath: String = "Private app storage / vessel-machine",')
patch(service,
      '@Volatile private var operationGeneration = 0L\n    private var lastStatsAt = 0L',
      '@Volatile private var operationGeneration = 0L\n    @Volatile private var resizeGeneration = 0L\n    @Volatile private var discoveryHelperInstalled = false\n    private var lastStatsAt = 0L')
patch(service,
      'now - lastStatsAt > 15000',
      'now - lastStatsAt > 60000 && !appStore.value.loading && appStore.value.busyPackage.isBlank()')
patch(service,
      'runtimeRevision = "v58-smooth-pageflip-input-browser-r1",',
      'runtimeRevision = "v59-private-storage-dynamic-display-r1",', count=2)
patch(service,
      'message = when {\n                !storage -> "Grant file access for Download/LinuxPC"\n                !assets -> "Native runtime assets missing from APK"',
      'message = when {\n                !assets -> "Native runtime assets missing from APK"')

patch(service,
'''    /** Surface dimensions are presentation-only; the native presenter owns swapchain resizing. */
    fun configureDisplay(width: Int, height: Int, densityDpi: Int, rate: Float) {
        if (width <= 0 || height <= 0 || densityDpi <= 0 || rate <= 0f) return
    }
''',
'''    /** Resize the guest desktop to the actual Android viewport, debounced. */
    fun configureDisplay(width: Int, height: Int, densityDpi: Int, rate: Float) {
        if (width <= 0 || height <= 0 || densityDpi <= 0 || rate <= 0f) return
        val targetWidth = ((width.coerceIn(640, 2560) / 8) * 8).coerceAtLeast(640)
        val targetHeight = ((height.coerceIn(480, 1600) / 2) * 2).coerceAtLeast(480)
        if (targetWidth == guestWidth && targetHeight == guestHeight) return
        guestWidth = targetWidth
        guestHeight = targetHeight
        // Keep KDE's logical scale desktop-like even though Android itself has a
        // very high phone DPI.
        guestDpi = 120
        guestRefresh = min(rate, VesselExperimentConfig.refreshHz(this).toFloat()).coerceAtLeast(30f)
        val generation = synchronized(this) { ++resizeGeneration }
        state.value = state.value.copy(guestDisplayWidth = guestWidth, guestDisplayHeight = guestHeight)
        scope.launch(Dispatchers.IO) {
            delay(90)
            if (generation != resizeGeneration) return@launch
            if (state.value.running && state.value.guestReady) {
                runtime.resizeDesktop(guestWidth, guestHeight, guestDpi, guestRefresh)
            } else {
                runtime.configureDisplay(guestWidth, guestHeight, guestDpi, guestRefresh)
            }
        }
    }
''')

patch(service,
'''    private suspend fun installDiscoveryHelper() {
        val bytes = assets.open("vessel/app_discovery_v3.py").use { it.readBytes() }
''',
'''    private suspend fun installDiscoveryHelper() {
        if (discoveryHelperInstalled) return
        val bytes = assets.open("vessel/app_discovery_v3.py").use { it.readBytes() }
''')
patch(service,
'''        check(result.optBoolean("ok")) { "Could not install Vessel AppStream helper" }
    }
''',
'''        check(result.optBoolean("ok")) { "Could not install Vessel AppStream helper" }
        discoveryHelperInstalled = true
    }
''')
patch_re(service,
         r'''\n\s*if \(!VesselGuestAgent\.waitUntilConnected\(8_000\)\) \{.*?return@launch\n\s*\}\n\s*installDiscoveryHelper\(\)''',
         '\n                installDiscoveryHelper()', count=1)
patch(service,
      '"/usr/local/lib/vessel/app_discovery.py ${shellQuote(query)} ${shellQuote(normalizedSort)} ${shellQuote(normalizedCategory)}",\n                    60,',
      '"/usr/local/lib/vessel/app_discovery.py ${shellQuote(query)} ${shellQuote(normalizedSort)} ${shellQuote(normalizedCategory)}",\n                    30,')

# ---------------------------------------------------------------------------
# Display/UI: no fake card corners, no scale slider. The display consumes all
# remaining vertical space and tells the guest its real viewport size.
# ---------------------------------------------------------------------------
activity = "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
patch(activity,
      'import androidx.compose.material3.Slider\n',
      '')
patch(activity,
      'import androidx.compose.ui.layout.ContentScale\n',
      'import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.layout.onSizeChanged\n')
patch(activity,
'''    private fun startLinux() {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        VmSessionService.active?.refreshAvailability()
        VmSessionService.active?.startVm()
    }
''',
'''    private fun startLinux() {
        VmSessionService.active?.refreshAvailability()
        VmSessionService.active?.startVm()
    }
''')
patch(activity,
      'if (!state.storageReady) ErrorStrip("Vessel needs file access once so the persistent Linux disk can live in Download/LinuxPC.")\n',
      '')
patch(activity,
      '!state.storageReady -> "Grant storage"\n                                else -> "Start Linux"',
      'else -> "Start Linux"')
patch(activity,
      'Metric(Icons.Default.Storage, "Disk", "Persistent sparse ext4 · safe auto-grow")',
      'Metric(Icons.Default.Storage, "Disk", "Private sparse ext4 · fast random I/O · safe auto-grow")')
patch(activity,
      'Button(onClick = { startLinux() }, enabled = !state.busy) { Text(if (state.storageReady) "Start Linux" else "Grant storage") }',
      'Button(onClick = { startLinux() }, enabled = !state.busy) { Text("Start Linux") }')

# Replace DesktopPage as a whole to avoid the old aspect-ratio card + slider.
patch_re(activity,
         r'''    @Composable\n    private fun DesktopPage\(state: SessionState, fullscreen: \(\) -> Unit\) \{.*?\n    \}\n\n    @Composable\n    private fun DesktopControls''',
'''    @Composable
    private fun DesktopPage(state: SessionState, fullscreen: () -> Unit) {
        var mode by remember { mutableStateOf(LinuxDesktopView.PointerMode.DIRECT) }
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
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                VmSessionService.active?.configureDisplay(
                                    size.width,
                                    size.height,
                                    resources.displayMetrics.densityDpi,
                                    display?.refreshRate ?: 60f,
                                )
                            }
                        },
                        factory = { context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() } },
                        update = { view -> view.setPointerMode(mode); if (!view.hasFocus()) view.requestFocus() },
                    )
                    if (!state.displayReady) {
                        Surface(Modifier.align(Alignment.Center).padding(18.dp), shape = RoundedCornerShape(18.dp), color = Color(0xD9101512)) {
                            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                LinearProgressIndicator(progress = { state.progressPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.width(220.dp))
                                Text("${state.message} · ${state.progressPercent.coerceIn(0, 100)}% · ${uptime(state.uptimeMs)}", maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("Presenter: ${state.presenterStatus}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Linux is stopped")
                            if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                            Button(onClick = { startLinux() }, enabled = !state.busy) { Text("Start Linux") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DesktopControls''')

# Fullscreen becomes genuinely edge-to-edge too; controls remain an overlay.
patch_re(activity,
         r'''            val aspect = \(state\.guestDisplayWidth.*?\n            \}\n\n            if \(controlsVisible\) \{''',
'''            AndroidView(
                modifier = Modifier.fillMaxSize().onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        VmSessionService.active?.configureDisplay(
                            size.width,
                            size.height,
                            resources.displayMetrics.densityDpi,
                            display?.refreshRate ?: 60f,
                        )
                    }
                },
                factory = { context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() } },
                update = { view -> view.setPointerMode(mode); if (!view.hasFocus()) view.requestFocus() },
            )

            if (controlsVisible) {''', count=1)
patch(activity,
'''            Modifier.fillMaxSize()
                .background(Color(0xff030504))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
''',
'''            Modifier.fillMaxSize()
                .background(Color(0xff030504)),
''')

# ---------------------------------------------------------------------------
# Pointer: KWin exports a 64x64 backing cursor, not a request to paint a giant
# 64 guest-pixel pointer. Cap its visual scale while keeping guest coordinates
# mapped exactly. Add Android haptic acknowledgement for guest clicks.
# ---------------------------------------------------------------------------
view = "app/src/main/java/com/example/dreamlinux/LinuxDesktopView.kt"
patch(view,
      'import android.view.Choreographer\n',
      'import android.view.Choreographer\nimport android.view.HapticFeedbackConstants\n')
patch(view,
'''            val x = ox + (cursorX - VesselWaylandPresenter.cursorHotX()) * scale
            val y = oy + (guestY - VesselWaylandPresenter.cursorHotY()) * scale
            canvas.drawBitmap(b, null, android.graphics.RectF(x, y, x + 64 * scale, y + 64 * scale), null)
''',
'''            val cursorScale = min(scale, 0.42f)
            val x = ox + cursorX * scale - VesselWaylandPresenter.cursorHotX() * cursorScale
            val y = oy + guestY * scale - VesselWaylandPresenter.cursorHotY() * cursorScale
            canvas.drawBitmap(b, null, android.graphics.RectF(x, y, x + 64 * cursorScale, y + 64 * cursorScale), null)
''')
patch(view,
'''                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VesselVirtioInput.absoluteNormalized(x, y, false)
''',
'''                MotionEvent.ACTION_UP -> {
                    VesselVirtioInput.absoluteNormalized(x, y, false)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_CANCEL -> VesselVirtioInput.absoluteNormalized(x, y, false)
''')
patch(view,
'''                    VesselVirtioInput.button(button, true)
                    VesselVirtioInput.button(button, false)
''',
'''                    VesselVirtioInput.button(button, true)
                    VesselVirtioInput.button(button, false)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
''')

# ---------------------------------------------------------------------------
# App discovery: the default Popular page should be instant. Do not parse the
# entire AppStream XML/YAML catalog just to show the curated first screen.
# ---------------------------------------------------------------------------
discovery = "app/src/main/assets/vessel/app_discovery_v3.py"
patch(discovery,
'''    payload = load_cache()
    by_pkg = {x.get("pkg"): dict(x) for x in payload.get("apps", []) if PACKAGE_RE.fullmatch(x.get("pkg", ""))}
    supplement_search(by_pkg, query)
''',
'''    if not query and sort_mode == "POPULAR":
        by_pkg = {}
        for pkg, name, summary, cat in POPULAR:
            add(by_pkg, pkg, name, summary, cat)
    else:
        payload = load_cache()
        by_pkg = {x.get("pkg"): dict(x) for x in payload.get("apps", []) if PACKAGE_RE.fullmatch(x.get("pkg", ""))}
        supplement_search(by_pkg, query)
''')

# Marker for CI/build logs and idempotency.
marker = ROOT / "app/src/main/assets/vessel/v59-patched.txt"
marker.write_text("VESSEL_V59_PATCHED\nprivate-storage=1\ndynamic-display=1\nhaptic-clicks=1\napp-discovery-fast-popular=1\nguest-agent-tty-fallback=1\n")
print("VESSEL_V59_PATCHED")
