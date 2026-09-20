package com.example.dreamlinux

import android.content.Context
import android.system.Os
import java.io.File

/**
 * App-private directory layout for a directory-based glibc rootfs.
 *
 * Mutable Linux data stays under filesDir. Executable proroot DSOs stay in
 * nativeLibraryDir because modern Android does not allow arbitrary app-data
 * executables.
 */
class VesselProrootLayout(context: Context) {
    val baseDir = File(context.filesDir, "vessel-proroot")
    val rootfsStoreDir = File(baseDir, "rootfs")
    val rootfsDir = File(rootfsStoreDir, "debian-arm64")
    val runtimeDir = File(baseDir, "runtime")
    val prorootTmpDir = File(runtimeDir, "proroot-tmp")
    val volatileDir = File(baseDir, "volatile")
    val guestTmpDir = File(volatileDir, "tmp")
    val guestRunDir = File(volatileDir, "run")
    val guestShmDir = File(volatileDir, "shm")
    val identityDir = File(volatileDir, "identity")
    val procCompatDir = File(volatileDir, "proc-compat")
    val diagnosticsDir = File(baseDir, "diagnostics")
    val diagnosticsLog = File(diagnosticsDir, "proroot.log")
    val desktopLog = File(diagnosticsDir, "desktop.log")
    val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)

    fun prepareHostLayout(): Boolean {
        val dirs = listOf(
            baseDir,
            rootfsStoreDir,
            runtimeDir,
            prorootTmpDir,
            volatileDir,
            guestTmpDir,
            guestRunDir,
            guestShmDir,
            identityDir,
            procCompatDir,
            diagnosticsDir,
            File(guestRunDir, "user"),
            File(guestRunDir, "user/0"),
        )
        if (!dirs.all { it.isDirectory || it.mkdirs() }) return false

        chmod(prorootTmpDir, 0x1C0) // 0700
        chmod(diagnosticsDir, 0x1C0) // 0700
        chmod(identityDir, 0x1C0) // 0700
        chmod(procCompatDir, 0x1C0) // 0700
        chmod(guestTmpDir, 0x3FF) // 01777
        chmod(guestShmDir, 0x3FF) // 01777
        chmod(guestRunDir, 0x1ED) // 0755
        chmod(File(guestRunDir, "user"), 0x1ED) // 0755
        chmod(File(guestRunDir, "user/0"), 0x1C0) // 0700
        return true
    }

    fun rootfsReady(): Boolean {
        if (!rootfsDir.isDirectory || !File(rootfsDir, "etc").isDirectory) return false
        return File(rootfsDir, "bin/sh").isFile || File(rootfsDir, "usr/bin/sh").isFile
    }

    fun prepareGuestMountPointsIfReady(): Boolean {
        if (!rootfsReady()) return false
        val targets = listOf(
            "tmp",
            "run",
            "dev",
            "dev/shm",
            "proc",
            "sys",
            "system",
            "apex",
            "sdcard",
            "storage",
            "storage/emulated",
            "storage/emulated/0",
            "home",
            "home/vessel",
        )
        if (!targets.all { path ->
                val dir = File(rootfsDir, path)
                dir.isDirectory || dir.mkdirs()
            }
        ) return false

        chmod(File(rootfsDir, "tmp"), 0x3FF)
        chmod(File(rootfsDir, "dev/shm"), 0x3FF)
        chmod(File(rootfsDir, "run"), 0x1ED)
        chmod(File(rootfsDir, "home/vessel"), 0x1C0)
        return true
    }

    fun prepareDesktopIdentity(uid: Int, gid: Int): Boolean {
        if (!prepareHostLayout() || !prepareGuestMountPointsIfReady()) return false
        val runtimeUser = File(guestRunDir, "user/" + uid)
        val vesselRuntime = File(runtimeUser, "vessel")
        if ((!runtimeUser.isDirectory && !runtimeUser.mkdirs()) ||
            (!vesselRuntime.isDirectory && !vesselRuntime.mkdirs())
        ) return false
        chmod(runtimeUser, 0x1C0)
        chmod(vesselRuntime, 0x1C0)

        val originalPasswd = File(rootfsDir, "etc/passwd")
            .takeIf { it.isFile }
            ?.readLines()
            .orEmpty()
            .filterNot { it.startsWith("vessel:") }
        val originalGroup = File(rootfsDir, "etc/group")
            .takeIf { it.isFile }
            ?.readLines()
            .orEmpty()
            .filterNot { it.startsWith("vessel:") }

        val passwd = File(identityDir, "passwd")
        val group = File(identityDir, "group")
        passwd.writeText(
            (originalPasswd + "vessel:x:$uid:$gid:Vessel:/home/vessel:/bin/bash")
                .joinToString("\n", postfix = "\n"),
        )
        group.writeText(
            (originalGroup + "vessel:x:$gid:")
                .joinToString("\n", postfix = "\n"),
        )
        chmod(passwd, 0x1A4) // 0644
        chmod(group, 0x1A4)
        return true
    }

    fun desktopHostSocket(uid: Int): File =
        File(guestRunDir, "user/" + uid + "/vessel/display.sock")

    fun desktopGuestSocket(uid: Int): String =
        "/run/user/" + uid + "/vessel/display.sock"

    fun runtimeLibraries(): List<File> =
        VesselProrootContract.REQUIRED_LIBRARIES.map { File(nativeLibraryDir, it) }

    /**
     * Shared baseline from upstream proroot/DSHA followed by Vessel-owned
     * volatile mounts. Later binds intentionally override earlier broad /proc
     * and /dev mounts.
     */
    fun binds(
        includeSharedStorage: Boolean = true,
        identityOverlay: Boolean = false,
        procCompat: Map<String, File> = emptyMap(),
    ): List<VesselProrootBind> {
        check(prepareHostLayout()) { "Could not prepare Vessel proroot directories" }
        val result = ArrayList<VesselProrootBind>()

        fun addExisting(host: String, guest: String = host) {
            val file = File(host)
            if (file.exists() && (file.canRead() || file.canExecute())) {
                result += VesselProrootBind(file.absolutePath, guest)
            }
        }

        addExisting("/dev")
        addExisting("/dev/urandom", "/dev/random")
        addExisting("/proc")
        addExisting("/sys")
        addExisting("/system")
        addExisting("/apex")
        addExisting("/proc/self/fd", "/dev/fd")

        // Keep /tmp inside the writable app-owned rootfs itself. Binding
        // Android app-data over /tmp looked harmless for shell mktemp, but apt's
        // GetTempFile/mkstemp path fails through this proroot bind with ENOENT.
        // The rootfs already owns a normal 01777 /tmp, which is the closest
        // thing to a real Linux filesystem and works for apt, Qt and KDE.
        result += VesselProrootBind(guestRunDir.absolutePath, "/run")
        result += VesselProrootBind(guestShmDir.absolutePath, "/dev/shm")

        if (identityOverlay) {
            val passwd = File(identityDir, "passwd")
            val group = File(identityDir, "group")
            check(passwd.isFile && group.isFile) { "Desktop identity overlay is not prepared" }
            result += VesselProrootBind(passwd.absolutePath, "/etc/passwd")
            result += VesselProrootBind(group.absolutePath, "/etc/group")
        }

        procCompat.forEach { (guest, host) ->
            check(guest.startsWith("/proc/") && host.isFile) {
                "Invalid proc compatibility bind: $guest -> $host"
            }
            result += VesselProrootBind(host.absolutePath, guest)
        }

        if (includeSharedStorage) {
            val shared = File("/storage/emulated/0")
            if (shared.isDirectory && shared.canRead()) {
                result += VesselProrootBind(shared.absolutePath, "/sdcard")
                result += VesselProrootBind(shared.absolutePath, "/storage/emulated/0")
            }
        }

        return result
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode) }
    }
}
