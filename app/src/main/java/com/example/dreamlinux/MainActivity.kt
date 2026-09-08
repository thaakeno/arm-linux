package com.example.dreamlinux

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
        else VmSessionService.state.value=VmSessionService.state.value.copy(message="Shizuku permission denied. Grant it in Shizuku to continue.")
    }
    private fun connect() {
        try {
            if(!Shizuku.pingBinder()) { VmSessionService.state.value=VmSessionService.state.value.copy(message="Start Shizuku, then connect again."); return }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(1); return }
            startForegroundService(Intent(this,VmSessionService::class.java))
        } catch(e:Exception) { VmSessionService.state.value=VmSessionService.state.value.copy(message=e.message?:"Connection failed") }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }
            var command by remember { mutableStateOf("id; uname -a") }
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xffa7d3bf),background=Color(0xff111716),surface=Color(0xff1a2320))) {
                Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Text("Dream Linux",style=MaterialTheme.typography.headlineLarge)
                    Text("DEVELOPMENT  /  GUEST EXECUTION",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text(if(state.running) "Microdroid running ? CID ${state.cid}" else "VM offline",style=MaterialTheme.typography.titleMedium)
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
                                Text("Desktop is not available yet",style=MaterialTheme.typography.titleLarge)
                                Text("This build tests the real VM connection. Debian, KDE and guest GPU acceleration have not passed their verification gates.")
                                HorizontalDivider()
                                Text("Required next: supported Debian execution and a verified guest-to-GPU rendering connection.")
                            }
                            1 -> {
                                Text("Guest command terminal",style=MaterialTheme.typography.titleLarge)
                                Text("Commands run in this app?s Microdroid VM through vsock. This is a bounded command console, not an interactive PTY.")
                                OutlinedTextField(value=command,onValueChange={command=it},label={Text("Guest shell command")},modifier=Modifier.fillMaxWidth())
                                Button(onClick={VmSessionService.active?.shell(command)},enabled=state.running&&state.cid>0&&!state.busy&&command.isNotBlank()) { Text("Run in guest") }
                                SelectionContainer { Text(state.terminal.ifBlank { "No guest commands executed." },fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                            }
                            2 -> {
                                Text("Verification status",style=MaterialTheme.typography.titleLarge)
                                Text("Debian: not established\nGuest GPU: unproven\nPerformance: not benchmarked\nVM: ${state.name.ifBlank { "not started" }}")
                                OutlinedButton(onClick={
                                    val report="Dream Linux development report\n${state.message}\n${state.name} CID ${state.cid}\n${state.console}\n${state.terminal}"
                                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Export diagnostics"))
                                }) { Text("Export diagnostics") }
                                SelectionContainer { Text(state.console.ifBlank { "No VM console output." },fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                } }
            }
        }
    }
    override fun onDestroy(){ Shizuku.removeRequestPermissionResultListener(permissionListener);super.onDestroy() }
}
