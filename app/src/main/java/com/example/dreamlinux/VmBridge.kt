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
    // Fresh instance name because the Gate-A payload now correctly reports payload-ready.
    // Protected Microdroid instance state must not be reused across payload changes.
    private val microVmName = "dev1-gate-a-v2"
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

    private val kdeInstalling = AtomicBoolean(false)
    @Volatile private var kdeStage = "not installed"
    @Volatile private var kdeError = ""

    private val lock = Any()
    private fun append(value: String) = synchronized(lock) {
        log = (log + value + "\n").takeLast(256000)
    }
    private fun logSnapshot(): String = synchronized(lock) { log }

    private class RedirectedDataContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getDataDir(): File = root
        override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        override fun getCodeCacheDir(): File = File(root, "code_cache").also { it.mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").also { it.mkdirs() }
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
        context.noBackupFilesDir.mkdirs()
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
    private fun stoppedStatus(machine: Any): Int = runCatching { machine.javaClass.getField("STATUS_STOPPED").getInt(null) }.getOrDefault(0)
    private fun deletedStatus(machine: Any): Int = runCatching { machine.javaClass.getField("STATUS_DELETED").getInt(null) }.getOrDefault(-1)
    private fun isRunning(machine: Any?): Boolean = machine != null && runCatching {
        vmStatus(machine) == runningStatus(machine)
    }.getOrDefault(false)

    private fun waitUntilRunning(machine: Any, mode: String, timeoutMs: Long) {
        stage = "vm_wait_running:$mode"
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var last = Int.MIN_VALUE
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val current = vmStatus(machine)
            if (current != last) {
                append("[$mode] VM status=$current waiting for STATUS_RUNNING=${runningStatus(machine)}")
                last = current
            }
            if (current == runningStatus(machine)) {
                stage = "running:$mode"
                append("[$mode] PASS status=RUNNING")
                return
            }
            if (current == deletedStatus(machine)) error("$mode VM became deleted before reaching RUNNING")
            Thread.sleep(250)
        }
        error("Timed out waiting ${timeoutMs}ms for $mode VM STATUS_RUNNING; lastStatus=$last stopped=${stoppedStatus(machine)}")
    }

    private fun buildMicrodroid(context: Context): Any {
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builder = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        AvfReflect.call(builder, "setProtectedVm", true)
        AvfReflect.call(builder, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        AvfReflect.call(builder, "setMemoryBytes", 512L * 1024L * 1024L)
        runCatching { AvfReflect.call(builder, "setVmOutputCaptured", true) }
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
            runCatching { AvfReflect.call(mgr, "delete", name) }
            machine = AvfReflect.call(mgr, "create", name, config) ?: error("create returned null")
        }
        vm = machine
        vmMode = mode
        append("managed VM acquired name=$name mode=$mode status=${runCatching { vmStatus(machine) }.getOrDefault(-999)}")
        return machine
    }

    private fun startMachine(machine: Any, mode: String) {
        if (!isRunning(machine)) {
            stage = "vm_start:$mode"
            append("[$mode] invoking VirtualMachine.run()")
            AvfReflect.call(machine, "run")
            append("VirtualMachine.run() accepted for $mode")
        }
        waitUntilRunning(machine, mode, if (mode == "debian") 60_000L else 45_000L)
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
            .put("kdeInstalled", kdeMarker().isFile)
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

    private fun console(): DebianConsole = DebianConsole(
        input = { consoleInput },
        log = ::logSnapshot,
        onLog = ::append,
    )

    override fun debianConsole(command: String): String {
        require(command.length <= 16384) { "Command too long" }
        check(vmMode == "debian" && isRunning(vm)) { "Debian VM is not running" }
        stage = "debian_console"
        val result = console().command(command)
        return result.fold(
            onSuccess = { output ->
                stage = "debian_console_pass"
                JSONObject().put("ok", true).put("output", output).toString()
            },
            onFailure = { error ->
                val e = AvfReflect.unwrap(error)
                lastError = "${e.javaClass.name}: ${e.message}"
                stage = "blocked:debian_console"
                append(lastError)
                JSONObject().put("ok", false).put("error", lastError).toString()
            },
        )
    }

    override fun installKde(): String {
        check(vmMode == "debian" && isRunning(vm)) { "Start Debian before installing KDE" }
        if (kdeMarker().isFile) return status()
        if (!kdeInstalling.compareAndSet(false, true)) return status()
        kdeError = ""
        kdeStage = "waiting for Debian console"
        Thread({
            try {
                Thread.sleep(4_000)
                stage = "kde_probe"
                console().command("cat /etc/os-release; id; command -v apt-get", 90_000).getOrThrow()

                kdeStage = "installing Plasma 6 packages"
                stage = "kde_packages"
                console().command(KDE_INSTALL_COMMAND, 30L * 60L * 1000L).getOrThrow()

                kdeStage = "starting Plasma Wayland"
                stage = "kde_start"
                console().command(KDE_START_COMMAND, 180_000).getOrThrow()

                kdeMarker().parentFile?.mkdirs()
                kdeMarker().writeText("installed-by=DEV-1-LINUX\n")
                kdeStage = "ready"
                stage = "kde_ready"
                append("KDE Plasma provisioning completed; Plasma Wayland service enabled for droid")
            } catch (t: Throwable) {
                val e = AvfReflect.unwrap(t)
                kdeError = "${e.javaClass.name}: ${e.message}"
                kdeStage = "blocked"
                lastError = kdeError
                stage = "blocked:kde_install"
                append(kdeError)
            } finally {
                kdeInstalling.set(false)
            }
        }, "dev1-kde-installer").also { it.isDaemon = true; it.start() }
        return status()
    }

    private fun kdeMarker() = File(debianDir, ".dev1-kde-installed")

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

        // Guard inside the bridge itself. The Android service is not trusted as the lifecycle authority.
        if (!isRunning(machine)) {
            append("[guest_command] VM not running at entry; waiting for real AVF status")
            waitUntilRunning(machine, "microdroid", 45_000L)
        }

        stage = "connect_vsock"
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        var attempt = 0
        var last: Throwable? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++
            if (!isRunning(machine)) {
                last = IllegalStateException("VM left STATUS_RUNNING while waiting for guest endpoint")
                append("[connect_vsock] attempt=$attempt VM no longer RUNNING; waiting briefly")
                Thread.sleep(300)
                continue
            }
            try {
                val method = machine.javaClass.methods.firstOrNull { it.name == "connectVsock" && it.parameterCount == 1 }
                    ?: error("VirtualMachine.connectVsock not found")
                val type = method.parameterTypes[0]
                val arg: Any = if (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) 5555L else 5555
                val pfd = method.invoke(machine, arg) as ParcelFileDescriptor
                append("[connect_vsock] PASS port=5555 fd=${pfd.fd} attempt=$attempt")
                pfd.use {
                    stage = "adb_shell"
                    val output = NativeTransport.shellFd(it.fd, command)
                    stage = "guest_command_pass"
                    lastError = ""
                    append("[guest_command] PASS command=${command.take(120)}")
                    return JSONObject().put("ok", true).put("output", output).toString()
                }
            } catch (t: Throwable) {
                last = AvfReflect.unwrap(t)
                if (attempt == 1 || attempt % 5 == 0) {
                    append("[connect_vsock] waiting attempt=$attempt: ${last.javaClass.name}: ${last.message}")
                }
                Thread.sleep(if (attempt < 5) 300L else 750L)
            }
        }
        val e = last ?: IllegalStateException("connectVsock timed out")
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
        val rawStatus = vm?.let { runCatching { vmStatus(it) }.getOrDefault(-999) } ?: -999
        val total = installTotalBytes
        val progress = if (total > 0) (installDoneBytes.toDouble() / total).coerceIn(0.0, 1.0) else -1.0
        return JSONObject()
            .put("name", if (vmMode == "debian") debianVmName else microVmName)
            .put("running", running)
            .put("rawVmStatus", rawStatus)
            .put("cid", -1)
            .put("managed", vm != null)
            .put("mode", vmMode)
            .put("stage", stage)
            .put("api", "VirtualMachineManager/connectVsock/custom-image")
            .put("vmRoot", root)
            .put("dataDir", vmData.path)
            .put("error", lastError)
            .put("log", logSnapshot())
            .put("debianInstalled", debianImage().installed())
            .put("debianInstalling", installing.get())
            .put("installBytes", installDoneBytes)
            .put("installTotal", installTotalBytes)
            .put("installProgress", progress)
            .put("installError", installError)
            .put("kdeInstalled", kdeMarker().isFile)
            .put("kdeInstalling", kdeInstalling.get())
            .put("kdeStage", kdeStage)
            .put("kdeError", kdeError)
            .put("guestGraphics", if (stage == "display_attached" || stage == "kde_ready") "gfxstream configured; hardware proof pending" else "unproven")
            .put("debian", if (debianImage().installed()) "official AVF Debian image installed" else "not installed")
            .toString()
    }

    override fun destroy() {
        runCatching { stopInternal() }
        kotlin.system.exitProcess(0)
    }

    companion object {
        private const val PACKAGE = "com.example.dreamlinux"

        private val KDE_INSTALL_COMMAND = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            . /etc/os-release
            echo "DEV1 Debian: ${'$'}PRETTY_NAME"
            apt-get update
            apt-get install -y plasma-desktop plasma-workspace plasma-workspace-wayland kwin-wayland konsole dolphin xwayland dbus-user-session mesa-utils vulkan-tools
            for f in /usr/local/bin/enable_display /usr/local/bin/enable_gfxstream; do
              if [ -f "${'$'}f" ]; then sed -i '/systemctl --user start weston/d' "${'$'}f"; fi
            done
            loginctl enable-linger droid || true
            install -d -m 700 -o droid -g droid /home/droid/.config/systemd/user
            cat >/home/droid/.config/systemd/user/dev1-plasma.service <<'EOF'
            [Unit]
            Description=DEV 1 LINUX Plasma Wayland
            After=default.target
            [Service]
            Type=simple
            Environment=XDG_SESSION_TYPE=wayland
            Environment=QT_QPA_PLATFORM=wayland
            Environment=KWIN_DRM_NO_AMS=1
            ExecStart=/bin/bash -lc 'if [ -f /usr/local/bin/enable_gfxstream ]; then source /usr/local/bin/enable_gfxstream || true; elif [ -f /usr/local/bin/enable_display ]; then source /usr/local/bin/enable_display || true; fi; exec /usr/bin/startplasma-wayland'
            Restart=on-failure
            RestartSec=3
            [Install]
            WantedBy=default.target
            EOF
            chown -R droid:droid /home/droid/.config
        """.trimIndent()

        private val KDE_START_COMMAND = """
            set -eu
            uid=$(id -u droid)
            install -d -m 700 -o droid -g droid /run/user/${'$'}uid
            runuser -u droid -- env XDG_RUNTIME_DIR=/run/user/${'$'}uid DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/${'$'}uid/bus systemctl --user daemon-reload || true
            runuser -u droid -- env XDG_RUNTIME_DIR=/run/user/${'$'}uid DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/${'$'}uid/bus systemctl --user enable --now dev1-plasma.service || true
            sleep 3
            pgrep -a kwin_wayland || pgrep -a plasmashell || systemctl --user --machine=droid@ status dev1-plasma.service --no-pager || true
            vulkaninfo --summary 2>/dev/null | head -80 || true
        """.trimIndent()
    }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd: Int, command: String): String
}
