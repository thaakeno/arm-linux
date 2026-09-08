package com.example.dreamlinux

import java.io.OutputStream
import java.util.UUID

/** Command channel over AVF's captured serial console. */
internal class DebianConsole(
    private val input: () -> OutputStream?,
    private val log: () -> String,
    private val onLog: (String) -> Unit,
) {
    @Synchronized
    fun command(command: String, timeoutMs: Long = 120_000): Result<String> = runCatching {
        val stream = input() ?: error("AVF console input is unavailable")
        val marker = "__DEV1_${UUID.randomUUID().toString().replace("-", "").take(12)}__"
        val before = log().length

        // Google's AVF Debian image creates the 'droid' user with passwordless sudo. Its normal
        // Terminal path also logs in as droid, so use that account rather than assuming root login
        // is enabled on the serial tty. If a shell is already active, the login line is harmless.
        write(stream, "\n")
        Thread.sleep(500)
        val recent = log().takeLast(12000)
        if (recent.contains("login:", ignoreCase = true) || recent.contains(" login", ignoreCase = true)) {
            write(stream, "droid\n")
            onLog("serial login submitted user=droid")
            Thread.sleep(900)
        }

        val quoted = shellQuote(command)
        val wrapper = "if command -v sudo >/dev/null 2>&1; then sudo -n sh -lc $quoted; else sh -lc $quoted; fi; rc=\$?; echo $marker:\$rc"
        write(stream, wrapper + "\n")
        onLog("serial command submitted marker=$marker")

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = log()
            if (text.takeLast(12000).contains("Password:", ignoreCase = true)) {
                error("Debian serial console requested a password for droid; automatic login is unavailable")
            }
            val at = text.indexOf(marker, before.coerceAtMost(text.length))
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
            Thread.sleep(400)
        }
        error("Timed out waiting for Debian serial command marker $marker. Last console output:\n${log().takeLast(12000)}")
    }

    private fun write(stream: OutputStream, value: String) {
        stream.write(value.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
