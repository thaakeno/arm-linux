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
 * The UML tty is used only for bootstrap. Once Plasma owns the graphical
 * session a tiny reconnecting guest agent talks to this loopback server through
 * passt (10.0.2.2 -> Android 127.0.0.1). New guest connections are accepted
 * immediately even when the previous Java Socket still looks "connected".
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
    @Volatile private var connectionGeneration = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        authToken = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val srv = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = srv
        lastStatus = "listening:${srv.localPort}"
        Thread({ acceptLoop(srv) }, "vessel-guest-agent-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun port(): Int = server?.localPort ?: -1
    fun token(): String = authToken
    fun isConnected(): Boolean = synchronized(connectionLock) {
        socket?.let { it.isConnected && !it.isClosed && !it.isInputShutdown && !it.isOutputShutdown } == true
    }
    fun status(): String = lastStatus
    fun waitUntilConnected(waitMs: Long = 20_000): Boolean = awaitConnection(waitMs.coerceIn(250, 60_000))

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

    /**
     * Accept continuously instead of parking the accept thread on a socket that
     * may already have died remotely. The guest reconnect loop can therefore
     * replace a stale channel without waiting for a host RPC to discover it.
     */
    private fun acceptLoop(srv: ServerSocket) {
        while (started.get() && !srv.isClosed) {
            val incoming = try {
                srv.accept()
            } catch (_: Throwable) {
                if (!started.get()) return
                continue
            }
            var adopted = false
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
                    connectionGeneration++
                    lastStatus = "connected"
                    adopted = true
                    connectionLock.notifyAll()
                }
                // Loop immediately back to accept(). A later guest reconnect can
                // atomically replace this socket even if Java has not noticed EOF.
            } catch (_: Throwable) {
                if (!adopted) runCatching { incoming.close() }
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
                    connectionLock.wait(left.coerceAtMost(250))
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return isConnected()
        }
    }

    /**
     * Run one serialized request. If the socket dies before the guest acknowledges
     * the command, reconnect and retry once. Once "accepted" is received we never
     * retry automatically, preventing duplicate apt/install or other mutations.
     */
    fun execute(
        command: String,
        timeoutSeconds: Int,
        onLine: ((String) -> Unit)? = null,
    ): Pair<Int, String> = synchronized(ioLock) {
        check(started.get()) { "Vessel guest control server is not running" }
        var attempt = 0
        var lastFailure: Throwable? = null
        while (attempt < 2) {
            attempt++
            check(awaitConnection(if (attempt == 1) 8_000 else 3_000)) {
                "Debian control agent did not connect; graphical desktop is left running for diagnostics"
            }
            val id = requestId.incrementAndGet()
            val req = JSONObject()
                .put("id", id)
                .put("timeout", timeoutSeconds.coerceIn(1, 3600))
                .put("command", Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8)))
            val out = StringBuilder()
            var accepted = false
            val s: Socket
            val r: BufferedReader
            val w: BufferedWriter
            val generation: Long
            synchronized(connectionLock) {
                s = socket ?: error("Debian control agent disconnected")
                r = reader ?: error("Debian control agent reader missing")
                w = writer ?: error("Debian control agent writer missing")
                generation = connectionGeneration
            }
            try {
                s.soTimeout = (timeoutSeconds.coerceIn(1, 3600) + 10) * 1000
                w.write(req.toString())
                w.write("\n")
                w.flush()
                while (true) {
                    val raw = r.readLine() ?: throw IllegalStateException("Debian control agent disconnected")
                    val msg = JSONObject(raw)
                    if (msg.optLong("id", -1L) != id) continue
                    when (msg.optString("type")) {
                        "accepted" -> accepted = true
                        "chunk" -> {
                            accepted = true
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
                lastFailure = IllegalStateException("Debian control command timed out after ${timeoutSeconds}s", t)
                synchronized(connectionLock) {
                    if (connectionGeneration == generation) closeConnectionLocked()
                    lastStatus = "reconnecting"
                    connectionLock.notifyAll()
                }
                if (accepted || attempt >= 2) throw lastFailure
            } catch (t: Throwable) {
                lastFailure = t
                synchronized(connectionLock) {
                    if (connectionGeneration == generation) closeConnectionLocked()
                    lastStatus = "reconnecting"
                    connectionLock.notifyAll()
                }
                if (accepted || attempt >= 2) throw t
            } finally {
                runCatching { s.soTimeout = 0 }
            }
        }
        throw lastFailure ?: IllegalStateException("Debian control channel unavailable")
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
