package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
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
        appendLine()
        appendLine("Status: ${if(state.running)"VM RUNNING" else "VM OFFLINE"}")
        appendLine("Message: ${state.message}")
        appendLine("VM: ${state.name.ifBlank{"not created"}}")
        appendLine("Mode: ${state.mode}")
        appendLine("Stage: ${state.stage}")
        appendLine("API: ${state.api}")
        appendLine("VM root: ${state.vmRoot}")
        appendLine("Capabilities: ${state.capabilities}")
        appendLine("Debian installed: ${state.debianInstalled}")
        appendLine("KDE: ${state.kdeInstalled} / ${state.kdeStage}")
        appendLine("Graphics: ${state.graphics}")
        appendLine()
        appendLine("=== MANAGED VM LOG ===")
        appendLine(state.console.ifBlank{"No managed VM diagnostics yet."})
        appendLine()
        appendLine("=== MICRODROID TERMINAL ===")
        appendLine(state.terminal.ifBlank{"No Microdroid commands executed."})
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
                        }
                        Box(Modifier.weight(1f)) {
                            when(page) { 0->DesktopPage(state); 1->TerminalPage(state); else->DiagnosticsPage(state) }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Header(state:SessionState) {
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DEV 1 LINUX",style=MaterialTheme.typography.headlineSmall)
                        Text("DEBIAN 13 · PLASMA 6 · AVF · NO ROOT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
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
                !state.connected -> SetupCard("1","Connect AVF","Shizuku supplies the shell-level bridge used to reach Android's virtualization stack. No root or bootloader unlock.") {
                    Button(onClick={connect()}) { Text("Connect Shizuku") }
                }
                !state.debianInstalled -> SetupCard("2","Install Debian 13","Downloads Google's current ARM64 AVF Debian image into isolated DEV 1 LINUX storage. Existing Termux and VM data are untouched.") {
                    if(state.debianInstalling) {
                        val progress=state.installProgress.coerceIn(0.0,1.0).toFloat()
                        if(state.installProgress>=0) LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(formatInstall(state),style=MaterialTheme.typography.bodySmall)
                    } else Button(onClick={VmSessionService.active?.installDebian()},enabled=!state.busy) { Text("Install Debian") }
                }
                !state.running || state.mode!="debian" -> {
                    SetupCard("3","Start accelerated VM","Starts the app-owned Debian custom VM with the requested gfxstream backend, display, touch and keyboard devices.") {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={startDebian()},enabled=!state.busy) { Text("Start Debian") }
                            OutlinedButton(onClick={VmSessionService.active?.probeCapabilities()},enabled=!state.busy) { Text("Probe AVF") }
                        }
                    }
                    CapabilityCard(state)
                }
                else -> {
                    if(!state.kdeInstalled) {
                        SetupCard("4","Install KDE Plasma 6","Installs Plasma, KWin Wayland, Konsole, Dolphin and XWayland inside Debian, disables the default Weston launch, then starts Plasma on the AVF display.") {
                            if(state.kdeInstalling) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(state.kdeStage,style=MaterialTheme.typography.bodySmall)
                            } else Button(onClick={VmSessionService.active?.installKde()},enabled=!state.busy) { Text("Install KDE Plasma") }
                        }
                    }
                    LinuxDisplay(Modifier.weight(1f).fillMaxWidth())
                    KeyToolbar()
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={VmSessionService.active?.stopVm()},enabled=!state.busy) { Text("Stop Linux") }
                        Text(if(state.kdeInstalled) "Plasma provisioned · ${state.graphics}" else "${state.kdeStage} · ${state.graphics}",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun startDebian() {
        val metrics=resources.displayMetrics
        val refresh=(display?.refreshRate?:60f).toInt()
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
            AndroidView(modifier=Modifier.fillMaxSize(),factory={ context -> SurfaceView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK); isFocusable=true; isFocusableInTouchMode=true; keepScreenOn=true
                holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(holder:SurfaceHolder) { requestFocus(); VmSessionService.active?.attachSurface(holder.surface) }
                    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) { VmSessionService.active?.attachSurface(holder.surface) }
                    override fun surfaceDestroyed(holder:SurfaceHolder) { VmSessionService.active?.detachSurface(holder.surface) }
                })
                setOnKeyListener { _,keyCode,event -> VmSessionService.active?.sendKey(event.action,keyCode,event.metaState) ?: false }
                setOnTouchListener { view,event ->
                    view.requestFocus()
                    val pointer=event.actionIndex.coerceIn(0,event.pointerCount-1)
                    VmSessionService.active?.sendTouch(event.actionMasked,event.getX(pointer),event.getY(pointer),event.getPointerId(pointer))
                    true
                }
            }},update={if(!it.hasFocus())it.requestFocus()})
        }
    }

    @Composable private fun KeyToolbar() {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to KeyEvent.KEYCODE_ESCAPE,"Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,"Alt" to KeyEvent.KEYCODE_ALT_LEFT,"Tab" to KeyEvent.KEYCODE_TAB).forEach { (label,key) ->
                OutlinedButton(onClick={VmSessionService.active?.sendKey(KeyEvent.ACTION_DOWN,key,0);VmSessionService.active?.sendKey(KeyEvent.ACTION_UP,key,0)},contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) { Text(label) }
            }
        }
    }

    @Composable private fun TerminalPage(state:SessionState) {
        var gateCommand by remember { mutableStateOf("id; uname -a; cat /proc/version") }
        var debianCommand by remember { mutableStateOf("cat /etc/os-release; uname -a; id; vulkaninfo --summary 2>/dev/null | head -60") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Linux consoles",style=MaterialTheme.typography.headlineSmall)
            if(state.running&&state.mode=="debian") {
                Text("Debian serial console",style=MaterialTheme.typography.titleMedium)
                Text("Runs commands inside the actual Debian guest through AVF's captured console channel.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value=debianCommand,onValueChange={debianCommand=it},label={Text("Debian command")},modifier=Modifier.fillMaxWidth(),minLines=2)
                Button(onClick={VmSessionService.active?.debianConsole(debianCommand)},enabled=!state.busy&&debianCommand.isNotBlank()) { Text("Run in Debian") }
                SelectionContainer { ConsoleBox(state.debianTerminal.ifBlank{"No Debian commands executed yet."}) }
            } else {
                Text("Gate A · managed Microdroid",style=MaterialTheme.typography.titleMedium)
                Text("Proves VirtualMachine.connectVsock() and passed-FD ADB without direct AF_VSOCK creation.",color=MaterialTheme.colorScheme.onSurfaceVariant)
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
            Metric("Debian",if(state.debianInstalled)"image installed" else "not installed")
            Metric("KDE",if(state.kdeInstalled)"provisioned" else state.kdeStage)
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

    private fun formatInstall(state:SessionState):String {
        fun mb(v:Long)=if(v<0)"?" else "${v/1024/1024} MB"
        return if(state.installTotal>0)"${mb(state.installBytes)} / ${mb(state.installTotal)}" else mb(state.installBytes)
    }

    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permissionListener); super.onDestroy() }
}
