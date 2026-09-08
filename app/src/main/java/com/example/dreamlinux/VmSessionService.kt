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
    val message:String="Connect Shizuku to begin",
    val busy:Boolean=false,
    val debianInstalled:Boolean=false,
    val debianInstalling:Boolean=false,
    val installProgress:Double=-1.0,
    val installBytes:Long=0L,
    val installTotal:Long=-1L,
    val kdeInstalled:Boolean=false,
    val kdeInstalling:Boolean=false,
    val kdeStage:String="not installed",
    val capabilities:String="Not checked",
    val graphics:String="unproven"
)

class VmSessionService : Service() {
    companion object { val state=MutableStateFlow(SessionState()); var active:VmSessionService?=null }
    private var displayRequested = true
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var bridge:IVmBridge?=null
    private var pendingSurface:Surface?=null
    private var successfulGateACommands=0
    private var reconnectProbeArmed=false

    // Bump this whenever the Shizuku-side bridge changes. Otherwise Shizuku may keep an old
    // UserService process alive across APK updates and we end up testing stale VmBridge code.
    private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this,VmBridge::class.java))
        .daemon(false).processNameSuffix("vm_bridge").debuggable(true).version(BuildConfig.VERSION_CODE) }

    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            bridge=IVmBridge.Stub.asInterface(binder)
            state.value=state.value.copy(connected=true,message="AVF bridge connected")
            scope.launch { refresh(); probeCapabilities() }
        }
        override fun onServiceDisconnected(name:ComponentName) {
            bridge=null
            state.value=state.value.copy(connected=false,running=false,message="Shizuku bridge disconnected; VM files retained")
        }
    }

    override fun onCreate() {
        super.onCreate(); active=this
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vm","DEV 1 LINUX session",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"vm").setContentTitle("DEV 1 LINUX")
            .setContentText("Local AVF Linux session").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(intent).build())
        scope.launch { state.collect { current -> withContext(Dispatchers.IO) {
            File(filesDir,"verification-runtime.json").writeText(JSONObject()
                .put("versionName",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE)
                .put("commit",BuildConfig.GIT_COMMIT).put("branch",BuildConfig.GIT_BRANCH)
                .put("running",current.running).put("name",current.name).put("mode",current.mode)
                .put("stage",current.stage).put("api",current.api).put("vmRoot",current.vmRoot)
                .put("debianInstalled",current.debianInstalled).put("debianInstalling",current.debianInstalling)
                .put("kdeInstalled",current.kdeInstalled).put("kdeInstalling",current.kdeInstalling).put("kdeStage",current.kdeStage)
                .put("graphics",current.graphics).put("capabilities",current.capabilities)
                .put("message",current.message).put("reconnect",if(reconnectProbeArmed)"PENDING" else if(successfulGateACommands>1)"PASS" else "NOT TESTED")
                .toString(2))
        } } }
        scope.launch { while(isActive) { delay(1200); if(bridge!=null&&!state.value.busy) refresh() } }
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
        val kdeError=obj.optString("kdeError")
        val msg=when {
            kdeError.isNotBlank() -> kdeError
            installError.isNotBlank() -> installError
            error.isNotBlank() -> error
            obj.optBoolean("kdeInstalling") -> "KDE: ${obj.optString("kdeStage","working")}"
            obj.optBoolean("debianInstalling") -> {
                val p=obj.optDouble("installProgress",-1.0)
                if(p>=0) "Installing Debian ${(p*100).toInt()}%" else "Installing Debian"
            }
            else -> "Stage: ${obj.optString("stage","unknown")}"
        }
        state.value=state.value.copy(
            running=obj.optBoolean("running"),cid=obj.optInt("cid",-1),name=obj.optString("name"),
            mode=obj.optString("mode","none"),stage=obj.optString("stage","unknown"),api=obj.optString("api"),
            vmRoot=obj.optString("vmRoot"),console=obj.optString("log"),message=msg,
            debianInstalled=obj.optBoolean("debianInstalled"),debianInstalling=obj.optBoolean("debianInstalling"),
            installProgress=obj.optDouble("installProgress",-1.0),installBytes=obj.optLong("installBytes",0L),
            installTotal=obj.optLong("installTotal",-1L),kdeInstalled=obj.optBoolean("kdeInstalled"),
            kdeInstalling=obj.optBoolean("kdeInstalling"),kdeStage=obj.optString("kdeStage","not installed"),
            graphics=obj.optString("guestGraphics","unproven"))
    }

    private suspend fun refresh() {
        try { val b=bridge?:return; applyStatus(withContext(Dispatchers.IO){b.status()}) }
        catch(e:Exception) { state.value=state.value.copy(message=e.message?:"Status unavailable") }
    }

    private suspend fun waitForVmRunning(b:IVmBridge, timeoutMs:Long=30_000L):String {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        var lastRaw=""
        var lastStage="unknown"
        while(SystemClock.elapsedRealtime()<deadline) {
            val raw=withContext(Dispatchers.IO){b.status()}
            lastRaw=raw
            val obj=JSONObject(raw)
            lastStage=obj.optString("stage","unknown")
            applyStatus(raw)
            if(obj.optBoolean("running")) return raw

            val error=obj.optString("error")
            if(error.isNotBlank() || lastStage.startsWith("blocked:")) {
                throw IllegalStateException(if(error.isNotBlank()) error else "VM startup blocked at $lastStage")
            }
            if(lastStage.startsWith("stopped:") || lastStage.startsWith("deleted:")) {
                throw IllegalStateException("VM stopped before becoming ready (stage=$lastStage)")
            }
            delay(200)
        }
        val suffix=if(lastRaw.isBlank()) "" else "; last status=$lastStage"
        throw IllegalStateException("Timed out waiting for AVF VM to reach STATUS_RUNNING$suffix")
    }

    fun startVm()=operation { b ->
        applyStatus(withContext(Dispatchers.IO){b.startVm()})
        waitForVmRunning(b,45_000L)
        state.value=state.value.copy(message="Microdroid VM running; guest endpoint will be retried as needed")
    }
    fun stopVm()=operation { b ->
        pendingSurface=null
        applyStatus(withContext(Dispatchers.IO){b.stopVm()})
        if(successfulGateACommands>0) reconnectProbeArmed=true
        state.value=state.value.copy(message="Managed VM stopped; VM data retained")
    }
    fun installDebian()=operation("debian") { b -> applyStatus(withContext(Dispatchers.IO){b.installDebian()}) }
    fun startDebian(width:Int,height:Int,dpi:Int,refreshRate:Int)=operation("debian") { b ->
        displayRequested=true
        applyStatus(withContext(Dispatchers.IO){b.startDebian(width,height,dpi,refreshRate)})
        waitForVmRunning(b,60_000L)
        pendingSurface?.let { surface -> if(surface.isValid) withContext(Dispatchers.IO){b.setDisplaySurface(surface)} }
        refresh()
    }
    fun startDebianDiagnostic()=operation("debian") { b ->
        displayRequested=false
        pendingSurface=null
        applyStatus(withContext(Dispatchers.IO){b.startDebianDiagnostic()})
        waitForVmRunning(b,60_000L)
        state.value=state.value.copy(message="Headless diagnostic: VMM started; Debian boot still unverified")
    }
    fun installKde()=operation("debian") { b ->
        applyStatus(withContext(Dispatchers.IO){b.installKde()})
        refresh()
    }
    fun debianConsole(command:String)=operation("debian") { b ->
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
            append(" · display client class=").append(obj.optBoolean("displayServiceApi"))
        } else "Capability probe failed: ${obj.optString("error")}" 
        state.value=state.value.copy(capabilities=text,message=text)
        refresh()
    }
    fun shell(command:String)=operation { b ->
        if(!state.value.running || state.value.mode!="microdroid") {
            applyStatus(withContext(Dispatchers.IO){b.startVm()})
        }
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
            message=if(reconnected)"Reconnect verified: guest command succeeded after VM restart" else "Guest command completed through sanctioned vsock FD")
        refresh()
    }

    fun attachSurface(surface:Surface) {
        pendingSurface=surface
        val b=bridge?:return
        if(!displayRequested||state.value.mode!="debian"||!state.value.running||!surface.isValid)return
        scope.launch { runCatching { withContext(Dispatchers.IO){b.setDisplaySurface(surface)} }
            .onFailure { state.value=state.value.copy(message="Display attach failed: ${it.message}") }
            refresh() }
    }
    fun detachSurface(surface:Surface?=null) {
        if(surface==null||pendingSurface===surface) pendingSurface=null
        val b=bridge?:return
        scope.launch { runCatching { withContext(Dispatchers.IO){b.clearDisplaySurface()} }; refresh() }
    }
    fun sendKey(action:Int,keyCode:Int,metaState:Int):Boolean = runCatching {
        bridge?.sendKey(action,keyCode,metaState) ?: false
    }.getOrDefault(false)
    fun sendTouch(action:Int,x:Float,y:Float,pointerId:Int):Boolean = runCatching {
        bridge?.sendTouch(action,x,y,pointerId) ?: false
    }.getOrDefault(false)

    private fun operation(failureChannel:String="microdroid",block:suspend (IVmBridge)->Unit) {
        if(state.value.busy) return
        val b=bridge?:return
        state.value=state.value.copy(busy=true)
        scope.launch { try { block(b) } catch(e:Exception) {
            val error=e.message?:e.javaClass.simpleName
            state.value=if(failureChannel=="debian") state.value.copy(message=error,debianTerminal=(state.value.debianTerminal+"\nERROR: $error\n").takeLast(120000))
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
