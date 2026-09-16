package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class VesselActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        VesselHostDebug.initialize(this)
        startForegroundService(Intent(this, VmSessionService::class.java))
        setContent { VesselApp() }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        startForegroundService(Intent(this, VmSessionService::class.java))
        VmSessionService.active?.refreshAvailability()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun startLinux() {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        VmSessionService.active?.refreshAvailability()
        VmSessionService.active?.startVm()
    }

    private fun copyText(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
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
        LaunchedEffect(state.frameReachedApp) { if (state.frameReachedApp) page = 1 }

        VesselTheme {
            if (fullscreen && (state.running || state.busy)) {
                FullscreenDesktop { fullscreen = false }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { VesselTopBar(state) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavItem(page == 0, "Machine", Icons.Default.Computer) { page = 0 }
                            NavItem(page == 1, "Display", Icons.Default.DesktopWindows) { page = 1 }
                            NavItem(page == 2, "Apps", Icons.Default.Laptop) { page = 2 }
                            NavItem(page == 3, "Terminal", Icons.Default.Terminal) { page = 3 }
                            NavItem(page == 4, "System", Icons.Default.Tune) { page = 4 }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            0 -> MachinePage(state) { page = 1 }
                            1 -> DesktopPage(state) { fullscreen = true }
                            2 -> AppsPage(state)
                            3 -> TerminalPage(state)
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
    private fun RowScope.NavItem(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
        NavigationBarItem(selected=selected, onClick=onClick, icon={Icon(icon,null)}, label={Text(label)})
    }

    @Composable
    private fun VesselTopBar(state: SessionState) {
        Surface(color=MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=RoundedCornerShape(14.dp), color=MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(9.dp), tint=MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                    Text("Rootless ARM64 Linux · VirtIO GPU · AHardwareBuffer · Adreno", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val label = when {
                    state.stage == "stopping" -> "STOPPING"
                    state.displayReady -> "VISIBLE"
                    state.frameReachedApp -> "FRAME"
                    state.running -> "RUNNING"
                    state.busy -> "STARTING"
                    state.connected -> "READY"
                    else -> "SETUP"
                }
                StatusPill(label, state.running || state.busy || state.displayReady)
            }
        }
    }

    @Composable
    private fun MachinePage(state: SessionState, openDisplay: () -> Unit) {
        val stopping = state.stage == "stopping"
        val canStop = state.running || state.busy
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            ElevatedCard(shape=RoundedCornerShape(26.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement=Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Debian workstation", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                            Text(if (state.running || state.busy) state.message else "Persistent Linux PC on your phone", color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(when { stopping->"STOPPING"; state.running->"LIVE"; state.busy->"STARTING"; else->"OFF" }, canStop)
                    }
                    if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                    if (!state.storageReady) ErrorStrip("Vessel needs file access once so the persistent Linux disk can live in Download/LinuxPC.")
                    if (state.busy || state.running) ProgressBlock(state)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick={if(canStop) VmSessionService.active?.stopVm() else { startLinux(); openDisplay() }},
                            enabled=!stopping,
                            modifier=Modifier.weight(1f),
                        ) {
                            Icon(if(canStop) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(7.dp))
                            Text(when {
                                stopping -> "Stopping…"
                                state.busy && !state.running -> "Cancel startup"
                                state.running -> "Stop Linux"
                                !state.storageReady -> "Grant storage"
                                else -> "Start Linux"
                            })
                        }
                        if (state.running) OutlinedButton(onClick=openDisplay) { Icon(Icons.Default.DesktopWindows,null); Spacer(Modifier.width(6.dp)); Text("Display") }
                    }
                }
            }

            Text("Machine", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold)
            ElevatedCard(shape=RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Metric(Icons.Default.Memory, "Memory", "${if(state.guestMemoryMb>0) state.guestMemoryMb else 4096} MiB UML guest · 6 vCPUs")
                    Metric(Icons.Default.DesktopWindows, "Desktop", "${state.guestDisplayWidth} × ${state.guestDisplayHeight} · stable landscape")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    Metric(Icons.Default.DesktopWindows, "Android Surface", state.presenterStatus)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(Icons.Default.Storage, "Disk", "Persistent sparse ext4 · safe auto-grow")
                }
            }
            LogCard(state)
        }
    }

    @Composable
    private fun DesktopPage(state: SessionState, fullscreen: () -> Unit) {
        var mode by remember { mutableStateOf(LinuxDesktopView.PointerMode.DIRECT) }
        Column(Modifier.fillMaxSize().padding(horizontal=10.dp, vertical=8.dp), verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Linux display", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                    Text(
                        when {
                            state.displayReady -> "Real GPU frame presented · ${uptime(state.uptimeMs)}"
                            state.frameReachedApp -> "Validated GPU frame reached Vessel"
                            state.running || state.busy -> state.progressDetail
                            else -> "Press Start Linux first"
                        },
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                    )
                }
                if (state.displayReady) StatusPill("VISIBLE", true)
            }

            if (state.running || state.busy) {
                DesktopControls(mode, { newMode -> mode=newMode; LinuxDesktopView.active?.setPointerMode(newMode) }, fullscreen)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Surface(Modifier.fillMaxSize(), color=Color.Black, shape=RoundedCornerShape(16.dp)) {
                        AndroidView(
                            modifier=Modifier.fillMaxSize(),
                            factory={context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() }},
                            update={view -> view.setPointerMode(mode); if(!view.hasFocus()) view.requestFocus()},
                        )
                    }
                    if (!state.displayReady) {
                        Surface(Modifier.align(Alignment.Center).padding(18.dp), shape=RoundedCornerShape(18.dp), color=Color(0xD9101512)) {
                            Column(Modifier.padding(18.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                LinearProgressIndicator(progress={state.progressPercent.coerceIn(0,100)/100f}, modifier=Modifier.width(220.dp))
                                Text("${state.message} · ${state.progressPercent.coerceIn(0,100)}%", maxLines=3, overflow=TextOverflow.Ellipsis)
                                Text("Presenter: ${state.presenterStatus}", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant, maxLines=1, overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment=Alignment.Center) {
                    ElevatedCard(shape=RoundedCornerShape(24.dp), modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint=MaterialTheme.colorScheme.primary)
                            Text("Linux is stopped")
                            if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                            Button(onClick={startLinux()}, enabled=!state.busy) { Text(if(state.storageReady) "Start Linux" else "Grant storage") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DesktopControls(mode: LinuxDesktopView.PointerMode, setMode: (LinuxDesktopView.PointerMode)->Unit, fullscreen: ()->Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            FilterChip(selected=mode==LinuxDesktopView.PointerMode.DIRECT, onClick={setMode(LinuxDesktopView.PointerMode.DIRECT)}, label={Text("Touch")}, leadingIcon={Icon(Icons.Default.TouchApp,null)})
            FilterChip(selected=mode==LinuxDesktopView.PointerMode.TRACKPAD, onClick={setMode(LinuxDesktopView.PointerMode.TRACKPAD)}, label={Text("Trackpad")}, leadingIcon={Icon(Icons.Default.Mouse,null)})
            AssistChip(onClick={LinuxDesktopView.active?.showKeyboard()}, label={Text("Keyboard")}, leadingIcon={Icon(Icons.Default.Keyboard,null)})
            AssistChip(onClick=fullscreen, label={Text("Fullscreen")}, leadingIcon={Icon(Icons.Default.OpenInFull,null)})
        }
    }

    @Composable
    private fun FullscreenDesktop(exit: ()->Unit) {
        var mode by remember { mutableStateOf(LinuxDesktopView.PointerMode.TRACKPAD) }
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(controlsVisible, mode) {
            if (controlsVisible) {
                delay(2600)
                controlsVisible = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier=Modifier.fillMaxSize(),
                factory={context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() }},
                update={it.setPointerMode(mode)},
            )
            if (controlsVisible) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top=8.dp),
                    shape=RoundedCornerShape(999.dp),
                    color=Color(0xC9101512),
                ) {
                    Row(Modifier.padding(horizontal=6.dp, vertical=2.dp), verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={mode=LinuxDesktopView.PointerMode.DIRECT;LinuxDesktopView.active?.setPointerMode(mode);controlsVisible=true}) { Icon(Icons.Default.TouchApp,"Touch") }
                        IconButton(onClick={mode=LinuxDesktopView.PointerMode.TRACKPAD;LinuxDesktopView.active?.setPointerMode(mode);controlsVisible=true}) { Icon(Icons.Default.Mouse,"Trackpad") }
                        IconButton(onClick={LinuxDesktopView.active?.showKeyboard();controlsVisible=true}) { Icon(Icons.Default.Keyboard,"Keyboard") }
                        IconButton(onClick=exit) { Icon(Icons.Default.CloseFullscreen,"Exit fullscreen") }
                    }
                }
            } else {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top=5.dp),
                    shape=RoundedCornerShape(999.dp),
                    color=Color(0x8A101512),
                    onClick={controlsVisible=true},
                ) {
                    Box(Modifier.width(58.dp).height(14.dp), contentAlignment=Alignment.Center) {
                        Surface(Modifier.width(28.dp).height(3.dp), shape=RoundedCornerShape(999.dp), color=MaterialTheme.colorScheme.onSurfaceVariant) {}
                    }
                }
            }
        }
    }

    @Composable
    private fun ExtraKeys() {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            listOf("Esc" to 1,"Tab" to 15,"Ctrl" to 29,"Alt" to 56,"Super" to 125,"←" to 105,"↑" to 103,"↓" to 108,"→" to 106).forEach { (label,key) ->
                OutlinedButton(onClick={LinuxDesktopView.active?.tapKey(key)}, contentPadding=PaddingValues(horizontal=10.dp,vertical=3.dp)) { Text(label) }
            }
        }
    }

    @Composable
    private fun AppsPage(state: SessionState) {
        val store by VmSessionService.appStore.collectAsStateWithLifecycle()
        var query by remember { mutableStateOf(store.query) }
        LaunchedEffect(state.guestReady) {
            if (state.guestReady && store.apps.isEmpty() && !store.loading) VmSessionService.active?.refreshApps("")
        }
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Debian apps", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                    Text("Real APT packages for this ARM64 machine", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick={VmSessionService.active?.refreshApps("")}, enabled=state.guestReady&&!store.loading) { Text("Popular") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(
                    value=query,
                    onValueChange={query=it},
                    modifier=Modifier.weight(1f),
                    singleLine=true,
                    label={Text("Search Debian")},
                    placeholder={Text("browser, media, office…")},
                )
                Button(onClick={VmSessionService.active?.refreshApps(query)}, enabled=state.guestReady&&!store.loading&&query.isNotBlank()) { Text("Search") }
            }
            if (!state.guestReady) ErrorStrip("Start Linux first. The app catalog is queried directly from the Debian machine so it always matches the current release and arm64 architecture.")
            if (store.error.isNotBlank()) ErrorStrip(store.error)
            if (store.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                store.apps.forEach { app -> AppCard(app, store.busyPackage) }
                if (state.guestReady && !store.loading && store.apps.isEmpty() && store.error.isBlank()) {
                    Text("Search Debian packages or open Popular.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    @Composable
    private fun AppCard(app: GuestApp, busyPackage: String) {
        ElevatedCard(shape=RoundedCornerShape(20.dp), modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=RoundedCornerShape(14.dp), color=MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(10.dp), tint=MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(app.name, fontWeight=FontWeight.SemiBold)
                        if (app.installed) StatusPill("INSTALLED", true)
                    }
                    Text(app.description, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant, maxLines=2, overflow=TextOverflow.Ellipsis)
                    Text(
                        app.packageName + if (app.installedSizeKb > 0) " · ${formatMb(app.installedSizeKb)} installed" else "",
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val busy = busyPackage == app.packageName
                if (app.installed) {
                    OutlinedButton(onClick={VmSessionService.active?.removeApp(app.packageName)}, enabled=busyPackage.isBlank()) { Text(if(busy) "…" else "Remove") }
                } else {
                    Button(onClick={VmSessionService.active?.installApp(app.packageName)}, enabled=busyPackage.isBlank()) { Text(if(busy) "…" else "Install") }
                }
            }
        }
    }

    @Composable
    private fun TerminalPage(state: SessionState) {
        val hostState by VesselHostDebug.state.collectAsStateWithLifecycle()
        var command by remember { mutableStateOf("") }
        var hostMode by remember { mutableStateOf(false) }
        val output = if (hostMode) hostState.output else state.terminalOutput
        val emptyText = if (hostMode) "Android host shell runs as Vessel's app UID. Use it even when Linux cannot start." else "Start Linux, then run Debian commands here."
        val canRun = if (hostMode) !hostState.busy else state.running && state.guestReady && !state.busy

        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Terminal", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                    Text(if(hostMode) "Android host debug · app sandbox" else "Debian guest shell", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hostMode && hostState.busy) StatusPill("RUNNING", true)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                FilterChip(selected=!hostMode, onClick={hostMode=false;command=""}, label={Text("Debian")})
                FilterChip(selected=hostMode, onClick={hostMode=true;command=""}, label={Text("Host Debug")})
                OutlinedButton(onClick={copyText("Terminal output",output)}, enabled=output.isNotBlank()) { Text("Copy") }
                OutlinedButton(onClick={if(hostMode)VesselHostDebug.clear() else VesselHostDebug.clearGuestTerminal()}, enabled=output.isNotBlank()) { Text("Clear") }
                if (hostMode) {
                    AssistChip(onClick={VesselHostDebug.runHostInfo()}, label={Text("Host info")})
                    AssistChip(onClick={VesselHostDebug.runGpuLinkerCheck()}, label={Text("GPU linker")})
                }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), shape=RoundedCornerShape(18.dp), color=Color(0xff050706)) {
                SelectionContainer {
                    Text(output.ifBlank{emptyText}, Modifier.padding(14.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), fontFamily=FontFamily.Monospace, style=MaterialTheme.typography.bodySmall, softWrap=false)
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=command,onValueChange={command=it},modifier=Modifier.weight(1f),singleLine=true,label={Text(if(hostMode)"Host command" else "Debian command")},placeholder={Text(if(hostMode)"echo \$VESSEL_LIBDIR" else "uname -a")})
                Button(onClick={if(command.isNotBlank()){if(hostMode)VesselHostDebug.run(command) else VmSessionService.active?.runGuestCommand(command);command=""}},enabled=canRun&&command.isNotBlank()){Icon(Icons.Default.Send,null)}
            }
        }
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()
        LaunchedEffect(state.guestReady, state.running) { VmSessionService.active?.refreshSystemStats() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text("System", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
                OutlinedButton(onClick={VmSessionService.active?.refreshSystemStats()}, enabled=!stats.loading) { Text("Refresh") }
            }
            if (stats.error.isNotBlank()) ErrorStrip(stats.error)
            ElevatedCard(shape=RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Performance", fontWeight=FontWeight.SemiBold)
                    Metric(Icons.Default.Memory,"Guest RAM",if(stats.guestRamTotalMb>0)"${stats.guestRamUsedMb} / ${stats.guestRamTotalMb} MiB used" else "${state.guestMemoryMb} MiB allocated")
                    Metric(Icons.Default.Bolt,"CPU","${stats.vcpus} UML vCPUs")
                    Metric(Icons.Default.DesktopWindows,"Display","${state.guestDisplayWidth} × ${state.guestDisplayHeight} stable landscape · Vulkan scaled")
                    Metric(Icons.Default.Bolt,"Graphics",state.graphics)
                }
            }
            ElevatedCard(shape=RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Storage", fontWeight=FontWeight.SemiBold)
                    Metric(Icons.Default.Storage,"Linux filesystem",if(stats.guestDiskFreeMb>0)"${stats.guestDiskUsedMb} MiB used · ${stats.guestDiskFreeMb} MiB free" else "Start Linux for filesystem usage")
                    Metric(Icons.Default.Storage,"Sparse disk","${stats.diskVirtualMb} MiB virtual · ~${stats.diskPhysicalMb} MiB physically allocated")
                    Metric(Icons.Default.Storage,"Android free","${stats.hostFreeMb} MiB")
                    OutlinedButton(onClick={VmSessionService.active?.expandDiskBy2GiB()}, enabled=!state.running&&!state.busy) { Text("Expand disk +2 GiB") }
                    Text("Expansion is safe and sparse. ext4 grows automatically on the next boot; Vessel does not offer unsafe online shrinking.", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ElevatedCard(shape=RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Runtime", fontWeight=FontWeight.SemiBold)
                    Metric(Icons.Default.Bolt,"Protocol","39 · ${state.runtimeRevision}")
                    Metric(Icons.Default.DesktopWindows,"Transport",state.displayTransport)
                    Metric(Icons.Default.DesktopWindows,"Presenter",state.presenterStatus)
                    Metric(Icons.Default.Storage,"Machine",state.machinePath)
                    if(stats.packageCount>0) Metric(Icons.Default.Laptop,"Debian packages","${stats.packageCount} installed")
                }
            }
            Button(onClick={VmSessionService.active?.runGpuDiagnostics()}, enabled=state.guestReady&&!state.busy) { Text("Run GPU + input diagnostics") }
            LogCard(state)
        }
    }

    @Composable
    private fun StatusPill(label: String, active: Boolean) {
        Surface(shape=RoundedCornerShape(999.dp), color=if(active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, Modifier.padding(horizontal=10.dp,vertical=6.dp), style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Metric(icon: ImageVector, label: String, value: String) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(value,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis) }
        }
    }

    @Composable
    private fun ErrorStrip(text: String) {
        val clean = remember(text) { text.replace(Regex("\\s+")," ").trim() }
        Surface(shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Runtime issue",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.error)
                Text(clean,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,maxLines=4,overflow=TextOverflow.Ellipsis)
                if(clean.length>260) Text("Full details are kept in Runtime log.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }

    @Composable
    private fun ProgressBlock(state: SessionState) {
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress={state.progressPercent.coerceIn(0,100)/100f}, modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text(state.progressDetail,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp)); Text("${state.progressPercent.coerceIn(0,100)}%",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
            }
        }
    }

    @Composable
    private fun LogCard(state: SessionState) {
        if (state.console.isBlank()) return
        val vertical=rememberScrollState(); val horizontal=rememberScrollState()
        ElevatedCard(modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Runtime log",fontWeight=FontWeight.SemiBold); Text("Full startup and native diagnostics",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedButton(onClick={copyText("Runtime log",state.console)}) { Text("Copy") }
                }
                Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=Color(0xff050706)) {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        SelectionContainer { Text(state.console.takeLast(40_000),modifier=Modifier.fillMaxSize().padding(12.dp).verticalScroll(vertical).horizontalScroll(horizontal),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall,softWrap=false) }
                    }
                }
            }
        }
    }

    private fun uptime(ms: Long): String { val seconds=(ms/1000).coerceAtLeast(0); val minutes=seconds/60; return if(minutes>0)"${minutes}m ${seconds%60}s" else "${seconds}s" }
    private fun formatMb(kib: Long): String = if (kib >= 1024) "%.1f MiB".format(kib / 1024.0) else "$kib KiB"
}
