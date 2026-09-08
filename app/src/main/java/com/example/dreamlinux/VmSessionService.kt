package com.example.dreamlinux

import android.app.*
import android.content.*
import android.os.*
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

data class SessionState(
    val connected: Boolean = false, val running: Boolean = false, val cid: Int = -1,
    val name: String = "", val console: String = "", val terminal: String = "",
    val message: String = "Connect Shizuku to begin", val busy: Boolean = false
)
class VmSessionService : Service() {
    companion object { val state = MutableStateFlow(SessionState()); var active: VmSessionService? = null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridge: IVmBridge? = null
    private val args by lazy { Shizuku.UserServiceArgs(ComponentName(this, VmBridge::class.java))
        .daemon(false).processNameSuffix("vm_bridge").debuggable(true).version(1) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridge = IVmBridge.Stub.asInterface(binder)
            state.value = state.value.copy(connected=true, message="Ready to start a stock test VM")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bridge=null
            state.value=state.value.copy(connected=false, running=false, cid=-1, message="Shizuku disconnected. VM state unknown; disk files retained.")
        }
    }
    override fun onCreate() {
        super.onCreate(); active=this
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vm", "Linux session", NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"vm").setContentTitle("Dream Linux")
            .setContentText("Development VM session controller").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(intent).build())
        scope.launch { state.collect { current -> withContext(Dispatchers.IO) {
            java.io.File(filesDir,"verification.json").writeText(JSONObject()
                .put("running",current.running).put("cid",current.cid).put("name",current.name)
                .put("message",current.message).put("console",current.console).put("terminal",current.terminal)
                .put("guestGraphics","unproven").put("debian","not established").toString(2))
        } } }
        scope.launch { while(isActive) { delay(2000); if(bridge!=null&&!state.value.busy) refresh() } }
    }
    override fun onStartCommand(intent: Intent?,flags:Int,startId:Int):Int {
        if(bridge==null) try {
            check(Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) { "Authorize Shizuku first" }
            Shizuku.bindUserService(args,connection)
        } catch(e:Exception){ state.value=state.value.copy(message=e.message?:"Shizuku unavailable") }
        return START_NOT_STICKY
    }
    private fun applyStatus(raw:String) {
        val obj=JSONObject(raw)
        state.value=state.value.copy(running=obj.getBoolean("running"),cid=obj.getInt("cid"),
            name=obj.getString("name"),console=obj.getString("log"))
    }
    private suspend fun refresh() { try { val b=bridge?:return; applyStatus(withContext(Dispatchers.IO){ b.status() }) }
        catch(e:Exception){ state.value=state.value.copy(message=e.message?:"Status unavailable") } }
    fun startVm() = operation { b -> applyStatus(withContext(Dispatchers.IO){ b.startVm() });
        state.value=state.value.copy(message="Starting stock Microdroid; Debian and GPU not established") }
    fun stopVm() = operation { b -> applyStatus(withContext(Dispatchers.IO){ b.stopVm() });
        state.value=state.value.copy(message="Owned VM stopped; files retained") }
    fun shell(command:String) = operation { b ->
        val reply=withContext(Dispatchers.IO){ b.guestShell(command) }
        check(reply!=null) { "Bridge returned no result; guest execution is unverified" }
        val result=JSONObject(reply)
        check(result.getBoolean("ok")) { result.optString("error","Guest command failed") }
        val output=result.getString("output")
        state.value=state.value.copy(terminal=(state.value.terminal+"\n$ $command\n"+output).takeLast(60000),message="Guest command completed")
    }
    private fun operation(block:suspend (IVmBridge)->Unit) {
        if(state.value.busy)return
        val b=bridge?:return
        state.value=state.value.copy(busy=true)
        scope.launch { try { block(b) } catch(e:Exception){
            val error=e.message?:e.javaClass.simpleName
            state.value=state.value.copy(message=error,terminal=(state.value.terminal+"\nERROR: $error\n").takeLast(60000))
        } finally { state.value=state.value.copy(busy=false) } }
    }
    override fun onDestroy() {
        active=null; scope.cancel()
        if(bridge!=null) try { Shizuku.unbindUserService(args,connection,true) } catch(_:Exception) {}
        bridge=null; super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
