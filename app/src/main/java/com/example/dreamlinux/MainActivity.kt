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
    private val permissionListener=Shizuku.OnRequestPermissionResultListener { _,result ->
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

    private fun diagnosticsText(s:SessionState)=buildString {
        appendLine("Mode: ${s.mode}")
        appendLine("VM API init: ${s.vmApiInit}")
        appendLine("VM data dir: ${s.vmDataDir.ifBlank{"not initialized"}}")
        appendLine("VM creation: ${s.vmCreation}")
        appendLine("VM boot: ${s.vmBoot}")
        appendLine("connectVsock: ${s.connectVsock}")
        appendLine("vsock FD received: ${s.vsockFdReceived}")
        appendLine("ADB handshake: ${s.adbHandshake}")
        appendLine("guest command: ${s.guestCommand}")
        appendLine("reconnect: ${s.reconnect}")
        appendLine("failure stage: ${s.failureStage}")
        appendLine("Capabilities: ${s.capabilities}")
        appendLine("Debian image: ${if(s.debianInstalled)"PASS" else if(s.debianInstalling)"INSTALLING" else "NOT TESTED"}")
        appendLine("Debian boot: ${s.debianBoot}")
        appendLine("Debian identity: ${s.debianIdentity}")
        appendLine("Display attach: ${s.display}")
        appendLine("Guest graphics: ${s.graphics}")
        appendLine("KDE Plasma: ${if(s.kdeInstalled)"PASS" else s.kdeStage}")
    }

    private fun fullLogReport(s:SessionState)=buildString {
        appendLine("DEV 2 LINUX")
        appendLine("AVF Linux diagnostic report")
        appendLine()
        appendLine("Status: ${if(s.running)"VM RUNNING" else "VM OFFLINE"}")
        appendLine("Message: ${s.message}")
        appendLine("VM: ${s.name.ifBlank{"not created"}}")
        appendLine()
        appendLine("=== VERIFICATION ===")
        appendLine(diagnosticsText(s))
        appendLine("=== MANAGED VM / CONSOLE LOG ===")
        appendLine(s.console.ifBlank{"No managed VM diagnostics yet."})
        appendLine()
        appendLine("=== MICRODROID TERMINAL ===")
        appendLine(s.terminal.ifBlank{"No Microdroid commands executed."})
        appendLine()
        appendLine("=== DEBIAN TERMINAL ===")
        appendLine(s.debianTerminal.ifBlank{"No Debian commands executed."})
    }

    private fun copyAllLogs(s:SessionState) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("DEV 2 LINUX logs",fullLogReport(s)))
        Toast.makeText(this,"All DEV 2 LINUX logs copied",Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs(s:SessionState) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,fullLogReport(s)),"Share DEV 2 LINUX logs"))
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }
            MaterialTheme(colorScheme=darkColorScheme(
                primary=Color(0xff9FE0C4),secondary=Color(0xffAFC7FF),
                background=Color(0xff090D0C),surface=Color(0xff111715),surfaceVariant=Color(0xff18211E))) {
                Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
                    NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                        listOf("Desktop","Terminal","Diagnostics").forEachIndexed { i,label ->
                            NavigationBarItem(selected=page==i,onClick={page=i},icon={Text(if(i==0)"D" else if(i==1)"T" else "I")},label={Text(label)})
                        }
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Header(state)
                        Box(Modifier.weight(1f)) {
                            when(page) { 0->DesktopPage(state);1->TerminalPage(state);else->DiagnosticsPage(state) }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Header(s:SessionState) {
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DEV 2 LINUX",style=MaterialTheme.typography.headlineSmall)
                        Text("DEBIAN 13 · KDE PLASMA · ANDROID AVF",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    }
                    Surface(shape=RoundedCornerShape(999.dp),color=if(s.running)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Text(if(s.running)"RUNNING" else if(s.connected)"READY" else "OFFLINE",Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall)
                    }
                }
                Text(s.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={copyAllLogs(s)},contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)) { Text("Copy all logs") }
                    if(s.running) TextButton(onClick={VmSessionService.active?.stopVm()},enabled=!s.busy) { Text("Stop VM") }
                }
            }
        }
    }

    @Composable private fun DesktopPage(s:SessionState) {
        val scroll=rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when {
                !s.connected -> StepCard("1","Connect AVF","Shizuku gives DEV 2 LINUX the shell-level bridge needed for Android's virtualization APIs. No root or bootloader unlock.") {
                    Button(onClick={connect}) { Text("Connect Shizuku") }
                }
                !s.debianInstalled -> {
                    StepCard("2","Install Debian 13","Downloads Google's official ARM64 AVF Linux image into isolated DEV 2 LINUX storage. Existing Termux and other VMs stay untouched.") {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={VmSessionService.active?.probeCapabilities()},enabled=!s.busy) { Text("Probe AVF") }
                            if(!s.debianInstalling) Button(onClick={VmSessionService.active?.installDebian()},enabled=!s.busy) { Text("Install Debian") }
                        }
                        if(s.debianInstalling) {
                            if(s.installProgress>=0) LinearProgressIndicator(progress={s.installProgress.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(installProgress(s),style=MaterialTheme.typography.bodySmall)
                        }
                        if(s.capabilities!="NOT TESTED") MonoBox(s.capabilities)
                    }
                }
                s.mode!="debian" || !s.running -> {
                    StepCard("3","Start Debian VM","Starts the official custom Debian VM only if this POCO exposes the required non-protected custom-VM and crosvm display/GPU APIs.") {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={startDebian()},enabled=!s.busy) { Text("Start Debian") }
                            OutlinedButton(onClick={VmSessionService.active?.probeCapabilities()},enabled=!s.busy) { Text("Probe AVF") }
                        }
                    }
                    CapabilityCard(s)
                }
                else -> {
                    if(!s.kdeInstalled) StepCard("4","Install KDE Plasma","Installs Debian 13 Plasma/KWin Wayland, Konsole, Dolphin and XWayland, then launches Plasma on the AVF display. Software rendering is never labeled accelerated.") {
                        if(s.kdeInstalling) {
                            LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.kdeStage)
                        } else Button(onClick={VmSessionService.active?.installKde()},enabled=!s.busy) { Text("Install KDE Plasma") }
                    }
                    Text("Linux display",style=MaterialTheme.typography.titleMedium)
                    LinuxDisplay(Modifier.fillMaxWidth().height(430.dp))
                    KeyToolbar()
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StatusChip("Debian",s.debianIdentity)
                        StatusChip("Display",s.display)
                    }
                    StatusChip("Graphics",s.graphics)
                    if(s.graphics.contains("SOFTWARE",true)) Text("Software renderer detected. This does not count as Gate B hardware acceleration.",color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    private fun startDebian():()->Unit = {
        val m=resources.displayMetrics
        val refresh=(display?.refreshRate?:60f).toInt()
        VmSessionService.active?.startDebian(m.widthPixels.coerceAtLeast(640),m.heightPixels.coerceAtLeast(480),m.densityDpi,refresh)
    }

    @Composable private fun StepCard(step:String,title:String,body:String,content:@Composable ColumnScope.()->Unit) {
        ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.primaryContainer) { Text(step,Modifier.padding(horizontal=9.dp,vertical=5.dp)) }
                    Text(title,style=MaterialTheme.typography.titleLarge)
                }
                Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }

    @Composable private fun CapabilityCard(s:SessionState) {
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("POCO AVF capability probe",style=MaterialTheme.typography.labelLarge)
                Text(s.capabilities,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                Text("Debian boot: ${s.debianBoot} · GPU: ${s.graphics}",style=MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable private fun LinuxDisplay(modifier:Modifier) {
        Surface(modifier,shape=RoundedCornerShape(16.dp),color=Color.Black) {
            AndroidView(modifier=Modifier.fillMaxSize(),factory={context->SurfaceView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK);isFocusable=true;isFocusableInTouchMode=true;keepScreenOn=true
                holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(h:SurfaceHolder){requestFocus();VmSessionService.active?.attachSurface(h.surface)}
                    override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,he:Int){VmSessionService.active?.attachSurface(h.surface)}
                    override fun surfaceDestroyed(h:SurfaceHolder){VmSessionService.active?.detachSurface(h.surface)}
                })
                setOnKeyListener { _,code,event -> VmSessionService.active?.sendKey(event.action,code,event.metaState)?:false }
                setOnTouchListener { view,event ->
                    view.requestFocus();val index=event.actionIndex.coerceIn(0,event.pointerCount-1)
                    VmSessionService.active?.sendTouch(event.actionMasked,event.getX(index),event.getY(index),event.getPointerId(index));true
                }
            }})
        }
    }

    @Composable private fun KeyToolbar() {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to KeyEvent.KEYCODE_ESCAPE,"Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,"Alt" to KeyEvent.KEYCODE_ALT_LEFT,"Tab" to KeyEvent.KEYCODE_TAB).forEach { (label,key)->
                OutlinedButton(onClick={VmSessionService.active?.sendKey(KeyEvent.ACTION_DOWN,key,0);VmSessionService.active?.sendKey(KeyEvent.ACTION_UP,key,0)},contentPadding=PaddingValues(horizontal=12.dp,vertical=7.dp)) { Text(label) }
            }
        }
    }

    @Composable private fun TerminalPage(s:SessionState) {
        var gate by remember { mutableStateOf("id; uname -a; cat /proc/version") }
        var debian by remember { mutableStateOf("cat /etc/os-release; uname -a; id; ls -l /dev/dri 2>&1; vulkaninfo --summary 2>/dev/null | head -80") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Linux terminals",style=MaterialTheme.typography.headlineSmall)
            Text("Debian 13",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(value=debian,onValueChange={debian=it},label={Text("Debian command")},modifier=Modifier.fillMaxWidth(),minLines=2)
            Button(onClick={VmSessionService.active?.debianConsole(debian)},enabled=s.running&&s.mode=="debian"&&!s.busy&&debian.isNotBlank()) { Text("Run in Debian") }
            MonoBox(s.debianTerminal.ifBlank{"Debian terminal becomes available after the official VM boots."})
            HorizontalDivider()
            Text("Gate A · Microdroid",style=MaterialTheme.typography.titleMedium)
            Text("Kept as the known-good AVF/vsock diagnostic path.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(s.mode!="microdroid"||!s.running) Button(onClick={VmSessionService.active?.startVm()},enabled=!s.busy) { Text("Start test VM") }
                OutlinedButton(onClick={copyAllLogs(s)}) { Text("Copy all logs") }
            }
            OutlinedTextField(value=gate,onValueChange={gate=it},label={Text("Microdroid command")},modifier=Modifier.fillMaxWidth(),minLines=2)
            Button(onClick={VmSessionService.active?.shell(gate)},enabled=s.running&&s.mode=="microdroid"&&!s.busy&&gate.isNotBlank()) { Text("Run in Microdroid") }
            MonoBox(s.terminal.ifBlank{"No Microdroid commands executed in this session."})
        }
    }

    @Composable private fun DiagnosticsPage(s:SessionState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Diagnostics",style=MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={copyAllLogs(s)}) { Text("Copy all logs") }
                OutlinedButton(onClick={shareLogs(s)}) { Text("Share logs") }
                OutlinedButton(onClick={VmSessionService.active?.probeCapabilities()},enabled=s.connected&&!s.busy) { Text("Probe AVF") }
            }
            MonoBox(diagnosticsText(s))
            Text("Managed VM / console log",style=MaterialTheme.typography.titleMedium)
            MonoBox(s.console.ifBlank{"No managed VM log yet."})
        }
    }

    @Composable private fun MonoBox(text:String) {
        SelectionContainer { Text(text,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(12.dp)).padding(12.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
    }

    @Composable private fun StatusChip(label:String,value:String) {
        Surface(shape=RoundedCornerShape(999.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Text("$label: $value",Modifier.padding(horizontal=11.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall)
        }
    }

    private fun installProgress(s:SessionState):String {
        fun mb(v:Long)=if(v<0)"?" else "${v/1024/1024} MB"
        return if(s.installTotal>0)"${mb(s.installBytes)} / ${mb(s.installTotal)}" else mb(s.installBytes)
    }

    override fun onDestroy(){Shizuku.removeRequestPermissionResultListener(permissionListener);super.onDestroy()}
}
