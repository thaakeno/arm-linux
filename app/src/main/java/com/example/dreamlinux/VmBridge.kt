package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Shizuku-side AVF controller.
 *
 * The phone exposes protected AVF but not non-protected AVF. We therefore keep Debian headless,
 * verify the actual guest over its serial console, and use sanctioned host->guest vsock channels
 * for two things that do not require the privileged AOSP Terminal SELinux domain:
 *
 *  1. reverse HTTP/SOCKS Internet proxy for the guest;
 *  2. VNC transport for the Plasma desktop.
 *
 * No direct /apex/.../crosvm execution and no direct AF_VSOCK creation from the Android app.
 */
class VmBridge : IVmBridge.Stub() {
    private val microVmName = "dev1-gate-a-v4"
    private val debianVmName = "dev1-debian13-vnc1"
    private val storageSuffix = if (BuildConfig.LOCAL_TEST) "-localtest" else ""
    private val vmData = File("/data/local/tmp/dev1-linux-vmm$storageSuffix")
    private val debianDir = File("/data/local/tmp/dev1-linux$storageSuffix/debian")

    private var manager: Any? = null
    private var vm: Any? = null
    private var vmMode = "none"
    private var stage = "idle"
    private var log = ""
    private var lastError = ""
    private var consoleThread: Thread? = null
    private var consoleInput: OutputStream? = null
    private var consoleOutput: InputStream? = null

    private val installing = AtomicBoolean(false)
    @Volatile private var installDoneBytes = 0L
    @Volatile private var installTotalBytes = -1L
    @Volatile private var installError = ""

    private val kdeInstalling = AtomicBoolean(false)
    @Volatile private var kdeStage = "not installed"
    @Volatile private var kdeError = ""
    @Volatile private var guestVerified = false
    @Volatile private var internetReady = false
    @Volatile private var internetStage = "not started"
    @Volatile private var vncReady = false

    private val proxyRunning = AtomicBoolean(false)
    private val vncForwardRunning = AtomicBoolean(false)
    private var vncServer: ServerSocket? = null

