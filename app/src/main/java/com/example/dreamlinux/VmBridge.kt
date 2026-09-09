package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Shizuku-side AVF controller for DEV 1.
 *
 * Qualcomm on this device exposes protected AVF only. Instead of forcing Google's non-pVM Debian
 * kernel through pvmfw, this build keeps the OEM/AOSP-trusted Microdroid kernel and runs a normal
 * Alpine aarch64 userspace from Microdroid's persistent encrypted storage. The VM is fully
 * hardware-virtualized by AVF/Gunyah; only the guest userspace is Alpine.
 *
 * A fully debuggable Microdroid explicitly supports adb root. We use that root *inside the VM*
 * (never Android host root) to unpack/chroot Alpine and to launch a small bionic helper. The helper
 * exposes a localhost HTTP proxy and VNC endpoint, while all host access still enters through
 * VirtualMachine.connectVsock().
 */
class VmBridge : IVmBridge.Stub() {
    private val gateVmName = "dev1-gate-a-v4"
    private val linuxVmName = "dev1-alpine-pvm-v1"
    private val storageSuffix = if (BuildConfig.LOCAL_TEST) "-localtest" else ""
    private val vmData = File("/data/local/tmp/dev1-linux-vmm$storageSuffix")
    private val stateDir = File("/data/local/tmp/dev1-linux$storageSuffix/alpine-state")

    private var manager: Any? = null
    @Volatile private var vm: Any? = null
    @Volatile private var vmMode = "none"
    @Volatile private var stage = "idle"
    private var log = ""
    @Volatile private var lastError = ""
    private var consoleThread: Thread? = null
    private var consoleInput: OutputStream? = null
    private var consoleOutput: InputStream? = null

    private val installing = AtomicBoolean(false)
    @Volatile private var installError = ""
    private val desktopInstalling = AtomicBoolean(false)
    @Volatile private var desktopStage = "not installed"
    @Volatile private var desktopError = ""
    @Volatile private var guestVerified = false
    @Volatile private var internetReady = false
    @Volatile private var internetStage = "not started"
    @Volatile private var vncReady = false
    @Volatile private var adbRootReady = false

    private val proxyRunning = AtomicBoolean(false)
    private val vncForwardRunning = AtomicBoolean(false)
    private var vncServer: ServerSocket? = null

