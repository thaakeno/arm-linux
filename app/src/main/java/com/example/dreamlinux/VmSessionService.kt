package com.example.dreamlinux

import android.app.*
import android.content.*
import android.os.*
import android.content.pm.PackageManager
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
    val stage:String="idle",
    val api:String="",
    val vmRoot:String="",
    val console:String="",
    val terminal:String="",
    val message:String="Connect Shizuku to begin",
    val busy:Boolean=false
)

class VmSessionService : Service() {
    companion object { val state=MutableStateFlow(SessionState()); var active:VmSessionService?=null }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var bridge:IVmBridge?=null
    private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this,VmBridge::class.java))
        .daemon(false).processNameSuffix("vm_bridge").debuggable(true).version(2) }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            bridge=IVmBridge.Stub.asInterface(binder)
            state.value=state.value.copy(connected=true,message="Managed AVF bridge connected")
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
            .setContentText("Managed AVF session controller").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(intent).build())
        scope.launch { state.collect { current -> withContext(Dispatchers.IO) {
            File(filesDir,"verification-runtime.json").writeText(JSONObject()
                .put("running",current.running).put("name",current.name).put("stage",current.stage)
                .put("api",current.api).put("vmRoot",current.vmRoot).put("message",current.message)
                .put("guestGraphics","unproven").put("debian","not established").toString(2))
        } } }
        scope.launch { while(isActive) { delay(1500); if(bridge!=null&&!state.value.busy) refresh() } }
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
        state.value=state.value.copy(
            running=obj.optBoolean("running"),cid=obj.optInt("cid",-1),name=obj.optString("name"),
            stage=obj.optString("stage","unknown"),api=obj.optString("api"),vmRoot=obj.optString("vmRoot"),
            console=obj.optString("log"),message=if(error.isNotBlank()) error else "Stage: ${obj.optString("stage","unknown")}" )
    }

    private suspend fun refresh() {
        try { val b=bridge?:return; applyStatus(withContext(Dispatchers.IO){b.status()}) }
        catch(e:Exception) { state.value=state.value.copy(message=e.message?:"Status unavailable") }
    }

    fun startVm()=operation { b -> applyStatus(withContext(Dispatchers.IO){b.startVm()}) }
    fun stopVm()=operation { b -> applyStatus(withContext(Dispatchers.IO){b.stopVm()}) }
    fun shell(command:String)=operation { b ->
        val reply=withContext(Dispatchers.IO){b.guestShell(command)}
        val result=JSONObject(reply)
        check(result.optBoolean("ok")) { result.optString("error","Guest command failed") }
        val output=result.optString("output")
        state.value=state.value.copy(terminal=(state.value.terminal+"\n$ $command\n$output").takeLast(256000),message="Guest command completed")
        refresh()
    }

    private fun operation(block:suspend (IVmBridge)->Unit) {
        if(state.value.busy) return
        val b=bridge?:return
        state.value=state.value.copy(busy=true)
        scope.launch { try { block(b) } catch(e:Exception) {
            val error=e.message?:e.javaClass.simpleName
            state.value=state.value.copy(message=error,terminal=(state.value.terminal+"\nERROR: $error\n").takeLast(256000))
        } finally { state.value=state.value.copy(busy=false) } }
    }

    override fun onDestroy() {
        active=null; scope.cancel()
        if(bridge!=null) try { Shizuku.unbindUserService(args,connection,true) } catch(_:Exception) {}
        bridge=null; super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
