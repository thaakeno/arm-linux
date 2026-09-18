package com.example.dreamlinux

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compatibility overlay only for global procfs files Android actually hides
 * from Vessel's app UID. Readable files stay on the real host /proc and incur
 * zero Java refresh work.
 */
class VesselProcCompat(
    context: Context,
    private val directory: File,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    private val candidates = linkedMapOf(
        "/proc/stat" to File(directory, "stat"),
        "/proc/meminfo" to File(directory, "meminfo"),
        "/proc/uptime" to File(directory, "uptime"),
        "/proc/loadavg" to File(directory, "loadavg"),
        "/proc/cpuinfo" to File(directory, "cpuinfo"),
    )

    @Volatile
    private var activeBinds: Map<String, File> = emptyMap()

    val binds: Map<String, File>
        get() = activeBinds

    fun prepare(): Boolean {
        if (!directory.isDirectory && !directory.mkdirs()) return false

        val hidden = LinkedHashMap<String, File>()
        candidates.forEach { (guestPath, target) ->
            if (!hostProcReadable(guestPath)) {
                hidden[guestPath] = target
                writeFallback(guestPath, target)
                runCatching { Os.chmod(target.absolutePath, 0x1A4) } // 0644
            }
        }
        activeBinds = hidden.toMap()
        return activeBinds.values.all { it.isFile && it.length() > 0L }
    }

    fun start() {
        if (!prepare() || !running.compareAndSet(false, true)) return

        // cpuinfo is static for the lifetime of the session. If it is the only
        // hidden global file, no refresher thread is needed at all.
        if (activeBinds.keys.none(::isDynamic)) {
            running.set(false)
            return
        }

        worker = Thread({
            while (running.get()) {
                refreshDynamic()
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "vessel-proc-compat").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun hostProcReadable(path: String): Boolean =
        runCatching {
            File(path).inputStream().buffered().use { input ->
                val buffer = ByteArray(256)
                input.read(buffer) > 0
            }
        }.getOrDefault(false)

    private fun isDynamic(path: String): Boolean =
        path != "/proc/cpuinfo"

    private fun refreshDynamic() {
        activeBinds.forEach { (path, target) ->
            if (isDynamic(path)) writeFallback(path, target)
        }
    }

    private fun writeFallback(path: String, target: File) {
        val value = when (path) {
            "/proc/stat" -> syntheticStat()
            "/proc/meminfo" -> syntheticMemInfo()
            "/proc/uptime" -> syntheticUptime()
            "/proc/loadavg" -> "0.00 0.00 0.00 1/1 1\n"
            "/proc/cpuinfo" -> syntheticCpuInfo()
            else -> return
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            tmp.writeText(value)
            if (!tmp.renameTo(target)) {
                target.writeText(value)
                tmp.delete()
            }
        }
    }

    private fun syntheticStat(): String {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val ticks = (SystemClock.elapsedRealtime() / 10L).coerceAtLeast(1L)
        val perCpu = (ticks / cpus).coerceAtLeast(1L)
        return buildString {
            append("cpu  0 0 0 ").append(ticks).append(" 0 0 0 0 0 0\n")
            repeat(cpus) { index ->
                append("cpu").append(index).append(" 0 0 0 ")
                    .append(perCpu).append(" 0 0 0 0 0 0\n")
            }
            append("intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n")
        }
    }

    private fun syntheticMemInfo(): String {
        val info = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val totalKb = (info.totalMem / 1024L).coerceAtLeast(1L)
        val freeKb = (info.availMem / 1024L).coerceIn(1L, totalKb)
        return "MemTotal:       " + totalKb + " kB\n" +
            "MemFree:        " + freeKb + " kB\n" +
            "MemAvailable:   " + freeKb + " kB\n" +
            "Buffers:        0 kB\nCached:         0 kB\n" +
            "SwapCached:     0 kB\nSwapTotal:      0 kB\nSwapFree:       0 kB\n"
    }

    private fun syntheticUptime(): String {
        val seconds = SystemClock.elapsedRealtime() / 1000.0
        return String.format(java.util.Locale.US, "%.2f %.2f\n", seconds, seconds)
    }

    private fun syntheticCpuInfo(): String {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val model = listOfNotNull(
            Build.SOC_MODEL.takeIf { it.isNotBlank() },
            Build.HARDWARE.takeIf { it.isNotBlank() },
        ).joinToString(" / ").ifBlank { "ARM64" }
        return buildString {
            repeat(cpus) { index ->
                append("processor\t: ").append(index).append('\n')
                append("model name\t: ").append(model).append('\n')
                append("CPU architecture: 8\n\n")
            }
        }
    }
}
