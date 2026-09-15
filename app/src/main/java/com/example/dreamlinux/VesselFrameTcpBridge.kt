package com.example.dreamlinux

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-app frame bridge for protocol 38.
 *
 * Termux cannot send SCM_RIGHTS directly to Vessel because Android SELinux
 * blocks the cross-UID AF_UNIX connection. The v38 host renderer therefore
 * sends standard vhost-user-gpu RGB updates over loopback TCP. Inside the
 * Vessel UID we proxy that byte stream into the existing native presenter
 * abstract socket, preserving the native Vulkan SurfaceView presentation path.
 */
object VesselFrameTcpBridge {
    private const val TAG = "VesselFrameBridge"
    private const val PORT = 47635
    private const val PRESENTER_SOCKET = "vessel-wayland-v1"
    private val started = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread(::serve, "vessel-frame-tcp").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun serve() {
        try {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.receiveBufferSize = 4 * 1024 * 1024
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
                server = ss
                Log.i(TAG, "frame bridge listening on 127.0.0.1:$PORT")
                while (started.get()) {
                    val client = try { ss.accept() } catch (_: Throwable) { break }
                    Thread({ forward(client) }, "vessel-frame-client").apply {
                        isDaemon = true
                        priority = Thread.MAX_PRIORITY
                        start()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame bridge failed", t)
        } finally {
            server = null
            started.set(false)
        }
    }

    private fun forward(client: Socket) {
        client.use { tcp ->
            tcp.tcpNoDelay = true
            tcp.keepAlive = true
            tcp.receiveBufferSize = 4 * 1024 * 1024
            val local = LocalSocket()
            try {
                local.connect(LocalSocketAddress(PRESENTER_SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                Log.i(TAG, "Termux frame stream connected to native presenter")
                val input = tcp.getInputStream()
                val output = local.outputStream
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                }
                output.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "frame stream forwarding failed", t)
            } finally {
                runCatching { local.close() }
            }
        }
    }

    fun stop() {
        started.set(false)
        runCatching { server?.close() }
        server = null
    }
}
