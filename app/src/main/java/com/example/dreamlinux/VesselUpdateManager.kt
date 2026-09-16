package com.example.dreamlinux

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Vessel's development updater has two lanes:
 *
 * 1. Runtime hot updates: tiny shell/Python bootstrap files are downloaded from
 *    the repository, SHA-256 verified, staged atomically and used on the next
 *    Linux start. No APK reinstall is required for these files.
 * 2. APK updates: a rolling GitHub release is downloaded, hash/package/version/
 *    signing certificate checked, then handed to Android's package installer.
 *
 * The hot runtime is deliberately allow-listed. Native libraries, Kotlin bytecode
 * and arbitrary app files can never be replaced by the hot-update channel.
 */
object VesselUpdateManager {
    private const val HOT_API = 1
    private const val HOT_BASE = "https://raw.githubusercontent.com/thaakeno/arm-linux/arch/vessel-self-contained/runtime-updates/edge/"
    private const val HOT_MANIFEST = HOT_BASE + "manifest.json"
    private const val EDGE_RELEASE_API = "https://api.github.com/repos/thaakeno/arm-linux/releases/tags/vessel-edge"
    private const val EDGE_METADATA_ASSET = "vessel-edge.json"
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private const val MAX_SCRIPT_BYTES = 256 * 1024
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L

    private val allowedRuntimeFiles = setOf(
        "wayland-cleanup.sh",
        "wayland-check.sh",
        "wayland-session.sh",
        "launch-wayland.py",
        "wayland-postprep.sh",
    )

