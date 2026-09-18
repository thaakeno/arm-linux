package com.example.dreamlinux

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small procfs compatibility overlay for global files Android may hide from an
 * untrusted app. Dynamic per-process proc entries remain the real host /proc.
 *
 * Real host data wins whenever Android exposes it. Fallback data describes the
 * actual Android device where a trustworthy platform API exists.
 */
class VesselProcCompat(
    context: Context,
    private val directory: File,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    val binds: Map<String, File>
        get() = linkedMapOf(
            "/proc/stat" to File(directory, "stat"),
            "/proc/meminfo" to File(directory, "meminfo"),
            "/proc/uptime" to File(directory, "uptime"),
            "/proc/loadavg" to File(directory, "loadavg"),
            "/proc/cpuinfo" to File(directory, "cpuinfo"),
        )

    fun prepare(): Boolean {
        if (!directory.isDirectory && !directory.mkdirs()) return false
        refreshAll()
        binds.values.forEach { runCatching { Os.chmod(it.absolutePath, 0x1A4) } } // 0644
        return binds.values.all { it.isFile && it.length() > 0L }
    }

    fun start() {
        if (!prepare() || !running.compareAndSet(false, true)) return
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

    private fun refreshAll() {
        mirrorOr("/proc/cpuinfo", binds.getValue("/proc/cpuinfo")) { syntheticCpuInfo() }
        refreshDynamic()
    }

    private fun refreshDynamic() {
        mirrorOr("/proc/stat", binds.getValue("/proc/stat")) { syntheticStat() }
        mirrorOr("/proc/meminfo", binds.getValue("/proc/meminfo")) { syntheticMemInfo() }
        mirrorOr("/proc/uptime", binds.getValue("/proc/uptime")) { syntheticUptime() }
        mirrorOr("/proc/loadavg", binds.getValue("/proc/loadavg")) { "0.00 0.00 0.00 1/1 1\n" }
    }

    private fun mirrorOr(source: String, target: File, fallback: () -> String) {
        val value = runCatching {
            File(source).readText().takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallback()
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
