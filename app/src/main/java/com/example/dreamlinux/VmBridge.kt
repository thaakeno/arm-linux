package com.example.dreamlinux

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Runs as Shizuku shell. Owns only the Process it creates. */
class VmBridge : IVmBridge.Stub() {
    private var vm: Process? = null
    private var name = ""
    private var cid = -1
    private var log = ""
    private val lock = Any()
    private fun append(value: String) = synchronized(lock) { log = (log + value).takeLast(48000) }

    @Synchronized override fun startVm(): String {
        if (vm?.isAlive == true) return status()
        name = "DreamLinux-" + UUID.randomUUID().toString().take(8)
        cid = -1
        synchronized(lock) { log = "" }
        val work = File("/data/local/tmp/$name")
        check(work.mkdir()) { "Cannot create dedicated VM directory" }
        val process = ProcessBuilder("/apex/com.android.virt/bin/vm", "run-microdroid",
            "--protected", "--mem", "256", "--name", name, "--work-dir", work.path)
            .redirectErrorStream(true).start()
        vm = process
        Thread({ process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { append(it + "\n") }
        } }, "vm-console").start()
        return status()
    }

    @Synchronized override fun stopVm(): String {
        vm?.let { if (it.isAlive) { it.destroy(); if (!it.waitFor(4, TimeUnit.SECONDS)) it.destroyForcibly() } }
        vm = null
        cid = -1
        return status()
    }

    @Synchronized override fun status(): String {
        if (vm?.isAlive == true && cid < 0) {
            val p = ProcessBuilder("/apex/com.android.virt/bin/vm", "list").redirectErrorStream(true).start()
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                val listing = p.inputStream.bufferedReader().readText()
                cid = Regex("name: \\\"" + Regex.escape(name) + "\\\",\\s+cid: (\\d+)")
                    .find(listing)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            } else p.destroyForcibly()
        }
        return JSONObject().put("name", name).put("running", vm?.isAlive == true)
            .put("cid", cid).put("log", synchronized(lock) { log })
            .put("guestGraphics", "unproven").put("debian", "not established").toString()
    }

    @Synchronized override fun guestShell(command: String): String {
        require(command.length <= 4096) { "Command too long" }
        check(vm?.isAlive == true && cid > 0) { "Owned guest is not ready" }
        return try {
            JSONObject().put("ok",true).put("output",NativeTransport.shell(cid, command)).toString()
        } catch(e:Exception) {
            JSONObject().put("ok",false).put("error",e.message?:e.javaClass.simpleName).toString()
        }
    }

    override fun destroy() { stopVm(); kotlin.system.exitProcess(0) }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shell(cid: Int, command: String): String
}
