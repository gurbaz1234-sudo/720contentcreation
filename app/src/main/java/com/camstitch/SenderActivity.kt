package com.camstitch

import android.app.Activity
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Sender mode: shows a preview for aiming the phone and streams to the hub. */
class SenderActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView
    private lateinit var lockButton: Button
    private var session: SenderSession? = null
    private lateinit var cfg: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Settings.fromIntent(intent)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); setPadding(16, 8, 16, 8); textSize = 13f
        }
        root.addView(statusView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lockButton = Button(this).apply {
            text = "Unlock exposure"
            setOnClickListener { session?.let { s -> s.setLocked(!s.isLocked) } }
        }
        bar.addView(lockButton)
        bar.addView(Button(this).apply { text = "Stop"; setOnClickListener { finish() } })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        setContentView(root)
        textureView.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val s = SenderSession(
            this, cfg.name, cfg.camSpec, cfg.width, cfg.height, cfg.fps,
            hubHost = cfg.hubHost, preview = st,
        ) { status -> runOnUiThread { show(status) } }
        session = s
        configureTransform(w, h, s.size.width, s.size.height)
        s.start()
    }

    private fun show(st: SenderSession.Status) {
        statusView.text = buildString {
            append("SENDER '${cfg.name}'  ${st.size.width}x${st.size.height}@${cfg.fps}\n")
            append(if (st.connected) "Hub: ${st.hub}  (connected)\n" else "Hub: ${st.hub}  (not connected)\n")
            append("Encode: ${"%.1f".format(st.encFps)} fps   ")
            append(if (st.synced) "Clock sync RTT: ${"%.1f".format(st.rttMs)} ms\n" else "Clock: not synced\n")
            append(if (st.locked) "Exposure/WB/focus: LOCKED\n" else "Exposure/WB/focus: auto\n")
            if (st.message.isNotEmpty()) append(st.message)
        }
        lockButton.text = if (st.locked) "Unlock exposure" else "Lock exposure"
    }

    /** Rotate the preview so it looks upright on screen (the streamed video is always sensor-native). */
    private fun configureTransform(viewW: Int, viewH: Int, bufW: Int, bufH: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, bufH.toFloat(), bufW.toFloat())
        val cx = viewRect.centerX(); val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = minOf(viewH.toFloat() / bufH, viewW.toFloat() / bufW)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        textureView.setTransform(matrix)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        session?.let { configureTransform(w, h, it.size.width, it.size.height) }
    }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { session?.stop(); session = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onDestroy() {
        session?.stop(); session = null
        super.onDestroy()
    }
}
