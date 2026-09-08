package com.example.dreamlinux

import java.io.OutputStream
import java.util.UUID

/** Best-effort command channel over the AVF serial console. No network tunnel or root exploit involved. */
internal class DebianConsole(
    private val input: () -> OutputStream?,
    private val log: () -> String,
    private val onLog: (String) -> Unit,
) {
    fun command(command: String, timeoutMs: Long = 120_000): Result<String> = runCatching {
        val stream = input() ?: error("AVF console input is unavailable")
        val marker = "__DEV1_${UUID.randomUUID().toString().replace("-", "").take(12)}__"
        val before = log().length

        // Google's debug Debian console accepts root. If a shell is already active, the first
        // line merely produces a harmless command error and the marker command still follows.
        write(stream, "\n")
        Thread.sleep(350)
        write(stream, "root\n")
        Thread.sleep(700)

        val quoted = shellQuote(command)
        val wrapper = "if command -v sudo >/dev/null 2>&1; then sudo -n sh -lc $quoted; else sh -lc $quoted; fi; rc=\$?; echo $marker:\$rc"
        write(stream, wrapper + "\n")
        onLog("serial command submitted marker=$marker")

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = log()
            val at = text.indexOf(marker)
            if (at >= 0) {
                val tail = text.substring(at)
                val match = Regex(Regex.escape(marker) + ":(\\d+)").find(tail)
                if (match != null) {
                    val rc = match.groupValues[1].toInt()
                    val output = text.drop(before.coerceAtMost(text.length)).takeLast(120000)
                    check(rc == 0) { "Guest command exited $rc\n$output" }
                    return@runCatching output
                }
            }
            Thread.sleep(500)
        }
        error("Timed out waiting for Debian serial command marker $marker")
    }

    private fun write(stream: OutputStream, value: String) {
        stream.write(value.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
