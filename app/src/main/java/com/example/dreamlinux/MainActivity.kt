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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by VmSessionService.state.collectAsStateWithLifecycle()
            var page by remember { mutableIntStateOf(0) }
            var command by remember { mutableStateOf("id; uname -a; cat /proc/version") }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xffa7d3bf),
                    background = Color(0xff111716),
                    surface = Color(0xff1a2320)
                )
            ) {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                    ) {
                        Spacer(Modifier.height(20.dp))
                        Text("DEV 2 LINUX", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "GATE A  /  MANAGED AVF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            if (state.running) "Managed Microdroid running" else "VM offline",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
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
                        }

                        Spacer(Modifier.height(16.dp))
                        PrimaryTabRow(selectedTabIndex = page) {
                            listOf("Desktop", "Terminal", "Diagnostics").forEachIndexed { i, label ->
                                Tab(selected = page == i, onClick = { page = i }, text = { Text(label) })
                            }
                        }

                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (page) {
                                0 -> {
                                    Text("Desktop gate is locked", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "Debian, KDE and guest GPU acceleration stay disabled until managed VM creation, sanctioned vsock FD delivery and real guest command execution pass on the POCO."
                                    )
                                    HorizontalDivider()
                                    Text("Current release target: hardware-accelerated Debian 13 KDE after Gate A and graphics verification.")
                                }
                                1 -> {
                                    Text("Guest command terminal", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "Each command opens a sanctioned VirtualMachine.connectVsock(5555) stream and passes its existing descriptor into the native ADB transport."
                                    )
                                    OutlinedTextField(
                                        value = command,
                                        onValueChange = { command = it },
                                        label = { Text("Guest shell command") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = { VmSessionService.active?.shell(command) },
                                        enabled = state.running && !state.busy && command.isNotBlank()
                                    ) { Text("Run in guest") }
                                    SelectionContainer {
                                        Text(
                                            state.terminal.ifBlank { "No guest commands executed." },
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                2 -> {
                                    Text("Gate A diagnostics", style = MaterialTheme.typography.titleLarge)
                                    SelectionContainer {
                                        Text(
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
                                                "Guest GPU: NOT TESTED",
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    OutlinedButton(onClick = {
                                        val report = "DEV 2 LINUX development report\n" +
                                            "${state.message}\n" +
                                            "${state.name}\n" +
                                            "vm_api_init=${state.vmApiInit}\n" +
                                            "vm_data_dir=${state.vmDataDir}\n" +
                                            "vm_creation=${state.vmCreation}\n" +
                                            "vm_boot=${state.vmBoot}\n" +
                                            "connect_vsock=${state.connectVsock}\n" +
                                            "vsock_fd_received=${state.vsockFdReceived}\n" +
                                            "adb_handshake=${state.adbHandshake}\n" +
                                            "guest_command=${state.guestCommand}\n" +
                                            "reconnect=${state.reconnect}\n" +
                                            "failure_stage=${state.failureStage}\n\n" +
                                            state.console + "\n" + state.terminal
                                        startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND)
                                                    .setType("text/plain")
                                                    .putExtra(Intent.EXTRA_TEXT, report),
                                                "Export diagnostics"
                                            )
                                        )
                                    }) { Text("Export diagnostics") }
                                    SelectionContainer {
                                        Text(
                                            state.console.ifBlank { "No managed VM diagnostics yet." },
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall
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

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
