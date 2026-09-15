package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TERMUX_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
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
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
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

    private fun copyRuntimeLog(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Vessel runtime log", text))
        Toast.makeText(this, "Runtime log copied", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun VesselApp() {
        val state by VmSessionService.state.collectAsStateWithLifecycle()
        var page by remember { mutableIntStateOf(0) }
        var fullscreen by remember { mutableStateOf(false) }

        LaunchedEffect(fullscreen) {
            immersive()
            requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        LaunchedEffect(state.frameReachedApp) {
            if (state.frameReachedApp) page = 1
        }

        VesselTheme {
            if (fullscreen && state.running) {
                FullscreenDesktop { fullscreen = false }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { VesselTopBar(state) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavItem(page == 0, "Machine", Icons.Default.Computer) { page = 0 }
                            NavItem(page == 1, "Display", Icons.Default.DesktopWindows) { page = 1 }
                            NavItem(page == 2, "Terminal", Icons.Default.Terminal) { page = 2 }
                            NavItem(page == 3, "System", Icons.Default.Tune) { page = 3 }
                        }
                    },
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
                primary = Color(0xff72F1B8),
                onPrimary = Color(0xff002E20),
                primaryContainer = Color(0xff113D30),
                secondary = Color(0xff8CB8FF),
                background = Color(0xff060807),
                surface = Color(0xff0D110F),
                surfaceVariant = Color(0xff171D1A),
                outline = Color(0xff33443D),
                error = Color(0xffFFB4AB),
                errorContainer = Color(0xff3B171A),
            ),
            content = content,
        )
    }

    @Composable
    private fun RowScope.NavItem(
        selected: Boolean,
        label: String,
        icon: ImageVector,
        onClick: () -> Unit,
    ) {
        NavigationBarItem(
            selected = selected,
            onClick = onClick,
            icon = { Icon(icon, null) },
            label = { Text(label) },
        )
    }

    @Composable
    private fun VesselTopBar(state: SessionState) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Rootless ARM64 Linux · VirtIO GPU · VirGL · Adreno",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    when {
                        state.displayReady -> "VISIBLE"
                        state.frameReachedApp -> "FRAME"
                        state.running -> "RUNNING"
                        state.connected -> "READY"
                        else -> "SETUP"
                    },
                    state.running,
                )
            }
        }
    }

    @Composable
    private fun MachinePage(state: SessionState, openDisplay: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ElevatedCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Debian workstation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (state.running) state.message else "Persistent Linux PC on your phone",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(if (state.running) "LIVE" else "OFF", state.running)
                    }
                    if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                    if (state.busy || state.running) ProgressBlock(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (state.running) {
                                    VmSessionService.active?.stopVm()
                                } else {
                                    startLinux()
                                    openDisplay()
                                }
                            },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (state.running) "Stop Linux" else "Start Linux")
                        }
                        if (state.running) {
                            OutlinedButton(onClick = openDisplay) {
                                Icon(Icons.Default.DesktopWindows, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Display")
                            }
                        }
                    }
                }
            }

            Text("Machine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Metric(Icons.Default.Memory, "Memory", "8 GB UML guest · 6 vCPUs")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    Metric(Icons.Default.DesktopWindows, "Android Surface", state.presenterStatus)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(Icons.Default.Storage, "Disk", "Persistent ext4")
                }
            }
            LogCard(state)
        }
    }

    @Composable
    private fun DesktopPage(state: SessionState, fullscreen: () -> Unit) {
        var mode by remember { mutableStateOf(VncFramebufferView.PointerMode.DIRECT) }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Linux display", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state.displayReady -> "Real GPU frame presented · ${uptime(state.uptimeMs)}"
                            state.frameReachedApp -> "Frame reached Vessel · attaching Android Surface"
                            state.running -> state.progressDetail
                            else -> "Press Start Linux first"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.displayReady) StatusPill("VISIBLE", true)
            }

            if (state.running || state.busy) {
                DesktopControls(
                    mode = mode,
                    setMode = { newMode ->
                        mode = newMode
                        VncFramebufferView.active?.setPointerMode(newMode)
                    },
                    fullscreen = fullscreen,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Surface(Modifier.fillMaxSize(), color = Color.Black, shape = RoundedCornerShape(16.dp)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                VncFramebufferView(context).apply {
                                    setPointerMode(mode)
                                    requestFocus()
                                }
                            },
                            update = { view ->
                                view.setPointerMode(mode)
                                if (!view.hasFocus()) view.requestFocus()
                            },
                        )
                    }
                    if (!state.displayReady) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center).padding(18.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xD9101512),
                        ) {
                            Column(
                                Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                if (state.running) LinearProgressIndicator(Modifier.width(220.dp))
                                Text(state.message)
                                Text(
                                    "Presenter: ${state.presenterStatus}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Linux is stopped")
                            Button(onClick = { startLinux() }, enabled = !state.busy) { Text("Start Linux") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DesktopControls(
        mode: VncFramebufferView.PointerMode,
        setMode: (VncFramebufferView.PointerMode) -> Unit,
        fullscreen: () -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterChip(
                selected = mode == VncFramebufferView.PointerMode.DIRECT,
                onClick = { setMode(VncFramebufferView.PointerMode.DIRECT) },
                label = { Text("Touch") },
                leadingIcon = { Icon(Icons.Default.TouchApp, null) },
            )
            FilterChip(
                selected = mode == VncFramebufferView.PointerMode.TRACKPAD,
                onClick = { setMode(VncFramebufferView.PointerMode.TRACKPAD) },
                label = { Text("Trackpad") },
                leadingIcon = { Icon(Icons.Default.Mouse, null) },
            )
            AssistChip(
                onClick = { VncFramebufferView.active?.showKeyboard() },
                label = { Text("Keyboard") },
                leadingIcon = { Icon(Icons.Default.Keyboard, null) },
            )
            AssistChip(
                onClick = fullscreen,
                label = { Text("Fullscreen") },
                leadingIcon = { Icon(Icons.Default.OpenInFull, null) },
            )
        }
    }

    @Composable
    private fun FullscreenDesktop(exit: () -> Unit) {
        var mode by remember { mutableStateOf(VncFramebufferView.PointerMode.TRACKPAD) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> VncFramebufferView(context).apply { setPointerMode(mode); requestFocus() } },
                update = { view -> view.setPointerMode(mode) },
            )
            Surface(
                Modifier.align(Alignment.TopCenter).padding(8.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xD9111614),
            ) {
                Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        mode = VncFramebufferView.PointerMode.DIRECT
                        VncFramebufferView.active?.setPointerMode(mode)
                    }) { Icon(Icons.Default.TouchApp, "Touch") }
                    IconButton(onClick = {
                        mode = VncFramebufferView.PointerMode.TRACKPAD
                        VncFramebufferView.active?.setPointerMode(mode)
                    }) { Icon(Icons.Default.Mouse, "Trackpad") }
                    IconButton(onClick = { VncFramebufferView.active?.showKeyboard() }) {
                        Icon(Icons.Default.Keyboard, "Keyboard")
                    }
                    IconButton(onClick = exit) { Icon(Icons.Default.CloseFullscreen, "Exit fullscreen") }
                }
            }
        }
    }

    @Composable
    private fun ExtraKeys() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(
                "Esc" to 0xff1b,
                "Tab" to 0xff09,
                "Ctrl" to 0xffe3,
                "Alt" to 0xffe9,
                "Super" to 0xffeb,
                "←" to 0xff51,
                "↑" to 0xff52,
                "↓" to 0xff54,
                "→" to 0xff53,
            ).forEach { (label, key) ->
                OutlinedButton(
                    onClick = { VncFramebufferView.active?.tapKey(key) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                ) { Text(label) }
            }
        }
    }

    @Composable
    private fun TerminalPage(state: SessionState) {
        var command by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Surface(
                Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xff050706),
            ) {
                SelectionContainer {
                    Text(
                        state.debianTerminal.ifBlank { "Start Linux, then run commands here." },
                        Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Debian command") },
                )
                Button(
                    onClick = {
                        if (command.isNotBlank()) {
                            VmSessionService.active?.debianConsole(command)
                            command = ""
                        }
                    },
                    enabled = state.running && state.guestReady && !state.busy,
                ) { Icon(Icons.Default.Send, null) }
            }
        }
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("System", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric(Icons.Default.Bolt, "Runtime", state.runtimeRevision.ifBlank { "Protocol 38 · not started" })
                    Metric(Icons.Default.Bolt, "Renderer", state.graphics)
                    Metric(Icons.Default.DesktopWindows, "Transport", state.displayTransport.ifBlank { "VirtIO GPU → Vessel" })
                    Metric(Icons.Default.DesktopWindows, "Presenter", state.presenterStatus)
                    Metric(Icons.Default.Storage, "Runtime path", state.vmRoot.ifBlank { "~/venus-wsi-local" })
                }
            }
            Button(
                onClick = { VmSessionService.active?.runGuestVulkanProbe() },
                enabled = state.guestReady && !state.busy,
            ) { Text("Run GPU diagnostics") }
            LogCard(state)
        }
    }

    @Composable
    private fun StatusPill(label: String, active: Boolean) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                label,
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun Metric(icon: ImageVector, label: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun ErrorStrip(text: String) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Text(text, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
    }

    @Composable
    private fun ProgressBlock(state: SessionState) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.progressPercent in 0..100) {
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(state.progressDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun LogCard(state: SessionState) {
        if (state.console.isBlank()) return
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Runtime log", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { copyRuntimeLog(state.console) }) {
                        Text("Copy logs")
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xff050706),
                ) {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        SelectionContainer {
                            Text(
                                state.console.takeLast(40_000),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .verticalScroll(vertical)
                                    .horizontalScroll(horizontal),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun uptime(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        return if (minutes > 0) "${minutes}m ${seconds % 60}s" else "${seconds}s"
    }
}
