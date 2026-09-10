package com.example.dreamlinux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Thin Android-side client for Vessel's proven unrooted UML + Venus runtime.
 *
 * The heavy Linux processes stay in Termux because that is the environment in
 * which the ARM64 UML kernel, umnet/passt and virglrenderer path were actually
 * verified on-device.  The APK owns lifecycle, UI and the interactive desktop.
 * There is no root/KVM/Gunyah requirement here.
 */
class TermuxUmlController(private val context: Context) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val CONTROL_PORT = 47631
        const val VNC_PORT = 5901

        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    }

    fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasRunCommandPermission(): Boolean =
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun launchDaemon() {
        check(isTermuxInstalled()) { "Termux is not installed" }
        check(hasRunCommandPermission()) {
            "Grant Vessel the 'Run commands in Termux environment' permission in Android settings"
        }
        val command = "exec python ~/venus-poc/tools/venus_poc/vessel_runtime_daemon.py >>~/vessel-daemon.log 2>&1"
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_PATH, "\$PREFIX/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, "~/")
            putExtra(EXTRA_BACKGROUND, true)
        }
        context.startService(intent)
    }

    private fun requestBlocking(request: JSONObject, timeoutMs: Int = 8_000): JSONObject {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", CONTROL_PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write(request.toString())
            writer.write("\n")
            writer.flush()
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                ?: error("Vessel runtime closed the control connection")
            return JSONObject(line)
        }
    }

    suspend fun ensureDaemon(): JSONObject = withContext(Dispatchers.IO) {
        runCatching { requestBlocking(JSONObject().put("action", "status"), 700) }.getOrNull()?.let { return@withContext it }
        launchDaemon()
        var last: Throwable? = null
        repeat(40) {
            delay(200)
            try {
                return@withContext requestBlocking(JSONObject().put("action", "status"), 700)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Vessel runtime daemon did not start", last)
    }

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        requestBlocking(JSONObject().put("action", "status"), 1_500)
    }

    suspend fun start(): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requestBlocking(JSONObject().put("action", "start").put("timeout", 80), 95_000)
    }

    suspend fun stop(): JSONObject = withContext(Dispatchers.IO) {
        runCatching { requestBlocking(JSONObject().put("action", "stop"), 12_000) }
            .getOrElse { JSONObject().put("ok", true).put("running", false).put("guestReady", false).put("desktopReady", false) }
    }

    suspend fun startDesktop(width: Int, height: Int, dpi: Int): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requestBlocking(
            JSONObject().put("action", "desktop").put("width", width).put("height", height).put("dpi", dpi),
            16 * 60 * 1_000
        )
    }

    suspend fun guest(command: String, timeoutSeconds: Int = 45): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requestBlocking(
            JSONObject().put("action", "guest").put("command", command).put("timeout", timeoutSeconds),
            (timeoutSeconds + 10) * 1_000
        )
    }
}
