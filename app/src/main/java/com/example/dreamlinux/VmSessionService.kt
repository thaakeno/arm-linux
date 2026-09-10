package com.example.dreamlinux

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.Surface
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

enum class RuntimeBackend { UML_VENUS, AVF_LEGACY }

data class SessionState(
    val connected:Boolean=false,
    val running:Boolean=false,
    val cid:Int=-1,
    val name:String="",
    val mode:String="none",
    val stage:String="idle",
    val api:String="",
    val vmRoot:String="",
    val console:String="",
    val terminal:String="",
    val debianTerminal:String="",
    val message:String="Ready to start Vessel",
    val busy:Boolean=false,
    val debianStarting:Boolean=false,
    val debianInstalled:Boolean=false,
    val debianInstalling:Boolean=false,
    val installProgress:Double=-1.0,
    val installBytes:Long=0L,
    val installTotal:Long=-1L,
    val kdeInstalled:Boolean=false,
    val kdeInstalling:Boolean=false,
    val kdeStage:String="not installed",
    val capabilities:String="Not checked",
    val graphics:String="Venus transport proven; APK native presenter integration in progress",
    val internetReady:Boolean=false,
    val internetStage:String="not started",
    val backend:RuntimeBackend=RuntimeBackend.UML_VENUS
)

class VmSessionService : Service() {
    companion object { val state=MutableStateFlow(SessionState()); var active:VmSessionService?=null }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var bridge:IVmBridge?=null
    private var pendingSurface:Surface?=null
    private var successfulGateACommands=0
    private var reconnectProbeArmed=false

