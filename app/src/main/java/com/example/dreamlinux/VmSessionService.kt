package com.example.dreamlinux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SessionState(
    val connected:Boolean=false,val running:Boolean=false,val guestReady:Boolean=false,val displayReady:Boolean=false,
    val name:String="Vessel Debian",val message:String="Ready",val busy:Boolean=false,val stage:String="idle",
    val progressPercent:Int=0,val progressDetail:String="Runtime stopped",val lastError:String="",
    val console:String="",val graphics:String="VirtIO GPU · VirGL · Adreno · DMA-BUF",val presenterStatus:String="not-started",
    val machinePath:String="Download/LinuxPC/Vessel-Debian",val uptimeMs:Long=0L,val storageReady:Boolean=false,
)

class VmSessionService:Service(){
    companion object{val state=MutableStateFlow(SessionState());@Volatile var active:VmSessionService?=null}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var runtime:VesselRuntimeController
    private var w=1280;private var h=720;private var dpi=120;private var refresh=120f

    override fun onCreate(){super.onCreate();active=this
        runtime=VesselRuntimeController(this){phase,pct,detail->state.value=state.value.copy(stage=phase,progressPercent=pct,progressDetail=detail,message=detail)}
        runtime.configureDisplay(w,h,dpi,refresh)
        val nm=getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("vessel-runtime","Vessel Linux runtime",NotificationManager.IMPORTANCE_LOW))
        val pi=PendingIntent.getActivity(this,0,Intent(this,VesselActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,NotificationCompat.Builder(this,"vessel-runtime").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Vessel").setContentText("Self-contained ARM64 Linux runtime").setOngoing(true).setContentIntent(pi).build())
        updateAvailability();scope.launch{while(isActive){refreshState();delay(if(state.value.running||state.value.busy)300 else 1500)}}
    }
    override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{updateAvailability();return START_STICKY}
    override fun onDestroy(){if(active===this)active=null;scope.cancel();VesselWaylandPresenter.shutdown();super.onDestroy()}
    override fun onBind(i:Intent?):IBinder?=null

    private fun updateAvailability(){val storage=runtime.hasStorageAccess();val assets=runtime.hostAssetsReady();state.value=state.value.copy(connected=storage&&assets,storageReady=storage,machinePath=runtime.machineDir.absolutePath,message=when{!storage->"Grant file access for Download/LinuxPC";!assets->"Native runtime assets missing from APK";state.value.running->state.value.message;else->"Ready to start Vessel"})}
    private fun applyState(o:JSONObject){val presenter=VesselWaylandPresenter.status();val err=o.optString("lastError");state.value=state.value.copy(running=o.optBoolean("running"),guestReady=o.optBoolean("guestReady"),displayReady=o.optBoolean("desktopReady")||presenter.startsWith("presenting-dmabuf"),presenterStatus=presenter,console=o.optString("logTail",state.value.console),lastError=err,uptimeMs=o.optLong("uptimeMs"),graphics=o.optString("renderer",state.value.graphics),message=when{err.isNotBlank()->err;presenter.startsWith("presenting-dmabuf")->"Plasma visible · direct DMA-BUF";o.optBoolean("guestReady")->state.value.progressDetail;else->state.value.message})}
    private suspend fun refreshState(){updateAvailability();if(!state.value.running)return;runCatching{runtime.status()}.onSuccess(::applyState)}

    fun configureDisplay(width:Int,height:Int,densityDpi:Int,rate:Float){
        val changed=width!=w||height!=h||densityDpi!=dpi||kotlin.math.abs(rate-refresh)>0.5f
        w=width;h=height;dpi=densityDpi;refresh=rate
        runtime.configureDisplay(w,h,dpi,refresh)
        if(changed&&state.value.running&&state.value.guestReady){scope.launch(Dispatchers.IO){runtime.resizeDesktop(w,h,dpi,refresh)}}
    }
    fun sendInput(type:String,values:Map<String,Any>)=runtime.input(type,values)
    fun startVm(){if(state.value.busy)return;updateAvailability();if(!state.value.connected)return;state.value=state.value.copy(busy=true,lastError="",progressPercent=1,progressDetail="Starting self-contained Vessel runtime",message="Starting Linux")
        scope.launch(Dispatchers.IO){try{val o=runtime.startDesktop();launch(Dispatchers.Main){applyState(o)}}catch(t:Throwable){launch(Dispatchers.Main){state.value=state.value.copy(lastError=t.message?:t.javaClass.simpleName,message=t.message?:"Startup failed")}}finally{launch(Dispatchers.Main){state.value=state.value.copy(busy=false)}}}}
    fun stopVm(){if(state.value.busy)return;state.value=state.value.copy(busy=true,message="Stopping Linux");scope.launch(Dispatchers.IO){val r=runCatching{runtime.stop()};launch(Dispatchers.Main){r.onSuccess(::applyState).onFailure{state.value=state.value.copy(lastError=it.message?:"Stop failed")};state.value=state.value.copy(busy=false,running=false,guestReady=false,displayReady=false,message="Linux stopped; disk retained")}}}
    fun debianConsole(command:String)=guest(command)
    fun guest(command:String){if(state.value.busy||!state.value.running)return;state.value=state.value.copy(busy=true);scope.launch(Dispatchers.IO){val r=runCatching{runtime.guest(command,90)};launch(Dispatchers.Main){r.onSuccess(::applyState).onFailure{state.value=state.value.copy(lastError=it.message?:"Command failed")};state.value=state.value.copy(busy=false)}}}
}
