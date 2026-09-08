package com.example.dreamlinux

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject

/** Runs inside the Shizuku UserService as shell and owns the managed AVF VM. */
class VmBridge : IVmBridge.Stub() {
    private val vmName = "dev1-gate-a"
    private val vmData = File("/data/local/tmp/dev1-linux-vmm")
    private var manager: Any? = null
    private var vm: Any? = null
    private var stage = "idle"
    private var log = ""
    private var lastError = ""
    private val lock = Any()

    private fun append(value: String) = synchronized(lock) { log = (log + value + "\n").takeLast(64000) }

    private class RedirectedDataContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getDataDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private fun baseContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val app = activityThread.getMethod("currentApplication").invoke(null) as? Context
            ?: error("ActivityThread.currentApplication() returned null")
        return if (app.packageName == "com.example.dreamlinux") app
        else app.createPackageContext("com.example.dreamlinux", Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun unwrap(t: Throwable): Throwable {
        var x=t
        while (x is InvocationTargetException && x.targetException != null) x=x.targetException
        return x
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val methods=target.javaClass.methods.filter { it.name==name && it.parameterCount==args.size }
        val method=methods.firstOrNull { m -> m.parameterTypes.indices.all { i ->
            val a=args[i]
            a==null || m.parameterTypes[i].isPrimitive || m.parameterTypes[i].isAssignableFrom(a.javaClass)
        } } ?: error("Method ${target.javaClass.name}.$name/${args.size} not found")
        return try { method.invoke(target,*args) } catch(t:Throwable) { throw unwrap(t) }
    }

    private fun ensureVm(): Any {
        vm?.let { return it }
        stage="context"
        check(vmData.mkdirs() || vmData.isDirectory) { "Cannot create ${vmData.path}" }
        val context=RedirectedDataContext(baseContext(),vmData)
        append("context package=${context.packageName} dataDir=${context.dataDir}")

        stage="manager_init"
        val managerClass=Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val mgr=managerClass.getConstructor(Context::class.java).newInstance(context)
        manager=mgr
        append("VirtualMachineManager constructed with redirected data directory")

        stage="config"
        val configClass=Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val builderClass=Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
        val builder=builderClass.getConstructor(Context::class.java).newInstance(context)
        invoke(builder,"setProtectedVm",true)
        val debugFull=configClass.getField("DEBUG_LEVEL_FULL").getInt(null)
        invoke(builder,"setDebugLevel",debugFull)
        invoke(builder,"setMemoryBytes",512L*1024L*1024L)
        invoke(builder,"setPayloadBinaryName","libdev1_payload.so")
        val config=invoke(builder,"build") ?: error("VirtualMachineConfig build returned null")

        stage="vm_create"
        val created=managerClass.getMethod("getOrCreate",String::class.java,configClass).invoke(mgr,vmName,config)
            ?: error("getOrCreate returned null")
        vm=created
        append("managed VM acquired: $vmName")
        return created
    }

    private fun vmStatus(machine: Any): Int = (invoke(machine,"getStatus") as Number).toInt()
    private fun runningStatus(machine: Any): Int = machine.javaClass.getField("STATUS_RUNNING").getInt(null)

    @Synchronized override fun startVm(): String {
        lastError=""
        return try {
            val machine=ensureVm()
            if(vmStatus(machine)!=runningStatus(machine)) {
                stage="vm_start"
                invoke(machine,"run")
                append("VirtualMachine.run() accepted")
            }
            stage="running"
            status()
        } catch(t:Throwable) {
            val e=unwrap(t); lastError="${e.javaClass.name}: ${e.message}"; stage="blocked:$stage"; append(lastError)
            status()
        }
    }

    @Synchronized override fun stopVm(): String {
        lastError=""
        try {
            vm?.let { machine -> if(vmStatus(machine)==runningStatus(machine)) invoke(machine,"stop") }
            stage="stopped"
            append("VM stopped")
        } catch(t:Throwable) {
            val e=unwrap(t); lastError="${e.javaClass.name}: ${e.message}"; stage="blocked:stop"; append(lastError)
        }
        return status()
    }

    @Synchronized override fun status(): String {
        var running=false
        var root=""
        try {
            vm?.let { machine ->
                running=vmStatus(machine)==runningStatus(machine)
                root=(runCatching { invoke(machine,"getRootDir") as? File }.getOrNull()?.path ?: "")
            }
        } catch(t:Throwable) {
            val e=unwrap(t); lastError="${e.javaClass.name}: ${e.message}"
        }
        return JSONObject()
            .put("name",vmName).put("running",running).put("cid",-1)
            .put("managed",vm!=null).put("stage",stage).put("api","VirtualMachineManager/connectVsock")
            .put("vmRoot",root).put("dataDir",vmData.path).put("error",lastError)
            .put("log",synchronized(lock){log}).put("guestGraphics","unproven")
            .put("debian","not established").toString()
    }

    @Synchronized override fun guestShell(command: String): String {
        require(command.length<=4096) { "Command too long" }
        val machine=vm ?: return JSONObject().put("ok",false).put("error","Managed VM is not created").toString()
        check(vmStatus(machine)==runningStatus(machine)) { "Managed VM is not running" }
        stage="connect_vsock"
        var last:Throwable?=null
        repeat(30) { attempt ->
            try {
                val method=machine.javaClass.methods.firstOrNull { it.name=="connectVsock" && it.parameterCount==1 }
                    ?: error("VirtualMachine.connectVsock not found")
                val arg:Any = if(method.parameterTypes[0]==java.lang.Long.TYPE) 5555L else 5555
                val pfd=method.invoke(machine,arg) as ParcelFileDescriptor
                pfd.use {
                    stage="adb_shell"
                    val output=NativeTransport.shellFd(it.fd,command)
                    stage="guest_command_pass"
                    append("guest command passed: ${command.take(120)}")
                    return JSONObject().put("ok",true).put("output",output).toString()
                }
            } catch(t:Throwable) {
                last=unwrap(t)
                if(attempt<29) Thread.sleep(500)
            }
        }
        val e=last ?: IllegalStateException("connectVsock failed")
        lastError="${e.javaClass.name}: ${e.message}"; stage="blocked:connect_vsock"; append(lastError)
        return JSONObject().put("ok",false).put("error",lastError).toString()
    }

    override fun destroy() { stopVm(); kotlin.system.exitProcess(0) }
}

object NativeTransport {
    init { System.loadLibrary("dream_transport") }
    external fun shellFd(fd:Int, command:String):String
}
