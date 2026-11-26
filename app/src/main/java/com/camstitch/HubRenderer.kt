package com.camstitch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GL side of the hub. Each incoming stream decodes into its own
 * SurfaceTexture (external OES texture). Draws either a grid of raw feeds,
 * a top-down stitched floor, or an orbitable 3D bowl.
 */
class HubRenderer(private val ctx: Context, private val onGlReady: () -> Unit) : GLSurfaceView.Renderer {

    enum class Mode(val label: String) { GRID("Feeds"), TOP("Top-down"), ORBIT("3D orbit") }

    class Source(val name: String, val width: Int, val height: Int, val generation: Int) {
        var texId = 0
        lateinit var st: SurfaceTexture
        lateinit var surface: Surface
        val texM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        @Volatile var frameAvailable = false
        @Volatile var hasFrame = false
    }

    private class CamUniforms(
        val tex: Int, val r: Int, val t: Int, val k: Int, val d: Int, val d5: Int,
        val size: Int, val fish: Int, val maxR: Int, val texM: Int, val gain: Int,
    )

    lateinit var view: GLSurfaceView
    val sources = CopyOnWriteArrayList<Source>()

    @Volatile var mode = Mode.GRID
    @Volatile var calibration: Calibration? = null
    @Volatile var yawDeg = -90f
    @Volatile var pitchDeg = 35f
    @Volatile var zoom = 1f
    @Volatile var glReady = false
    @Volatile var info: String = ""            // shown in overlay (warnings)
    var onSnapshotSaved: ((String) -> Unit)? = null

    @Volatile private var pendingSnapshot: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var viewW = 1
    private var viewH = 1

