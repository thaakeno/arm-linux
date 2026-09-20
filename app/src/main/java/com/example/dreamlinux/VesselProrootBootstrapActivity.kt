package com.example.dreamlinux

import android.content.Intent
import android.os.Bundle
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VesselProrootBootstrapActivity : ComponentActivity() {
    companion object {
        private const val MANIFEST_URL =
            "https://github.com/thaakeno/arm-linux/releases/download/" +
                "vessel-proroot-rootfs-v1-edge/Vessel-Proroot-trixie-arm64.json"
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_ARCHIVE_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_CHUNK_BYTES = 256L * 1024L * 1024L
        private const val MAX_CHUNKS = 48
        private const val EXTRA_FREE_BYTES = 512L * 1024L * 1024L
        private const val BUFFER_BYTES = 1024 * 1024
        private const val DOWNLOAD_THREADS = 4
    }

    private data class Chunk(
        val index: Int,
        val name: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
    )

    private data class Manifest(
        val revision: String,
        val archiveSha256: String,
        val archiveBytes: Long,
        val extractedBytes: Long,
        val entryCount: Long,
        val mesaVersion: String,
        val desktopRelease: String,
        val hardLinksFlattened: Boolean,
        val requiredPaths: List<String>,
        val chunks: List<Chunk>,
    )

    private data class UiState(
        val checking: Boolean = true,
        val running: Boolean = false,
        val available: Boolean = false,
        val progress: Int = 0,
        val title: String = "Checking Vessel runtime",
        val detail: String = "Verifying the production ARM64 Linux rootfs",
        val error: String = "",
        val archiveBytes: Long = 0,
    )

    private data class DeferredSymlink(
        val path: String,
        val target: String,
    )

    private val ui = MutableStateFlow(UiState())
    private val running = AtomicBoolean(false)
    @Volatile private var cancelled = false
    @Volatile private var manifestCache: Manifest? = null

    private val layout by lazy { VesselProrootLayout(this) }
    private val chunkDir by lazy { File(layout.baseDir, "bootstrap-chunks") }
    private val stagingRootfs by lazy { File(layout.rootfsStoreDir, "debian-arm64.staging") }
    private val backupRootfs by lazy { File(layout.rootfsStoreDir, "debian-arm64.previous") }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val selected = VesselExperimentConfig.runtimeBackend(this)
        if (selected == VesselRuntimeFactory.RECOVERY_BACKEND_ID && VesselRuntimeFactory.umlRecoveryAvailable(this)) {
            openVessel()
            return
        }
        if (selected != VesselRuntimeFactory.RECOVERY_BACKEND_ID && prorootReady()) {
            openVessel()
            return
        }

        setContent { BootstrapUi() }
        refreshAvailability()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun prorootReady(): Boolean = runCatching {
        // Bootstrap only answers one question: can the existing persistent
        // Debian rootfs be reused? KWin/Xwayland/QML drift is migrated later by
        // the runtime and must not trigger a full rootfs replacement.
        VesselRuntimeFactory.createProroot(this).installedRootfsReadiness().ready
    }.getOrDefault(false)

    private fun refreshAvailability() {
        if (running.get()) return
        ui.value = UiState(checking = true)
        Thread({
            try {
                val manifest = parseManifest(readText(MANIFEST_URL))
                check(probe(manifest.chunks.first().url)) { "Rootfs release is still publishing" }
                check(probe(manifest.chunks.last().url)) { "Rootfs release is still publishing" }
                manifestCache = manifest
                ui.value = UiState(
                    checking = false,
                    available = true,
                    title = "Vessel Linux runtime",
                    detail = "Debian 13 · Plasma Wayland · direct KGSL · verified rootfs",
                    archiveBytes = manifest.archiveBytes,
                )
            } catch (error: Throwable) {
                manifestCache = null
                ui.value = UiState(
                    checking = false,
                    available = false,
                    title = "Runtime rootfs unavailable",
                    detail = "No Linux files were changed",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }, "vessel-proroot-manifest").apply { isDaemon = true; start() }
    }

    private fun startPreparation() {
        if (!running.compareAndSet(false, true)) return
        cancelled = false
        ui.value = ui.value.copy(
            running = true,
            available = false,
            progress = 0,
            title = "Preparing Vessel Linux",
            detail = "Starting verified rootfs setup",
            error = "",
        )
        Thread({
            try {
                val manifest = manifestCache ?: parseManifest(readText(MANIFEST_URL))
                install(manifest)
                if (!cancelled) {
                    VesselExperimentConfig.setRuntimeBackend(this, VesselRuntimeFactory.ACTIVE_BACKEND_ID)
                    runOnUiThread { openVessel() }
                }
            } catch (error: Throwable) {
                if (!cancelled) {
                    ui.value = ui.value.copy(
                        checking = false,
                        running = false,
                        available = manifestCache != null,
                        title = "Runtime setup failed",
                        detail = "Verified chunks are kept for Retry",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                running.set(false)
            }
        }, "vessel-proroot-bootstrap").apply { isDaemon = true; start() }
    }

    private fun install(manifest: Manifest) {
        check(layout.prepareHostLayout()) { "Cannot prepare Vessel private storage" }
        if (prorootReady()) return
        check(!cancelled) { "Setup cancelled" }

        val requiredFree = manifest.archiveBytes + manifest.extractedBytes + EXTRA_FREE_BYTES
        val free = layout.baseDir.usableSpace
        check(free <= 0L || free >= requiredFree) {
            "Vessel needs about " + formatGiB(requiredFree) + " GiB free for setup"
        }

        chunkDir.mkdirs()
        val expectedNames = manifest.chunks.map { it.name }.toSet()
        chunkDir.listFiles()?.forEach { if (it.name !in expectedNames) it.delete() }

        val files = manifest.chunks.map { chunk -> File(chunkDir, chunk.name) }
        manifest.chunks.forEach { chunk ->
            val file = files[chunk.index]
            if (file.isFile && file.length() == chunk.bytes && sha256(file) == chunk.sha256) {
                return@forEach
            }
            if (file.exists() && file.length() > chunk.bytes) file.delete()
        }

        val pending = manifest.chunks.filter { chunk ->
            val file = files[chunk.index]
            !(file.isFile && file.length() == chunk.bytes && sha256(file) == chunk.sha256)
        }

        if (pending.isNotEmpty()) {
            val done = AtomicLong(
                manifest.chunks.sumOf { chunk ->
                    files[chunk.index].length().coerceAtMost(chunk.bytes)
                },
            )
            val pool = Executors.newFixedThreadPool(minOf(DOWNLOAD_THREADS, pending.size))
            try {
                val futures = pending.map { chunk ->
                    pool.submit {
                        downloadChunk(chunk, files[chunk.index]) { delta ->
                            val total = done.addAndGet(delta)
                            update(
                                (total * 68L / manifest.archiveBytes.coerceAtLeast(1L))
                                    .toInt().coerceIn(0, 68),
                                "Downloading Linux rootfs",
                                formatMiB(total).toString() + " / " + formatMiB(manifest.archiveBytes) + " MiB",
                            )
                        }
                    }
                }
                futures.forEach { future ->
                    try {
                        future.get()
                    } catch (error: Throwable) {
                        throw (error.cause ?: error)
                    }
                }
            } finally {
                pool.shutdownNow()
                pool.awaitTermination(2, TimeUnit.SECONDS)
            }
        }

        check(files.sumOf { it.length() } == manifest.archiveBytes) {
            "Rootfs download is incomplete"
        }
        update(70, "Verifying rootfs archive", "Checking complete SHA-256")
        check(sha256(files) == manifest.archiveSha256) { "Rootfs archive checksum mismatch" }
        check(!cancelled) { "Setup cancelled" }

        update(72, "Installing Linux rootfs", "Extracting into private staging storage")
        extractRootfs(manifest, files)

        update(97, "Validating runtime", "Checking GPU, compositor and proroot contracts")
        val readiness = VesselRuntimeFactory.createProroot(this).desktopReadiness(verifyIntegrity = true)
        check(readiness.ready) { readiness.reason }

        File(layout.baseDir, "rootfs-install.json").writeText(
            JSONObject()
                .put("schema", 1)
                .put("revision", manifest.revision)
                .put("archiveSha256", manifest.archiveSha256)
                .put("runtime", "proroot")
                .toString(2),
        )
        chunkDir.deleteRecursively()
        update(100, "Vessel ready", "Opening direct shared-kernel Linux")
    }

    private fun extractRootfs(manifest: Manifest, chunks: List<File>) {
        stagingRootfs.deleteRecursively()
        check(stagingRootfs.mkdirs()) { "Cannot create rootfs staging directory" }

        val deferred = ArrayList<DeferredSymlink>()
        var entries = 0L
        try {
            val streams = chunks.map { BufferedInputStream(FileInputStream(it), BUFFER_BYTES) }
            val sequence = SequenceInputStream(Collections.enumeration(streams))
            ZstdInputStream(sequence).use { zstd ->
                TarArchiveInputStream(BufferedInputStream(zstd, BUFFER_BYTES)).use { tar ->
                    while (true) {
                        check(!cancelled) { "Setup cancelled" }
                        val entry = tar.nextTarEntry ?: break
                        entries++
                        val relative = safeRelative(entry.name)
                        if (relative.isEmpty()) continue
                        val target = safeTarget(stagingRootfs, relative)

                        when {
                            entry.isDirectory -> {
                                check(target.isDirectory || target.mkdirs()) {
                                    "Cannot create rootfs directory: " + relative
                                }
                                chmod(target, entry.mode)
                            }
                            entry.isSymbolicLink -> {
                                deferred += DeferredSymlink(relative, entry.linkName)
                            }
                            entry.isLink -> {
                                error("Rootfs archive contains unsupported hard link: " + relative)
                            }
                            entry.isFile -> {
                                val parent = target.parentFile
                                check(parent != null && (parent.isDirectory || parent.mkdirs())) {
                                    "Cannot create rootfs parent for " + relative
                                }
                                FileOutputStream(target).buffered(BUFFER_BYTES).use { output ->
                                    tar.copyTo(output, BUFFER_BYTES)
                                }
                                chmod(target, entry.mode)
                            }
                            else -> {
                                if (!relative.startsWith("dev/")) {
                                    error("Unsupported rootfs tar entry: " + relative)
                                }
                            }
                        }

                        if (entries % 250L == 0L) {
                            val pct = (72 + entries * 22L / manifest.entryCount.coerceAtLeast(1L))
                                .toInt().coerceIn(72, 94)
                            update(
                                pct,
                                "Installing Linux rootfs",
                                entries.toString() + " / " + manifest.entryCount + " entries",
                            )
                        }
                    }
                }
            }

            check(entries == manifest.entryCount) {
                "Rootfs entry count mismatch: " + entries + " != " + manifest.entryCount
            }

            deferred.forEach { link ->
                val target = safeTarget(stagingRootfs, link.path)
                target.parentFile?.mkdirs()
                target.delete()
                check('\u0000' !in link.target) { "Symlink target contains NUL" }
                Os.symlink(link.target, target.absolutePath)
            }

            manifest.requiredPaths.forEach { relative ->
                check(safeTarget(stagingRootfs, safeRelative(relative)).exists()) {
                    "Required rootfs file missing: " + relative
                }
            }

            activateStagingRootfs()
        } catch (error: Throwable) {
            stagingRootfs.deleteRecursively()
            throw error
        }
    }

    private fun activateStagingRootfs() {
        val live = layout.rootfsDir
        backupRootfs.deleteRecursively()
        if (live.exists()) {
            check(live.renameTo(backupRootfs)) { "Could not stage previous rootfs for replacement" }
        }
        if (!stagingRootfs.renameTo(live)) {
            if (backupRootfs.exists()) backupRootfs.renameTo(live)
            error("Could not activate new rootfs")
        }
        backupRootfs.deleteRecursively()
    }

    private fun safeRelative(raw: String): String {
        check('\u0000' !in raw) { "Rootfs path contains NUL" }
        var value = raw.replace('\\', '/')
        while (value.startsWith("./")) value = value.removePrefix("./")
        check(!value.startsWith('/')) { "Absolute rootfs path rejected: " + raw }
        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        check(parts.none { it == ".." }) { "Rootfs traversal rejected: " + raw }
        return parts.joinToString("/")
    }

    private fun safeTarget(root: File, relative: String): File {
        val target = File(root, relative)
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        check(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Rootfs path escapes staging: " + relative
        }
        return target
    }

    private fun chmod(file: File, mode: Int) {
        // Preserve rwx plus setuid/setgid/sticky metadata from the verified
        // rootfs. Android may enforce nosuid, but silently deleting Linux mode
        // metadata makes package/application compatibility worse.
        val permissions = mode and 0xFFF
        if (permissions != 0) runCatching { Os.chmod(file.absolutePath, permissions) }
    }

    private fun downloadChunk(chunk: Chunk, target: File, progress: (Long) -> Unit) {
        check(chunk.url.startsWith("https://github.com/") ||
            chunk.url.startsWith("https://objects.githubusercontent.com/")) {
            "Unexpected rootfs download host"
        }

        var existing = target.length().coerceAtMost(chunk.bytes)
        if (existing == chunk.bytes && sha256(target) == chunk.sha256) return

        var connection = URL(chunk.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-")
        connection.connect()
        var response = connection.responseCode
        if (existing > 0 && response != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            target.delete()
            existing = 0
            connection = URL(chunk.url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.connect()
            response = connection.responseCode
        }
        check(response in 200..299) {
            "HTTP " + response + " while downloading " + chunk.name
        }

        FileOutputStream(target, existing > 0).use { output ->
            connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    check(!cancelled) { "Setup cancelled" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    progress(n.toLong())
                }
            }
        }
        connection.disconnect()

        check(target.length() == chunk.bytes) { "Rootfs chunk size mismatch: " + chunk.name }
        check(sha256(target) == chunk.sha256) { "Rootfs chunk checksum mismatch: " + chunk.name }
    }

    private fun parseManifest(text: String): Manifest {
        val json = JSONObject(text)
        val schema = json.getInt("schema")
        val runtime = json.getString("runtime")
        val debian = json.getString("debian")
        val arch = json.getString("arch")
        val compression = json.getString("compression")
        val transport = json.getString("transport")
        val mesaVersion = json.getString("mesaVersion")
        val desktopRelease = json.getString("desktopRelease")
        val hardLinksFlattened = json.optBoolean("hardLinksFlattened", false)
        val capabilityArray = json.optJSONArray("capabilities")
        val capabilities = buildSet {
            if (capabilityArray != null) {
                for (i in 0 until capabilityArray.length()) {
                    val capability = capabilityArray.optString(i).trim()
                    if (capability.isNotEmpty()) add(capability)
                }
            }
        }

        VesselRootfsReleaseContract.incompatibility(
            schema = schema,
            runtime = runtime,
            debian = debian,
            arch = arch,
            compression = compression,
            transport = transport,
            mesaVersion = mesaVersion,
            desktopRelease = desktopRelease,
            hardLinksFlattened = hardLinksFlattened,
            capabilities = capabilities,
        )?.let { error(it) }

        val archiveBytes = json.getLong("archiveBytes")
        check(archiveBytes in 1..MAX_ARCHIVE_BYTES)
        val archiveSha = json.getString("archiveSha256")
        check(archiveSha.matches(Regex("[0-9a-f]{64}")))

        val array = json.getJSONArray("chunks")
        check(array.length() in 1..MAX_CHUNKS)
        val chunks = ArrayList<Chunk>()
        var total = 0L
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            check(item.getInt("index") == i)
            val bytes = item.getLong("bytes")
            check(bytes in 1..MAX_CHUNK_BYTES)
            val sha = item.getString("sha256")
            check(sha.matches(Regex("[0-9a-f]{64}")))
            val name = item.getString("name")
            check(name.matches(Regex("[A-Za-z0-9._-]{1,180}")))
            val url = item.getString("url")
            total += bytes
            chunks += Chunk(i, name, url, bytes, sha)
        }
        check(total == archiveBytes)

        val required = json.getJSONArray("requiredPaths")
        val requiredPaths = (0 until required.length()).map {
            safeRelative(required.getString(it))
        }

        return Manifest(
            revision = json.getString("revision"),
            archiveSha256 = archiveSha,
            archiveBytes = archiveBytes,
            extractedBytes = json.getLong("extractedBytes").coerceAtLeast(1),
            entryCount = json.getLong("entryCount").coerceAtLeast(1),
            mesaVersion = mesaVersion,
            desktopRelease = desktopRelease,
            hardLinksFlattened = hardLinksFlattened,
            requiredPaths = requiredPaths,
            chunks = chunks,
        )
    }

    private fun readText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.connect()
        check(connection.responseCode in 200..299)
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(output.size() + n <= MAX_MANIFEST_BYTES) { "Manifest too large" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        connection.disconnect()
        return bytes.toString(Charsets.UTF_8)
    }

    private fun probe(url: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.connect()
        val ok = connection.responseCode in 200..399
        connection.disconnect()
        ok
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        files.forEach { file ->
            FileInputStream(file).buffered(BUFFER_BYTES).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(progress: Int, title: String, detail: String) {
        ui.value = ui.value.copy(
            running = true,
            progress = progress.coerceIn(0, 100),
            title = title,
            detail = detail,
        )
    }

    private fun cancel() {
        cancelled = true
        ui.value = ui.value.copy(
            running = false,
            available = manifestCache != null,
            title = "Setup paused",
            detail = "Downloaded verified chunks are kept",
        )
    }

    private fun useRecovery() {
        if (!VesselRuntimeFactory.umlRecoveryAvailable(this)) return
        VesselExperimentConfig.setRuntimeBackend(this, VesselRuntimeFactory.RECOVERY_BACKEND_ID)
        openVessel()
    }

    private fun openVessel() {
        startActivity(Intent(this, VesselActivity::class.java))
        finish()
    }

    @Composable
    private fun BootstrapUi() {
        val state by ui.collectAsStateWithLifecycle()
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xff72F1B8),
                background = Color(0xff060807),
                surface = Color(0xff0D110F),
            ),
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text("Vessel", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(state.title, style = MaterialTheme.typography.titleMedium)
                            Text(state.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.error.isNotBlank()) {
                                Text(state.error, color = MaterialTheme.colorScheme.error)
                            }
                            if (state.running) {
                                LinearProgressIndicator(
                                    progress = { state.progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(state.progress.toString() + "%")
                                OutlinedButton(onClick = ::cancel, modifier = Modifier.fillMaxWidth()) {
                                    Text("Pause setup")
                                }
                            } else {
                                if (state.archiveBytes > 0) {
                                    Text(
                                        "Download: " + formatMiB(state.archiveBytes) +
                                            " MiB · resumable · SHA-256 verified",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick = { if (state.available) startPreparation() else refreshAvailability() },
                                    enabled = !state.checking,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (state.available) "Prepare production runtime" else "Check again")
                                }
                                if (VesselRuntimeFactory.umlRecoveryAvailable(this@VesselProrootBootstrapActivity)) {
                                    OutlinedButton(
                                        onClick = ::useRecovery,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Use UML recovery")
                                    }
                                    Text(
                                        "Recovery is explicit. Vessel never silently falls back from proroot to UML.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatMiB(bytes: Long): Long =
        bytes.coerceAtLeast(0L) / (1024L * 1024L)

    private fun formatGiB(bytes: Long): String =
        "%.1f".format(
            java.util.Locale.US,
            bytes.toDouble() / (1024.0 * 1024.0 * 1024.0),
        )
}
