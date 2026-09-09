package com.example.dreamlinux

import android.os.ParcelFileDescriptor
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Non-blocking facade so Linux provisioning never freezes the Android UI. */
class AsyncVmBridge : IVmBridge.Stub() {
    private val delegate = VmBridge()
    private val linuxLaunchActive = AtomicBoolean(false)
    @Volatile private var cachedStatus = "{}"
    @Volatile private var launchStartedAt = 0L
    @Volatile private var launchError = ""

    override fun startVm(): String = delegate.startVm().also { cachedStatus = it }

    override fun stopVm(): String {
        linuxLaunchActive.set(false)
        return delegate.stopVm().also { cachedStatus = it }
    }

    override fun status(): String {
        if (!linuxLaunchActive.get()) return delegate.status().also { cachedStatus = it }
        val elapsed = ((android.os.SystemClock.elapsedRealtime() - launchStartedAt) / 1000L).coerceAtLeast(0L)

        // VmBridge.status() is deliberately non-blocking now, so publish the real live stage/logs
        // even while startDebian() (our AIDL-compatible Start Alpine call) is still provisioning.
        val live = runCatching { JSONObject(delegate.status()) }.getOrNull()
        return runCatching {
            val base = live ?: JSONObject(cachedStatus.ifBlank { "{}" })
            base.put("debianStarting", true)
            base.put("startupElapsedSeconds", elapsed)
            if (base.optString("mode").isBlank() || base.optString("mode") == "none") base.put("mode", "alpine")
            if (base.optString("name").isBlank()) base.put("name", "dev1-alpine-pvm-v1")
            if (base.optString("stage").isBlank() || base.optString("stage") == "idle") base.put("stage", "alpine_starting_background")
            if (launchError.isNotBlank()) base.put("error", launchError)
            base.toString()
        }.getOrElse {
            JSONObject()
                .put("running", false)
                .put("mode", "alpine")
                .put("name", "dev1-alpine-pvm-v1")
                .put("stage", "alpine_starting_background")
                .put("debianStarting", true)
                .put("startupElapsedSeconds", elapsed)
                .put("error", launchError)
                .toString()
        }
    }

    override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String {
        if (!linuxLaunchActive.compareAndSet(false, true)) return status()
        launchError = ""
        launchStartedAt = android.os.SystemClock.elapsedRealtime()
        cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
        Thread({
            try {
                cachedStatus = delegate.startDebian(width, height, dpi, refreshRate)
            } catch (t: Throwable) {
                launchError = "${t.javaClass.name}: ${t.message}"
            } finally {
                linuxLaunchActive.set(false)
                cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
            }
        }, "dev1-alpine-startup").also { it.isDaemon = true; it.start() }
        return status()
    }

    override fun startDebianDiagnostic(): String {
        if (!linuxLaunchActive.compareAndSet(false, true)) return status()
        launchError = ""
        launchStartedAt = android.os.SystemClock.elapsedRealtime()
        cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
        Thread({
            try {
                cachedStatus = delegate.startDebianDiagnostic()
            } catch (t: Throwable) {
                launchError = "${t.javaClass.name}: ${t.message}"
            } finally {
                linuxLaunchActive.set(false)
                cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
            }
        }, "dev1-alpine-diagnostic").also { it.isDaemon = true; it.start() }
        return status()
    }

    override fun guestShell(command: String): String = delegate.guestShell(command)
    override fun installDebian(): String = delegate.installDebian()
    override fun inspectCapabilities(): String = delegate.inspectCapabilities()
    override fun setDisplaySurface(surface: Surface) = delegate.setDisplaySurface(surface)
    override fun clearDisplaySurface() = delegate.clearDisplaySurface()
    override fun sendKey(action: Int, keyCode: Int, metaState: Int): Boolean = delegate.sendKey(action, keyCode, metaState)
    override fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int): Boolean = delegate.sendTouch(action, x, y, pointerId)
    override fun installKde(): String = delegate.installKde()
    override fun debianConsole(command: String): String = delegate.debianConsole(command)
    override fun openDebianVsock(port: Int): ParcelFileDescriptor = delegate.openDebianVsock(port)
    override fun destroy() = delegate.destroy()
}
