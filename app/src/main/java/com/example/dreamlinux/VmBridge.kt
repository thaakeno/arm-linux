package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dalvik.system.PathClassLoader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Shell-UID AVF controller. Gate A remains the known-good protected Microdroid path. */
class VmBridge(private val appContext: Context) : IVmBridge.Stub() {
    constructor() : this(resolveApplicationContext())

    private val gateVmName = "dev2-gate-a-v5"
    private val debianPvmName = "dev2-debian13-pvm-v2"
    private val debianNonPvmName = "dev2-debian13-v2"
    private val vmRoot = File("/data/local/tmp/dev2-linux/${appContext.packageName}")
    private val debianDir = File("/data/local/tmp/dev2-linux/debian13")
    private val scopedContext: Context by lazy { ShellVmContext(appContext, vmRoot) }

    private var manager: Any? = null
    private var managedVm: Any? = null
    private var mode = "none"
    private var running = false
    private var log = ""
    private var failureStage = "none"
    private var vmApiInit = "PENDING"
    private var vmCreation = "PENDING"
    private var vmBoot = "PENDING"
    private var connectVsock = "PENDING"
    private var vsockFdReceived = "PENDING"
    private var adbHandshake = "PENDING"
    private var guestCommand = "PENDING"
    private var debianBoot = "NOT TESTED"
    private var debianIdentity = "NOT TESTED"
    private var graphicsState = "NOT TESTED"
    private var displayState = "NOT TESTED"
    private var kdeState = "NOT TESTED"
    private var capabilitiesText = "NOT TESTED"
    private var debianProtected = false

    private val installing = AtomicBoolean(false)
    @Volatile private var installDone = 0L
    @Volatile private var installTotal = -1L
    @Volatile private var installError = ""
    private val kdeInstalling = AtomicBoolean(false)
    @Volatile private var kdeStage = "not installed"
    @Volatile private var kdeError = ""

    private var consoleThread: Thread? = null
    private var consoleInput: OutputStream? = null
    private var displayService: Any? = null
    private val lock = Any()

    private fun appendLine(value: String) = synchronized(lock) {
        val clean = value.trimEnd('\r', '\n')
        log = (log + clean + "\n").takeLast(256000)
    }

    private fun appendRaw(value: String) = synchronized(lock) {
        log = (log + value.replace("\r\n", "\n").replace('\r', '\n')).takeLast(256000)
    }

    private fun logSnapshot(): String = synchronized(lock) { log }

    private fun rootCause(t: Throwable): Throwable {
        var current = t
        while (current is InvocationTargetException && current.targetException != null) current = current.targetException
        return current
    }

    private fun fail(stage: String, throwable: Throwable): Nothing {
        val cause = rootCause(throwable)
        failureStage = stage
        val detail = "${cause.javaClass.name}: ${cause.message ?: "no message"}"
        appendLine("[$stage] $detail")
        when (stage) {
            "vm_api_init" -> vmApiInit = "BLOCKED"
            "vm_creation" -> vmCreation = "BLOCKED"
            "vm_boot" -> vmBoot = "BLOCKED"
            "connect_vsock" -> connectVsock = "BLOCKED"
            "adb_handshake" -> adbHandshake = "BLOCKED"
            "guest_command" -> guestCommand = "BLOCKED"
            "debian_boot" -> debianBoot = "BLOCKED"
            "debian_identity" -> debianIdentity = "BLOCKED"
            "display" -> displayState = "BLOCKED"
            "graphics" -> graphicsState = "BLOCKED"
            "kde" -> kdeState = "BLOCKED"
        }
        throw IllegalStateException("$stage: $detail", cause)
    }

    private val virtLoader: ClassLoader by lazy {
        val dir = File("/apex/com.android.virt/javalib")
        val jars = dir.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?.sortedBy { it.name }
            ?.joinToString(File.pathSeparator) { it.absolutePath }
            .orEmpty()
        if (jars.isBlank()) appContext.classLoader else PathClassLoader(jars, appContext.classLoader)
    }

