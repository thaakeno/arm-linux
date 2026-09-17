package com.example.dreamlinux

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fresh installs download a CI-built, already configured Debian + Plasma image.
 * Existing private disks are never replaced. The heavy apt/dpkg work therefore
 * happens once on GitHub's ARM64 runner instead of on the phone.
 */
class VesselBootstrapActivity : Activity() {
    companion object {
        private const val MANIFEST_URL =
            "https://github.com/thaakeno/arm-linux/releases/download/vessel-workstation-edge/Vessel-Workstation-bookworm-arm64.json"
        private const val MAX_MANIFEST_BYTES = 128 * 1024
        private const val MIN_VALID_DISK_BYTES = 512L * 1024L * 1024L
        private const val EXTRA_FREE_BYTES = 3L * 1024L * 1024L * 1024L
        private const val BUFFER_BYTES = 1024 * 1024
    }

    private data class ImageManifest(
        val revision: String,
        val url: String,
        val compressedSha256: String,
        val compressedBytes: Long,
        val imageSha256: String,
        val imageBytes: Long,
    )

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private val running = AtomicBoolean(false)
    @Volatile private var cancelled = false

    private val machineDir: File by lazy { File(filesDir, "vessel-machine").apply { mkdirs() } }
    private val disk: File by lazy { File(machineDir, "debian-docker.ext4") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (disk.isFile && disk.length() > MIN_VALID_DISK_BYTES) {
            openVessel()
            return
        }
        buildUi()
        startPreparation()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(12, 14, 16))
        }
        status = TextView(this).apply {
            text = "Preparing Vessel workstation"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        detail = TextView(this).apply {
            text = "Downloading the complete Debian + Plasma desktop once"
            textSize = 14f
            setTextColor(Color.rgb(190, 195, 202))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(22))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
            isIndeterminate = false
        }
        retry = Button(this).apply {
            text = "Retry"
            visibility = Button.GONE
            setOnClickListener { startPreparation() }
        }

        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        root.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root)
    }

    private fun startPreparation() {
        if (!running.compareAndSet(false, true)) return
        cancelled = false
        retry.visibility = Button.GONE
        updateUi(0, "Checking workstation image", "This replaces the old 1,200+ package install on your phone")
        Thread({
            try {
                prepareWorkstation()
                if (!cancelled) runOnUiThread { openVessel() }
            } catch (t: Throwable) {
                if (!cancelled) {
                    runOnUiThread {
                        status.text = "Workstation setup failed"
                        detail.text = t.message ?: t.javaClass.simpleName
                        retry.visibility = Button.VISIBLE
                    }
                }
            } finally {
                running.set(false)
            }
        }, "vessel-workstation-bootstrap").apply { isDaemon = true; start() }
    }

    private fun prepareWorkstation() {
        check(machineDir.exists() || machineDir.mkdirs()) { "Cannot create Vessel private storage" }
        if (disk.isFile && disk.length() > MIN_VALID_DISK_BYTES) return

        val manifestText = readText(MANIFEST_URL, MAX_MANIFEST_BYTES)
        val manifest = parseManifest(manifestText)
        check(!cancelled)

        val available = machineDir.usableSpace
        val minimumFree = manifest.compressedBytes + EXTRA_FREE_BYTES
        check(available <= 0L || available >= minimumFree) {
            "Vessel needs about ${formatGiB(minimumFree)} GiB free for the first workstation download"
        }

        val archive = File(machineDir, "Vessel-Workstation.ext4.zst.part")
        val tmp = File(machineDir, "debian-docker.ext4.part")
        if (archive.length() > manifest.compressedBytes) archive.delete()

        updateUi(2, "Downloading complete Plasma workstation", "Resumable download · ${formatMiB(manifest.compressedBytes)} MiB")
        downloadResumable(manifest.url, archive, manifest.compressedBytes) { done, total ->
            check(!cancelled)
            val pct = if (total > 0L) (2 + done * 72L / total).toInt().coerceIn(2, 74) else 2
            updateUi(pct, "Downloading complete Plasma workstation", "${formatMiB(done)} / ${formatMiB(total)} MiB")
        }

        updateUi(77, "Verifying download", "SHA-256 · ${manifest.revision.take(12)}")
        check(archive.length() == manifest.compressedBytes) { "Workstation download is incomplete" }
        check(sha256(archive) == manifest.compressedSha256) { "Workstation download checksum mismatch" }
        check(!cancelled)

        tmp.delete()
        updateUi(82, "Unpacking workstation", "No apt or dpkg installation is running on the phone")
        val imageSha = decompressSparse(archive, tmp, manifest.imageBytes) { done, total ->
            check(!cancelled)
            val pct = (82 + done * 16L / total.coerceAtLeast(1L)).toInt().coerceIn(82, 98)
            updateUi(pct, "Unpacking workstation", "${formatMiB(done)} / ${formatMiB(total)} MiB")
        }
        check(tmp.length() == manifest.imageBytes) { "Workstation image size mismatch" }
        check(imageSha == manifest.imageSha256) { "Workstation image checksum mismatch" }
        check(tmp.length() > MIN_VALID_DISK_BYTES) { "Workstation image extraction failed" }
        check(!cancelled)

        updateUi(99, "Finalizing workstation", "Keeping the Linux disk in fast private storage")
        if (disk.exists()) check(disk.delete()) { "Cannot replace incomplete Debian disk" }
        check(tmp.renameTo(disk)) { "Could not activate the workstation disk" }
        File(machineDir, "workstation-image.json").writeText(manifestText)
        archive.delete()
        updateUi(100, "Workstation ready", "Starting Vessel")
    }

    private fun parseManifest(text: String): ImageManifest {
        val json = JSONObject(text)
        check(json.optInt("schema") == 1) { "Unsupported workstation manifest" }
        check(json.optString("arch") == "arm64") { "Workstation architecture mismatch" }
        check(json.optString("compression") == "zstd") { "Unsupported workstation compression" }
        check(json.optBoolean("recommends")) { "Workstation image is missing Debian recommended desktop packages" }
        val url = json.getString("url")
        val parsed = URL(url)
        check(parsed.protocol == "https" && parsed.host == "github.com") { "Untrusted workstation download URL" }
        check(parsed.path.startsWith("/thaakeno/arm-linux/releases/download/vessel-workstation-edge/")) {
            "Unexpected workstation download location"
        }
        val compressedSha = json.getString("compressedSha256").lowercase()
        val imageSha = json.getString("imageSha256").lowercase()
        check(compressedSha.matches(Regex("[0-9a-f]{64}")) && imageSha.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid workstation checksum metadata"
        }
        val compressedBytes = json.getLong("compressedBytes")
        val imageBytes = json.getLong("imageBytes")
        check(compressedBytes in 1..2_000_000_000L) { "Invalid workstation archive size" }
        check(imageBytes > MIN_VALID_DISK_BYTES && imageBytes <= 10L * 1024L * 1024L * 1024L) {
            "Invalid workstation image size"
        }
        return ImageManifest(
            revision = json.getString("revision"),
            url = url,
            compressedSha256 = compressedSha,
            compressedBytes = compressedBytes,
            imageSha256 = imageSha,
            imageBytes = imageBytes,
        )
    }

    private fun readText(url: String, maxBytes: Int): String {
        val connection = open(url, null)
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Could not fetch workstation manifest (${connection.responseCode})" }
            val declared = connection.contentLengthLong
            check(declared <= 0L || declared <= maxBytes) { "Workstation manifest is too large" }
            connection.inputStream.buffered().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    check(total <= maxBytes) { "Workstation manifest exceeded size limit" }
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadResumable(url: String, target: File, expectedBytes: Long, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        var existing = target.takeIf { it.isFile }?.length() ?: 0L
        if (existing > expectedBytes) {
            target.delete()
            existing = 0L
        }
        if (existing == expectedBytes) {
            onProgress(existing, expectedBytes)
            return
        }

        var connection = open(url, existing.takeIf { it > 0L })
        var code = connection.responseCode
        if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            target.delete()
            existing = 0L
            connection = open(url, null)
            code = connection.responseCode
        }
        check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
            "Workstation download failed (HTTP $code)"
        }
        val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        var done = if (append) existing else 0L
        try {
            connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                FileOutputStream(target, append).buffered(BUFFER_BYTES).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        check(done <= expectedBytes) { "Workstation download exceeded expected size" }
                        onProgress(done, expectedBytes)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(done == expectedBytes) { "Workstation download ended early" }
    }

    private fun open(url: String, rangeStart: Long?): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Vessel/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeStart != null) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Workstation download redirect had no location")
                current = URL(current, location)
                connection.disconnect()
            } else {
                return connection
            }
        }
        error("Too many workstation download redirects")
    }

    private fun decompressSparse(source: File, target: File, expectedBytes: Long, onProgress: (Long, Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        ZstdInputStream(source.inputStream().buffered(BUFFER_BYTES)).use { input ->
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0L)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    var nonZero = false
                    var i = 0
                    while (i < n) {
                        if (buffer[i].toInt() != 0) {
                            nonZero = true
                            break
                        }
                        i++
                    }
                    if (nonZero) out.write(buffer, 0, n) else out.seek(out.filePointer + n)
                    done += n
                    check(done <= expectedBytes) { "Workstation image expanded beyond expected size" }
                    onProgress(done, expectedBytes)
                }
                out.setLength(done)
                out.fd.sync()
            }
        }
        check(done == expectedBytes) { "Workstation image ended early" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateUi(percent: Int, title: String, text: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            progress.progress = percent.coerceIn(0, 100)
            status.text = title
            detail.text = text
        }
    }

    private fun openVessel() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, VesselActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun formatMiB(bytes: Long): Long = bytes.coerceAtLeast(0L) / (1024L * 1024L)
    private fun formatGiB(bytes: Long): String = "%.1f".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
}
