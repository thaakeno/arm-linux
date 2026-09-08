package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs inside the Shizuku UserService as uid=shell. All privileged AVF interaction lives here.
 * No direct AF_VSOCK creation is used: Microdroid commands use VirtualMachine.connectVsock().
 */
class VmBridge : IVmBridge.Stub() {
    private val microVmName = "dev1-gate-a"
    private val debianVmName = "dev1-debian13"
    private val vmData = File("/data/local/tmp/dev1-linux-vmm")
    private val debianDir = File("/data/local/tmp/dev1-linux/debian")
    private val imageUrl = "https://dl.google.com/android/ferrochrome/latest/aarch64/images.tar.gz"

    private var manager: Any? = null
    private var vm: Any? = null
    private var vmMode = "none"
    private var stage = "idle"
    private var log = ""
    private var lastError = ""
    private var consoleThread: Thread? = null
    private var displayService: Any? = null

    private val installing = AtomicBoolean(false)
    @Volatile private var installDoneBytes = 0L
    @Volatile private var installTotalBytes = -1L
    @Volatile private var installError = ""

    private val lock = Any()
    private fun append(value: String) = synchronized(lock) {
        log = (log + value + "\n").takeLast(128000)
    }

    private class RedirectedDataContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getDataDir(): File = root
        override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        override fun getCodeCacheDir(): File = File(root, "code_cache").also { it.mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    private fun baseContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentApplication = runCatching {
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        if (currentApplication != null && currentApplication.packageName == PACKAGE) return currentApplication

        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: error("ActivityThread.currentActivityThread() returned null")
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        return system.createPackageContext(PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun redirectedContext(): Context {
        check(vmData.mkdirs() || vmData.isDirectory) { "Cannot create ${vmData.path}" }
        val context = RedirectedDataContext(baseContext(), vmData)
        append("context package=${context.packageName} dataDir=${context.dataDir}")
        return context
    }

    private fun unwrap(t: Throwable): Throwable {
        var x = t
        while (x is InvocationTargetException && x.targetException != null) x = x.targetException
        return x
    }

    private fun compatible(parameter: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !parameter.isPrimitive
        if (!parameter.isPrimitive) return parameter.isAssignableFrom(arg.javaClass)
        return when (parameter) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Long.TYPE -> arg is Long
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Float.TYPE -> arg is Float
            java.lang.Double.TYPE -> arg is Double
            else -> true
        }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.indices.all { i -> compatible(m.parameterTypes[i], args[i]) }
        } ?: error("Method ${target.javaClass.name}.$name/${args.size} not found")
        return try { method.invoke(target, *args) } catch (t: Throwable) { throw unwrap(t) }
    }

    private fun invokeOptional(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.indices.all { i -> compatible(m.parameterTypes[i], args[i]) }
        } ?: return null
        return try { method.invoke(target, *args) } catch (t: Throwable) { throw unwrap(t) }
    }

    private fun manager(context: Context = redirectedContext()): Any {
        manager?.let { return it }
        stage = "manager_init"
        val managerClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val mgr = managerClass.getConstructor(Context::class.java).newInstance(context)
        manager = mgr
        append("VirtualMachineManager constructed with shell-writable redirected data directory")
        return mgr
    }

    private fun vmStatus(machine: Any): Int = (invoke(machine, "getStatus") as Number).toInt()
    private fun runningStatus(machine: Any): Int = machine.javaClass.getField("STATUS_RUNNING").getInt(null)
    private fun isRunning(machine: Any?): Boolean = machine != null && runCatching {
        vmStatus(machine) == runningStatus(machine)
    }.getOrDefault(false)

