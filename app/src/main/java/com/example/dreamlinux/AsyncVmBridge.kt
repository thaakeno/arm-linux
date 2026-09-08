package com.example.dreamlinux

import android.os.ParcelFileDescriptor
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Non-blocking Shizuku-side facade around VmBridge.
 *
 * VmBridge.startDebian() intentionally performs guest verification, bridge provisioning and the
 * Internet probe. Those steps can take a while and the original AIDL call held VmBridge's monitor
 * for the whole sequence, which made status() block too. The Android UI therefore looked frozen.
 *
 * This facade runs that long transaction on a worker thread and serves a lightweight status
 * snapshot while it is in flight. Once the worker completes, status() switches back to the real
 * VmBridge status. No VM or AVF semantics are changed here; this only removes Binder/UI blocking.
 */
class AsyncVmBridge : IVmBridge.Stub() {
    private val delegate = VmBridge()
    private val debianLaunchActive = AtomicBoolean(false)
    @Volatile private var cachedStatus = "{}"
    @Volatile private var launchStartedAt = 0L
    @Volatile private var launchError = ""

    override fun startVm(): String = delegate.startVm().also { cachedStatus = it }
    override fun stopVm(): String {
        debianLaunchActive.set(false)
        return delegate.stopVm().also { cachedStatus = it }
    }

    override fun status(): String {
        if (!debianLaunchActive.get()) {
            return delegate.status().also { cachedStatus = it }
        }
        val elapsed = ((android.os.SystemClock.elapsedRealtime() - launchStartedAt) / 1000L).coerceAtLeast(0L)
        return runCatching {
            val base = JSONObject(cachedStatus.ifBlank { "{}" })
            base.put("running", false)
            base.put("mode", "debian")
            base.put("name", "dev1-debian13-vnc1")
            base.put("stage", "debian_starting_background")
            base.put("error", launchError)
            base.put("debianStarting", true)
            base.put("startupElapsedSeconds", elapsed)
            base.put("guestGraphics", "Starting Debian in background; VNC fallback pending")
            base.toString()
        }.getOrElse {
            JSONObject()
                .put("running", false)
                .put("mode", "debian")
                .put("name", "dev1-debian13-vnc1")
                .put("stage", "debian_starting_background")
                .put("debianStarting", true)
                .put("startupElapsedSeconds", elapsed)
                .toString()
        }
    }

    override fun startDebian(width: Int, height: Int, dpi: Int, refreshRate: Int): String {
        if (!debianLaunchActive.compareAndSet(false, true)) return status()
        launchError = ""
        launchStartedAt = android.os.SystemClock.elapsedRealtime()
        cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
        Thread({
            try {
                cachedStatus = delegate.startDebian(width, height, dpi, refreshRate)
            } catch (t: Throwable) {
                launchError = "${t.javaClass.name}: ${t.message}"
            } finally {
                debianLaunchActive.set(false)
                cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
            }
        }, "dev1-debian-startup").also { it.isDaemon = true; it.start() }
        return status()
    }

    override fun startDebianDiagnostic(): String {
        if (!debianLaunchActive.compareAndSet(false, true)) return status()
        launchError = ""
        launchStartedAt = android.os.SystemClock.elapsedRealtime()
        cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
        Thread({
            try {
                cachedStatus = delegate.startDebianDiagnostic()
            } catch (t: Throwable) {
                launchError = "${t.javaClass.name}: ${t.message}"
            } finally {
                debianLaunchActive.set(false)
                cachedStatus = runCatching { delegate.status() }.getOrDefault(cachedStatus)
            }
        }, "dev1-debian-diagnostic").also { it.isDaemon = true; it.start() }
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
