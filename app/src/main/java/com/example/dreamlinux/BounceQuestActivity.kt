package com.example.dreamlinux

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class BounceQuestActivity : Activity() {
    private lateinit var surface: SurfaceView
    private lateinit var hud: GameHudView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var handle = 0L
    private var moveX = 0f
    private var moveZ = 0f
    private var paused = false
    private var sfx = true
    private lateinit var sounds: SoundPool
    private val soundIds = mutableMapOf<String, Int>()

    private val tick = object : Runnable {
        override fun run() {
            val h = handle
            if (h != 0L) {
                nativeInput(h, moveX, moveZ)
                hud.coins = runCatching { nativeCoins(h) }.getOrDefault(0)
                hud.elapsed = runCatching { nativeElapsed(h) }.getOrDefault(0f).coerceAtLeast(0f)
                runCatching { nativeEvents(h) }.getOrDefault("")
                    .lineSequence().filter { it.isNotBlank() }.forEach(::consumeEvent)
                hud.invalidate()
            }
            handler.postDelayed(this, 16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }
        System.loadLibrary("bounce_quest")
        initAudio()

        val root = FrameLayout(this)
        surface = SurfaceView(this).apply { holder.setFormat(PixelFormat.OPAQUE) }
        hud = GameHudView()
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                }
                if (handle == 0L && holder.surface.isValid) {
                    handle = nativeCreate(holder.surface, assets)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val h = handle
                if (h != 0L && width > height) nativeResize(h, max(1, width), max(1, height))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val h = handle
                handle = 0L
                if (h != 0L) nativeDestroy(h)
            }
        })
        handler.post(tick)
    }

    private inner class GameHudView : View(this@BounceQuestActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        var coins = 0
        var elapsed = 0f
        private var joyPointer = -1
        private var joyX = 0f
        private var joyY = 0f
        private var won = false
        private var finalTime = 0f

        private fun rr(c: Canvas, r: RectF, radius: Float, color: Int) {
            p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(r, radius, radius, p)
        }
        private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = true) {
            p.style = Paint.Style.FILL; p.color = color; p.textAlign = align; p.textSize = size
            p.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            c.drawText(s, x, y - (p.ascent() + p.descent()) * .5f, p)
        }
        private fun hudRects(): Array<RectF> {
            val w = width.toFloat(); val h = height.toFloat()
            return arrayOf(
                RectF(w*.015f,h*.018f,w*.218f,h*.205f),
                RectF(w*.245f,h*.030f,w*.365f,h*.112f),
                RectF(w*.392f,h*.030f,w*.555f,h*.112f),
                RectF(w*.790f,h*.028f,w*.915f,h*.108f),
                RectF(w*.940f,h*.028f,w*.985f,h*.108f)
            )
        }
        private fun joyCenter() = Pair(width*.115f, height*.805f)
        private fun joyRadius() = height*.135f
        private fun dashCenter() = Pair(width*.795f, height*.820f)
        private fun dashRadius() = height*.082f
        private fun jumpCenter() = Pair(width*.920f, height*.805f)
        private fun jumpRadius() = height*.125f

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= h) return
            val dark = 0xD72B3640.toInt()
            val dark2 = 0xBF202A33.toInt()
            val red = 0xEEEA3A36.toInt()
            val cyan = 0xFF67C8FF.toInt()
            val gold = 0xFFFFD342.toInt()
            val r = hudRects()

            // Logo card
            rr(c,r[0],h*.030f,dark)
            val lx=r[0].left+r[0].width()*.09f
            val top=r[0].top+r[0].height()*.08f
            txt(c,"B",lx,top+r[0].height()*.22f,h*.068f,Color.WHITE,Paint.Align.LEFT)
            p.color=0xFFF23A34.toInt(); p.style=Paint.Style.FILL
            c.drawCircle(lx+r[0].width()*.205f, top+r[0].height()*.21f, h*.021f,p)
            txt(c,"unce",lx+r[0].width()*.27f,top+r[0].height()*.22f,h*.060f,Color.WHITE,Paint.Align.LEFT)
            txt(c,"Quest",r[0].centerX()+r[0].width()*.08f,top+r[0].height()*.57f,h*.067f,cyan)
            txt(c,"ROLL   BOUNCE   REACH",r[0].centerX(),r[0].bottom-r[0].height()*.10f,h*.016f,0xFFE9EEF3.toInt())

            // Coin pill
            rr(c,r[1],h*.023f,dark)
            val coinX=r[1].left+h*.042f
            p.color=gold;p.style=Paint.Style.FILL;c.drawCircle(coinX,r[1].centerY(),h*.020f,p)
            p.color=0xFFFFF2A2.toInt();c.drawCircle(coinX-h*.006f,r[1].centerY()-h*.006f,h*.006f,p)
            txt(c,"$coins / 10",r[1].centerX()+h*.022f,r[1].centerY(),h*.038f)

            // Timer pill with stopwatch icon
            rr(c,r[2],h*.023f,dark)
            stroke.style=Paint.Style.STROKE;stroke.strokeWidth=h*.005f;stroke.color=Color.WHITE
            val tx=r[2].left+h*.043f; val ty=r[2].centerY()
            c.drawCircle(tx,ty,h*.018f,stroke);c.drawLine(tx,ty,tx,ty-h*.011f,stroke);c.drawLine(tx,ty,tx+h*.009f,ty+h*.006f,stroke)
            c.drawLine(tx-h*.008f,ty-h*.024f,tx+h*.008f,ty-h*.024f,stroke)
            val min=(elapsed/60f).toInt();val sec=elapsed-min*60f
            txt(c,String.format(Locale.US,"%02d:%05.2f",min,sec),r[2].centerX()+h*.020f,r[2].centerY(),h*.038f)

            // SFX and pause
            rr(c,r[3],h*.020f,dark)
            p.color=Color.WHITE;p.style=Paint.Style.FILL
            val sx=r[3].left+h*.032f;val sy=r[3].centerY()
            val sp=Path();sp.moveTo(sx-h*.012f,sy-h*.010f);sp.lineTo(sx-h*.003f,sy-h*.010f);sp.lineTo(sx+h*.008f,sy-h*.021f);sp.lineTo(sx+h*.008f,sy+h*.021f);sp.lineTo(sx-h*.003f,sy+h*.010f);sp.lineTo(sx-h*.012f,sy+h*.010f);sp.close();c.drawPath(sp,p)
            stroke.style=Paint.Style.STROKE;stroke.strokeWidth=h*.004f;stroke.color=Color.WHITE
            c.drawArc(RectF(sx,sy-h*.019f,sx+h*.036f,sy+h*.019f),-50f,100f,false,stroke)
            txt(c,if(sfx)"SFX ON" else "SFX OFF",r[3].centerX()+h*.020f,r[3].centerY(),h*.031f)
            rr(c,r[4],h*.018f,dark)
            stroke.strokeWidth=h*.006f;stroke.color=Color.WHITE
            c.drawLine(r[4].centerX()-h*.007f,r[4].centerY()-h*.017f,r[4].centerX()-h*.007f,r[4].centerY()+h*.017f,stroke)
            c.drawLine(r[4].centerX()+h*.007f,r[4].centerY()-h*.017f,r[4].centerX()+h*.007f,r[4].centerY()+h*.017f,stroke)

            // Joystick with directional arrows
            val (jx,jy)=joyCenter(); val jr=joyRadius()
            p.style=Paint.Style.FILL;p.color=0x20FFFFFF;c.drawCircle(jx,jy,jr,p)
            stroke.style=Paint.Style.STROKE;stroke.strokeWidth=h*.0045f;stroke.color=0xBFFFFFFF.toInt();c.drawCircle(jx,jy,jr,stroke)
            val ar=h*.020f
            fun arrow(cx:Float,cy:Float,dx:Float,dy:Float){
                p.style=Paint.Style.FILL;p.color=0xA7FFFFFF.toInt();val q=Path()
                if(dx<0){q.moveTo(cx-ar,cy);q.lineTo(cx+ar*.55f,cy-ar*.7f);q.lineTo(cx+ar*.55f,cy+ar*.7f)}
                else if(dx>0){q.moveTo(cx+ar,cy);q.lineTo(cx-ar*.55f,cy-ar*.7f);q.lineTo(cx-ar*.55f,cy+ar*.7f)}
                else if(dy<0){q.moveTo(cx,cy-ar);q.lineTo(cx-ar*.7f,cy+ar*.55f);q.lineTo(cx+ar*.7f,cy+ar*.55f)}
                else {q.moveTo(cx,cy+ar);q.lineTo(cx-ar*.7f,cy-ar*.55f);q.lineTo(cx+ar*.7f,cy-ar*.55f)}
                q.close();c.drawPath(q,p)
            }
            arrow(jx-jr*.68f,jy,-1f,0f);arrow(jx+jr*.68f,jy,1f,0f);arrow(jx,jy-jr*.68f,0f,-1f);arrow(jx,jy+jr*.68f,0f,1f)
            p.style=Paint.Style.FILL;p.color=0xEEF5F7F9.toInt();c.drawCircle(jx+joyX,jy+joyY,jr*.34f,p)
            stroke.strokeWidth=h*.0035f;stroke.color=Color.WHITE;c.drawCircle(jx+joyX,jy+joyY,jr*.34f,stroke)

            // Dash button
            val (dx,dy)=dashCenter();val dr=dashRadius()
            p.style=Paint.Style.FILL;p.color=dark2;c.drawCircle(dx,dy,dr,p)
            stroke.style=Paint.Style.STROKE;stroke.strokeWidth=h*.004f;stroke.color=0xEFFFFFFF.toInt();c.drawCircle(dx,dy,dr,stroke)
            stroke.strokeWidth=h*.0055f;stroke.strokeCap=Paint.Cap.ROUND
            c.drawLine(dx-dr*.54f,dy-dr*.15f,dx-dr*.08f,dy-dr*.15f,stroke);c.drawLine(dx-dr*.46f,dy,dx,dy,stroke)
            p.style=Paint.Style.FILL;p.color=Color.WHITE;c.drawCircle(dx+dr*.21f,dy-dr*.06f,dr*.18f,p)
            txt(c,"DASH",dx,dy+dr*.78f,h*.024f)

            // Jump button
            val (bx,by)=jumpCenter();val br=jumpRadius()
            p.style=Paint.Style.FILL;p.color=red;c.drawCircle(bx,by,br,p)
            stroke.style=Paint.Style.STROKE;stroke.strokeWidth=h*.006f;stroke.color=Color.WHITE;c.drawCircle(bx,by,br,stroke)
            stroke.strokeWidth=h*.010f;stroke.strokeCap=Paint.Cap.ROUND
            for(off in floatArrayOf(-br*.20f,br*.02f)){
                val q=Path();q.moveTo(bx-br*.25f,by+off);q.lineTo(bx,by-br*.22f+off);q.lineTo(bx+br*.25f,by+off);c.drawPath(q,stroke)
            }
            txt(c,"JUMP",bx,by+br*.80f,h*.027f)

            if(won){
                p.color=0x99040A10.toInt();p.style=Paint.Style.FILL;c.drawRect(0f,0f,w,h,p)
                val card=RectF(w*.34f,h*.30f,w*.66f,h*.67f);rr(c,card,h*.035f,0xF02A3641.toInt())
                txt(c,"LEVEL COMPLETE",card.centerX(),card.top+h*.075f,h*.050f)
                val m=(finalTime/60f).toInt();val ss=finalTime-m*60f
                txt(c,String.format(Locale.US,"%02d:%05.2f",m,ss),card.centerX(),card.centerY(),h*.043f,gold)
                val play=RectF(w*.405f,h*.565f,w*.595f,h*.635f);rr(c,play,h*.025f,0xFFE53B35.toInt());txt(c,"PLAY AGAIN",play.centerX(),play.centerY(),h*.030f)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if(width<=height)return true
            val action=e.actionMasked
            val index=e.actionIndex
            val id=e.getPointerId(index)
            val x=e.getX(index);val y=e.getY(index)
            val (jx,jy)=joyCenter();val jr=joyRadius();val (dx,dy)=dashCenter();val dr=dashRadius();val (bx,by)=jumpCenter();val br=jumpRadius()

            if(won && action==MotionEvent.ACTION_DOWN){
                if(x in width*.405f..width*.595f && y in height*.565f..height*.635f){
                    val h=handle;if(h!=0L)nativeReset(h);won=false;paused=false;elapsed=0f;invalidate()
                }
                return true
            }

            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN){
                if(hypot(x-jx,y-jy)<=jr*1.25f && joyPointer<0){joyPointer=id;updateJoy(x,y)}
                else if(hypot(x-dx,y-dy)<=dr*1.35f){val h=handle;if(h!=0L)nativeDash(h)}
                else if(hypot(x-bx,y-by)<=br*1.25f){val h=handle;if(h!=0L)nativeJump(h)}
                else{
                    val rs=hudRects()
                    if(rs[3].contains(x,y)){sfx=!sfx;invalidate()}
                    else if(rs[4].contains(x,y)){paused=!paused;val h=handle;if(h!=0L)nativePause(h,paused);invalidate()}
                }
            }
            if(action==MotionEvent.ACTION_MOVE && joyPointer>=0){
                val pi=e.findPointerIndex(joyPointer);if(pi>=0)updateJoy(e.getX(pi),e.getY(pi))
            }
            if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP || action==MotionEvent.ACTION_CANCEL) && id==joyPointer){
                joyPointer=-1;joyX=0f;joyY=0f;moveX=0f;moveZ=0f;invalidate()
            }
            return true
        }

        private fun updateJoy(x:Float,y:Float){
            val (cx,cy)=joyCenter();val maxR=joyRadius()*.58f
            var dx=x-cx;var dy=y-cy;val d=hypot(dx,dy)
            if(d>maxR){dx*=maxR/d;dy*=maxR/d}
            joyX=dx;joyY=dy
            moveX=(dx/maxR).coerceIn(-1f,1f)
            moveZ=(dy/maxR).coerceIn(-1f,1f)
            invalidate()
        }
        fun showWin(time:Float){finalTime=time;won=true;invalidate()}
    }

    private fun initAudio(){
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        sounds=SoundPool.Builder().setMaxStreams(16).setAudioAttributes(attrs).build()
        soundIds["bounce1"]=sounds.load(this,R.raw.bq_bounce_1,1);soundIds["bounce2"]=sounds.load(this,R.raw.bq_bounce_2,1)
        soundIds["collect"]=sounds.load(this,R.raw.bq_collect,1);soundIds["jump"]=sounds.load(this,R.raw.bq_jump,1)
        soundIds["dash"]=sounds.load(this,R.raw.bq_dash,1);soundIds["death"]=sounds.load(this,R.raw.bq_death,1);soundIds["win"]=sounds.load(this,R.raw.bq_win,1)
    }

    private fun consumeEvent(e:String){
        if(e=="SFX:WIN"){
            val t=if(handle!=0L)nativeElapsed(handle) else hud.elapsed
            hud.showWin(t);paused=true;if(handle!=0L)nativePause(handle,true)
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

    override fun onDestroy(){
        handler.removeCallbacksAndMessages(null)
        val h=handle;handle=0L;if(h!=0L)nativeDestroy(h)
        sounds.release();super.onDestroy()
    }

    private external fun nativeCreate(surface: Surface, assets: AssetManager): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeInput(handle: Long, x: Float, z: Float)
    private external fun nativeJump(handle: Long)
    private external fun nativeDash(handle: Long)
    private external fun nativePause(handle: Long, paused: Boolean)
    private external fun nativeReset(handle: Long)
    private external fun nativeCoins(handle: Long): Int
    private external fun nativeElapsed(handle: Long): Float
    private external fun nativeEvents(handle: Long): String
}
