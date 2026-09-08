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
import org.json.JSONObject

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

    fun installed(): Boolean = runCatching {
        configFile.isFile && validatePayload(configFile, directory, throwOnMissing = false)
    }.getOrDefault(false)

    fun install() {
        val root = directory.canonicalFile
        if (root.exists()) root.deleteRecursively()
        check(root.mkdirs()) { "Cannot create ${root.path}" }
        val archive = File(root.parentFile, "images.tar.gz.part")
        onStage("debian_download")
        download(archive)
        onStage("debian_extract")
        val extracted = extract(archive, root)
        archive.delete()

        normalizeKnownPayloadNames(root)

        check(configFile.isFile) {
            "Official image archive did not contain vm_config.json. Extracted: ${extracted.joinToString(", ")}"
        }
        validatePayload(configFile, root, throwOnMissing = true)

        // Only resize writable filesystem/disk images. EFI and kernel payloads must remain byte-exact.
        if (rootPart.isFile) align4096(rootPart)
        File(root, ".dev1-installed").writeText("source=$URL\n")
        onLog("official AVF Debian image installed at ${root.path}")
    }

    private fun download(destination: File) {
        val connection = URL(URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DEV-1-LINUX/0.3")
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

    /** Mirrors AOSP Terminal's rule: directories are created, every other tar entry is copied. */
    private fun extract(archive: File, root: File): List<String> {
        val canonicalRoot = root.canonicalPath + File.separator
        val names = ArrayList<String>()
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024))
        ).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                names += entry.name
                val target = File(root, entry.name).canonicalFile
                check(target.path == root.canonicalPath || target.path.startsWith(canonicalRoot)) {
                    "Unsafe archive path: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(target.outputStream(), 256 * 1024).use { out ->
                        tar.copyTo(out, 256 * 1024)
                    }
                }
            }
        }
        onLog("Debian archive entries: ${names.joinToString(", ")}")
        return names
    }

    /**
     * Google has changed the packaging layout before. The VM config references payload basenames,
     * so accept a nested archive directory and normalize those files to our isolated payload root.
     */
    private fun normalizeKnownPayloadNames(root: File) {
        val names = listOf("vm_config.json", "root_part", "efi_part", "vmlinuz", "initrd.img", "image.raw", "bios_part")
        for (name in names) {
            val expected = File(root, name)
            if (expected.exists()) continue
            val found = root.walkTopDown()
                .firstOrNull { it != root && it.name == name && it.isFile }
                ?: continue
            found.copyTo(expected, overwrite = true)
            onLog("normalized payload ${found.relativeTo(root).path} -> $name")
        }
    }

    private fun validatePayload(config: File, root: File, throwOnMissing: Boolean): Boolean {
        val json = JSONObject(config.readText())
        val required = linkedSetOf<String>()

        fun addPath(raw: String?) {
            val value = raw?.trim().orEmpty()
            if (value.isBlank() || value == "null") return
            val expanded = value
                .replace("\$PAYLOAD_DIR/", "")
                .replace("\$PAYLOAD_DIR", "")
            if (!expanded.startsWith("/") && expanded.isNotBlank()) required += expanded
        }

        addPath(json.optString("bootloader", null))
        addPath(json.optString("kernel", null))
        addPath(json.optString("initrd", null))
        val disks = json.optJSONArray("disks")
        if (disks != null) {
            for (i in 0 until disks.length()) {
                val disk = disks.getJSONObject(i)
                addPath(disk.optString("image", null))
                val partitions = disk.optJSONArray("partitions")
                if (partitions != null) {
                    for (j in 0 until partitions.length()) {
                        addPath(partitions.getJSONObject(j).optString("path", null))
                    }
                }
            }
        }

        val missing = required.filterNot { File(root, it).isFile }
        if (missing.isEmpty()) {
            onLog("Debian payload verified from vm_config.json: ${required.joinToString(", ")}")
            return true
        }

        if (throwOnMissing) {
            val present = root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).path }
                .take(64)
                .toList()
            error("Official Debian payload is incomplete. Missing from vm_config.json: ${missing.joinToString(", ")}. Present: ${present.joinToString(", ")}")
        }
        return false
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
