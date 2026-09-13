package com.example.dreamlinux

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.truncate

/** Persistent low-latency Android -> guest native input channel. */
object VesselInputClient {
    // 47632 is reserved for the long-lived UML guest command agent.
    private const val PORT = 47634
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingDeque<String>(1024)
    private val fractionLock = Any()
    private var fracX = 0f
    private var fracY = 0f

    init {
        Thread(::loop, "vessel-input-client").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun send(obj: JSONObject, motion: Boolean = false) {
        val line = obj.toString()
        if (motion && queue.remainingCapacity() < 64) {
            // Never let stale pointer motion build up behind the finger. Buttons
            // and keys remain lossless; only superseded motion may be dropped.
            while (queue.size > 768) queue.pollFirst()
        }
        if (!queue.offerLast(line)) {
            queue.pollFirst()
            queue.offerLast(line)
        }
    }

    fun absolute(x: Float, y: Float, down: Boolean) = send(
        JSONObject().put("t", "abs")
            .put("x", (x.coerceIn(0f, 1f) * 32767f).toInt())
            .put("y", (y.coerceIn(0f, 1f) * 32767f).toInt())
            .put("down", down),
        motion = true,
    )

    fun relative(dx: Float, dy: Float) {
        val ix: Int
        val iy: Int
        synchronized(fractionLock) {
            fracX += dx
            fracY += dy
            ix = truncate(fracX).toInt()
            iy = truncate(fracY).toInt()
            fracX -= ix
            fracY -= iy
        }
        if (ix == 0 && iy == 0) return
        send(JSONObject().put("t", "rel").put("dx", ix).put("dy", iy), motion = true)
    }

    fun button(code: Int, down: Boolean) =
        send(JSONObject().put("t", "btn").put("code", code).put("down", down))

    fun scroll(x: Int, y: Int) {
        if (x == 0 && y == 0) return
        send(JSONObject().put("t", "scroll").put("x", x).put("y", y))
    }

    fun key(code: Int, down: Boolean) =
        send(JSONObject().put("t", "key").put("code", code).put("down", down))

    private fun loop() {
        var backoff = 20L
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.sendBufferSize = 64 * 1024
                    socket.connect(InetSocketAddress("127.0.0.1", PORT), 800)
                    val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), 32 * 1024)
                    backoff = 20L
                    while (running.get()) {
                        val first = queue.takeFirst()
                        out.write(first)
                        out.newLine()
                        // Drain everything already queued in one socket write. This
                        // avoids one flush/syscall per high-rate pointer sample.
                        var drained = 0
                        while (drained < 64) {
                            val next = queue.pollFirst() ?: break
                            out.write(next)
                            out.newLine()
                            drained++
                        }
                        out.flush()
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = (backoff * 2).coerceAtMost(400L)
            }
        }
    }
}
