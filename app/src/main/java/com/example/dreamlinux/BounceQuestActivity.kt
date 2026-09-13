package com.example.dreamlinux

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

class BounceQuestActivity : Activity() {
    private lateinit var surface: SurfaceView
    private lateinit var coinText: TextView
    private lateinit var timerText: TextView
    private lateinit var sfxText: TextView
    private lateinit var pauseText: TextView
    private lateinit var joyBase: FrameLayout
    private lateinit var joyKnob: View
    private lateinit var winOverlay: FrameLayout
    private lateinit var winTime: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var joyId = -1
    private var joyCx = 0f
    private var joyCy = 0f
    private var moveX = 0f
    private var moveZ = 0f
    private var paused = false
    private var sfx = true
    private lateinit var sounds: SoundPool
    private val soundIds = mutableMapOf<String,Int>()

    private val tick = object: Runnable {
        override fun run() {
            val h=handle
            if(h!=0L){
                nativeInput(h,moveX,moveZ)
                coinText.text="✦  ${nativeCoins(h)} / 10"
                val sec=nativeElapsed(h).coerceAtLeast(0f)
                val min=(sec/60).toInt(); val s=sec-min*60
                timerText.text=String.format(Locale.US,"◴  %02d:%05.2f",min,s)
                val ev=runCatching{nativeEvents(h)}.getOrDefault("")
                if(ev.isNotBlank())ev.lineSequence().forEach(::consumeEvent)
            }
            handler.postDelayed(this,16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M)window.attributes=window.attributes.apply{preferredRefreshRate=120f}
        System.loadLibrary("bounce_quest")
        initAudio()
        surface=SurfaceView(this).apply{holder.setFormat(PixelFormat.OPAQUE)}
        val root=FrameLayout(this).apply{setBackgroundColor(Color.rgb(80,150,220))}
        root.addView(surface,FrameLayout.LayoutParams(-1,-1))
        buildHud(root)
        buildWinOverlay(root)
        setContentView(root)
        surface.holder.addCallback(object:SurfaceHolder.Callback{
            override fun surfaceCreated(holder:SurfaceHolder){
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R)runCatching{holder.surface.setFrameRate(120f,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)}
                if(handle==0L)handle=nativeCreate(holder.surface,assets)
            }
            override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){if(handle!=0L)nativeResize(handle,max(1,width),max(1,height))}
            override fun surfaceDestroyed(holder:SurfaceHolder){if(handle!=0L)nativeDestroy(handle);handle=0L}
        })
        handler.post(tick)
    }

    private fun buildHud(root:FrameLayout){
        val topPad=dp(14)
        val logo=label("B●unce\nQuest",34f,true).apply{
            gravity=Gravity.CENTER;setTextColor(Color.WHITE);setShadowLayer(8f,0f,3f,Color.BLACK);background=roundRect(0xB82B3440.toInt(),22)
        }
        root.addView(logo,FrameLayout.LayoutParams(dp(300),dp(110),Gravity.TOP or Gravity.LEFT).apply{leftMargin=dp(20);topMargin=topPad})
        coinText=pill("✦  0 / 10",25f);root.addView(coinText,FrameLayout.LayoutParams(dp(190),dp(72),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=topPad;leftMargin=-dp(120)})
        timerText=pill("◴  00:00.00",25f);root.addView(timerText,FrameLayout.LayoutParams(dp(235),dp(72),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=topPad;leftMargin=dp(330)})
        sfxText=pill("SFX ON",22f).apply{setOnClickListener{sfx=!sfx;text=if(sfx)"SFX ON" else "SFX OFF"}}
        root.addView(sfxText,FrameLayout.LayoutParams(dp(185),dp(70),Gravity.TOP or Gravity.RIGHT).apply{rightMargin=dp(115);topMargin=topPad})
        pauseText=pill("Ⅱ",30f).apply{setOnClickListener{paused=!paused;text=if(paused)"▶" else "Ⅱ";if(handle!=0L)nativePause(handle,paused)}}
        root.addView(pauseText,FrameLayout.LayoutParams(dp(78),dp(70),Gravity.TOP or Gravity.RIGHT).apply{rightMargin=dp(22);topMargin=topPad})

        joyBase=FrameLayout(this).apply{background=circle(0x38FFFFFF,0x88FFFFFF.toInt(),4)}
        joyKnob=View(this).apply{background=circle(0xDDEAF0F5.toInt(),Color.WHITE,3)}
        joyBase.addView(joyKnob,FrameLayout.LayoutParams(dp(92),dp(92),Gravity.CENTER))
        root.addView(joyBase,FrameLayout.LayoutParams(dp(220),dp(220),Gravity.BOTTOM or Gravity.LEFT).apply{leftMargin=dp(36);bottomMargin=dp(35)})
        joyBase.setOnTouchListener{_,e->joystick(e)}

        val dash=roundButton("➤\nDASH",92,0xA62A3440.toInt()).apply{setOnTouchListener{_,e->if(e.action==MotionEvent.ACTION_DOWN){if(handle!=0L)nativeDash(handle);true}else true}}
        root.addView(dash,FrameLayout.LayoutParams(dp(135),dp(135),Gravity.BOTTOM or Gravity.RIGHT).apply{rightMargin=dp(190);bottomMargin=dp(62)})
        val jump=roundButton("⌃\nJUMP",115,0xE6E93732.toInt()).apply{setOnTouchListener{_,e->if(e.action==MotionEvent.ACTION_DOWN){if(handle!=0L)nativeJump(handle);true}else true}}
        root.addView(jump,FrameLayout.LayoutParams(dp(180),dp(180),Gravity.BOTTOM or Gravity.RIGHT).apply{rightMargin=dp(28);bottomMargin=dp(35)})
    }

    private fun buildWinOverlay(root:FrameLayout){
        winOverlay=FrameLayout(this).apply{setBackgroundColor(0x9909131E.toInt());visibility=View.GONE}
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(44),dp(34),dp(44),dp(34));background=roundRect(0xF2293542.toInt(),28)
        }
        val title=label("LEVEL COMPLETE",34f,true).apply{setTextColor(Color.WHITE);gravity=Gravity.CENTER}
        winTime=label("00:00.00",24f,true).apply{setTextColor(0xFFFFD34E.toInt());gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(20))}
        val restart=label("PLAY AGAIN",22f,true).apply{
            gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=roundRect(0xFFE53B35.toInt(),18);setPadding(dp(32),dp(14),dp(32),dp(14));setOnClickListener{if(handle!=0L)nativeReset(handle);winOverlay.visibility=View.GONE;paused=false;pauseText.text="Ⅱ";if(handle!=0L)nativePause(handle,false)}
        }
        card.addView(title,LinearLayout.LayoutParams(dp(360),dp(60)))
        card.addView(winTime,LinearLayout.LayoutParams(dp(360),dp(64)))
        card.addView(restart,LinearLayout.LayoutParams(dp(260),dp(60)))
        winOverlay.addView(card,FrameLayout.LayoutParams(dp(470),dp(260),Gravity.CENTER))
        root.addView(winOverlay,FrameLayout.LayoutParams(-1,-1))
    }

    private fun joystick(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN->{joyId=e.getPointerId(e.actionIndex);joyCx=joyBase.width/2f;joyCy=joyBase.height/2f}
            MotionEvent.ACTION_MOVE->{val i=e.findPointerIndex(joyId);if(i>=0){var dx=e.getX(i)-joyCx;var dy=e.getY(i)-joyCy;val maxR=joyBase.width*.31f;val d=hypot(dx,dy);if(d>maxR){dx*=maxR/d;dy*=maxR/d};joyKnob.translationX=dx;joyKnob.translationY=dy;moveX=(dx/maxR).coerceIn(-1f,1f);moveZ=(dy/maxR).coerceIn(-1f,1f)}}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP,MotionEvent.ACTION_CANCEL->{if(e.getPointerId(e.actionIndex)==joyId){joyId=-1;joyKnob.animate().translationX(0f).translationY(0f).setDuration(90).start();moveX=0f;moveZ=0f}}
        };return true
    }

    private fun initAudio(){
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        sounds=SoundPool.Builder().setMaxStreams(16).setAudioAttributes(attrs).build()
        soundIds["bounce1"]=sounds.load(this,R.raw.bq_bounce_1,1);soundIds["bounce2"]=sounds.load(this,R.raw.bq_bounce_2,1)
        soundIds["collect"]=sounds.load(this,R.raw.bq_collect,1);soundIds["jump"]=sounds.load(this,R.raw.bq_jump,1);soundIds["dash"]=sounds.load(this,R.raw.bq_dash,1);soundIds["death"]=sounds.load(this,R.raw.bq_death,1);soundIds["win"]=sounds.load(this,R.raw.bq_win,1)
    }

    private fun consumeEvent(e:String){
        if(e=="SFX:WIN"){
            val sec=if(handle!=0L)nativeElapsed(handle)else 0f;val min=(sec/60).toInt();val s=sec-min*60
            winTime.text=String.format(Locale.US,"TIME  %02d:%05.2f",min,s);winOverlay.visibility=View.VISIBLE;paused=true;if(handle!=0L)nativePause(handle,true)
        }
        if(!sfx)return
        when{
            e.startsWith("SFX:BOUNCE")->{val impact=e.substringAfterLast(':',"2").toFloatOrNull()?.coerceIn(.5f,12f)?:2f;val gain=(.28f+impact/12f*.66f).coerceIn(.28f,.94f);val rate=(1.08f-impact/12f*.20f+(Math.random().toFloat()-.5f)*.08f).coerceIn(.82f,1.15f);play(if(System.nanoTime().and(1L)==0L)"bounce1" else "bounce2",gain,rate)}
            e=="SFX:JUMP"->play("jump",.76f,1.04f)
            e=="SFX:DASH"->play("dash",.82f,.98f)
            e=="SFX:COLLECT"->play("collect",.90f,.98f+Math.random().toFloat()*.08f)
            e=="SFX:DEATH"->play("death",.92f,1f)
            e=="SFX:WIN"->play("win",1f,1f)
        }
    }
    private fun play(name:String,vol:Float,rate:Float){soundIds[name]?.let{sounds.play(it,vol,vol,2,0,rate.coerceIn(.5f,2f))}}

    private fun pill(text:String,size:Float)=label(text,size,true).apply{gravity=Gravity.CENTER;background=roundRect(0xCC25303B.toInt(),18);setTextColor(Color.WHITE)}
    private fun label(t:String,size:Float,bold:Boolean)=TextView(this).apply{text=t;textSize=size;typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;includeFontPadding=false}
    private fun roundButton(t:String,size:Int,bg:Int)=label(t,size/4f,true).apply{gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=circle(bg,Color.WHITE,4);setShadowLayer(5f,0f,2f,Color.BLACK)}
    private fun roundRect(fill:Int,radius:Int)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(radius).toFloat()}
    private fun circle(fill:Int,stroke:Int,strokeWidth:Int)=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(fill);setStroke(dp(strokeWidth),stroke)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()

    override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(handle!=0L)nativeDestroy(handle);handle=0L;sounds.release();super.onDestroy()}

    private external fun nativeCreate(surface:Surface,assets:AssetManager):Long
    private external fun nativeDestroy(handle:Long)
    private external fun nativeResize(handle:Long,width:Int,height:Int)
    private external fun nativeInput(handle:Long,x:Float,z:Float)
    private external fun nativeJump(handle:Long)
    private external fun nativeDash(handle:Long)
    private external fun nativePause(handle:Long,paused:Boolean)
    private external fun nativeReset(handle:Long)
    private external fun nativeCoins(handle:Long):Int
    private external fun nativeElapsed(handle:Long):Float
    private external fun nativeEvents(handle:Long):String
}
