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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Settings.fromIntent(intent)

        renderer = HubRenderer(this) { if (resumed) startPipelines() }
        renderer.onSnapshotSaved = { msg -> toast(msg) }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
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
        btn("Snap: lens calib") { renderer.requestSnapshot("intrinsics") }
        btn("Snap: floor calib") { renderer.requestSnapshot("floor") }
        btn("Load calibration") { pickCalibration() }
        btn("Lock/unlock hub cam") { localSender?.let { it.setLocked(!it.isLocked) } }
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)

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
