package com.example.dreamlinux

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val permissionListener=Shizuku.OnRequestPermissionResultListener { _, result ->
        if(result==PackageManager.PERMISSION_GRANTED) connect()
        else VmSessionService.state.value=VmSessionService.state.value.copy(message="Shizuku permission denied")
    }
    private fun connect() {
        try {
            if(!Shizuku.pingBinder()) { VmSessionService.state.value=VmSessionService.state.value.copy(message="Start Shizuku first"); return }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(1); return }
            startForegroundService(Intent(this,VmSessionService::class.java))
        } catch(e:Exception) { VmSessionService.state.value=VmSessionService.state.value.copy(message=e.message?:"Connection failed") }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }
            var command by remember { mutableStateOf("id; uname -a; cat /proc/version") }
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xff8fd8bd),background=Color(0xff0d1211),surface=Color(0xff151d1a))) {
                Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
                    Spacer(Modifier.height(18.dp))
                    Text("DEV 1 LINUX",style=MaterialTheme.typography.headlineLarge)
                    Text("AVF / NO ROOT / DEVELOPMENT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(18.dp))
                    Text(if(state.running) "Managed VM running" else "VM offline",style=MaterialTheme.typography.titleMedium)
                    Text(state.message,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=8.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(!state.connected) Button(onClick={connect()}) { Text("Connect Shizuku") }
                        else {
                            Button(onClick={VmSessionService.active?.startVm()},enabled=!state.running&&!state.busy) { Text("Start VM") }
                            OutlinedButton(onClick={VmSessionService.active?.stopVm()},enabled=state.running&&!state.busy) { Text("Stop VM") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryTabRow(selectedTabIndex=page) {
                        listOf("Desktop","Terminal","Diagnostics").forEachIndexed { i,label ->
                            Tab(selected=page==i,onClick={page=i},text={Text(label)})
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        when(page) {
                            0 -> {
                                Text("Desktop gate",style=MaterialTheme.typography.titleLarge)
                                Text("The desktop stays locked until Debian execution and hardware-backed guest graphics are proven. No fake Android-rendered desktop is used.")
                                HorizontalDivider()
                                Text("Current target: managed AVF VM → sanctioned vsock FD → guest command. Debian 13 and KDE come after that path survives on-device testing.")
                            }
                            1 -> {
                                Text("Guest command terminal",style=MaterialTheme.typography.titleLarge)
                                Text("Commands use VirtualMachine.connectVsock() and an already-connected descriptor. The app never creates AF_VSOCK directly.")
                                OutlinedTextField(value=command,onValueChange={command=it},label={Text("Guest shell command")},modifier=Modifier.fillMaxWidth())
                                Button(onClick={VmSessionService.active?.shell(command)},enabled=state.running&&!state.busy&&command.isNotBlank()) { Text("Run in guest") }
                                SelectionContainer { Text(state.terminal.ifBlank { "No guest commands executed." },fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                            }
                            2 -> {
                                Text("Verification",style=MaterialTheme.typography.titleLarge)
                                Text("Debian: not established\nGuest GPU: unproven\nPerformance: not benchmarked\nVM: ${state.name.ifBlank { "not created" }}")
                                OutlinedButton(onClick={
                                    val report="DEV 1 LINUX report\n${state.message}\n${state.name}\n${state.console}\n${state.terminal}"
                                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Export diagnostics"))
                                }) { Text("Export diagnostics") }
                                SelectionContainer { Text(state.console.ifBlank { "No VM diagnostics yet." },fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                } }
            }
        }
    }
    override fun onDestroy(){ Shizuku.removeRequestPermissionResultListener(permissionListener); super.onDestroy() }
}
