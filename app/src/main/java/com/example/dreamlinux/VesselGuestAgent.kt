package com.example.dreamlinux

import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Stable post-boot RPC transport.
 *
 * The UML tty is intentionally used only while bootstrapping the guest. Once
 * KWin/Plasma owns the graphical session we use a tiny reconnecting guest agent
 * over passt's guest->host loopback mapping (10.0.2.2 -> Android 127.0.0.1).
 * This keeps Apps, Terminal, stats and package operations independent of tty0.
 */
object VesselGuestAgent {
    private val started = AtomicBoolean(false)
    private val requestId = AtomicLong(0)
    private val connectionLock = Object()
    private val ioLock = Any()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var authToken: String = ""
    @Volatile private var lastStatus: String = "stopped"

    fun start() {
        if (!started.compareAndSet(false, true)) return
        authToken = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val srv = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server = srv
        lastStatus = "listening:${srv.localPort}"
        Thread({ acceptLoop(srv) }, "vessel-guest-agent-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun port(): Int = server?.localPort ?: -1
    fun token(): String = authToken
    fun isConnected(): Boolean = socket?.let { it.isConnected && !it.isClosed } == true
    fun status(): String = lastStatus

    fun resetConnection() {
        synchronized(connectionLock) {
            closeConnectionLocked()
            lastStatus = if (started.get()) "listening:${port()}" else "stopped"
            connectionLock.notifyAll()
        }
    }

    fun stop() {
        started.set(false)
        synchronized(connectionLock) {
            closeConnectionLocked()
            runCatching { server?.close() }
            server = null
            lastStatus = "stopped"
            connectionLock.notifyAll()
        }
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (started.get() && !srv.isClosed) {
            val incoming = try {
                srv.accept()
            } catch (_: Throwable) {
                if (!started.get()) return
                continue
            }
            try {
                incoming.tcpNoDelay = true
                incoming.keepAlive = true
                incoming.soTimeout = 5000
                val r = BufferedReader(InputStreamReader(incoming.getInputStream(), Charsets.UTF_8), 64 * 1024)
                val w = BufferedWriter(OutputStreamWriter(incoming.getOutputStream(), Charsets.UTF_8), 64 * 1024)
                val hello = r.readLine()?.let(::JSONObject)
                if (hello?.optString("hello") != authToken) {
                    incoming.close()
                    continue
                }
                w.write(JSONObject().put("hello", "ok").toString())
                w.write("\n")
                w.flush()
                incoming.soTimeout = 0
                synchronized(connectionLock) {
                    closeConnectionLocked()
                    socket = incoming
                    reader = r
                    writer = w
                    lastStatus = "connected"
                    connectionLock.notifyAll()
                }
                // Do not read here. execute() owns request/response framing.
                while (started.get() && !incoming.isClosed && incoming.isConnected) {
                    try {
                        Thread.sleep(500)
                        if (incoming.isInputShutdown || incoming.isOutputShutdown) break
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (_: Throwable) {
                runCatching { incoming.close() }
            } finally {
                synchronized(connectionLock) {
                    if (socket === incoming) {
                        closeConnectionLocked()
                        lastStatus = if (started.get()) "reconnecting" else "stopped"
                        connectionLock.notifyAll()
                    }
                }
            }
        }
    }

    private fun awaitConnection(waitMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        synchronized(connectionLock) {
            while (started.get() && !isConnected()) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                try {
                    connectionLock.wait(left.coerceAtMost(500))
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return isConnected()
        }
    }

    fun execute(
        command: String,
        timeoutSeconds: Int,
        onLine: ((String) -> Unit)? = null,
    ): Pair<Int, String> = synchronized(ioLock) {
        check(started.get()) { "Vessel guest control server is not running" }
        check(awaitConnection(20_000)) {
            "Debian control agent did not connect; graphical desktop is left running for diagnostics"
        }
        val id = requestId.incrementAndGet()
        val req = JSONObject()
            .put("id", id)
            .put("timeout", timeoutSeconds.coerceIn(1, 3600))
            .put("command", Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8)))
        val out = StringBuilder()
        val s = socket ?: error("Debian control agent disconnected")
        val r = reader ?: error("Debian control agent reader missing")
        val w = writer ?: error("Debian control agent writer missing")
        try {
            s.soTimeout = (timeoutSeconds.coerceIn(1, 3600) + 15) * 1000
            w.write(req.toString())
            w.write("\n")
            w.flush()
            while (true) {
                val raw = r.readLine() ?: throw IllegalStateException("Debian control agent disconnected")
                val msg = JSONObject(raw)
                if (msg.optLong("id", -1L) != id) continue
                when (msg.optString("type")) {
                    "chunk" -> {
                        val chunk = runCatching {
                            String(Base64.getDecoder().decode(msg.optString("data")), Charsets.UTF_8)
                        }.getOrDefault("")
                        if (chunk.isNotEmpty()) {
                            out.append(chunk)
                            if (out.length > 1_000_000) out.delete(0, out.length - 800_000)
                            onLine?.let { cb -> chunk.lineSequence().forEach(cb) }
                        }
                    }
                    "done" -> {
                        lastStatus = "connected"
                        return@synchronized msg.optInt("rc", -1) to out.toString()
                    }
                }
            }
        } catch (t: SocketTimeoutException) {
            resetConnection()
            throw IllegalStateException("Debian control command timed out after ${timeoutSeconds}s", t)
        } catch (t: Throwable) {
            resetConnection()
            throw t
        } finally {
            runCatching { s.soTimeout = 0 }
        }
    }

    private fun closeConnectionLocked() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
