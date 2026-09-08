package com.example.dreamlinux

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

internal class DebianImage(
    val directory: File,
    private val onProgress: (done: Long, total: Long) -> Unit,
    private val onStage: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    companion object {
        const val URL = "https://dl.google.com/android/ferrochrome/latest/aarch64/images.tar.gz"
    }

    val configFile: File get() = File(directory, "vm_config.json")
    val rootPart: File get() = File(directory, "root_part")
    val efiPart: File get() = File(directory, "efi_part")
    val kernel: File get() = File(directory, "vmlinuz")
    val initrd: File get() = File(directory, "initrd.img")

    fun installed(): Boolean = configFile.isFile && rootPart.isFile && efiPart.isFile && kernel.isFile && initrd.isFile

    fun install() {
        val root = directory.canonicalFile
        if (root.exists()) root.deleteRecursively()
        check(root.mkdirs()) { "Cannot create ${root.path}" }
        val archive = File(root.parentFile, "images.tar.gz.part")
        onStage("debian_download")
        download(archive)
        onStage("debian_extract")
        extract(archive, root)
        archive.delete()

        check(configFile.isFile) { "Official image archive did not contain vm_config.json" }
        check(rootPart.isFile) { "Official image archive did not contain root_part" }
        check(efiPart.isFile) { "Official image archive did not contain efi_part" }
        check(kernel.isFile) { "Official image archive did not contain vmlinuz" }
        check(initrd.isFile) { "Official image archive did not contain initrd.img" }

        listOf(rootPart, efiPart).forEach(::align4096)
        File(root, ".dev1-installed").writeText("source=$URL\n")
        onLog("official AVF Debian image installed at ${root.path}")
    }

    private fun download(destination: File) {
        val connection = URL(URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DEV-1-LINUX/0.2")
            connection.connect()
            check(connection.responseCode in 200..299) { "Image download HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var done = 0L
            onProgress(done, total)
            BufferedInputStream(connection.inputStream, 256 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(destination), 256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: File, root: File) {
        val canonicalRoot = root.canonicalPath + File.separator
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024))
        ).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = File(root, entry.name).canonicalFile
                check(target.path == root.canonicalPath || target.path.startsWith(canonicalRoot)) {
                    "Unsafe archive path: ${entry.name}"
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        BufferedOutputStream(target.outputStream(), 256 * 1024).use { out ->
                            tar.copyTo(out, 256 * 1024)
                        }
                    }
                    else -> onLog("skipping non-file archive entry ${entry.name}")
                }
            }
        }
    }

    private fun align4096(file: File) {
        val length = file.length()
        val remainder = length % 4096L
        if (remainder != 0L) {
            val aligned = length + (4096L - remainder)
            RandomAccessFile(file, "rw").use { it.setLength(aligned) }
            onLog("aligned ${file.name}: $length -> $aligned bytes")
        }
    }
}
