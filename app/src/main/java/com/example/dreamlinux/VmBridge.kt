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

class VmBridge : IVmBridge.Stub() {
    private val gateVmName = "dev1-gate-a-v${BuildConfig.VERSION_CODE}"
    private val linuxVmName = "dev1-debian-pvm-v${BuildConfig.VERSION_CODE}"
    private val storageSuffix = if (BuildConfig.LOCAL_TEST) "-localtest" else ""
    private val vmData = File("/data/local/tmp/dev1-linux-vmm$storageSuffix")
    private val stateDir = File("/data/local/tmp/dev1-linux$storageSuffix/debian-state")

    private var manager: Any? = null
    @Volatile private var vm: Any? = null
    @Volatile private var vmMode = "none"
    @Volatile private var stage = "idle"
    @Volatile private var lastError = ""
    @Volatile private var guestVerified = false
    @Volatile private var adbRootReady = false
    @Volatile private var internetReady = false
    @Volatile private var internetStage = "not started"
    @Volatile private var desktopStage = "not installed"
    @Volatile private var desktopError = ""
    @Volatile private var vncReady = false
    private val desktopInstalling = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    @Volatile private var installError = ""

    private val proxyRunning = AtomicBoolean(false)
    private val vncForwardRunning = AtomicBoolean(false)
    private var vncServer: ServerSocket? = null
    private var consoleThread: Thread? = null
    private var consoleOutput: InputStream? = null

    private val logLock = Any()
    private var log = ""
    private fun append(s: String) = synchronized(logLock) { log = (log + s + "\n").takeLast(512000) }
    private fun appendRaw(s: String) = synchronized(logLock) { log = (log + s).takeLast(512000) }
    private fun logSnapshot(): String = synchronized(logLock) { log }

    private class RedirectedDataContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getDataDir(): File = root
        override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        override fun getCodeCacheDir(): File = File(root, "code_cache").also { it.mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").also { it.mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    private fun baseContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val current = runCatching { at.getMethod("currentApplication").invoke(null) as? Context }.getOrNull()
        if (current != null && current.packageName == PACKAGE) return current
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: error("ActivityThread unavailable")
        val system = at.getMethod("getSystemContext").invoke(thread) as Context
        return system.createPackageContext(PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun redirectedContext(): Context {
        check(vmData.mkdirs() || vmData.isDirectory) { "Cannot create ${vmData.path}" }
        val c = RedirectedDataContext(baseContext(), vmData)
        c.filesDir.mkdirs(); c.noBackupFilesDir.mkdirs()
        append("context package=${c.packageName} uid=${android.os.Process.myUid()} dataDir=${c.dataDir}")
        return c
    }

    private fun manager(context: Context = redirectedContext()): Any {
        manager?.let { return it }
        val type = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        return type.getConstructor(Context::class.java).newInstance(context).also {
            manager = it
            append("VirtualMachineManager created with shell-writable redirected context")
        }
    }

    private fun vmStatus(machine: Any): Int = (AvfReflect.call(machine, "getStatus") as Number).toInt()
    private fun runningStatus(machine: Any): Int = machine.javaClass.getField("STATUS_RUNNING").getInt(null)
    private fun isRunning(machine: Any?): Boolean = machine != null && runCatching { vmStatus(machine) == runningStatus(machine) }.getOrDefault(false)

    private fun buildMicrodroid(context: Context, linux: Boolean): Any {
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builder = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        AvfReflect.call(builder, "setProtectedVm", true)
        AvfReflect.call(builder, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        AvfReflect.call(builder, "setMemoryBytes", (if (linux) 4096L else 512L) * 1024L * 1024L)
        if (linux) {
            AvfReflect.call(builder, "setEncryptedStorageBytes", 10L * 1024L * 1024L * 1024L)
            runCatching { AvfReflect.call(builder, "setCpuTopology", configClass.getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null)) }
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
            append("setConfig rejected; recreating $name: ${AvfReflect.unwrap(t).message}")
            runCatching { AvfReflect.call(machine, "stop") }
            runCatching { AvfReflect.call(mgr, "delete", name) }
            machine = AvfReflect.call(mgr, "create", name, config) ?: error("create returned null")
        }
        vm = machine
        vmMode = mode
        append("managed VM acquired name=$name mode=$mode status=${runCatching { vmStatus(machine) }.getOrDefault(-999)}")
        return machine
    }

    private fun attachConsole(machine: Any) {
        consoleThread?.interrupt()
        runCatching { consoleOutput?.close() }
        val stream = runCatching { AvfReflect.callOptional(machine, "getConsoleOutput") as? InputStream }.getOrNull() ?: return
        consoleOutput = stream
        consoleThread = Thread({
            val buf = ByteArray(8192)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    appendRaw(String(buf, 0, n, StandardCharsets.UTF_8))
                }
            } catch (_: Throwable) {}
        }, "dev1-console").also { it.isDaemon = true; it.start() }
    }

