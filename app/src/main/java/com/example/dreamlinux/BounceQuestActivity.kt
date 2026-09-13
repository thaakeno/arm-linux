package com.example.dreamlinux

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
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
    private lateinit var winOverlay: FrameLayout
    private lateinit var winTime: TextView
    private val handler=Handler(Looper.getMainLooper())
    private var handle=0L
    private var moveX=0f
    private var moveZ=0f
    private var paused=false
    private var sfx=true
    private lateinit var sounds:SoundPool
    private val soundIds=mutableMapOf<String,Int>()

    private val tick=object:Runnable{
        override fun run(){
            val h=handle
            if(h!=0L){
                nativeInput(h,moveX,moveZ)
                coinText.text="●  ${nativeCoins(h)} / 10"
                val sec=nativeElapsed(h).coerceAtLeast(0f);val min=(sec/60).toInt();val s=sec-min*60
                timerText.text=String.format(Locale.US,"◴  %02d:%05.2f",min,s)
                runCatching{nativeEvents(h)}.getOrDefault("").lineSequence().filter{it.isNotBlank()}.forEach(::consumeEvent)
            }
            handler.postDelayed(this,16)
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M)window.attributes=window.attributes.apply{preferredRefreshRate=120f}
        System.loadLibrary("bounce_quest");initAudio()
        surface=SurfaceView(this).apply{holder.setFormat(PixelFormat.OPAQUE)}
        val root=FrameLayout(this).apply{setBackgroundColor(Color.rgb(90,155,215))}
        root.addView(surface,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        root.post{buildHud(root);buildWinOverlay(root)}
        surface.holder.addCallback(object:SurfaceHolder.Callback{
            override fun surfaceCreated(holder:SurfaceHolder){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R)runCatching{holder.surface.setFrameRate(120f,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)};if(handle==0L)handle=nativeCreate(holder.surface,assets)}
            override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){if(handle!=0L)nativeResize(handle,max(1,width),max(1,height))}
            override fun surfaceDestroyed(holder:SurfaceHolder){if(handle!=0L)nativeDestroy(handle);handle=0L}
        })
        handler.post(tick)
    }

    private fun buildHud(root:FrameLayout){
        val w=root.width.toFloat().coerceAtLeast(1280f),h=root.height.toFloat().coerceAtLeast(600f)
        fun lp(pw:Float,ph:Float,gravity:Int,x:Float=0f,y:Float=0f)=FrameLayout.LayoutParams((w*pw).toInt(),(h*ph).toInt(),gravity).apply{
            if(gravity and Gravity.LEFT!=0)leftMargin=(w*x).toInt() else if(gravity and Gravity.RIGHT!=0)rightMargin=(w*x).toInt()
            if(gravity and Gravity.TOP!=0)topMargin=(h*y).toInt() else if(gravity and Gravity.BOTTOM!=0)bottomMargin=(h*y).toInt()
        }
        root.addView(BounceLogoView(),lp(.205f,.165f,Gravity.TOP or Gravity.LEFT,.016f,.018f))
        coinText=pill("●  0 / 10",h*.036f);root.addView(coinText,lp(.128f,.082f,Gravity.TOP or Gravity.LEFT,.235f,.020f))
        timerText=pill("◴  00:00.00",h*.036f);root.addView(timerText,lp(.170f,.082f,Gravity.TOP or Gravity.LEFT,.378f,.020f))
        sfxText=pill("SFX ON",h*.032f).apply{setOnClickListener{sfx=!sfx;text=if(sfx)"SFX ON" else "SFX OFF"}}
        root.addView(sfxText,lp(.126f,.078f,Gravity.TOP or Gravity.RIGHT,.086f,.020f))
        pauseText=pill("Ⅱ",h*.045f).apply{setOnClickListener{paused=!paused;text=if(paused)"▶" else "Ⅱ";if(handle!=0L)nativePause(handle,paused)}}
        root.addView(pauseText,lp(.052f,.078f,Gravity.TOP or Gravity.RIGHT,.020f,.020f))

        val joy=JoystickView{ x,z->moveX=x;moveZ=z };root.addView(joy,lp(.185f,.315f,Gravity.BOTTOM or Gravity.LEFT,.020f,.035f))
        val dash=ActionButtonView(false){if(handle!=0L)nativeDash(handle)};root.addView(dash,lp(.100f,.215f,Gravity.BOTTOM or Gravity.RIGHT,.150f,.050f))
        val jump=ActionButtonView(true){if(handle!=0L)nativeJump(handle)};root.addView(jump,lp(.125f,.270f,Gravity.BOTTOM or Gravity.RIGHT,.020f,.027f))
    }

    private fun buildWinOverlay(root:FrameLayout){
        val w=root.width.toFloat().coerceAtLeast(1280f),h=root.height.toFloat().coerceAtLeast(600f)
        winOverlay=FrameLayout(this).apply{setBackgroundColor(0x88050B12.toInt());visibility=View.GONE}
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding((w*.025f).toInt(),(h*.035f).toInt(),(w*.025f).toInt(),(h*.035f).toInt());background=roundRect(0xF2293542.toInt(),h*.03f)}
        val title=label("LEVEL COMPLETE",h*.050f,true).apply{gravity=Gravity.CENTER}
        winTime=label("00:00.00",h*.036f,true).apply{setTextColor(0xFFFFD34E.toInt());gravity=Gravity.CENTER}
        val restart=label("PLAY AGAIN",h*.030f,true).apply{gravity=Gravity.CENTER;background=roundRect(0xFFE53B35.toInt(),h*.02f);setOnClickListener{if(handle!=0L)nativeReset(handle);winOverlay.visibility=View.GONE;paused=false;pauseText.text="Ⅱ";if(handle!=0L)nativePause(handle,false)}}
        card.addView(title,LinearLayout.LayoutParams((w*.30f).toInt(),(h*.08f).toInt()));card.addView(winTime,LinearLayout.LayoutParams((w*.30f).toInt(),(h*.07f).toInt()));card.addView(restart,LinearLayout.LayoutParams((w*.20f).toInt(),(h*.08f).toInt()))
        winOverlay.addView(card,FrameLayout.LayoutParams((w*.38f).toInt(),(h*.34f).toInt(),Gravity.CENTER));root.addView(winOverlay,FrameLayout.LayoutParams(-1,-1))
    }

    inner class BounceLogoView:View(this){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){super.onDraw(c);val W=width.toFloat();val H=height.toFloat();p.color=0xD728333E.toInt();c.drawRoundRect(0f,0f,W,H,H*.20f,H*.20f,p);p.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);p.textAlign=Paint.Align.LEFT;p.color=Color.WHITE;p.textSize=H*.39f;c.drawText("B",W*.08f,H*.40f,p);p.color=0xFFF23732.toInt();c.drawCircle(W*.235f,H*.285f,H*.105f,p);p.color=Color.WHITE;p.textSize=H*.35f;c.drawText("unce",W*.30f,H*.40f,p);p.color=0xFF69C7FF.toInt();p.textSize=H*.39f;c.drawText("Quest",W*.31f,H*.75f,p);p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);p.letterSpacing=.14f;p.textSize=H*.105f;p.color=0xFFE9EEF2.toInt();c.drawText("ROLL   BOUNCE   REACH",W*.50f,H*.93f,p);p.letterSpacing=0f}
    }

    inner class JoystickView(private val changed:(Float,Float)->Unit):View(this){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var kx=0f;private var ky=0f
        override fun onDraw(c:Canvas){super.onDraw(c);val r=minOf(width,height)*.44f,cx=width/2f,cy=height/2f;p.style=Paint.Style.FILL;p.color=0x18FFFFFF;c.drawCircle(cx,cy,r,p);p.style=Paint.Style.STROKE;p.strokeWidth=r*.035f;p.color=0xB8FFFFFF.toInt();c.drawCircle(cx,cy,r,p);p.style=Paint.Style.FILL;p.color=0xE8EDF2F6.toInt();c.drawCircle(cx+kx,cy+ky,r*.34f,p);p.style=Paint.Style.STROKE;p.strokeWidth=r*.025f;p.color=Color.WHITE;c.drawCircle(cx+kx,cy+ky,r*.34f,p)}
        override fun onTouchEvent(e:MotionEvent):Boolean{val cx=width/2f,cy=height/2f,maxR=minOf(width,height)*.29f;when(e.actionMasked){MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{var dx=e.x-cx;var dy=e.y-cy;val d=hypot(dx,dy);if(d>maxR){dx*=maxR/d;dy*=maxR/d};kx=dx;ky=dy;changed((dx/maxR).coerceIn(-1f,1f),(dy/maxR).coerceIn(-1f,1f));invalidate()}MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{kx=0f;ky=0f;changed(0f,0f);invalidate()}};return true}
    }

    inner class ActionButtonView(private val jump:Boolean,private val fire:()->Unit):View(this){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){super.onDraw(c);val r=minOf(width,height)*.46f,cx=width/2f,cy=height/2f;p.style=Paint.Style.FILL;p.color=if(jump)0xEDEB3D37.toInt() else 0xB52A3440.toInt();c.drawCircle(cx,cy,r,p);p.style=Paint.Style.STROKE;p.strokeWidth=r*.045f;p.color=Color.WHITE;c.drawCircle(cx,cy,r,p);p.style=Paint.Style.STROKE;p.strokeWidth=r*.075f;p.strokeCap=Paint.Cap.ROUND;p.color=Color.WHITE;if(jump){for(off in floatArrayOf(-r*.13f,r*.08f)){val path=Path();path.moveTo(cx-r*.22f,cy+off);path.lineTo(cx,cy-r*.18f+off);path.lineTo(cx+r*.22f,cy+off);c.drawPath(path,p)}}else{c.drawLine(cx-r*.28f,cy-r*.09f,cx+r*.12f,cy-r*.09f,p);c.drawLine(cx-r*.22f,cy+r*.05f,cx+r*.18f,cy+r*.05f,p);p.style=Paint.Style.FILL;c.drawCircle(cx+r*.24f,cy-r*.02f,r*.10f,p)}p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.DEFAULT_BOLD;p.textSize=r*.24f;c.drawText(if(jump)"JUMP" else "DASH",cx,cy+r*.64f,p)}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN){alpha=.76f;fire();invalidate()}else if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)alpha=1f;return true}
    }

    private fun initAudio(){val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();sounds=SoundPool.Builder().setMaxStreams(16).setAudioAttributes(attrs).build();soundIds["bounce1"]=sounds.load(this,R.raw.bq_bounce_1,1);soundIds["bounce2"]=sounds.load(this,R.raw.bq_bounce_2,1);soundIds["collect"]=sounds.load(this,R.raw.bq_collect,1);soundIds["jump"]=sounds.load(this,R.raw.bq_jump,1);soundIds["dash"]=sounds.load(this,R.raw.bq_dash,1);soundIds["death"]=sounds.load(this,R.raw.bq_death,1);soundIds["win"]=sounds.load(this,R.raw.bq_win,1)}
    private fun consumeEvent(e:String){if(e=="SFX:WIN"){val sec=if(handle!=0L)nativeElapsed(handle)else 0f;val min=(sec/60).toInt();val s=sec-min*60;winTime.text=String.format(Locale.US,"TIME  %02d:%05.2f",min,s);winOverlay.visibility=View.VISIBLE;paused=true;if(handle!=0L)nativePause(handle,true)};if(!sfx)return;when{e.startsWith("SFX:BOUNCE")->{val impact=e.substringAfterLast(':',"2").toFloatOrNull()?.coerceIn(.5f,12f)?:2f;val gain=(.28f+impact/12f*.66f).coerceIn(.28f,.94f);val rate=(1.08f-impact/12f*.20f+(Math.random().toFloat()-.5f)*.08f).coerceIn(.82f,1.15f);play(if(System.nanoTime().and(1L)==0L)"bounce1" else "bounce2",gain,rate)};e=="SFX:JUMP"->play("jump",.76f,1.04f);e=="SFX:DASH"->play("dash",.82f,.98f);e=="SFX:COLLECT"->play("collect",.90f,.98f+Math.random().toFloat()*.08f);e=="SFX:DEATH"->play("death",.92f,1f);e=="SFX:WIN"->play("win",1f,1f)}}
    private fun play(name:String,vol:Float,rate:Float){soundIds[name]?.let{sounds.play(it,vol,vol,2,0,rate.coerceIn(.5f,2f))}}
    private fun pill(t:String,px:Float)=label(t,px,true).apply{gravity=Gravity.CENTER;background=roundRect(0xD428343F.toInt(),px*.42f);setShadowLayer(2f,0f,1f,0x99000000.toInt())}
    private fun label(t:String,px:Float,bold:Boolean)=TextView(this).apply{text=t;setTextSize(TypedValue.COMPLEX_UNIT_PX,px);setTextColor(Color.WHITE);typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;includeFontPadding=false}
    private fun roundRect(fill:Int,radius:Float)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=radius}
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
