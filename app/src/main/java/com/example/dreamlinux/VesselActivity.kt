package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
                        val items = listOf(
                            Triple("Machine", Icons.Default.Computer, 0),
                            Triple("Desktop", Icons.Default.DesktopWindows, 1),
                            Triple("Terminal", Icons.Default.Terminal, 2),
                            Triple("Storage", Icons.Default.Storage, 3),
                            Triple("System", Icons.Default.Tune, 4)
                        )
                        items.forEach { (label, icon, index) ->
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
        val scheme = darkColorScheme(
            primary = Color(0xff8BE8BE),
            onPrimary = Color(0xff003827),
            primaryContainer = Color(0xff123F31),
            secondary = Color(0xffAFC6FF),
            background = Color(0xff070A09),
            surface = Color(0xff0E1311),
            surfaceVariant = Color(0xff17201C),
            outline = Color(0xff33423C)
        )
        MaterialTheme(colorScheme = scheme, content = content)
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
                StatusPill(
                    when {
                        state.kdeInstalled -> "DESKTOP"
                        state.running -> "RUNNING"
                        state.connected -> "READY"
                        else -> "SETUP"
                    },
                    state.running
                )
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
            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!state.connected) SetupCard(state)

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
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Text("Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FeatureCard(Icons.Default.Security, "No root hypervisor", "Runs the ARM64 UML kernel as an ordinary Android/Termux process. No /dev/kvm, Gunyah or GenieZone requirement.")
            FeatureCard(Icons.Default.Bolt, "Real phone GPU", "Debian Vulkan uses Mesa Venus over the proven umshm relay to virglrenderer/Turnip on the Android GPU.")
            FeatureCard(Icons.Default.DesktopWindows, "Desktop-first", "Plasma runs on a guest-local TigerVNC X server. This avoids the remote X11 PutImage failure we spent hours isolating while keeping keyboard and touch interactive.")
            FeatureCard(Icons.Default.Folder, "Persistent machine", "The ext4 guest disk survives app restarts. Stop only powers the guest down; it does not wipe Linux.")
        }
    }

    @Composable
    private fun SetupCard(state: SessionState) {
        ElevatedCard(shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("One-time runtime permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Vessel controls the already-proven UML binaries in Termux. Android requires you to allow this app to run Termux commands.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openAppSettings() }) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("Permission settings") }
                    OutlinedButton(onClick = { VmSessionService.active?.connectRuntime() }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry") }
                }
                Text(state.capabilities, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @Composable
    private fun DesktopPage(state: SessionState) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("KDE Plasma", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (state.kdeInstalled) "Interactive Debian desktop" else "Start Linux first", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(if (state.kdeInstalled) "LIVE" else "OFFLINE", state.kdeInstalled)
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Desktop is not running")
                            Button(onClick = { startLinux() }, enabled = state.connected && !state.busy) { Text("Start Debian + Plasma") }
                        }
                    }
                }
            }
            DesktopKeys()
        }
    }

    @Composable
    private fun DesktopKeys() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Esc" to KeyEvent.KEYCODE_ESCAPE,
                "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
                "Alt" to KeyEvent.KEYCODE_ALT_LEFT,
                "Tab" to KeyEvent.KEYCODE_TAB,
                "Super" to KeyEvent.KEYCODE_META_LEFT
            ).forEach { (label, key) ->
                OutlinedButton(
                    onClick = {
                        VncFramebufferView.active?.sendAndroidKey(true, key)
                        VncFramebufferView.active?.sendAndroidKey(false, key)
                    },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp)
                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
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
                OutlinedButton(onClick = { copy(state.debianTerminal) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
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
            FeatureCard(Icons.Default.Storage, "Persistent ext4", state.vmRoot.ifBlank { "Vessel runtime directory will appear after connection." })
            FeatureCard(Icons.Default.Save, "Safe shutdown", "Vessel asks Debian to sync and power down before terminating the UML process, reducing dirty-filesystem recovery on the next boot.")
            ElevatedCard(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guest disk tools", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { VmSessionService.active?.debianConsole("df -hT; echo; lsblk") }, enabled = state.running && !state.busy) {
                        Icon(Icons.Default.Analytics, null); Spacer(Modifier.width(8.dp)); Text("Inspect disks")
                    }
                }
            }
            Text("VM cloning, image resize, qcow2 conversion and import/export are intentionally not faked here. DroidVM exposes those through QEMU tooling; Vessel's current persistent UML disk is raw ext4, so those operations need a dedicated safe offline manager.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("System", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FeatureCard(Icons.Default.Hub, "Backend", "ARM64 UML → umshm → Venus → virglrenderer/Turnip. The legacy AVF/Shizuku code remains in-tree but is not used for normal Vessel startup.")
            FeatureCard(Icons.Default.Wifi, "Networking", "umnet/passt provides NAT to Debian. The embedded desktop is forwarded only over 127.0.0.1, so TigerVNC is not exposed to the LAN.")
            FeatureCard(Icons.Default.Code, "Build", "Vessel ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_BRANCH} · ${BuildConfig.GIT_COMMIT}")
            Button(onClick = { VmSessionService.active?.probeCapabilities() }, enabled = !state.busy) { Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(7.dp)); Text("Run runtime check") }
            OutlinedButton(onClick = { shareDiagnostics(state) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(7.dp)); Text("Share diagnostics") }
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
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun StatusPill(text: String, active: Boolean) {
        Surface(shape = RoundedCornerShape(999.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
        }
    }

    private fun startLinux() {
        val m = resources.displayMetrics
        VmSessionService.active?.startDebian(
            m.widthPixels.coerceAtLeast(960),
            m.heightPixels.coerceAtLeast(720),
            m.densityDpi.coerceAtLeast(96),
            60
        )
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vessel", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics(state: SessionState) {
        val text = buildString {
            appendLine("Vessel ${BuildConfig.VERSION_NAME}")
            appendLine("${BuildConfig.GIT_BRANCH} ${BuildConfig.GIT_COMMIT}")
            appendLine("Backend: ${state.backend}")
            appendLine("Running: ${state.running} desktop=${state.kdeInstalled}")
            appendLine("Stage: ${state.stage}")
            appendLine("Graphics: ${state.graphics}")
            appendLine("Network: ${state.internetStage}")
            appendLine("Capabilities: ${state.capabilities}")
            appendLine("\nRuntime:\n${state.console.takeLast(30000)}")
            appendLine("\nTerminal:\n${state.debianTerminal.takeLast(30000)}")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share Vessel diagnostics"))
    }
}
