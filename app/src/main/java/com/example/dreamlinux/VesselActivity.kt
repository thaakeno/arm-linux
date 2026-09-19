package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VesselActivity : ComponentActivity() {
    @Volatile private var fullscreenRequested = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySystemChrome(false)
        preferHighRefresh()
        VesselHostDebug.initialize(this)
        VesselUpdateManager.initialize(this)
        startForegroundService(Intent(this, VmSessionService::class.java))
        setContent { VesselApp() }
    }

    override fun onResume() {
        super.onResume()
        applySystemChrome(fullscreenRequested)
        preferHighRefresh()
        startForegroundService(Intent(this, VmSessionService::class.java))
        VmSessionService.active?.refreshAvailability()
        VesselUpdateManager.resumePendingInstall(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemChrome(fullscreenRequested)
    }

    private fun applySystemChrome(fullscreen: Boolean) {
        fullscreenRequested = fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun preferHighRefresh() {
        val attrs = window.attributes
        if (VesselExperimentConfig.runtimeBackend(this) == VesselRuntimeFactory.ACTIVE_BACKEND_ID) {
            // Production proroot uses Surface.setFrameRate/SurfaceControl hints.
            // A fixed preferredDisplayModeId would pin the panel at 120 Hz and
            // defeat Phase 5's idle 60 Hz battery policy.
            if (attrs.preferredDisplayModeId != 0) {
                attrs.preferredDisplayModeId = 0
                window.attributes = attrs
            }
            return
        }

        // UML recovery still uses its legacy fixed-mode presentation path.
        val displayManager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        val current = display.mode
        val requested = VesselExperimentConfig.refreshHz(this).toFloat().coerceAtMost(120f)
        val best = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight &&
                    it.refreshRate <= requested + 0.5f
            }
            .maxByOrNull { it.refreshRate }
            ?: return
        if (best.modeId != current.modeId) {
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }
    }

    private fun startLinux() {
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
            applySystemChrome(fullscreen)
            preferHighRefresh()
            requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        LaunchedEffect(state.frameReachedApp) { if (state.frameReachedApp) page = 1 }

        VesselTheme {
            if (fullscreen && (state.running || state.busy)) {
                FullscreenDesktop(state) { fullscreen = false }
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
                            3 -> TerminalPage()
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
        NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
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
                    Text(if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) "Rootless ARM64 Linux · Direct KGSL · Native Surface · Adreno" else "Recovery UML · VirtIO GPU · Native Surface · Adreno", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ElevatedCard(shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Debian workstation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(if (state.running || state.busy) "${state.message} · ${uptime(state.uptimeMs)}" else "Persistent Linux PC on your phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(when { stopping -> "STOPPING"; state.running -> "LIVE"; state.busy -> "STARTING"; else -> "OFF" }, canStop)
                    }
                    if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                    if (!state.storageReady) ErrorStrip("Vessel cannot access its private Linux storage yet.")
                    if (state.busy || state.running) ProgressBlock(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { if (canStop) VmSessionService.active?.stopVm() else { startLinux(); openDisplay() } },
                            enabled = !stopping,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(if (canStop) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(7.dp))
                            Text(when {
                                stopping -> "Stopping…"
                                state.busy && !state.running -> "Cancel startup"
                                state.running -> "Stop Linux"
                                !state.storageReady -> "Storage unavailable"
                                else -> "Start Linux"
                            })
                        }
                        if (state.running) OutlinedButton(onClick = openDisplay) {
                            Icon(Icons.Default.DesktopWindows, null); Spacer(Modifier.width(6.dp)); Text("Display")
                        }
                    }
                }
            }

            Text("Machine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val sharedKernel = state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID
                    Metric(
                        Icons.Default.Memory,
                        "Memory",
                        if (sharedKernel) "Shared Android memory · no fixed guest RAM"
                        else "${if (state.guestMemoryMb > 0) state.guestMemoryMb else 4096} MiB UML guest · ${VesselExperimentConfig.vcpus(this@VesselActivity)} vCPU",
                    )
                    Metric(Icons.Default.DesktopWindows, "Desktop", "${state.guestDisplayWidth} × ${state.guestDisplayHeight} · stable landscape")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    Metric(Icons.Default.DesktopWindows, "Android Surface", state.presenterStatus)
                    Metric(Icons.Default.Wifi, "Network", state.internetStage)
                    Metric(
                        Icons.Default.Storage,
                        "Disk",
                        if (sharedKernel) "Private persistent directory rootfs · grows with Android storage"
                        else "Private persistent sparse ext4 · safe auto-grow",
                    )
                }
            }
            LogCard(state)
        }
    }

    @Composable
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
                    Text(
                        "Vessel ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT} · ${state.runtimeRevision}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.displayReady) StatusPill("VISIBLE", true)
            }
            if (state.running || state.busy) {
                DesktopControls(mode, { newMode -> mode = newMode; LinuxDesktopView.active?.setPointerMode(newMode) }, fullscreen)
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val monitorAspect = remember(state.guestDisplayWidth, state.guestDisplayHeight) {
                        (state.guestDisplayWidth.toFloat() / state.guestDisplayHeight.coerceAtLeast(1))
                            .coerceIn(1.25f, 2.40f)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(monitorAspect)
                            .background(Color.Black),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
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
                }
                ExtraKeys()
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.DesktopWindows, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Linux is stopped")
                            if (state.lastError.isNotBlank()) ErrorStrip(state.lastError)
                            Button(onClick = { startLinux() }, enabled = !state.busy) { Text(if (state.storageReady) "Start Linux" else "Storage unavailable") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DesktopControls(mode: LinuxDesktopView.PointerMode, setMode: (LinuxDesktopView.PointerMode) -> Unit, fullscreen: () -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mode == LinuxDesktopView.PointerMode.DIRECT, onClick = { setMode(LinuxDesktopView.PointerMode.DIRECT) }, label = { Text("Touch") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) })
            FilterChip(selected = mode == LinuxDesktopView.PointerMode.TRACKPAD, onClick = { setMode(LinuxDesktopView.PointerMode.TRACKPAD) }, label = { Text("Trackpad") }, leadingIcon = { Icon(Icons.Default.Mouse, null) })
            AssistChip(onClick = { LinuxDesktopView.active?.showKeyboard() }, label = { Text("Keyboard") }, leadingIcon = { Icon(Icons.Default.Keyboard, null) })
            AssistChip(onClick = fullscreen, label = { Text("Fullscreen") }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) })
        }
    }

    @Composable
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
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> LinuxDesktopView(context).apply { setPointerMode(mode); requestFocus() } },
                update = { view -> view.setPointerMode(mode); if (!view.hasFocus()) view.requestFocus() },
            )

            if (controlsVisible) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp),
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
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp).clickable { controlsVisible = true; controlsEpoch++ },
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

    @Composable
    private fun ExtraKeys() {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Esc" to 1, "Tab" to 15, "Ctrl" to 29, "Alt" to 56, "Super" to 125, "←" to 105, "↑" to 103, "↓" to 108, "→" to 106).forEach { (label, key) ->
                OutlinedButton(onClick = { LinuxDesktopView.active?.tapKey(key) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)) { Text(label) }
            }
        }
    }

    @Composable
    private fun AppsPage(state: SessionState) {
        val store by VmSessionService.appStore.collectAsStateWithLifecycle()
        var query by remember { mutableStateOf(store.query) }
        LaunchedEffect(state.guestReady, state.stage) {
            if (state.guestReady && state.stage == "ready" && store.apps.isEmpty() && !store.loading) {
                VmSessionService.active?.refreshApps("", "POPULAR", "All")
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Debian apps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Cached AppStream catalog · ARM64 · fast local search", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (store.loading) StatusPill("LOADING", true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Search apps") },
                    placeholder = { Text("browser, office, media…") },
                )
                Button(
                    onClick = { VmSessionService.active?.refreshApps(query, store.sort, store.category) },
                    enabled = state.guestReady && !store.loading,
                ) { Text("Search") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VmSessionService.APP_SORTS.forEach { sort ->
                    val label = when (sort) {
                        "POPULAR" -> "Popular"
                        "NEW" -> "New"
                        "HOT_WEEK" -> "Hot week"
                        "HOT_MONTH" -> "Hot month"
                        "HOT_YEAR" -> "Hot year"
                        "INSTALLED" -> "Installed"
                        "SIZE" -> "Size"
                        else -> "A–Z"
                    }
                    FilterChip(
                        selected = store.sort == sort,
                        onClick = { VmSessionService.active?.refreshApps(query, sort, store.category) },
                        enabled = state.guestReady && !store.loading,
                        label = { Text(label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VmSessionService.APP_CATEGORIES.forEach { category ->
                    FilterChip(
                        selected = store.category == category,
                        onClick = { VmSessionService.active?.refreshApps(query, store.sort, category) },
                        enabled = state.guestReady && !store.loading,
                        label = { Text(category) },
                    )
                }
            }
            if (!state.guestReady) ErrorStrip("Start Linux first. Vessel reads Debian's own ARM64 catalog.")
            if (state.guestReady && store.sort.startsWith("HOT_")) {
                Text("Hot combines recent AppStream releases with a small curated popularity signal; it is not global install telemetry.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (store.error.isNotBlank()) ErrorStrip(store.error)
            if (store.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(store.apps, key = { it.packageName }) { app -> AppCard(app, store) }
                if (state.guestReady && !store.loading && store.apps.isEmpty() && store.error.isBlank()) {
                    item { Text("No apps in this view.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    @Composable
    private fun AppCard(app: GuestApp, store: AppStoreState) {
        val image = remember(app.iconBase64) {
            runCatching {
                if (app.iconBase64.isBlank()) null else {
                    val bytes = Base64.decode(app.iconBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
        val busy = store.busyPackage == app.packageName
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(56.dp)) {
                        if (image != null) {
                            Image(image, app.name, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit)
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Laptop, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (app.installed) StatusPill("INSTALLED", true)
                        }
                        Text(app.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(app.category)
                                append(" · ")
                                append(app.packageName)
                                if (app.installedSizeKb > 0) append(" · ${formatMb(app.installedSizeKb)} installed size")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (app.installed) {
                        OutlinedButton(onClick = { VmSessionService.active?.removeApp(app.packageName) }, enabled = store.busyPackage.isBlank()) {
                            Text(if (busy) "Removing" else "Remove")
                        }
                    } else {
                        Button(onClick = { VmSessionService.active?.installApp(app.packageName) }, enabled = store.busyPackage.isBlank()) {
                            Text(if (busy) "Installing" else "Install")
                        }
                    }
                }
                if (busy) {
                    if (store.operationProgress < 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { store.operationProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        store.operationDetail.ifBlank { "Working…" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun TerminalPage() {
        VesselTerminalPanel()
    }

    @Composable
    private fun SystemPage(state: SessionState) {
        val stats by VmSessionService.machineStats.collectAsStateWithLifecycle()
        val diagnostics by VmSessionService.diagnostics.collectAsStateWithLifecycle()
        val updates by VesselUpdateManager.state.collectAsStateWithLifecycle()
        val updateScope = rememberCoroutineScope()
        var expRuntime by remember { mutableStateOf(VesselExperimentConfig.runtimeBackend(this@VesselActivity)) }
        val umlRecoveryAvailable = VesselRuntimeFactory.umlRecoveryAvailable(this@VesselActivity)
        var expVcpus by remember { mutableIntStateOf(VesselExperimentConfig.vcpus(this@VesselActivity)) }
        var expMemory by remember { mutableIntStateOf(VesselExperimentConfig.memoryMb(this@VesselActivity)) }
        var expRefresh by remember { mutableIntStateOf(VesselExperimentConfig.refreshHz(this@VesselActivity)) }
        var expResolution by remember { mutableIntStateOf(VesselExperimentConfig.resolutionPercent(this@VesselActivity)) }
        var expFlipY by remember { mutableStateOf(VesselExperimentConfig.flipDisplayY(this@VesselActivity)) }
        var expPointerY by remember { mutableStateOf(VesselExperimentConfig.invertPointerY(this@VesselActivity)) }
        var expDesktop by remember { mutableStateOf(VesselExperimentConfig.desktopBackend(this@VesselActivity)) }
        var expHostGl by remember { mutableStateOf(VesselExperimentConfig.hostGl(this@VesselActivity)) }
        var expFirefoxDmabuf by remember { mutableStateOf(VesselExperimentConfig.firefoxDmabuf(this@VesselActivity)) }
        var selectedDiagnosticLog by remember { mutableStateOf("desktop.log") }
        var selectedDiagnosticText by remember { mutableStateOf("Loading desktop.log…") }
        var diagnosticRefresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(state.guestReady, state.running) { VmSessionService.active?.refreshSystemStats() }
        LaunchedEffect(selectedDiagnosticLog, diagnostics, diagnosticRefresh) {
            selectedDiagnosticText = withContext(Dispatchers.IO) {
                VmSessionService.active?.readDiagnosticLog(selectedDiagnosticLog)
                    ?: "[Vessel runtime service is not connected]"
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("System", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { VmSessionService.active?.refreshSystemStats() }, enabled = !stats.loading) { Text("Refresh") }
            }
            if (stats.error.isNotBlank()) ErrorStrip(stats.error)
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Performance", fontWeight = FontWeight.SemiBold)
                    Metric(Icons.Default.Memory, "Memory", if (stats.guestRamTotalMb > 0) "${stats.guestRamUsedMb} / ${stats.guestRamTotalMb} MiB used" else if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) "Shared Android kernel memory" else "${state.guestMemoryMb} MiB allocated")
                    Metric(Icons.Default.Bolt, "CPU", if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) "${stats.vcpus} host CPU threads visible" else "${stats.vcpus} UML vCPU")
                    Metric(Icons.Default.DesktopWindows, "Display", "${state.guestDisplayWidth} × ${state.guestDisplayHeight} stable landscape · up to 120 Hz")
                    Metric(Icons.Default.Bolt, "Graphics", state.graphics)
                    OutlinedButton(
                        onClick = { VmSessionService.active?.launchSystemInfo() },
                        enabled = state.guestReady && !state.busy,
                    ) {
                        Icon(Icons.Default.Memory, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Linux specs")
                    }
                    Text("Opens KDE Info Center inside the running Linux desktop.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Runtime + performance", fontWeight = FontWeight.SemiBold)
                    Text("Production default is proroot shared-kernel + direct KGSL. UML is kept only as an explicit recovery backend; Vessel never falls back to it after a runtime error.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Runtime backend", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(
                            selected = expRuntime == VesselRuntimeFactory.ACTIVE_BACKEND_ID,
                            enabled = !state.running && !state.busy,
                            onClick = {
                                if (VmSessionService.active?.switchRuntimeBackend(VesselRuntimeFactory.ACTIVE_BACKEND_ID) == true) {
                                    expRuntime = VesselRuntimeFactory.ACTIVE_BACKEND_ID
                                }
                            },
                            label = { Text("Proroot · production") },
                        )
                        FilterChip(
                            selected = expRuntime == VesselRuntimeFactory.RECOVERY_BACKEND_ID,
                            enabled = !state.running && !state.busy,
                            onClick = {
                                if (umlRecoveryAvailable) {
                                    if (VmSessionService.active?.switchRuntimeBackend(VesselRuntimeFactory.RECOVERY_BACKEND_ID) == true) {
                                        expRuntime = VesselRuntimeFactory.RECOVERY_BACKEND_ID
                                    }
                                } else {
                                    // UML is explicit recovery only. If its old sparse disk is
                                    // not present, open the existing workstation installer on
                                    // direct user action; never fall back automatically.
                                    startActivity(Intent(this@VesselActivity, VesselBootstrapActivityV2::class.java))
                                }
                            },
                            label = { Text(if (umlRecoveryAvailable) "UML · recovery" else "Install UML recovery") },
                        )
                    }
                    Text(
                        when {
                            state.running || state.busy -> "Stop Linux before changing runtime backend."
                            umlRecoveryAvailable -> "Both runtimes are installed. Switching is manual only."
                            else -> "The UML recovery disk is not installed. Tap Install UML recovery to add it explicitly."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (expRuntime == VesselRuntimeFactory.RECOVERY_BACKEND_ID) {
                        Text("UML vCPU", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(1, 2, 4, 6).forEach { value ->
                                FilterChip(selected = expVcpus == value, onClick = { expVcpus = value; VesselExperimentConfig.setVcpus(this@VesselActivity, value) }, label = { Text("$value CPU") })
                            }
                        }
                        Text("UML guest memory", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(0 to "Auto", 2048 to "2 GB", 3072 to "3 GB", 4096 to "4 GB").forEach { (value, label) ->
                                FilterChip(selected = expMemory == value, onClick = { expMemory = value; VesselExperimentConfig.setMemoryMb(this@VesselActivity, value) }, label = { Text(label) })
                            }
                        }
                        Text("UML desktop stack", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(selected = expDesktop == "wayland", onClick = { expDesktop = "wayland"; VesselExperimentConfig.setDesktopBackend(this@VesselActivity, "wayland") }, label = { Text("Wayland") })
                            FilterChip(selected = expDesktop == "x11", onClick = { expDesktop = "x11"; VesselExperimentConfig.setDesktopBackend(this@VesselActivity, "x11") }, label = { Text("X11") })
                        }
                        Text("UML host GL", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(selected = expHostGl == "system", onClick = { expHostGl = "system"; VesselExperimentConfig.setHostGl(this@VesselActivity, "system") }, label = { Text("System EGL") })
                            FilterChip(selected = expHostGl == "angle", onClick = { expHostGl = "angle"; VesselExperimentConfig.setHostGl(this@VesselActivity, "angle") }, label = { Text("Bundled ANGLE") })
                            FilterChip(selected = expFirefoxDmabuf, onClick = { expFirefoxDmabuf = !expFirefoxDmabuf; VesselExperimentConfig.setFirefoxDmabuf(this@VesselActivity, expFirefoxDmabuf) }, label = { Text("Firefox DMA-BUF") })
                        }
                        FilterChip(selected = expFlipY, onClick = { expFlipY = !expFlipY; VesselExperimentConfig.setFlipDisplayY(this@VesselActivity, expFlipY) }, label = { Text("Fix UML display Y flip") })
                    }

                    Text("Refresh cap", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(60, 90, 120).forEach { value ->
                            FilterChip(selected = expRefresh == value, onClick = { expRefresh = value; VesselExperimentConfig.setRefreshHz(this@VesselActivity, value) }, label = { Text("$value Hz") })
                        }
                    }
                    Text("Desktop resolution", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(67 to "~720p", 83 to "Balanced", 100 to "Native cap").forEach { (value, label) ->
                            FilterChip(selected = expResolution == value, onClick = { expResolution = value; VesselExperimentConfig.setResolutionPercent(this@VesselActivity, value) }, label = { Text(label) })
                        }
                    }
                    FilterChip(selected = expPointerY, onClick = { expPointerY = !expPointerY; VesselExperimentConfig.setInvertPointerY(this@VesselActivity, expPointerY) }, label = { Text("Invert pointer Y") })

                    OutlinedButton(onClick = {
                        VesselExperimentConfig.reset(this@VesselActivity)
                        VmSessionService.active?.switchRuntimeBackend(VesselRuntimeFactory.ACTIVE_BACKEND_ID)
                        expRuntime = VesselExperimentConfig.runtimeBackend(this@VesselActivity)
                        expVcpus = VesselExperimentConfig.vcpus(this@VesselActivity)
                        expMemory = VesselExperimentConfig.memoryMb(this@VesselActivity)
                        expRefresh = VesselExperimentConfig.refreshHz(this@VesselActivity)
                        expResolution = VesselExperimentConfig.resolutionPercent(this@VesselActivity)
                        expFlipY = VesselExperimentConfig.flipDisplayY(this@VesselActivity)
                        expPointerY = VesselExperimentConfig.invertPointerY(this@VesselActivity)
                        expDesktop = VesselExperimentConfig.desktopBackend(this@VesselActivity)
                        expHostGl = VesselExperimentConfig.hostGl(this@VesselActivity)
                        expFirefoxDmabuf = VesselExperimentConfig.firefoxDmabuf(this@VesselActivity)
                    }) { Text("Reset production defaults") }
                }
            }
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Storage", fontWeight = FontWeight.SemiBold)
                    Metric(Icons.Default.Storage, "Linux filesystem", if (stats.guestDiskFreeMb > 0) "${stats.guestDiskUsedMb} MiB used · ${stats.guestDiskFreeMb} MiB free" else "Start Linux for filesystem usage")
                    if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) {
                        Metric(Icons.Default.Storage, "Rootfs", "App-private directory rootfs · grows with available Android storage")
                    } else {
                        Metric(Icons.Default.Storage, "Recovery sparse disk", "${stats.diskVirtualMb} MiB virtual · ~${stats.diskPhysicalMb} MiB physically allocated")
                        OutlinedButton(onClick = { VmSessionService.active?.expandDiskBy2GiB() }, enabled = !state.running && !state.busy) { Text("Expand recovery disk +2 GiB") }
                    }
                    Metric(Icons.Default.Storage, "Android free", "${stats.hostFreeMb} MiB")
                }
            }
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Runtime", fontWeight = FontWeight.SemiBold)
                    Metric(Icons.Default.Bolt, "Vessel", "${BuildConfig.VERSION_NAME} · ${state.runtimeRevision}")
                    Metric(Icons.Default.Bolt, "Backend", if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) "Proroot · production" else "UML · recovery")
                    Metric(Icons.Default.DesktopWindows, "Transport", state.displayTransport)
                    Metric(Icons.Default.DesktopWindows, "Presenter", state.presenterStatus)
                    Metric(Icons.Default.Bolt, "Audio", VesselAudioBridge.status())
                    Metric(Icons.Default.Terminal, "Control", if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) "Direct shared-kernel process control" else VesselGuestAgent.status())
                    Metric(Icons.Default.Storage, "Machine", state.machinePath)
                    if (stats.packageCount > 0) Metric(Icons.Default.Laptop, "Debian packages", "${stats.packageCount} installed")
                }
            }
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Updates", fontWeight = FontWeight.SemiBold)
                    Metric(Icons.Default.Bolt, "App", "${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_COMMIT}")
                    Metric(Icons.Default.Terminal, "Live runtime", updates.runtimeRevision)
                    if (updates.busy) LinearProgressIndicator(progress = { updates.progressPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(updates.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.runtimeBackend == VesselRuntimeFactory.RECOVERY_BACKEND_ID) {
                            Button(
                                onClick = { updateScope.launch { runCatching { VesselUpdateManager.checkRuntimeUpdate(this@VesselActivity) } } },
                                enabled = !updates.busy,
                            ) { Text("Update UML runtime") }
                        }
                        OutlinedButton(
                            onClick = { updateScope.launch { runCatching { VesselUpdateManager.checkAndInstallAppUpdate(this@VesselActivity) } } },
                            enabled = !updates.busy,
                        ) { Text("Update app") }
                        if (state.runtimeBackend == VesselRuntimeFactory.RECOVERY_BACKEND_ID) {
                            OutlinedButton(
                                onClick = { updateScope.launch { runCatching { VesselUpdateManager.rollbackRuntime(this@VesselActivity) } } },
                                enabled = !updates.busy && updates.canRollback,
                            ) { Text("Rollback UML runtime") }
                        }
                    }
                    Text(
                        if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) {
                            "Production proroot uses the verified Debian rootfs channel. Kotlin/native runtime changes ship with the Android APK."
                        } else {
                            "UML recovery keeps its allow-listed hot-script channel. Kotlin/native changes use the Android APK updater."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Crash diagnostics", fontWeight = FontWeight.SemiBold)
                            Text(if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) "Host memory, proroot process state, Wayland/KGSL, audio and app crash artifacts" else "Host memory, UML exit state, Wayland/GPU, PulseAudio and app crash artifacts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { copyText("Vessel diagnostics", diagnostics) }, enabled = diagnostics.isNotBlank()) { Text("Copy") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { VmSessionService.active?.collectCrashDiagnostics() }) { Text("Collect") }
                        OutlinedButton(onClick = { VmSessionService.active?.runGpuDiagnostics() }, enabled = state.guestReady) { Text("GPU + input") }
                    }
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xff050706)) {
                        val summaryVertical = rememberScrollState()
                        val summaryHorizontal = rememberScrollState()
                        SelectionContainer {
                            Text(
                                diagnostics,
                                Modifier
                                    .height(260.dp)
                                    .verticalScroll(summaryVertical)
                                    .horizontalScroll(summaryHorizontal)
                                    .padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            if (state.runtimeBackend == VesselRuntimeFactory.ACTIVE_BACKEND_ID) {
                ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Full runtime logs", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Named proroot/Plasma logs · full selected file · no 6 KB / 18-line UI truncation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = { copyText(selectedDiagnosticLog, selectedDiagnosticText) },
                                enabled = selectedDiagnosticText.isNotBlank(),
                            ) { Text("Copy full") }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            VmSessionService.PROROOT_DIAGNOSTIC_LOGS.forEach { name ->
                                FilterChip(
                                    selected = selectedDiagnosticLog == name,
                                    onClick = { selectedDiagnosticLog = name },
                                    label = { Text(name.removeSuffix(".log")) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { diagnosticRefresh++ }) { Text("Refresh log") }
                            OutlinedButton(onClick = { VmSessionService.active?.collectCrashDiagnostics() }) { Text("Refresh bundle") }
                        }
                        val logVertical = rememberScrollState()
                        val logHorizontal = rememberScrollState()
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xff050706),
                        ) {
                            SelectionContainer {
                                Text(
                                    selectedDiagnosticText,
                                    Modifier
                                        .height(420.dp)
                                        .verticalScroll(logVertical)
                                        .horizontalScroll(logHorizontal)
                                        .padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
            LogCard(state)
        }
    }

    @Composable
    private fun StatusPill(label: String, active: Boolean) {
        Surface(shape = RoundedCornerShape(999.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Metric(icon: ImageVector, label: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun ErrorStrip(text: String) {
        val clean = remember(text) { text.replace(Regex("\\s+"), " ").trim() }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Runtime issue", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(clean, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(
                    "build ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (clean.length > 260) Text("Full details are kept in Runtime log.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }

    @Composable
    private fun ProgressBlock(state: SessionState) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { state.progressPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.progressDetail, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                Text("${state.progressPercent.coerceIn(0, 100)}% · ${uptime(state.uptimeMs)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    @Composable
    private fun LogCard(state: SessionState) {
        if (state.console.isBlank()) return
        var showRaw by remember { mutableStateOf(false) }
        val important = remember(state.console) {
            state.console.lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank() && (
                        line.contains("[error]", ignoreCase = true) ||
                        line.contains("[crash]", ignoreCase = true) ||
                        line.contains("[network]", ignoreCase = true) ||
                        line.contains("[display]", ignoreCase = true) ||
                        line.contains("[gpu]", ignoreCase = true) ||
                        line.contains("[input]", ignoreCase = true) ||
                        line.contains("[control]", ignoreCase = true) ||
                        line.contains("READY", ignoreCase = true)
                    )
                }
                .toList()
                .takeLast(8)
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Runtime status", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (showRaw) "Raw diagnostics" else "Recent meaningful events",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Summary" else "Raw") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { copyText("Runtime log", state.console) }) { Text("Copy") }
                }
                if (showRaw) {
                    val vertical = rememberScrollState()
                    val horizontal = rememberScrollState()
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xff050706)) {
                        Box(Modifier.fillMaxWidth().height(240.dp)) {
                            SelectionContainer {
                                Text(
                                    state.console.takeLast(24_000),
                                    modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(vertical).horizontalScroll(horizontal),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                } else {
                    val summary = if (important.isEmpty()) "Runtime is active. No warnings or failures in the recent log." else important.joinToString("\n")
                    SelectionContainer {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
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

    private fun formatMb(kib: Long): String = if (kib >= 1024) "%.1f MiB".format(kib / 1024.0) else "$kib KiB"
}
