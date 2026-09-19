package com.example.dreamlinux

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Crash-resilient startup breadcrumb log.
 *
 * Each write is fsynced so a SIGSEGV/SIGABRT in a later native boundary still
 * leaves the last completed stage on disk for the next app process.
 */
class VesselStartupJournal(context: Context) {
    private val file = File(
        context.filesDir,
        "vessel-proroot/diagnostics/startup-journal.log",
    )
    private val startedAt = SystemClock.elapsedRealtime()

    @Synchronized
    fun begin() {
        file.parentFile?.mkdirs()
        write(
            "BEGIN app=" + BuildConfig.VERSION_NAME +
                " commit=" + BuildConfig.GIT_COMMIT +
                " sdk=" + android.os.Build.VERSION.SDK_INT,
            append = false,
        )
    }

    @Synchronized
    fun mark(stage: String, detail: String = "") {
        val cleanStage = stage.replace('\n', ' ').replace('\r', ' ')
        val cleanDetail = detail.replace('\n', ' ').replace('\r', ' ').take(1200)
        write(
            "+" + (SystemClock.elapsedRealtime() - startedAt) + "ms " +
                cleanStage +
                if (cleanDetail.isBlank()) "" else " :: " + cleanDetail,
            append = true,
        )
    }

    @Synchronized
    fun failure(error: Throwable) {
        mark(
            "FAILED",
            (error.javaClass.simpleName + ": " + (error.message ?: "")).trim(),
        )
    }

    fun snapshot(maxChars: Int = 32_000): String =
        runCatching {
            if (!file.isFile) return@runCatching ""
            val text = file.readText()
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }.getOrDefault("")

    private fun write(line: String, append: Boolean) {
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file, append).use { output ->
                output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
        }
    }
}
