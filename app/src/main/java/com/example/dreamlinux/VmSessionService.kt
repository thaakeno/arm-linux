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
    val console:String="",
    val terminal:String="",
    val debianTerminal:String="",
    val message:String="Connect Shizuku to begin",
    val busy:Boolean=false,
    val vmApiInit:String="PENDING",
    val vmDataDir:String="",
    val vmCreation:String="PENDING",
    val vmBoot:String="PENDING",
    val connectVsock:String="PENDING",
    val vsockFdReceived:String="PENDING",
    val adbHandshake:String="PENDING",
    val guestCommand:String="PENDING",
    val reconnect:String="NOT TESTED",
    val failureStage:String="none",
    val capabilities:String="NOT TESTED",
    val debianInstalled:Boolean=false,
    val debianInstalling:Boolean=false,
    val installProgress:Double=-1.0,
    val installBytes:Long=0,
    val installTotal:Long=-1,
    val installError:String="",
    val debianBoot:String="NOT TESTED",
    val debianIdentity:String="NOT TESTED",
    val display:String="NOT TESTED",
    val graphics:String="NOT TESTED",
    val kdeInstalled:Boolean=false,
    val kdeInstalling:Boolean=false,
    val kdeStage:String="not installed",
    val kdeError:String=""
)

class VmSessionService : Service() {
    companion object { val state=MutableStateFlow(SessionState()); var active:VmSessionService?=null }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var bridge:IVmBridge?=null
    private var successfulCommands=0
    private var reconnectProbeArmed=false
    private var pendingSurface:Surface?=null

