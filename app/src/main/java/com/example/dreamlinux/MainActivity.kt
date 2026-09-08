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
import androidx.compose.foundation.horizontalScroll
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
        else VmSessionService.state.value=
            VmSessionService.state.value.copy(message="Shizuku permission denied")
    }

    private fun connect() {
        try {
            if(!Shizuku.pingBinder()) {
                VmSessionService.state.value=
                    VmSessionService.state.value.copy(message="Start Shizuku first")
                return
            }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
                return
            }
            startForegroundService(Intent(this,VmSessionService::class.java))
        } catch(e:Exception) {
            VmSessionService.state.value=
                VmSessionService.state.value.copy(message=e.message?:"Connection failed")
        }
    }

    private fun diagnosticsText(s:SessionState)=buildString {
        appendLine("Mode: ${s.mode}${if(s.mode=="debian") if(s.debianProtected)" (protected)" else " (non-protected)" else ""}")
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
            ClipData.newPlainText("DEV 2 LINUX logs",fullLogReport(s))
        )
        Toast.makeText(this,"All logs copied",Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs(s:SessionState) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT,fullLogReport(s)),
                "Share DEV 2 LINUX logs"
            )
        )
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)

        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }

            MaterialTheme(
                colorScheme=darkColorScheme(
                    primary=Color(0xff9FE0C4),
                    secondary=Color(0xffAFC7FF),
                    background=Color(0xff080B0A),
                    surface=Color(0xff101614),
                    surfaceVariant=Color(0xff17201D)
                )
            ) {
                Scaffold(
                    containerColor=MaterialTheme.colorScheme.background,
                    topBar={ CompactHeader(state,onCopy={copyAllLogs(state)}) },
                    bottomBar={
                        NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                            listOf("Desktop","Terminal","Diagnostics").forEachIndexed { i,label ->
                                NavigationBarItem(
                                    selected=page==i,
                                    onClick={page=i},
                                    icon={Text(when(i){0->"D";1->"T";else->"I"})},
                                    label={Text(label)}
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when(page) {
                            0 -> DesktopPage(state)
                            1 -> TerminalPage(state)
                            else -> DiagnosticsPage(state)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CompactHeader(s:SessionState,onCopy:()->Unit) {
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DEV 2 LINUX",style=MaterialTheme.typography.titleLarge)
                        Text(
                            "Debian 13 · KDE Plasma · AVF",
                            style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.primary
                        )
                    }
                    StatePill(if(s.running)"RUNNING" else if(s.connected)"READY" else "OFFLINE")
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        s.message,
                        Modifier.weight(1f),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick=onCopy,contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp)) {
                        Text("Copy logs")
                    }
                    if(s.running) {
                        TextButton(
                            onClick={VmSessionService.active?.stopVm()},
                            enabled=!s.busy,
                            contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp)
                        ) { Text("Stop") }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatePill(text:String) {
        Surface(shape=RoundedCornerShape(999.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                text,
                Modifier.padding(horizontal=10.dp,vertical=5.dp),
                style=MaterialTheme.typography.labelSmall
            )
        }
    }

    @Composable
    private fun DesktopPage(s:SessionState) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            StatusOverview(s)

            when {
                !s.connected -> ActionCard(
                    "Connect AVF",
                    "Shizuku provides the shell-level bridge. No root, bootloader unlock or flashing."
                ) {
                    Button(onClick={connect()}) { Text("Connect Shizuku") }
                }

                !s.debianInstalled -> ActionCard(
                    "Install Debian 13",
                    "Downloads Google's official ARM64 AVF Linux image into DEV 2 LINUX storage."
                ) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick={VmSessionService.active?.probeCapabilities()},
                            enabled=!s.busy
                        ) { Text("Probe AVF") }
                        Button(
                            onClick={VmSessionService.active?.installDebian()},
                            enabled=!s.busy&&!s.debianInstalling
                        ) { Text("Install Debian") }
                    }
                    if(s.debianInstalling) {
                        if(s.installProgress>=0) {
                            LinearProgressIndicator(
                                progress={s.installProgress.toFloat().coerceIn(0f,1f)},
                                modifier=Modifier.fillMaxWidth()
                            )
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(installProgress(s),style=MaterialTheme.typography.bodySmall)
                    }
                }

                s.mode!="debian" || !s.running -> ActionCard(
                    "Boot Debian",
                    if(s.capabilities.contains("nonPVM=false"))
                        "This phone is pVM-only. DEV 2 LINUX will now test the official Debian custom image as a protected VM instead of rejecting it before launch."
                    else
                        "Starts the official Debian custom image using the best AVF mode exposed by this device."
                ) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={startDebian()},enabled=!s.busy) { Text("Start Debian") }
                        OutlinedButton(
                            onClick={VmSessionService.active?.probeCapabilities()},
                            enabled=!s.busy
                        ) { Text("Probe again") }
                    }
                }

                else -> {
                    if(!s.kdeInstalled) {
                        ActionCard(
                            "Desktop",
                            if(s.debianProtected)
                                "Protected Debian is running. Standard pVM networking is disabled, so Plasma can start only if it is already present; otherwise host-mediated networking becomes the next gate."
                            else
                                "Install and launch KDE Plasma Wayland inside Debian."
                        ) {
                            Button(
                                onClick={VmSessionService.active?.installKde()},
                                enabled=!s.busy&&!s.kdeInstalling
                            ) {
                                Text(if(s.kdeInstalling)s.kdeStage else "Start KDE setup")
                            }
                            if(s.kdeInstalling) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }

                    Text("Linux display",style=MaterialTheme.typography.titleMedium)
                    LinuxDisplay(Modifier.fillMaxWidth().height(420.dp))
                    KeyToolbar()

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)
                    ) {
                        StatusChip("Debian",s.debianIdentity)
                        StatusChip("Display",s.display)
                        StatusChip("Graphics",s.graphics)
                    }

                    if(s.display=="BLOCKED" || s.graphics.contains("SOFTWARE",true)) {
                        Text(
                            if(s.display=="BLOCKED")
                                "The VM booted, but Android's crosvm display service is blocked or unavailable on this build. Copy the logs so we can target that exact gate."
                            else
                                "Software rendering detected. This does not count as hardware acceleration.",
                            color=MaterialTheme.colorScheme.error,
                            style=MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusOverview(s:SessionState) {
        ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) {
            Column(
                Modifier.padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("System status",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                    Text(
                        if(s.failureStage=="none")"NO ACTIVE FAILURE" else s.failureStage.uppercase(),
                        style=MaterialTheme.typography.labelSmall,
                        color=if(s.failureStage=="none")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    s.capabilities,
                    fontFamily=FontFamily.Monospace,
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    StatusChip("Gate A",if(s.guestCommand=="PASS")"PASS" else s.vmBoot)
                    StatusChip("Debian",s.debianBoot)
                    StatusChip("Display",s.display)
                }
            }
        }
    }

    private fun startDebian():()->Unit = {
        val m=resources.displayMetrics
        val refresh=(display?.refreshRate?:60f).toInt()
        VmSessionService.active?.startDebian(
            m.widthPixels.coerceAtLeast(640),
            m.heightPixels.coerceAtLeast(480),
            m.densityDpi,
            refresh
        )
    }

    @Composable
    private fun ActionCard(title:String,body:String,content:@Composable ColumnScope.()->Unit) {
        ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ) {
                Text(title,style=MaterialTheme.typography.titleLarge)
                Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }

    @Composable
    private fun LinuxDisplay(modifier:Modifier) {
        Surface(modifier,shape=RoundedCornerShape(15.dp),color=Color.Black) {
            AndroidView(
                modifier=Modifier.fillMaxSize(),
                factory={context->
                    SurfaceView(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable=true
                        isFocusableInTouchMode=true
                        keepScreenOn=true

                        holder.addCallback(object:SurfaceHolder.Callback {
                            override fun surfaceCreated(h:SurfaceHolder) {
                                requestFocus()
                                VmSessionService.active?.attachSurface(h.surface)
                            }
                            override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,he:Int) {
                                VmSessionService.active?.attachSurface(h.surface)
                            }
                            override fun surfaceDestroyed(h:SurfaceHolder) {
                                VmSessionService.active?.detachSurface(h.surface)
                            }
                        })

                        setOnKeyListener { _,code,event ->
                            VmSessionService.active?.sendKey(
                                event.action,
                                code,
                                event.scanCode,
                                event.metaState
                            ) ?: false
                        }

                        setOnTouchListener { view,event ->
                            view.requestFocus()
                            val index=event.actionIndex.coerceIn(0,event.pointerCount-1)
                            VmSessionService.active?.sendTouch(
                                event.actionMasked,
                                event.getX(index),
                                event.getY(index),
                                event.getPointerId(index)
                            )
                            true
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun KeyToolbar() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                Triple("Esc",KeyEvent.KEYCODE_ESCAPE,1),
                Triple("Ctrl",KeyEvent.KEYCODE_CTRL_LEFT,29),
                Triple("Alt",KeyEvent.KEYCODE_ALT_LEFT,56),
                Triple("Tab",KeyEvent.KEYCODE_TAB,15)
            ).forEach { (label,key,scan) ->
                OutlinedButton(
                    onClick={
                        VmSessionService.active?.sendKey(KeyEvent.ACTION_DOWN,key,scan,0)
                        VmSessionService.active?.sendKey(KeyEvent.ACTION_UP,key,scan,0)
                    },
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)
                ) { Text(label) }
            }
        }
    }

    @Composable
    private fun TerminalPage(s:SessionState) {
        var gate by remember { mutableStateOf("id; uname -a; cat /proc/version") }
        var debian by remember {
            mutableStateOf("cat /etc/os-release; uname -a; id; ls -l /dev/dri 2>&1; vulkaninfo --summary 2>/dev/null | head -80")
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            Text("Terminal",style=MaterialTheme.typography.headlineSmall)

            Text("Debian",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value=debian,
                onValueChange={debian=it},
                label={Text("Debian command")},
                modifier=Modifier.fillMaxWidth(),
                minLines=2
            )
            Button(
                onClick={VmSessionService.active?.debianConsole(debian)},
                enabled=s.running&&s.mode=="debian"&&!s.busy&&debian.isNotBlank()
            ) { Text("Run in Debian") }
            MonoBox(s.debianTerminal.ifBlank{"Debian terminal becomes available after the custom VM boots."})

            HorizontalDivider()

            Text("Gate A · Microdroid",style=MaterialTheme.typography.titleMedium)
            Text(
                "Known-good protected AVF/vsock path. Guest commands auto-start the test VM if it is offline.",
                color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value=gate,
                onValueChange={gate=it},
                label={Text("Microdroid command")},
                modifier=Modifier.fillMaxWidth(),
                minLines=2
            )
            Button(
                onClick={VmSessionService.active?.shell(gate)},
                enabled=s.connected&&!s.busy&&gate.isNotBlank()
            ) { Text("Run Gate A command") }
            MonoBox(s.terminal.ifBlank{"No Microdroid commands executed in this session."})
        }
    }

    @Composable
    private fun DiagnosticsPage(s:SessionState) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Diagnostics",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                TextButton(onClick={copyAllLogs(s)}) { Text("Copy") }
                TextButton(onClick={shareLogs(s)}) { Text("Share") }
            }

            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick={VmSessionService.active?.probeCapabilities()},
                    enabled=s.connected&&!s.busy
                ) { Text("Probe AVF") }
                if(!s.running) {
                    OutlinedButton(
                        onClick={VmSessionService.active?.startVm()},
                        enabled=s.connected&&!s.busy
                    ) { Text("Test Gate A") }
                }
            }

            MonoBox(diagnosticsText(s))
            Text("Managed VM / console",style=MaterialTheme.typography.titleMedium)
            MonoBox(s.console.ifBlank{"No managed VM log yet."})
        }
    }

    @Composable
    private fun MonoBox(text:String) {
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface,RoundedCornerShape(12.dp))
                    .padding(12.dp),
                fontFamily=FontFamily.Monospace,
                style=MaterialTheme.typography.bodySmall
            )
        }
    }

    @Composable
    private fun StatusChip(label:String,value:String) {
        Surface(shape=RoundedCornerShape(999.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "$label: $value",
                Modifier.padding(horizontal=10.dp,vertical=6.dp),
                style=MaterialTheme.typography.labelSmall
            )
        }
    }

    private fun installProgress(s:SessionState):String {
        fun mb(v:Long)=if(v<0)"?" else "${v/1024/1024} MB"
        return if(s.installTotal>0) "${mb(s.installBytes)} / ${mb(s.installTotal)}"
        else mb(s.installBytes)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