    data class UpdateState(
        val busy: Boolean = false,
        val progressPercent: Int = 0,
        val message: String = "Updates ready",
        val runtimeRevision: String = "built-in",
        val canRollback: Boolean = false,
        val appUpdateAvailable: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private fun root(context: Context) = File(context.filesDir, "vessel-live-runtime")
    private fun currentDir(context: Context) = File(root(context), "current")
    private fun previousDir(context: Context) = File(root(context), "previous")
    private fun updateCache(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }
    private fun pendingApk(context: Context) = File(updateCache(context), "pending-vessel.apk")
    private fun prefs(context: Context) = context.getSharedPreferences("vessel_updates", Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        refreshLocalState(context)
        scope.launch {
            runCatching { checkRuntimeUpdate(context, automatic = true) }
        }
    }

    fun refreshLocalState(context: Context) {
        val revision = currentRuntimeRevision(context)
        mutableState.value = mutableState.value.copy(
            runtimeRevision = revision,
            canRollback = previousDir(context).isDirectory,
        )
    }

    fun currentRuntimeRevision(context: Context): String {
        val metadata = File(currentDir(context), "metadata.json")
        return runCatching { JSONObject(metadata.readText()).optString("revision", "built-in") }.getOrDefault("built-in")
    }

    fun runtimeScript(context: Context, name: String, fallback: String): String =
        runtimeScriptOrNull(context, name) ?: fallback

    fun runtimeScriptOrNull(context: Context, name: String): String? {
        if (name !in allowedRuntimeFiles) return null
        val file = File(currentDir(context), name)
        if (!file.isFile || file.length() !in 1..MAX_SCRIPT_BYTES.toLong()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    suspend fun checkRuntimeUpdate(context: Context, automatic: Boolean = false) = withContext(Dispatchers.IO) {
        val old = mutableState.value
        mutableState.value = old.copy(
            busy = !automatic,
            progressPercent = if (automatic) old.progressPercent else 5,
            message = if (automatic) old.message else "Checking runtime channel…",
        )
        try {
            val manifestText = downloadText(HOT_MANIFEST, MAX_MANIFEST_BYTES)
            val manifest = JSONObject(manifestText)
            require(manifest.optInt("hotApi", -1) == HOT_API) { "Unsupported hot-runtime API" }
            val revision = manifest.getString("revision").trim()
            require(revision.isNotBlank() && revision.length <= 96) { "Invalid runtime revision" }
            val currentRevision = currentRuntimeRevision(context)
            if (revision == currentRevision) {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    progressPercent = 100,
                    message = if (automatic) "Runtime is current" else "Runtime already up to date",
                    runtimeRevision = currentRevision,
                    canRollback = previousDir(context).isDirectory,
                )
                return@withContext
            }

            val files = manifest.getJSONArray("files")
            require(files.length() in 1..allowedRuntimeFiles.size) { "Invalid runtime file count" }
            val requested = HashSet<String>()
            val base = root(context).apply { mkdirs() }
            val staging = File(base, "staging-${System.nanoTime()}")
            check(staging.mkdirs()) { "Could not create runtime staging directory" }
            try {
                for (i in 0 until files.length()) {
                    val entry = files.getJSONObject(i)
                    val name = entry.getString("name")
                    require(name in allowedRuntimeFiles && requested.add(name)) { "Runtime file is not allowed: $name" }
                    val expected = entry.getString("sha256").lowercase(Locale.US)
                    require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $name" }
                    val target = File(staging, name)
                    val pctBase = 10 + (i * 70 / files.length())
                    if (!automatic) mutableState.value = mutableState.value.copy(progressPercent = pctBase, message = "Downloading $name…")
                    downloadFile(HOT_BASE + name, target, MAX_SCRIPT_BYTES.toLong()) { read, total ->
                        if (!automatic && total > 0L) {
                            val slice = (read * 70L / total / files.length()).toInt()
                            mutableState.value = mutableState.value.copy(progressPercent = (pctBase + slice).coerceAtMost(85))
                        }
                    }
                    check(sha256(target) == expected) { "Checksum mismatch for $name" }
                }
                File(staging, "metadata.json").writeText(
                    JSONObject()
                        .put("hotApi", HOT_API)
                        .put("revision", revision)
                        .put("installedAt", System.currentTimeMillis())
                        .toString(2),
                )
                promoteRuntime(context, staging)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }

            mutableState.value = mutableState.value.copy(
                busy = false,
                progressPercent = 100,
                message = "Runtime $revision installed · applies on next Linux start",
                runtimeRevision = revision,
                canRollback = previousDir(context).isDirectory,
            )
        } catch (t: Throwable) {
            mutableState.value = mutableState.value.copy(
                busy = false,
                progressPercent = 0,
                message = if (automatic) mutableState.value.message else "Runtime update failed: ${t.message ?: t.javaClass.simpleName}",
                runtimeRevision = currentRuntimeRevision(context),
                canRollback = previousDir(context).isDirectory,
            )
            if (!automatic) throw t
        }
    }

    suspend fun rollbackRuntime(context: Context) = withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(busy = true, message = "Rolling runtime back…")
        val current = currentDir(context)
        val previous = previousDir(context)
        require(previous.isDirectory) { "No runtime rollback is available" }
        val temp = File(root(context), "rollback-${System.nanoTime()}")
        if (current.exists()) check(current.renameTo(temp)) { "Could not stage current runtime" }
        try {
            check(previous.renameTo(current)) { "Could not restore previous runtime" }
            if (temp.exists()) check(temp.renameTo(previous)) { "Could not preserve rollback point" }
        } catch (t: Throwable) {
            if (!current.exists() && temp.exists()) temp.renameTo(current)
            throw t
        }
        refreshLocalState(context)
        mutableState.value = mutableState.value.copy(busy = false, progressPercent = 100, message = "Runtime rollback ready · restart Linux")
    }

    private fun promoteRuntime(context: Context, staging: File) {
        val base = root(context).apply { mkdirs() }
        val current = currentDir(context)
        val previous = previousDir(context)
        val old = File(base, "old-${System.nanoTime()}")
        if (previous.exists()) previous.deleteRecursively()
        if (current.exists()) check(current.renameTo(old)) { "Could not stage previous runtime" }
        try {
            check(staging.renameTo(current)) { "Could not activate runtime update" }
            if (old.exists()) check(old.renameTo(previous)) { "Could not save runtime rollback" }
        } catch (t: Throwable) {
            if (!current.exists() && old.exists()) old.renameTo(current)
            throw t
        }
    }

    suspend fun checkAndInstallAppUpdate(activity: Activity) = withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(busy = true, progressPercent = 5, message = "Checking Vessel app update…")
        try {
            val release = JSONObject(downloadText(EDGE_RELEASE_API, MAX_MANIFEST_BYTES, accept = "application/vnd.github+json"))
            val assets = release.getJSONArray("assets")
            var metadataUrl: String? = null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.optString("name")) {
                    EDGE_METADATA_ASSET -> metadataUrl = asset.optString("browser_download_url")
                    "Vessel-edge-arm64.apk" -> apkUrl = asset.optString("browser_download_url")
                }
            }
            require(!metadataUrl.isNullOrBlank() && !apkUrl.isNullOrBlank()) { "Edge release is missing updater assets" }
            val metadata = JSONObject(downloadText(metadataUrl!!, MAX_MANIFEST_BYTES))
            val versionCode = metadata.getLong("versionCode")
            val versionName = metadata.getString("versionName")
            val expectedSha = metadata.getString("sha256").lowercase(Locale.US)
            require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK checksum metadata" }
            if (versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    progressPercent = 100,
                    message = "Vessel ${BuildConfig.VERSION_NAME} is already current",
                    appUpdateAvailable = false,
                )
                return@withContext
            }
            mutableState.value = mutableState.value.copy(appUpdateAvailable = true, progressPercent = 10, message = "Downloading Vessel $versionName…")
            val part = File(updateCache(activity), "vessel-edge.apk.part")
            part.delete()
            downloadFile(apkUrl!!, part, MAX_APK_BYTES) { read, total ->
                if (total > 0L) {
                    mutableState.value = mutableState.value.copy(
                        progressPercent = (10 + read * 80L / total).toInt().coerceIn(10, 90),
                        message = "Downloading Vessel $versionName · ${(read * 100L / total).coerceIn(0, 100)}%",
                    )
                }
            }
            check(sha256(part) == expectedSha) { "Downloaded APK checksum mismatch" }
            validateApk(activity, part, versionCode)
            val target = pendingApk(activity)
            target.delete()
            check(part.renameTo(target)) { "Could not stage APK update" }
            prefs(activity).edit().putBoolean("pending_apk", true).apply()
            mutableState.value = mutableState.value.copy(busy = false, progressPercent = 100, message = "Vessel $versionName downloaded · opening Android installer")
            withContext(Dispatchers.Main) { launchPendingInstaller(activity) }
        } catch (t: Throwable) {
            mutableState.value = mutableState.value.copy(
                busy = false,
                progressPercent = 0,
                message = "App update failed: ${t.message ?: t.javaClass.simpleName}",
            )
            throw t
        }
    }

    fun resumePendingInstall(activity: Activity) {
        if (!prefs(activity).getBoolean("pending_apk", false)) return
        if (!pendingApk(activity).isFile) {
            prefs(activity).edit().remove("pending_apk").apply()
            return
        }
        launchPendingInstaller(activity)
    }

    private fun launchPendingInstaller(activity: Activity) {
        val apk = pendingApk(activity)
        if (!apk.isFile) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            mutableState.value = mutableState.value.copy(message = "Allow Vessel to install updates once, then return here")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun validateApk(context: Context, apk: File, expectedVersionCode: Long) {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Downloaded file is not a valid APK")
        check(info.packageName == context.packageName) { "APK package name does not match Vessel" }
        check(info.longVersionCode == expectedVersionCode) { "APK version metadata does not match release" }

        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val currentCerts = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val apkCerts = info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        check(currentCerts.isNotEmpty() && currentCerts == apkCerts) {
            "APK signing key changed. Install the first stable-updater APK manually once; later updates can install in place"
        }
    }

    private fun downloadText(url: String, maxBytes: Int, accept: String? = null): String {
        val connection = open(url, accept)
        return try {
            val declared = connection.contentLengthLong
            if (declared > maxBytes) error("Update metadata is too large")
            connection.inputStream.buffered().use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    check(total <= maxBytes) { "Update metadata exceeded size limit" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File, maxBytes: Long, progress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            if (total > maxBytes) error("Download is too large")
            connection.inputStream.buffered(128 * 1024).use { input ->
                FileOutputStream(target).buffered(128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        check(done <= maxBytes) { "Download exceeded size limit" }
                        output.write(buffer, 0, n)
                        progress(done, total)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String? = null): HttpURLConnection {
        require(url.startsWith("https://")) { "Updates require HTTPS" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Vessel/${BuildConfig.VERSION_NAME}")
            if (accept != null) setRequestProperty("Accept", accept)
            connect()
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                error("Update server returned HTTP $code")
            }
        }
    }

    private fun sha256(file: File): String = file.inputStream().buffered(128 * 1024).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
