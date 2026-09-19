package com.example.dreamlinux

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Second-generation first-run workstation bootstrap.
 *
 * The complete Debian/KDE workstation is assembled in CI. GitHub publishes the
 * compressed filesystem as independently retryable release chunks and an atomic
 * schema-2 manifest. Android downloads/resumes each chunk, verifies every chunk,
 * then streams the concatenated compressed bytes directly into the final sparse
 * ext4 image. No apt/dpkg workload runs on the phone and no giant temporary
 * combined archive is created.
 */
class VesselBootstrapActivityV2 : ComponentActivity() {
    companion object {
        private const val MANIFEST_URL =
            "https://github.com/thaakeno/arm-linux/releases/download/vessel-workstation-v2-edge/Vessel-Workstation-bookworm-arm64.json"
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MIN_VALID_DISK_BYTES = 512L * 1024L * 1024L
        private const val EXTRA_FREE_BYTES = 512L * 1024L * 1024L
        private const val MAX_COMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_CHUNK_BYTES = 512L * 1024L * 1024L
        private const val MAX_CHUNKS = 32
        private const val BUFFER_BYTES = 1024 * 1024
        private const val PARALLEL_DOWNLOADS = 6
        private const val PROGRESS_UI_INTERVAL_MS = 250L
        private const val SPEED_SAMPLE_INTERVAL_MS = 500L
    }

    private data class ImageChunk(
        val index: Int,
        val name: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
    )

    private data class ImageManifest(
        val revision: String,
        val compressedSha256: String,
        val compressedBytes: Long,
        val imageSha256: String,
        val imageBytes: Long,
        val chunks: List<ImageChunk>,
    )

    private data class BootstrapUiState(
        val checking: Boolean = true,
        val ready: Boolean = false,
        val running: Boolean = false,
        val progress: Int = 0,
        val title: String = "Checking workstation image",
        val detail: String = "Making sure the prebuilt Debian desktop is ready",
        val error: String = "",
        val archiveBytes: Long = 0L,
        val chunkCount: Int = 0,
    )

    private data class DownloadSnapshot(
        val doneBytes: Long,
        val bytesPerSecond: Double,
        val etaSeconds: Long,
        val activeStreams: Int,
    )

    private class DownloadProgressTracker(
        private val totalBytes: Long,
        initialDone: LongArray,
    ) {
        private val doneByChunk = AtomicLongArray(initialDone)
        private val activeStreams = AtomicInteger(0)
        private val lock = Any()
        private var sampleAtMs = SystemClock.elapsedRealtime()
        private var sampleBytes = initialDone.sum()
        private var smoothedBytesPerSecond = 0.0
        private var lastUiAtMs = 0L

        fun streamStarted() {
            activeStreams.incrementAndGet()
        }

        fun streamStopped() {
            activeStreams.decrementAndGet()
        }

        fun update(index: Int, doneBytes: Long, force: Boolean = false): DownloadSnapshot? {
            doneByChunk.set(index, doneBytes.coerceAtLeast(0L))
            return synchronized(lock) {
                val now = SystemClock.elapsedRealtime()
                var aggregate = 0L
                for (i in 0 until doneByChunk.length()) {
                    aggregate += doneByChunk.get(i)
                }
                aggregate = aggregate.coerceIn(0L, totalBytes)

                val sampleDeltaMs = now - sampleAtMs
                if (sampleDeltaMs >= SPEED_SAMPLE_INTERVAL_MS) {
                    val byteDelta = (aggregate - sampleBytes).coerceAtLeast(0L)
                    val instant = if (sampleDeltaMs > 0L) {
                        byteDelta * 1000.0 / sampleDeltaMs.toDouble()
                    } else {
                        0.0
                    }
                    if (instant > 0.0) {
                        smoothedBytesPerSecond = if (smoothedBytesPerSecond <= 0.0) {
                            instant
                        } else {
                            smoothedBytesPerSecond * 0.72 + instant * 0.28
                        }
                    }
                    sampleAtMs = now
                    sampleBytes = aggregate
                }

                if (!force && now - lastUiAtMs < PROGRESS_UI_INTERVAL_MS) {
                    return@synchronized null
                }
                lastUiAtMs = now
                val remaining = (totalBytes - aggregate).coerceAtLeast(0L)
                val eta = if (smoothedBytesPerSecond >= 1024.0) {
                    (remaining / smoothedBytesPerSecond).toLong().coerceAtLeast(0L)
                } else {
                    -1L
                }
                DownloadSnapshot(
                    doneBytes = aggregate,
                    bytesPerSecond = smoothedBytesPerSecond,
                    etaSeconds = eta,
                    activeStreams = activeStreams.get().coerceAtLeast(0),
                )
            }
        }
    }

