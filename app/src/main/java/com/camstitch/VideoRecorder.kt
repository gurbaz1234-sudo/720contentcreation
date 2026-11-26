package com.camstitch

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Records the stitched view to an MP4 in Movies/CamStitch (no storage
 * permission needed on Android 10+). The renderer draws each frame a second
 * time into [inputSurface]; this class encodes and muxes it.
 */
class VideoRecorder(ctx: Context, val width: Int, val height: Int, val viewLabel: String) {

    companion object {
        const val MAX_MS = 10_000L
        const val FOLDER = "Movies/CamStitch"
        private const val FPS = 30

        /** Largest even size <= 1920 wide with the screen's aspect that the encoder accepts. */
        fun pickSize(screenW: Int, screenH: Int): Pair<Int, Int> {
            val caps = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).let { c ->
                    val v = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    c.release(); v
                }
            } catch (e: Exception) { null }
            for (w in intArrayOf(1920, 1600, 1280, 960)) {
                val ww = minOf(w, screenW) / 16 * 16
                val hh = (ww.toLong() * screenH / screenW).toInt() / 16 * 16
                if (ww >= 320 && hh >= 160 && (caps == null || caps.isSizeSupported(ww, hh))) return ww to hh
            }
            return 1280 to 720
        }
    }

    val name: String = "CamStitch_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
    val uri: Uri
    val inputSurface: Surface
    private val resolver = ctx.contentResolver
    private val pfd: ParcelFileDescriptor
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var drainThread: Thread? = null
    @Volatile private var firstPtsUs = -1L
    @Volatile private var lastPtsUs = -1L
    @Volatile var framesIn = 0
        private set

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("cannot create video in $FOLDER")
        pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("cannot open $uri")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * FPS * 0.15).toInt().coerceIn(4_000_000, 16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        drainThread = thread(name = "rec-drain") { drain() }
    }

    /** Called by the GL thread before drawing a frame. Returns the pts to use, or -1 to skip this frame. */
    fun nextFramePtsNs(nowNs: Long): Long {
        val nowUs = nowNs / 1000
        if (firstPtsUs < 0) firstPtsUs = nowUs
        val t = nowUs - firstPtsUs
        if (t > MAX_MS * 1000) return -1
        if (lastPtsUs >= 0 && t - lastPtsUs < 1_000_000 / FPS - 2_000) return -1   // ~30 fps
        lastPtsUs = t
        framesIn++
        return t * 1000
    }

    val durationMs: Long get() = if (lastPtsUs < 0) 0 else lastPtsUs / 1000 + 1000 / FPS

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var started = false
        while (true) {
            val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); started = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && started && !isConfig && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        try { if (started) muxer.stop() } catch (_: Exception) {}
    }

    /** Ends the recording. Call after the GL thread stopped drawing into inputSurface. Blocking. */
    fun finish(): Boolean {
        try { codec.signalEndOfInputStream() } catch (_: Exception) {}
        drainThread?.join(5000)
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        inputSurface.release()
        try { muxer.release() } catch (_: Exception) {}
        pfd.close()
        return if (framesIn > 0) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                // remembered for the videos screen: "<view>|<seconds>"
                put(MediaStore.Video.Media.DESCRIPTION, "$viewLabel|${"%.1f".format(Locale.US, durationMs / 1000.0)}")
            }
            resolver.update(uri, v, null, null)
            true
        } else {
            resolver.delete(uri, null, null)
            false
        }
    }
}