    private fun virtClass(name: String): Class<*> {
        return runCatching { Class.forName(name) }
            .getOrElse { Class.forName(name, true, virtLoader) }
    }

    private fun classExists(name: String): Boolean = runCatching { virtClass(name) }.isSuccess

    private fun initializeManager(): Any {
        manager?.let { return it }
        try {
            vmRoot.mkdirs()
            check(vmRoot.isDirectory && vmRoot.canWrite()) { "Shell VM root is not writable: ${vmRoot.absolutePath}" }
            val cls = virtClass("android.system.virtualmachine.VirtualMachineManager")
            val instance = cls.getConstructor(Context::class.java).newInstance(scopedContext)
            manager = instance
            vmApiInit = "PASS"
            failureStage = "none"
            appendLine("[vm_api_init] PASS ${cls.name}")
            appendLine("[vm_data_dir] ${scopedContext.dataDir.absolutePath}")
            return instance
        } catch (t: Throwable) {
            fail("vm_api_init", t)
        }
    }

    private fun buildGateConfig(): Any {
        val builder = virtClass("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(scopedContext)
        AvfReflect.call(builder, "setProtectedVm", true)
        AvfReflect.call(builder, "setPayloadBinaryName", "libdream_payload.so")
        AvfReflect.call(builder, "setMemoryBytes", 256L * 1024L * 1024L)
        val cls = virtClass("android.system.virtualmachine.VirtualMachineConfig")
        AvfReflect.call(builder, "setDebugLevel", cls.getField("DEBUG_LEVEL_FULL").getInt(null))
        AvfReflect.callOptional(builder, "setVmOutputCaptured", true)
        return AvfReflect.call(builder, "build") ?: error("VirtualMachineConfig build returned null")
    }

    private fun acquire(name: String, config: Any, newMode: String): Any {
        val mgr = initializeManager()
        val configClass = virtClass("android.system.virtualmachine.VirtualMachineConfig")
        var vm = mgr.javaClass.getMethod("getOrCreate", String::class.java, configClass)
            .invoke(mgr, name, config) ?: error("getOrCreate returned null")

        runCatching { AvfReflect.call(vm, "setConfig", config) }.onFailure { first ->
            appendLine("[vm_config] setConfig rejected for $name: ${rootCause(first).message}; recreating DEV 2 instance")
            runCatching { if (isVmRunning(vm)) AvfReflect.call(vm, "stop") }
            runCatching { AvfReflect.call(mgr, "delete", name) }
            vm = AvfReflect.call(mgr, "create", name, config)
                ?: error("VirtualMachineManager.create returned null")
        }

        managedVm = vm
        mode = newMode
        vmCreation = "PASS"
        failureStage = "none"
        appendLine("[vm_creation] PASS name=$name mode=$newMode")
        attachConsole(vm)
        return vm
    }

    private fun statusValue(vm: Any): Int = (AvfReflect.call(vm, "getStatus") as Number).toInt()

    private fun runningValue(): Int =
        virtClass("android.system.virtualmachine.VirtualMachine").getField("STATUS_RUNNING").getInt(null)

    private fun isVmRunning(vm: Any?): Boolean =
        vm != null && runCatching { statusValue(vm) == runningValue() }.getOrDefault(false)

    private fun waitUntilRunning(vm: Any, timeoutMs: Long, stageName: String, settleMs: Long = 800L) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (isVmRunning(vm)) {
                if (settleMs > 0) Thread.sleep(settleMs)
                if (!isVmRunning(vm)) {
                    appendLine("[$stageName] VM entered RUNNING then exited; continuing wait")
                    Thread.sleep(200)
                    continue
                }
                running = true
                if (mode == "microdroid") vmBoot = "PASS" else debianBoot = "PASS"
                failureStage = "none"
                appendLine("[$stageName] PASS status=RUNNING mode=$mode")
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting ${timeoutMs}ms for stable VirtualMachine STATUS_RUNNING")
    }

    private fun attachConsole(vm: Any) {
        consoleThread?.interrupt()
        consoleInput = runCatching { AvfReflect.callOptional(vm, "getConsoleInput") as? OutputStream }.getOrNull()
        val output = runCatching { AvfReflect.callOptional(vm, "getConsoleOutput") as? InputStream }.getOrNull() ?: return

        consoleThread = Thread({
            try {
                val buffer = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val n = output.read(buffer)
                    if (n < 0) break
                    if (n > 0) appendRaw(String(buffer, 0, n, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                if (!Thread.currentThread().isInterrupted) appendLine("[console] ended: ${t.message}")
            }
        }, "dev2-vm-console").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun ensureGateVmRunning(): Any {
        val existing = managedVm
        if (mode == "microdroid" && existing != null && isVmRunning(existing)) return existing

        if (mode == "debian" && isVmRunning(existing)) stopInternal()

        val vm = if (mode == "microdroid" && existing != null) existing
        else acquire(gateVmName, buildGateConfig(), "microdroid")

        if (!isVmRunning(vm)) {
            attachConsole(vm)
            appendLine("[vm_boot] invoking VirtualMachine.run()")
            AvfReflect.call(vm, "run")
            waitUntilRunning(vm, 45_000L, "vm_boot", settleMs = 1200L)
        }
        return vm
    }

    @Synchronized
    override fun startVm(): String {
        try {
            ensureGateVmRunning()
            return status()
        } catch (t: Throwable) {
            if (failureStage == "none") fail("vm_boot", t) else throw t
        }
    }

    @Synchronized
    override fun stopVm(): String {
        try {
            stopInternal()
        } catch (t: Throwable) {
            fail("vm_stop", t)
        }
        return status()
    }

    private fun stopInternal() {
        clearDisplaySurfaceInternal()
        managedVm?.let { if (isVmRunning(it)) AvfReflect.call(it, "stop") }
        running = false
        consoleThread?.interrupt()
        consoleThread = null
        consoleInput = null
        appendLine("[vm_lifecycle] stopped managed VM mode=$mode")
    }

    private fun connectAdb(vm: Any): ParcelFileDescriptor {
        connectVsock = "PENDING"
        vsockFdReceived = "PENDING"
        val deadline = System.nanoTime() + 30_000_000_000L
        var attempt = 0
        var last: Throwable? = null

        while (System.nanoTime() < deadline) {
            attempt++
            if (!isVmRunning(vm)) error("VM stopped while waiting for guest vsock endpoint")
            try {
                val pfd = AvfReflect.call(vm, "connectVsock", 5555L) as? ParcelFileDescriptor
                    ?: error("VirtualMachine.connectVsock returned no ParcelFileDescriptor")
                connectVsock = "PASS"
                vsockFdReceived = "PASS"
                failureStage = "none"
                appendLine("[connect_vsock] PASS port=5555 fd=${pfd.fd} attempt=$attempt")
                return pfd
            } catch (t: Throwable) {
                last = rootCause(t)
                if (attempt == 1 || attempt % 5 == 0) {
                    appendLine("[connect_vsock] waiting attempt=$attempt: ${last.message}")
                }
                Thread.sleep(if (attempt < 5) 300L else 750L)
            }
        }
        throw last ?: IllegalStateException("Timed out waiting for guest vsock endpoint")
    }

    @Synchronized
    override fun guestShell(command: String): String {
        require(command.isNotBlank() && command.length <= 4096)
        return try {
            val vm = ensureGateVmRunning()
            connectAdb(vm).use { pfd ->
                val output = NativeTransport.shellFd(pfd.fd, command)
                adbHandshake = "PASS"
                guestCommand = "PASS"
                failureStage = "none"
                appendLine("[guest_command] PASS command=${command.take(96)}")
                JSONObject().put("ok", true).put("output", output).toString()
            }
        } catch (t: Throwable) {
            val e = rootCause(t)
            connectVsock = if (connectVsock == "PASS") connectVsock else "BLOCKED"
            guestCommand = "BLOCKED"
            failureStage = "guest_command"
            appendLine("[guest_command] BLOCKED ${e.javaClass.name}: ${e.message}")
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.name).toString()
        }
    }

    override fun inspectCapabilities(): String = try {
        val mgr = initializeManager()
        val caps = (AvfReflect.call(mgr, "getCapabilities") as? Number)?.toInt() ?: -1
        val cls = mgr.javaClass
        val pBit = runCatching { cls.getField("CAPABILITY_PROTECTED_VM").getInt(null) }.getOrDefault(1)
        val nBit = runCatching { cls.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null) }.getOrDefault(2)
        val custom = classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig")
        val gpu = classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig\$GpuConfig\$Builder")
        val display = classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig\$DisplayConfig\$Builder")
        val displaySvc = classExists("android.crosvm.ICrosvmAndroidDisplayService\$Stub")
        val internalSvc = classExists("android.system.virtualizationservice_internal.IVirtualizationServiceInternal\$Stub")
        val jarCount = File("/apex/com.android.virt/javalib").listFiles()?.count { it.extension == "jar" } ?: 0

        capabilitiesText =
            "caps=$caps pVM=${caps >= 0 && caps and pBit != 0} nonPVM=${caps >= 0 && caps and nBit != 0} " +
                "custom=$custom gpuApi=$gpu displayApi=$display displaySvc=$displaySvc internalSvc=$internalSvc virtJars=$jarCount"

        appendLine("[capabilities] $capabilitiesText")
        JSONObject()
            .put("ok", true)
            .put("capabilities", caps)
            .put("protectedVm", caps >= 0 && caps and pBit != 0)
            .put("nonProtectedVm", caps >= 0 && caps and nBit != 0)
            .put("customImageApi", custom)
            .put("gpuConfigApi", gpu)
            .put("displayConfigApi", display)
            .put("displayServiceApi", displaySvc)
            .put("internalServiceApi", internalSvc)
            .put("text", capabilitiesText)
            .toString()
    } catch (t: Throwable) {
        val e = rootCause(t)
        JSONObject().put("ok", false).put("error", "${e.javaClass.name}: ${e.message}").toString()
    }

    private fun debianImage() = DebianImage(
        debianDir,
        onProgress = { done, total ->
            installDone = done
            installTotal = total
        },
        onStage = { appendLine("[$it]") },
        onLog = ::appendLine,
    )

    override fun installDebian(): String {
        if (debianImage().installed()) return status()
        if (!installing.compareAndSet(false, true)) return status()

        installError = ""
        installDone = 0
        installTotal = -1

        Thread({
            try {
                debianImage().install()
            } catch (t: Throwable) {
                val e = rootCause(t)
                installError = "${e.javaClass.name}: ${e.message}"
                appendLine("[debian_install] BLOCKED $installError")
            } finally {
                installing.set(false)
            }
        }, "dev2-debian-installer").also {
            it.isDaemon = true
            it.start()
        }
        return status()
    }

    @Synchronized
    override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String {
        try {
            check(debianImage().installed()) { "Install Debian first" }
            val caps = JSONObject(inspectCapabilities())
            check(caps.optBoolean("ok")) { caps.optString("error") }
            check(caps.optBoolean("customImageApi")) { "VirtualMachineCustomImageConfig API is missing" }

            val supportsPvm = caps.optBoolean("protectedVm")
            val supportsNonPvm = caps.optBoolean("nonProtectedVm")
            check(supportsPvm || supportsNonPvm) { "AVF reports neither protected nor non-protected VM support" }

            // Use Google's intended non-protected mode where available. On this POCO only pVM is
            // available, so explicitly probe whether the official custom image can boot as a pVM.
            debianProtected = !supportsNonPvm && supportsPvm
            val requestGraphics = caps.optBoolean("gpuConfigApi") && caps.optBoolean("displayConfigApi")
            val vmName = if (debianProtected) debianPvmName else debianNonPvmName

            if (mode == "microdroid" && isVmRunning(managedVm)) stopInternal()

            val config = DebianAvfConfig.build(
                scopedContext,
                debianDir,
                vmName,
                width,
                height,
                dpi,
                refreshRate,
                protectedVm = debianProtected,
                requestGraphics = requestGraphics,
                log = ::appendLine,
            )

            val vm = acquire(vmName, config, "debian")
            debianBoot = "PENDING"
            graphicsState = if (requestGraphics) "CONFIGURED_UNPROVEN" else "API_UNAVAILABLE"
            displayState = if (requestGraphics) "PENDING" else "API_UNAVAILABLE"

            if (!isVmRunning(vm)) {
                attachConsole(vm)
                appendLine("[debian_boot] invoking VirtualMachine.run() protected=$debianProtected")
                AvfReflect.call(vm, "run")
            }

            waitUntilRunning(vm, 75_000L, "debian_boot", settleMs = 1500L)
            appendLine("[debian_boot] stable protected=$debianProtected")
            return status()
        } catch (t: Throwable) {
            fail("debian_boot", t)
        }
    }

    private fun console() = DebianConsole({ consoleInput }, ::logSnapshot, ::appendLine)

    override fun debianConsole(command: String): String {
        require(command.isNotBlank() && command.length <= 16384)
        return try {
            check(mode == "debian" && isVmRunning(managedVm)) { "Debian VM is not running" }
            val output = console().command(command, 180_000).getOrThrow()
            if (command.contains("os-release")) debianIdentity = "PASS"
            JSONObject().put("ok", true).put("output", output).toString()
        } catch (t: Throwable) {
            val e = rootCause(t)
            failureStage = "debian_console"
            appendLine("[debian_console] ${e.javaClass.name}: ${e.message}")
            JSONObject().put("ok", false).put("error", "${e.javaClass.name}: ${e.message}").toString()
        }
    }

    private fun getDisplayService(): Any {
        displayService?.let { return it }

        val sm = virtClass("android.os.ServiceManager")
        val binder = sm.getMethod("waitForService", String::class.java)
            .invoke(null, "android.system.virtualizationservice") as? IBinder
            ?: error("virtualizationservice binder unavailable")

        val internalStub = virtClass(
            "android.system.virtualizationservice_internal.IVirtualizationServiceInternal\$Stub"
        )
        val internal = internalStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("IVirtualizationServiceInternal unavailable")

        runCatching { AvfReflect.callOptional(internal, "clearDisplayService") }
        val displayBinder = AvfReflect.call(internal, "waitDisplayService") as? IBinder
            ?: error("crosvm display service unavailable")

        val displayStub = virtClass("android.crosvm.ICrosvmAndroidDisplayService\$Stub")
        return displayStub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, displayBinder)
            ?.also { displayService = it }
            ?: error("ICrosvmAndroidDisplayService unavailable")
    }

    override fun setDisplaySurface(surface: Surface) {
        try {
            check(mode == "debian" && isVmRunning(managedVm)) { "Debian VM is not running" }
            val service = getDisplayService()
            AvfReflect.call(service, "setSurface", surface, false)
            AvfReflect.callOptional(service, "drawSavedFrameForSurface", false)
            displayState = "PASS"
            failureStage = "none"
            appendLine("[display] PASS Android Surface attached to crosvm")
        } catch (t: Throwable) {
            val cause = rootCause(t)
            displayState = "BLOCKED"
            graphicsState = if (graphicsState == "CONFIGURED_UNPROVEN") "UNPROVEN" else graphicsState
            failureStage = "display"
            appendLine("[display] BLOCKED ${cause.javaClass.name}: ${cause.message}")
            throw IllegalStateException("display: ${cause.message}", cause)
        }
    }

    override fun clearDisplaySurface() {
        runCatching { clearDisplaySurfaceInternal() }
    }

    private fun clearDisplaySurfaceInternal() {
        val service = displayService ?: return
        runCatching { AvfReflect.callOptional(service, "saveFrameForSurface", false) }
        runCatching { AvfReflect.callOptional(service, "removeSurface", false) }
        displayService = null
    }

    override fun sendKey(action: Int, keyCode: Int, scanCode: Int, metaState: Int): Boolean {
        val vm = managedVm ?: return false
        if (mode != "debian" || !isVmRunning(vm)) return false

        val effectiveScanCode = if (scanCode != 0) scanCode else when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> 1
            KeyEvent.KEYCODE_TAB -> 15
            KeyEvent.KEYCODE_CTRL_LEFT -> 29
            KeyEvent.KEYCODE_ALT_LEFT -> 56
            else -> 0
        }

        return runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            val event = KeyEvent(
                now,
                now,
                action,
                keyCode,
                0,
                metaState,
                KeyEvent.KEYCODE_UNKNOWN,
                effectiveScanCode,
                0,
                android.view.InputDevice.SOURCE_KEYBOARD,
            )
            AvfReflect.call(vm, "sendKeyEvent", event)
            true
        }.getOrDefault(false)
    }

    override fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean {
        val vm = managedVm ?: return false
        if (mode != "debian" || !isVmRunning(vm)) return false

        return runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                AvfReflect.call(vm, "sendMultiTouchEvent", event)
            } finally {
                event.recycle()
            }
            true
        }.getOrDefault(false)
    }

    override fun installKde(): String {
        check(mode == "debian" && isVmRunning(managedVm)) { "Start Debian first" }
        if (kdeMarker().isFile) {
            kdeState = "PASS"
            return status()
        }
        if (!kdeInstalling.compareAndSet(false, true)) return status()

        kdeStage = "starting"
        kdeError = ""
        kdeState = "PENDING"

        Thread({
            try {
                Thread.sleep(3000)
                debianIdentity = "PENDING"
                console().command("cat /etc/os-release; id; command -v apt-get", 120_000).getOrThrow()
                debianIdentity = "PASS"

                if (debianProtected) {
                    appendLine("[kde] pVM networking is disabled; checking whether Plasma is already present")
                    val existing = console().command(
                        "command -v startplasma-wayland || command -v kwin_wayland || true",
                        60_000
                    ).getOrThrow()
                    check(existing.contains("startplasma-wayland") || existing.contains("kwin_wayland")) {
                        "Protected Debian booted, but Plasma is not preinstalled and standard pVM networking is unavailable. Host-mediated networking is required before apt installation."
                    }
                } else {
                    kdeStage = "installing Plasma 6"
                    console().command(KDE_INSTALL, 30L * 60L * 1000L).getOrThrow()
                }

                kdeStage = "starting Plasma Wayland"
                console().command(KDE_START, 240_000).getOrThrow()
                kdeMarker().writeText("DEV-2-LINUX\n")
                kdeStage = "ready"
                kdeState = "PASS"

                val probe = console().command(GPU_PROBE, 120_000).getOrThrow()
                graphicsState = when {
                    probe.contains("llvmpipe", true) || probe.contains("softpipe", true) -> "SOFTWARE_FALLBACK"
                    probe.contains("venus", true) || probe.contains("virtio", true) ||
                        probe.contains("gfxstream", true) -> "GUEST_RENDERER_REPORTED"
                    else -> "UNPROVEN"
                }
                appendLine("[kde] PASS Plasma launched; graphics=$graphicsState")
            } catch (t: Throwable) {
                val e = rootCause(t)
                kdeError = "${e.javaClass.name}: ${e.message}"
                kdeStage = "blocked"
                kdeState = "BLOCKED"
                appendLine("[kde] BLOCKED $kdeError")
            } finally {
                kdeInstalling.set(false)
            }
        }, "dev2-kde-installer").also {
            it.isDaemon = true
            it.start()
        }
        return status()
    }

    private fun kdeMarker() = File(debianDir, ".dev2-kde-installed")

    @Synchronized
    override fun status(): String {
        running = isVmRunning(managedVm)
        val total = installTotal
        val progress =
            if (total > 0) (installDone.toDouble() / total).coerceIn(0.0, 1.0) else -1.0

        return JSONObject()
            .put("name", when (mode) {
                "debian" -> if (debianProtected) debianPvmName else debianNonPvmName
                else -> gateVmName
            })
            .put("mode", mode)
            .put("running", running)
            .put("cid", -1)
            .put("log", logSnapshot())
            .put("avfApiPath", "android.system.virtualmachine.VirtualMachineManager")
            .put("vmApiInit", vmApiInit)
            .put("vmDataDir", scopedContext.dataDir.absolutePath)
            .put("vmCreation", vmCreation)
            .put("vmBoot", vmBoot)
            .put("connectVsock", connectVsock)
            .put("vsockFdReceived", vsockFdReceived)
            .put("adbHandshake", adbHandshake)
            .put("guestCommand", guestCommand)
            .put("failureStage", failureStage)
            .put("capabilities", capabilitiesText)
            .put("debianInstalled", debianImage().installed())
            .put("debianInstalling", installing.get())
            .put("installBytes", installDone)
            .put("installTotal", installTotal)
            .put("installProgress", progress)
            .put("installError", installError)
            .put("debianBoot", debianBoot)
            .put("debianIdentity", debianIdentity)
            .put("debianProtected", debianProtected)
            .put("display", displayState)
            .put("guestGraphics", graphicsState)
            .put("kdeInstalled", kdeMarker().isFile)
            .put("kdeInstalling", kdeInstalling.get())
            .put("kdeStage", kdeStage)
            .put("kdeError", kdeError)
            .toString()
    }

    override fun destroy() {
        runCatching { stopInternal() }
        runCatching { (managedVm as? AutoCloseable)?.close() }
        kotlin.system.exitProcess(0)
    }

    private class ShellVmContext(base: Context, root: File) : ContextWrapper(base) {
        private val data = File(root, "data").apply { mkdirs() }
        private val files = File(data, "files").apply { mkdirs() }
        private val cache = File(data, "cache").apply { mkdirs() }
        private val noBackup = File(data, "no_backup").apply { mkdirs() }
        private val codeCache = File(data, "code_cache").apply { mkdirs() }

        override fun getDataDir(): File = data
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getCodeCacheDir(): File = codeCache
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private fun resolveApplicationContext(): Context {
            val at = Class.forName("android.app.ActivityThread")
            return at.getMethod("currentApplication").invoke(null) as? Context
                ?: error("Shizuku UserService did not provide application Context")
        }

        private val KDE_INSTALL = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y plasma-desktop plasma-workspace kwin-wayland konsole dolphin xwayland dbus-user-session mesa-utils vulkan-tools
            for f in /usr/local/bin/enable_display /usr/local/bin/enable_gfxstream; do
              [ -f "${'$'}f" ] && sed -i '/systemctl --user start weston/d' "${'$'}f" || true
            done
        """.trimIndent()

        private val KDE_START = """
            set -eu
            pkill weston || true
            uid=${'$'}(id -u droid 2>/dev/null || echo 1000)
            install -d -m 700 -o droid -g droid /run/user/${'$'}uid
            runuser -u droid -- env XDG_RUNTIME_DIR=/run/user/${'$'}uid dbus-run-session bash -lc '
              if [ -f /usr/local/bin/enable_gfxstream ]; then source /usr/local/bin/enable_gfxstream || true; elif [ -f /usr/local/bin/enable_display ]; then source /usr/local/bin/enable_display || true; fi
              export XDG_SESSION_TYPE=wayland QT_QPA_PLATFORM=wayland
              exec startplasma-wayland
            ' >/tmp/dev2-plasma.log 2>&1 &
            sleep 8
            pgrep -a kwin_wayland || pgrep -a plasmashell || { cat /tmp/dev2-plasma.log; exit 1; }
        """.trimIndent()

        private val GPU_PROBE = """
            echo '=== DRM ==='; ls -l /dev/dri 2>&1 || true
            echo '=== VULKAN ==='; vulkaninfo --summary 2>&1 | head -100 || true
            echo '=== GL ==='; glxinfo -B 2>&1 | head -80 || true
            echo '=== PROCS ==='; pgrep -a kwin_wayland || true; pgrep -a plasmashell || true
        """.trimIndent()
    }
}

object NativeTransport {
    init {
        System.loadLibrary("dream_transport")
    }

    external fun shellFd(fd: Int, command: String): String
}
