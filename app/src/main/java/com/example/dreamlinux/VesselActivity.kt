package com.example.dreamlinux

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class VesselActivity:ComponentActivity(){
    private lateinit var status:TextView;private lateinit var detail:TextView;private lateinit var progress:ProgressBar;private lateinit var start:Button;private lateinit var desktop:LinuxDesktopView
    override fun onCreate(b:Bundle?){super.onCreate(b);WindowCompat.setDecorFitsSystemWindows(window,false);immersive();startForegroundService(Intent(this,VmSessionService::class.java))
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,18,18,18);setBackgroundColor(Color.rgb(7,10,9))}
        val title=TextView(this).apply{text="Vessel";textSize=25f;setTextColor(Color.WHITE)};status=TextView(this).apply{textSize=15f;setTextColor(Color.rgb(120,240,190))};detail=TextView(this).apply{textSize=12f;setTextColor(Color.LTGRAY)}
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        start=Button(this).apply{text="Start Linux";setOnClickListener{startOrGrant()}}
        val touch=Button(this).apply{text="Touch";setOnClickListener{desktop.setPointerMode(LinuxDesktopView.PointerMode.DIRECT)}}
        val track=Button(this).apply{text="Trackpad";setOnClickListener{desktop.setPointerMode(LinuxDesktopView.PointerMode.TRACKPAD)}}
        val keyboard=Button(this).apply{text="Keyboard";setOnClickListener{desktop.showKeyboard()}}
        controls.addView(start,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));controls.addView(touch);controls.addView(track);controls.addView(keyboard)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
        desktop=LinuxDesktopView(this)
        root.addView(title);root.addView(status);root.addView(detail);root.addView(controls);root.addView(progress,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,12));root.addView(desktop,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){VmSessionService.state.collect{render(it)}}}
    }
    override fun onResume(){super.onResume();immersive();startForegroundService(Intent(this,VmSessionService::class.java))}
    override fun onWindowFocusChanged(f:Boolean){super.onWindowFocusChanged(f);if(f)immersive()}
    private fun startOrGrant(){val s=VmSessionService.state.value;if(s.running){VmSessionService.active?.stopVm();return};if(!Environment.isExternalStorageManager()){startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")));return};VmSessionService.active?.startVm()}
    private fun render(s:SessionState){status.text=s.message;detail.text="${s.progressDetail}\n${s.machinePath}\n${s.presenterStatus}";progress.progress=s.progressPercent.coerceIn(0,100);start.isEnabled=!s.busy;start.text=when{!s.storageReady->"Grant storage";s.running->"Stop Linux";else->"Start Linux"};start.setOnClickListener{startOrGrant()}}
    private fun immersive(){WindowCompat.getInsetsController(window,window.decorView).apply{systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsetsCompat.Type.systemBars())}}
}