    private val uiState = MutableStateFlow(BootstrapUiState())
    private val running = AtomicBoolean(false)
    @Volatile private var cancelled = false
    @Volatile private var manifestCache: ImageManifest? = null

    private val machineDir: File by lazy { File(filesDir, "vessel-machine").apply { mkdirs() } }
    private val disk: File by lazy { File(machineDir, "debian-docker.ext4") }
    private val legacyDisk: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LinuxPC/Vessel-Debian/debian-docker.ext4",
        )
    }

    private fun hasExistingLinuxDisk(): Boolean =
        (disk.isFile && disk.length() > MIN_VALID_DISK_BYTES) ||
            (legacyDisk.isFile && legacyDisk.length() > MIN_VALID_DISK_BYTES && legacyDisk.canRead())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (hasExistingLinuxDisk()) {
            openVessel()
            return
        }
        setContent { BootstrapApp() }
        refreshManifestAvailability()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun refreshManifestAvailability() {
        if (running.get()) return
        uiState.value = BootstrapUiState(
            checking = true,
            title = "Checking workstation image",
            detail = "No Linux download starts until you press Prepare workstation",
        )
        Thread({
            try {
                val manifest = parseManifest(readText(MANIFEST_URL, MAX_MANIFEST_BYTES))
                check(probeAsset(manifest.chunks.first().url)) {
                    "The workstation image is still publishing. Try again in a moment."
                }
                if (manifest.chunks.size > 1) {
                    check(probeAsset(manifest.chunks.last().url)) {
                        "The workstation image is still publishing. Try again in a moment."
                    }
                }
                manifestCache = manifest
                uiState.value = BootstrapUiState(
                    checking = false,
                    ready = true,
                    title = "Debian workstation",
                    detail = "Prebuilt Plasma is ready · ${formatMiB(manifest.compressedBytes)} MiB one-time download",
                    archiveBytes = manifest.compressedBytes,
                    chunkCount = manifest.chunks.size,
                )
            } catch (t: Throwable) {
                manifestCache = null
                uiState.value = BootstrapUiState(
                    checking = false,
                    ready = false,
                    title = "Workstation image unavailable",
                    detail = "Nothing was downloaded to your phone",
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }, "vessel-workstation-check-v2").apply { isDaemon = true; start() }
    }

    private fun startPreparation() {
        if (!running.compareAndSet(false, true)) return
        cancelled = false
        uiState.value = uiState.value.copy(
            checking = false,
            ready = false,
            running = true,
            progress = 0,
            title = "Preparing Debian workstation",
            detail = "Starting one-time workstation setup",
            error = "",
        )
        Thread({
            try {
                val manifest = manifestCache ?: parseManifest(readText(MANIFEST_URL, MAX_MANIFEST_BYTES))
                prepareWorkstation(manifest)
                if (!cancelled) runOnUiThread { openVessel() }
            } catch (t: Throwable) {
                if (!cancelled) {
                    uiState.value = uiState.value.copy(
                        checking = false,
                        ready = manifestCache != null,
                        running = false,
                        title = "Workstation setup failed",
                        detail = "Completed chunks are kept so Retry resumes instead of starting over",
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            } finally {
                running.set(false)
            }
        }, "vessel-workstation-bootstrap-v2").apply { isDaemon = true; start() }
    }

    private fun cancelPreparation() {
        cancelled = true
        uiState.value = uiState.value.copy(
            running = false,
            ready = manifestCache != null,
            title = "Setup paused",
            detail = "Downloaded chunks are kept and will resume next time",
        )
    }

    private fun prepareWorkstation(manifest: ImageManifest) {
        check(machineDir.exists() || machineDir.mkdirs()) { "Cannot create Vessel private storage" }
        if (hasExistingLinuxDisk()) return
        check(!cancelled) { "Setup cancelled" }

        val minimumFree = manifest.compressedBytes + manifest.imageBytes + EXTRA_FREE_BYTES
        val available = machineDir.usableSpace
        check(available <= 0L || available >= minimumFree) {
            "Vessel needs about ${formatGiB(minimumFree)} GiB free for first setup"
        }

        val chunkDir = File(machineDir, "workstation-chunks").apply { mkdirs() }
        val expectedNames = manifest.chunks.map { it.name }.toSet()
        chunkDir.listFiles()?.forEach { file ->
            if (file.name !in expectedNames) file.delete()
        }

        val chunkFiles = manifest.chunks.map { chunk -> File(chunkDir, chunk.name) }
        val initialDone = LongArray(manifest.chunks.size)

        manifest.chunks.forEachIndexed { position, chunk ->
            check(!cancelled) { "Setup cancelled" }
            val target = chunkFiles[position]
            if (target.length() > chunk.bytes) target.delete()

            if (target.isFile && target.length() == chunk.bytes) {
                updateUi(
                    downloadPercent(initialDone.sum(), manifest.compressedBytes),
                    "Checking workstation download",
                    "Part ${position + 1}/${manifest.chunks.size} · verifying cached data",
                )
                if (sha256(target) == chunk.sha256) {
                    initialDone[position] = chunk.bytes
                } else {
                    target.delete()
                }
            } else if (target.isFile) {
                initialDone[position] = target.length().coerceAtMost(chunk.bytes)
            }
        }

        val tracker = DownloadProgressTracker(manifest.compressedBytes, initialDone)
        val pendingChunks = manifest.chunks.filter { chunk -> initialDone[chunk.index] < chunk.bytes }

        if (pendingChunks.isNotEmpty()) {
            val workerCount = minOf(PARALLEL_DOWNLOADS, pendingChunks.size)
            updateUi(
                downloadPercent(initialDone.sum(), manifest.compressedBytes),
                "Downloading complete Plasma workstation",
                "$workerCount parallel streams · ${formatMiB(initialDone.sum())} / ${formatMiB(manifest.compressedBytes)} MiB · measuring speed…",
            )

            val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
                Thread(runnable, "vessel-workstation-download").apply { isDaemon = true }
            }
            val futures = pendingChunks.map { chunk ->
                executor.submit {
                    tracker.streamStarted()
                    try {
                        downloadAndVerifyChunk(
                            chunk = chunk,
                            target = chunkFiles[chunk.index],
                            tracker = tracker,
                            manifest = manifest,
                        )
                    } finally {
                        tracker.streamStopped()
                    }
                }
            }
            executor.shutdown()
            try {
                futures.forEach { future ->
                    try {
                        future.get()
                    } catch (t: Throwable) {
                        executor.shutdownNow()
                        throw (t.cause ?: t)
                    }
                }
            } finally {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            }
        }

        val downloadedBytes = chunkFiles.sumOf { it.length() }
        check(downloadedBytes == manifest.compressedBytes) { "Workstation download is incomplete" }
        check(!cancelled) { "Setup cancelled" }

        val tmp = File(machineDir, "debian-docker.ext4.part")
        tmp.delete()
        updateUi(78, "Unpacking workstation", "Plasma, apps and Debian packages are already configured")
        val hashes = decompressSparse(
            sources = chunkFiles,
            target = tmp,
            expectedBytes = manifest.imageBytes,
        ) { done, total ->
            check(!cancelled) { "Setup cancelled" }
            val pct = (78 + done * 20L / total.coerceAtLeast(1L)).toInt().coerceIn(78, 98)
            updateUi(pct, "Unpacking workstation", "${formatMiB(done)} / ${formatMiB(total)} MiB")
        }
        check(hashes.first == manifest.compressedSha256) { "Compressed workstation checksum mismatch" }
        check(hashes.second == manifest.imageSha256) { "Workstation image checksum mismatch" }
        check(tmp.length() == manifest.imageBytes) { "Workstation image size mismatch" }
        check(tmp.length() > MIN_VALID_DISK_BYTES) { "Workstation image extraction failed" }
        check(!cancelled) { "Setup cancelled" }

        updateUi(99, "Finalizing workstation", "Activating the verified Linux disk")
        if (disk.exists()) check(disk.delete()) { "Cannot replace incomplete Debian disk" }
        check(tmp.renameTo(disk)) { "Could not activate the workstation disk" }
        File(machineDir, "workstation-image.json").writeText(
            JSONObject()
                .put("schema", 2)
                .put("revision", manifest.revision)
                .put("compressedSha256", manifest.compressedSha256)
                .put("imageSha256", manifest.imageSha256)
                .put("chunks", manifest.chunks.size)
                .toString(2),
        )
        chunkDir.deleteRecursively()
        updateUi(100, "Workstation ready", "Opening Vessel")
    }

    private fun downloadPercent(done: Long, total: Long): Int =
        if (total <= 0L) 2 else (2 + done * 72L / total).toInt().coerceIn(2, 74)

    private fun downloadAndVerifyChunk(
        chunk: ImageChunk,
        target: File,
        tracker: DownloadProgressTracker,
        manifest: ImageManifest,
    ) {
        for (attempt in 0 until 2) {
            check(!cancelled) { "Setup cancelled" }

            if (target.length() > chunk.bytes) {
                target.delete()
                tracker.update(chunk.index, 0L, force = true)?.let { showDownloadProgress(it, manifest) }
            }

            if (target.isFile && target.length() == chunk.bytes) {
                if (sha256(target) == chunk.sha256) {
                    tracker.update(chunk.index, chunk.bytes, force = true)?.let { showDownloadProgress(it, manifest) }
                    return
                }
                target.delete()
                tracker.update(chunk.index, 0L, force = true)?.let { showDownloadProgress(it, manifest) }
            }

            downloadResumable(chunk.url, target, chunk.bytes) { done, _ ->
                check(!cancelled) { "Setup cancelled" }
                tracker.update(chunk.index, done)?.let { showDownloadProgress(it, manifest) }
            }

            if (sha256(target) == chunk.sha256) {
                tracker.update(chunk.index, chunk.bytes, force = true)?.let { showDownloadProgress(it, manifest) }
                return
            }

            target.delete()
            tracker.update(chunk.index, 0L, force = true)?.let { showDownloadProgress(it, manifest) }
            if (attempt == 0) {
                // A corrupt resumed prefix should not make the user manually restart setup.
                // Retry this one part once from byte zero while the other streams keep going.
                continue
            }
        }
        error("Workstation part ${chunk.index + 1} failed checksum twice")
    }

    private fun showDownloadProgress(snapshot: DownloadSnapshot, manifest: ImageManifest) {
        val streams = snapshot.activeStreams.coerceAtLeast(1)
        val speed = formatRate(snapshot.bytesPerSecond)
        val eta = formatEta(snapshot.etaSeconds)
        updateUi(
            downloadPercent(snapshot.doneBytes, manifest.compressedBytes),
            "Downloading complete Plasma workstation",
            "$streams parallel streams · ${formatMiB(snapshot.doneBytes)} / ${formatMiB(manifest.compressedBytes)} MiB\n$speed · ETA $eta",
        )
    }

    private fun parseManifest(text: String): ImageManifest {
        val json = JSONObject(text)
        check(json.optInt("schema") == 2) { "Workstation manifest is not the new chunked format yet" }
        check(json.optString("arch") == "arm64") { "Workstation architecture mismatch" }
        check(json.optString("compression") == "zstd") { "Unsupported workstation compression" }
        check(json.optString("transport") == "github-release-chunks-v1") { "Unsupported workstation transport" }
        check(json.optBoolean("recommends")) { "Workstation image is missing Debian recommended desktop packages" }

        val revision = json.getString("revision").lowercase()
        check(revision.matches(Regex("[0-9a-f]{40}"))) { "Invalid workstation revision" }
        val compressedSha = checkedSha(json.getString("compressedSha256"), "compressed")
        val imageSha = checkedSha(json.getString("imageSha256"), "image")
        val compressedBytes = json.getLong("compressedBytes")
        val imageBytes = json.getLong("imageBytes")
        check(compressedBytes in 1..MAX_COMPRESSED_BYTES) { "Invalid workstation archive size" }
        check(imageBytes > MIN_VALID_DISK_BYTES && imageBytes <= 10L * 1024L * 1024L * 1024L) {
            "Invalid workstation image size"
        }

        val array = json.getJSONArray("chunks")
        check(array.length() in 1..MAX_CHUNKS) { "Invalid workstation chunk count" }
        val chunks = ArrayList<ImageChunk>(array.length())
        var total = 0L
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val index = item.getInt("index")
            check(index == i) { "Workstation chunk order mismatch" }
            val name = item.getString("name")
            check(name.matches(Regex("Vessel-Workstation-bookworm-arm64-[0-9a-f]{40}\\.part-[0-9]{3}"))) {
                "Invalid workstation chunk name"
            }
            check(name.contains(revision)) { "Workstation chunk revision mismatch" }
            val bytes = item.getLong("bytes")
            check(bytes in 1..MAX_CHUNK_BYTES) { "Invalid workstation chunk size" }
            val sha = checkedSha(item.getString("sha256"), "chunk")
            val url = item.getString("url")
            validateReleaseUrl(url, name)
            total += bytes
            check(total <= compressedBytes) { "Workstation chunk total exceeds archive size" }
            chunks += ImageChunk(index, name, url, bytes, sha)
        }
        check(total == compressedBytes) { "Workstation chunk total does not match archive size" }

        return ImageManifest(
            revision = revision,
            compressedSha256 = compressedSha,
            compressedBytes = compressedBytes,
            imageSha256 = imageSha,
            imageBytes = imageBytes,
            chunks = chunks,
        )
    }

    private fun checkedSha(value: String, label: String): String {
        val sha = value.lowercase()
        check(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid $label checksum metadata" }
        return sha
    }

    private fun validateReleaseUrl(url: String, expectedName: String) {
        val parsed = URL(url)
        check(parsed.protocol == "https" && parsed.host == "github.com") { "Untrusted workstation download URL" }
        check(parsed.path == "/thaakeno/arm-linux/releases/download/vessel-workstation-v2-edge/$expectedName") {
            "Unexpected workstation download location"
        }
    }

    private fun readText(url: String, maxBytes: Int): String {
        val connection = open(url, null, "GET")
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Could not fetch workstation manifest (HTTP ${connection.responseCode})"
            }
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

    private fun probeAsset(url: String): Boolean {
        val connection = open(url, null, "HEAD")
        return try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadResumable(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
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

        var connection = open(url, existing.takeIf { it > 0L }, "GET")
        var code = connection.responseCode
        if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            target.delete()
            existing = 0L
            connection = open(url, null, "GET")
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
                        check(!cancelled) { "Setup cancelled" }
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        check(done <= expectedBytes) { "Workstation part exceeded expected size" }
                        onProgress(done, expectedBytes)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(done == expectedBytes) { "Workstation part ended early" }
    }

    private fun open(url: String, rangeStart: Long?, method: String): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
                requestMethod = method
                setRequestProperty("User-Agent", "Vessel/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeStart != null) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("Workstation download redirect had no location")
                current = URL(current, location)
                connection.disconnect()
            } else {
                return connection
            }
        }
        error("Too many workstation download redirects")
    }

    private fun decompressSparse(
        sources: List<File>,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Pair<String, String> {
        val compressedDigest = MessageDigest.getInstance("SHA-256")
        val imageDigest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val buffer = ByteArray(BUFFER_BYTES)

        val streams: List<InputStream> = sources.map { it.inputStream().buffered(BUFFER_BYTES) }
        SequenceInputStream(Collections.enumeration(streams)).use { sequence ->
            DigestInputStream(sequence, compressedDigest).use { compressed ->
                ZstdInputStream(compressed).use { input ->
                    RandomAccessFile(target, "rw").use { out ->
                        out.setLength(0L)
                        while (true) {
                            check(!cancelled) { "Setup cancelled" }
                            val n = input.read(buffer)
                            if (n < 0) break
                            imageDigest.update(buffer, 0, n)
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
            }
        }
        check(done == expectedBytes) { "Workstation image ended early" }
        return hex(compressedDigest.digest()) to hex(imageDigest.digest())
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
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun updateUi(percent: Int, title: String, detail: String) {
        uiState.value = uiState.value.copy(
            checking = false,
            ready = false,
            running = true,
            progress = percent.coerceIn(0, 100),
            title = title,
            detail = detail,
            error = "",
        )
    }

    private fun openVessel() {
        if (isFinishing || isDestroyed) return
        if (intent.getBooleanExtra("vessel.selectUmlAfterInstall", false)) {
            // Explicit user-requested recovery install/switch only. This is not
            // an automatic runtime fallback after a proroot failure.
            VesselExperimentConfig.setRuntimeBackend(this, VesselRuntimeFactory.RECOVERY_BACKEND_ID)
        }
        startActivity(Intent(this, VesselActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Composable
    private fun BootstrapApp() {
        val state by uiState.collectAsStateWithLifecycle()
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xff72F1B8),
                onPrimary = Color(0xff002E20),
                primaryContainer = Color(0xff113D30),
                secondary = Color(0xff8CB8FF),
                background = Color(0xff060807),
                surface = Color(0xff0D110F),
                surfaceVariant = Color(0xff171D1A),
                outline = Color(0xff33443D),
                error = Color(0xffFFB4AB),
                errorContainer = Color(0xff3B171A),
            ),
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = { BootstrapTopBar(state) },
                bottomBar = { BootstrapNavigation() },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { Spacer(Modifier.height(2.dp)) }
                    item { SetupCard(state) }
                    item {
                        Text(
                            "Machine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item { MachineCard(state) }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }

    @Composable
    private fun BootstrapTopBar(state: BootstrapUiState) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Laptop, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vessel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Rootless ARM64 Linux · VirtIO GPU · Native Surface · Adreno",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val label = when {
                    state.running -> "SETUP"
                    state.checking -> "CHECKING"
                    state.error.isNotBlank() -> "RETRY"
                    state.ready -> "READY"
                    else -> "SETUP"
                }
                StatusPill(label, state.running || state.ready)
            }
        }
    }

    @Composable
    private fun SetupCard(state: BootstrapUiState) {
        ElevatedCard(shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Debian workstation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                state.running -> state.title
                                state.ready -> "One-time workstation setup"
                                state.checking -> "Checking the prebuilt desktop"
                                else -> state.title
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        when {
                            state.running -> "PREPARING"
                            state.ready -> "READY"
                            state.checking -> "CHECKING"
                            else -> "OFF"
                        },
                        state.running || state.ready,
                    )
                }

                if (state.error.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            state.error,
                            Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (state.running) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.detail,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("${state.progress}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = ::cancelPreparation, modifier = Modifier.fillMaxWidth()) {
                        Text("Pause setup")
                    }
                } else {
                    Text(state.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.ready) {
                        Text(
                            "Nothing installs automatically. Press the button when you want the ${formatMiB(state.archiveBytes)} MiB workstation download to start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { if (state.ready) startPreparation() else refreshManifestAvailability() },
                        enabled = !state.checking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(when { state.checking -> "Checking…"; state.ready -> "Prepare workstation"; else -> "Check again" })
                    }
                }
            }
        }
    }

    @Composable
    private fun MachineCard(state: BootstrapUiState) {
        ElevatedCard(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Metric(Icons.Default.DesktopWindows, "Desktop", "KDE Plasma/Wayland · preinstalled")
                Metric(Icons.Default.Bolt, "Setup", "GitHub-built ARM64 image · no on-phone apt/dpkg")
                Metric(Icons.Default.Computer, "Packages", "Debian Recommends enabled · full desktop integrations")
                Metric(Icons.Default.Storage, "Disk", "Persistent sparse ext4 · fast private storage")
                Metric(
                    Icons.Default.Storage,
                    "Download",
                    if (state.archiveBytes > 0L) {
                        "${formatMiB(state.archiveBytes)} MiB · ${state.chunkCount} verified parts · parallel + resumable"
                    } else {
                        "Chunk-resumable · SHA-256 verified"
                    },
                )
            }
        }
    }

    @Composable
    private fun Metric(icon: ImageVector, title: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    private fun StatusPill(label: String, active: Boolean) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                label,
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun BootstrapNavigation() {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavItem(true, "Machine", Icons.Default.Computer, true)
            NavItem(false, "Display", Icons.Default.DesktopWindows, false)
            NavItem(false, "Apps", Icons.Default.Laptop, false)
            NavItem(false, "Terminal", Icons.Default.Terminal, false)
            NavItem(false, "System", Icons.Default.Tune, false)
        }
    }

    @Composable
    private fun RowScope.NavItem(selected: Boolean, label: String, icon: ImageVector, enabled: Boolean) {
        NavigationBarItem(
            selected = selected,
            onClick = {},
            enabled = enabled,
            icon = { Icon(icon, null) },
            label = { Text(label) },
        )
    }

    private fun formatMiB(bytes: Long): Long = bytes.coerceAtLeast(0L) / (1024L * 1024L)

    private fun formatRate(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0) return "Measuring speed…"
        val mib = bytesPerSecond / (1024.0 * 1024.0)
        return if (mib >= 1.0) {
            "%.1f MiB/s".format(java.util.Locale.US, mib)
        } else {
            "%.0f KiB/s".format(java.util.Locale.US, bytesPerSecond / 1024.0)
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds < 0L) return "calculating…"
        if (seconds < 60L) return "${seconds}s"
        val minutes = seconds / 60L
        val secs = seconds % 60L
        if (minutes < 60L) return "${minutes}m ${secs.toString().padStart(2, '0')}s"
        val hours = minutes / 60L
        val mins = minutes % 60L
        return "${hours}h ${mins.toString().padStart(2, '0')}m"
    }

    private fun formatGiB(bytes: Long): String = "%.1f".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
}
