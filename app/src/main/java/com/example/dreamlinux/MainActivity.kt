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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

    private fun fullLogReport(state:SessionState)=buildString {
        appendLine("DEV 1 LINUX")
        appendLine("Managed AVF diagnostic report")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_BRANCH} ${BuildConfig.GIT_COMMIT}")
        appendLine()
        appendLine("Status: ${if(state.running)"VM RUNNING" else "VM OFFLINE"}")
        appendLine("Message: ${state.message}")
        appendLine("VM: ${state.name.ifBlank{"not created"}}")
        appendLine("Mode: ${state.mode}")
        appendLine("Stage: ${state.stage}")
        appendLine("API: ${state.api}")
        appendLine("VM root: ${state.vmRoot}")
        appendLine("Capabilities: ${state.capabilities}")
        appendLine("Debian bundle: ${state.debianInstalled}")
        appendLine("Internet: ${state.internetReady} / ${state.internetStage}")
        appendLine("Desktop: ${state.kdeInstalled} / ${state.kdeStage}")
        appendLine("Graphics: ${state.graphics}")
        appendLine()
        appendLine("=== MANAGED VM LOG ===")
        appendLine(state.console.ifBlank{"No managed VM diagnostics yet."})
        appendLine()
        appendLine("=== GATE A TERMINAL ===")
        appendLine(state.terminal.ifBlank{"No Gate A commands executed."})
        appendLine()
        appendLine("=== DEBIAN TERMINAL ===")
        appendLine(state.debianTerminal.ifBlank{"No Debian commands executed."})
    }

    private fun copyAllLogs(state:SessionState) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("DEV 1 LINUX logs",fullLogReport(state)))
        Toast.makeText(this,"All DEV 1 LINUX logs copied",Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs(state:SessionState) {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,fullLogReport(state)),
            "Export diagnostics"))
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }
            MaterialTheme(colorScheme=darkColorScheme(
                primary=Color(0xff9FE0C4),secondary=Color(0xffAFC7FF),
                background=Color(0xff090D0C),surface=Color(0xff111715),surfaceVariant=Color(0xff18211E)
            )) {
                Scaffold(containerColor=MaterialTheme.colorScheme.background) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Header(state)
                        NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(selected=page==0,onClick={page=0},icon={Icon(Icons.Default.Computer,null)},label={Text("Desktop")})
                            NavigationBarItem(selected=page==1,onClick={page=1},icon={Icon(Icons.Default.Terminal,null)},label={Text("Terminal")})
                            NavigationBarItem(selected=page==2,onClick={page=2},icon={Icon(Icons.Default.Memory,null)},label={Text("Diagnostics")})
                            NavigationBarItem(selected=page==3,onClick={page=3},icon={Icon(Icons.Default.Info,null)},label={Text("About")})
                        }
                        Box(Modifier.weight(1f)) {
                            when(page) { 0->DesktopPage(state); 1->TerminalPage(state); 2->DiagnosticsPage(state); else->AboutPage() }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun AboutPage() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("About DEV 1 LINUX",style=MaterialTheme.typography.headlineSmall)
            SelectionContainer { Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nBranch ${BuildConfig.GIT_BRANCH}\nCommit ${BuildConfig.GIT_COMMIT}\nARM64 development build",fontFamily=FontFamily.Monospace) }
            Text("Debian 13 runs as a real ARM64 userspace inside a protected AVF/Gunyah VM. The phone's trusted Microdroid kernel stays intact and Debian persists on Microdroid encrypted storage.")
            Text("VM-local adb root is used only inside the debuggable Microdroid guest to prepare Debian. Android itself is not rooted and the bootloader stays locked. This is not Termux, proot or CPU emulation.")
            Text("Internet is carried through VirtualMachine.connectVsock() and a host-side proxy. Plasma 6 is displayed through TigerVNC for the reliable first path. Hardware GPU acceleration remains experimental until renderer proof exists.")
        }
    }

    @Composable private fun Header(state:SessionState) {
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DEV 1 LINUX",style=MaterialTheme.typography.headlineSmall)
                        Text(BuildConfig.VERSION_NAME,style=MaterialTheme.typography.labelSmall)
                        Text("DEBIAN 13 · PLASMA 6 · MICRODROID pVM · AVF",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    }
                    StatusPill(if(state.running) "RUNNING" else if(state.connected) "READY" else "OFFLINE",state.running)
                }
                Spacer(Modifier.height(10.dp))
                Text(state.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable private fun StatusPill(text:String,active:Boolean) {
        Surface(shape=RoundedCornerShape(999.dp),color=if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text(text,Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall)
        }
    }

    @Composable private fun DesktopPage(state:SessionState) {
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when {
                !state.connected -> SetupCard("1","Connect AVF","Shizuku gives DEV 1 the shell-level bridge needed to reach Android's virtualization stack. No Android root or bootloader unlock.") {
                    Button(onClick={connect()}) { Text("Connect Shizuku") }
                }
                !state.debianInstalled -> SetupCard("2","Debian bundle missing","This APK should contain a Debian 13 arm64 minbase rootfs and the guest bridge. Reinstall the latest DEV 1 build if this remains missing.") {
                    Button(onClick={VmSessionService.active?.installDebian()},enabled=!state.busy) { Text("Recheck bundle") }
                }
                !state.running || state.mode!="debian" -> {
                    SetupCard("2","Start Debian 13","Boots the trusted Microdroid protected VM, enables root only inside that VM, creates the persistent Debian userspace, and proves apt Internet before reporting ready.") {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={startLinux()},enabled=!state.busy) { Text(if(state.debianStarting)"Starting…" else "Start Debian") }
                            OutlinedButton(onClick={VmSessionService.active?.probeCapabilities()},enabled=!state.busy) { Text("Probe AVF") }
                        }
                    }
                    CapabilityCard(state)
                }
                else -> {
                    ReadyCard(state)
                    if(!state.kdeInstalled) {
                        SetupCard("3","Install KDE Plasma 6","Installs Debian's Plasma desktop and TigerVNC through the vsock Internet bridge. Your Debian filesystem persists across VM restarts.") {
                            if(state.kdeInstalling) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(state.kdeStage,style=MaterialTheme.typography.bodySmall)
                            } else Button(onClick={VmSessionService.active?.installKde()},enabled=!state.busy&&state.internetReady) { Text("Install KDE Plasma 6") }
                        }
                    }
                    LinuxDisplay(Modifier.weight(1f).fillMaxWidth())
                    KeyToolbar()
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={VmSessionService.active?.stopVm()},enabled=!state.busy) { Text("Stop Linux") }
                        Text(if(state.kdeInstalled) "Plasma 6 via VNC · ${state.graphics}" else "${state.kdeStage} · ${state.graphics}",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable private fun ReadyCard(state:SessionState) {
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Column { Text("Debian 13 pVM",style=MaterialTheme.typography.labelLarge); Text("Real ARM64 userspace",style=MaterialTheme.typography.bodySmall) }
                Column(horizontalAlignment=Alignment.End) { Text(if(state.internetReady)"Internet ready" else state.internetStage,style=MaterialTheme.typography.labelLarge); Text("Persistent encrypted rootfs",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }

    private fun startLinux() {
        val metrics=resources.displayMetrics
        val refresh=(if(android.os.Build.VERSION.SDK_INT>=30) display?.refreshRate?:60f else windowManager.defaultDisplay.refreshRate).toInt()
        VmSessionService.active?.startDebian(metrics.widthPixels.coerceAtLeast(640),metrics.heightPixels.coerceAtLeast(480),metrics.densityDpi,refresh)
    }

    @Composable private fun SetupCard(step:String,title:String,body:String,content:@Composable ColumnScope.()->Unit) {
        ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.primaryContainer) { Text(step,Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelLarge) }
                    Text(title,style=MaterialTheme.typography.titleLarge)
                }
                Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }

    @Composable private fun CapabilityCard(state:SessionState) {
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp)) {
                Text("Device capability probe",style=MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(state.capabilities,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)
            }
        }
    }

    @Composable private fun LinuxDisplay(modifier:Modifier=Modifier) {
        Surface(modifier,shape=RoundedCornerShape(16.dp),color=Color.Black) {
            AndroidView(
                modifier=Modifier.fillMaxSize(),
                factory={ context -> VncFramebufferView(context).apply { requestFocus() } },
                update={ if(!it.hasFocus()) it.requestFocus() },
            )
        }
    }

    @Composable private fun KeyToolbar() {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to KeyEvent.KEYCODE_ESCAPE,"Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,"Alt" to KeyEvent.KEYCODE_ALT_LEFT,"Tab" to KeyEvent.KEYCODE_TAB).forEach { (label,key) ->
                OutlinedButton(onClick={
                    VncFramebufferView.active?.sendAndroidKey(true,key)
                    VncFramebufferView.active?.sendAndroidKey(false,key)
                },contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) { Text(label) }
            }
        }
    }

    @Composable private fun TerminalPage(state:SessionState) {
        var gateCommand by remember { mutableStateOf("id; uname -a; cat /proc/version") }
        var linuxCommand by remember { mutableStateOf("cat /etc/os-release; dpkg --print-architecture; uname -a; id; apt-get update") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Linux consoles",style=MaterialTheme.typography.headlineSmall)
            if(state.running&&state.mode=="debian") {
                Text("Debian shell",style=MaterialTheme.typography.titleMedium)
                Text("Commands execute inside the persistent Debian 13 rootfs in the protected Microdroid VM.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value=linuxCommand,onValueChange={linuxCommand=it},label={Text("Debian command")},modifier=Modifier.fillMaxWidth(),minLines=2)
                Button(onClick={VmSessionService.active?.debianConsole(linuxCommand)},enabled=!state.busy&&linuxCommand.isNotBlank()) { Text("Run in Debian") }
                SelectionContainer { ConsoleBox(state.debianTerminal.ifBlank{"No Debian commands executed yet."}) }
            } else {
                Text("Gate A · stock Microdroid",style=MaterialTheme.typography.titleMedium)
                Text("Low-level diagnostic path proving managed AVF + connectVsock + ADB.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(!state.connected) Button(onClick={connect()}) { Text("Connect") }
                    else if(!(state.running&&state.mode=="microdroid")) Button(onClick={VmSessionService.active?.startVm()},enabled=!state.busy) { Text("Start test VM") }
                    if(state.running) OutlinedButton(onClick={VmSessionService.active?.stopVm()},enabled=!state.busy) { Text("Stop") }
                    OutlinedButton(onClick={copyAllLogs(state)}) { Text("Copy logs") }
                }
                OutlinedTextField(value=gateCommand,onValueChange={gateCommand=it},label={Text("Guest command")},modifier=Modifier.fillMaxWidth(),minLines=2)
                Button(onClick={VmSessionService.active?.shell(gateCommand)},enabled=state.connected&&!state.busy&&gateCommand.isNotBlank()) { Text("Run in guest") }
                SelectionContainer { ConsoleBox(state.terminal.ifBlank{"No commands executed yet."}) }
            }
        }
    }

    @Composable private fun ConsoleBox(text:String) {
        Text(text,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(12.dp)).padding(12.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
    }

    @Composable private fun DiagnosticsPage(state:SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Diagnostics",style=MaterialTheme.typography.headlineSmall)
            Metric("Mode",state.mode); Metric("Stage",state.stage); Metric("API",state.api.ifBlank{"not connected"})
            OutlinedButton(onClick={VmSessionService.active?.startDebianDiagnostic()},enabled=state.connected&&state.debianInstalled&&!state.busy) { Text("Test Debian pVM + Internet") }
            Metric("Debian bundle",if(state.debianInstalled)"embedded" else "missing")
            Metric("Internet",if(state.internetReady)"ready" else state.internetStage)
            Metric("Desktop",if(state.kdeInstalled)"provisioned" else state.kdeStage)
            Metric("Graphics",state.graphics)
            CapabilityCard(state)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={VmSessionService.active?.probeCapabilities()},enabled=state.connected&&!state.busy) { Text("Probe AVF") }
                OutlinedButton(onClick={copyAllLogs(state)}) { Text("Copy all logs") }
                OutlinedButton(onClick={shareLogs(state)}) { Text("Share") }
            }
            SelectionContainer { ConsoleBox(state.console.ifBlank{"No VM output yet."}) }
        }
    }

    @Composable private fun Metric(label:String,value:String) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(value,Modifier.widthIn(max=260.dp),fontFamily=FontFamily.Monospace) }
    }

    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permissionListener); super.onDestroy() }
}