    private val lock = Any()
    private fun append(value: String) = synchronized(lock) {
        log = (log + value + "\n").takeLast(384000)
    }
    private fun appendConsole(value: String) = synchronized(lock) {
        log = (log + value).takeLast(384000)
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
            Thread.sleep(200)
        }
        error("Timed out waiting ${timeoutMs}ms for $mode VM STATUS_RUNNING; lastStatus=$last")
    }

    private fun buildMicrodroid(context: Context, linux: Boolean): Any {
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builder = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        AvfReflect.call(builder, "setProtectedVm", true)
        AvfReflect.call(builder, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        AvfReflect.call(builder, "setMemoryBytes", (if (linux) 4096L else 512L) * 1024L * 1024L)
        if (linux) {
            // Persistent ext4/dm-crypt storage mounted by Microdroid at /mnt/encryptedstore.
            AvfReflect.call(builder, "setEncryptedStorageBytes", 10L * 1024L * 1024L * 1024L)
            runCatching {
                val topology = configClass.getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null)
                AvfReflect.call(builder, "setCpuTopology", topology)
            }
        }
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
            append("setConfig rejected (${AvfReflect.unwrap(t).message}); recreating $name")
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
        waitUntilRunning(machine, mode, 45_000L)
    }

    private fun attachConsole(machine: Any) {
        consoleThread?.interrupt()
        runCatching { consoleOutput?.close() }
        consoleInput = runCatching { AvfReflect.callOptional(machine, "getConsoleInput") as? OutputStream }.getOrNull()
        val stream = runCatching { AvfReflect.callOptional(machine, "getConsoleOutput") as? InputStream }.getOrNull() ?: return
        consoleOutput = stream
        consoleThread = Thread({
            try { ConsoleCapture.read(stream, ::appendConsole) }
            catch (t: Throwable) { if (!Thread.currentThread().isInterrupted) append("console capture ended: ${t.message}") }
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
            if (isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:microdroid"
            startMachine(acquireVm(gateVmName, buildMicrodroid(context, false), "microdroid"), "microdroid")
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
        adbRootReady = false
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
            .put("desktopPath", "trusted Microdroid pVM + Alpine chroot + TigerVNC over vsock")
            .put("networkPath", "host-mediated HTTP proxy over VirtualMachine.connectVsock")
            .put("debianInstalled", bundleReady())
            .put("kdeInstalled", desktopMarker().isFile)
            .toString()
    } catch (t: Throwable) {
        val e = AvfReflect.unwrap(t)
        JSONObject().put("ok", false).put("error", "${e.javaClass.name}: ${e.message}").toString()
    }

    private fun classExists(name: String) = runCatching { Class.forName(name) }.isSuccess

    private fun bundleReady(): Boolean = runCatching {
        baseContext().assets.open("alpine-minirootfs.tar.gz").use { it.read() >= 0 } &&
            baseContext().assets.open("dev1_guest_bridge").use { it.read() >= 0 }
    }.getOrDefault(false)

    private fun alpineAssetName(): String = runCatching {
        baseContext().assets.open("alpine-version.txt").bufferedReader().use { it.readLine().orEmpty() }
    }.getOrDefault("alpine-minirootfs")

    private fun alpineBranch(): String {
        val match = Regex("alpine-minirootfs-([0-9]+\\.[0-9]+)").find(alpineAssetName())
        return "v${match?.groupValues?.getOrNull(1) ?: "3.24"}"
    }

    override fun installDebian(): String {
        // Alpine minirootfs is bundled into the APK. Provisioning happens into encrypted storage
        // on first Start Linux, so there is no separate multi-gigabyte host download anymore.
        installError = ""
        stage = if (bundleReady()) "alpine_bundle_ready" else "blocked:alpine_bundle_missing"
        if (!bundleReady()) installError = "Embedded Alpine rootfs/helper assets are missing from this APK"
        append("Alpine bundle ${if (bundleReady()) "ready" else "missing"}: ${alpineAssetName()}")
        return status()
    }

    @Synchronized override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String =
        startLinuxMode(width, height)

    @Synchronized override fun startDebianDiagnostic(): String = startLinuxMode(1280, 720)

    private fun startLinuxMode(width: Int, height: Int): String {
        lastError = ""
        guestVerified = false
        internetReady = false
        internetStage = "starting"
        return try {
            check(bundleReady()) { "Alpine rootfs/helper bundle is missing from APK" }
            if (isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:alpine_pvm"
            append("Using OEM-trusted Microdroid pVM kernel; Alpine userspace lives in persistent encrypted storage")
            append("No custom kernel, no pvmfw bypass, no Termux/proot")
            val machine = acquireVm(linuxVmName, buildMicrodroid(context, true), "alpine")
            startMachine(machine, "alpine")

            stage = "microdroid_adb_root"
            ensureAdbRoot(machine)

            stage = "alpine_provision"
            provisionAlpine(machine)
            guestVerified = true

            stage = "internet_bridge"
            startProxyWorkers(machine)
            startVncForwarder(machine)
            verifyInternet(machine)

            stage = "alpine_ready"
            append("[alpine] PASS real Alpine aarch64 userspace running on trusted Microdroid kernel")
            append("[alpine] kernel remains ${adbShell(machine, "uname -r").trim()}; userspace=${alpineAssetName()}")
            status()
        } catch (t: Throwable) {
            blocked(t, stage)
            status()
        }
    }

    private fun ensureAdbRoot(machine: Any) {
        val initial = runCatching { adbShell(machine, "id -u") }.getOrDefault("").trim()
        if (initial.lines().lastOrNull()?.trim() == "0") {
            adbRootReady = true
            append("[adb_root] already root inside Microdroid")
            return
        }

        val pfd = connectVsockRetry(machine, ADB_VSOCK_PORT, 30_000L)
        val reply = pfd.use { NativeTransport.serviceFd(it.fd, "root:") }
        append("[adb_root] request: ${reply.trim().take(1000)}")

        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        var last = ""
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(350)
            last = runCatching { adbShell(machine, "id -u; id; getenforce") }.getOrDefault("")
            val firstNumeric = last.lineSequence().map { it.trim() }.firstOrNull { it == "0" }
            if (firstNumeric == "0") {
                adbRootReady = true
                append("[adb_root] PASS Microdroid adbd restarted as VM-local root\n${last.takeLast(2000)}")
                return
            }
        }
        error("Microdroid adb root did not become ready. Last output: ${last.takeLast(2000)}")
    }

    private fun provisionAlpine(machine: Any) {
        val root = ALPINE_ROOT
        val branch = alpineBranch()
        val script = """
            set -eu
            test "$(id -u)" = 0
            test -d /mnt/encryptedstore
            test -f /mnt/apk/assets/alpine-minirootfs.tar.gz
            test -f /mnt/apk/assets/dev1_guest_bridge
            ROOT=$root
            mkdir -p "$root"
            if [ ! -f "$root/.dev1-rootfs-ready" ]; then
              echo DEV1_UNPACK_START
              rm -rf "$root"/* "$root"/.[!.]* "$root"/..?* 2>/dev/null || true
              tar -xzf /mnt/apk/assets/alpine-minirootfs.tar.gz -C "$root"
              touch "$root/.dev1-rootfs-ready"
              echo DEV1_UNPACK_DONE
            fi
            mkdir -p "$root/dev" "$root/proc" "$root/sys" "$root/tmp" "$root/run"
            grep -q " $root/dev " /proc/mounts || mount --bind /dev "$root/dev"
            grep -q " $root/proc " /proc/mounts || mount --bind /proc "$root/proc"
            grep -q " $root/sys " /proc/mounts || mount --bind /sys "$root/sys"
            printf '%s\n' \
              'https://dl-cdn.alpinelinux.org/alpine/$branch/main' \
              'https://dl-cdn.alpinelinux.org/alpine/$branch/community' > "$root/etc/apk/repositories"
            printf '%s\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > "$root/etc/resolv.conf"
            cp /mnt/apk/assets/dev1_guest_bridge /data/local/tmp/dev1_guest_bridge
            chmod 755 /data/local/tmp/dev1_guest_bridge
            (/data/local/tmp/dev1_guest_bridge >/data/local/tmp/dev1-bridge.log 2>&1 &)
            sleep 1
            cat /data/local/tmp/dev1-bridge.log 2>/dev/null || true
            chroot "$root" /bin/sh -lc 'cat /etc/alpine-release; uname -a; id; /bin/busybox | head -1'
        """.trimIndent()
        val out = adbShell(machine, script, 120_000L)
        check(out.contains("Alpine") || Regex("\\d+\\.\\d+\\.\\d+").containsMatchIn(out)) {
            "Alpine rootfs verification failed: ${out.takeLast(5000)}"
        }
        append("[alpine_rootfs] PASS ${alpineAssetName()}\n${out.takeLast(5000)}")
    }

    private fun verifyInternet(machine: Any) {
        internetStage = "testing Alpine apk through host vsock proxy"
        var last: Throwable? = null
        repeat(10) { attempt ->
            try {
                Thread.sleep(if (attempt == 0) 700L else 350L)
                val command = chrootCommand(
                    "export http_proxy=http://127.0.0.1:3128 https_proxy=http://127.0.0.1:3128 HTTP_PROXY=\$http_proxy HTTPS_PROXY=\$https_proxy; apk update; echo DEV1_APK_NETWORK_PASS"
                )
                val out = adbShell(machine, command, 90_000L)
                if (out.contains("DEV1_APK_NETWORK_PASS")) {
                    internetReady = true
                    internetStage = "ready"
                    append("[internet] PASS Alpine apk repositories reached through host-mediated vsock proxy")
                    return
                }
            } catch (t: Throwable) {
                last = t
                if (attempt == 0 || attempt == 4) append("internet probe retry ${attempt + 1}: ${AvfReflect.unwrap(t).message}")
            }
        }
        internetReady = false
        internetStage = "blocked: ${AvfReflect.unwrap(last ?: IllegalStateException("proxy test failed")).message}"
        error("Alpine Internet bridge failed: $internetStage")
    }

    override fun debianConsole(command: String): String {
        require(command.length <= 16384) { "Command too long" }
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Linux VM is not created").toString()
        check(vmMode == "alpine" && isRunning(machine) && adbRootReady) { "Start Alpine Linux first" }
        stage = "alpine_console"
        return try {
            val output = adbShell(machine, chrootCommand(command), 120_000L)
            stage = if (guestVerified) "alpine_ready" else "alpine_console_pass"
            JSONObject().put("ok", true).put("output", output).toString()
        } catch (t: Throwable) {
            val e = AvfReflect.unwrap(t)
            lastError = "${e.javaClass.name}: ${e.message}"
            stage = "blocked:alpine_console"
            append(lastError)
            JSONObject().put("ok", false).put("error", lastError).toString()
        }
    }

    private fun chrootCommand(command: String): String =
        "chroot $ALPINE_ROOT /bin/sh -lc ${shellQuote(command)}"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    override fun openDebianVsock(port: Int): ParcelFileDescriptor {
        require(port in 1..65535) { "Invalid vsock port" }
        val machine = vm ?: error("Linux VM is not created")
        check(vmMode == "alpine" && isRunning(machine)) { "Alpine VM is not running" }
        return connectVsock(machine, port)
    }

    override fun installKde(): String {
        val machine = vm ?: error("Linux VM is not created")
        check(vmMode == "alpine" && isRunning(machine) && guestVerified) { "Start and verify Alpine first" }
        if (desktopMarker().isFile) return status()
        if (!internetReady) {
            desktopError = "Internet bridge is not ready"
            desktopStage = "blocked"
            return status()
        }
        if (!desktopInstalling.compareAndSet(false, true)) return status()
        desktopError = ""
        desktopStage = "installing XFCE + TigerVNC"
        Thread({
            try {
                stage = "desktop_packages"
                val install = """
                    export http_proxy=http://127.0.0.1:3128
                    export https_proxy=http://127.0.0.1:3128
                    export HTTP_PROXY=\$http_proxy HTTPS_PROXY=\$https_proxy
                    apk add --no-cache xfce4 xfce4-terminal dbus tigervnc font-dejavu
                    mkdir -p /root/.vnc /root/.config/tigervnc /tmp/.X11-unix
                    chmod 1777 /tmp /tmp/.X11-unix
                    pkill Xvnc 2>/dev/null || true
                    pkill xfce4-session 2>/dev/null || true
                    Xvnc :1 -SecurityTypes None -localhost yes -geometry 1200x2200 -depth 24 >/tmp/xvnc.log 2>&1 &
                    sleep 2
                    DISPLAY=:1 dbus-run-session -- startxfce4 >/tmp/xfce.log 2>&1 &
                    sleep 4
                    pgrep -a Xvnc
                    pgrep -a xfce4-session || true
                    echo DEV1_DESKTOP_READY
                """.trimIndent()
                val out = adbShell(machine, chrootCommand(install), 30L * 60L * 1000L)
                check(out.contains("DEV1_DESKTOP_READY")) { "Desktop did not report ready: ${out.takeLast(8000)}" }
                stateDir.mkdirs()
                desktopMarker().writeText("installed-by=${BuildConfig.VERSION_NAME}\nuserspace=${alpineAssetName()}\n")
                desktopStage = "ready"
                vncReady = true
                stage = "desktop_ready"
                append("XFCE + TigerVNC provisioning completed; Android-local VNC is 127.0.0.1:$ANDROID_VNC_PORT")
            } catch (t: Throwable) {
                val e = AvfReflect.unwrap(t)
                desktopError = "${e.javaClass.name}: ${e.message}"
                desktopStage = "blocked"
                lastError = desktopError
                stage = "blocked:desktop_install"
                append(desktopError)
            } finally {
                desktopInstalling.set(false)
            }
        }, "dev1-desktop-installer").also { it.isDaemon = true; it.start() }
        return status()
    }

    private fun desktopMarker() = File(stateDir, ".desktop-installed")

    private fun startProxyWorkers(machine: Any) {
        if (!proxyRunning.compareAndSet(false, true)) return
        internetStage = "host workers starting"
        repeat(8) { index ->
            Thread({
                while (proxyRunning.get() && isRunning(machine)) {
                    try {
                        val pfd = connectVsockRetry(machine, PROXY_VSOCK_PORT, 15_000L)
                        serveProxyWorker(pfd)
                    } catch (t: Throwable) {
                        if (proxyRunning.get()) {
                            if (index == 0) append("internet worker reconnect: ${AvfReflect.unwrap(t).message}")
                            Thread.sleep(500)
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
                            val pfd = connectVsockRetry(machine, VNC_VSOCK_PORT, 10_000L)
                            val outPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                            val vmIn = FileInputStream(pfd.fileDescriptor)
                            val vmOut = FileOutputStream(outPfd.fileDescriptor)
                            try { relayDuplex(client.getInputStream(), client.getOutputStream(), vmIn, vmOut) }
                            finally {
                                runCatching { vmIn.close() }; runCatching { vmOut.close() }
                                runCatching { pfd.close() }; runCatching { outPfd.close() }
                            }
                        } catch (t: Throwable) {
                            append("VNC forward session ended: ${AvfReflect.unwrap(t).message}")
                        } finally { runCatching { client.close() } }
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

    private fun connectVsockRetry(machine: Any, port: Int, timeoutMs: Long): ParcelFileDescriptor {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var attempt = 0
        var last: Throwable? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++
            try {
                val pfd = connectVsock(machine, port)
                if (attempt > 1) append("[connect_vsock] PASS port=$port attempt=$attempt")
                return pfd
            } catch (t: Throwable) {
                last = AvfReflect.unwrap(t)
                if (attempt == 1 || attempt % 8 == 0) append("[connect_vsock] waiting port=$port attempt=$attempt: ${last.message}")
                Thread.sleep(if (attempt < 6) 250L else 500L)
            }
        }
        throw last ?: IllegalStateException("connectVsock($port) timed out")
    }

    private fun adbShell(machine: Any, command: String, timeoutMs: Long = 40_000L): String {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var last: Throwable? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                val pfd = connectVsockRetry(machine, ADB_VSOCK_PORT, minOf(8_000L, timeoutMs))
                return pfd.use { NativeTransport.shellFd(it.fd, command) }
            } catch (t: Throwable) {
                last = AvfReflect.unwrap(t)
                Thread.sleep(300)
            }
        }
        throw last ?: IllegalStateException("ADB shell timed out")
    }

    private fun readAsciiLine(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun relayDuplex(vsockIn: InputStream, vsockOut: OutputStream, remote: Socket) =
        relayDuplex(vsockIn, vsockOut, remote.getInputStream(), remote.getOutputStream())

    private fun relayDuplex(aIn: InputStream, aOut: OutputStream, bIn: InputStream, bOut: OutputStream) {
        val t = Thread({ runCatching { copy(aIn, bOut) }; runCatching { bOut.flush() } }, "dev1-relay-a")
        t.isDaemon = true
        t.start()
        try { copy(bIn, aOut); aOut.flush() }
        finally {
            runCatching { aIn.close() }; runCatching { bIn.close() }
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

    override fun setDisplaySurface(surface: Surface) {
        append("Surface attach ignored: desktop uses TigerVNC over vsock; privileged AOSP display Binder is not required")
    }
    override fun clearDisplaySurface() = Unit
    override fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean = false
    override fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean = false

    @Synchronized override fun guestShell(command: String): String {
        require(command.length <= 4096) { "Command too long" }
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Managed VM is not created").toString()
        check(vmMode == "microdroid") { "Gate A shell uses the stock Microdroid test VM" }
        if (!isRunning(machine)) waitUntilRunning(machine, "microdroid", 45_000L)
        return try {
            val output = adbShell(machine, command)
            stage = "guest_command_pass"
            lastError = ""
            append("[guest_command] PASS command=${command.take(120)}")
            JSONObject().put("ok", true).put("output", output).toString()
        } catch (t: Throwable) {
            val e = AvfReflect.unwrap(t)
            lastError = "${e.javaClass.name}: ${e.message}"
            stage = "blocked:connect_vsock"
            append(lastError)
            JSONObject().put("ok", false).put("error", lastError).toString()
        }
    }

    override fun status(): String {
        val machine = vm
        val running = isRunning(machine)
        val root = machine?.let {
            runCatching { AvfReflect.callOptional(it, "getRootDir") as? File }.getOrNull()?.path ?: ""
        } ?: ""
        val rawStatus = machine?.let { runCatching { vmStatus(it) }.getOrDefault(-999) } ?: -999
        return JSONObject()
            .put("name", when (vmMode) { "alpine" -> linuxVmName; "microdroid" -> gateVmName; else -> "" })
            .put("running", running)
            .put("rawVmStatus", rawStatus)
            .put("cid", -1)
            .put("managed", machine != null)
            .put("mode", vmMode)
            .put("stage", stage)
            .put("api", "VirtualMachineManager/connectVsock/Microdroid encrypted storage")
            .put("vmRoot", root)
            .put("dataDir", vmData.path)
            .put("error", lastError)
            .put("log", logSnapshot())
            .put("debianInstalled", bundleReady())
            .put("debianInstalling", installing.get())
            .put("installBytes", 0L)
            .put("installTotal", -1L)
            .put("installProgress", -1.0)
            .put("installError", installError)
            .put("guestVerified", guestVerified)
            .put("internetReady", internetReady)
            .put("internetStage", internetStage)
            .put("kdeInstalled", desktopMarker().isFile)
            .put("kdeInstalling", desktopInstalling.get())
            .put("kdeStage", desktopStage)
            .put("kdeError", desktopError)
            .put("vncReady", vncReady || desktopMarker().isFile)
            .put("vncPort", ANDROID_VNC_PORT)
            .put("guestGraphics", if (desktopMarker().isFile) "XFCE via TigerVNC software framebuffer; hardware GPU still unproven" else "Alpine pVM ready; desktop/GPU pending")
            .put("debian", if (guestVerified) "Alpine userspace verified in trusted Microdroid pVM" else "embedded Alpine bundle ${if (bundleReady()) "ready" else "missing"}")
            .put("alpineVersion", alpineAssetName())
            .put("adbRootReady", adbRootReady)
            .toString()
    }

    override fun destroy() {
        runCatching { stopInternal() }
        kotlin.system.exitProcess(0)
    }

    companion object {
        private const val PACKAGE = BuildConfig.APPLICATION_ID
        private const val ADB_VSOCK_PORT = 5555
        private const val PROXY_VSOCK_PORT = 7777
        private const val VNC_VSOCK_PORT = 5901
        private const val ALPINE_ROOT = "/mnt/encryptedstore/alpine"
        const val ANDROID_VNC_PORT = 5909
    }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd: Int, command: String): String
    external fun serviceFd(fd: Int, service: String): String
}
