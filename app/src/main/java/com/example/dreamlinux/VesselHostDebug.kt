package com.example.dreamlinux

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

data class HostDebugState(
    val output: String = "",
    val busy: Boolean = false,
)

/**
 * Android-host shell for diagnosing Vessel itself, not the Debian guest.
 * Commands run as Vessel's ordinary app UID inside the app sandbox.
 */
object VesselHostDebug {
    val state = MutableStateFlow(HostDebugState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun clear() {
        state.value = state.value.copy(output = "")
    }

    fun clearGuestTerminal() {
        val current = VmSessionService.state.value
        VmSessionService.state.value = current.copy(terminalOutput = "")
    }

    fun runHostInfo() = run(
        """printf 'Vessel host debug\n\n'; id; uname -a; printf '\nAndroid: '; getprop ro.build.version.release; printf '\nABI: '; getprop ro.product.cpu.abi; printf '\n\nNative libraries:\n'; ls -lh \"\$VESSEL_LIBDIR\"/libvessel* 2>&1; printf '\nRuntime directory:\n'; ls -lah \"\$VESSEL_RUNDIR\" 2>&1"""
    )

    fun runGpuLinkerCheck() = run(
        """printf 'Launching vhost-device-gpu --help to force Android linker resolution...\n'; \"\$VESSEL_LIBDIR/libvessel_vhost_gpu.so\" --help 2>&1"""
    )

    fun run(command: String, timeoutSeconds: Long = 30) {
        val context = appContext ?: return
        val trimmed = command.trim()
        if (trimmed.isBlank() || state.value.busy) return

        state.value = state.value.copy(
            busy = true,
            output = (state.value.output + if (state.value.output.isBlank()) "" else "\n" + "host$ $trimmed\n").takeLast(MAX_OUTPUT),
        )

        scope.launch {
            val result = runCatching { execute(context, trimmed, timeoutSeconds) }
                .getOrElse { "${it.javaClass.simpleName}: ${it.message ?: "host command failed"}\n" }
            state.value = state.value.copy(
                busy = false,
                output = (state.value.output + result).takeLast(MAX_OUTPUT),
            )
        }
    }

    private fun execute(context: Context, command: String, timeoutSeconds: Long): String {
        val libDir = context.applicationInfo.nativeLibraryDir
        val runDir = File(context.filesDir, "vessel-runtime")
        val machineDir = File(Environment.getExternalStorageDirectory(), "Download/LinuxPC/Vessel-Debian")
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", context.filesDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("PATH", "/system/bin:/system/xbin")
                    put("LD_LIBRARY_PATH", libDir)
                    put("VESSEL_LIBDIR", libDir)
                    put("VESSEL_FILESDIR", context.filesDir.absolutePath)
                    put("VESSEL_CACHEDIR", context.cacheDir.absolutePath)
                    put("VESSEL_RUNDIR", runDir.absolutePath)
                    put("VESSEL_MACHINE", machineDir.absolutePath)
                }
            }
            .start()

        val output = StringBuilder()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.append(line).append('\n')
                    if (output.length > MAX_OUTPUT * 2) output.delete(0, output.length - MAX_OUTPUT)
                }
            }
        }, "vessel-host-debug-reader").apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutSeconds.coerceIn(1, 120), TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(2_000)

        if (!finished) output.append("[timed out after ${timeoutSeconds}s]\n")
        else output.append("[exit ${process.exitValue()}]\n")
        return output.toString().takeLast(MAX_OUTPUT)
    }

    private const val MAX_OUTPUT = 120_000
}
