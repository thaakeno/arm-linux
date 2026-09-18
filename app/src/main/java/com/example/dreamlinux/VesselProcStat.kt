package com.example.dreamlinux

/**
 * Parsed subset of /proc/<pid>/stat. Kept Android-free so PID reuse and session
 * ownership rules can be unit-tested on the host JVM.
 */
internal data class VesselProcStat(
    val pid: Int,
    val state: Char,
    val parent: Int,
    val processGroup: Int,
    val session: Int,
    val startedAtTicks: Long,
) {
    val exited: Boolean get() = state == 'Z' || state == 'X' || state == 'x'
}

internal object VesselProcStatParser {
    fun parse(raw: String, expectedPid: Int): VesselProcStat? {
        if (expectedPid <= 1 || raw.isBlank()) return null
        val open = raw.indexOf(" (")
        val close = raw.lastIndexOf(')')
        if (open <= 0 || close <= open) return null

        return try {
            if (raw.substring(0, open).trim().toInt() != expectedPid) return null
            val fields = raw.substring(close + 1).trim().split(Regex("""\s+"""))
            // fields[0] is stat field 3 (state), so starttime/field 22 is index 19.
            if (fields.size < 20 || fields[0].length != 1) return null
            VesselProcStat(
                pid = expectedPid,
                state = fields[0][0],
                parent = fields[1].toInt(),
                processGroup = fields[2].toInt(),
                session = fields[3].toInt(),
                startedAtTicks = fields[19].toLong(),
            ).takeIf { it.parent > 0 && it.startedAtTicks > 0 }
        } catch (_: RuntimeException) {
            null
        }
    }
}
