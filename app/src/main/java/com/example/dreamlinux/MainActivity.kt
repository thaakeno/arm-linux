package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
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
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) connect()
        else VmSessionService.state.value = VmSessionService.state.value.copy(
            message = "Shizuku permission denied. Grant it in Shizuku to continue."
        )
    }

    private fun connect() {
        try {
            if (!Shizuku.pingBinder()) {
                VmSessionService.state.value = VmSessionService.state.value.copy(
                    message = "Start Shizuku, then connect again."
                )
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
                return
            }
            startForegroundService(Intent(this, VmSessionService::class.java))
        } catch (e: Exception) {
            VmSessionService.state.value = VmSessionService.state.value.copy(
                message = e.message ?: "Connection failed"
            )
        }
    }

    private fun diagnosticsText(state: SessionState): String =
        "VM API init: ${state.vmApiInit}\n" +
            "VM data dir: ${state.vmDataDir.ifBlank { "not initialized" }}\n" +
            "VM creation: ${state.vmCreation}\n" +
            "VM boot: ${state.vmBoot}\n" +
            "connectVsock: ${state.connectVsock}\n" +
            "vsock FD received: ${state.vsockFdReceived}\n" +
            "ADB handshake: ${state.adbHandshake}\n" +
            "guest command: ${state.guestCommand}\n" +
            "reconnect: ${state.reconnect}\n" +
            "failure stage: ${state.failureStage}\n" +
            "Debian: NOT TESTED\n" +
            "Guest GPU: NOT TESTED"

    private fun fullLogReport(state: SessionState): String = buildString {
        appendLine("DEV 2 LINUX")
        appendLine("Managed AVF diagnostic report")
        appendLine()
        appendLine("Status: ${if (state.running) "VM RUNNING" else "VM OFFLINE"}")
        appendLine("Message: ${state.message}")
        appendLine("VM: ${state.name.ifBlank { "not created" }}")
        appendLine()
        appendLine("=== VERIFICATION ===")
        appendLine(diagnosticsText(state))
        appendLine()
        appendLine("=== MANAGED VM LOG ===")
        appendLine(state.console.ifBlank { "No managed VM diagnostics yet." })
        appendLine()
        appendLine("=== TERMINAL LOG ===")
        appendLine(state.terminal.ifBlank { "No guest commands executed." })
    }

    private fun copyAllLogs(state: SessionState) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("DEV 2 LINUX logs", fullLogReport(state)))
        Toast.makeText(this, "All DEV 2 LINUX logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogs(state: SessionState) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, fullLogReport(state)),
                "Export diagnostics"
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }
            var command by remember { mutableStateOf("id; uname -a; cat /proc/version") }
            val gateAPassed = state.vmApiInit == "PASS" &&
                state.vmCreation == "PASS" &&
                state.vmBoot == "PASS" &&
                state.connectVsock == "PASS" &&
                state.vsockFdReceived == "PASS" &&
                state.adbHandshake == "PASS" &&
                state.guestCommand == "PASS" &&
                state.reconnect == "PASS"

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xffa7d3bf),
                    background = Color(0xff0e1412),
                    surface = Color(0xff17201d),
                    surfaceVariant = Color(0xff202b27)
                )
            ) {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)
                    ) {
                        Spacer(Modifier.height(16.dp))
                        Text("DEV 2 LINUX", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            if (gateAPassed) "GATE A PASSED  /  DEBIAN NEXT" else "MANAGED AVF  /  GATE A",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (state.running) "Managed Microdroid running" else "VM offline",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!state.connected) {
                                        Button(onClick = { connect() }) { Text("Connect Shizuku") }
                                    } else {
                                        Button(
                                            onClick = { VmSessionService.active?.startVm() },
                                            enabled = !state.running && !state.busy
                                        ) { Text("Start VM") }
                                        OutlinedButton(
                                            onClick = { VmSessionService.active?.stopVm() },
                                            enabled = state.running && !state.busy
                                        ) { Text("Stop VM") }
                                    }
                                    OutlinedButton(onClick = { copyAllLogs(state) }) { Text("Copy logs") }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        PrimaryTabRow(selectedTabIndex = page) {
                            listOf("Desktop", "Terminal", "Diagnostics").forEachIndexed { i, label ->
                                Tab(selected = page == i, onClick = { page = i }, text = { Text(label) })
                            }
                        }

                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (page) {
                                0 -> {
                                    Text(
                                        if (gateAPassed) "Gate A is complete" else "Desktop gate",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        if (gateAPassed) {
                                            "Managed VM creation, sanctioned vsock FD delivery, guest command execution and restart/reconnect are verified. The next implementation gate is a real Debian 13 userspace."
                                        } else {
                                            "Finish managed VM creation, sanctioned vsock FD delivery, guest command execution and restart/reconnect verification first."
                                        }
                                    )
                                    HorizontalDivider()
                                    Text("Debian 13: NOT TESTED", style = MaterialTheme.typography.titleMedium)
                                    Text("KDE Plasma: LOCKED")
                                    Text("Guest hardware acceleration: NOT TESTED")
                                    Text(
                                        "The desktop stays unavailable until Debian execution and hardware-backed guest graphics are proven. Android-rendered placeholder graphics do not count."
                                    )
                                }

                                1 -> {
                                    Text("Guest terminal", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "Commands use VirtualMachine.connectVsock(5555) and feed the sanctioned connected descriptor into the native ADB transport."
                                    )
                                    OutlinedTextField(
                                        value = command,
                                        onValueChange = { command = it },
                                        label = { Text("Guest shell command") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { VmSessionService.active?.shell(command) },
                                            enabled = state.running && !state.busy && command.isNotBlank()
                                        ) { Text("Run in guest") }
                                        OutlinedButton(onClick = { copyAllLogs(state) }) { Text("Copy all logs") }
                                    }
                                    Card(Modifier.fillMaxWidth()) {
                                        SelectionContainer {
                                            Text(
                                                state.terminal.ifBlank { "No guest commands executed." },
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(14.dp)
                                            )
                                        }
                                    }
                                }

                                2 -> {
                                    Text("Verification", style = MaterialTheme.typography.titleLarge)
                                    Card(Modifier.fillMaxWidth()) {
                                        SelectionContainer {
                                            Text(
                                                diagnosticsText(state),
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(14.dp)
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { copyAllLogs(state) }) { Text("Copy all logs") }
                                        OutlinedButton(onClick = { exportLogs(state) }) { Text("Share logs") }
                                    }
                                    Text("Managed VM log", style = MaterialTheme.typography.titleMedium)
                                    Card(Modifier.fillMaxWidth()) {
                                        SelectionContainer {
                                            Text(
                                                state.console.ifBlank { "No managed VM diagnostics yet." },
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
