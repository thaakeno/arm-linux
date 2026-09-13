package com.example.dreamlinux

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent low-latency Android -> Termux -> guest evdev input channel. */
object VesselInputClient {
    // 47632 is reserved for the long-lived UML guest command agent.
    private const val PORT = 47634
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingDeque<String>(384)

    init {
        Thread(::loop, "vessel-input-client").apply { isDaemon = true; start() }
    }

    fun send(obj: JSONObject) {
        val line = obj.toString()
        if (!queue.offerLast(line)) {
            queue.pollFirst()
            queue.offerLast(line)
        }
    }

    fun absolute(x: Float, y: Float, down: Boolean) = send(JSONObject().put("t","abs").put("x",(x.coerceIn(0f,1f)*32767f).toInt()).put("y",(y.coerceIn(0f,1f)*32767f).toInt()).put("down",down))
    fun relative(dx: Float, dy: Float) = send(JSONObject().put("t","rel").put("dx",dx.toInt()).put("dy",dy.toInt()))
    fun button(code: Int, down: Boolean) = send(JSONObject().put("t","btn").put("code",code).put("down",down))
    fun scroll(x: Int, y: Int) = send(JSONObject().put("t","scroll").put("x",x).put("y",y))
    fun key(code: Int, down: Boolean) = send(JSONObject().put("t","key").put("code",code).put("down",down))

    private fun loop() {
        var backoff = 80L
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress("127.0.0.1", PORT), 1200)
                    val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), 16 * 1024)
                    backoff = 80L
                    while (running.get()) {
                        val line = queue.takeFirst()
                        out.write(line); out.newLine(); out.flush()
                    }
                }
            } catch (_: Throwable) {
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(800L)
            }
        }
    }
}
