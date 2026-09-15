package com.example.dreamlinux

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.truncate

/** Persistent low-latency Android -> Vessel Linux uinput/evdev channel. */
object VesselInputClient {
    private const val PORT = 47634
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingDeque<String>(1024)
    private val fractionLock = Any()
    private var fracX = 0f
    private var fracY = 0f
    private var scrollFracX = 0f
    private var scrollFracY = 0f

    init {
        Thread(::loop, "vessel-input-client").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun send(obj: JSONObject, motion: Boolean = false) {
        val line = obj.toString()
        if (motion && queue.remainingCapacity() < 64) {
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

    /** Returns the exact integer delta sent to Linux so fractional motion is never lost. */
    fun relative(dx: Float, dy: Float): Pair<Int, Int> {
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
        if (ix != 0 || iy != 0) {
            send(JSONObject().put("t", "rel").put("dx", ix).put("dy", iy), motion = true)
        }
        return ix to iy
    }

    fun button(code: Int, down: Boolean) =
        send(JSONObject().put("t", "btn").put("code", code).put("down", down))

    fun scroll(x: Int, y: Int) {
        if (x == 0 && y == 0) return
        send(JSONObject().put("t", "scroll").put("x", x).put("y", y), motion = true)
    }

    /** Preserve fractional wheel/gesture deltas instead of dropping slow two-finger scrolling. */
    fun scrollPrecise(x: Float, y: Float) {
        val ix: Int
        val iy: Int
        synchronized(fractionLock) {
            scrollFracX += x
            scrollFracY += y
            ix = truncate(scrollFracX).toInt()
            iy = truncate(scrollFracY).toInt()
            scrollFracX -= ix
            scrollFracY -= iy
        }
        scroll(ix, iy)
    }

    fun key(code: Int, down: Boolean) =
        send(JSONObject().put("t", "key").put("code", code).put("down", down))

    private fun tapKey(code: Int, shift: Boolean = false) {
        if (shift) key(42, true)
        key(code, true)
        key(code, false)
        if (shift) key(42, false)
    }

    /**
     * Android IMEs usually commit text instead of emitting hardware KeyEvents.
     * Convert the common keyboard set to genuine Linux evdev key strokes so it
     * works in Plasma without a compositor-specific text injection shortcut.
     */
    fun text(value: String) {
        value.forEach { c ->
            val lower = c.lowercaseChar()
            val letter = when (lower) {
                'a' -> 30; 'b' -> 48; 'c' -> 46; 'd' -> 32; 'e' -> 18; 'f' -> 33
                'g' -> 34; 'h' -> 35; 'i' -> 23; 'j' -> 36; 'k' -> 37; 'l' -> 38
                'm' -> 50; 'n' -> 49; 'o' -> 24; 'p' -> 25; 'q' -> 16; 'r' -> 19
                's' -> 31; 't' -> 20; 'u' -> 22; 'v' -> 47; 'w' -> 17; 'x' -> 45
                'y' -> 21; 'z' -> 44
                else -> null
            }
            if (letter != null) {
                tapKey(letter, c.isUpperCase())
                return@forEach
            }
            when (c) {
                '1' -> tapKey(2); '2' -> tapKey(3); '3' -> tapKey(4); '4' -> tapKey(5)
                '5' -> tapKey(6); '6' -> tapKey(7); '7' -> tapKey(8); '8' -> tapKey(9)
                '9' -> tapKey(10); '0' -> tapKey(11)
                ' ' -> tapKey(57); '\n', '\r' -> tapKey(28); '\t' -> tapKey(15)
                '-' -> tapKey(12); '_' -> tapKey(12, true)
                '=' -> tapKey(13); '+' -> tapKey(13, true)
                '[' -> tapKey(26); '{' -> tapKey(26, true)
                ']' -> tapKey(27); '}' -> tapKey(27, true)
                ';' -> tapKey(39); ':' -> tapKey(39, true)
                '\'' -> tapKey(40); '"' -> tapKey(40, true)
                '`' -> tapKey(41); '~' -> tapKey(41, true)
                '\\' -> tapKey(43); '|' -> tapKey(43, true)
                ',' -> tapKey(51); '<' -> tapKey(51, true)
                '.' -> tapKey(52); '>' -> tapKey(52, true)
                '/' -> tapKey(53); '?' -> tapKey(53, true)
                '!' -> tapKey(2, true); '@' -> tapKey(3, true); '#' -> tapKey(4, true)
                '$' -> tapKey(5, true); '%' -> tapKey(6, true); '^' -> tapKey(7, true)
                '&' -> tapKey(8, true); '*' -> tapKey(9, true); '(' -> tapKey(10, true)
                ')' -> tapKey(11, true)
            }
        }
    }

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