    private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this,AsyncVmBridge::class.java))
        .daemon(false).processNameSuffix("vessel_vm_bridge").debuggable(true).version(BuildConfig.VERSION_CODE) }

    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            bridge=IVmBridge.Stub.asInterface(binder)
            state.value=state.value.copy(connected=true,message="System bridge connected")
            scope.launch { refresh(); probeCapabilities() }
        }
        override fun onServiceDisconnected(name:ComponentName) {
            bridge=null
            state.value=state.value.copy(connected=false,running=false,debianStarting=false,message="System bridge disconnected; Linux data retained")
        }
    }

    override fun onCreate() {
        super.onCreate(); active=this
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vm","Vessel Linux session",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"vm").setContentTitle("Vessel")
            .setContentText("ARM64 Linux session").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(intent).build())
        scope.launch { state.collect { current -> withContext(Dispatchers.IO) {
            File(filesDir,"verification-runtime.json").writeText(JSONObject()
                .put("versionName",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE)
                .put("commit",BuildConfig.GIT_COMMIT).put("branch",BuildConfig.GIT_BRANCH)
                .put("backend",current.backend.name)
                .put("running",current.running).put("name",current.name).put("mode",current.mode)
                .put("stage",current.stage).put("api",current.api).put("vmRoot",current.vmRoot)
                .put("linuxStarting",current.debianStarting)
                .put("debianBundleReady",current.debianInstalled)
                .put("desktopInstalled",current.kdeInstalled).put("desktopInstalling",current.kdeInstalling).put("desktopStage",current.kdeStage)
                .put("internetReady",current.internetReady).put("internetStage",current.internetStage)
                .put("graphics",current.graphics).put("capabilities",current.capabilities)
                .put("message",current.message).put("reconnect",if(reconnectProbeArmed)"PENDING" else if(successfulGateACommands>1)"PASS" else "NOT TESTED")
                .toString(2))
        } } }
        scope.launch { while(isActive) { delay(700); if(bridge!=null) refresh() } }
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(bridge==null) try {
            check(Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) { "Authorize Shizuku first" }
            Shizuku.bindUserService(args,connection)
        } catch(e:Exception) { state.value=state.value.copy(message=e.message?:"Shizuku unavailable") }
        return START_STICKY
    }

    private fun applyStatus(raw:String) {
        val obj=JSONObject(raw)
        val error=obj.optString("error")
        val installError=obj.optString("installError")
        val desktopError=obj.optString("kdeError")
        val linuxStarting=obj.optBoolean("debianStarting")
        val msg=when {
            error.isNotBlank() -> error
            desktopError.isNotBlank() -> desktopError
            installError.isNotBlank() -> installError
            linuxStarting -> "Starting Debian · ${obj.optLong("startupElapsedSeconds",0L)}s · ${humanStage(obj.optString("stage"))}"
            obj.optBoolean("kdeInstalling") -> "Desktop: ${obj.optString("kdeStage","working")}"
            else -> humanStage(obj.optString("stage","unknown"))
        }
        state.value=state.value.copy(
            running=obj.optBoolean("running"),cid=obj.optInt("cid",-1),name=obj.optString("name"),
            mode=obj.optString("mode","none"),stage=obj.optString("stage","unknown"),api=obj.optString("api"),
            vmRoot=obj.optString("vmRoot"),console=obj.optString("log"),message=msg,
            debianStarting=linuxStarting,
            debianInstalled=obj.optBoolean("debianInstalled"),debianInstalling=obj.optBoolean("debianInstalling"),
            installProgress=obj.optDouble("installProgress",-1.0),installBytes=obj.optLong("installBytes",0L),
            installTotal=obj.optLong("installTotal",-1L),kdeInstalled=obj.optBoolean("kdeInstalled"),
            kdeInstalling=obj.optBoolean("kdeInstalling"),kdeStage=obj.optString("kdeStage","not installed"),
            graphics=obj.optString("guestGraphics",state.value.graphics),
            internetReady=obj.optBoolean("internetReady"),internetStage=obj.optString("internetStage","not started"))
    }

    private fun humanStage(stage:String):String = when(stage) {
        "config:debian_pvm" -> "Preparing Linux runtime"
        "vm_create:debian" -> "Creating Debian runtime"
        "vm_start:debian" -> "Starting Linux"
        "vm_wait_running:debian" -> "Waiting for guest"
        "running:debian" -> "Guest running"
        "microdroid_adb_root" -> "Preparing guest permissions"
        "debian_provision" -> "Preparing Debian ARM64 userspace"
        "internet_bridge" -> "Connecting Linux networking"
        "debian_ready" -> "Debian ready"
        "desktop_packages" -> "Installing KDE Plasma"
        "desktop_ready" -> "KDE Plasma ready"
        "guest_command_pass" -> "Guest command passed"
        else -> if(stage.startsWith("blocked:")) "Blocked: ${stage.removePrefix("blocked:")}" else "Stage: $stage"
    }

    private suspend fun refresh() {
        try { val b=bridge?:return; applyStatus(withContext(Dispatchers.IO){b.status()}) }
        catch(e:Exception) { state.value=state.value.copy(message=e.message?:"Status unavailable") }
    }

    private suspend fun waitForVmRunning(b:IVmBridge, timeoutMs:Long=30_000L):String {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        var lastStage="unknown"
        while(SystemClock.elapsedRealtime()<deadline) {
            val raw=withContext(Dispatchers.IO){b.status()}
            val obj=JSONObject(raw)
            lastStage=obj.optString("stage","unknown")
            applyStatus(raw)
            if(obj.optBoolean("running")) return raw
            val error=obj.optString("error")
            if(error.isNotBlank() || lastStage.startsWith("blocked:")) throw IllegalStateException(if(error.isNotBlank()) error else "VM blocked at $lastStage")
            delay(200)
        }
        throw IllegalStateException("Timed out waiting for Linux runtime; last stage=$lastStage")
    }

    private suspend fun waitForLinuxStartup(b:IVmBridge, timeoutMs:Long=12L*60L*1000L) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<deadline) {
            val raw=withContext(Dispatchers.IO){b.status()}
            val obj=JSONObject(raw)
            applyStatus(raw)
            if(!obj.optBoolean("debianStarting")) {
                val error=obj.optString("error")
                if(error.isNotBlank() || obj.optString("stage").startsWith("blocked:")) {
                    throw IllegalStateException(if(error.isNotBlank()) error else "Linux startup blocked at ${obj.optString("stage")}")
                }
                return
            }
            delay(400)
        }
        throw IllegalStateException("Debian startup exceeded 12 minutes; check diagnostics")
    }

    fun startVm()=operation { b ->
        applyStatus(withContext(Dispatchers.IO){b.startVm()})
        waitForVmRunning(b,45_000L)
        state.value=state.value.copy(message="Guest runtime running")
    }

    fun stopVm()=operation { b ->
        pendingSurface=null
        applyStatus(withContext(Dispatchers.IO){b.stopVm()})
        if(successfulGateACommands>0) reconnectProbeArmed=true
        state.value=state.value.copy(message="Linux stopped; persistent data retained")
    }

    fun installDebian()=operation("linux") { b -> applyStatus(withContext(Dispatchers.IO){b.installDebian()}) }

    fun startDebian(width:Int,height:Int,dpi:Int,refreshRate:Int)=operation("linux") { b ->
        pendingSurface=null
        state.value=state.value.copy(message="Launching Debian ARM64…")
        applyStatus(withContext(Dispatchers.IO){b.startDebian(width,height,dpi,refreshRate)})
        waitForLinuxStartup(b)
        refresh()
    }

    fun startDebianDiagnostic()=operation("linux") { b ->
        pendingSurface=null
        state.value=state.value.copy(message="Launching Debian diagnostic…")
        applyStatus(withContext(Dispatchers.IO){b.startDebianDiagnostic()})
        waitForLinuxStartup(b)
        refresh()
    }

    fun installKde()=operation("linux") { b ->
        applyStatus(withContext(Dispatchers.IO){b.installKde()})
        refresh()
    }

    fun debianConsole(command:String)=operation("linux") { b ->
        waitForVmRunning(b,60_000L)
        val reply=withContext(Dispatchers.IO){b.debianConsole(command)}
        val result=JSONObject(reply)
        check(result.optBoolean("ok")) { result.optString("error","Debian command failed") }
        val output=result.optString("output")
        state.value=state.value.copy(
            debianTerminal=(state.value.debianTerminal+"\n# $command\n$output").takeLast(256000),
            message="Debian command completed")
        refresh()
    }

    fun probeCapabilities()=operation { b ->
        val raw=withContext(Dispatchers.IO){b.inspectCapabilities()}
        val obj=JSONObject(raw)
        val text=if(obj.optBoolean("ok")) buildString {
            append("AVF caps=").append(obj.optInt("capabilities"))
            append(" · pVM=").append(obj.optBoolean("protectedVm"))
            append(" · non-pVM=").append(obj.optBoolean("nonProtectedVm"))
            append(" · custom=").append(obj.optBoolean("customImageApi"))
            append(" · GPU API=").append(obj.optBoolean("gpuConfigApi"))
            append(" · display API=").append(obj.optBoolean("displayConfigApi"))
        } else "Capability probe failed: ${obj.optString("error")}" 
        state.value=state.value.copy(capabilities=text,message=text)
        refresh()
    }

    fun shell(command:String)=operation { b ->
        if(!state.value.running || state.value.mode!="microdroid") applyStatus(withContext(Dispatchers.IO){b.startVm()})
        waitForVmRunning(b,45_000L)
        val reply=withContext(Dispatchers.IO){b.guestShell(command)}
        val result=JSONObject(reply)
        check(result.optBoolean("ok")) { result.optString("error","Guest command failed") }
        val output=result.optString("output")
        successfulGateACommands++
        val reconnected=reconnectProbeArmed
        if(reconnected) reconnectProbeArmed=false
        state.value=state.value.copy(
            terminal=(state.value.terminal+"\n$ $command\n$output").takeLast(256000),
            message=if(reconnected)"Reconnect verified" else "Guest command completed")
        refresh()
    }

    fun attachSurface(surface:Surface) { pendingSurface=surface }
    fun detachSurface(surface:Surface?=null) { if(surface==null||pendingSurface===surface) pendingSurface=null }
    fun sendKey(action:Int,keyCode:Int,metaState:Int):Boolean = false
    fun sendTouch(action:Int,x:Float,y:Float,pointerId:Int):Boolean = false

    private fun operation(failureChannel:String="microdroid",block:suspend (IVmBridge)->Unit) {
        if(state.value.busy) return
        val b=bridge?:return
        state.value=state.value.copy(busy=true)
        scope.launch { try { block(b) } catch(e:Exception) {
            val error=e.message?:e.javaClass.simpleName
            state.value=if(failureChannel=="linux") state.value.copy(message=error,debianTerminal=(state.value.debianTerminal+"\nERROR: $error\n").takeLast(120000))
                else state.value.copy(message=error,terminal=(state.value.terminal+"\nERROR: $error\n").takeLast(120000))
            refresh()
            state.value=state.value.copy(message=error)
        } finally { state.value=state.value.copy(busy=false) } }
    }

    override fun onDestroy() {
        active=null; scope.cancel(); pendingSurface=null
        if(bridge!=null) try { Shizuku.unbindUserService(args,connection,true) } catch(_:Exception) {}
        bridge=null; super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