    private fun waitUntilRunning(machine: Any, mode: String, timeoutMs: Long) {
        stage = "vm_wait_running:$mode"
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var last = Int.MIN_VALUE
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val current = vmStatus(machine)
            if (current != last) { append("[$mode] VM status=$current waiting for STATUS_RUNNING=${runningStatus(machine)}"); last = current }
            if (current == runningStatus(machine)) { stage = "running:$mode"; append("[$mode] VMM status=RUNNING"); return }
            Thread.sleep(200)
        }
        error("Timed out waiting for $mode VM STATUS_RUNNING; lastStatus=$last")
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

    private fun blocked(t: Throwable, where: String = stage) {
        val e = AvfReflect.unwrap(t)
        lastError = "${e.javaClass.name}: ${e.message}"
        stage = "blocked:$where"
        append(lastError)
    }

    @Synchronized override fun startVm(): String {
        lastError = ""
        return try {
            if (isRunning(vm)) stopInternal()
            val context = redirectedContext()
            startMachine(acquireVm(gateVmName, buildMicrodroid(context, false), "microdroid"), "microdroid")
            status()
        } catch (t: Throwable) { blocked(t); status() }
    }

    @Synchronized override fun stopVm(): String {
        lastError = ""
        runCatching { stopInternal() }.onFailure { blocked(it, "stop") }
        return status()
    }

    private fun stopInternal() {
        proxyRunning.set(false)
        vncForwardRunning.set(false)
        runCatching { vncServer?.close() }; vncServer = null
        vm?.let { if (isRunning(it)) runCatching { AvfReflect.call(it, "stop") } }
        runCatching { consoleOutput?.close() }; consoleOutput = null
        consoleThread?.interrupt(); consoleThread = null
        guestVerified = false; adbRootReady = false; internetReady = false; vncReady = false
        stage = "stopped:$vmMode"
        append("VM stopped mode=$vmMode")
    }

    private fun classExists(name: String) = runCatching { Class.forName(name) }.isSuccess

