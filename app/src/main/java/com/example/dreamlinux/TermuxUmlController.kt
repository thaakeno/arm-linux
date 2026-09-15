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

/** Android-side controller for Vessel's rootless UML + VirtIO GPU runtime. */
class TermuxUmlController(private val context: Context) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val CONTROL_PORT = 47631
        const val VNC_PORT = -1
        const val REQUIRED_PROTOCOL = 38
        private const val REQUIRED_RUNTIME_REVISION = "v38-virtio-gpu-plasma-r1"
        private const val DISPLAY_TRANSPORT = "virtio-gpu-rgb-loopback-android-vulkan-v1"
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

    /**
     * Start only the already-installed Vessel runtime. The Android app never
     * clones, fetches, resets, cleans or otherwise mutates the user's repo.
     */
    private fun launchDaemon() {
        check(isTermuxInstalled()) { "Termux is not installed" }
        check(hasRunCommandPermission()) { "Grant Vessel the Run commands in Termux permission" }
        val command = """
            LOG=~/vessel-daemon.log
            : > "${'$'}LOG"
            {
              echo "[vessel-launch] ${'$'}(date -Iseconds) protocol 38 KDE Plasma / VirtIO GPU / VirGL / Adreno"
              set -e

              RUNTIME=~/vessel-poc-runtime
              test -d "${'$'}RUNTIME" || {
                echo "Vessel runtime missing: ${'$'}RUNTIME" >&2
                echo "Update/install the runtime manually; Vessel will never git-fetch it for you." >&2
                exit 70
              }
              test -f "${'$'}RUNTIME/tools/venus_poc/vessel_runtime_daemon_v38.py"
              test -f "${'$'}RUNTIME/tools/venus_poc/guest_command_agent_v38.py"
              test -f "${'$'}RUNTIME/tools/venus_poc/run_vessel_virtio_gpu.sh"
              test -f "${'$'}RUNTIME/tools/venus_poc/vhost_gpu_display_frontend.py"

              DAEMON_RE='^([^ ]*/)?python(3)?[[:space:]]+[^ ]*/vessel_runtime_daemon(_v[0-9]+)?\.py([[:space:]].*)?${'$'}'
              OLD_PIDS="${'$'}(pgrep -f "${'$'}DAEMON_RE" 2>/dev/null || true)"
              if [ -n "${'$'}OLD_PIDS" ]; then
                kill -TERM ${'$'}OLD_PIDS 2>/dev/null || true
                for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
                  REMAINING="${'$'}(pgrep -f "${'$'}DAEMON_RE" 2>/dev/null || true)"
                  [ -z "${'$'}REMAINING" ] && break
                  sleep 0.1
                done
                REMAINING="${'$'}(pgrep -f "${'$'}DAEMON_RE" 2>/dev/null || true)"
                [ -z "${'$'}REMAINING" ] || kill -KILL ${'$'}REMAINING 2>/dev/null || true
              fi

              export VESSEL_POC_DIR="${'$'}RUNTIME"
              export VESSEL_UML_DIR=~/venus-wsi-local
              export VESSEL_MEM_MB=8192
              export VESSEL_VCPUS=6
              exec python "${'$'}RUNTIME/tools/venus_poc/vessel_runtime_daemon_v38.py"
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

    private fun isNativeProtocol(obj: JSONObject): Boolean =
        obj.optInt("protocolVersion", 0) == REQUIRED_PROTOCOL &&
            obj.optString("runtimeRevision") == REQUIRED_RUNTIME_REVISION &&
            obj.optString("displayTransport") == DISPLAY_TRANSPORT &&
            obj.optBoolean("gpuOnly", false) &&
            !obj.optBoolean("softwareFallback", true) &&
            obj.optString("translationLayer") == "VirGL" &&
            obj.optInt("vncPort", -1) == -1

    private fun requireOk(action: String, obj: JSONObject): JSONObject {
        if (!obj.optBoolean("ok", false)) {
            val reason = obj.optString("error").ifBlank {
                obj.optString("lastError").ifBlank { "$action failed" }
            }
            throw IllegalStateException(reason)
        }
        if (!isNativeProtocol(obj)) {
            throw IllegalStateException("$action returned a stale Vessel runtime; KDE Plasma protocol 38 runtime is required")
        }
        return obj
    }

    /** Called only by explicit start/actions, never just because the app opened. */
    suspend fun ensureDaemon(): JSONObject = withContext(Dispatchers.IO) {
        val existing = runCatching {
            requestBlocking(JSONObject().put("action", "status"), 900)
        }.getOrNull()
        if (existing != null && isNativeProtocol(existing)) return@withContext existing
        if (existing != null) {
            runCatching { requestBlocking(JSONObject().put("action", "stop"), 15_000) }
            delay(250)
        }

        launchDaemon()
        var last: Throwable? = null
        repeat(240) {
            delay(250)
            try {
                val status = requestBlocking(JSONObject().put("action", "status"), 900)
                if (isNativeProtocol(status)) return@withContext status
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException(
            "Vessel KDE Plasma protocol 38 runtime did not start. Update ~/vessel-poc-runtime manually, then check ~/vessel-daemon.log",
            last,
        )
    }

    /** Passive status probe: never starts or updates anything. */
    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        val status = requestBlocking(JSONObject().put("action", "status"), 1_500)
        if (!isNativeProtocol(status)) throw IllegalStateException("stale Vessel runtime")
        status
    }

    suspend fun start(): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requireOk("Start Debian", requestBlocking(JSONObject().put("action", "start").put("timeout", 100), 115_000))
    }

    suspend fun stop(): JSONObject = withContext(Dispatchers.IO) {
        VesselWaylandPresenter.resetPresentationLatch()
        runCatching { requestBlocking(JSONObject().put("action", "stop"), 15_000) }.getOrElse {
            JSONObject()
                .put("ok", true)
                .put("protocolVersion", REQUIRED_PROTOCOL)
                .put("runtimeRevision", REQUIRED_RUNTIME_REVISION)
                .put("displayTransport", DISPLAY_TRANSPORT)
                .put("gpuOnly", true)
                .put("softwareFallback", false)
                .put("translationLayer", "VirGL")
                .put("vncPort", -1)
                .put("running", false)
                .put("guestReady", false)
                .put("desktopReady", false)
        }
    }

    suspend fun startDesktop(width: Int, height: Int, dpi: Int): JSONObject = withContext(Dispatchers.IO) {
        VesselWaylandPresenter.resetPresentationLatch()
        ensureDaemon()
        requireOk(
            "Start display",
            requestBlocking(
                JSONObject().put("action", "desktop").put("width", width).put("height", height).put("dpi", dpi),
                20 * 60 * 1_000,
            ),
        )
    }

    suspend fun startDesktopAsync(width: Int, height: Int, dpi: Int): JSONObject = withContext(Dispatchers.IO) {
        VesselWaylandPresenter.resetPresentationLatch()
        ensureDaemon()
        requireOk(
            "Start display",
            requestBlocking(
                JSONObject().put("action", "desktopAsync").put("width", width).put("height", height).put("dpi", dpi),
                8_000,
            ),
        )
    }

    suspend fun guest(command: String, timeoutSeconds: Int = 45): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requireOk(
            "Guest command",
            requestBlocking(JSONObject().put("action", "guest").put("command", command).put("timeout", timeoutSeconds), (timeoutSeconds + 10) * 1_000),
        )
    }

    suspend fun desktopAction(name: String): JSONObject = withContext(Dispatchers.IO) {
        ensureDaemon()
        requireOk(
            "Display action",
            requestBlocking(JSONObject().put("action", "desktopAction").put("name", name), 90_000),
        )
    }
}
