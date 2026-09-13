package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class VesselActivity : ComponentActivity() {
    companion object { private const val TERMUX_PERMISSION_REQUEST = 41 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        startForegroundService(Intent(this, VmSessionService::class.java))
        setContent { VesselApp() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TERMUX_PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            VmSessionService.active?.connectRuntime()
            VmSessionService.active?.startVm()
        }
    }

    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun startLinux() {
        val termux = try {
            packageManager.getPackageInfo(TermuxUmlController.TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (!termux) {
            Toast.makeText(this, "Install Termux first", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(TermuxUmlController.RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TermuxUmlController.RUN_COMMAND_PERMISSION), TERMUX_PERMISSION_REQUEST)
            return
        }
        VmSessionService.active?.startVm()
    }

    @Composable
    private fun VesselApp() {
        val state by VmSessionService.state.collectAsStateWithLifecycle()
        var page by remember { mutableIntStateOf(0) }
        var fullscreen by remember { mutableStateOf(false) }

        LaunchedEffect(fullscreen) {
            immersive()
            requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        VesselTheme {
            if (fullscreen && state.kdeInstalled) {
                FullscreenDesktop { fullscreen = false }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { VesselTopBar(state) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavItem(page == 0, "Machine", Icons.Default.Computer) { page = 0 }
                            NavItem(page == 1, "Desktop", Icons.Default.DesktopWindows) { page = 1 }
                            NavItem(page == 2, "Terminal", Icons.Default.Terminal) { page = 2 }
                            NavItem(page == 3, "System", Icons.Default.Tune) { page = 3 }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            0 -> MachinePage(state) { page = 1 }
                            1 -> DesktopPage(state) { fullscreen = true }
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
                primary = Color(0xff72F1B8), onPrimary = Color(0xff002E20),
                primaryContainer = Color(0xff113D30), secondary = Color(0xff8CB8FF),
                background = Color(0xff060807), surface = Color(0xff0D110F),
                surfaceVariant = Color(0xff171D1A), outline = Color(0xff33443D),
                error = Color(0xffFFB4AB), errorContainer = Color(0xff3B171A)
            ),
            content = content
        )
    }

    @Composable
    private fun RowScope.NavItem(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
        NavigationBarItem(
            selected = selected,
            onClick = onClick,
            icon = { Icon(icon, null) },
            label = { Text(label) }
        )
    }

    @Composable
    private fun VesselTopBar(state: SessionState) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Rootless ARM64 Linux · Venus on Adreno", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(when { state.kdeInstalled -> "DESKTOP"; state.running -> "RUNNING"; state.connected -> "READY"; else -> "SETUP" }, state.running)
            }
        }
    }

    @Composable
    private fun MachinePage(state: SessionState, openDesktop: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent) {
                Column(
                    Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xff12382B), Color(0xff0A1511), Color(0xff080A09)))).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Debian workstation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(if (state.running) "Live · ${uptime(state.uptimeMs)}" else "Persistent Linux PC on your phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(if (state.running) "LIVE" else "OFF", state.running)
                    }
                    if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                    if (state.busy || (state.running && !state.kdeInstalled)) ProgressBlock(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (state.running) VmSessionService.active?.stopVm() else startLinux() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (state.running) "Stop Linux" else "Start Linux")
                        }
                        if (state.kdeInstalled) FilledTonalButton(onClick = openDesktop) {
                            Icon(Icons.Default.DesktopWindows, null); Spacer(Modifier.width(6.dp)); Text("Desktop")
                        }
                    }
                }
            }

            Text("Machine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(Icons.Default.Memory, "Memory", "8 GB UML guest")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(Icons.Default.DesktopWindows, "Desktop", if (state.kdeInstalled) "KDE Plasma · embedded display" else state.kdeStage)
                    Metric(Icons.Default.Storage, "Disk", "Persistent ext4")
                    Metric(Icons.Default.TouchApp, "Input", "Touch · trackpad · mouse · keyboard")
                }
            }

            if (state.kdeInstalled) {
                Text("Linux apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(Icons.Default.Public, "Firefox") { VmSessionService.active?.launchFirefox() }
                    AppButton(Icons.Default.Folder, "Files") { VmSessionService.active?.launchDesktopApp("dolphin") }
                    AppButton(Icons.Default.Edit, "Kate") { VmSessionService.active?.launchDesktopApp("kate") }
                }
            }
            LogCard(state)
        }
    }

    @Composable
    private fun DesktopPage(state: SessionState, fullscreen: () -> Unit) {
        var mode by remember { mutableStateOf(VncFramebufferView.PointerMode.DIRECT) }
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Linux desktop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (state.kdeInstalled) "KDE Plasma · ${uptime(state.uptimeMs)}" else state.progressDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.kdeInstalled) StatusPill("LIVE", true)
            }

            if (state.kdeInstalled) {
                DesktopControls(mode, { newMode ->
                    mode = newMode
                    VncFramebufferView.active?.setPointerMode(newMode)
                }, fullscreen)
                Surface(Modifier.weight(1f).fillMaxWidth(), color = Color.Black, shape = RoundedCornerShape(16.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> VncFramebufferView(context).apply { setPointerMode(mode); requestFocus() } },
                        update = { view -> view.setPointerMode(mode); if (!view.hasFocus()) view.requestFocus() }
                    )
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(if (state.running) "Plasma isn't running yet" else "Linux is stopped")
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button(onClick = { if (state.running) VmSessionService.active?.installKde() else startLinux() }, enabled = !state.busy) {
                                Text(if (state.running) "Start Plasma" else "Start Linux + Plasma")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DesktopControls(mode: VncFramebufferView.PointerMode, setMode: (VncFramebufferView.PointerMode) -> Unit, fullscreen: () -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = mode == VncFramebufferView.PointerMode.DIRECT, onClick = { setMode(VncFramebufferView.PointerMode.DIRECT) }, label = { Text("Touch") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) })
            FilterChip(selected = mode == VncFramebufferView.PointerMode.TRACKPAD, onClick = { setMode(VncFramebufferView.PointerMode.TRACKPAD) }, label = { Text("Trackpad") }, leadingIcon = { Icon(Icons.Default.Mouse, null) })
            AssistChip(onClick = { VncFramebufferView.active?.showKeyboard() }, label = { Text("Keyboard") }, leadingIcon = { Icon(Icons.Default.Keyboard, null) })
            AssistChip(onClick = fullscreen, label = { Text("Fullscreen") }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) })
        }
    }

    @Composable
    private fun FullscreenDesktop(exit: () -> Unit) {
        var mode by remember { mutableStateOf(VncFramebufferView.PointerMode.TRACKPAD) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> VncFramebufferView(context).apply { setPointerMode(mode); requestFocus() } },
                update = { view -> view.setPointerMode(mode) }
            )
            Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = RoundedCornerShape(22.dp), color = Color(0xD9111614)) {
                Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { mode = VncFramebufferView.PointerMode.DIRECT; VncFramebufferView.active?.setPointerMode(mode) }) { Icon(Icons.Default.TouchApp, "Touch") }
                    IconButton(onClick = { mode = VncFramebufferView.PointerMode.TRACKPAD; VncFramebufferView.active?.setPointerMode(mode) }) { Icon(Icons.Default.Mouse, "Trackpad") }
                    IconButton(onClick = { VncFramebufferView.active?.showKeyboard() }) { Icon(Icons.Default.Keyboard, "Keyboard") }
                    IconButton(onClick = exit) { Icon(Icons.Default.CloseFullscreen, "Exit fullscreen") }
                }
            }
        }
    }

    @Composable
    private fun ExtraKeys() {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Esc" to 0xff1b, "Tab" to 0xff09, "Ctrl" to 0xffe3, "Alt" to 0xffe9, "Super" to 0xffeb, "←" to 0xff51, "↑" to 0xff52, "↓" to 0xff54, "→" to 0xff53).forEach { (label, key) ->
                OutlinedButton(onClick = { VncFramebufferView.active?.tapKey(key) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)) { Text(label) }
            }
        }
    }

    @Composable
    private fun TerminalPage(state: SessionState) {
        var command by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xff050706)) {
                SelectionContainer {
                    Text(state.debianTerminal.ifBlank { "Start Linux, then run commands here." }, Modifier.padding(14.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Debian command") })
                Button(onClick = { if (command.isNotBlank()) { VmSessionService.active?.debianConsole(command); command = "" } }, enabled = state.running && !state.busy) { Icon(Icons.Default.Send, null) }
            }
        }
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("System", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Metric(Icons.Default.Info, "Build", "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}")
                    Metric(Icons.Default.Hub, "Backend", "ARM64 UML · protocol ${TermuxUmlController.REQUIRED_PROTOCOL}")
                    Metric(Icons.Default.Bolt, "GPU", state.graphics)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(Icons.Default.Storage, "Runtime", state.vmRoot.ifBlank { "Not connected" })
                }
            }
            Button(onClick = { VmSessionService.active?.probeCapabilities() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(7.dp)); Text("Run runtime check")
            }
            OutlinedButton(onClick = { VmSessionService.active?.runGuestVulkanProbe() }, enabled = state.running && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Memory, null); Spacer(Modifier.width(7.dp)); Text("Vulkan diagnostics")
            }
            LogCard(state)
        }
    }

    @Composable
    private fun AppButton(icon: ImageVector, label: String, action: () -> Unit) {
        FilledTonalButton(onClick = action) { Icon(icon, null); Spacer(Modifier.width(6.dp)); Text(label) }
    }

    @Composable
    private fun StatusPill(label: String, good: Boolean) {
        Surface(shape = RoundedCornerShape(999.dp), color = if (good) Color(0xff153C2F) else MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, Modifier.padding(horizontal = 11.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Metric(icon: ImageVector, name: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ProgressBlock(state: SessionState) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val detail = state.progressDetail.ifBlank { state.message }
            if (state.progressPercent in 0..100) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text("${state.progressPercent}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = { state.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(detail, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun ErrorStrip(text: String) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Text(text.take(220), Modifier.fillMaxWidth().padding(10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun LogCard(state: SessionState) {
        val context = LocalContext.current
        ElevatedCard(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Runtime log", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = state.console.isNotBlank(),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Vessel runtime log", state.console))
                            Toast.makeText(context, "Full runtime log copied", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy logs")
                    }
                }
                SelectionContainer {
                    Text(state.console.takeLast(5000).ifBlank { "No runtime output yet." }, Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(top = 7.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    private fun uptime(ms: Long): String {
        val total = ms / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }
}