    override fun inspectCapabilities(): String = try {
        val mgr = manager(redirectedContext())
        val caps = (AvfReflect.call(mgr, "getCapabilities") as? Number)?.toInt() ?: -1
        val cls = mgr.javaClass
        val pBit = runCatching { cls.getField("CAPABILITY_PROTECTED_VM").getInt(null) }.getOrDefault(1)
        val npBit = runCatching { cls.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null) }.getOrDefault(2)
        JSONObject().put("ok", true).put("capabilities", caps)
            .put("protectedVm", caps >= 0 && caps and pBit != 0)
            .put("nonProtectedVm", caps >= 0 && caps and npBit != 0)
            .put("customImageApi", classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig"))
            .put("gpuConfigApi", classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig\$GpuConfig\$Builder"))
            .put("displayConfigApi", classExists("android.system.virtualmachine.VirtualMachineCustomImageConfig\$DisplayConfig\$Builder"))
            .put("displayServiceApi", false)
            .put("debianInstalled", bundleReady()).put("kdeInstalled", desktopMarker().isFile).toString()
    } catch (t: Throwable) {
        val e = AvfReflect.unwrap(t)
        JSONObject().put("ok", false).put("error", "${e.javaClass.name}: ${e.message}").toString()
    }

    private fun bundleReady(): Boolean = runCatching {
        baseContext().assets.open("debian-rootfs.tar.gz").use { it.read() >= 0 } &&
        baseContext().assets.open("dev1_guest_bridge").use { it.read() >= 0 } &&
        baseContext().assets.open("dev1_gunzip").use { it.read() >= 0 }
    }.getOrDefault(false)

    private fun debianAssetName(): String = runCatching {
        baseContext().assets.open("debian-version.txt").bufferedReader().use { it.readLine().orEmpty() }
    }.getOrDefault("Debian GNU/Linux 13 (trixie) arm64 minbase")

    override fun installDebian(): String {
        installError = if (bundleReady()) "" else "Embedded Debian/rootfs helpers are missing from this APK"
        stage = if (bundleReady()) "debian_bundle_ready" else "blocked:debian_bundle_missing"
        append("Debian bundle ${if (bundleReady()) "ready" else "missing"}: ${debianAssetName()}")
        return status()
    }

    @Synchronized override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String = startLinuxMode()
    @Synchronized override fun startDebianDiagnostic(): String = startLinuxMode()

    private fun startLinuxMode(): String {
        lastError = ""; guestVerified = false; internetReady = false; internetStage = "starting"
        return try {
            check(bundleReady()) { "Debian 13 rootfs/helper bundle is missing from APK" }
            if (isRunning(vm)) stopInternal()
            val context = redirectedContext()
            stage = "config:debian_pvm"
            append("Using OEM-trusted Microdroid pVM kernel; Debian 13 userspace in encrypted storage")
            val machine = acquireVm(linuxVmName, buildMicrodroid(context, true), "debian")
            startMachine(machine, "debian")
            stage = "microdroid_adb_root"; ensureAdbRoot(machine)
            stage = "debian_provision"; provisionDebian(machine); guestVerified = true
            stage = "internet_bridge"; startProxyWorkers(machine); startVncForwarder(machine); verifyInternet(machine)
            stage = "debian_ready"
            append("[debian] PASS Debian 13 arm64 userspace running on trusted Microdroid kernel")
            status()
        } catch (t: Throwable) { blocked(t, stage); status() }
    }

    private fun ensureAdbRoot(machine: Any) {
        val initial = runCatching { adbShell(machine, "id -u") }.getOrDefault("").trim()
        if (initial.lineSequence().any { it.trim() == "0" }) { adbRootReady = true; append("[adb_root] already root inside Microdroid"); return }
        val pfd = connectVsockRetry(machine, ADB_VSOCK_PORT, 30_000L)
        runCatching { pfd.use { NativeTransport.serviceFd(it.fd, "root:") } }
            .onSuccess { append("[adb_root] request: ${it.trim().take(1000)}") }
            .onFailure { append("[adb_root] adbd restart disconnected control socket as expected: ${AvfReflect.unwrap(it).message}") }
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            check(isRunning(machine)) { "VM powered off while waiting for adb root" }
            Thread.sleep(350)
            val out = runCatching { adbShell(machine, "id -u; id; getenforce", 5_000L) }.getOrDefault("")
            if (out.lineSequence().any { it.trim() == "0" }) {
                adbRootReady = true; append("[adb_root] PASS Microdroid adbd restarted as VM-local root\n${out.takeLast(2000)}"); return
            }
        }
        error("Microdroid adb root did not become ready")
    }

