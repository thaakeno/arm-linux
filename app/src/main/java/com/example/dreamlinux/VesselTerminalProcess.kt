package com.example.dreamlinux

import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal data class VesselProcessIdentity(
    val stat: VesselProcStat,
    val uid: Int,
) {
    val pid: Int get() = stat.pid
    val session: Int get() = stat.session
    val exited: Boolean get() = stat.exited

    fun sameProcess(other: VesselProcessIdentity?): Boolean =
        other != null &&
            pid == other.pid &&
            uid == other.uid &&
            stat.startedAtTicks == other.stat.startedAtTicks &&
            stat.session == other.stat.session

    fun ownsPtySession(appPid: Int): Boolean =
        uid == Process.myUid() &&
            stat.parent == appPid &&
            stat.processGroup == pid &&
            stat.session == pid
}

/**
 * Reaps exactly one PTY session.
 *
 * The Vessel PTY child calls setsid() before exec and sends its own stat record
 * to the parent before it is allowed to exec. Every later signal revalidates
 * UID, session and /proc starttime so PID reuse cannot redirect cleanup.
 */
internal object VesselTerminalProcessCloser {
    fun captureLeader(pid: Int, birthStat: String): VesselProcessIdentity {
        val stat = VesselProcStatParser.parse(birthStat, pid)
            ?: throw IOException("PTY child returned an invalid birth identity")
        val identity = VesselProcessIdentity(stat, Process.myUid())
        check(identity.ownsPtySession(Process.myPid())) {
            "PTY launcher did not own an isolated session: pid=" + pid +
                " ppid=" + stat.parent + " pgrp=" + stat.processGroup + " sid=" + stat.session
        }
        val live = read(pid) ?: throw IOException("PTY process disappeared during startup")
        check(identity.sameProcess(live)) { "PTY identity changed before startup completed" }
        return identity
    }

    @Throws(IOException::class, InterruptedException::class)
    fun close(expected: VesselProcessIdentity, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(250L, 15_000L)
        var stopped = false
        var leaderKilled = false

        val current = read(expected.pid)
        if (current == null || current.exited) {
            requireSessionEmpty(expected.session)
            return
        }
        if (!expected.sameProcess(current) ||
            current.stat.processGroup != expected.pid ||
            current.session != expected.pid
        ) {
            throw IOException("PTY leader identity changed; refusing to signal pid=" + expected.pid)
        }

        try {
            signalLeader(expected, OsConstants.SIGSTOP)
            stopped = true

            while (true) {
                val members = sessionMembers(expected.session)
                    .filter { it.pid != expected.pid && !it.exited }
                for (member in members) signalMember(member, OsConstants.SIGKILL)
                if (members.isEmpty()) break
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw IOException("PTY session " + expected.session + " still has live child processes")
                }
                Thread.sleep(20)
            }

            signalLeader(expected, OsConstants.SIGKILL)
            leaderKilled = true

            while (SystemClock.elapsedRealtime() < deadline) {
                val liveLeader = read(expected.pid)
                val leaderGone = liveLeader == null || !expected.sameProcess(liveLeader) || liveLeader.exited
                if (leaderGone && sessionMembers(expected.session).none { !it.exited }) return
                Thread.sleep(20)
            }
            requireSessionEmpty(expected.session)
        } finally {
            if (stopped && !leaderKilled) {
                runCatching { signalLeader(expected, OsConstants.SIGCONT) }
            }
        }
    }

    @Throws(IOException::class)
    fun requireSessionEmpty(sessionId: Int) {
        val active = sessionMembers(sessionId).firstOrNull { !it.exited }
        if (active != null) {
            throw IOException("PTY session " + sessionId + " still owns pid=" + active.pid)
        }
    }

    private fun sessionMembers(sessionId: Int): List<VesselProcessIdentity> {
        if (sessionId <= 1) return emptyList()
        val entries = File("/proc").listFiles() ?: throw IOException("Cannot enumerate /proc")
        val uid = Process.myUid()
        val result = ArrayList<VesselProcessIdentity>()
        for (entry in entries) {
            val name = entry.name
            if (name.isEmpty() || name.any { !it.isDigit() }) continue
            val pid = name.toIntOrNull() ?: continue
            val identity = read(pid) ?: continue
            if (identity.uid == uid && identity.session == sessionId) result += identity
        }
        return result
    }

    private fun read(pid: Int): VesselProcessIdentity? {
        if (pid <= 1 || pid == Process.myPid()) return null
        return try {
            val stat = VesselProcStatParser.parse(File("/proc/" + pid + "/stat").readText(), pid) ?: return null
            val uid = Os.stat("/proc/" + pid).st_uid
            VesselProcessIdentity(stat, uid)
        } catch (_: FileNotFoundException) {
            null
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) {
                null
            } else {
                throw IOException("Cannot inspect pid=" + pid, error)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun signalLeader(expected: VesselProcessIdentity, signal: Int) {
        val current = read(expected.pid) ?: return
        if (!expected.sameProcess(current)) throw IOException("PTY leader PID was reused; no signal sent")
        signalPid(expected.pid, signal)
    }

    private fun signalMember(expected: VesselProcessIdentity, signal: Int) {
        val current = read(expected.pid) ?: return
        if (!expected.sameProcess(current)) return
        if (current.uid != Process.myUid() || current.session != expected.session) return
        signalPid(expected.pid, signal)
    }

    private fun signalPid(pid: Int, signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) throw IOException("Failed to signal pid=" + pid, error)
        }
    }
}
