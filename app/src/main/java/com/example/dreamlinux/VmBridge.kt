package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject

/**
 * Shizuku UserService running as shell UID 2000.
 *
 * Gate A intentionally uses only the managed VirtualMachine API. There is no CLI or direct
 * AF_VSOCK fallback: if the managed path fails, diagnostics preserve the exact failing stage.
 */
class VmBridge(private val appContext: Context) : IVmBridge.Stub() {
    constructor() : this(resolveApplicationContext())

    private var manager: Any? = null
    private var managedVm: Any? = null
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

    private val lock = Any()
    private val vmName = "dev2-gate-a"
    private val vmRoot = File("/data/local/tmp/dev2-linux/${appContext.packageName}")
    private val scopedContext: Context by lazy { ShellVmContext(appContext, vmRoot) }

    private fun append(value: String) = synchronized(lock) {
        log = (log + value + if (value.endsWith('\n')) "" else "\n").takeLast(48000)
    }

    private fun rootCause(t: Throwable): Throwable {
        var current = t
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    private fun fail(stage: String, throwable: Throwable): Nothing {
        val cause = rootCause(throwable)
        failureStage = stage
        val detail = "${cause.javaClass.name}: ${cause.message ?: "no message"}"
        append("[$stage] $detail")
        when (stage) {
            "vm_api_init" -> vmApiInit = "BLOCKED"
            "vm_creation" -> vmCreation = "BLOCKED"
            "vm_boot" -> vmBoot = "BLOCKED"
            "connect_vsock" -> connectVsock = "BLOCKED"
            "adb_handshake" -> adbHandshake = "BLOCKED"
            "guest_command" -> guestCommand = "BLOCKED"
        }
        throw IllegalStateException("$stage: $detail", cause)
    }

    private fun invoke(target: Any, method: String, vararg args: Any?): Any? {
        val candidate = target.javaClass.methods.firstOrNull { m ->
            m.name == method && m.parameterTypes.size == args.size && m.parameterTypes.indices.all { i ->
                val arg = args[i] ?: return@all !m.parameterTypes[i].isPrimitive
                boxed(m.parameterTypes[i]).isAssignableFrom(arg.javaClass)
            }
        } ?: throw NoSuchMethodException("${target.javaClass.name}.$method/${args.size}")
        candidate.isAccessible = true
        return candidate.invoke(target, *args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    private fun initializeManager(): Any {
        manager?.let { return it }
        try {
            vmRoot.mkdirs()
            check(vmRoot.isDirectory && vmRoot.canWrite()) {
                "Shell VM root is not writable: ${vmRoot.absolutePath}"
            }
            val managerClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
            val instance = managerClass.getConstructor(Context::class.java).newInstance(scopedContext)
            manager = instance
            vmApiInit = "PASS"
            failureStage = "none"
            append("[vm_api_init] PASS ${managerClass.name}")
            append("[vm_data_dir] ${scopedContext.dataDir.absolutePath}")
            return instance
        } catch (t: Throwable) {
            fail("vm_api_init", t)
        }
    }

    private fun buildConfig(): Any {
        val builderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
        val builder = builderClass.getConstructor(Context::class.java).newInstance(scopedContext)
        invoke(builder, "setProtectedVm", true)
        invoke(builder, "setPayloadBinaryName", "libdream_payload.so")
        invoke(builder, "setMemoryBytes", 256L * 1024L * 1024L)

        // DEBUG_LEVEL_FULL is 1 on current AVF, but use the actual runtime constant.
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val debugFull = configClass.getField("DEBUG_LEVEL_FULL").getInt(null)
        invoke(builder, "setDebugLevel", debugFull)
        try { invoke(builder, "setVmOutputCaptured", true) } catch (_: Throwable) { }
        return invoke(builder, "build") ?: error("VirtualMachineConfig.Builder.build returned null")
    }

    private fun createOrGetVm(): Any {
        managedVm?.let { return it }
        val mgr = initializeManager()
        try {
            val config = buildConfig()
            val vm = invoke(mgr, "getOrCreate", vmName, config)
                ?: error("VirtualMachineManager.getOrCreate returned null")
            managedVm = vm
            vmCreation = "PASS"
            failureStage = "none"
            append("[vm_creation] PASS name=$vmName")
            return vm
        } catch (t: Throwable) {
            fail("vm_creation", t)
        }
    }

    private fun statusValue(vm: Any): Int = (invoke(vm, "getStatus") as Number).toInt()

    private fun waitUntilRunning(vm: Any) {
        val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
        val runningValue = vmClass.getField("STATUS_RUNNING").getInt(null)
        val deadline = System.nanoTime() + 45_000_000_000L
        while (System.nanoTime() < deadline) {
            if (statusValue(vm) == runningValue) {
                running = true
                vmBoot = "PASS"
                failureStage = "none"
                append("[vm_boot] PASS status=RUNNING")
                return
            }
            Thread.sleep(250)
        }
        throw IllegalStateException("Timed out waiting 45s for VirtualMachine STATUS_RUNNING")
    }

    @Synchronized
    override fun startVm(): String {
        try {
            val vm = createOrGetVm()
            val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
            val runningValue = vmClass.getField("STATUS_RUNNING").getInt(null)
            if (statusValue(vm) != runningValue) {
                append("[vm_boot] invoking VirtualMachine.run()")
                invoke(vm, "run")
            }
            waitUntilRunning(vm)
            return status()
        } catch (t: Throwable) {
            if (failureStage == "none") fail("vm_boot", t) else throw t
        }
    }

    @Synchronized
    override fun stopVm(): String {
        val vm = managedVm
        if (vm != null) {
            try {
                invoke(vm, "stop")
                running = false
                append("[vm_lifecycle] stopped managed VM")
            } catch (t: Throwable) {
                val cause = rootCause(t)
                failureStage = "vm_stop"
                append("[vm_stop] ${cause.javaClass.name}: ${cause.message}")
                throw IllegalStateException("vm_stop: ${cause.message}", cause)
            }
        }
        return status()
    }

    private fun connectAdb(vm: Any): ParcelFileDescriptor {
        try {
            val pfd = invoke(vm, "connectVsock", 5555L) as? ParcelFileDescriptor
                ?: error("VirtualMachine.connectVsock returned no ParcelFileDescriptor")
            connectVsock = "PASS"
            vsockFdReceived = "PASS"
            failureStage = "none"
            append("[connect_vsock] PASS port=5555 fd=${pfd.fd}")
            return pfd
        } catch (t: Throwable) {
            vsockFdReceived = "BLOCKED"
            fail("connect_vsock", t)
        }
    }

    @Synchronized
    override fun guestShell(command: String): String {
        require(command.isNotBlank() && command.length <= 4096) { "Invalid command length" }
        val vm = managedVm ?: return JSONObject()
            .put("ok", false).put("error", "Managed VM has not been created").toString()
        return try {
            val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
            val runningValue = vmClass.getField("STATUS_RUNNING").getInt(null)
            check(statusValue(vm) == runningValue) { "Managed VM is not running" }
            connectAdb(vm).use { pfd ->
                val output = NativeTransport.shellFd(pfd.fd, command)
                adbHandshake = "PASS"
                guestCommand = "PASS"
                failureStage = "none"
                append("[guest_command] PASS command=${command.take(96)}")
                JSONObject().put("ok", true).put("output", output).toString()
            }
        } catch (t: Throwable) {
            val cause = rootCause(t)
            if (connectVsock != "PASS") {
                JSONObject().put("ok", false).put("error", "connect_vsock: ${cause.message}").toString()
            } else {
                adbHandshake = "BLOCKED"
                guestCommand = "BLOCKED"
                failureStage = "adb_handshake"
                append("[adb_handshake] ${cause.javaClass.name}: ${cause.message}")
                JSONObject().put("ok", false).put("error", "adb_handshake: ${cause.message}").toString()
            }
        }
    }

    @Synchronized
    override fun status(): String {
        managedVm?.let { vm ->
            try {
                val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
                val runningValue = vmClass.getField("STATUS_RUNNING").getInt(null)
                running = statusValue(vm) == runningValue
            } catch (_: Throwable) { }
        }
        return JSONObject()
            .put("name", vmName)
            .put("running", running)
            .put("cid", -1)
            .put("log", synchronized(lock) { log })
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
            .put("guestGraphics", "NOT TESTED")
            .put("debian", "NOT TESTED")
            .toString()
    }

    override fun destroy() {
        try { stopVm() } catch (_: Throwable) { }
        try { (managedVm as? AutoCloseable)?.close() } catch (_: Throwable) { }
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
    }

    companion object {
        private fun resolveApplicationContext(): Context {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication").invoke(null) as? Context
            return currentApplication ?: error("Shizuku UserService did not provide an application Context")
        }
    }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd: Int, command: String): String
}
