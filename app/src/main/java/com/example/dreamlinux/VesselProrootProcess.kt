package com.example.dreamlinux

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object VesselProrootProcessNative {
    init { System.loadLibrary("vessel_wayland_presenter") }

    private external fun nativeSpawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        pidFdOut: IntArray,
    ): String?

    private external fun nativeWait(pid: Int): Int

    fun spawn(plan: VesselProrootLaunchPlan, logFile: File? = null): VesselManagedProrootProcess {
        val environment = System.getenv().toMutableMap()
        plan.applyEnvironment(environment)
        val env = environment.entries
            .asSequence()
            .filter { it.key.isNotEmpty() && '\u0000' !in it.key && '\u0000' !in it.value }
            .sortedBy { it.key }
            .map { it.key + "=" + it.value }
            .toList()
            .toTypedArray()

        val out = IntArray(2) { -1 }
        val birth = nativeSpawn(
            plan.argv.toTypedArray(),
            env,
            plan.hostWorkingDirectory,
            out,
        ) ?: throw IOException("Could not spawn isolated proroot process")
        val pid = out[0]
        val fd = out[1]
        if (pid <= 1 || fd < 0) {
            if (fd >= 0) runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw IOException("Native proroot launcher returned an invalid process")
        }

        val identity = try {
            VesselSessionProcessCloser.captureLeader(pid, birth)
        } catch (error: Throwable) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw error
        }
        return VesselManagedProrootProcess(
            pid = pid,
            outputFd = fd,
            identity = identity,
            logFile = logFile,
            waiter = { nativeWait(pid) },
        )
    }
}

internal data class VesselProcessResult(
    val exitCode: Int,
    val output: String,
)

internal class VesselManagedProrootProcess(
    val pid: Int,
    outputFd: Int,
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
    private val outputPfd = ParcelFileDescriptor.adoptFd(outputFd)
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
                ParcelFileDescriptor.AutoCloseInputStream(outputPfd).use { input ->
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
        // EOF follows process exit; give the output reader a short bounded drain
        // so one-shot compatibility probes never lose their final success line.
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
        val process = VesselProrootProcessNative.spawn(plan, logFile)
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