    private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this,VmBridge::class.java))
        .daemon(false).processNameSuffix("vm_bridge").debuggable(true).version(10) }

    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            bridge=IVmBridge.Stub.asInterface(binder)
            state.value=state.value.copy(connected=true,message="AVF bridge ready")
            scope.launch { refresh(); probeCapabilities() }
        }
        override fun onServiceDisconnected(name:ComponentName) {
            bridge=null
            state.value=state.value.copy(connected=false,running=false,message="Shizuku bridge disconnected; VM files retained")
        }
    }

    override fun onCreate() {
        super.onCreate(); active=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("vm","DEV 2 LINUX session",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"vm").setContentTitle("DEV 2 LINUX")
            .setContentText("AVF Linux session").setSmallIcon(android.R.drawable.ic_menu_manage).setContentIntent(intent).build())

        scope.launch { state.collect { current -> withContext(Dispatchers.IO) {
            File(filesDir,"verification-runtime.json").writeText(JSONObject()
                .put("source","device-runtime").put("running",current.running).put("mode",current.mode)
                .put("vm_api_init",current.vmApiInit).put("vm_creation",current.vmCreation).put("vm_boot",current.vmBoot)
                .put("connect_vsock",current.connectVsock).put("vsock_fd_received",current.vsockFdReceived)
                .put("adb_handshake",current.adbHandshake).put("guest_command",current.guestCommand).put("reconnect",current.reconnect)
                .put("debian_installed",current.debianInstalled).put("debian_boot",current.debianBoot).put("debian_identity",current.debianIdentity)
                .put("display",current.display).put("gles",current.graphics).put("kde",if(current.kdeInstalled)"PASS" else current.kdeStage)
                .put("failure_stage",current.failureStage).put("message",current.message).toString(2))
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
        val o=JSONObject(raw)
        val error=when {
            o.optString("kdeError").isNotBlank()->o.optString("kdeError")
            o.optString("installError").isNotBlank()->o.optString("installError")
            else->""
        }
        state.value=state.value.copy(
            running=o.optBoolean("running"),name=o.optString("name"),mode=o.optString("mode","none"),console=o.optString("log"),
            vmApiInit=o.optString("vmApiInit",state.value.vmApiInit),vmDataDir=o.optString("vmDataDir",state.value.vmDataDir),
            vmCreation=o.optString("vmCreation",state.value.vmCreation),vmBoot=o.optString("vmBoot",state.value.vmBoot),
            connectVsock=o.optString("connectVsock",state.value.connectVsock),vsockFdReceived=o.optString("vsockFdReceived",state.value.vsockFdReceived),
            adbHandshake=o.optString("adbHandshake",state.value.adbHandshake),guestCommand=o.optString("guestCommand",state.value.guestCommand),
            failureStage=o.optString("failureStage",state.value.failureStage),capabilities=o.optString("capabilities",state.value.capabilities),
            debianInstalled=o.optBoolean("debianInstalled"),debianInstalling=o.optBoolean("debianInstalling"),
            installProgress=o.optDouble("installProgress",-1.0),installBytes=o.optLong("installBytes"),installTotal=o.optLong("installTotal",-1),
            installError=o.optString("installError"),debianBoot=o.optString("debianBoot",state.value.debianBoot),
            debianIdentity=o.optString("debianIdentity",state.value.debianIdentity),display=o.optString("display",state.value.display),
            graphics=o.optString("guestGraphics",state.value.graphics),kdeInstalled=o.optBoolean("kdeInstalled"),kdeInstalling=o.optBoolean("kdeInstalling"),
            kdeStage=o.optString("kdeStage",state.value.kdeStage),kdeError=o.optString("kdeError"),
            message=if(error.isNotBlank()) error else state.value.message)
    }

    private suspend fun bridgeString(stage:String,call:()->String?):String {
        val raw=withContext(Dispatchers.IO){call()}
        return raw?:throw IllegalStateException("$stage: bridge returned null")
    }
    private suspend fun refresh() { runCatching { bridge?.let { applyStatus(bridgeString("status"){it.status()}) } }
        .onFailure { state.value=state.value.copy(message=it.message?:"Status unavailable") } }

    fun startVm()=operation { b ->
        applyStatus(bridgeString("vm_boot"){b.startVm()})
        state.value=state.value.copy(reconnect=if(reconnectProbeArmed)"PENDING" else state.value.reconnect,
            message=if(reconnectProbeArmed)"VM restarted. Run a guest command to verify reconnect." else "Managed Microdroid running")
    }
    fun stopVm()=operation { b ->
        pendingSurface=null; applyStatus(bridgeString("vm_stop"){b.stopVm()})
        if(successfulCommands>0&&state.value.mode=="microdroid") reconnectProbeArmed=true
        state.value=state.value.copy(reconnect=if(reconnectProbeArmed)"PENDING" else state.value.reconnect,message="VM stopped; data retained")
    }
    fun shell(command:String)=operation { b ->
        val result=JSONObject(bridgeString("guest_command"){b.guestShell(command)})
        check(result.optBoolean("ok")){result.optString("error","Guest command failed")}
        successfulCommands++; val reconnectPassed=reconnectProbeArmed; if(reconnectPassed)reconnectProbeArmed=false
        state.value=state.value.copy(terminal=(state.value.terminal+"\n$ $command\n${result.optString("output")}").takeLast(160000),
            reconnect=if(reconnectPassed)"PASS" else state.value.reconnect,message=if(reconnectPassed)"Reconnect verified" else "Guest command completed through sanctioned vsock FD")
        refresh()
    }

    fun probeCapabilities()=operation { b ->
        val result=JSONObject(bridgeString("capabilities"){b.inspectCapabilities()})
        val text=if(result.optBoolean("ok")) result.optString("text") else "BLOCKED: ${result.optString("error")}" 
        state.value=state.value.copy(capabilities=text,message="AVF capability probe complete")
        refresh()
    }

    fun installDebian()=operation { b ->
        applyStatus(bridgeString("debian_install"){b.installDebian()})
        state.value=state.value.copy(message="Debian image installation started")
    }

    fun startDebian(width:Int,height:Int,dpi:Int,refreshRate:Int)=operation { b ->
        applyStatus(bridgeString("debian_boot"){b.startDebian(width,height,dpi,refreshRate)})
        state.value=state.value.copy(message="Debian VM running; attaching display")
        pendingSurface?.let { if(it.isValid) withContext(Dispatchers.IO){b.setDisplaySurface(it)} }
        val id=JSONObject(bridgeString("debian_identity"){b.debianConsole("cat /etc/os-release; uname -a; id")})
        if(id.optBoolean("ok")) state.value=state.value.copy(debianTerminal=(state.value.debianTerminal+"\n# identity\n${id.optString("output")}").takeLast(200000),message="Debian 13 guest verified")
        refresh()
    }

    fun debianConsole(command:String)=operation { b ->
        val result=JSONObject(bridgeString("debian_console"){b.debianConsole(command)})
        check(result.optBoolean("ok")){result.optString("error","Debian command failed")}
        state.value=state.value.copy(debianTerminal=(state.value.debianTerminal+"\n# $command\n${result.optString("output")}").takeLast(240000),message="Debian command completed")
        refresh()
    }

    fun installKde()=operation { b ->
        applyStatus(bridgeString("kde"){b.installKde()}); state.value=state.value.copy(message="KDE Plasma provisioning started")
    }

    fun attachSurface(surface:Surface) {
        pendingSurface=surface; val b=bridge?:return
        if(state.value.mode!="debian"||!state.value.running||!surface.isValid)return
        scope.launch { runCatching { withContext(Dispatchers.IO){b.setDisplaySurface(surface)} }
            .onFailure { state.value=state.value.copy(message="Display attach failed: ${it.message}") }; refresh() }
    }
    fun detachSurface(surface:Surface?=null) {
        if(surface==null||pendingSurface===surface)pendingSurface=null
        val b=bridge?:return; scope.launch { runCatching { withContext(Dispatchers.IO){b.clearDisplaySurface()} }; refresh() }
    }
    fun sendKey(action:Int,keyCode:Int,metaState:Int)=runCatching { bridge?.sendKey(action,keyCode,metaState)?:false }.getOrDefault(false)
    fun sendTouch(action:Int,x:Float,y:Float,pointerId:Int)=runCatching { bridge?.sendTouch(action,x,y,pointerId)?:false }.getOrDefault(false)

    private fun operation(block:suspend(IVmBridge)->Unit) {
        if(state.value.busy)return; val b=bridge?:return
        state.value=state.value.copy(busy=true)
        scope.launch { try { block(b) } catch(e:Exception) {
            val error=e.message?:e.javaClass.simpleName
            state.value=state.value.copy(message=error,terminal=(state.value.terminal+"\nERROR: $error\n").takeLast(160000)); refresh()
        } finally { state.value=state.value.copy(busy=false) } }
    }

    override fun onDestroy() {
        active=null; scope.cancel(); pendingSurface=null
        if(bridge!=null)runCatching { Shizuku.unbindUserService(args,connection,true) }
        bridge=null; super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
