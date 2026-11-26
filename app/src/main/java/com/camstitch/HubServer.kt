package com.camstitch

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Per-stream counters, read by the overlay. */
class StreamStats {
    @Volatile var rxFrames = 0L
    @Volatile var decFrames = 0L
    @Volatile var dropped = 0L
    @Volatile var latencyMs = Double.NaN      // capture (sender sensor) -> decoded on hub
    @Volatile var synced = false
    @Volatile var senderRttMs = 0.0
    @Volatile var senderFps = 0.0
    @Volatile var senderLocked = false
    @Volatile var kbps = 0.0
    internal var bytesWindow = 0L
}

/** Accepts sender connections and advertises the hub over mDNS. */
class HubServer(private val ctx: Context, private val renderer: HubRenderer, private val log: (String) -> Unit) {

    val streams = CopyOnWriteArrayList<RemoteStream>()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val multicastLock = ctx.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("camstitch-hub").apply { setReferenceCounted(false) }

    fun start() {
        running = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(Proto.PORT))
        server = ss
        thread(name = "hub-accept") {
            while (running) {
                val sock = try { ss.accept() } catch (e: IOException) { break }
                val rs = RemoteStream(sock, renderer, this::onHello, this::onClosed)
                streams += rs
                rs.start()
            }
        }
        registerNsd()
        log("hub listening on port ${Proto.PORT}")
    }

    private fun onHello(rs: RemoteStream) {
        // A phone that reconnects replaces its old (stale) stream.
        val dupes = streams.filter { it !== rs && it.name == rs.name }
        if (dupes.any { it.remoteAddress != rs.remoteAddress }) {
            log("WARNING: two phones are both named '${rs.name}' - give each phone a unique name")
        }
        dupes.forEach { it.close() }
        log("'${rs.name}' connected (${rs.width}x${rs.height}, ${rs.model})")
    }

    private fun onClosed(rs: RemoteStream) {
        streams.remove(rs)
        if (running) log("'${rs.name}' disconnected")
    }

    private fun registerNsd() {
        try {
            multicastLock.acquire()
            val info = NsdServiceInfo().apply {
                serviceName = Proto.SERVICE_NAME
                serviceType = Proto.SERVICE_TYPE
                port = Proto.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { log("mDNS registration failed ($e): senders must enter the hub IP") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            ctx.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        } catch (e: Exception) {
            log("mDNS unavailable: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        nsdListener?.let { try { ctx.getSystemService(NsdManager::class.java).unregisterService(it) } catch (_: Exception) {} }
        nsdListener = null
        if (multicastLock.isHeld) multicastLock.release()
        streams.forEach { it.close() }
        streams.clear()
    }
}

/** One connected sender: reads the TCP stream and decodes H.264 into the renderer's surface. */
class RemoteStream(
    private val socket: Socket,
    private val renderer: HubRenderer,
    private val onHello: (RemoteStream) -> Unit,
    private val onClosed: (RemoteStream) -> Unit,
) {
    @Volatile var name = "?"; private set
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var model = ""; private set
    val stats = StreamStats()
    val remoteAddress: String = socket.inetAddress?.hostAddress ?: "?"

    private val closed = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var source: HubRenderer.Source? = null
    private var outThread: Thread? = null
    private lateinit var out: DataOutputStream
    @Volatile private var waitingForKey = true

    fun start() = thread(name = "hub-rx") { readLoop() }

    private fun readLoop() {
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 1 shl 20
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
            out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val hello = Proto.read(inp)
            if (hello.type != Proto.HELLO) throw IOException("expected HELLO")
            val j = JSONObject(String(hello.data))
            name = j.getString("name"); width = j.getInt("width"); height = j.getInt("height")
            model = j.optString("model")
            onHello(this)

            val src = renderer.createSource(name, width, height) ?: throw IOException("renderer not ready")
            source = src
            startDecoder(src)
            requestKeyFrame()

            while (!closed.get()) {
                val m = Proto.read(inp)
                stats.bytesWindow += m.data.size
                when (m.type) {
                    Proto.CONFIG -> feed(m.data, 0, m.data.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    Proto.FRAME -> onFrame(m.data)
                    Proto.TIME_REQ -> {
                        val t1 = System.nanoTime()
                        val t0 = ByteBuffer.wrap(m.data).long
                        Proto.write(out, Proto.TIME_RESP, Proto.longs(t0, t1)); out.flush()
                    }
                    Proto.STATS -> {
                        val s = JSONObject(String(m.data))
                        stats.senderRttMs = s.optDouble("rtt_ms"); stats.senderFps = s.optDouble("enc_fps")
                        stats.senderLocked = s.optBoolean("locked")
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun onFrame(d: ByteArray) {
        stats.rxFrames++
        val b = ByteBuffer.wrap(d)
        val captureHubNs = b.long
        val flags = b.get().toInt()
        val isKey = flags and Proto.FLAG_KEY != 0
        stats.synced = flags and Proto.FLAG_SYNCED != 0
        if (waitingForKey && !isKey) { stats.dropped++; return }
        // pts carries the capture time (hub clock) so the output thread can measure latency.
        val ptsUs = if (stats.synced) captureHubNs / 1000 else System.nanoTime() / 1000
        val codecFlags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        if (feed(d, 9, d.size - 9, ptsUs, codecFlags)) {
            waitingForKey = false
        } else {
            // Decoder is behind: skip to the next keyframe instead of building up lag.
            stats.dropped++
            waitingForKey = true
            requestKeyFrame()
        }
    }

    private fun requestKeyFrame() {
        try { Proto.write(out, Proto.REQ_KEYFRAME, ByteArray(0)); out.flush() } catch (_: Exception) {}
    }

    private fun startDecoder(src: HubRenderer.Source) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val c = MediaCodec.createDecoderByType(mime)
        val fmt = MediaFormat.createVideoFormat(mime, width, height)
        val caps = c.codecInfo.getCapabilitiesForType(mime)
        if (Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
        c.configure(fmt, src.surface, null, 0)
        c.start()
        codec = c
        outThread = thread(name = "hub-dec-$name") { outputLoop(c) }
    }

    private fun feed(data: ByteArray, off: Int, len: Int, ptsUs: Long, flags: Int): Boolean {
        val c = codec ?: return false
        return try {
            val idx = c.dequeueInputBuffer(15_000)
            if (idx < 0) return false
            val buf = c.getInputBuffer(idx) ?: return false
            buf.clear()
            if (len > buf.capacity()) { c.queueInputBuffer(idx, 0, 0, ptsUs, 0); return false }
            buf.put(data, off, len)
            c.queueInputBuffer(idx, 0, len, ptsUs, flags)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun outputLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            val idx = try { c.dequeueOutputBuffer(info, 20_000) } catch (e: Exception) { break }
            if (idx < 0) continue
            if (stats.synced) {
                val lat = (System.nanoTime() - info.presentationTimeUs * 1000) / 1e6
                stats.latencyMs = if (stats.latencyMs.isNaN()) lat else stats.latencyMs * 0.9 + lat * 0.1
            }
            stats.decFrames++
            try { c.releaseOutputBuffer(idx, true) } catch (e: Exception) { break }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { socket.close() } catch (_: Exception) {}
        try { outThread?.join(300) } catch (_: Exception) {}
        codec?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        codec = null
        source?.let { renderer.removeSource(it) }
        source = null
        onClosed(this)
    }
}
