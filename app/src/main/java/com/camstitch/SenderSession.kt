package com.camstitch

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Surface
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Camera -> H.264 encoder -> TCP to the hub. Used by SenderActivity, and by
 * the hub itself (connecting to 127.0.0.1) so the hub's own camera goes
 * through exactly the same pipeline as every other phone.
 */
class SenderSession(
    private val ctx: Context,
    val name: String,
    private val spec: CamSpec,
    reqWidth: Int,
    reqHeight: Int,
    private val fps: Int,
    private val hubHost: String?,          // null or blank = discover via NSD
    private val hubPort: Int = Proto.PORT,
    private val preview: SurfaceTexture? = null,
    private val listener: (Status) -> Unit = {},
) {
    data class Status(
        val connected: Boolean, val hub: String, val rttMs: Double, val synced: Boolean,
        val encFps: Double, val locked: Boolean, val size: Size, val message: String,
    )

    private class Packet(val type: Int, val payload: ByteArray)

    val size: Size
    private val bitrate: Int
    private val encoder: MediaCodec
    private val inputSurface: Surface
    private var previewSurface: Surface? = null
    private val camera: CameraController

    @Volatile private var running = true
    @Volatile private var connected = false
    @Volatile private var needKey = true
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var hubLabel = "searching..."
    @Volatile private var message = ""
    @Volatile private var socket: Socket? = null

    // clock sync: hubTime = senderTime + offsetNs
    @Volatile private var offsetNs = 0L
    @Volatile private var synced = false
    @Volatile private var rttMs = 0.0
    private val samples = ArrayDeque<Pair<Long, Long>>() // (rtt, offset)

    private val queue = LinkedBlockingDeque<Packet>(60)
    private var encFrames = 0
    private var encFps = 0.0
    private var fpsWindowStart = System.nanoTime()
    private val multicastLock: WifiManager.MulticastLock

    init {
        val ch = CameraUtil.characteristicsFor(ctx, spec)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        size = CameraUtil.chooseSize(map, MediaCodec::class.java, reqWidth, reqHeight)
        bitrate = (size.width * size.height * fps * 0.11).toInt().coerceIn(1_500_000, 12_000_000)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        val surfaces = ArrayList<Surface>()
        surfaces += inputSurface
        preview?.let {
            it.setDefaultBufferSize(size.width, size.height)
            val s = Surface(it)
            previewSurface = s
            surfaces += s
        }
        camera = CameraController(ctx, spec, fps, surfaces) { message = it; report() }

        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("camstitch-sender").apply { setReferenceCounted(false) }
    }

    fun start() {
        multicastLock.acquire()
        camera.start()
        thread(name = "enc-out") { encoderLoop() }
        thread(name = "net") { networkLoop() }
        thread(name = "timesync") {
            while (running) {
                if (connected) queue.offerFirst(Packet(Proto.TIME_REQ, ByteArray(0)))
                Thread.sleep(500)
                report()
            }
        }
    }

    fun setLocked(on: Boolean) = camera.setLocked(on)
    val isLocked get() = camera.locked

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        camera.stop()
        thread {
            Thread.sleep(300)
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            inputSurface.release()
            previewSurface?.release()
        }
        if (multicastLock.isHeld) multicastLock.release()
    }

    private fun report() {
        listener(Status(connected, hubLabel, rttMs, synced, encFps, camera.locked, size, message))
    }

    private fun requestKeyFrame() {
        try {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- encoder

    private fun encoderLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { encoder.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (idx < 0) continue
            val buf = try { encoder.getOutputBuffer(idx) } catch (e: Exception) { null }
            if (buf != null && info.size > 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val data = ByteArray(info.size); buf.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codecConfig = data
                    if (connected) queue.offerLast(Packet(Proto.CONFIG, data))
                } else {
                    onEncodedFrame(data, info)
                }
            }
            try { encoder.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
        }
    }

    private fun onEncodedFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        encFrames++
        val now = System.nanoTime()
        if (now - fpsWindowStart > 1_000_000_000L) {
            encFps = encFrames * 1e9 / (now - fpsWindowStart); encFrames = 0; fpsWindowStart = now
        }
        if (!connected) { needKey = true; return }
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (needKey && !isKey) return
        needKey = false

        // Encoder pts carries the camera sensor timestamp (us). Convert to hub clock.
        var flags = if (isKey) Proto.FLAG_KEY else 0
        var mono = CameraController.toMonotonicNs(info.presentationTimeUs * 1000, camera.timestampIsRealtime)
        if (kotlin.math.abs(now - mono) > 2_000_000_000L) { mono = now; flags = flags or Proto.FLAG_APPROX_TS }
        val hubNs = if (synced) { flags = flags or Proto.FLAG_SYNCED; mono + offsetNs } else 0L

        if (!queue.offerLast(Packet(Proto.FRAME, Proto.frame(hubNs, flags, data)))) {
            // Network can't keep up: drop backlog, resume at next keyframe.
            queue.clear(); needKey = true; requestKeyFrame()
        }
    }

    // ---------------------------------------------------------------- network

    private fun networkLoop() {
        while (running) {
            val addr = resolveHub()
            if (addr == null) { Thread.sleep(1000); continue }
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 512 * 1024
                s.connect(addr, 3000)
                socket = s
                hubLabel = "${addr.address.hostAddress}:${addr.port}"
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))

                val hello = JSONObject()
                    .put("name", name).put("width", size.width).put("height", size.height)
                    .put("fps", fps).put("model", Build.MODEL).put("proto", Proto.VERSION)
                Proto.write(out, Proto.HELLO, hello.toString().toByteArray())
                codecConfig?.let { Proto.write(out, Proto.CONFIG, it) }
                out.flush()
                queue.clear(); needKey = true
                synchronized(samples) { samples.clear() }
                synced = false
                connected = true
                requestKeyFrame()
                message = "connected to hub"
                report()

                thread(name = "net-rx") { readerLoop(inp) }
                var lastStats = 0L
                while (running && !s.isClosed) {
                    val p = queue.pollFirst(200, TimeUnit.MILLISECONDS)
                    if (p != null) {
                        if (p.type == Proto.TIME_REQ) Proto.write(out, Proto.TIME_REQ, Proto.longs(System.nanoTime()))
                        else Proto.write(out, p.type, p.payload)
                        out.flush()
                    }
                    val now = System.nanoTime()
                    if (now - lastStats > 1_000_000_000L) {
                        lastStats = now
                        val st = JSONObject().put("rtt_ms", rttMs).put("enc_fps", encFps)
                            .put("locked", camera.locked).put("synced", synced)
                        Proto.write(out, Proto.STATS, st.toString().toByteArray()); out.flush()
                    }
                }
            } catch (e: Exception) {
                message = "hub connection lost: ${e.message}"
            } finally {
                connected = false
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                report()
            }
            if (running) Thread.sleep(1000)
        }
    }

    private fun readerLoop(inp: DataInputStream) {
        try {
            while (running) {
                val m = Proto.read(inp)
                when (m.type) {
                    Proto.TIME_RESP -> {
                        val t3 = System.nanoTime()
                        val b = ByteBuffer.wrap(m.data)
                        val t0 = b.long; val t1 = b.long
                        val rtt = t3 - t0
                        val off = t1 - (t0 + t3) / 2
                        synchronized(samples) {
                            samples.addLast(rtt to off)
                            while (samples.size > 16) samples.removeFirst()
                            val best = samples.minByOrNull { it.first }!!
                            offsetNs = best.second
                            rttMs = best.first / 1e6
                        }
                        synced = true
                    }
                    Proto.REQ_KEYFRAME -> { needKey = true; requestKeyFrame() }
                }
            }
        } catch (_: Exception) {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun resolveHub(): InetSocketAddress? {
        if (!hubHost.isNullOrBlank()) {
            hubLabel = "$hubHost:$hubPort"
            return InetSocketAddress(hubHost, hubPort)
        }
        hubLabel = "searching (mDNS)..."
        report()
        return discoverHub(ctx, 4000)
    }

    companion object {
        /** Find the hub on the local network via mDNS/NSD. Blocking. */
        @Suppress("DEPRECATION")
        fun discoverHub(ctx: Context, timeoutMs: Long): InetSocketAddress? {
            val nsd = ctx.getSystemService(NsdManager::class.java)
            val latch = CountDownLatch(1)
            var result: InetSocketAddress? = null
            var resolving = false
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) { latch.countDown() }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!info.serviceName.startsWith(Proto.SERVICE_NAME) || resolving) return
                    resolving = true
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) { resolving = false }
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            val host = i.host
                            if (host != null) result = InetSocketAddress(host, i.port)
                            latch.countDown()
                        }
                    })
                }
            }
            return try {
                nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                result
            } catch (e: Exception) {
                null
            } finally {
                try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) {}
            }
        }
    }
}
