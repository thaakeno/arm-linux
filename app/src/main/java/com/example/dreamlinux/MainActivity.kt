package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val permissionListener=Shizuku.OnRequestPermissionResultListener { _, result ->
        if(result==PackageManager.PERMISSION_GRANTED) connect()
        else VmSessionService.state.value=VmSessionService.state.value.copy(message="Shizuku permission denied")
    }

    private fun connect() {
        try {
            if(!Shizuku.pingBinder()) {
                VmSessionService.state.value=VmSessionService.state.value.copy(message="Start Shizuku first")
                return
            }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1); return
            }
            startForegroundService(Intent(this,VmSessionService::class.java))
        } catch(e:Exception) {
            VmSessionService.state.value=VmSessionService.state.value.copy(message=e.message?:"Connection failed")
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            VesselTheme {
                var page by remember { mutableIntStateOf(0) }
                Scaffold(
                    containerColor=MaterialTheme.colorScheme.background,
                    bottomBar={ VesselNav(page) { page=it } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        VesselHeader(state)
                        Box(Modifier.weight(1f)) {
                            when(page) {
                                0 -> HomePage(state)
                                1 -> DesktopPage(state)
                                2 -> TerminalPage(state)
                                else -> SystemPage(state)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun VesselTheme(content:@Composable ()->Unit) {
        val scheme=darkColorScheme(
            primary=Color(0xff9BE8C5),
            onPrimary=Color(0xff00382A),
            secondary=Color(0xffA9C7FF),
            background=Color(0xff080B0A),
            surface=Color(0xff101513),
            surfaceVariant=Color(0xff18201D),
            outline=Color(0xff34413C)
        )
        MaterialTheme(colorScheme=scheme,content=content)
    }

    @Composable private fun VesselHeader(state:SessionState) {
        Surface(color=MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Terminal,null,Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                    Text("ARM64 Linux on Android",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    when { state.running -> "RUNNING"; state.connected -> "READY"; else -> "OFFLINE" },
                    state.running
                )
            }
        }
    }

    @Composable private fun VesselNav(page:Int,onSelect:(Int)->Unit) {
        NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
            listOf(
                Triple("Home",Icons.Default.Home,0),
                Triple("Desktop",Icons.Default.DesktopWindows,1),
                Triple("Terminal",Icons.Default.Terminal,2),
                Triple("System",Icons.Default.Settings,3)
            ).forEach { (label,icon,index) ->
                NavigationBarItem(
                    selected=page==index,
                    onClick={onSelect(index)},
                    icon={Icon(icon,null)},
                    label={Text(label)}
                )
            }
        }
    }

    @Composable private fun HomePage(state:SessionState) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)
        ) {
            Text("Your Linux machine",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            Text(state.message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)

            ElevatedCard(shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer,null,Modifier.size(34.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Debian ARM64",style=MaterialTheme.typography.titleLarge)
                            Text("KDE Plasma desktop · persistent root filesystem",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(if(state.running)"Live" else "Stopped",state.running)
                    }
                    HorizontalDivider()
                    MetricRow(Icons.Default.Memory,"Runtime",if(state.backend==RuntimeBackend.UML_VENUS)"UML + Venus" else "AVF legacy")
                    MetricRow(Icons.Default.Bolt,"Graphics",state.graphics)
                    MetricRow(Icons.Default.Wifi,"Network",if(state.internetReady)"Connected" else state.internetStage)
                    MetricRow(Icons.Default.DesktopWindows,"Desktop",if(state.kdeInstalled)"KDE Plasma ready" else state.kdeStage)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(!state.connected) {
                            Button(onClick={connect()},Modifier.weight(1f)) { Icon(Icons.Default.Link,null); Spacer(Modifier.width(8.dp)); Text("Connect") }
                        } else if(!state.running) {
                            Button(onClick={startLinux()},enabled=!state.busy,modifier=Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(8.dp)); Text("Start Linux") }
                        } else {
                            Button(onClick={VmSessionService.active?.stopVm()},enabled=!state.busy,modifier=Modifier.weight(1f)) { Icon(Icons.Default.Stop,null); Spacer(Modifier.width(8.dp)); Text("Stop") }
                        }
                        OutlinedButton(onClick={VmSessionService.active?.probeCapabilities()},enabled=state.connected&&!state.busy) { Icon(Icons.Default.Tune,null) }
                    }
                }
            }

            SectionTitle("Graphics stack")
            InfoCard(
                Icons.Default.Bolt,
                "Venus acceleration",
                "The proven path is ARM64 Debian → Mesa Venus → virglrenderer/Turnip on the phone GPU. The branch also carries the working shared-frame presenter and synchronization fixes from the Termux proof."
            )
            InfoCard(
                Icons.Default.DesktopWindows,
                "Desktop presentation",
                if(state.kdeInstalled) "KDE Plasma is installed. The embedded viewer remains available while the native Venus presenter is moved into the APK runtime." else "Install KDE Plasma once Debian is running, then use the Desktop tab."
            )
            if(state.running&&!state.kdeInstalled) {
                Button(onClick={VmSessionService.active?.installKde()},enabled=!state.busy&&state.internetReady,modifier=Modifier.fillMaxWidth()) {
                    Text(if(state.kdeInstalling)"Installing Plasma…" else "Install KDE Plasma")
                }
            }
        }
    }

    @Composable private fun DesktopPage(state:SessionState) {
        Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Desktop",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                    Text(if(state.running)"Debian session active" else "Start Linux from Home",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(if(state.kdeInstalled)"PLASMA" else "NO DESKTOP",state.kdeInstalled)
            }
            Surface(Modifier.weight(1f).fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color.Black) {
                if(state.running&&state.kdeInstalled) {
                    AndroidView(
                        modifier=Modifier.fillMaxSize(),
                        factory={context->VncFramebufferView(context).apply{requestFocus()}},
                        update={if(!it.hasFocus())it.requestFocus()}
                    )
                } else {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DesktopWindows,null,Modifier.size(44.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if(!state.running)"Linux is stopped" else "KDE Plasma is not installed")
                        }
                    }
                }
            }
            KeyToolbar()
        }
    }

    @Composable private fun TerminalPage(state:SessionState) {
        var command by remember { mutableStateOf("uname -a; cat /etc/os-release; vulkaninfo --summary 2>/dev/null | head -40") }
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Terminal",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            OutlinedTextField(command,{command=it},Modifier.fillMaxWidth(),label={Text("Debian command")},singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={VmSessionService.active?.debianConsole(command)},enabled=state.running&&!state.busy) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(6.dp)); Text("Run") }
                OutlinedButton(onClick={copyText(state.debianTerminal,"Terminal output")}) { Icon(Icons.Default.ContentCopy,null); Spacer(Modifier.width(6.dp)); Text("Copy") }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color(0xff050706)) {
                SelectionContainer {
                    Text(state.debianTerminal.ifBlank{"Debian terminal output will appear here."},Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable private fun SystemPage(state:SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("System",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            InfoCard(Icons.Default.Memory,"Runtime architecture","Primary target: unrooted ARM64 UML with the proven umshm transport and Venus GPU path. The existing AVF bridge remains in-tree as a compatibility backend while the APK-native UML launcher is integrated.")
            InfoCard(Icons.Default.Security,"Isolation","No Android root is required by the UML path. Linux runs as an ordinary Android process with its own guest kernel and persistent disk image.")
            InfoCard(Icons.Default.Storage,"Storage","Persistent Debian image with a separate runtime layer. Disk management, snapshots, cloning and import/export are next in the manager layer.")
            ElevatedCard(shape=RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Build",style=MaterialTheme.typography.titleMedium)
                    Text("Vessel ${BuildConfig.VERSION_NAME}\n${BuildConfig.GIT_BRANCH}\n${BuildConfig.GIT_COMMIT}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                    Text("Backend ${state.backend.name}",style=MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick={shareDiagnostics(state)},Modifier.fillMaxWidth()) { Icon(Icons.Default.Share,null); Spacer(Modifier.width(8.dp)); Text("Share diagnostics") }
        }
    }

    @Composable private fun MetricRow(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,value:String) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp)); Text(label,Modifier.width(82.dp),style=MaterialTheme.typography.labelMedium)
            Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable private fun InfoCard(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,body:String) {
        Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(icon,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column { Text(title,style=MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    @Composable private fun SectionTitle(text:String) { Text(text,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }

    @Composable private fun StatusPill(text:String,active:Boolean) {
        Surface(shape=RoundedCornerShape(999.dp),color=if(active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(text,Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall)
        }
    }

    @Composable private fun KeyToolbar() {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to KeyEvent.KEYCODE_ESCAPE,"Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,"Alt" to KeyEvent.KEYCODE_ALT_LEFT,"Tab" to KeyEvent.KEYCODE_TAB).forEach { (label,key) ->
                OutlinedButton(
                    onClick={VncFramebufferView.active?.sendAndroidKey(true,key);VncFramebufferView.active?.sendAndroidKey(false,key)},
                    contentPadding=PaddingValues(horizontal=11.dp,vertical=7.dp)
                ) { Text(label) }
            }
        }
    }

    private fun startLinux() {
        val metrics=resources.displayMetrics
        val refresh=(if(android.os.Build.VERSION.SDK_INT>=30)display?.refreshRate?:60f else @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate).toInt()
        VmSessionService.active?.startDebian(metrics.widthPixels.coerceAtLeast(640),metrics.heightPixels.coerceAtLeast(480),metrics.densityDpi,refresh)
    }

    private fun copyText(text:String,label:String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label,text))
        Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show()
    }

    private fun diagnostics(state:SessionState)=buildString {
        appendLine("Vessel ${BuildConfig.VERSION_NAME}")
        appendLine("Branch ${BuildConfig.GIT_BRANCH} · ${BuildConfig.GIT_COMMIT}")
        appendLine("Backend ${state.backend}")
        appendLine("Running ${state.running} · mode ${state.mode} · stage ${state.stage}")
        appendLine("Graphics ${state.graphics}")
        appendLine("Network ${state.internetReady} · ${state.internetStage}")
        appendLine("KDE ${state.kdeInstalled} · ${state.kdeStage}")
        appendLine("Capabilities ${state.capabilities}")
        appendLine("\n=== Debian ===\n${state.debianTerminal}")
        appendLine("\n=== Runtime ===\n${state.console}")
    }

    private fun shareDiagnostics(state:SessionState) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,diagnostics(state)),"Share Vessel diagnostics"))
    }
}
