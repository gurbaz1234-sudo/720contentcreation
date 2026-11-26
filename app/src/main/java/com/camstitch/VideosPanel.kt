package com.camstitch

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.util.concurrent.Executors

/** A recording in Movies/CamStitch. */
data class Clip(val uri: Uri, val name: String, val sizeBytes: Long, val seconds: Double?, val view: String?) {
    val meta: String
        get() = listOfNotNull(seconds?.let { "%.1f s".format(it) }, view, "%.1f MB".format(sizeBytes / 1048576.0)).joinToString(" · ")
}

/**
 * Full-screen "Recorded videos" screen on top of the hub view: a list with
 * Play / Export / Delete per video, an in-app player, and the delete dialog.
 */
class VideosPanel(private val act: Activity, private val onChanged: (Int) -> Unit) {

    companion object { const val REQ_DELETE = 43 }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics).toInt()

    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), dp(4f), dp(12f), dp(12f)) }
    private val countView = TextView(act).apply { setTextColor(0xFF9AA0A6.toInt()); textSize = 14f }
    private val player = VideoView(act)
    private val playerBox = FrameLayout(act).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
    private var pendingDelete: Clip? = null

    /** The whole panel; add it on top of the hub view. */
    val root: FrameLayout = FrameLayout(act).apply { setBackgroundColor(0xFF121214.toInt()); visibility = View.GONE; isClickable = true }

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1D1D20.toInt()); setPadding(dp(16f), dp(6f), dp(4f), dp(6f))
        }
        header.addView(TextView(act).apply { text = "Recorded videos  "; setTextColor(Color.WHITE); textSize = 17f })
        header.addView(countView, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(textButton("✕", Color.WHITE, 20f) { hide() })
        col.addView(header)
        col.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        player.setMediaController(MediaController(act).also { it.setAnchorView(player) })
        player.setOnPreparedListener { it.isLooping = true }
        playerBox.addView(player, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playerBox.addView(textButton("✕", Color.WHITE, 20f) { closePlayer() },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        root.addView(playerBox, FrameLayout.LayoutParams(-1, -1))
    }

    val isShowing get() = root.visibility == View.VISIBLE

    fun show() { root.visibility = View.VISIBLE; refresh() }

    fun hide() { closePlayer(); root.visibility = View.GONE }

    /** Back button: close the player first, then the list. Returns true if it handled it. */
    fun onBack(): Boolean = when {
        playerBox.visibility == View.VISIBLE -> { closePlayer(); true }
        isShowing -> { hide(); true }
        else -> false
    }

    private fun closePlayer() { player.stopPlayback(); playerBox.visibility = View.GONE }

    // ------------------------------------------------------------ data

    /** Videos this app saved in Movies/CamStitch, newest first. */
    fun query(): List<Clip> {
        val out = ArrayList<Clip>()
        val col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DESCRIPTION)
        try {
            act.contentResolver.query(col, proj, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${VideoRecorder.FOLDER}%"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val desc = c.getString(3)?.split("|")
                    out += Clip(ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "video.mp4",
                        c.getLong(2), desc?.getOrNull(1)?.toDoubleOrNull(), desc?.getOrNull(0)?.takeIf { it.isNotBlank() })
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun refresh() {
        io.execute {
            val clips = query()
            main.post { render(clips); onChanged(clips.size) }
        }
    }

    private fun render(clips: List<Clip>) {
        list.removeAllViews()
        countView.text = if (clips.isEmpty()) "" else "· ${clips.size}"
        if (clips.isEmpty()) {
            list.addView(TextView(act).apply {
                text = "No videos yet. Record one with ● Rec on Top-down or 3D orbit (max 10 s)."
                setTextColor(0xFF9AA0A6.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(60f), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        for (c in clips) list.addView(row(c))
    }

    private fun row(c: Clip): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(6f))
        }
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
        r.addView(thumb, LinearLayout.LayoutParams(dp(120f), dp(54f)))
        io.execute {
            val bmp: Bitmap? = try { act.contentResolver.loadThumbnail(c.uri, Size(320, 144), null) } catch (e: Exception) { null }
            if (bmp != null) main.post { thumb.setImageBitmap(bmp) }
        }
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(8f), 0) }
        texts.addView(TextView(act).apply {
            text = c.name; setTextColor(Color.WHITE); textSize = 14f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(act).apply { text = c.meta; setTextColor(0xFF9AA0A6.toInt()); textSize = 12f })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(textButton("Play", 0xFF8AB4F8.toInt()) { play(c) })
        r.addView(textButton("Export", 0xFF8AB4F8.toInt()) { export(c) })
        r.addView(textButton("Delete", 0xFFF28B82.toInt()) { confirmDelete(c) })
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(r)
        wrap.addView(View(act).apply { setBackgroundColor(0xFF2A2A2E.toInt()) }, LinearLayout.LayoutParams(-1, dp(1f)))
        return wrap
    }

    private fun textButton(label: String, color: Int, size: Float = 13f, onClick: () -> Unit) = Button(act).apply {
        text = label; setTextColor(color); textSize = size; isAllCaps = true
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        minWidth = 0; minimumWidth = 0; setPadding(dp(10f), 0, dp(10f), 0)
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ actions

    private fun play(c: Clip) {
        playerBox.visibility = View.VISIBLE
        player.setVideoURI(c.uri)
        player.start()
    }

    /** Android share sheet: Drive, WhatsApp, Gmail, Files, Nearby Share... */
    private fun export(c: Clip) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, c.uri)
            putExtra(Intent.EXTRA_TITLE, c.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(Intent.createChooser(send, "Export ${c.name}"))
    }

    private fun confirmDelete(c: Clip) {
        AlertDialog.Builder(act)
            .setTitle("Delete video?")
            .setMessage("${c.name} (${c.meta}) will be removed from ${VideoRecorder.FOLDER}. This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> delete(c) }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun delete(c: Clip) {
        try {
            act.contentResolver.delete(c.uri, null, null)
            toast("Video deleted")
            refresh()
        } catch (e: SecurityException) {
            // Not owned by this install (e.g. after reinstalling): ask the system to confirm.
            try {
                val sender = if (Build.VERSION.SDK_INT >= 30) {
                    MediaStore.createDeleteRequest(act.contentResolver, listOf(c.uri)).intentSender
                } else {
                    (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender ?: throw e
                }
                pendingDelete = c
                act.startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0)
            } catch (e2: Exception) {
                toast("Cannot delete: ${e2.message}")
            }
        }
    }

    /** Forward from Activity.onActivityResult. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_DELETE) return false
        val c = pendingDelete; pendingDelete = null
        if (resultCode == Activity.RESULT_OK && c != null) {
            if (Build.VERSION.SDK_INT < 30) delete(c) else { toast("Video deleted"); refresh() }
        }
        return true
    }

    private fun toast(m: String) = android.widget.Toast.makeText(act, m, android.widget.Toast.LENGTH_SHORT).show()
}
