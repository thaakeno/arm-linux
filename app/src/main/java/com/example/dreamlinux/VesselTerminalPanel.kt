package com.example.dreamlinux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun VesselTerminalPanel() {
    val context = LocalContext.current
    val terminalState by VesselTerminalManager.state.collectAsStateWithLifecycle()
    val hostState by VesselHostDebug.state.collectAsStateWithLifecycle()

    var hostMode by remember { mutableStateOf(false) }
    var hostCommand by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchTranscript by remember { mutableStateOf("") }
    var terminalHost by remember { mutableStateOf<VesselTerminalViewHost?>(null) }

    val selectedTab = terminalState.tabs.firstOrNull { it.id == terminalState.selectedId }
    val selectedSession = selectedTab?.let { VesselTerminalManager.session(it.id) }

    LaunchedEffect(Unit) {
        VesselTerminalManager.initialize(context)
        VesselTerminalManager.refreshAvailability()
    }

    LaunchedEffect(searchOpen, searchQuery, terminalState.selectedId) {
        if (!searchOpen || searchQuery.isBlank() || terminalState.selectedId == 0L) {
            searchTranscript = ""
            return@LaunchedEffect
        }
        while (isActive) {
            searchTranscript = VesselTerminalManager.transcript(terminalState.selectedId)
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (hostMode) "Android host debug · app sandbox" else terminalState.availability,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!hostMode && selectedTab != null) {
                TerminalStatePill(
                    if (selectedTab.closing) "CLOSING" else if (selectedTab.running) "LIVE" else "EXITED",
                    selectedTab.running && !selectedTab.closing,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterChip(selected = !hostMode, onClick = { hostMode = false }, label = { Text("Linux PTY") })
            FilterChip(selected = hostMode, onClick = { hostMode = true }, label = { Text("Host Debug") })
        }

        if (hostMode) {
            HostTerminalPanel(
                context = context,
                output = hostState.output,
                busy = hostState.busy,
                command = hostCommand,
                onCommandChange = { hostCommand = it },
                onRun = {
                    val command = hostCommand.trim()
                    if (command.isNotEmpty()) {
                        VesselHostDebug.run(command)
                        hostCommand = ""
                    }
                },
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            terminalState.tabs.forEach { tab ->
                FilterChip(
                    selected = tab.id == terminalState.selectedId,
                    onClick = { VesselTerminalManager.select(tab.id) },
                    enabled = !tab.closing,
                    label = {
                        Text(
                            "Terminal " + tab.number,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            Button(
                onClick = { VesselTerminalManager.createTerminal() },
                enabled = terminalState.ready && terminalState.tabs.size < terminalState.maxSessions,
            ) {
                Text("+ New")
            }
            if (selectedTab != null) {
                OutlinedButton(
                    onClick = { VesselTerminalManager.close(selectedTab.id) },
                    enabled = !selectedTab.closing,
                ) {
                    Text("Close")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val text = VesselTerminalManager.transcript()
                    if (text.isBlank()) {
                        toast(context, "No terminal output to copy")
                    } else {
                        copy(context, "Terminal logs", text)
                    }
                },
                enabled = selectedTab != null,
            ) {
                Text("Copy logs")
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clip = clipboard?.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                    } else {
                        ""
                    }
                    if (text.isNotEmpty()) VesselTerminalManager.pasteSelected(text)
                },
                enabled = selectedTab?.running == true,
            ) {
                Text("Paste")
            }
            OutlinedButton(
                onClick = { VesselTerminalManager.clearHistory() },
                enabled = selectedTab != null,
            ) {
                Text("Clear history")
            }
            OutlinedButton(
                onClick = { searchOpen = !searchOpen },
                enabled = selectedTab != null,
            ) {
                Text(if (searchOpen) "Hide search" else "Search")
            }
            OutlinedButton(
                onClick = { terminalHost?.showKeyboard() },
                enabled = selectedTab?.running == true,
            ) {
                Text("Keyboard")
            }
        }

        if (terminalState.lastError.isNotBlank()) {
            TerminalMessage(terminalState.lastError, error = true)
        }
        if (selectedTab?.cleanupWarning?.isNotBlank() == true) {
            TerminalMessage(selectedTab.cleanupWarning, error = true)
        }
        if (!terminalState.ready && terminalState.tabs.isEmpty()) {
            TerminalMessage(
                terminalState.availability +
                    ". This phase keeps PTY activation gated until the official proroot runtime and directory rootfs are installed.",
                error = false,
            )
        }

        if (searchOpen) {
            val matches = remember(searchQuery, searchTranscript) {
                countMatches(searchTranscript, searchQuery)
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search scrollback") },
                supportingText = {
                    Text(
                        if (searchQuery.isBlank()) "Search only reads scrollback while this field is active"
                        else matches.toString() + " matches",
                    )
                },
            )
        }

        Surface(
            Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (selectedSession != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            VesselTerminalViewHost(viewContext).also {
                                terminalHost = it
                                it.bind(selectedSession)
                            }
                        },
                        update = { host ->
                            terminalHost = host
                            host.bind(selectedSession)
                        },
                    )
                } else {
                    Text(
                        if (terminalState.ready) "Create a terminal to start /bin/bash -l"
                        else terminalState.availability,
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("ESC", "TAB", "↑", "↓", "←", "→", "^C", "^D", "^Z", "|", "~", "/", "-").forEach { key ->
                AssistChip(
                    onClick = { VesselTerminalManager.extraKey(key) },
                    enabled = selectedTab?.running == true,
                    label = { Text(key, fontFamily = FontFamily.Monospace) },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HostTerminalPanel(
    context: Context,
    output: String,
    busy: Boolean,
    command: String,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        OutlinedButton(
            onClick = { copy(context, "Host terminal output", output) },
            enabled = output.isNotBlank(),
        ) { Text("Copy") }
        OutlinedButton(
            onClick = { VesselHostDebug.clear() },
            enabled = output.isNotBlank(),
        ) { Text("Clear") }
        AssistChip(onClick = { VesselHostDebug.runHostInfo() }, label = { Text("Host info") })
        AssistChip(onClick = { VesselHostDebug.runGpuLinkerCheck() }, label = { Text("GPU linker") })
    }

    Surface(
        Modifier.weight(1f).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xff050706),
    ) {
        SelectionContainer {
            Text(
                output.ifBlank { "Android host shell runs as Vessel's app UID." },
                Modifier.padding(14.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            label = { Text("Host command") },
            placeholder = { Text("echo \$VESSEL_LIBDIR") },
        )
        Button(onClick = onRun, enabled = !busy && command.isNotBlank()) {
            Text(if (busy) "Running" else "Run")
        }
    }
}

@Composable
private fun TerminalMessage(message: String, error: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(10.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TerminalStatePill(label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun countMatches(text: String, query: String): Int {
    if (text.isEmpty() || query.isBlank()) return 0
    var count = 0
    var offset = 0
    while (offset < text.length) {
        val found = text.indexOf(query, offset, ignoreCase = true)
        if (found < 0) break
        count++
        offset = found + query.length.coerceAtLeast(1)
    }
    return count
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
    toast(context, label + " copied")
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