    private val lock = Any()
    private fun append(value: String) = synchronized(lock) {
        log = (log + value + "\n").takeLast(256000)
    }
    private fun appendConsole(value: String) = synchronized(lock) {
        log = (log + value).takeLast(256000)
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
                append("[$mode] VMM status=RUNNING")
                return
            }
            if (current == deletedStatus(machine)) error("$mode VM became deleted before reaching RUNNING")
            Thread.sleep(250)
        }
        error("Timed out waiting ${timeoutMs}ms for $mode VM STATUS_RUNNING; lastStatus=$last")
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
            append("setConfig rejected (${AvfReflect.unwrap(t).message}); recreating only $name")
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
            attachConsole(machine)
            AvfReflect.call(machine, "run")
            append("VirtualMachine.run() accepted for $mode")
        }
        waitUntilRunning(machine, mode, if (mode == "debian") 60_000L else 45_000L)
    }

    private fun attachConsole(machine: Any) {
        consoleThread?.interrupt()
        runCatching { consoleOutput?.close() }
        consoleInput = runCatching { AvfReflect.callOptional(machine, "getConsoleInput") as? OutputStream }.getOrNull()
        val stream = runCatching { AvfReflect.callOptional(machine, "getConsoleOutput") as? InputStream }.getOrNull() ?: return
        consoleOutput = stream
        consoleThread = Thread({
            try {
                ConsoleCapture.read(stream, ::appendConsole)
            } catch (t: Throwable) {
                if (!Thread.currentThread().isInterrupted) append("console capture ended: ${t.message}")
            }
        }, "dev1-vm-console").also { it.isDaemon = true; it.start() }
    }

    private fun blocked(t: Throwable, explicitStage: String? = null) {
        val e = AvfReflect.unwrap(t)
        lastError = "${e.javaClass.name}: ${e.message}"
        stage = "blocked:${explicitStage ?: stage}"
        append(lastError)
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
        proxyRunning.set(false)
        vncForwardRunning.set(false)
        runCatching { vncServer?.close() }
        vncServer = null
        consoleInput = null
        runCatching { consoleOutput?.close() }
        consoleOutput = null
        vm?.let { machine -> if (isRunning(machine)) AvfReflect.call(machine, "stop") }
        consoleThread?.interrupt()
        consoleThread = null
        guestVerified = false
        internetReady = false
        vncReady = false
        stage = "stopped:$vmMode"
        append("VM stopped mode=$vmMode")
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
            .put("displayServiceApi", false)
            .put("desktopPath", "headless AVF + VNC over sanctioned vsock")
            .put("networkPath", "reverse HTTP/SOCKS proxy over sanctioned vsock")
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

    @Synchronized override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String =
        startDebianMode(width, height, dpi, refreshRate)

    @Synchronized override fun startDebianDiagnostic(): String = startDebianMode(640, 480, 160, 60)

    private fun startDebianMode(width: Int, height: Int, dpi: Int, refreshRate: Int): String {
        lastError = ""
        guestVerified = false
        internetReady = false
        internetStage = "starting"
        return try {
            check(debianImage().installed()) { "Debian image is not installed yet" }
            if (isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:debian"
            append("Using headless Debian path: no privileged crosvm display Binder, no pVM TAP networking")
            val config = DebianAvfConfig.build(
                context, debianDir, debianVmName,
                width, height, dpi, refreshRate,
                ::append, false,
            )
            val machine = acquireVm(debianVmName, config, "debian")
            startMachine(machine, "debian")

            stage = "debian_guest_probe"
            Thread.sleep(1500)
            val probe = console().command(
                "cat /etc/os-release; uname -a; id; command -v python3; test -c /dev/vsock || test -e /dev/vsock || true",
                90_000,
            ).getOrThrow()
            append("[debian_guest] PASS actual guest userspace reached\n${probe.takeLast(6000)}")
            guestVerified = true

            stage = "debian_bridge_bootstrap"
            bootstrapGuestBridge()
            startProxyWorkers(machine)
            startVncForwarder(machine)
            verifyInternet()
            stage = "debian_ready"
            status()
        } catch (t: Throwable) {
            blocked(t, if (stage.startsWith("display")) "display" else stage)
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
                stage = if (guestVerified) "debian_ready" else "debian_console_pass"
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

    override fun openDebianVsock(port: Int): ParcelFileDescriptor {
        require(port in 1..65535) { "Invalid vsock port" }
        val machine = vm ?: error("Debian VM is not created")
        check(vmMode == "debian" && isRunning(machine)) { "Debian VM is not running" }
        val method = machine.javaClass.methods.firstOrNull { it.name == "connectVsock" && it.parameterCount == 1 }
            ?: error("VirtualMachine.connectVsock not found")
        val type = method.parameterTypes[0]
        val arg: Any = if (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) port.toLong() else port
        return method.invoke(machine, arg) as ParcelFileDescriptor
    }

    private fun bootstrapGuestBridge() {
        val script64 = Base64.getEncoder().encodeToString(GUEST_BRIDGE_PY.toByteArray(StandardCharsets.UTF_8))
        val service64 = Base64.getEncoder().encodeToString(GUEST_BRIDGE_SERVICE.toByteArray(StandardCharsets.UTF_8))
        val command = """
            set -eu
            install -d -m 755 /usr/local/lib/dev1
            python3 -c "import base64;open('/usr/local/lib/dev1/bridge.py','wb').write(base64.b64decode('$script64'))"
            chmod 755 /usr/local/lib/dev1/bridge.py
            python3 -c "import base64;open('/etc/systemd/system/dev1-bridge.service','wb').write(base64.b64decode('$service64'))"
            install -d -m 755 /etc/apt/apt.conf.d
            printf '%s\n' 'Acquire::http::Proxy "http://127.0.0.1:3128/";' 'Acquire::https::Proxy "http://127.0.0.1:3128/";' > /etc/apt/apt.conf.d/99dev1-proxy
            printf '%s\n' 'http_proxy=http://127.0.0.1:3128' 'https_proxy=http://127.0.0.1:3128' 'HTTP_PROXY=http://127.0.0.1:3128' 'HTTPS_PROXY=http://127.0.0.1:3128' 'ALL_PROXY=socks5h://127.0.0.1:1080' >> /etc/environment
            systemctl daemon-reload
            systemctl enable --now dev1-bridge.service
            systemctl --no-pager --full status dev1-bridge.service | head -40
        """.trimIndent()
        console().command(command, 120_000).getOrThrow()
        append("Guest reverse proxy/VNC bridge provisioned into Debian root filesystem")
    }

    private fun startProxyWorkers(machine: Any) {
        if (!proxyRunning.compareAndSet(false, true)) return
        internetStage = "host workers starting"
        repeat(8) { index ->
            Thread({
                while (proxyRunning.get() && vmMode == "debian" && isRunning(machine)) {
                    try {
                        val pfd = connectVsock(machine, PROXY_VSOCK_PORT)
                        serveProxyWorker(pfd)
                    } catch (t: Throwable) {
                        if (proxyRunning.get()) {
                            if (index == 0) append("internet worker reconnect: ${AvfReflect.unwrap(t).message}")
                            Thread.sleep(600)
                        }
                    }
                }
            }, "dev1-net-$index").also { it.isDaemon = true; it.start() }
        }
    }

    private fun serveProxyWorker(pfd: ParcelFileDescriptor) {
        val outPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(outPfd.fileDescriptor)
        try {
            val line = readAsciiLine(input, 4096)
            val parts = line.trim().split(' ')
            check(parts.size == 3 && parts[0] == "CONNECT") { "Bad guest proxy request: $line" }
            val host = parts[1]
            val port = parts[2].toInt()
            val remote = Socket()
            try {
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(host, port), 15_000)
                output.write("OK\n".toByteArray())
                output.flush()
                relayDuplex(input, output, remote)
            } catch (t: Throwable) {
                runCatching { output.write("ERR ${t.message}\n".toByteArray()); output.flush() }
                throw t
            } finally {
                runCatching { remote.close() }
            }
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { pfd.close() }
            runCatching { outPfd.close() }
        }
    }

    private fun verifyInternet() {
        internetStage = "testing"
        var last: Throwable? = null
        repeat(12) {
            try {
                Thread.sleep(500)
                val out = console().command(
                    "HTTPS_PROXY=http://127.0.0.1:3128 HTTP_PROXY=http://127.0.0.1:3128 python3 -c \"import urllib.request; r=urllib.request.urlopen('https://deb.debian.org/',timeout=20); print('DEV1_HTTP',r.status)\"",
                    45_000,
                ).getOrThrow()
                if (out.contains("DEV1_HTTP 200") || out.contains("DEV1_HTTP 3")) {
                    internetReady = true
                    internetStage = "ready"
                    append("[internet] PASS Debian reached deb.debian.org through host-mediated vsock proxy")
                    return
                }
            } catch (t: Throwable) {
                last = t
            }
        }
        internetReady = false
        internetStage = "blocked: ${AvfReflect.unwrap(last ?: IllegalStateException("proxy test failed")).message}"
        error("Debian Internet bridge failed: $internetStage")
    }

    override fun installKde(): String {
        check(vmMode == "debian" && isRunning(vm) && guestVerified) { "Start and verify Debian first" }
        if (kdeMarker().isFile) return status()
        if (!internetReady) {
            kdeError = "Internet bridge is not ready"
            kdeStage = "blocked"
            return status()
        }
        if (!kdeInstalling.compareAndSet(false, true)) return status()
        kdeError = ""
        kdeStage = "installing Plasma 6 + TigerVNC"
        Thread({
            try {
                stage = "kde_packages"
                val script64 = Base64.getEncoder().encodeToString(KDE_INSTALL_SCRIPT.toByteArray(StandardCharsets.UTF_8))
                console().command(
                    "python3 -c \"import base64;open('/usr/local/sbin/dev1-install-plasma','wb').write(base64.b64decode('$script64'))\"; chmod 755 /usr/local/sbin/dev1-install-plasma",
                    120_000,
                ).getOrThrow()
                console().command("/usr/local/sbin/dev1-install-plasma", 35L * 60L * 1000L).getOrThrow()
                kdeMarker().parentFile?.mkdirs()
                kdeMarker().writeText("installed-by=DEV-1-LINUX-${BuildConfig.VERSION_NAME}\n")
                kdeStage = "ready"
                vncReady = true
                stage = "kde_ready"
                append("KDE Plasma 6 + TigerVNC provisioning completed; local Android VNC forward is 127.0.0.1:$ANDROID_VNC_PORT")
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

    private fun startVncForwarder(machine: Any) {
        if (!vncForwardRunning.compareAndSet(false, true)) return
        Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), ANDROID_VNC_PORT))
                vncServer = server
                append("Android-local VNC forward listening on 127.0.0.1:$ANDROID_VNC_PORT")
                while (vncForwardRunning.get() && isRunning(machine)) {
                    val client = server.accept()
                    Thread({
                        try {
                            val pfd = connectVsock(machine, VNC_VSOCK_PORT)
                            val outPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                            val vmIn = FileInputStream(pfd.fileDescriptor)
                            val vmOut = FileOutputStream(outPfd.fileDescriptor)
                            try {
                                relayDuplex(client.getInputStream(), client.getOutputStream(), vmIn, vmOut)
                            } finally {
                                runCatching { vmIn.close() }
                                runCatching { vmOut.close() }
                                runCatching { pfd.close() }
                                runCatching { outPfd.close() }
                            }
                        } catch (t: Throwable) {
                            append("VNC forward session ended: ${AvfReflect.unwrap(t).message}")
                        } finally {
                            runCatching { client.close() }
                        }
                    }, "dev1-vnc-session").also { it.isDaemon = true; it.start() }
                }
            } catch (t: Throwable) {
                if (vncForwardRunning.get()) append("VNC forwarder failed: ${AvfReflect.unwrap(t).message}")
            } finally {
                vncForwardRunning.set(false)
                runCatching { vncServer?.close() }
                vncServer = null
            }
        }, "dev1-vnc-forward").also { it.isDaemon = true; it.start() }
    }

    private fun connectVsock(machine: Any, port: Int): ParcelFileDescriptor {
        val method = machine.javaClass.methods.firstOrNull { it.name == "connectVsock" && it.parameterCount == 1 }
            ?: error("VirtualMachine.connectVsock not found")
        val type = method.parameterTypes[0]
        val arg: Any = if (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) port.toLong() else port
        return method.invoke(machine, arg) as ParcelFileDescriptor
    }

    private fun readAsciiLine(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun relayDuplex(vsockIn: InputStream, vsockOut: OutputStream, remote: Socket) {
        relayDuplex(vsockIn, vsockOut, remote.getInputStream(), remote.getOutputStream())
    }

    private fun relayDuplex(aIn: InputStream, aOut: OutputStream, bIn: InputStream, bOut: OutputStream) {
        val t = Thread({ runCatching { copy(aIn, bOut) }; runCatching { bOut.flush() } }, "dev1-relay-a")
        t.isDaemon = true
        t.start()
        try {
            copy(bIn, aOut)
            aOut.flush()
        } finally {
            runCatching { aIn.close() }
            runCatching { bIn.close() }
            t.join(1500)
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) return
            output.write(buf, 0, n)
            output.flush()
        }
    }

    /** Privileged AOSP Terminal display Binder is intentionally not used in this build. */
    override fun setDisplaySurface(surface: Surface) {
        append("Surface attach ignored: using VNC-over-vsock desktop path instead of privileged virtualizationservice display Binder")
    }

    override fun clearDisplaySurface() = Unit
    override fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean = false
    override fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean = false

    @Synchronized override fun guestShell(command: String): String {
        require(command.length <= 4096) { "Command too long" }
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Managed VM is not created").toString()
        check(vmMode == "microdroid") { "Gate A shell uses the stock Microdroid VM" }
        if (!isRunning(machine)) waitUntilRunning(machine, "microdroid", 45_000L)

        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        var attempt = 0
        var last: Throwable? = null
        var commandSubmitted = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++
            if (!isRunning(machine)) {
                last = IllegalStateException("VM left STATUS_RUNNING while waiting for guest endpoint")
                Thread.sleep(300)
                continue
            }
            try {
                val pfd = connectVsock(machine, 5555)
                append("[connect_vsock] PASS port=5555 fd=${pfd.fd} attempt=$attempt")
                pfd.use {
                    stage = "adb_shell"
                    commandSubmitted = true
                    val output = NativeTransport.shellFd(it.fd, command)
                    stage = "guest_command_pass"
                    lastError = ""
                    append("[guest_command] PASS command=${command.take(120)}")
                    return JSONObject().put("ok", true).put("output", output).toString()
                }
            } catch (t: Throwable) {
                last = AvfReflect.unwrap(t)
                if (commandSubmitted) break
                if (attempt == 1 || attempt % 5 == 0) append("[connect_vsock] waiting attempt=$attempt: ${last.message}")
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
            .put("guestVerified", guestVerified)
            .put("internetReady", internetReady)
            .put("internetStage", internetStage)
            .put("kdeInstalled", kdeMarker().isFile)
            .put("kdeInstalling", kdeInstalling.get())
            .put("kdeStage", kdeStage)
            .put("kdeError", kdeError)
            .put("vncReady", vncReady || kdeMarker().isFile)
            .put("vncPort", ANDROID_VNC_PORT)
            .put("guestGraphics", if (kdeMarker().isFile) "Plasma via TigerVNC software framebuffer; hardware GPU unproven" else "VNC fallback ready after Plasma install")
            .put("debian", if (guestVerified) "Debian guest boot verified" else if (debianImage().installed()) "image installed; guest boot not yet verified" else "not installed")
            .toString()
    }

    override fun destroy() {
        runCatching { stopInternal() }
        kotlin.system.exitProcess(0)
    }

    companion object {
        private const val PACKAGE = BuildConfig.APPLICATION_ID
        private const val PROXY_VSOCK_PORT = 7777
        private const val VNC_VSOCK_PORT = 5901
        const val ANDROID_VNC_PORT = 5909

        private val GUEST_BRIDGE_SERVICE = """
            [Unit]
            Description=DEV 1 host bridge
            After=multi-user.target
            [Service]
            Type=simple
            ExecStart=/usr/bin/python3 /usr/local/lib/dev1/bridge.py
            Restart=always
            RestartSec=1
            [Install]
            WantedBy=multi-user.target
        """.trimIndent()

        private val GUEST_BRIDGE_PY = """
#!/usr/bin/env python3
import socket, threading, queue, select, urllib.parse
AF_VSOCK=40; ANY=-1; WORKER=7777; VNC=5901
workers=queue.Queue()

def recvline(s,limit=4096):
    b=bytearray()
    while len(b)<limit:
        x=s.recv(1)
        if not x or x==b'\n': break
        if x!=b'\r': b+=x
    return b.decode('utf-8','replace')

def relay(a,b,initial=b''):
    try:
        if initial: b.sendall(initial)
        a.setblocking(False); b.setblocking(False)
        while True:
            r,_,_=select.select([a,b],[],[],120)
            if not r: continue
            for src in r:
                dst=b if src is a else a
                data=src.recv(65536)
                if not data: return
                dst.sendall(data)
    except Exception:
        pass
    finally:
        try:a.close()
        except:pass
        try:b.close()
        except:pass

def worker_accept():
    s=socket.socket(AF_VSOCK,socket.SOCK_STREAM); s.bind((ANY,WORKER)); s.listen(32)
    while True:
        c,_=s.accept(); workers.put(c)

def get_worker(host,port):
    while True:
        w=workers.get()
        try:
            w.sendall(('CONNECT %s %d\n'%(host,port)).encode())
            if recvline(w).strip()=='OK': return w
        except: pass
        try:w.close()
        except:pass

def read_header(c):
    b=bytearray()
    while b'\r\n\r\n' not in b and len(b)<131072:
        x=c.recv(4096)
        if not x: break
        b+=x
    return bytes(b)

def http_client(c):
    try:
        h=read_header(c)
        if not h: return
        text=h.decode('iso-8859-1','replace'); lines=text.split('\r\n'); first=lines[0].split(' ')
        if len(first)<3: return
        method,target,ver=first[0],first[1],first[2]
        if method.upper()=='CONNECT':
            hp=target.rsplit(':',1); host=hp[0]; port=int(hp[1]) if len(hp)>1 else 443
            w=get_worker(host,port); c.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n'); relay(c,w); return
        u=urllib.parse.urlsplit(target)
        if u.hostname:
            host=u.hostname; port=u.port or (443 if u.scheme=='https' else 80); path=urllib.parse.urlunsplit(('', '', u.path or '/', u.query, ''))
        else:
            hostline=next((x[5:].strip() for x in lines[1:] if x.lower().startswith('host:')),None)
            if not hostline:return
            hp=hostline.rsplit(':',1); host=hp[0]; port=int(hp[1]) if len(hp)>1 and hp[1].isdigit() else 80; path=target
        lines[0]='%s %s %s'%(method,path,ver); rewritten='\r\n'.join(lines).encode('iso-8859-1')
        w=get_worker(host,port); relay(c,w,rewritten)
    finally:
        try:c.close()
        except:pass

def http_server():
    s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(('127.0.0.1',3128)); s.listen(64)
    while True:
        c,_=s.accept(); threading.Thread(target=http_client,args=(c,),daemon=True).start()

def recvn(c,n):
    b=b''
    while len(b)<n:
        x=c.recv(n-len(b))
        if not x: raise EOFError()
        b+=x
    return b

def socks_client(c):
    try:
        v,n=recvn(c,2); recvn(c,n); c.sendall(b'\x05\x00')
        v,cmd,rsv,atyp=recvn(c,4)
        if cmd!=1: return
        if atyp==1: host=socket.inet_ntoa(recvn(c,4))
        elif atyp==3:
            ln=recvn(c,1)[0]; host=recvn(c,ln).decode()
        elif atyp==4: host=socket.inet_ntop(socket.AF_INET6,recvn(c,16))
        else:return
        port=int.from_bytes(recvn(c,2),'big'); w=get_worker(host,port)
        c.sendall(b'\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00'); relay(c,w)
    except Exception:
        try:c.sendall(b'\x05\x01\x00\x01\x00\x00\x00\x00\x00\x00')
        except:pass
        try:c.close()
        except:pass

def socks_server():
    s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(('127.0.0.1',1080)); s.listen(64)
    while True:
        c,_=s.accept(); threading.Thread(target=socks_client,args=(c,),daemon=True).start()

def vnc_server():
    s=socket.socket(AF_VSOCK,socket.SOCK_STREAM); s.bind((ANY,VNC)); s.listen(8)
    while True:
        c,_=s.accept()
        try:
            local=socket.create_connection(('127.0.0.1',5901),5)
            threading.Thread(target=relay,args=(c,local),daemon=True).start()
        except Exception:
            try:c.close()
            except:pass

for fn in (worker_accept,http_server,socks_server,vnc_server): threading.Thread(target=fn,daemon=True).start()
threading.Event().wait()
        """.trimIndent()

        private val KDE_INSTALL_SCRIPT = """
            #!/bin/sh
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            export http_proxy=http://127.0.0.1:3128
            export https_proxy=http://127.0.0.1:3128
            export HTTP_PROXY=$http_proxy HTTPS_PROXY=$https_proxy
            apt-get update
            apt-get install -y kde-plasma-desktop kwin-x11 konsole dolphin dbus-x11 tigervnc-standalone-server tigervnc-tools xterm mesa-utils
            install -d -m 700 -o droid -g droid /home/droid/.vnc
            cat >/home/droid/.vnc/Xtigervnc-session <<'EOF'
            #!/bin/sh
            unset SESSION_MANAGER
            unset DBUS_SESSION_BUS_ADDRESS
            export XDG_CURRENT_DESKTOP=KDE
            export KDE_FULL_SESSION=true
            export QT_X11_NO_MITSHM=1
            exec dbus-run-session -- startplasma-x11
            EOF
            chmod 700 /home/droid/.vnc/Xtigervnc-session
            chown -R droid:droid /home/droid/.vnc
            runuser -u droid -- tigervncserver -kill :1 >/dev/null 2>&1 || true
            runuser -u droid -- tigervncserver :1 -localhost yes -SecurityTypes None -geometry 1200x2200 -depth 24
            sleep 4
            pgrep -a Xtigervnc
            pgrep -a plasmashell || true
            echo DEV1_PLASMA_READY
        """.trimIndent()
    }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd: Int, command: String): String
}
