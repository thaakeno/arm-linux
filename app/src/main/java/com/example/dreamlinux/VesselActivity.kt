package com.example.dreamlinux

import android.content.pm.ActivityInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class VesselActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startForegroundService(Intent(this, VmSessionService::class.java))
        setContent { VesselApp() }
    }

    @Composable
    private fun VesselApp() {
        val state by VmSessionService.state.collectAsStateWithLifecycle()
        var page by remember { mutableIntStateOf(0) }
        var fullscreen by remember { mutableStateOf(false) }

        LaunchedEffect(fullscreen) {
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) {
                insets.hide(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                insets.show(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        VesselTheme {
            if (fullscreen && state.kdeInstalled) {
                FullscreenDesktop(onExit = { fullscreen = false })
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { VesselTopBar(state) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(
                                Triple("Machine", Icons.Default.Computer, 0),
                                Triple("Desktop", Icons.Default.DesktopWindows, 1),
                                Triple("Terminal", Icons.Default.Terminal, 2),
                                Triple("System", Icons.Default.Tune, 3)
                            ).forEach { (label, icon, index) ->
                                NavigationBarItem(
                                    selected = page == index,
                                    onClick = { page = index },
                                    icon = { Icon(icon, null) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            0 -> MachinePage(state, openDesktop = { page = 1 })
                            1 -> DesktopPage(state, onFullscreen = { fullscreen = true })
                            2 -> TerminalPage(state)
                            else -> SystemPage(state)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun VesselTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xff74F0BA),
                onPrimary = Color(0xff002E20),
                primaryContainer = Color(0xff103D30),
                secondary = Color(0xff93B8FF),
                background = Color(0xff060807),
                surface = Color(0xff0C100E),
                surfaceVariant = Color(0xff151C19),
                outline = Color(0xff31403A),
                error = Color(0xffFFB4AB),
                errorContainer = Color(0xff3B171A)
            ),
            content = content
        )
    }

    @Composable
    private fun VesselTopBar(state: SessionState) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Rootless ARM64 · Venus GPU", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    when { state.kdeInstalled -> "DESKTOP"; state.running -> "RUNNING"; state.connected -> "READY"; else -> "SETUP" },
                    state.running || state.kdeInstalled
                )
            }
        }
    }

    @Composable
    private fun MachinePage(state: SessionState, openDesktop: () -> Unit) {
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier.background(
                        Brush.linearGradient(listOf(Color(0xff12362B), Color(0xff0B1713), Color(0xff0A0D0C)))
                    ).padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Debian workstation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (state.running) "Live for ${formatUptime(state.uptimeMs)}" else "Persistent Linux PC on your phone",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(if (state.running) "LIVE" else "OFF", state.running)
                        }
                        if (state.lastError.isNotBlank()) ErrorStrip(shortRuntimeError(state.lastError))
                        if (state.busy || (state.running && !state.kdeInstalled)) ProgressBlock(state)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { if (state.running) VmSessionService.active?.stopVm() else startLinux() },
                                enabled = state.connected && !state.busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (state.running) "Stop Linux" else "Start Linux")
                            }
                            if (state.kdeInstalled) {
                                FilledTonalButton(onClick = openDesktop) {
                                    Icon(Icons.Default.DesktopWindows, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Desktop")
                                }
                            }
                        }
                    }
                }
            }

            Text("Native display", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ViewInAr, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Native Surface 3D test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Direct Android GPU surface · no VNC · no Termux:X11 · direct touch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(onClick = { openNativeCube() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Open native 3D cube")
                    }
                }
            }

            Text("Machine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(Icons.Default.Memory, "Memory", "8 GB UML memory")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(Icons.Default.DesktopWindows, "Display", if (state.kdeInstalled) "KDE Plasma X11 · embedded RFB (legacy)" else state.kdeStage)
                    Metric(Icons.Default.Storage, "Disk", "Persistent ext4")
                }
            }

            if (state.kdeInstalled) {
                Text("Quick launch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { VmSessionService.active?.launchFirefox() }, enabled = !state.busy) {
                        Icon(Icons.Default.Public, null); Spacer(Modifier.width(6.dp)); Text("Firefox")
                    }
                    FilledTonalButton(onClick = { openNativeCube() }) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(6.dp)); Text("Native 3D")
                    }
                    FilledTonalButton(onClick = { VmSessionService.active?.runVulkan3DTest() }, enabled = !state.busy) {
                        Icon(Icons.Default.Memory, null); Spacer(Modifier.width(6.dp)); Text("Guest Vulkan")
                    }
                    OutlinedButton(onClick = openDesktop) {
                        Icon(Icons.Default.OpenInFull, null); Spacer(Modifier.width(6.dp)); Text("Open desktop")
                    }
                }
            }

            RuntimeLogCard(state)
        }
    }

    @Composable
    private fun DesktopPage(state: SessionState, onFullscreen: () -> Unit) {
        var pointerMode by remember { mutableStateOf(VncFramebufferView.PointerMode.DIRECT) }
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Desktop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        when { state.kdeInstalled -> "KDE Plasma · ${formatUptime(state.uptimeMs)}"; state.running -> state.progressDetail; else -> "Linux is stopped" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.kdeInstalled) StatusPill("LIVE", true)
            }

            if (state.kdeInstalled) {
                DesktopControls(pointerMode, onMode = { pointerMode = it; VncFramebufferView.active?.setPointerMode(it) }, onFullscreen = onFullscreen)
                Surface(Modifier.weight(1f).fillMaxWidth(), color = Color.Black, shape = RoundedCornerShape(18.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> VncFramebufferView(context).apply { setPointerMode(pointerMode); requestFocus() } },
                        update = { it.setPointerMode(pointerMode); if (!it.hasFocus()) it.requestFocus() }
                    )
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(if (state.running) "Plasma is not attached yet" else "Start your Linux PC")
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button(onClick = { if (state.running) VmSessionService.active?.installKde() else startLinux() }, enabled = state.connected && !state.busy) {
                                Text(if (state.running) "Start Plasma" else "Start Linux + Plasma")
                            }
                            OutlinedButton(onClick = { openNativeCube() }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.ViewInAr, null)
                                Spacer(Modifier.width(7.dp))
                                Text("Open native 3D cube")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DesktopControls(mode: VncFramebufferView.PointerMode, onMode: (VncFramebufferView.PointerMode) -> Unit, onFullscreen: () -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = mode == VncFramebufferView.PointerMode.DIRECT, onClick = { onMode(VncFramebufferView.PointerMode.DIRECT) }, label = { Text("Direct touch") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) })
            FilterChip(selected = mode == VncFramebufferView.PointerMode.TRACKPAD, onClick = { onMode(VncFramebufferView.PointerMode.TRACKPAD) }, label = { Text("Trackpad") }, leadingIcon = { Icon(Icons.Default.Mouse, null) })
            AssistChip(onClick = { VncFramebufferView.active?.showKeyboard() }, label = { Text("Keyboard") }, leadingIcon = { Icon(Icons.Default.Keyboard, null) })
            AssistChip(onClick = { VmSessionService.active?.launchFirefox() }, label = { Text("Firefox") }, leadingIcon = { Icon(Icons.Default.Public, null) })
            AssistChip(onClick = { openNativeCube() }, label = { Text("Native 3D") }, leadingIcon = { Icon(Icons.Default.ViewInAr, null) })
            AssistChip(onClick = { VmSessionService.active?.runVulkan3DTest() }, label = { Text("Guest Vulkan") }, leadingIcon = { Icon(Icons.Default.Memory, null) })
            AssistChip(onClick = onFullscreen, label = { Text("Fullscreen") }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) })
        }
    }

    @Composable
    private fun FullscreenDesktop(onExit: () -> Unit) {
        var mode by remember { mutableStateOf(VncFramebufferView.PointerMode.DIRECT) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> VncFramebufferView(context).apply { setPointerMode(mode); requestFocus() } },
                update = { it.setPointerMode(mode) }
            )
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xD9111614)
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { mode = VncFramebufferView.PointerMode.DIRECT; VncFramebufferView.active?.setPointerMode(mode) }) { Icon(Icons.Default.TouchApp, "Direct") }
                    IconButton(onClick = { mode = VncFramebufferView.PointerMode.TRACKPAD; VncFramebufferView.active?.setPointerMode(mode) }) { Icon(Icons.Default.Mouse, "Trackpad") }
                    IconButton(onClick = { VncFramebufferView.active?.showKeyboard() }) { Icon(Icons.Default.Keyboard, "Keyboard") }
                    IconButton(onClick = { VmSessionService.active?.launchFirefox() }) { Icon(Icons.Default.Public, "Firefox") }
                    IconButton(onClick = { openNativeCube() }) { Icon(Icons.Default.ViewInAr, "Native 3D") }
                    IconButton(onClick = { VmSessionService.active?.runVulkan3DTest() }) { Icon(Icons.Default.Memory, "Guest Vulkan") }
                    IconButton(onClick = onExit) { Icon(Icons.Default.CloseFullscreen, "Exit fullscreen") }
                }
            }
        }
    }

    @Composable
    private fun ExtraKeys() {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT, "Alt" to KeyEvent.KEYCODE_ALT_LEFT, "Tab" to KeyEvent.KEYCODE_TAB, "Super" to KeyEvent.KEYCODE_META_LEFT).forEach { (label, key) ->
                OutlinedButton(onClick = { VncFramebufferView.active?.sendAndroidKey(true, key); VncFramebufferView.active?.sendAndroidKey(false, key) }) { Text(label) }
            }
        }
    }

    @Composable
    private fun TerminalPage(state: SessionState) {
        var command by remember { mutableStateOf("uname -a") }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Debian terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Command") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { VmSessionService.active?.debianConsole(command) }, enabled = state.running && !state.busy) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Run") }
                OutlinedButton(onClick = { copy(state.debianTerminal) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), color = Color(0xff020403), shape = RoundedCornerShape(18.dp)) {
                SelectionContainer { Text(state.debianTerminal.ifBlank { "Start Linux, then run commands here." }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("System", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            FeatureCard(Icons.Default.Hub, "Runtime", "Rootless ARM64 User Mode Linux · protocol 22")
            FeatureCard(Icons.Default.Bolt, "GPU", "Mesa Venus → umshm → virglrenderer/Turnip → Adreno")
            FeatureCard(Icons.Default.DesktopWindows, "Native display", "Android Surface benchmark · direct GPU + touch · no VNC/Termux:X11 in benchmark")
            FeatureCard(Icons.Default.Wifi, "Network", "umnet/passt NAT · desktop transport stays on loopback")
            FeatureCard(Icons.Default.Code, "Build", "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_BRANCH.ifBlank { "app/vessel-final" }} · ${BuildConfig.GIT_COMMIT}")
            Button(onClick = { openNativeCube() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ViewInAr, null)
                Spacer(Modifier.width(7.dp))
                Text("Open native 3D cube")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { VmSessionService.active?.probeCapabilities() }, enabled = !state.busy) { Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp)); Text("Runtime check") }
                OutlinedButton(onClick = { shareDiagnostics(state) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share logs") }
            }
            OutlinedButton(onClick = { openAppSettings() }) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("Android app settings") }
        }
    }

    @Composable
    private fun RuntimeLogCard(state: SessionState) {
        var expanded by remember { mutableStateOf(false) }
        val log = state.console.takeLast(if (expanded) 40000 else 9000)
        ElevatedCard(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Live runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(state.progressDetail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
                    IconButton(onClick = { copy(log) }, enabled = log.isNotBlank()) { Icon(Icons.Default.ContentCopy, "Copy") }
                }
                Surface(color = Color(0xff020403), shape = RoundedCornerShape(14.dp)) {
                    SelectionContainer { Text(log.ifBlank { "Runtime output appears here." }, Modifier.fillMaxWidth().heightIn(min = 110.dp, max = if (expanded) 520.dp else 240.dp).verticalScroll(rememberScrollState()).padding(11.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }

    @Composable
    private fun ProgressBlock(state: SessionState) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) { Text(state.progressDetail.ifBlank { state.message }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); if (state.progressPercent >= 0) Text("${state.progressPercent}%", style = MaterialTheme.typography.labelMedium) }
            if (state.progressPercent >= 0) LinearProgressIndicator(progress = { state.progressPercent / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun ErrorStrip(text: String) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) { Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
    }

    @Composable
    private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }

    @Composable
    private fun FeatureCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(15.dp)) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }

    @Composable
    private fun StatusPill(text: String, active: Boolean) {
        Surface(shape = RoundedCornerShape(99.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
    }

    private fun startLinux() {
        VmSessionService.active?.startDebian(1152, 720, 120, 60)
    }

    private fun openNativeCube() {
        startActivity(Intent(this, NativeCubeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    private fun formatUptime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0); val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return when { h > 0 -> "%dh %02dm".format(h, m); m > 0 -> "%dm %02ds".format(m, s); else -> "${s}s" }
    }

    private fun shortRuntimeError(raw: String): String = raw.substringBefore("Console tail:").lineSequence().firstOrNull()?.take(220)?.ifBlank { "Runtime failed" } ?: "Runtime failed"

    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }

    private fun copy(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vessel", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun diagnosticsText(state: SessionState): String = buildString {
        appendLine("Vessel ${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_COMMIT}")
        appendLine("protocol=${TermuxUmlController.REQUIRED_PROTOCOL}")
        appendLine("running=${state.running} desktop=${state.kdeInstalled} uptimeMs=${state.uptimeMs}")
        appendLine("${state.progressPercent}% ${state.progressPhase}: ${state.progressDetail}")
        appendLine("error=${state.lastError.ifBlank { "none" }}")
        appendLine("graphics=${state.graphics}")
        appendLine("network=${state.internetStage}")
        appendLine("\n${state.console.takeLast(30000)}")
    }

    private fun shareDiagnostics(state: SessionState) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, diagnosticsText(state)) }, "Share Vessel diagnostics"))
    }
}
