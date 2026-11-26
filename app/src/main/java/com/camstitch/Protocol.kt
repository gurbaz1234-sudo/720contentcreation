package com.camstitch

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Wire protocol between Sender phones and the Hub (TCP, big-endian).
 *
 * Every message: [u8 type][i32 length][payload]
 *
 *  Sender -> Hub
 *   HELLO      JSON {"name","width","height","fps","model","proto"}
 *   CONFIG     H.264 codec config (SPS/PPS, Annex-B)
 *   FRAME      [i64 captureTimeHubNs][u8 flags][H.264 access unit, Annex-B]
 *   TIME_REQ   [i64 t0 sender nanoTime]
 *   STATS      JSON {"rtt_ms","enc_fps","locked","synced"}
 *
 *  Hub -> Sender
 *   TIME_RESP     [i64 t0][i64 t1 hub nanoTime]
 *   REQ_KEYFRAME  (empty)
 *
 * captureTimeHubNs is the camera sensor timestamp converted into the hub's
 * System.nanoTime() clock using NTP-style offset estimation, so the hub can
 * measure true capture->decode latency and (later) align frames across phones.
 */
object Proto {
    const val PORT = 47800
    const val SERVICE_TYPE = "_camstitch._tcp."
    const val SERVICE_NAME = "CamStitchHub"
    const val VERSION = 1

    const val HELLO = 1
    const val CONFIG = 2
    const val FRAME = 3
    const val TIME_REQ = 4
    const val TIME_RESP = 5
    const val REQ_KEYFRAME = 6
    const val STATS = 7

    const val FLAG_KEY = 1
    const val FLAG_SYNCED = 2
    const val FLAG_APPROX_TS = 4

    private const val MAX_PAYLOAD = 8 * 1024 * 1024

    class Msg(val type: Int, val data: ByteArray)

    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeByte(type)
        out.writeInt(payload.size)
        out.write(payload)
    }

    fun read(inp: DataInputStream): Msg {
        val type = inp.readUnsignedByte()
        val len = inp.readInt()
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad payload length $len")
        val d = ByteArray(len)
        inp.readFully(d)
        return Msg(type, d)
    }

    fun longs(vararg v: Long): ByteArray {
        val b = ByteBuffer.allocate(8 * v.size)
        v.forEach { b.putLong(it) }
        return b.array()
    }

    fun frame(captureHubNs: Long, flags: Int, au: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(9 + au.size)
        b.putLong(captureHubNs)
        b.put(flags.toByte())
        b.put(au)
        return b.array()
    }
}
