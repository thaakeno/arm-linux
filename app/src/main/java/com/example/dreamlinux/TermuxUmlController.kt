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

/** Android-side client for the proven unrooted UML + Venus runtime. */
class TermuxUmlController(private val context: Context) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val CONTROL_PORT = 47631
        const val VNC_PORT = 5901
        const val REQUIRED_PROTOCOL = 14

        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
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
        val command = """
            LOG=~/vessel-daemon.log
            : > "${'$'}LOG"
            {
              echo "[vessel-launch] ${'$'}(date -Iseconds) starting"
              set -e
              cd ~/venus-poc
              echo "[vessel-launch] fetching app/vessel-final"
              git fetch origin app/vessel-final
              if [ ! -e ~/vessel-poc-runtime/.git ]; then
                echo "[vessel-launch] creating runtime worktree"
                rm -rf ~/vessel-poc-runtime
                git worktree add --detach ~/vessel-poc-runtime origin/app/vessel-final
              else
                echo "[vessel-launch] refreshing runtime worktree"
                git -C ~/vessel-poc-runtime reset --hard origin/app/vessel-final
              fi

              echo "[vessel-launch] stopping stale daemon"
              OLD_PID="${'$'}(pgrep -f '(^|/)python(3)? .*vessel_runtime_daemon(_v[0-9]+)?\\.py${'$'}' | head -n1 || true)"
              if [ -n "${'$'}OLD_PID" ]; then
                kill "${'$'}OLD_PID" 2>/dev/null || true
                for _ in 1 2 3 4 5 6 7 8 9 10; do
                  kill -0 "${'$'}OLD_PID" 2>/dev/null || break
                  sleep 0.1
                done
                kill -9 "${'$'}OLD_PID" 2>/dev/null || true
              fi

              export VESSEL_POC_DIR=~/vessel-poc-runtime
              echo "[vessel-launch] exec runtime daemon protocol 14"
              exec python ~/vessel-poc-runtime/tools/venus_poc/vessel_runtime_daemon_v14.py
            } >> "${'$'}LOG" 2>&1
        """.trimIndent()
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
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
        val existing = runCatching {
            requestBlocking(JSONObject().put("action", "status"), 900)
        }.getOrNull()
        if (existing != null && existing.optInt("protocolVersion", 0) >= REQUIRED_PROTOCOL) {
            return@withContext existing
        }

        launchDaemon()
        var last: Throwable? = null
        repeat(100) {
            delay(200)
            try {
                val status = requestBlocking(JSONObject().put("action", "status"), 900)
                if (status.optInt("protocolVersion", 0) >= REQUIRED_PROTOCOL) {
                    return@withContext status
                }
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException(
            "Vessel runtime daemon did not start. In Termux run: cat ~/vessel-daemon.log",
            last
        )
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
            .getOrElse {
                JSONObject().put("ok", true).put("running", false)
                    .put("guestReady", false).put("desktopReady", false)
            }
    }

    suspend fun startDesktop(width: Int, height: Int, dpi: Int): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requestBlocking(
            JSONObject().put("action", "desktop")
                .put("width", width).put("height", height).put("dpi", dpi),
            22 * 60 * 1_000
        )
    }

    suspend fun guest(command: String, timeoutSeconds: Int = 45): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requestBlocking(
            JSONObject().put("action", "guest")
                .put("command", command).put("timeout", timeoutSeconds),
            (timeoutSeconds + 10) * 1_000
        )
    }
}
