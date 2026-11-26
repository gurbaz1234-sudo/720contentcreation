package com.camstitch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Primary phone. Receives every sender's stream, decodes them on the GPU and
 * shows: raw feeds, a top-down stitched floor, or a 3D bowl you can orbit.
 */
class HubActivity : Activity() {

    private lateinit var cfg: Settings
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: HubRenderer
    private lateinit var overlay: TextView
    private lateinit var modeButton: Button
    private var server: HubServer? = null
    private var localSender: SenderSession? = null
    private var resumed = false
    private val ui = Handler(Looper.getMainLooper())
    private val log = ArrayDeque<String>()
    private var lastStatsNs = System.nanoTime()
    private val lastCounts = HashMap<RemoteStream, LongArray>()

    private val calibFile get() = File(getExternalFilesDir(null), "calibration.json")

    // recording + videos
    private lateinit var recButton: Button
    private lateinit var videosButton: Button
    private val lockedDuringRec = ArrayList<Button>()
    private lateinit var recProgress: android.view.View
    private lateinit var recLabel: TextView
    private lateinit var snack: LinearLayout
    private lateinit var snackText: TextView
    private lateinit var snackAction: Button
    private lateinit var videos: VideosPanel
    private var recorder: VideoRecorder? = null
    private var recStartMs = 0L
    private var recStopping = false
    private fun dp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Settings.fromIntent(intent)

