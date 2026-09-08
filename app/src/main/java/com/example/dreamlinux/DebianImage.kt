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

    fun installed(): Boolean = runCatching {
        configFile.isFile && validatePayload(configFile, directory, false)
    }.getOrDefault(false)

    fun install() {
        val root = directory.canonicalFile
        if (root.exists()) root.deleteRecursively()
        check(root.mkdirs()) { "Cannot create ${root.path}" }
        val archive = File(root.parentFile, "debian-images.tar.gz.part")

        onStage("debian_download")
        download(archive)
        onStage("debian_extract")
        val extracted = extract(archive, root)
        archive.delete()
        normalize(root)

        check(configFile.isFile) {
            "Official Debian archive has no vm_config.json. Entries: ${extracted.take(80).joinToString(", ")}"
        }
        validatePayload(configFile, root, true)
        if (rootPart.isFile) align4096(rootPart)
        File(root, ".dev2-debian-installed").writeText("source=$URL\n")
        onStage("debian_installed")
        onLog("[debian_install] PASS official AVF image at ${root.path}")
    }

    private fun download(destination: File) {
        destination.parentFile?.mkdirs()
        val connection = URL(URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DEV-2-LINUX/1.0")
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
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(target.outputStream(), 256 * 1024).use { tar.copyTo(it, 256 * 1024) }
                }
            }
        }
        onLog("[debian_extract] entries=${names.size}")
        return names
    }

    private fun normalize(root: File) {
        val names = listOf("vm_config.json", "root_part", "efi_part", "vmlinuz", "initrd.img", "image.raw", "bios_part")
        for (name in names) {
            val expected = File(root, name)
            if (expected.exists()) continue
            val found = root.walkTopDown().firstOrNull { it != root && it.name == name && it.isFile } ?: continue
            found.copyTo(expected, overwrite = true)
            onLog("[debian_extract] normalized ${found.relativeTo(root).path} -> $name")
        }
    }

    private fun validatePayload(config: File, root: File, throwOnMissing: Boolean): Boolean {
        val json = JSONObject(config.readText())
        val required = linkedSetOf<String>()
        fun addPath(raw: String?) {
            val value = raw?.trim().orEmpty()
            if (value.isBlank() || value == "null") return
            val expanded = value.replace("\$PAYLOAD_DIR/", "").replace("\$PAYLOAD_DIR", "")
            if (!expanded.startsWith("/")) required += expanded
        }
        addPath(json.optString("bootloader", null))
        addPath(json.optString("kernel", null))
        addPath(json.optString("initrd", null))
        json.optJSONArray("disks")?.let { disks ->
            for (i in 0 until disks.length()) {
                val disk = disks.getJSONObject(i)
                addPath(disk.optString("image", null))
                disk.optJSONArray("partitions")?.let { parts ->
                    for (j in 0 until parts.length()) addPath(parts.getJSONObject(j).optString("path", null))
                }
            }
        }
        val missing = required.filterNot { File(root, it).isFile }
        if (missing.isEmpty()) {
            onLog("[debian_verify] payload=${required.joinToString(",")}")
            return true
        }
        if (throwOnMissing) error("Official Debian payload incomplete. Missing: ${missing.joinToString(", ")}")
        return false
    }

    private fun align4096(file: File) {
        val length = file.length()
        val remainder = length % 4096L
        if (remainder != 0L) {
            val aligned = length + 4096L - remainder
            RandomAccessFile(file, "rw").use { it.setLength(aligned) }
            onLog("[debian_verify] aligned ${file.name} $length -> $aligned")
        }
    }
}
