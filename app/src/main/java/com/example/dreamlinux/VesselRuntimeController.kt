package com.example.dreamlinux

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Protocol 39: Vessel owns UML, VirtIO GPU, VirtIO input and presentation in
 * one Android UID. Input is Android -> native sender -> vhost-user
 * virtio-input -> Linux evdev/libinput. There is no guest TCP/uinput bridge,
 * VNC, RGB frame transport, Termux dependency or software-render fallback.
 */
class VesselRuntimeController(
    private val context: Context,
    private val progress: (String, Int, String) -> Unit,
) {
    companion object {
        const val PROTOCOL = 39
        const val REVISION = "v39-self-contained-dmabuf-virtio-input-r2"
        const val DISPLAY_TRANSPORT = "vhost-user-gpu-dmabuf-same-uid-v1"
        const val INPUT_TRANSPORT = "virtio-input-vhost-user-same-uid-v1"
        private const val ROOTFS_URL = "https://github.com/zalexdev/linux-um-arm64/releases/download/prebuilt-20260816/debian-docker.ext4.gz"
        private const val ROOTFS_SHA256 = "2807979f76021fadf1f76f0c827fbe1eab4df51e33bfeca0c840c5181c610be3"
        private const val GUEST_READY_BANNER = "Type 'exit' to shut the kernel down and return to Android."
        private const val VIRTIO_GPU_ID = 16
        private const val VIRTIO_INPUT_ID = 18
        private const val MIN_DISK_CAPACITY_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MIN_HOST_FREE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_GUEST_FREE_KIB = 2_000_000L
    }

    private data class InputBackendSpec(
        val label: String,
        val device: String,
        val vhostSocket: File,
        val controlSocket: File,
    )

    private val runtimeDir = File(context.filesDir, "vessel-runtime").apply { mkdirs() }
    private val gpuSocket = File(runtimeDir, "vessel-vugpu.sock")
    val displaySocket = File(runtimeDir, "vessel-vugpu.sock.display")
    private val touchVhostSocket = File(runtimeDir, "vessel-input-touch.sock")
    private val pointerVhostSocket = File(runtimeDir, "vessel-input-pointer.sock")
    private val keyboardVhostSocket = File(runtimeDir, "vessel-input-keyboard.sock")
    private val touchControlSocket = File(runtimeDir, "vessel-input-touch.ctl")
    private val pointerControlSocket = File(runtimeDir, "vessel-input-pointer.ctl")
    private val keyboardControlSocket = File(runtimeDir, "vessel-input-keyboard.ctl")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)

    val machineDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "LinuxPC/Vessel-Debian",
    )
    private val disk = File(machineDir, "debian-docker.ext4")
    private val persistentLog = File(machineDir, "vessel-runtime.log")

    val guestMemoryMb: Int by lazy {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val totalMb = (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(4096)
        ((totalMb * 3) / 10).coerceIn(2048, 4096)
    }

    private val umlBin get() = File(nativeDir, "libvessel_uml.so")
    private val stubBin get() = File(nativeDir, "libvessel_stub.so")
    private val umnetBin get() = File(nativeDir, "libvessel_umnet.so")
    private val passtBin get() = File(nativeDir, "libvessel_passt.so")
    private val gpuBin get() = File(nativeDir, "libvessel_vhost_gpu.so")
    private val inputBin get() = File(nativeDir, "libvessel_vhost_input.so")
    private val virglLib get() = File(nativeDir, "libvessel_virglrenderer.so")
    private val epoxyLib get() = File(nativeDir, "libvessel_epoxy.so")
    private val eglAngle get() = File(nativeDir, "libEGL_angle.so")
    private val glesAngle get() = File(nativeDir, "libGLESv2_angle.so")
    private val angleSelector get() = File(nativeDir, "libvessel_angle_select.so")

    private val inputSpecs: List<InputBackendSpec>
        get() = listOf(
            InputBackendSpec("touch", "touch", touchVhostSocket, touchControlSocket),
            InputBackendSpec("pointer", "pointer", pointerVhostSocket, pointerControlSocket),
            InputBackendSpec("keyboard", "keyboard", keyboardVhostSocket, keyboardControlSocket),
        )

    @Volatile private var displayWidth = 1280
    @Volatile private var displayHeight = 720
    @Volatile private var displayDpi = 120
    @Volatile private var displayRefresh = 120f

    @Volatile private var gpuProcess: Process? = null
    @Volatile private var umlProcess: Process? = null
    @Volatile private var inputProcesses: List<Process> = emptyList()
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var guestReady = false
    @Volatile private var inputReady = false
    @Volatile private var desktopReady = false
    @Volatile private var lastError = ""
    @Volatile private var startedAt = 0L
    @Volatile private var lastInputFailure = ""

    private val logLock = Any()
    private val log = StringBuilder()
    private val commandLock = Any()
    private val consoleLock = Any()
    @Volatile private var consoleWriter: BufferedWriter? = null
    @Volatile private var pendingMarker: String? = null
    @Volatile private var pendingFuture: CompletableFuture<Pair<Int, String>>? = null
    private val pendingOutput = StringBuilder()
    private val commandId = AtomicLong()
    @Volatile private var guestShellReady = CompletableFuture<Unit>()

    fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    private fun runtimeFiles(): List<File> = listOf(
        umlBin, stubBin, umnetBin, passtBin, gpuBin, inputBin,
        virglLib, epoxyLib, eglAngle, glesAngle, angleSelector,
    )

    fun hostAssetsReady(): Boolean = runtimeFiles().all { it.isFile && it.length() > 0L }

    private fun normalizeWidth(value: Int) = ((value.coerceIn(640, 3840) / 8) * 8).coerceAtLeast(640)
    private fun normalizeHeight(value: Int) = ((value.coerceIn(480, 2160) / 2) * 2).coerceAtLeast(480)

    fun configureDisplay(width: Int, height: Int, dpi: Int, refresh: Float) {
        displayWidth = normalizeWidth(width)
        displayHeight = normalizeHeight(height)
        displayDpi = dpi.coerceIn(72, 480)
        displayRefresh = refresh.coerceIn(30f, 240f)
        VesselWaylandPresenter.configure(
            displaySocket.absolutePath,
            displayWidth,
            displayHeight,
            displayDpi,
            displayRefresh,
        )
    }

    suspend fun resizeDesktop(width: Int, height: Int, dpi: Int, refresh: Float): Boolean = withContext(Dispatchers.IO) {
        configureDisplay(width, height, dpi, refresh)
        if (!running || !guestReady) return@withContext true
        runCatching { applyDisplayModeBlocking() }.getOrElse {
            append("[display] resize failed: ${it.message}\n")
            false
        }
    }

    private fun append(text: String) {
        synchronized(logLock) {
            log.append(text)
            if (log.length > 300_000) log.delete(0, log.length - 240_000)
            if (machineDir.isDirectory) {
                runCatching {
                    FileOutputStream(persistentLog, true).bufferedWriter().use { it.write(text) }
                    if (persistentLog.length() > 2_000_000L) {
                        persistentLog.writeText(persistentLog.readText().takeLast(1_000_000))
                    }
                }
            }
        }
    }

    private fun logTail(): String = synchronized(logLock) { log.takeLast(180_000).toString() }
    private fun inputBackendsAlive(): Boolean = inputProcesses.size == inputSpecs.size && inputProcesses.all { it.isAlive }

    private fun baseState(ok: Boolean = true): JSONObject = JSONObject()
        .put("ok", ok)
        .put("protocolVersion", PROTOCOL)
        .put("runtimeRevision", REVISION)
        .put("displayTransport", DISPLAY_TRANSPORT)
        .put("inputTransport", INPUT_TRANSPORT)
        .put("backend", "UML_VIRTIO_GPU_VIRTIO_INPUT_NATIVE")
        .put("renderer", "KDE Plasma/Xorg -> Mesa VirGL -> vhost-device-gpu -> virglrenderer -> ANGLE -> Adreno")
        .put("rendererMode", "virgl-opengl")
        .put("translationLayer", "VirGL")
        .put("gpuOnly", true)
        .put("softwareFallback", false)
        .put("vncPort", -1)
        .put("runtimeDir", runtimeDir.absolutePath)
        .put("machineDir", machineDir.absolutePath)
        .put("guestMemoryMb", guestMemoryMb)
        .put("running", running)
        .put("guestReady", guestReady)
        .put("desktopReady", desktopReady)
        .put("frameContentValidated", VesselWaylandPresenter.status().startsWith("presenting-dmabuf"))
        .put("inputConnected", inputReady && inputBackendsAlive())
        .put("inputSender", VesselVirtioInput.status())
        .put("displayWidth", displayWidth)
        .put("displayHeight", displayHeight)
        .put("displayDpi", displayDpi)
        .put("displayRefresh", displayRefresh.toDouble())
        .put("uptimeMs", if (running) android.os.SystemClock.elapsedRealtime() - startedAt else 0L)
        .put("logTail", logTail())
        .put("lastError", lastError)

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        val presenter = VesselWaylandPresenter.status()
        if (presenter.startsWith("presenting-dmabuf")) desktopReady = true
        if (inputReady && !inputBackendsAlive()) inputReady = false
        baseState().put("presenter", presenter)
    }

    private fun assertAssets() {
        val missing = runtimeFiles().filterNot { it.isFile && it.length() > 0L }
        check(missing.isEmpty()) { "Vessel APK is missing native runtime assets: ${missing.joinToString { it.name }}" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val b = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureDiskBackingCapacity() {
        if (disk.length() >= MIN_DISK_CAPACITY_BYTES) return
        val hostFree = machineDir.usableSpace
        if (hostFree > 0L) {
            check(hostFree >= MIN_HOST_FREE_BYTES) {
                "Vessel needs at least 2 GiB of free Android storage to finish the Debian desktop setup"
            }
        }
        val before = disk.length()
        progress("disk_capacity", 26, "Preparing expandable Debian disk")
        RandomAccessFile(disk, "rw").use { it.setLength(MIN_DISK_CAPACITY_BYTES) }
        check(disk.length() == MIN_DISK_CAPACITY_BYTES) { "Could not expand Debian disk backing file" }
        append(
            "[disk] sparse backing capacity ${before / (1024 * 1024)} MiB -> " +
                "${MIN_DISK_CAPACITY_BYTES / (1024 * 1024)} MiB; physical storage grows only as Linux writes data\n",
        )
    }

    private fun ensureDisk() {
        check(hasStorageAccess()) { "Storage access required for Download/LinuxPC" }
        check(machineDir.exists() || machineDir.mkdirs()) { "Cannot create ${machineDir.absolutePath}" }
        if (!disk.isFile || disk.length() <= 512L * 1024 * 1024) {
            val gz = File(machineDir, "debian-docker.ext4.gz.part")
            val tmp = File(machineDir, "debian-docker.ext4.part")
            gz.delete(); tmp.delete()
            progress("disk_download", 4, "Downloading Debian once to Download/LinuxPC")
            val connection = URL(ROOTFS_URL).openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Vessel/39")
            }
            connection.getInputStream().buffered(256 * 1024).use { input ->
                FileOutputStream(gz).buffered(256 * 1024).use { out ->
                    val b = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        done += n
                        progress("disk_download", (4 + (done / 4_000_000L).toInt()).coerceAtMost(24), "Downloading persistent Debian image")
                    }
                }
            }
            check(sha256(gz) == ROOTFS_SHA256) { "Debian image checksum mismatch" }
            progress("disk_extract", 25, "Preparing persistent Debian disk")
            GZIPInputStream(gz.inputStream().buffered(256 * 1024), 256 * 1024).use { input ->
                FileOutputStream(tmp).buffered(256 * 1024).use { out -> input.copyTo(out, 256 * 1024) }
            }
            check(tmp.length() > 512L * 1024 * 1024) { "Debian disk extraction failed" }
            if (disk.exists()) check(disk.delete()) { "Cannot replace incomplete Debian disk" }
            check(tmp.renameTo(disk)) { "Could not promote persistent Debian disk" }
            gz.delete()
        }
        ensureDiskBackingCapacity()
    }

    private fun startProcessLogReader(process: Process, threadName: String, prefix: String) {
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { append("$prefix$it\n") }
                }
            } catch (e: IOException) {
                if (!stopping) append("$prefix[log reader closed: ${e.message}]\n")
            }
        }, threadName).apply { isDaemon = true; start() }
    }

    private fun stopInputBackends() {
        inputProcesses.forEach { p -> runCatching { if (p.isAlive) p.destroyForcibly() } }
        inputProcesses = emptyList()
        inputReady = false
        inputSpecs.forEach { spec ->
            spec.vhostSocket.delete()
            spec.controlSocket.delete()
        }
    }

    private fun startInputBackends() {
        stopInputBackends()
        val started = mutableListOf<Process>()
        try {
            inputSpecs.forEach { spec ->
                spec.vhostSocket.delete()
                spec.controlSocket.delete()
                append("[input] starting ${spec.label} virtio-input backend\n")
                val pb = ProcessBuilder(
                    inputBin.absolutePath,
                    "--socket-path", spec.vhostSocket.absolutePath,
                    "--control-path", spec.controlSocket.absolutePath,
                    "--device", spec.device,
                ).redirectErrorStream(true)
                pb.environment()["RUST_LOG"] = "info"
                val p = pb.start()
                started += p
                startProcessLogReader(p, "vessel-input-${spec.label}-log", "[input-${spec.label}] ")

                var tries = 0
                while ((!spec.vhostSocket.exists() || !spec.controlSocket.exists()) && tries++ < 150) {
                    if (!p.isAlive) error("${spec.label} virtio-input backend exited rc=${runCatching { p.exitValue() }.getOrDefault(-1)}")
                    Thread.sleep(20)
                }
                check(spec.vhostSocket.exists() && spec.controlSocket.exists()) {
                    "${spec.label} virtio-input sockets did not become ready"
                }
            }
            inputProcesses = started.toList()
            VesselVirtioInput.configure(
                touchControlSocket.absolutePath,
                pointerControlSocket.absolutePath,
                keyboardControlSocket.absolutePath,
            )
            append("[input] native virtio-input backends ready\n")
        } catch (t: Throwable) {
            started.forEach { p -> runCatching { if (p.isAlive) p.destroyForcibly() } }
            stopInputBackends()
            throw t
        }
    }

    fun input(type: String, values: Map<String, Any>) {
        if (!running || !inputReady) return
        if (!VesselVirtioInput.send(type, values)) {
            inputReady = false
            val status = VesselVirtioInput.status()
            if (status != lastInputFailure) {
                lastInputFailure = status
                append("[input] native virtio-input send failed: $status\n")
            }
        }
    }

    private fun startGpu() {
        runCatching { gpuProcess?.destroyForcibly() }
        gpuSocket.delete()
        var presenterReady = displaySocket.exists()
        var tries = 0
        while (!presenterReady && tries++ < 100) {
            Thread.sleep(20)
            presenterReady = displaySocket.exists()
        }
        check(presenterReady) { "Native vhost-user-gpu display socket did not start: ${VesselWaylandPresenter.status()}" }
        append("[host] starting vhost-device-gpu; display=${displaySocket.absolutePath}\n")
        val pb = ProcessBuilder(
            gpuBin.absolutePath,
            "--socket-path", gpuSocket.absolutePath,
            "--gpu-mode", "virglrenderer",
            "--capset", "virgl,virgl2",
            "--use-egl", "true",
            "--use-glx", "false",
            "--use-gles", "true",
            "--use-surfaceless", "true",
        ).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        pb.environment()["LD_PRELOAD"] = angleSelector.absolutePath
        pb.environment()["VESSEL_ANGLE_PATH"] = nativeDir.absolutePath
        pb.environment()["EPOXY_USE_ANGLE"] = "1"
        pb.environment()["RUST_LOG"] = "info"
        val p = pb.start()
        gpuProcess = p
        startProcessLogReader(p, "vessel-gpu-log", "[gpu] ")
        tries = 0
        while (!gpuSocket.exists() && tries++ < 150) {
            if (!p.isAlive) error("vhost-device-gpu exited during startup rc=${runCatching { p.exitValue() }.getOrDefault(-1)}")
            Thread.sleep(40)
        }
        check(gpuSocket.exists()) { "vhost-device-gpu socket did not appear" }
        append("[host] vhost-device-gpu ready\n")
    }

    private fun startUml() {
        runCatching { umlProcess?.destroyForcibly() }
        guestShellReady = CompletableFuture()
        append("[host] starting UML with ${guestMemoryMb} MiB RAM, 6 vCPUs\n")
        val cmd = listOf(
            umnetBin.absolutePath, "--passt", passtBin.absolutePath, "--dns", "1.1.1.1", "--",
            umlBin.absolutePath,
            "mem=${guestMemoryMb}M", "ncpus=6", "seccomp=on",
            "ubd0=${disk.absolutePath}", "root=/dev/ubda", "rw", "init=/umarm-init",
            "stub_exe=${stubBin.absolutePath}",
            "virtio_uml.device=${gpuSocket.absolutePath}:$VIRTIO_GPU_ID",
            "virtio_uml.device=${touchVhostSocket.absolutePath}:$VIRTIO_INPUT_ID",
            "virtio_uml.device=${pointerVhostSocket.absolutePath}:$VIRTIO_INPUT_ID",
            "virtio_uml.device=${keyboardVhostSocket.absolutePath}:$VIRTIO_INPUT_ID",
            "panic=-1", "con=null", "con0=fd:0,fd:1", "console=tty0",
        )
        val p = ProcessBuilder(cmd).directory(machineDir).redirectErrorStream(true).start()
        umlProcess = p
        append("[host] UML launcher started\n")
        consoleWriter = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8), 32 * 1024)
        Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), 64 * 1024).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        append("$line\n")
                        if (line.contains(GUEST_READY_BANNER) && guestShellReady.complete(Unit)) {
                            append("[guest] interactive shell ready\n")
                        }
                        synchronized(consoleLock) {
                            val marker = pendingMarker
                            val trimmed = line.trim()
                            if (marker != null && trimmed.startsWith("$marker:")) {
                                val rc = trimmed.removePrefix("$marker:").toIntOrNull()
                                if (rc != null) {
                                    pendingFuture?.complete(rc to pendingOutput.toString())
                                    pendingMarker = null
                                    pendingFuture = null
                                    pendingOutput.setLength(0)
                                } else {
                                    pendingOutput.append(line).append('\n')
                                }
                            } else if (marker != null) {
                                pendingOutput.append(line).append('\n')
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (!stopping) append("[host] UML console reader closed unexpectedly: ${e.message}\n")
            } finally {
                val rc = runCatching { p.waitFor() }.getOrDefault(-1)
                val failure = IllegalStateException("UML exited before guest command completed (rc=$rc)")
                synchronized(consoleLock) {
                    pendingFuture?.completeExceptionally(failure)
                    pendingFuture = null
                    pendingMarker = null
                    pendingOutput.setLength(0)
                }
                if (!stopping) {
                    guestShellReady.completeExceptionally(IllegalStateException("UML exited before guest shell was ready (rc=$rc)"))
                }
                append("[host] UML launcher exited rc=$rc\n")
                running = false
                guestReady = false
                inputReady = false
            }
        }, "vessel-uml-console").apply { isDaemon = true; start() }
    }

    private fun awaitGuestShell(timeoutSeconds: Int) {
        check(umlProcess?.isAlive == true) { "UML is not running" }
        try {
            guestShellReady.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (t: Throwable) {
            throw IllegalStateException("Debian userspace did not reach the interactive shell within ${timeoutSeconds}s", t)
        }
    }

    private fun guestBlocking(command: String, timeoutSeconds: Int): Pair<Int, String> = synchronized(commandLock) {
        val future: CompletableFuture<Pair<Int, String>>
        synchronized(consoleLock) {
            check(umlProcess?.isAlive == true) { "UML is not running" }
            check(pendingFuture == null) { "Another guest command is running" }
            val marker = "__VESSEL_${commandId.incrementAndGet()}__"
            future = CompletableFuture()
            pendingMarker = marker
            pendingFuture = future
            pendingOutput.setLength(0)
            consoleWriter!!.apply {
                write("$command\nprintf '$marker:%s\\n' \$?\n")
                flush()
            }
        }
        try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } finally {
            if (!future.isDone) synchronized(consoleLock) {
                if (pendingFuture === future) {
                    pendingMarker = null
                    pendingFuture = null
                    pendingOutput.setLength(0)
                }
            }
        }
    }

    private fun ensureGuestFilesystemCapacity() {
        progress("disk_resize", 43, "Expanding Debian filesystem")
        val (rc, out) = guestBlocking(
            "if command -v resize2fs >/dev/null 2>&1; then resize2fs /dev/ubda; else echo 'resize2fs is missing from the Debian base image'; false; fi",
            180,
        )
        if (rc != 0) {
            append("[disk] resize2fs failed rc=$rc ${out.takeLast(3000)}\n")
            error("Could not expand the Debian ext4 filesystem to its backing disk capacity")
        }
        val (freeRc, freeOut) = guestBlocking(
            "avail=\$(df -Pk / | awk 'NR==2 {print \$4}'); echo free_kib=\$avail; test \$avail -ge $MIN_GUEST_FREE_KIB",
            15,
        )
        check(freeRc == 0) {
            "Debian filesystem still has too little free space after resize (${freeOut.trim()})"
        }
        append("[disk] ${freeOut.trim()} after ext4 resize\n")
    }

    private fun awaitVirtioInputDevices() {
        check(inputBackendsAlive()) { "VirtIO input backend process exited before guest enumeration" }
        var ready = false
        var last = ""
        var tries = 0
        while (!ready && tries++ < 100) {
            val result = guestBlocking(
                "test -r /proc/bus/input/devices && " +
                    "grep -Fq 'Vessel Touchscreen' /proc/bus/input/devices && " +
                    "grep -Fq 'Vessel Trackpad' /proc/bus/input/devices && " +
                    "grep -Fq 'Vessel Keyboard' /proc/bus/input/devices",
                5,
            )
            ready = result.first == 0
            last = result.second
            if (!ready) Thread.sleep(100)
        }
        check(ready) { "Linux virtio-input devices did not appear: ${last.takeLast(3000)}" }
        check(VesselVirtioInput.send("rel", mapOf("dx" to 0, "dy" to 0))) {
            "Android -> virtio-input control path failed: ${VesselVirtioInput.status()}"
        }
        check(inputBackendsAlive()) { "VirtIO input backend exited during input validation" }
        inputReady = true
        lastInputFailure = ""
        append("[input] Linux evdev devices ready; sender=${VesselVirtioInput.status()}\n")
    }

    private fun plasmaReadyCommand(): String =
        "command -v startplasma-x11 >/dev/null && command -v Xorg >/dev/null && command -v xrandr >/dev/null && " +
            "command -v xinput >/dev/null && (command -v cvt >/dev/null || command -v xcvt >/dev/null) && " +
            "test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so"

    private fun ensurePlasma() {
        progress("plasma", 55, "Checking KDE Plasma desktop")
        val check = guestBlocking(plasmaReadyCommand(), 20)
        if (check.first == 0) return
        progress("plasma_install", 56, "Installing KDE Plasma once into persistent disk")
        val cmd = "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends " +
            "plasma-workspace plasma-desktop kwin-x11 xserver-xorg-core xserver-xorg-input-libinput dbus dbus-x11 udev libinput-tools mesa-utils x11-xserver-utils xinput xcvt && apt-get clean"
        val (rc, out) = guestBlocking(cmd, 1800)
        if (rc != 0) {
            val lower = out.lowercase()
            val reason = when {
                "not enough free space" in lower || "no space left on device" in lower ->
                    "Plasma setup could not finish because the Debian filesystem ran out of space"
                "temporary failure resolving" in lower || "failed to fetch" in lower ->
                    "Plasma setup could not download Debian packages; check the Linux network connection"
                else -> "Plasma package installation failed (apt rc=$rc). See Runtime log for details"
            }
            append("[plasma] apt failed rc=$rc\n")
            error(reason)
        }
        val verify = guestBlocking(plasmaReadyCommand(), 20)
        check(verify.first == 0) { "Plasma packages installed, but required desktop components are still missing" }
        append("[plasma] KDE Plasma/Xorg packages ready\n")
    }

    private fun displayModeCommand(): String {
        val w = displayWidth
        val h = displayHeight
        val dpi = displayDpi
        val rate = displayRefresh.roundToInt().coerceIn(30, 240)
        return """
            export DISPLAY=:0
            out=${'$'}(xrandr --query | awk '/ connected/{print ${'$'}1; exit}')
            test -n "${'$'}out" || exit 2
            cvtbin=${'$'}(command -v cvt || command -v xcvt)
            line=${'$'}(${'$'}cvtbin $w $h $rate 2>/dev/null | sed -n 's/^Modeline //p' | head -1)
            if [ -n "${'$'}line" ]; then
              name=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/^"\([^"]*\)".*/\1/p')
              eval "xrandr --newmode ${'$'}line" 2>/dev/null || true
              xrandr --addmode "${'$'}out" "${'$'}name" 2>/dev/null || true
              xrandr --output "${'$'}out" --mode "${'$'}name"
            fi
            xrandr --dpi $dpi
        """.trimIndent()
    }

    private fun applyDisplayModeBlocking(): Boolean {
        val (rc, out) = guestBlocking(displayModeCommand(), 20)
        if (rc != 0) append("[display] xrandr rc=$rc ${out.takeLast(2000)}\n")
        return rc == 0
    }

    private fun launchDesktop() {
        progress("desktop", 72, "Starting direct DRM Plasma desktop")
        val prep = "set -e; mkdir -p /run/dbus /run/user /etc/X11/xorg.conf.d /tmp/.X11-unix; " +
            "dbus-uuidgen --ensure=/etc/machine-id; " +
            "(pgrep -x systemd-udevd >/dev/null || (/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log || /usr/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log)); " +
            "udevadm trigger --action=add || true; udevadm settle --timeout=10 || true; test -S /run/dbus/system_bus_socket || dbus-daemon --system --fork; " +
            "id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel; for g in video render input; do getent group \"\$g\" >/dev/null || groupadd \"\$g\"; done; usermod -a -G video,render,input vessel; " +
            "uid=\$(id -u vessel); gid=\$(id -g vessel); mkdir -p /run/user/\$uid; chown \$uid:\$gid /run/user/\$uid; chmod 700 /run/user/\$uid; test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1; " +
            "cat >/etc/X11/xorg.conf.d/99-vessel.conf <<'XEOF'\n" +
            "Section \"ServerFlags\"\n Option \"AutoAddDevices\" \"true\"\n Option \"DontVTSwitch\" \"true\"\nEndSection\n" +
            "Section \"Device\"\n Identifier \"Vessel GPU\"\n Driver \"modesetting\"\n Option \"kmsdev\" \"/dev/dri/card0\"\n Option \"AccelMethod\" \"glamor\"\n Option \"SWcursor\" \"true\"\nEndSection\n" +
            "Section \"InputClass\"\n Identifier \"Vessel Trackpad\"\n MatchProduct \"Vessel Trackpad\"\n Driver \"libinput\"\nEndSection\n" +
            "Section \"InputClass\"\n Identifier \"Vessel Touchscreen\"\n MatchProduct \"Vessel Touchscreen\"\n Driver \"libinput\"\nEndSection\n" +
            "Section \"InputClass\"\n Identifier \"Vessel Keyboard\"\n MatchProduct \"Vessel Keyboard\"\n Driver \"libinput\"\nEndSection\nXEOF\n"
        val (prc, pout) = guestBlocking(prep, 50)
        check(prc == 0) { "desktop prep failed: $pout" }

        val session = "#!/bin/bash\n" +
            "export DISPLAY=:0 XDG_SESSION_TYPE=x11 XDG_SESSION_DESKTOP=KDE XDG_CURRENT_DESKTOP=KDE DESKTOP_SESSION=plasma KDE_FULL_SESSION=true KDE_SESSION_VERSION=5 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl\n" +
            "export XDG_RUNTIME_DIR=/run/user/\$(id -u)\n" +
            "exec startplasma-x11\n"
        val b64 = Base64.getEncoder().encodeToString(session.toByteArray())
        val launch = "printf '%s' '$b64' | base64 -d >/usr/local/bin/vessel-plasma-session; chmod 755 /usr/local/bin/vessel-plasma-session; " +
            "if [ -s /tmp/vessel-xorg.pid ]; then kill \$(cat /tmp/vessel-xorg.pid) 2>/dev/null || true; fi; pkill -u vessel -x plasmashell 2>/dev/null || true; pkill -u vessel -x kwin_x11 2>/dev/null || true; " +
            "rm -f /tmp/.X0-lock /tmp/.X11-unix/X0; nohup setsid sh -c 'exec </dev/tty1 >/dev/tty1 2>&1; exec env LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl Xorg :0 -ac -noreset -nolisten tcp -novtswitch -sharevts vt1' >/tmp/vessel-xorg.log 2>&1 & echo \$! >/tmp/vessel-xorg.pid; " +
            "for i in \$(seq 1 160); do test -S /tmp/.X11-unix/X0 && break; sleep .1; done; test -S /tmp/.X11-unix/X0; " +
            "DISPLAY=:0 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl glxinfo -B >/tmp/vessel-glx.log 2>&1; ! grep -Eqi 'llvmpipe|softpipe|swrast|software rasterizer' /tmp/vessel-glx.log; " +
            "for i in \$(seq 1 50); do DISPLAY=:0 xinput list --name-only >/tmp/vessel-xinput.log 2>&1; grep -Fq 'Vessel Trackpad' /tmp/vessel-xinput.log && grep -Fq 'Vessel Touchscreen' /tmp/vessel-xinput.log && grep -Fq 'Vessel Keyboard' /tmp/vessel-xinput.log && break; sleep .1; done; " +
            "DISPLAY=:0 xinput list --name-only >/tmp/vessel-xinput.log 2>&1; " +
            "grep -Fq 'Vessel Trackpad' /tmp/vessel-xinput.log && grep -Fq 'Vessel Touchscreen' /tmp/vessel-xinput.log && grep -Fq 'Vessel Keyboard' /tmp/vessel-xinput.log || { cat /tmp/vessel-xinput.log; exit 43; }; " +
            "nohup su -l vessel -c \"DISPLAY=:0 XDG_RUNTIME_DIR=/run/user/\$(id -u vessel) dbus-run-session -- /usr/local/bin/vessel-plasma-session\" >/tmp/vessel-plasma.log 2>&1 </dev/null &"
        val (rc, out) = guestBlocking(launch, 60)
        check(rc == 0) { "Plasma launch failed: ${out.takeLast(12000)}" }
        append("[input] Xorg/libinput attached all Vessel devices\n")
        applyDisplayModeBlocking()
    }

    suspend fun startDesktop(): JSONObject = withContext(Dispatchers.IO) {
        if (running) return@withContext status()
        stopping = false
        lastError = ""
        try {
            assertAssets()
            ensureDisk()
            persistentLog.writeText("Vessel ${REVISION} startup\n")
            append("[host] machine=${machineDir.absolutePath}\n")
            append("[host] presenter=${VesselWaylandPresenter.status()}\n")
            progress("input_backend", 28, "Starting native VirtIO input devices")
            startInputBackends()
            progress("gpu", 31, "Starting native VirtIO GPU")
            startGpu()
            progress("uml", 36, "Booting Debian ARM64 · ${guestMemoryMb} MiB")
            startUml()
            startedAt = android.os.SystemClock.elapsedRealtime()
            running = true
            progress("guest_boot", 40, "Waiting for Debian userspace")
            awaitGuestShell(240)
            ensureGuestFilesystemCapacity()
            val ready = guestBlocking("stty -echo 2>/dev/null || true; test -c /dev/dri/card0 && test -c /dev/dri/renderD128", 30)
            check(ready.first == 0) { "Debian/VirtIO GPU did not become ready: ${ready.second.takeLast(8000)}" }
            guestReady = true
            progress("input", 48, "Verifying native VirtIO evdev/libinput devices")
            awaitVirtioInputDevices()
            ensurePlasma()
            launchDesktop()
            progress("frame", 88, "Waiting for direct DMA-BUF scanout")
            var tries = 0
            while (tries++ < 240) {
                val ps = VesselWaylandPresenter.status()
                if (ps.startsWith("presenting-dmabuf")) {
                    desktopReady = true
                    progress("ready", 100, "Plasma visible through direct DMA-BUF")
                    return@withContext baseState().put("presenter", ps)
                }
                if (ps.contains("missing-") || ps.contains("failed") || ps.contains("rejected")) {
                    error("Native presenter failed: $ps")
                }
                Thread.sleep(50)
            }
            error("Plasma started but no DMA-BUF frame reached Vessel; presenter=${VesselWaylandPresenter.status()}")
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            append("[error] $lastError\n")
            stopBlocking()
            throw t
        }
    }

    suspend fun guest(command: String, timeoutSeconds: Int = 45): JSONObject = withContext(Dispatchers.IO) {
        val (rc, out) = guestBlocking(command, timeoutSeconds)
        baseState(rc == 0).put("rc", rc).put("output", out).also {
            if (rc != 0) it.put("error", "guest command failed rc=$rc")
        }
    }

    private fun stopBlocking() {
        stopping = true
        runCatching {
            if (umlProcess?.isAlive == true) {
                consoleWriter?.write("exit\n")
                consoleWriter?.flush()
                umlProcess?.waitFor(8, TimeUnit.SECONDS)
            }
        }
        runCatching { consoleWriter?.close() }
        runCatching { if (umlProcess?.isAlive == true) umlProcess?.destroyForcibly() }
        runCatching { gpuProcess?.destroyForcibly() }
        umlProcess = null
        gpuProcess = null
        consoleWriter = null
        running = false
        guestReady = false
        inputReady = false
        desktopReady = false
        synchronized(consoleLock) {
            pendingFuture?.completeExceptionally(IllegalStateException("Vessel stopped"))
            pendingFuture = null
            pendingMarker = null
            pendingOutput.setLength(0)
        }
        stopInputBackends()
        gpuSocket.delete()
    }

    suspend fun stop(): JSONObject = withContext(Dispatchers.IO) {
        stopBlocking()
        VesselWaylandPresenter.resetPresentationLatch()
        baseState()
    }
}