        renderer = HubRenderer(this) { if (resumed) startPipelines() }
        renderer.onSnapshotSaved = { msg -> toast(msg) }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(RecordableConfigChooser())
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        renderer.view = glView

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x77000000); textSize = 11f; setPadding(12, 6, 12, 6)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0x55000000) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 11f; setOnClickListener { f() } }.also { bar.addView(it) }
        modeButton = btn("View: ${renderer.mode.label}") { cycleMode() }
        recButton = btn("● Rec") { if (recorder != null) stopRecording() else startRecording() }
        recButton.setTextColor(0xFFB3261E.toInt())
        lockedDuringRec += btn("Snap: lens calib") { renderer.requestSnapshot("intrinsics") }
        lockedDuringRec += btn("Snap: floor calib") { renderer.requestSnapshot("floor") }
        lockedDuringRec += btn("Load calibration") { pickCalibration() }
        btn("Lock/unlock hub cam") { localSender?.let { it.setLocked(!it.isLocked) } }
        videosButton = btn("Videos (0)") { videos.show() }
        lockedDuringRec += modeButton; lockedDuringRec += videosButton
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // recording indicator: red line across the top + "● REC 0:03 / 0:10"
        recProgress = android.view.View(this).apply { setBackgroundColor(0xFFFF2D2D.toInt()); visibility = android.view.View.GONE }
        root.addView(recProgress, FrameLayout.LayoutParams(0, dp(3f), Gravity.TOP or Gravity.START))
        recLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(10f), dp(5f), dp(10f), dp(5f)); visibility = android.view.View.GONE
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x8C000000.toInt()); cornerRadius = dp(14f).toFloat() }
        }
        root.addView(recLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8f), dp(10f), 0) })

        // snackbar: message + optional action
        snack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE
            setPadding(dp(16f), dp(4f), dp(8f), dp(4f))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEB282828.toInt()); cornerRadius = dp(20f).toFloat() }
        }
        snackText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        snackAction = Button(this).apply {
            setTextColor(0xFF8AB4F8.toInt()); textSize = 13f; minWidth = 0; minimumWidth = 0
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }
        snack.addView(snackText); snack.addView(snackAction)
        root.addView(snack, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(64f) })

        videos = VideosPanel(this) { n -> videosButton.text = "Videos ($n)" }
        root.addView(videos.root, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        videos.refresh()

        loadCalibration(quiet = true)
        setupTouch()
        ui.post(statsTick)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()   // onSurfaceCreated -> onGlReady -> startPipelines()
    }

    override fun onPause() {
        resumed = false
        if (recorder != null) stopRecording(blocking = true)
        stopPipelines()
        renderer.glReady = false
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(statsTick)
        super.onDestroy()
    }

    private fun startPipelines() {
        if (server != null) return
        val s = HubServer(this, renderer) { msg -> ui.post { addLog(msg) } }
        try { s.start() } catch (e: Exception) { addLog("cannot open port ${Proto.PORT}: ${e.message}"); return }
        server = s
        if (cfg.useLocalCamera) {
            try {
                localSender = SenderSession(this, cfg.name, cfg.camSpec, cfg.width, cfg.height, cfg.fps, hubHost = "127.0.0.1").also { it.start() }
            } catch (e: Exception) {
                addLog("hub camera failed: ${e.message}")
            }
        }
    }

    private fun stopPipelines() {
        localSender?.stop(); localSender = null
        server?.stop(); server = null
        lastCounts.clear()
    }

    // ---------------------------------------------------------------- UI

    // ---------------------------------------------------------------- recording (max 10 s, stitched views only)

    private fun startRecording() {
        if (renderer.mode == HubRenderer.Mode.GRID) {
            toast("Switch View to Top-down or 3D orbit to record the stitched video"); return
        }
        val (sw, sh) = renderer.screenSize
        val (w, h) = VideoRecorder.pickSize(sw, sh)
        val r = try { VideoRecorder(this, w, h, renderer.mode.label) } catch (e: Exception) {
            toast("Cannot start recording: ${e.message}"); return
        }
        recorder = r; recStopping = false
        setRecUi(true)
        renderer.attachRecorder(r) { ok ->
            if (!ok) { toast("Recording not supported on this device's GPU"); stopRecording() }
        }
        recStartMs = android.os.SystemClock.elapsedRealtime()
        addLog("recording started (${renderer.mode.label}, ${w}x$h)")
        ui.post(recTick)
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (recorder == null || recStopping) return
            val el = android.os.SystemClock.elapsedRealtime() - recStartMs
            val sec = (el / 1000).coerceAtMost(VideoRecorder.MAX_MS / 1000)
            recLabel.text = "● REC 0:%02d / 0:%02d".format(sec, VideoRecorder.MAX_MS / 1000)
            val lp = recProgress.layoutParams
            lp.width = (recProgress.rootView.width * (el.toFloat() / VideoRecorder.MAX_MS).coerceAtMost(1f)).toInt()
            recProgress.layoutParams = lp
            if (el >= VideoRecorder.MAX_MS) stopRecording() else ui.postDelayed(this, 100)
        }
    }

    private fun stopRecording(blocking: Boolean = false) {
        val r = recorder ?: return
        if (recStopping) return
        recStopping = true
        ui.removeCallbacks(recTick)
        setRecUi(false)
        val finish = {
            Thread {
                val ok = try { r.finish() } catch (e: Exception) { false }
                ui.post {
                    recorder = null; recStopping = false
                    if (ok) {
                        addLog("video saved: ${VideoRecorder.FOLDER}/${r.name}")
                        showSnack("Saved ${r.name} (%.1f s)".format(r.durationMs / 1000.0), "View") { videos.show() }
                    } else toast("Recording failed: no frames")
                    videos.refresh()
                }
            }.start()
        }
        if (blocking) {
            // Activity is pausing: the GL thread must let go of the recorder before it stops.
            val latch = java.util.concurrent.CountDownLatch(1)
            renderer.detachRecorder { }
            glView.queueEvent { latch.countDown() }
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            finish()
        } else {
            renderer.detachRecorder { finish() }
        }
    }

    private fun setRecUi(on: Boolean) {
        recButton.text = if (on) "■ Stop" else "● Rec"
        lockedDuringRec.forEach { it.isEnabled = !on; it.alpha = if (on) 0.4f else 1f }
        recLabel.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        recProgress.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        if (on) recLabel.text = "● REC 0:00 / 0:%02d".format(VideoRecorder.MAX_MS / 1000)
    }

    private val hideSnack = Runnable { snack.visibility = android.view.View.GONE }

    private fun showSnack(msg: String, action: String?, onAction: () -> Unit = {}) {
        snackText.text = msg
        snackAction.visibility = if (action == null) android.view.View.GONE else android.view.View.VISIBLE
        snackAction.text = action ?: ""
        snackAction.setOnClickListener { snack.visibility = android.view.View.GONE; onAction() }
        snack.visibility = android.view.View.VISIBLE
        ui.removeCallbacks(hideSnack); ui.postDelayed(hideSnack, 4000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!videos.onBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun cycleMode() {
        val next = HubRenderer.Mode.values()[(renderer.mode.ordinal + 1) % HubRenderer.Mode.values().size]
        if (next != HubRenderer.Mode.GRID && renderer.calibration == null) {
            toast("No calibration loaded yet: showing feeds only")
            renderer.mode = HubRenderer.Mode.GRID
        } else renderer.mode = next
        modeButton.text = "View: ${renderer.mode.label}"
    }

    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                renderer.zoom = (renderer.zoom * d.scaleFactor).coerceIn(0.3f, 6f); return true
            }
        })
        var lx = 0f; var ly = 0f
        glView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (e.pointerCount == 1 && !scale.isInProgress) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                    MotionEvent.ACTION_MOVE -> {
                        renderer.yawDeg -= (e.x - lx) * 0.25f
                        renderer.pitchDeg = (renderer.pitchDeg + (e.y - ly) * 0.2f).coerceIn(5f, 85f)
                        lx = e.x; ly = e.y
                    }
                }
            } else if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                val keep = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(keep); ly = e.getY(keep)
            }
            true
        }
    }

    private fun pickCalibration() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, 42)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (videos.onActivityResult(requestCode, resultCode)) return
        val uri = data?.data ?: return
        if (requestCode != 42 || resultCode != RESULT_OK) return
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Calibration.parse(text, uri.toString()) // validate before saving
            calibFile.writeText(text)
            loadCalibration(quiet = false)
        } catch (e: Exception) {
            toast("Invalid calibration: ${e.message}")
        }
    }

    private fun loadCalibration(quiet: Boolean) {
        val f = calibFile
        if (!f.exists()) { if (!quiet) toast("No calibration at ${f.absolutePath}"); return }
        try {
            val c = Calibration.load(f)
            renderer.calibration = c
            addLog("calibration loaded: ${c.cameras.keys.joinToString()}")
            if (!quiet) toast("Calibration loaded (${c.cameras.size} cameras)")
        } catch (e: Exception) {
            addLog("calibration error: ${e.message}")
        }
    }

    private fun addLog(msg: String) {
        log.addLast(msg)
        while (log.size > 4) log.removeFirst()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private val statsTick = object : Runnable {
        override fun run() {
            updateOverlay()
            ui.postDelayed(this, 500)
        }
    }

    private fun updateOverlay() {
        val now = System.nanoTime()
        val dt = (now - lastStatsNs) / 1e9
        lastStatsNs = now
        val sb = StringBuilder()
        sb.append("HUB  ${localIps().joinToString("  ")}  port ${Proto.PORT}   view: ${renderer.mode.label}\n")
        val streams = server?.streams?.toList() ?: emptyList()
        if (streams.isEmpty()) sb.append("Waiting for senders...\n")
        for (s in streams) {
            val st = s.stats
            val prev = lastCounts.getOrPut(s) { longArrayOf(st.rxFrames, st.decFrames) }
            val rx = (st.rxFrames - prev[0]) / dt
            val dec = (st.decFrames - prev[1]) / dt
            prev[0] = st.rxFrames; prev[1] = st.decFrames
            st.kbps = st.bytesWindow * 8 / 1000.0 / dt; st.bytesWindow = 0
            val lat = if (st.synced && !st.latencyMs.isNaN()) "${st.latencyMs.toInt()} ms" else "--"
            sb.append("${s.name} [${s.remoteAddress}] ${s.width}x${s.height}  rx ${"%.0f".format(rx)} fps  dec ${"%.0f".format(dec)} fps  ")
            sb.append("${"%.1f".format(st.kbps / 1000)} Mbps  latency $lat  rtt ${"%.0f".format(st.senderRttMs)} ms")
            sb.append(if (st.senderLocked) "  LOCKED" else "  auto-exp").append("  drops ${st.dropped}\n")
        }
        if (renderer.mode == HubRenderer.Mode.GRID && renderer.sources.isNotEmpty()) {
            sb.append("Grid order: ${renderer.sources.joinToString(", ") { it.name }}\n")
        }
        if (renderer.calibration == null) sb.append("No calibration: put calibration.json in ${calibFile.parent} or use Load\n")
        if (renderer.info.isNotEmpty()) sb.append(renderer.info).append('\n')
        log.forEach { sb.append("· ").append(it).append('\n') }
        overlay.text = sb.toString().trimEnd()
        lastCounts.keys.retainAll(streams.toSet())
    }

    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${ni.name}:${it.hostAddress}" } }
    } catch (e: Exception) { emptyList() }
}

/** Picks an EGL config the video encoder can draw into (EGL_RECORDABLE_ANDROID), with a fallback. */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10, display: javax.microedition.khronos.egl.EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
        val E = javax.microedition.khronos.egl.EGL10.EGL_NONE
        fun pick(recordable: Boolean): javax.microedition.khronos.egl.EGLConfig? {
            val a = arrayListOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16, javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4, // ES2
            )
            if (recordable) { a += 0x3142; a += 1 }   // EGL_RECORDABLE_ANDROID
            a += E
            val cfgs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val n = IntArray(1)
            return if (egl.eglChooseConfig(display, a.toIntArray(), cfgs, 1, n) && n[0] > 0) cfgs[0] else null
        }
        return pick(true) ?: pick(false) ?: throw IllegalStateException("no suitable EGL config")
    }
}
