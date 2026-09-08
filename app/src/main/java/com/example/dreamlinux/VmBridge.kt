package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Shizuku-side AVF controller. The app never creates AF_VSOCK directly.
 * Microdroid uses VirtualMachine.connectVsock(); Debian uses AVF's custom-image/display APIs.
 */
class VmBridge : IVmBridge.Stub() {
    private val microVmName = "dev1-gate-a"
    private val debianVmName = "dev1-debian13"
    private val vmData = File("/data/local/tmp/dev1-linux-vmm")
    private val debianDir = File("/data/local/tmp/dev1-linux/debian")

    private var manager: Any? = null
    private var vm: Any? = null
    private var vmMode = "none"
    private var stage = "idle"
    private var log = ""
    private var lastError = ""
    private var consoleThread: Thread? = null
    private var consoleInput: OutputStream? = null
    private var displayService: Any? = null

    private val installing = AtomicBoolean(false)
    @Volatile private var installDoneBytes = 0L
    @Volatile private var installTotalBytes = -1L
    @Volatile private var installError = ""

    private val lock = Any()
    private fun append(value: String) = synchronized(lock) {
        log = (log + value + "\n").takeLast(256000)
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
        val current = runCatching {
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        if (current != null && current.packageName == PACKAGE) return current

        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: error("ActivityThread.currentActivityThread() returned null")
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        return system.createPackageContext(PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun redirectedContext(): Context {
        check(vmData.mkdirs() || vmData.isDirectory) { "Cannot create ${vmData.path}" }
        val context = RedirectedDataContext(baseContext(), vmData)
        context.filesDir.mkdirs()
        append("context package=${context.packageName} uid=${android.os.Process.myUid()} dataDir=${context.dataDir}")
        return context
    }

    private fun manager(context: Context = redirectedContext()): Any {
        manager?.let { return it }
        stage = "manager_init"
        val type = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val created = type.getConstructor(Context::class.java).newInstance(context)
        manager = created
        append("VirtualMachineManager created with shell-writable redirected context")
        return created
    }

    private fun vmStatus(machine: Any): Int = (AvfReflect.call(machine, "getStatus") as Number).toInt()
    private fun runningStatus(machine: Any): Int = machine.javaClass.getField("STATUS_RUNNING").getInt(null)
    private fun isRunning(machine: Any?): Boolean = machine != null && runCatching {
        vmStatus(machine) == runningStatus(machine)
    }.getOrDefault(false)

    private fun buildMicrodroid(context: Context): Any {
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builder = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        AvfReflect.call(builder, "setProtectedVm", true)
        AvfReflect.call(builder, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        AvfReflect.call(builder, "setMemoryBytes", 512L * 1024L * 1024L)
        AvfReflect.call(builder, "setPayloadBinaryName", "libdev1_payload.so")
        return AvfReflect.call(builder, "build") ?: error("VirtualMachineConfig build returned null")
    }

    private fun acquireVm(name: String, config: Any, mode: String): Any {
        val mgr = manager()
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        stage = "vm_create:$mode"
        var machine = mgr.javaClass.getMethod("getOrCreate", String::class.java, configClass)
            .invoke(mgr, name, config) ?: error("getOrCreate returned null")
        try {
            AvfReflect.call(machine, "setConfig", config)
        } catch (t: Throwable) {
            append("setConfig unavailable/rejected (${AvfReflect.unwrap(t).message}); recreating only $name")
            runCatching { AvfReflect.call(machine, "stop") }
            AvfReflect.call(mgr, "delete", name)
            machine = AvfReflect.call(mgr, "create", name, config) ?: error("create returned null")
        }
        vm = machine
        vmMode = mode
        append("managed VM acquired name=$name mode=$mode")
        return machine
    }

    private fun startMachine(machine: Any, mode: String) {
        if (!isRunning(machine)) {
            stage = "vm_start:$mode"
            AvfReflect.call(machine, "run")
            append("VirtualMachine.run() accepted for $mode")
        }
        attachConsole(machine)
        stage = "running:$mode"
    }

    private fun attachConsole(machine: Any) {
        consoleThread?.interrupt()
        consoleInput = runCatching { AvfReflect.callOptional(machine, "getConsoleInput") as? OutputStream }.getOrNull()
        val stream = runCatching { AvfReflect.callOptional(machine, "getConsoleOutput") as? InputStream }.getOrNull()
            ?: return
        consoleThread = Thread({
            try {
                val buffer = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) append(String(buffer, 0, count, Charsets.UTF_8).trimEnd())
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
            startMachine(acquireVm(microVmName, buildMicrodroid(context), "microdroid"), "microdroid")
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
        consoleInput = null
        vm?.let { machine -> if (isRunning(machine)) AvfReflect.call(machine, "stop") }
        consoleThread?.interrupt()
        consoleThread = null
        stage = "stopped:$vmMode"
        append("VM stopped mode=$vmMode")
    }

    private fun blocked(t: Throwable, explicitStage: String? = null) {
        val e = AvfReflect.unwrap(t)
        lastError = "${e.javaClass.name}: ${e.message}"
        stage = "blocked:${explicitStage ?: stage}"
        append(lastError)
    }

    override fun inspectCapabilities(): String = try {
        val mgr = manager(redirectedContext())
        val caps = (AvfReflect.call(mgr, "getCapabilities") as? Number)?.toInt() ?: -1
        val cls = mgr.javaClass
        val protectedBit = runCatching { cls.getField("CAPABILITY_PROTECTED_VM").getInt(null) }.getOrDefault(1)
        val nonProtectedBit = runCatching { cls.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null) }.getOrDefault(2)
        JSONObject()
            .put("ok", true)
            .put("capabilities", caps)
            .put("protectedVm", caps >= 0 && caps and protectedBit != 0)
            .put("nonProtectedVm", caps >= 0 && caps and nonProtectedBit != 0)
            .put("customImageApi", classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig"))
            .put("gpuConfigApi", classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig\$GpuConfig\$Builder"))
            .put("displayConfigApi", classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig\$DisplayConfig\$Builder"))
            .put("displayServiceApi", classExists("android.crosvm.ICrosvmAndroidDisplayService\$Stub"))
            .put("debianInstalled", debianImage().installed())
            .toString()
    } catch (t: Throwable) {
        val e = AvfReflect.unwrap(t)
        JSONObject().put("ok", false).put("error", "${e.javaClass.name}: ${e.message}").toString()
    }

    private fun classExists(name: String) = runCatching { Class.forName(name) }.isSuccess

    private fun debianImage() = DebianImage(
        debianDir,
        onProgress = { done, total -> installDoneBytes = done; installTotalBytes = total },
        onStage = { stage = it },
        onLog = ::append,
    )

    override fun installDebian(): String {
        val image = debianImage()
        if (image.installed()) return status()
        if (!installing.compareAndSet(false, true)) return status()
        installError = ""
        installDoneBytes = 0L
        installTotalBytes = -1L
        Thread({
            try {
                image.install()
                stage = "debian_installed"
            } catch (t: Throwable) {
                val e = AvfReflect.unwrap(t)
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

    @Synchronized override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String {
        lastError = ""
        return try {
            check(debianImage().installed()) { "Debian image is not installed yet" }
            if (vmMode == "microdroid" && isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:debian"
            val config = DebianAvfConfig.build(context, debianDir, debianVmName, width, height, dpi, refreshRate, ::append)
            startMachine(acquireVm(debianVmName, config, "debian"), "debian")
            status()
        } catch (t: Throwable) {
            blocked(t)
            status()
        }
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
        val displayBinder = AvfReflect.call(internal, "waitDisplayService") as? IBinder
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
            val service = getDisplayService()
            AvfReflect.call(service, "setSurface", surface, false)
            AvfReflect.callOptional(service, "drawSavedFrameForSurface", false)
            stage = "display_attached"
            append("Android Surface attached to crosvm display service")
        } catch (t: Throwable) {
            blocked(t, "display_attach")
            throw IllegalStateException(lastError, AvfReflect.unwrap(t))
        }
    }

    override fun clearDisplaySurface() {
        try { clearDisplaySurfaceInternal() } catch (t: Throwable) { blocked(t, "display_clear") }
    }

    private fun clearDisplaySurfaceInternal() {
        val service = displayService ?: return
        runCatching { AvfReflect.callOptional(service, "saveFrameForSurface", false) }
        runCatching { AvfReflect.callOptional(service, "removeSurface", false) }
        displayService = null
    }

    override fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean {
        val machine = vm ?: return false
        if (vmMode != "debian" || !isRunning(machine)) return false
        return try {
            val now = android.os.SystemClock.uptimeMillis()
            val event = KeyEvent(now, now, action, keyCode, 0, metaState)
            AvfReflect.call(machine, "sendKeyEvent", event)
            true
        } catch (t: Throwable) {
            blocked(t, "input_key")
            false
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
            try { AvfReflect.call(machine, "sendMultiTouchEvent", event) } finally { event.recycle() }
            true
        } catch (t: Throwable) {
            blocked(t, "input_touch:$pointerId")
            false
        }
    }

    @Synchronized override fun guestShell(command: String): String {
        require(command.length <= 4096) { "Command too long" }
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Managed VM is not created").toString()
        check(vmMode == "microdroid") { "Gate A shell uses the stock Microdroid VM" }
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
                last = AvfReflect.unwrap(t)
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
        val root = vm?.let { machine ->
            runCatching { AvfReflect.callOptional(machine, "getRootDir") as? File }.getOrNull()?.path ?: ""
        } ?: ""
        val total = installTotalBytes
        val progress = if (total > 0) (installDoneBytes.toDouble() / total).coerceIn(0.0, 1.0) else -1.0
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
            .put("debianInstalled", debianImage().installed())
            .put("debianInstalling", installing.get())
            .put("installBytes", installDoneBytes)
            .put("installTotal", installTotalBytes)
            .put("installProgress", progress)
            .put("installError", installError)
            .put("guestGraphics", if (stage == "display_attached") "gfxstream surface attached; hardware proof pending" else "unproven")
            .put("debian", if (debianImage().installed()) "official AVF Debian image installed" else "not installed")
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