    private var tileProg = 0
    private var tileAPos = 0; private var tileAUv = 0; private var tileUTexM = 0; private var tileUFlip = 0; private var tileUTex = 0
    private val quad: FloatBuffer = floatBuf(floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    ))

    private var stitchKey = ""
    private var stitchProg = 0
    private var stitchCams: List<Pair<Source, CameraCalib>> = emptyList()
    private val camUniforms = ArrayList<CamUniforms>()
    private var uMvp = 0; private var aWorld = 0; private var uFeather = 0
    private var bowlFor: Calibration? = null
    private var bowlVbo = 0; private var bowlIbo = 0; private var bowlCount = 0
    private val proj = FloatArray(16); private val viewM = FloatArray(16); private val mvp = FloatArray(16)

    // ------------------------------------------------------------ sources

    /** Called from network threads. Blocks until the GL thread has made the texture. */
    fun createSource(name: String, w: Int, h: Int): Source? {
        val latch = CountDownLatch(1)
        var result: Source? = null
        view.queueEvent {
            try {
                if (glReady) {
                    val s = Source(name, w, h, generation)
                    s.texId = Gl.newExternalTexture()
                    s.st = SurfaceTexture(s.texId).apply { setDefaultBufferSize(w, h) }
                    s.st.setOnFrameAvailableListener({ s.frameAvailable = true }, main)
                    s.surface = Surface(s.st)
                    sources.add(s)
                    result = s
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    fun removeSource(s: Source) {
        view.queueEvent {
            if (sources.remove(s)) {
                try { s.surface.release(); s.st.release() } catch (_: Exception) {}
                if (s.generation == generation) GLES20.glDeleteTextures(1, intArrayOf(s.texId), 0)
            }
        }
    }

    fun requestSnapshot(kind: String) { pendingSnapshot = kind }

    // ------------------------------------------------------------ GL callbacks

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        generation++
        for (s in sources) try { s.surface.release(); s.st.release() } catch (_: Exception) {}
        sources.clear()
        stitchKey = ""; stitchProg = 0; bowlFor = null
        tileProg = Gl.program(Shaders.TILE_VS, Shaders.TILE_FS)
        tileAPos = GLES20.glGetAttribLocation(tileProg, "aPos")
        tileAUv = GLES20.glGetAttribLocation(tileProg, "aUv")
        tileUTexM = GLES20.glGetUniformLocation(tileProg, "uTexM")
        tileUFlip = GLES20.glGetUniformLocation(tileProg, "uFlip")
        tileUTex = GLES20.glGetUniformLocation(tileProg, "uTex")
        glReady = true
        main.post { onGlReady() }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { viewW = w; viewH = h }

    override fun onDrawFrame(gl: GL10?) {
        for (s in sources) {
            if (s.frameAvailable) {
                s.frameAvailable = false
                try {
                    s.st.updateTexImage()
                    s.st.getTransformMatrix(s.texM)
                    s.hasFrame = true
                } catch (_: Exception) {}
            }
        }
        pendingSnapshot?.let { pendingSnapshot = null; snapshot(it) }

        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0.05f, 0.05f, 0.06f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        when (mode) {
            Mode.GRID -> drawGrid()
            Mode.TOP, Mode.ORBIT -> drawStitch()
        }
    }

    // ------------------------------------------------------------ feed grid

    private fun drawGrid() {
        val list = sources.toList()
        val n = list.size
        if (n == 0) return
        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n / cols.toDouble()).toInt()
        val tw = viewW / cols
        val th = viewH / rows
        list.forEachIndexed { i, s ->
            val col = i % cols; val row = i / cols
            val x0 = col * tw; val y0 = viewH - (row + 1) * th
            val a = s.width.toFloat() / s.height
            val (w, h) = if (tw.toFloat() / th > a) ((th * a).toInt() to th) else (tw to (tw / a).toInt())
            GLES20.glViewport(x0 + (tw - w) / 2, y0 + (th - h) / 2, w, h)
            drawTex(s, 1f)
        }
        GLES20.glViewport(0, 0, viewW, viewH)
    }

    private fun drawTex(s: Source, flip: Float) {
        if (!s.hasFrame) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(tileProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
        GLES20.glUniform1i(tileUTex, 0)
        GLES20.glUniformMatrix4fv(tileUTexM, 1, false, s.texM, 0)
        GLES20.glUniform1f(tileUFlip, flip)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(tileAPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tileAPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tileAUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tileAUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(tileAPos)
        GLES20.glDisableVertexAttribArray(tileAUv)
    }

    // ------------------------------------------------------------ stitching

    private fun ensureBowl(cal: Calibration) {
        if (bowlFor === cal) return
        if (bowlVbo != 0) GLES20.glDeleteBuffers(2, intArrayOf(bowlVbo, bowlIbo), 0)
        val mesh = BowlMesh(cal)
        val b = IntArray(2)
        GLES20.glGenBuffers(2, b, 0)
        bowlVbo = b[0]; bowlIbo = b[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bowlVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertices.capacity() * 4, mesh.vertices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bowlIbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.capacity() * 2, mesh.indices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        bowlCount = mesh.indexCount
        bowlFor = cal
    }

    private fun ensureStitchProgram(cal: Calibration): Boolean {
        val cams = sources.mapNotNull { s -> cal.cameras[s.name]?.let { s to it } }
        val key = "${System.identityHashCode(cal)}:" + cams.joinToString(",") { "${it.first.name}#${it.first.texId}" }
        if (key == stitchKey) return stitchProg != 0
        stitchKey = key
        if (stitchProg != 0) { GLES20.glDeleteProgram(stitchProg); stitchProg = 0 }
        camUniforms.clear()
        stitchCams = cams
        val missing = sources.filter { !cal.cameras.containsKey(it.name) }.map { it.name }
        info = if (missing.isNotEmpty()) "Not in calibration (not stitched): ${missing.joinToString()}" else ""
        if (cams.isEmpty()) { info = "No connected camera matches the calibration names"; return false }
        for ((s, c) in cams) {
            if (abs(s.width.toFloat() / s.height - c.width.toFloat() / c.height) > 0.01f) {
                info += "\n${s.name}: stream ${s.width}x${s.height} vs calibration ${c.width}x${c.height} (aspect differs, recalibrate!)"
            }
        }
        return try {
            val p = Gl.program(Shaders.STITCH_VS, Shaders.stitchFs(cams.size))
            uMvp = GLES20.glGetUniformLocation(p, "uMVP")
            aWorld = GLES20.glGetAttribLocation(p, "aWorld")
            uFeather = GLES20.glGetUniformLocation(p, "uFeather")
            for (i in cams.indices) {
                fun u(n: String) = GLES20.glGetUniformLocation(p, n)
                camUniforms += CamUniforms(
                    u("uTex$i"), u("uR$i"), u("uT$i"), u("uK$i"), u("uD$i"), u("uD5_$i"),
                    u("uSize$i"), u("uFish$i"), u("uMaxR$i"), u("uTexM$i"), u("uGain$i"),
                )
            }
            stitchProg = p
            true
        } catch (e: Exception) {
            info = "Stitch shader error: ${e.message}"
            false
        }
    }

    private fun drawStitch() {
        val cal = calibration ?: return
        ensureBowl(cal)
        if (!ensureStitchProgram(cal)) return

        val aspect = viewW.toFloat() / viewH
        if (mode == Mode.TOP) {
            val half = cal.floorRadius * 1.15f / zoom
            Matrix.orthoM(proj, 0, -half * aspect, half * aspect, -half, half, 0.1f, 100f)
            Matrix.setLookAtM(viewM, 0, cal.centerX, cal.centerY, 20f, cal.centerX, cal.centerY, 0f, 0f, 1f, 0f)
        } else {
            val dist = (cal.floorRadius * 1.3f / zoom).coerceAtMost(cal.outerRadius * 0.95f)
            val p = Math.toRadians(pitchDeg.toDouble()); val y = Math.toRadians(yawDeg.toDouble())
            val ex = cal.centerX + (dist * cos(p) * cos(y)).toFloat()
            val ey = cal.centerY + (dist * cos(p) * sin(y)).toFloat()
            val ez = (dist * sin(p)).toFloat()
            Matrix.perspectiveM(proj, 0, 60f, aspect, 0.01f, 100f)
            Matrix.setLookAtM(viewM, 0, ex, ey, ez, cal.centerX, cal.centerY, 0f, 0f, 0f, 1f)
        }
        Matrix.multiplyMM(mvp, 0, proj, 0, viewM, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glUseProgram(stitchProg)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(uFeather, cal.featherPx)

        stitchCams.forEachIndexed { i, (s, c) ->
            val u = camUniforms[i]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId)
            GLES20.glUniform1i(u.tex, i)
            // GL wants column-major; calibration R is row-major -> transpose.
            val rc = floatArrayOf(c.r[0], c.r[3], c.r[6], c.r[1], c.r[4], c.r[7], c.r[2], c.r[5], c.r[8])
            GLES20.glUniformMatrix3fv(u.r, 1, false, rc, 0)
            GLES20.glUniform3f(u.t, c.t[0], c.t[1], c.t[2])
            val sx = s.width.toFloat() / c.width; val sy = s.height.toFloat() / c.height
            GLES20.glUniform4f(u.k, c.fx * sx, c.fy * sy, c.cx * sx, c.cy * sy)
            if (c.fisheye) {
                GLES20.glUniform4f(u.d, c.dist[0], c.dist[1], c.dist[2], c.dist[3]); GLES20.glUniform1f(u.d5, 0f)
            } else {
                GLES20.glUniform4f(u.d, c.dist[0], c.dist[1], c.dist[2], c.dist[3]); GLES20.glUniform1f(u.d5, c.dist[4])
            }
            GLES20.glUniform2f(u.size, s.width.toFloat(), s.height.toFloat())
            GLES20.glUniform1f(u.fish, if (c.fisheye) 1f else 0f)
            GLES20.glUniform1f(u.maxR, c.maxR)
            GLES20.glUniformMatrix4fv(u.texM, 1, false, s.texM, 0)
            GLES20.glUniform3f(u.gain, c.gain[0], c.gain[1], c.gain[2])
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bowlVbo)
        GLES20.glVertexAttribPointer(aWorld, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glEnableVertexAttribArray(aWorld)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bowlIbo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, bowlCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(aWorld)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    // ------------------------------------------------------------ snapshots

    /**
     * Saves the current decoded frame of every feed as PNG (exactly the pixels
     * the stitcher sees) under Android/data/com.camstitch/files/snapshots/<kind>/<camera>/.
     */
    private fun snapshot(kind: String) {
        val stamp = System.currentTimeMillis()
        var saved = 0
        for (s in sources) {
            if (!s.hasFrame) continue
            val fb = IntArray(1); val tex = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, s.width, s.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
            GLES20.glViewport(0, 0, s.width, s.height)
            drawTex(s, -1f) // flipped, so glReadPixels' bottom-up rows come out top-down
            val buf = ByteBuffer.allocateDirect(s.width * s.height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, s.width, s.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fb, 0)
            GLES20.glDeleteTextures(1, tex, 0)
            val name = s.name; val w = s.width; val h = s.height
            saved++
            saver.execute {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buf.rewind(); bmp.copyPixelsFromBuffer(buf)
                val dir = File(ctx.getExternalFilesDir(null), "snapshots/$kind/$name").apply { mkdirs() }
                FileOutputStream(File(dir, "${kind}_$stamp.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
        GLES20.glViewport(0, 0, viewW, viewH)
        val msg = "Saved $saved $kind snapshot(s)"
        saver.execute { main.post { onSnapshotSaved?.invoke(msg) } }
    }

    companion object {
        fun floatBuf(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    }
}