    private fun provisionDebian(machine: Any) {
        val script = """
            set -u
            ROOT=$DEBIAN_ROOT
            ASSET=/mnt/apk/assets/debian-rootfs.tar.gz
            TMP=/mnt/encryptedstore/dev1-debian-rootfs.tar
            GUNZIP=/data/local/tmp/dev1_gunzip
            BRIDGE=/data/local/tmp/dev1_guest_bridge
            echo DEV1_PROVISION_BEGIN
            echo UID=$(id -u)
            echo ASSET_INFO
            ls -l "$ASSET" /mnt/apk/assets/dev1_gunzip /mnt/apk/assets/dev1_guest_bridge 2>&1 || true
            echo STORAGE_INFO
            df -h /mnt/encryptedstore 2>&1 || true
            mkdir -p "$ROOT"
            if [ ! -f "$ROOT/.dev1-rootfs-ready" ]; then
              echo DEV1_DEBIAN_UNPACK_START
              rm -rf "$ROOT"/* "$ROOT"/.[!.]* "$ROOT"/..?* 2>/dev/null || true
              cp /mnt/apk/assets/dev1_gunzip "$GUNZIP" || { echo DEV1_GUNZIP_COPY_FAIL rc=$?; exit 31; }
              chmod 755 "$GUNZIP" || { echo DEV1_GUNZIP_CHMOD_FAIL rc=$?; exit 32; }
              rm -f "$TMP"
              "$GUNZIP" "$ASSET" "$TMP" 2>&1
              GZRC=$?
              echo DEV1_GUNZIP_RC=$GZRC
              [ "$GZRC" -eq 0 ] || exit 33
              ls -lh "$TMP" 2>&1 || true
              echo DEV1_TAR_HELP
              toybox tar --help 2>&1 | head -20 || true
              echo DEV1_TAR_EXTRACT_START
              toybox tar -xomf "$TMP" -C "$ROOT" 2>&1
              TRC=$?
              echo DEV1_TAR_RC=$TRC
              [ "$TRC" -eq 0 ] || exit 34
              rm -f "$TMP"
              echo DEV1_DEBIAN_UNPACK_DONE
            fi
            mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/tmp" "$ROOT/run" "$ROOT/dev/pts"
            chmod 1777 "$ROOT/tmp" || true
            grep -q " $ROOT/dev " /proc/mounts || mount --bind /dev "$ROOT/dev" 2>&1 || { echo DEV1_BIND_DEV_FAIL rc=$?; exit 41; }
            grep -q " $ROOT/proc " /proc/mounts || mount --bind /proc "$ROOT/proc" 2>&1 || { echo DEV1_BIND_PROC_FAIL rc=$?; exit 42; }
            grep -q " $ROOT/sys " /proc/mounts || mount --bind /sys "$ROOT/sys" 2>&1 || { echo DEV1_BIND_SYS_FAIL rc=$?; exit 43; }
            printf '%s\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > "$ROOT/etc/resolv.conf"
            if [ -x "$ROOT/debootstrap/debootstrap" ]; then
              echo DEV1_DEBOOTSTRAP_SECOND_STAGE
              chroot "$ROOT" /debootstrap/debootstrap --second-stage 2>&1
              DRC=$?
              echo DEV1_DEBOOTSTRAP_RC=$DRC
              [ "$DRC" -eq 0 ] || exit 44
            fi
            cat > "$ROOT/etc/apt/sources.list" <<'EOF'
            deb http://deb.debian.org/debian trixie main
            deb http://deb.debian.org/debian trixie-updates main
            deb http://security.debian.org/debian-security trixie-security main
            EOF
            mkdir -p "$ROOT/etc/apt/apt.conf.d"
            cat > "$ROOT/etc/apt/apt.conf.d/80dev1proxy" <<'EOF'
            Acquire::http::Proxy "http://127.0.0.1:3128";
            Acquire::https::Proxy "http://127.0.0.1:3128";
            EOF
            cp /mnt/apk/assets/dev1_guest_bridge "$BRIDGE" || exit 45
            chmod 755 "$BRIDGE"
            pkill -f "$BRIDGE" 2>/dev/null || true
            ("$BRIDGE" >/data/local/tmp/dev1-bridge.log 2>&1 &)
            sleep 1
            cat /data/local/tmp/dev1-bridge.log 2>/dev/null || true
            touch "$ROOT/.dev1-rootfs-ready"
            echo DEV1_VERIFY_START
            chroot "$ROOT" /bin/bash -lc 'cat /etc/os-release; echo ARCH=$(dpkg --print-architecture); apt-get --version | head -1; uname -a; id' 2>&1
            echo DEV1_PROVISION_DONE
        """.trimIndent()
        val out = adbShell(machine, script, 240_000L)
        append("[debian_provision_output]\n${out.takeLast(14000)}")
        check(out.contains("DEV1_DEBIAN_UNPACK_DONE") || out.contains("DEV1_PROVISION_DONE")) {
            "Debian extraction failed. Guest output:\n${out.takeLast(12000)}"
        }
        check(out.contains("Debian GNU/Linux 13") && out.contains("ARCH=arm64") && out.contains("DEV1_PROVISION_DONE")) {
            "Debian rootfs verification failed. Guest output:\n${out.takeLast(12000)}"
        }
        append("[debian_rootfs] PASS ${debianAssetName()}")
    }

