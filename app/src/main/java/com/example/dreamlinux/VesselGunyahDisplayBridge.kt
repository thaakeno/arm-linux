package com.example.dreamlinux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.crosvm.ICrosvmAndroidDisplayService
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crosvm's Android display service lives in servicemanager. Android intentionally blocks an
 * untrusted app from looking it up, so the Gunyah backend uses the same shape as DroidVM:
 * a tiny root app_process performs only the servicemanager lookup and broadcasts the live binder
 * back through system_server. The UI remains an ordinary Android process.
 */
object VesselGunyahDisplayBridge {
    const val SERVICE_NAME = "vessel_gunyah_disp_gpu0"
    private const val ACTION = "com.example.dreamlinux.GUNYAH_DISPLAY_BINDER"
    private const val EXTRA_BUNDLE = "bundle"
    private const val EXTRA_BINDER = "binder"
    private const val EXTRA_NONCE = "nonce"

    @Volatile private var appContext: Context? = null
    @Volatile private var service: ICrosvmAndroidDisplayService? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var sentSurface: Surface? = null
    @Volatile private var width = 1920
    @Volatile private var height = 1080
    @Volatile private var state = "not-started"
    private val fetching = AtomicBoolean(false)
    private var nonce = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val bundle = intent.getBundleExtra(EXTRA_BUNDLE) ?: return
            if (bundle.getString(EXTRA_NONCE) != nonce) return
            val binder = bundle.getBinder(EXTRA_BINDER) ?: return
            attachBinder(binder)
        }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val ctx = context.applicationContext
            ctx.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
            appContext = ctx
        }
    }

    fun attach(context: Context, newSurface: Surface) {
        initialize(context)
        surface = newSurface
        if (service != null) {
            sendSurface()
        } else {
            requestBinder()
        }
    }

    fun surfaceChanged(w: Int, h: Int) {
        if (w > 0 && h > 0 && service == null) {
            // Presentation geometry must never become the guest mode. These are fallback values
            // only until crosvm reports the real scanout through getDisplayConfig().
            state = "surface-ready-waiting-for-crosvm"
        }
    }

    fun detach() {
        val svc = service
        if (svc != null && sentSurface != null) {
            runCatching { svc.saveFrameForSurface(false) }
            runCatching { svc.removeSurface(false) }
        }
        sentSurface = null
        surface = null
        state = if (svc != null) "crosvm-connected-no-surface" else "waiting-for-crosvm"
    }

    fun shutdown() {
        detach()
        service = null
        fetching.set(false)
        state = "stopped"
    }

    fun status(): String = state
    fun guestWidth(): Int = width
    fun guestHeight(): Int = height

    private fun attachBinder(binder: IBinder) {
        fetching.set(false)
        val svc = ICrosvmAndroidDisplayService.Stub.asInterface(binder)
        service = svc
        state = "crosvm-display-connected"
        runCatching {
            binder.linkToDeath({
                service = null
                sentSurface = null
                state = "crosvm-display-died"
                requestBinder()
            }, 0)
        }
        runCatching {
            svc.displayConfig
        }.getOrNull()?.let { cfg ->
            if (cfg.width > 0 && cfg.height > 0) {
                width = cfg.width
                height = cfg.height
            }
        }
        sendSurface()
    }

    private fun sendSurface() {
        val svc = service ?: return
        val target = surface ?: return
        if (!target.isValid || sentSurface === target) return
        try {
            svc.setSurface(target, false)
            sentSurface = target
            state = "presenting-gunyah-native-surface"
        } catch (_: IllegalArgumentException) {
            // Crosvm's binder backend can throw after accepting the Surface. DroidVM treats this
            // as delivered as well.
            sentSurface = target
            state = "presenting-gunyah-native-surface"
        } catch (t: Throwable) {
            state = "crosvm-surface-error:${t.javaClass.simpleName}"
        }
    }

    private fun requestBinder() {
        val ctx = appContext ?: return
        if (!fetching.compareAndSet(false, true)) return
        nonce = UUID.randomUUID().toString()
        state = "waiting-for-crosvm-display"
        Thread({
            try {
                val apk = ctx.applicationInfo.sourceDir
                val cmd = "CLASSPATH=${shell(apk)} /system/bin/app_process /system/bin " +
                    "com.example.dreamlinux.VesselGunyahBinderBridge ${shell(SERVICE_NAME)} ${shell(nonce)}"
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                if (service == null) state = "waiting-for-crosvm-display"
            } catch (t: Throwable) {
                state = "display-broker-error:${t.javaClass.simpleName}"
            } finally {
                if (service == null) fetching.set(false)
            }
        }, "vessel-gunyah-binder").apply { isDaemon = true; start() }
    }

    private fun shell(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal fun broadcastAction(): String = ACTION
    internal fun extraBundle(): String = EXTRA_BUNDLE
    internal fun extraBinder(): String = EXTRA_BINDER
    internal fun extraNonce(): String = EXTRA_NONCE
}

/**
 * Executed only through root's app_process. Kept deliberately tiny: resolve one Binder service,
 * wrap it in a nonce-bearing explicit broadcast, exit.
 */
object VesselGunyahBinderBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) return
        val serviceName = args[0]
        val nonce = args[1]
        val context = systemContext() ?: return
        val binder = waitForService(serviceName) ?: return
        val bundle = Bundle().apply {
            putBinder(VesselGunyahDisplayBridge.extraBinder(), binder)
            putString(VesselGunyahDisplayBridge.extraNonce(), nonce)
        }
        val intent = Intent(VesselGunyahDisplayBridge.broadcastAction()).apply {
            setPackage("com.example.dreamlinux")
            putExtra(VesselGunyahDisplayBridge.extraBundle(), bundle)
        }
        context.sendBroadcast(intent)
    }

    private fun systemContext(): Context? {
        return runCatching {
            if (Looper.myLooper() == null) Looper.prepare()
            val cls = Class.forName("android.app.ActivityThread")
            val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
            val thread = ctor.newInstance()
            cls.getMethod("getSystemContext").invoke(thread) as Context
        }.getOrNull()
    }

    private fun waitForService(name: String): IBinder? {
        return runCatching {
            val cls = Class.forName("android.os.ServiceManager")
            val check = cls.getMethod("checkService", String::class.java)
            val deadline = System.nanoTime() + 5_000_000_000L
            var binder: IBinder? = null
            while (binder == null && System.nanoTime() < deadline) {
                binder = check.invoke(null, name) as? IBinder
                if (binder == null) Thread.sleep(100)
            }
            binder
        }.getOrNull()
    }
}
