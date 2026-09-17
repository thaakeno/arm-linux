package com.example.dreamlinux

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Debian audio -> Android media pipeline.
 *
 * The guest mixes through PulseAudio/ALSA into a tiny PCM pipe. That pipe
 * connects through passt to this loopback server and is rendered with a normal
 * Android USAGE_MEDIA AudioTrack. This means routing, volume, Bluetooth, USB,
 * vendor DSP effects and the platform Spatializer remain owned by Android.
 */
object VesselAudioBridge {
    private val started = AtomicBoolean(false)
    private val activeStreams = AtomicInteger(0)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile private var appContext: Context? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var lastFormat = "idle"
    @Volatile private var lastError = ""
    @Volatile private var spatialStatus = "spatializer unavailable"

    fun start(context: Context, bindAddress: String = "127.0.0.1") {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        val srv = ServerSocket(0, 4, InetAddress.getByName(bindAddress))
        server = srv
        refreshSpatialStatus(null, null)
        Thread({ acceptLoop(srv) }, "vessel-audio-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun port(): Int = server?.localPort ?: -1

    fun status(): String {
        val streams = activeStreams.get()
        val base = if (streams > 0) "$lastFormat · $streams stream${if (streams == 1) "" else "s"}" else "ready · $lastFormat"
        return buildString {
            append("Android AudioTrack · ")
            append(base)
            append(" · ")
            append(spatialStatus)
            if (lastError.isNotBlank()) append(" · last error: $lastError")
        }
    }

    fun stop() {
        started.set(false)
        runCatching { server?.close() }
        server = null
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        activeStreams.set(0)
        lastFormat = "stopped"
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (started.get() && !srv.isClosed) {
            val socket = try {
                srv.accept()
            } catch (_: Throwable) {
                if (!started.get()) return
                continue
            }
            clients += socket
            Thread({ handle(socket) }, "vessel-audio-stream").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readAsciiLine(input: InputStream, maxBytes: Int = 256): String? {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < maxBytes) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value == '\n'.code) return bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value != '\r'.code) bytes += value.toByte()
        }
        error("PCM header is too long")
    }

    private fun handle(socket: Socket) {
        var track: AudioTrack? = null
        var streamCounted = false
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            // Read exactly through the newline. A BufferedReader can prefetch raw
            // PCM bytes past the header and make the first audio block disappear.
            val header = readAsciiLine(input) ?: error("missing PCM header")
            // VESSELAUDIO/1 <rate> <channels> <bits> <format>
            val f = header.trim().split(Regex("\\s+"))
            check(f.size >= 5 && f[0] == "VESSELAUDIO/1") { "bad PCM header" }
            val rate = f[1].toInt().coerceIn(8_000, 192_000)
            val channels = f[2].toInt().coerceIn(1, 8)
            val bits = f[3].toInt()
            check(bits == 16 && f[4].equals("S16_LE", ignoreCase = true)) { "only S16_LE is supported" }

            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }
            val effectiveChannels = when (channels) { 1, 2, 6, 8 -> channels; else -> 2 }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .build()
            val attrBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            if (Build.VERSION.SDK_INT >= 32) {
                attrBuilder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
                attrBuilder.setIsContentSpatialized(false)
            }
            val attrs = attrBuilder.build()
            val minBuffer = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Android rejected PCM format" }
            // ~40 ms target with enough headroom for scheduler jitter in UML/passt.
            val target = rate * effectiveChannels * 2 / 25
            val bufferBytes = max(minBuffer * 2, target)
            track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (_: Throwable) {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(bufferBytes, minBuffer * 4))
                    .build()
            }
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
            lastFormat = "${rate / 1000f} kHz · ${effectiveChannels}ch PCM"
            lastError = ""
            refreshSpatialStatus(attrs, format)
            activeStreams.incrementAndGet()
            streamCounted = true
            track.play()

            val bytes = ByteArray(32 * 1024)
            while (started.get()) {
                val n = input.read(bytes)
                if (n <= 0) break
                var off = 0
                while (off < n) {
                    val wrote = track.write(bytes, off, n - off, AudioTrack.WRITE_BLOCKING)
                    if (wrote <= 0) error("AudioTrack write failed: $wrote")
                    off += wrote
                }
            }
        } catch (t: Throwable) {
            if (started.get()) lastError = t.message ?: t.javaClass.simpleName
        } finally {
            if (track != null) {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
            }
            if (streamCounted) activeStreams.decrementAndGet()
            clients -= socket
            runCatching { socket.close() }
        }
    }

    private fun refreshSpatialStatus(attrs: AudioAttributes?, format: AudioFormat?) {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < 32) {
            spatialStatus = "platform media DSP"
            return
        }
        val manager = context.getSystemService(AudioManager::class.java)
        val spatializer = manager?.spatializer
        spatialStatus = if (spatializer == null) {
            "spatializer unavailable"
        } else {
            val capable = if (attrs != null && format != null) runCatching {
                spatializer.canBeSpatialized(attrs, format)
            }.getOrDefault(false) else false
            when {
                capable && spatializer.isAvailable && spatializer.isEnabled -> "Spatializer active"
                capable && spatializer.isAvailable -> "Spatializer available"
                spatializer.immersiveAudioLevel != 0 -> "spatial audio capable"
                else -> "platform/vendor media DSP"
            }
        }
    }
}
