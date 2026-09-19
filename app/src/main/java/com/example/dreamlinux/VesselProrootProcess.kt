package com.example.dreamlinux

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-process launcher for non-interactive proroot workloads.
 *
 * Android's ProcessBuilder is deliberately used instead of forking the ART
 * process from JNI. /system/bin/setsid provides one isolated session that
 * Vessel can later reap without relying on a fragile post-fork Java/native
 * runtime state. This matches the proven proroot app-process launch shape used
 * by DSHA while keeping Vessel's stricter session ownership checks.
 */
internal object VesselProrootProcessHost {
    const val SETSID = "/system/bin/setsid"
    private const val START_TOKEN = "VESSEL_START"

    private val supervisorScript =
        "IFS= read -r VESSEL_START || exit 125\n" +
            "[ \"\$VESSEL_START\" = VESSEL_START ] || exit 125\n" +
            "exec \"\$@\"\n"

    internal fun supervisorCommand(plan: VesselProrootLaunchPlan): List<String> =
        buildList {
            add(SETSID)
            add("/system/bin/sh")
            add("-c")
            add(supervisorScript)
            add("vessel-proroot-supervisor")
            addAll(plan.argv)
        }

    internal fun androidPid(process: Process): Int =
        androidPid(process.javaClass.name, process.toString())

    internal fun androidPid(type: String, description: String?): Int {
        if (type != "java.lang.UNIXProcess" &&
            type != "java.lang.ProcessImpl" &&
            type != "java.lang.ProcessManager" + "$" + "ProcessImpl"
        ) {
            return -1
        }
        if (description == null || !description.startsWith("Process[pid=")) return -1
        val end = description.indexOf(',', startIndex = 12)
        if (end < 0) return -1
        val value = description.substring(12, end).trim()
        if (!value.matches(Regex("[1-9][0-9]{0,9}"))) return -1
        return value.toIntOrNull()?.takeIf { it > 1 } ?: -1
    }

    fun spawn(
        plan: VesselProrootLaunchPlan,
        logFile: File? = null,
    ): VesselManagedProrootProcess {
        val setsid = File(SETSID)
        check(setsid.isFile && setsid.canExecute()) {
            "Android setsid helper is unavailable: " + SETSID
        }

        val builder = ProcessBuilder(supervisorCommand(plan))
            .directory(File(plan.hostWorkingDirectory))
            .redirectErrorStream(true)
        plan.applyEnvironment(builder.environment())

        val process = try {
            builder.start()
        } catch (error: Throwable) {
            throw IOException("Could not start proroot host process", error)
        }

        val pid = androidPid(process)
        if (pid <= 1) {
            runCatching { process.destroyForcibly() }
            throw IOException(
                "Could not identify Android proroot child: " +
                    process.javaClass.name + " " + process,
            )
        }

        val identity = try {
            VesselSessionProcessCloser.captureSpawnedLeader(pid, 2500)
        } catch (error: Throwable) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            throw IOException("Could not establish isolated proroot session", error)
        }

        try {
            process.outputStream.use { input ->
                input.write((START_TOKEN + "\n").toByteArray(StandardCharsets.US_ASCII))
                input.flush()
            }
        } catch (error: Throwable) {
            runCatching { VesselSessionProcessCloser.close(identity, 1500) }
            runCatching { process.destroyForcibly() }
            throw IOException("Could not release proroot startup handshake", error)
        }

        return VesselManagedProrootProcess(
            pid = pid,
            output = process.inputStream,
            identity = identity,
            logFile = logFile,
            waiter = { process.waitFor() },
        )
    }
}

internal data class VesselProcessResult(
    val exitCode: Int,
    val output: String,
)

internal class VesselManagedProrootProcess(
    val pid: Int,
    output: InputStream,
    private val identity: VesselProcessIdentity,
    private val logFile: File?,
    waiter: () -> Int,
) {
    companion object {
        private const val MAX_TAIL_CHARS = 512 * 1024
        private const val MAX_LOG_BYTES = 8L * 1024L * 1024L
    }

    private val exitCode = AtomicInteger(Int.MIN_VALUE)
    private val exitLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)
    private val tailLock = Any()
    private val tail = StringBuilder()
    private val lastReaderError = AtomicReference<String?>(null)

    init {
        logFile?.parentFile?.mkdirs()

        Thread({
            var fileOut: FileOutputStream? = null
            try {
                if (logFile != null) {
                    if (logFile.isFile && logFile.length() > MAX_LOG_BYTES) {
                        logFile.delete()
                    }
                    fileOut = FileOutputStream(logFile, true)
                }
                output.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        fileOut?.write(buffer, 0, n)
                        val text = String(buffer, 0, n, Charsets.UTF_8)
                        synchronized(tailLock) {
                            tail.append(text)
                            if (tail.length > MAX_TAIL_CHARS) {
                                tail.delete(0, tail.length - MAX_TAIL_CHARS)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                lastReaderError.set(error.message ?: error.javaClass.simpleName)
            } finally {
                runCatching { fileOut?.flush() }
                runCatching { fileOut?.close() }
                outputLatch.countDown()
            }
        }, "vessel-proroot-output-" + pid).apply {
            isDaemon = true
            start()
        }

        Thread({
            val rc = runCatching { waiter() }.getOrElse { -255 }
            exitCode.set(rc)
            exitLatch.countDown()
        }, "vessel-proroot-wait-" + pid).apply {
            isDaemon = true
            start()
        }
    }

    fun isAlive(): Boolean = exitCode.get() == Int.MIN_VALUE

    fun exitCodeOrNull(): Int? =
        exitCode.get().takeUnless { it == Int.MIN_VALUE }

    fun outputTail(): String = synchronized(tailLock) {
        buildString {
            append(tail)
            lastReaderError.get()?.let { append("\n[reader] ").append(it) }
        }
    }

    fun await(timeoutMs: Long): Int? {
        if (!exitLatch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
            return null
        }
        outputLatch.await(1000L, TimeUnit.MILLISECONDS)
        return exitCodeOrNull()
    }

    @Throws(IOException::class, InterruptedException::class)
    fun close(timeoutMs: Long = 5000) {
        if (!isAlive()) {
            exitLatch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            VesselSessionProcessCloser.requireSessionEmpty(identity.session)
            return
        }

        val started = SystemClock.elapsedRealtime()
        VesselSessionProcessCloser.close(identity, timeoutMs)
        val elapsed = SystemClock.elapsedRealtime() - started
        val remaining = (timeoutMs - elapsed).coerceAtLeast(250L)
        if (!exitLatch.await(remaining, TimeUnit.MILLISECONDS)) {
            throw IOException("Vessel session was killed but its leader was not reaped")
        }
        outputLatch.await(1000L, TimeUnit.MILLISECONDS)
        VesselSessionProcessCloser.requireSessionEmpty(identity.session)
    }
}

internal object VesselProrootProcessRunner {
    fun run(
        plan: VesselProrootLaunchPlan,
        timeoutSeconds: Int,
        logFile: File? = null,
    ): VesselProcessResult {
        val process = VesselProrootProcessHost.spawn(plan, logFile)
        val timeoutMs = timeoutSeconds.coerceIn(1, 3600) * 1000L
        val rc = process.await(timeoutMs)
        if (rc != null) {
            return VesselProcessResult(rc, process.outputTail())
        }

        val output = process.outputTail()
        runCatching { process.close(3000) }
        return VesselProcessResult(
            exitCode = 124,
            output = output + "\n[Vessel] command timed out after " + timeoutSeconds + "s",
        )
    }
}
