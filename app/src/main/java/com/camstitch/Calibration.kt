package com.camstitch

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * calibration.json written by tools/calibrate.py. World frame: metres, Z up,
 * floor at Z = 0. Camera convention is OpenCV's (x right, y down, z forward):
 *     p_cam = R * p_world + t
 */
class CameraCalib(
    val name: String,
    val fisheye: Boolean,
    val width: Int, val height: Int,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val dist: FloatArray,       // pinhole: k1 k2 p1 p2 k3 ; fisheye: k1 k2 k3 k4
    val r: FloatArray,          // 3x3 row-major
    val t: FloatArray,          // 3
    val maxR: Float,            // validity limit: pinhole = max normalized radius, fisheye = max theta (rad)
    val gain: FloatArray,       // per-channel colour gain (1,1,1 default)
)

class Calibration(
    val cameras: Map<String, CameraCalib>,
    val centerX: Float, val centerY: Float,
    val floorRadius: Float, val outerRadius: Float, val wallHeight: Float,
    val featherPx: Float,
    val source: String,
) {
    companion object {
        fun load(file: File): Calibration = parse(file.readText(), file.absolutePath)

        fun parse(text: String, source: String): Calibration {
            val j = JSONObject(text)
            val cams = LinkedHashMap<String, CameraCalib>()
            val cj = j.getJSONObject("cameras")
            for (name in cj.keys()) {
                val c = cj.getJSONObject(name)
                val k = c.getJSONArray("K")
                val d = c.getJSONArray("dist")
                val r = c.getJSONArray("R")
                val t = c.getJSONArray("t")
                val g = c.optJSONArray("gain")
                cams[name] = CameraCalib(
                    name = name,
                    fisheye = c.optString("model", "pinhole") == "fisheye",
                    width = c.getInt("width"), height = c.getInt("height"),
                    fx = k.getDouble(0).toFloat(), fy = k.getDouble(1).toFloat(),
                    cx = k.getDouble(2).toFloat(), cy = k.getDouble(3).toFloat(),
                    dist = FloatArray(5) { if (it < d.length()) d.getDouble(it).toFloat() else 0f },
                    r = FloatArray(9) { r.getDouble(it).toFloat() },
                    t = FloatArray(3) { t.getDouble(it).toFloat() },
                    maxR = c.optDouble("max_r", 10.0).toFloat(),
                    gain = FloatArray(3) { if (g != null && it < g.length()) g.getDouble(it).toFloat() else 1f },
                )
            }
            val b = j.optJSONObject("bowl") ?: JSONObject()
            val ctr = b.optJSONArray("center")
            val floorR = b.optDouble("floor_radius", 1.0).toFloat()
            return Calibration(
                cams,
                ctr?.optDouble(0, 0.0)?.toFloat() ?: 0f, ctr?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                floorR, b.optDouble("outer_radius", floorR * 2.0).toFloat(), b.optDouble("height", floorR.toDouble()).toFloat(),
                j.optJSONObject("view")?.optDouble("feather_px", 60.0)?.toFloat() ?: 60f,
                source,
            )
        }
    }
}

/** Bowl surface: flat floor disk out to floorRadius, then walls curving up quadratically. */
class BowlMesh(cal: Calibration, rings: Int = 72, segments: Int = 144) {
    val vertices: FloatBuffer
    val indices: ShortBuffer
    val indexCount: Int

    init {
        val verts = FloatArray((rings + 1) * (segments + 1) * 3)
        var p = 0
        for (i in 0..rings) {
            val r = cal.outerRadius * i / rings
            val z = if (r <= cal.floorRadius) 0f else {
                val u = (r - cal.floorRadius) / (cal.outerRadius - cal.floorRadius)
                cal.wallHeight * u * u
            }
            for (s in 0..segments) {
                val a = (2.0 * Math.PI * s / segments)
                verts[p++] = cal.centerX + (r * cos(a)).toFloat()
                verts[p++] = cal.centerY + (r * sin(a)).toFloat()
                verts[p++] = z
            }
        }
        val idx = ShortArray(rings * segments * 6)
        var q = 0
        for (i in 0 until rings) for (s in 0 until segments) {
            val a = i * (segments + 1) + s
            val b = a + segments + 1
            idx[q++] = a.toShort(); idx[q++] = b.toShort(); idx[q++] = (a + 1).toShort()
            idx[q++] = (a + 1).toShort(); idx[q++] = b.toShort(); idx[q++] = (b + 1).toShort()
        }
        indexCount = idx.size
        vertices = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        indices = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }
    }
}
