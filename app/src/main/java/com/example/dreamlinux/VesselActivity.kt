package com.example.dreamlinux

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
        VesselTheme {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf(
                            Triple("Machine", Icons.Default.Computer, 0),
                            Triple("Desktop", Icons.Default.DesktopWindows, 1),
                            Triple("Terminal", Icons.Default.Terminal, 2),
                            Triple("Storage", Icons.Default.Storage, 3),
                            Triple("System", Icons.Default.Tune, 4)
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
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Header(state)
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            0 -> MachinePage(state) { page = 1 }
                            1 -> DesktopPage(state)
                            2 -> TerminalPage(state)
                            3 -> StoragePage(state)
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
                primary = Color(0xff8BE8BE),
                onPrimary = Color(0xff003827),
                primaryContainer = Color(0xff123F31),
                secondary = Color(0xffAFC6FF),
                background = Color(0xff070A09),
                surface = Color(0xff0E1311),
                surfaceVariant = Color(0xff17201C),
                outline = Color(0xff33423C),
                errorContainer = Color(0xff4A1D20)
            ),
            content = content
        )
    }

    @Composable
    private fun Header(state: SessionState) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Rootless ARM64 Linux", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val label = when {
                    state.kdeInstalled -> "DESKTOP"
                    state.running -> "RUNNING"
                    state.connected -> "READY"
                    else -> "SETUP"
                }
                StatusPill(label, state.running || state.kdeInstalled)
            }
        }
    }

    @Composable
    private fun MachinePage(state: SessionState, openDesktop: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Debian workstation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (state.lastError.isBlank()) {
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!state.connected) SetupCard(state)
            if (state.lastError.isNotBlank()) ErrorCard(shortRuntimeError(state.lastError))

            ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Debian ARM64", style = MaterialTheme.typography.titleLarge)
                            Text("Persistent ext4 · KDE Plasma", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        StatusPill(if (state.running) "Live" else "Stopped", state.running)
                    }
                    HorizontalDivider()
                    Metric(Icons.Default.Memory, "Runtime", "User Mode Linux · 2 GB")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(Icons.Default.DesktopWindows, "Display", if (state.kdeInstalled) "KDE Plasma · embedded VNC" else state.kdeStage)
                    if (state.lastError.isBlank() && (state.busy || state.running && !state.kdeInstalled)) ProgressBlock(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { if (state.running) VmSessionService.active?.stopVm() else startLinux() },
                            enabled = state.connected && !state.busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.running) "Stop" else "Start Linux")
                        }
                        if (state.kdeInstalled) {
                            FilledTonalButton(onClick = openDesktop) {
                                Icon(Icons.Default.OpenInFull, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Open")
                            }
                        }
                    }
                }
            }

            RuntimeLogCard(state)
            Text("Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FeatureCard(Icons.Default.Security, "No root hypervisor", "Runs ARM64 UML as an ordinary Android/Termux process. No /dev/kvm, Gunyah or GenieZone requirement.")
            FeatureCard(Icons.Default.Bolt, "Real phone GPU", "Debian Vulkan uses Mesa Venus over umshm to virglrenderer/Turnip on the Android GPU.")
            FeatureCard(Icons.Default.DesktopWindows, "Desktop-first", "KDE Plasma runs on guest-local TigerVNC and is displayed by Vessel's embedded RFB client. Termux:X11 is not used here.")
        }
    }

    @Composable
    private fun ProgressBlock(state: SessionState) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.progressDetail.ifBlank { state.message }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (state.progressPercent >= 0) Text("${state.progressPercent}%", style = MaterialTheme.typography.labelMedium)
            }
            if (state.progressPercent >= 0) {
                LinearProgressIndicator(progress = { state.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(state.progressPhase, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun RuntimeLogCard(state: SessionState) {
        val log = state.console.takeLast(16000)
        val scroll = rememberScrollState()
        LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
        ElevatedCard(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live runtime log", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { copy(log) }, enabled = log.isNotBlank()) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Copy")
                    }
                }
                Surface(color = Color(0xff030504), shape = RoundedCornerShape(12.dp)) {
                    SelectionContainer {
                        Text(
                            log.ifBlank { "Runtime output will appear here while Debian starts." },
                            Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 260.dp).verticalScroll(scroll).padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SetupCard(state: SessionState) {
        ElevatedCard(shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("One-time runtime permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Vessel controls the proven UML binaries in Termux. RUN_COMMAND must be granted once.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openAppSettings() }) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("Settings") }
                    OutlinedButton(onClick = { VmSessionService.active?.connectRuntime() }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry") }
                }
                Text(state.capabilities, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @Composable
    private fun ErrorCard(error: String) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Runtime error", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(error, style = MaterialTheme.typography.bodyMedium)
                Text("Full output is shown once in Live runtime log below.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { copy(error) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy reason") }
            }
        }
    }

    @Composable
    private fun DesktopPage(state: SessionState) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("KDE Plasma", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            state.kdeInstalled -> "Interactive Debian desktop"
                            state.lastError.isNotBlank() -> "Runtime error · check Machine"
                            state.running && state.kdeInstalling -> state.progressDetail
                            state.running -> "Debian is running · Plasma not started"
                            else -> "Start Linux first"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                StatusPill(when { state.kdeInstalled -> "LIVE"; state.running -> "STARTING"; else -> "OFFLINE" }, state.running)
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), color = Color.Black, shape = RoundedCornerShape(18.dp)) {
                if (state.kdeInstalled) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> VncFramebufferView(context).apply { requestFocus() } },
                        update = { if (!it.hasFocus()) it.requestFocus() }
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    state.lastError.isNotBlank() -> "Runtime stopped before Plasma was ready"
                                    state.running -> state.progressDetail.ifBlank { "Preparing desktop" }
                                    else -> "Desktop is not running"
                                }
                            )
                            if (state.busy && state.lastError.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button(
                                onClick = { if (state.running) VmSessionService.active?.installKde() else startLinux() },
                                enabled = state.connected && !state.busy
                            ) { Text(if (state.running) "Start Plasma" else "Start Debian + Plasma") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Esc" to KeyEvent.KEYCODE_ESCAPE,
                    "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
                    "Alt" to KeyEvent.KEYCODE_ALT_LEFT,
                    "Tab" to KeyEvent.KEYCODE_TAB,
                    "Super" to KeyEvent.KEYCODE_META_LEFT
                ).forEach { (label, key) ->
                    OutlinedButton(onClick = {
                        VncFramebufferView.active?.sendAndroidKey(true, key)
                        VncFramebufferView.active?.sendAndroidKey(false, key)
                    }) { Text(label) }
                }
            }
        }
    }

    @Composable
    private fun TerminalPage(state: SessionState) {
        var command by remember { mutableStateOf("uname -a") }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Debian terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Command") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { VmSessionService.active?.debianConsole(command) }, enabled = state.running && !state.busy) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Run")
                }
                OutlinedButton(onClick = { copy(state.debianTerminal) }) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), color = Color(0xff030504), shape = RoundedCornerShape(16.dp)) {
                SelectionContainer {
                    Text(
                        state.debianTerminal.ifBlank { "Start Linux, then run commands here." },
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun StoragePage(state: SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Storage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FeatureCard(Icons.Default.Storage, "Persistent ext4", state.vmRoot.ifBlank { "Runtime directory appears after connection." })
            FeatureCard(Icons.Default.Save, "Safe shutdown", "Vessel asks Debian to sync and power down before terminating UML.")
            ElevatedCard(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guest disk tools", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { VmSessionService.active?.debianConsole("df -hT; echo; lsblk") }, enabled = state.running && !state.busy) {
                        Icon(Icons.Default.Analytics, null); Spacer(Modifier.width(8.dp)); Text("Inspect disks")
                    }
                }
            }
        }
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("System", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FeatureCard(Icons.Default.Hub, "Backend", "ARM64 UML → umshm → Venus → virglrenderer/Turnip")
            FeatureCard(Icons.Default.Wifi, "Networking", "umnet/passt provides NAT. The embedded desktop is forwarded only over 127.0.0.1.")
            FeatureCard(Icons.Default.Code, "Build", "Vessel ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_BRANCH.ifBlank { "app/vessel-final" }} · ${BuildConfig.GIT_COMMIT}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { VmSessionService.active?.probeCapabilities() }, enabled = !state.busy) {
                    Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(7.dp)); Text("Runtime check")
                }
                OutlinedButton(onClick = { shareDiagnostics(state) }) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(7.dp)); Text("Share")
                }
            }
            OutlinedButton(onClick = { copy(diagnosticsText(state)) }) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(7.dp)); Text("Copy all diagnostics")
            }
            Text("The full live runtime log is shown only on the Machine page.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(state.capabilities, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun FeatureCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(15.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(3.dp))
                    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun StatusPill(text: String, active: Boolean) {
        Surface(shape = RoundedCornerShape(99.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
    }

    private fun startLinux() {
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 1.35f).toInt().coerceIn(1280, 2560)
        val height = (metrics.heightPixels * 1.1f).toInt().coerceIn(720, 1600)
        val dpi = metrics.densityDpi.coerceIn(120, 220)
        VmSessionService.active?.startDebian(width, height, dpi, 60)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vessel", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shortRuntimeError(raw: String): String {
        val text = raw.substringBefore("Console tail:").trim()
        return when {
            text.contains("Guest command transport timed out", ignoreCase = true) -> "Debian command channel stopped responding."
            text.contains("Guest command timed out", ignoreCase = true) -> "Debian command channel timed out while starting the runtime."
            text.contains("guest command failed", ignoreCase = true) -> text.lineSequence().firstOrNull()?.take(220) ?: "A Debian command failed."
            text.contains("Failed to lock", ignoreCase = true) || text.contains("disk", ignoreCase = true) && text.contains("locked", ignoreCase = true) -> "The Debian disk is still locked by another UML process."
            text.contains("TigerVNC failed", ignoreCase = true) -> "TigerVNC failed to start the KDE Plasma display."
            text.contains("Mesa Venus", ignoreCase = true) && text.contains("not installed", ignoreCase = true) -> "Mesa Venus is missing from the Debian image."
            text.contains("daemon did not start", ignoreCase = true) -> "The Vessel runtime daemon did not start."
            else -> text.lineSequence().firstOrNull()?.take(220)?.ifBlank { "Runtime failed." } ?: "Runtime failed."
        }
    }

    private fun diagnosticsText(state: SessionState): String = buildString {
        appendLine("Vessel ${BuildConfig.VERSION_NAME}")
        appendLine("${BuildConfig.GIT_BRANCH} ${BuildConfig.GIT_COMMIT}")
        appendLine("Backend: ${state.backend}")
        appendLine("Running: ${state.running} desktop=${state.kdeInstalled}")
        appendLine("Stage: ${state.stage}")
        appendLine("Progress: ${state.progressPercent}% ${state.progressPhase} · ${state.progressDetail}")
        appendLine("Error: ${state.lastError.ifBlank { "none" }}")
        appendLine("Graphics: ${state.graphics}")
        appendLine("Network: ${state.internetStage}")
        appendLine("Capabilities: ${state.capabilities}")
        appendLine("\nRuntime log:\n${state.console.takeLast(30000)}")
        if (state.debianTerminal.isNotBlank()) appendLine("\nTerminal:\n${state.debianTerminal.takeLast(12000)}")
    }

    private fun shareDiagnostics(state: SessionState) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, diagnosticsText(state))
        }, "Share Vessel diagnostics"))
    }
}