    private fun buildMicrodroid(context: Context): Any {
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
        val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
        invoke(builder, "setProtectedVm", true)
        invoke(builder, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        invoke(builder, "setMemoryBytes", 512L * 1024L * 1024L)
        invoke(builder, "setPayloadBinaryName", "libdev1_payload.so")
        return invoke(builder, "build") ?: error("VirtualMachineConfig build returned null")
    }

    private fun acquireVm(name: String, config: Any, mode: String): Any {
        val mgr = manager()
        val managerClass = mgr.javaClass
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        stage = "vm_create:$mode"
        var machine = managerClass.getMethod("getOrCreate", String::class.java, configClass)
            .invoke(mgr, name, config) ?: error("getOrCreate returned null")

        // Custom images change as display size/backend changes. Match AOSP Terminal behavior:
        // update config when possible, otherwise recreate this app-owned VM only.
        try {
            invokeOptional(machine, "setConfig", config)
        } catch (t: Throwable) {
            append("setConfig rejected (${unwrap(t).message}); recreating $name")
            invoke(mgr, "delete", name)
            machine = invoke(mgr, "create", name, config) ?: error("create returned null")
        }
        vm = machine
        vmMode = mode
        append("managed VM acquired: $name mode=$mode")
        return machine
    }

    private fun startMachine(machine: Any, mode: String) {
        if (!isRunning(machine)) {
            stage = "vm_start:$mode"
            invoke(machine, "run")
            append("VirtualMachine.run() accepted for $mode")
        }
        startConsoleCapture(machine)
        stage = "running:$mode"
    }

    private fun startConsoleCapture(machine: Any) {
        consoleThread?.interrupt()
        val stream = runCatching { invokeOptional(machine, "getConsoleOutput") as? InputStream }.getOrNull()
            ?: return
        consoleThread = Thread({
            try {
                val buf = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) append(String(buf, 0, n, Charsets.UTF_8).trimEnd())
                }
            } catch (t: Throwable) {
                if (!Thread.currentThread().isInterrupted) append("console capture ended: ${t.message}")
            }
        }, "dev1-vm-console").also { it.isDaemon = true; it.start() }
    }

    @Synchronized override fun startVm(): String {
        lastError = ""
        return try {
            if (vmMode == "debian" && isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:microdroid"
            val machine = acquireVm(microVmName, buildMicrodroid(context), "microdroid")
            startMachine(machine, "microdroid")
            status()
        } catch (t: Throwable) {
            blocked(t)
            status()
        }
    }

    @Synchronized override fun stopVm(): String {
        lastError = ""
        try { stopInternal() } catch (t: Throwable) { blocked(t, "stop") }
        return status()
    }

    private fun stopInternal() {
        clearDisplaySurfaceInternal()
        vm?.let { machine -> if (isRunning(machine)) invoke(machine, "stop") }
        consoleThread?.interrupt(); consoleThread = null
        stage = "stopped:$vmMode"
        append("VM stopped mode=$vmMode")
    }

    private fun blocked(t: Throwable, explicitStage: String? = null) {
        val e = unwrap(t)
        lastError = "${e.javaClass.name}: ${e.message}"
        stage = "blocked:${explicitStage ?: stage}"
        append(lastError)
    }

    override fun inspectCapabilities(): String {
        return try {
            val ctx = redirectedContext()
            val mgr = manager(ctx)
            val caps = (invoke(mgr, "getCapabilities") as? Number)?.toInt() ?: -1
            val cls = mgr.javaClass
            val protectedBit = runCatching { cls.getField("CAPABILITY_PROTECTED_VM").getInt(null) }.getOrDefault(1)
            val nonProtectedBit = runCatching { cls.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null) }.getOrDefault(2)
            val customClass = runCatching { Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig") }.isSuccess
            val gpuClass = runCatching { Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$GpuConfig\$Builder") }.isSuccess
            val displayClass = runCatching { Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$DisplayConfig\$Builder") }.isSuccess
            JSONObject()
                .put("ok", true)
                .put("capabilities", caps)
                .put("protectedVm", caps >= 0 && caps and protectedBit != 0)
                .put("nonProtectedVm", caps >= 0 && caps and nonProtectedBit != 0)
                .put("customImageApi", customClass)
                .put("gpuConfigApi", gpuClass)
                .put("displayConfigApi", displayClass)
                .put("debianInstalled", isDebianInstalled())
                .toString()
        } catch (t: Throwable) {
            val e = unwrap(t)
            JSONObject().put("ok", false).put("error", "${e.javaClass.name}: ${e.message}").toString()
        }
    }

    override fun installDebian(): String {
        if (isDebianInstalled()) return status()
        if (!installing.compareAndSet(false, true)) return status()
        installError = ""
        installDoneBytes = 0L
        installTotalBytes = -1L
        stage = "debian_download"
        Thread({
            try {
                installOfficialImage()
                stage = "debian_installed"
                append("official AVF Debian image installed at ${debianDir.path}")
            } catch (t: Throwable) {
                val e = unwrap(t)
                installError = "${e.javaClass.name}: ${e.message}"
                lastError = installError
                stage = "blocked:debian_install"
                append(installError)
            } finally {
                installing.set(false)
            }
        }, "dev1-debian-installer").also { it.isDaemon = true; it.start() }
        return status()
    }

    private fun installOfficialImage() {
        val root = debianDir.canonicalFile
        if (root.exists()) root.deleteRecursively()
        check(root.mkdirs()) { "Cannot create ${root.path}" }
        val archive = File(root.parentFile, "images.tar.gz.part")
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "DEV-1-LINUX/0.2")
        connection.connect()
        check(connection.responseCode in 200..299) { "Image download HTTP ${connection.responseCode}" }
        installTotalBytes = connection.contentLengthLong
        BufferedInputStream(connection.inputStream, 256 * 1024).use { input ->
            BufferedOutputStream(FileOutputStream(archive), 256 * 1024).use { output ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    installDoneBytes += n
                }
            }
        }
        connection.disconnect()
        stage = "debian_extract"
        extractTarGz(archive, root)
        archive.delete()
        val config = File(root, "vm_config.json")
        check(config.isFile) { "Official image archive did not contain vm_config.json" }
        val rootPart = File(root, "root_part")
        check(rootPart.isFile) { "Official image archive did not contain root_part" }
        // crosvm raw disk backing must be block aligned; preserve contents and only extend upward.
        val remainder = rootPart.length() % 4096L
        if (remainder != 0L) rootPart.setLength(rootPart.length() + (4096L - remainder))
        File(root, ".dev1-installed").writeText("source=$imageUrl\n")
    }

    private fun extractTarGz(archive: File, root: File) {
        val canonicalRoot = root.canonicalPath + File.separator
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = File(root, entry.name).canonicalFile
                check(target.path == root.canonicalPath || target.path.startsWith(canonicalRoot)) { "Unsafe archive path: ${entry.name}" }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        BufferedOutputStream(target.outputStream(), 256 * 1024).use { out -> tar.copyTo(out, 256 * 1024) }
                    }
                    else -> append("skipping non-file archive entry ${entry.name}")
                }
            }
        }
    }

    private fun isDebianInstalled(): Boolean =
        File(debianDir, "vm_config.json").isFile && File(debianDir, "root_part").isFile

    @Synchronized override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String {
        lastError = ""
        return try {
            check(isDebianInstalled()) { "Debian image is not installed yet" }
            if (vmMode == "microdroid" && isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:debian"
            val config = buildDebianConfig(context, width, height, dpi, refreshRate)
            val machine = acquireVm(debianVmName, config, "debian")
            startMachine(machine, "debian")
            status()
        } catch (t: Throwable) {
            blocked(t)
            status()
        }
    }

    private fun buildDebianConfig(context: Context, width: Int, height: Int, dpi: Int, refreshRate: Int): Any {
        val configFile = File(debianDir, "vm_config.json")
        val raw = configFile.readText()
            .replace("\$PAYLOAD_DIR", debianDir.path)
            .replace("\$APP_DATA_DIR", debianDir.path)
        val json = JSONObject(raw)

        val customBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder")
        val custom = customBuilderClass.getConstructor().newInstance()
        invoke(custom, "setName", debianVmName)

        fun resolved(key: String): String? {
            val value = json.optString(key, "").trim()
            if (value.isBlank() || value == "null") return null
            return if (value.startsWith("/")) value else File(debianDir, value).path
        }
        resolved("bootloader")?.let { invoke(custom, "setBootloaderPath", it) }
        resolved("kernel")?.let { invoke(custom, "setKernelPath", it) }
        resolved("initrd")?.let { invoke(custom, "setInitrdPath", it) }
        json.optString("params", "").split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { invoke(custom, "addParam", it) }

        val disks = json.optJSONArray("disks") ?: JSONArray().put(JSONObject().put("image", File(debianDir, "root_part").path).put("writable", true))
        for (i in 0 until disks.length()) {
            val disk = disks.getJSONObject(i)
            val imageRaw = disk.optString("image", "")
            val image = when {
                imageRaw.contains("\$PAYLOAD_DIR") -> imageRaw.replace("\$PAYLOAD_DIR", debianDir.path)
                imageRaw.startsWith("/") -> imageRaw
                imageRaw.isNotBlank() -> File(debianDir, imageRaw).path
                else -> File(debianDir, "root_part").path
            }
            val className = if (disk.optBoolean("writable", true))
                "android.system.virtualmachine.VirtualMachineCustomImageConfig\$Disk\$RWDisk"
            else "android.system.virtualmachine.VirtualMachineCustomImageConfig\$Disk\$RODisk"
            val diskObj = Class.forName(className).getConstructor(String::class.java).newInstance(image)
            invoke(custom, "addDisk", diskObj)
        }

        // Match the current AOSP Terminal gfxstream configuration. If these APIs are missing,
        // starting the desktop must fail loudly instead of pretending software rendering passed.
        stage = "config:gfxstream"
        val gpuBuilder = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$GpuConfig\$Builder")
            .getConstructor().newInstance()
        invoke(gpuBuilder, "setBackend", "gfxstream")
        invokeOptional(gpuBuilder, "setRendererUseEgl", false)
        invokeOptional(gpuBuilder, "setRendererUseGles", false)
        invokeOptional(gpuBuilder, "setRendererUseGlx", false)
        invokeOptional(gpuBuilder, "setRendererUseSurfaceless", true)
        invokeOptional(gpuBuilder, "setRendererUseVulkan", true)
        invokeOptional(gpuBuilder, "setContextTypes", arrayOf("gfxstream-vulkan", "gfxstream-composer"))
        invoke(custom, "setGpuConfig", invoke(gpuBuilder, "build") ?: error("GpuConfig build failed"))

        val displayBuilder = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$DisplayConfig\$Builder")
            .getConstructor().newInstance()
        invoke(displayBuilder, "setWidth", width.coerceAtLeast(640))
        invoke(displayBuilder, "setHeight", height.coerceAtLeast(480))
        invoke(displayBuilder, "setHorizontalDpi", dpi.coerceIn(120, 640))
        invoke(displayBuilder, "setVerticalDpi", dpi.coerceIn(120, 640))
        invoke(displayBuilder, "setRefreshRate", refreshRate.coerceIn(30, 240))
        invoke(custom, "setDisplayConfig", invoke(displayBuilder, "build") ?: error("DisplayConfig build failed"))
        invokeOptional(custom, "useKeyboard", true)
        invokeOptional(custom, "useMouse", true)
        invokeOptional(custom, "useTouch", true)
        invokeOptional(custom, "useTrackpad", true)
        invokeOptional(custom, "useAutoMemoryBalloon", true)

        // Keep the image's supported AVF networking choice visible. If the platform rejects it,
        // the exact exception is surfaced in diagnostics instead of hiding it behind a fallback.
        val wantsNetwork = json.optBoolean("network", false)
        invokeOptional(custom, "useNetwork", wantsNetwork)
        append("Debian config network=$wantsNetwork display=${width}x$height@$refreshRate dpi=$dpi gpu=gfxstream")

        val customConfig = invoke(custom, "build") ?: error("CustomImageConfig build failed")
        val vmConfigClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builder = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        invoke(builder, "setProtectedVm", json.optBoolean("protected", false))
        invoke(builder, "setMemoryBytes", json.optLong("memory_mib", 4096L).coerceIn(1024L, 12288L) * 1024L * 1024L)
        invoke(builder, "setDebugLevel", vmConfigClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        val matchHost = runCatching { vmConfigClass.getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null) }.getOrNull()
        if (matchHost != null) invokeOptional(builder, "setCpuTopology", matchHost)
        invoke(builder, "setCustomImageConfig", customConfig)
        invokeOptional(builder, "setVmOutputCaptured", true)
        invokeOptional(builder, "setVmConsoleInputSupported", true)
        invokeOptional(builder, "setConnectVmConsole", true)
        return invoke(builder, "build") ?: error("Debian VirtualMachineConfig build failed")
    }

    private fun getDisplayService(): Any {
        displayService?.let { return it }
        stage = "display_service"
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("waitForService", String::class.java)
            .invoke(null, "android.system.virtualizationservice") as? IBinder
            ?: error("virtualizationservice binder unavailable")
        val internalStub = Class.forName("android.system.virtualizationservice_internal.IVirtualizationServiceInternal\$Stub")
        val internal = internalStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("IVirtualizationServiceInternal unavailable")
        val displayBinder = invoke(internal, "waitDisplayService") as? IBinder
            ?: error("crosvm display service unavailable")
        val displayStub = Class.forName("android.crosvm.ICrosvmAndroidDisplayService\$Stub")
        val service = displayStub.getMethod("asInterface", IBinder::class.java).invoke(null, displayBinder)
            ?: error("ICrosvmAndroidDisplayService unavailable")
        displayService = service
        return service
    }

    override fun setDisplaySurface(surface: Surface) {
        try {
            check(vmMode == "debian" && isRunning(vm)) { "Debian VM is not running" }
            stage = "display_attach"
            invoke(getDisplayService(), "setSurface", surface, false)
            invokeOptional(getDisplayService(), "drawSavedFrameForSurface", false)
            stage = "display_attached"
            append("Android Surface attached to crosvm display service")
        } catch (t: Throwable) {
            blocked(t, "display_attach")
            throw IllegalStateException(lastError, unwrap(t))
        }
    }

    override fun clearDisplaySurface() {
        try { clearDisplaySurfaceInternal() } catch (t: Throwable) { blocked(t, "display_clear") }
    }

    private fun clearDisplaySurfaceInternal() {
        val service = displayService ?: return
        runCatching { invokeOptional(service, "saveFrameForSurface", false) }
        runCatching { invokeOptional(service, "removeSurface", false) }
        displayService = null
    }

    override fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean {
        val machine = vm ?: return false
        if (vmMode != "debian" || !isRunning(machine)) return false
        return try {
            val now = android.os.SystemClock.uptimeMillis()
            val event = KeyEvent(now, now, action, keyCode, 0, metaState)
            (invoke(machine, "sendKeyEvent", event) as? Boolean) ?: true
        } catch (t: Throwable) {
            blocked(t, "input_key"); false
        }
    }

    override fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean {
        val machine = vm ?: return false
        if (vmMode != "debian" || !isRunning(machine)) return false
        return try {
            val now = android.os.SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try { (invoke(machine, "sendMultiTouchEvent", event) as? Boolean) ?: true }
            finally { event.recycle() }
        } catch (t: Throwable) {
            blocked(t, "input_touch:$pointerId"); false
        }
    }

    @Synchronized override fun guestShell(command: String): String {
        require(command.length <= 4096) { "Command too long" }
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Managed VM is not created").toString()
        check(vmMode == "microdroid") { "ADB-vsock shell is currently the Microdroid Gate A transport; Debian uses its custom-image console/display path" }
        check(isRunning(machine)) { "Managed VM is not running" }
        stage = "connect_vsock"
        var last: Throwable? = null
        repeat(30) { attempt ->
            try {
                val method = machine.javaClass.methods.firstOrNull { it.name == "connectVsock" && it.parameterCount == 1 }
                    ?: error("VirtualMachine.connectVsock not found")
                val arg: Any = if (method.parameterTypes[0] == java.lang.Long.TYPE) 5555L else 5555
                val pfd = method.invoke(machine, arg) as ParcelFileDescriptor
                pfd.use {
                    stage = "adb_shell"
                    val output = NativeTransport.shellFd(it.fd, command)
                    stage = "guest_command_pass"
                    append("guest command passed: ${command.take(120)}")
                    return JSONObject().put("ok", true).put("output", output).toString()
                }
            } catch (t: Throwable) {
                last = unwrap(t)
                if (attempt < 29) Thread.sleep(500)
            }
        }
        val e = last ?: IllegalStateException("connectVsock failed")
        lastError = "${e.javaClass.name}: ${e.message}"
        stage = "blocked:connect_vsock"
        append(lastError)
        return JSONObject().put("ok", false).put("error", lastError).toString()
    }

    @Synchronized override fun status(): String {
        val running = isRunning(vm)
        val root = vm?.let { machine -> runCatching { invokeOptional(machine, "getRootDir") as? File }.getOrNull()?.path ?: "" } ?: ""
        val total = installTotalBytes
        val progress = if (total > 0) (installDoneBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else -1.0
        return JSONObject()
            .put("name", if (vmMode == "debian") debianVmName else microVmName)
            .put("running", running)
            .put("cid", -1)
            .put("managed", vm != null)
            .put("mode", vmMode)
            .put("stage", stage)
            .put("api", "VirtualMachineManager/connectVsock/custom-image")
            .put("vmRoot", root)
            .put("dataDir", vmData.path)
            .put("error", lastError)
            .put("log", synchronized(lock) { log })
            .put("debianInstalled", isDebianInstalled())
            .put("debianInstalling", installing.get())
            .put("installBytes", installDoneBytes)
            .put("installTotal", installTotalBytes)
            .put("installProgress", progress)
            .put("installError", installError)
            .put("guestGraphics", if (stage == "display_attached") "gfxstream surface attached; hardware proof pending" else "unproven")
            .put("debian", if (isDebianInstalled()) "official AVF image installed" else "not installed")
            .toString()
    }

    override fun destroy() {
        runCatching { stopInternal() }
        kotlin.system.exitProcess(0)
    }

    companion object { private const val PACKAGE = "com.example.dreamlinux" }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd: Int, command: String): String
}
