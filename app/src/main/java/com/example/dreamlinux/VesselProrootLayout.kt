package com.example.dreamlinux

import android.content.Context
import android.system.Os
import java.io.File

/**
 * App-private directory layout for a directory-based glibc rootfs.
 *
 * Do not reuse Vessel's UML ext4 image here. proroot translates paths against a
 * mutable directory tree and Android 10+ forbids executing the launcher from
 * writable app data, so only data lives here; executable runtime DSOs stay in
 * nativeLibraryDir.
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
    val diagnosticsDir = File(baseDir, "diagnostics")
    val diagnosticsLog = File(diagnosticsDir, "proroot.log")
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
            diagnosticsDir,
            File(guestRunDir, "user"),
            File(guestRunDir, "user/0"),
        )
        if (!dirs.all { it.isDirectory || it.mkdirs() }) return false

        chmod(prorootTmpDir, 0x1C0) // 0700
        chmod(diagnosticsDir, 0x1C0) // 0700
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

    /**
     * Bind targets should already look like a normal Linux filesystem before a
     * process starts. This avoids fixing individual applications that assume
     * /tmp, /run or /dev/shm exists.
     */
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
        )
        if (!targets.all { path ->
                val dir = File(rootfsDir, path)
                dir.isDirectory || dir.mkdirs()
            }
        ) return false

        chmod(File(rootfsDir, "tmp"), 0x3FF)
        chmod(File(rootfsDir, "dev/shm"), 0x3FF)
        chmod(File(rootfsDir, "run"), 0x1ED)
        return true
    }

    fun runtimeLibraries(): List<File> =
        VesselProrootContract.REQUIRED_LIBRARIES.map { File(nativeLibraryDir, it) }

    /**
     * Shared baseline from upstream proroot and DSHA, followed by Vessel-owned
     * volatile mounts. /tmp, /run and /dev/shm are deliberately app-private.
     */
    fun binds(includeSharedStorage: Boolean = true): List<VesselProrootBind> {
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

        // These come after /dev so the private shm mount wins over Android /dev.
        result += VesselProrootBind(guestTmpDir.absolutePath, "/tmp")
        result += VesselProrootBind(guestRunDir.absolutePath, "/run")
        result += VesselProrootBind(guestShmDir.absolutePath, "/dev/shm")

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