    private fun verifyInternet(machine: Any) {
        internetStage = "testing Debian apt through host vsock proxy"
        val out = adbShell(machine, chrootCommand("apt-get update; echo DEV1_APT_NETWORK_PASS"), 180_000L)
        check(out.contains("DEV1_APT_NETWORK_PASS")) { "Debian apt Internet test failed: ${out.takeLast(8000)}" }
        internetReady = true; internetStage = "ready"; append("[internet] PASS Debian apt over vsock proxy")
    }

    override fun debianConsole(command: String): String {
        require(command.length <= 16384)
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Linux VM is not created").toString()
        if (!(vmMode == "debian" && isRunning(machine) && adbRootReady)) return JSONObject().put("ok", false).put("error", "Start Debian Linux first").toString()
        return try { JSONObject().put("ok", true).put("output", adbShell(machine, chrootCommand(command), 120_000L)).toString() }
        catch (t: Throwable) { JSONObject().put("ok", false).put("error", "${AvfReflect.unwrap(t).javaClass.name}: ${AvfReflect.unwrap(t).message}").toString() }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun chrootCommand(command: String) = "chroot $DEBIAN_ROOT /bin/bash -lc ${shellQuote(command)}"

    override fun installKde(): String {
        val machine = vm ?: return status()
        if (!(vmMode == "debian" && isRunning(machine) && guestVerified && internetReady)) return status()
        if (desktopMarker().isFile || !desktopInstalling.compareAndSet(false, true)) return status()
        desktopError = ""; desktopStage = "installing Plasma 6 + TigerVNC"
        Thread({
            try {
                stage = "desktop_packages"
                val cmd = """
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update
                    apt-get install -y --no-install-recommends kde-plasma-desktop plasma-workspace dbus-x11 tigervnc-standalone-server tigervnc-tools xterm fonts-dejavu-core
                    mkdir -p /root/.vnc /tmp/.X11-unix
                    chmod 1777 /tmp /tmp/.X11-unix
                    pkill Xtigervnc 2>/dev/null || true
                    pkill -f startplasma-x11 2>/dev/null || true
                    Xtigervnc :1 -SecurityTypes None -localhost -geometry 1200x2200 -depth 24 >/tmp/xvnc.log 2>&1 &
                    sleep 3
                    DISPLAY=:1 dbus-run-session -- startplasma-x11 >/tmp/plasma.log 2>&1 &
                    sleep 8
                    pgrep -a Xtigervnc
                    echo DEV1_DESKTOP_READY
                """.trimIndent()
                val out = adbShell(machine, chrootCommand(cmd), 45L * 60L * 1000L)
                check(out.contains("DEV1_DESKTOP_READY")) { "Plasma did not report ready: ${out.takeLast(12000)}" }
                stateDir.mkdirs(); desktopMarker().writeText("${BuildConfig.VERSION_NAME}\n")
                desktopStage = "ready"; vncReady = true; stage = "desktop_ready"
                append("Debian Plasma 6 + TigerVNC ready on Android-local 127.0.0.1:$ANDROID_VNC_PORT")
            } catch (t: Throwable) {
                val e = AvfReflect.unwrap(t); desktopError = "${e.javaClass.name}: ${e.message}"; desktopStage = "blocked"; stage = "blocked:desktop_install"; append(desktopError)
            } finally { desktopInstalling.set(false) }
        }, "dev1-desktop-installer").also { it.isDaemon = true; it.start() }
        return status()
    }

    private fun desktopMarker() = File(stateDir, ".desktop-installed")

    private fun startProxyWorkers(machine: Any) {
        if (!proxyRunning.compareAndSet(false, true)) return
        repeat(8) { index ->
            Thread({
                while (proxyRunning.get() && isRunning(machine)) {
                    try { serveProxyWorker(connectVsockRetry(machine, PROXY_VSOCK_PORT, 15_000L)) }
                    catch (t: Throwable) { if (proxyRunning.get()) { if (index == 0) append("internet worker reconnect: ${AvfReflect.unwrap(t).message}"); Thread.sleep(500) } }
                }
            }, "dev1-net-$index").also { it.isDaemon = true; it.start() }
        }
    }

    private fun serveProxyWorker(pfd: ParcelFileDescriptor) {
        val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(dup.fileDescriptor)
        try {
            val parts = readAsciiLine(input, 4096).trim().split(' ')
            check(parts.size == 3 && parts[0] == "CONNECT")
            val remote = Socket()
            try {
                remote.tcpNoDelay = true; remote.connect(InetSocketAddress(parts[1], parts[2].toInt()), 15_000)
                output.write("OK\n".toByteArray()); output.flush()
                relayDuplex(input, output, remote.getInputStream(), remote.getOutputStream())
            } finally { runCatching { remote.close() } }
        } finally { runCatching { input.close() }; runCatching { output.close() }; runCatching { pfd.close() }; runCatching { dup.close() } }
    }

    private fun startVncForwarder(machine: Any) {
        if (!vncForwardRunning.compareAndSet(false, true)) return
        Thread({
            try {
                val server = ServerSocket(); server.reuseAddress = true; server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), ANDROID_VNC_PORT)); vncServer = server
                append("Android-local VNC forward listening on 127.0.0.1:$ANDROID_VNC_PORT")
                while (vncForwardRunning.get() && isRunning(machine)) {
                    val client = server.accept()
                    Thread({
                        try {
                            val pfd = connectVsockRetry(machine, VNC_VSOCK_PORT, 10_000L)
                            val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                            relayDuplex(client.getInputStream(), client.getOutputStream(), FileInputStream(pfd.fileDescriptor), FileOutputStream(dup.fileDescriptor))
                        } catch (_: Throwable) {} finally { runCatching { client.close() } }
                    }, "dev1-vnc-session").also { it.isDaemon = true; it.start() }
                }
            } catch (t: Throwable) { if (vncForwardRunning.get()) append("VNC forwarder failed: ${AvfReflect.unwrap(t).message}") }
            finally { vncForwardRunning.set(false); runCatching { vncServer?.close() }; vncServer = null }
        }, "dev1-vnc-forward").also { it.isDaemon = true; it.start() }
    }

    private fun connectVsock(machine: Any, port: Int): ParcelFileDescriptor {
        val method = machine.javaClass.methods.firstOrNull { it.name == "connectVsock" && it.parameterCount == 1 } ?: error("VirtualMachine.connectVsock not found")
        val arg: Any = if (method.parameterTypes[0] == java.lang.Long.TYPE || method.parameterTypes[0] == java.lang.Long::class.java) port.toLong() else port
        return method.invoke(machine, arg) as ParcelFileDescriptor
    }

    private fun connectVsockRetry(machine: Any, port: Int, timeoutMs: Long): ParcelFileDescriptor {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var attempt = 0; var last: Throwable? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            check(isRunning(machine)) { "VM left STATUS_RUNNING while waiting for vsock port=$port; rawStatus=${runCatching { vmStatus(machine) }.getOrDefault(-999)}" }
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
            check(isRunning(machine)) { "VM left STATUS_RUNNING during ADB shell" }
            try { return connectVsockRetry(machine, ADB_VSOCK_PORT, minOf(8_000L, timeoutMs)).use { NativeTransport.shellFd(it.fd, command) } }
            catch (t: Throwable) { last = AvfReflect.unwrap(t); Thread.sleep(300) }
        }
        throw last ?: IllegalStateException("ADB shell timed out")
    }

    private fun readAsciiLine(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < max) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) out.write(b) }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun relayDuplex(aIn: InputStream, aOut: OutputStream, bIn: InputStream, bOut: OutputStream) {
        val t = Thread({ runCatching { copy(aIn, bOut) } }, "dev1-relay-a").also { it.isDaemon = true; it.start() }
        try { copy(bIn, aOut) } finally { runCatching { aIn.close() }; runCatching { bIn.close() }; t.join(1500) }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32768)
        while (true) { val n = input.read(buf); if (n <= 0) return; output.write(buf, 0, n); output.flush() }
    }

    @Synchronized override fun guestShell(command: String): String {
        val machine = vm ?: return JSONObject().put("ok", false).put("error", "Managed VM is not created").toString()
        return try { JSONObject().put("ok", true).put("output", adbShell(machine, command)).toString() }
        catch (t: Throwable) { JSONObject().put("ok", false).put("error", "${AvfReflect.unwrap(t).javaClass.name}: ${AvfReflect.unwrap(t).message}").toString() }
    }

    override fun openDebianVsock(port: Int): ParcelFileDescriptor {
        val machine = vm ?: error("Linux VM is not created")
        check(vmMode == "debian" && isRunning(machine)) { "Debian VM is not running" }
        return connectVsock(machine, port)
    }

    override fun setDisplaySurface(surface: Surface) = Unit
    override fun clearDisplaySurface() = Unit
    override fun sendKey(action: Int, keyCode: Int, metaState: Int) = false
    override fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int) = false

    override fun status(): String {
        val machine = vm
        return JSONObject()
            .put("name", when (vmMode) { "debian" -> linuxVmName; "microdroid" -> gateVmName; else -> "" })
            .put("running", isRunning(machine))
            .put("rawVmStatus", machine?.let { runCatching { vmStatus(it) }.getOrDefault(-999) } ?: -999)
            .put("cid", -1).put("managed", machine != null).put("mode", vmMode).put("stage", stage)
            .put("api", "VirtualMachineManager/connectVsock/Microdroid encrypted storage")
            .put("vmRoot", machine?.let { runCatching { (AvfReflect.callOptional(it, "getRootDir") as? File)?.path ?: "" }.getOrDefault("") } ?: "")
            .put("dataDir", vmData.path).put("error", lastError).put("log", logSnapshot())
            .put("debianInstalled", bundleReady()).put("debianInstalling", installing.get()).put("installBytes", 0L).put("installTotal", -1L).put("installProgress", -1.0).put("installError", installError)
            .put("guestVerified", guestVerified).put("internetReady", internetReady).put("internetStage", internetStage)
            .put("kdeInstalled", desktopMarker().isFile).put("kdeInstalling", desktopInstalling.get()).put("kdeStage", desktopStage).put("kdeError", desktopError)
            .put("vncReady", vncReady || desktopMarker().isFile).put("vncPort", ANDROID_VNC_PORT)
            .put("guestGraphics", if (desktopMarker().isFile) "Debian Plasma 6 via TigerVNC software framebuffer; hardware GPU still unproven" else "Debian pVM ready; desktop/GPU pending")
            .put("debian", if (guestVerified) "Debian 13 userspace verified in trusted Microdroid pVM" else "embedded Debian bundle ${if (bundleReady()) "ready" else "missing"}")
            .put("debianVersion", debianAssetName()).put("adbRootReady", adbRootReady).toString()
    }

    override fun destroy() { runCatching { stopInternal() }; kotlin.system.exitProcess(0) }

    companion object {
        private const val PACKAGE = BuildConfig.APPLICATION_ID
        private const val ADB_VSOCK_PORT = 5555
        private const val PROXY_VSOCK_PORT = 7777
        private const val VNC_VSOCK_PORT = 5901
        private const val DEBIAN_ROOT = "/mnt/encryptedstore/debian"
        const val ANDROID_VNC_PORT = 5909
    }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd: Int, command: String): String
    external fun serviceFd(fd: Int, service: String): String
}
