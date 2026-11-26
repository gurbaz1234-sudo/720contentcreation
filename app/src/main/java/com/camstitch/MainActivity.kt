package com.camstitch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Settings passed from the launcher to Hub / Sender. */
data class Settings(
    val name: String, val camSpec: CamSpec, val width: Int, val height: Int,
    val fps: Int, val hubHost: String?, val useLocalCamera: Boolean,
) {
    fun toIntent(i: Intent): Intent = i.putExtra("name", name).putExtra("cam", camSpec.encode())
        .putExtra("w", width).putExtra("h", height).putExtra("fps", fps)
        .putExtra("hub", hubHost ?: "").putExtra("local", useLocalCamera)

    companion object {
        fun fromIntent(i: Intent) = Settings(
            i.getStringExtra("name") ?: "cam",
            CamSpec.decode(i.getStringExtra("cam") ?: "0||1.0"),
            i.getIntExtra("w", 1280), i.getIntExtra("h", 960), i.getIntExtra("fps", 30),
            i.getStringExtra("hub")?.ifBlank { null }, i.getBooleanExtra("local", true),
        )
    }
}

class MainActivity : Activity() {

    private val resolutions = listOf(
        "1280x960 (4:3, full FOV, recommended)" to (1280 to 960),
        "1280x720 (16:9, crops FOV)" to (1280 to 720),
        "1440x1080 (4:3)" to (1440 to 1080),
        "1920x1080 (16:9)" to (1920 to 1080),
        "960x720 (4:3, low bandwidth)" to (960 to 720),
    )
    private var camOptions: List<CamOption> = emptyList()

    private lateinit var nameEdit: EditText
    private lateinit var camSpinner: Spinner
    private lateinit var resSpinner: Spinner
    private lateinit var fpsEdit: EditText
    private lateinit var hubEdit: EditText
    private lateinit var localCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("camstitch", Context.MODE_PRIVATE)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, 16, 0, 0) }

        col.addView(TextView(this).apply { text = "CamStitch POC"; textSize = 22f })
        col.addView(label("Camera name (must match calibration; e.g. hub, cam1, cam2)"))
        nameEdit = EditText(this).apply { setSingleLine(); setText(prefs.getString("name", "cam1")) }
        col.addView(nameEdit)

        col.addView(label("Camera / lens (pick the widest one)"))
        camSpinner = Spinner(this)
        col.addView(camSpinner)

        col.addView(label("Resolution (all phones should use the same)"))
        resSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, resolutions.map { it.first })
            setSelection(prefs.getInt("res", 0))
        }
        col.addView(resSpinner)

        col.addView(label("FPS"))
        fpsEdit = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("fps", 30).toString()) }
        col.addView(fpsEdit)

        col.addView(label("Hub IP (sender only; leave blank to auto-discover)"))
        hubEdit = EditText(this).apply { setSingleLine(); hint = "e.g. 192.168.43.1"; setText(prefs.getString("hub", "")) }
        col.addView(hubEdit)

        localCheck = CheckBox(this).apply { text = "Hub: also use this phone's own camera"; isChecked = prefs.getBoolean("local", true) }
        col.addView(localCheck)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 24, 0, 0) }
        buttons.addView(Button(this).apply { text = "Start HUB (primary phone)"; setOnClickListener { launch(HubActivity::class.java) } })
        buttons.addView(Button(this).apply { text = "Start SENDER"; setOnClickListener { launch(SenderActivity::class.java) } })
        col.addView(buttons)
        col.addView(TextView(this).apply {
            textSize = 12f; setPadding(0, 24, 0, 0)
            text = "Tip: turn on the Wi-Fi hotspot on the hub phone and join it from the senders. " +
                "If auto-discovery fails, type the hub IP shown on the hub screen."
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else loadCameras()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadCameras()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    private fun loadCameras() {
        camOptions = try { CameraUtil.listOptions(this) } catch (e: Exception) { emptyList() }
        camSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, camOptions.map { it.label })
        val saved = getSharedPreferences("camstitch", Context.MODE_PRIVATE).getString("cam", null)
        val idx = camOptions.indexOfFirst { it.spec.encode() == saved }
        if (idx >= 0) camSpinner.setSelection(idx)
    }

    private fun launch(cls: Class<*>) {
        if (camOptions.isEmpty()) { Toast.makeText(this, "No camera available", Toast.LENGTH_LONG).show(); return }
        val spec = camOptions[camSpinner.selectedItemPosition.coerceAtLeast(0)].spec
        val (w, h) = resolutions[resSpinner.selectedItemPosition.coerceAtLeast(0)].second
        val fps = fpsEdit.text.toString().toIntOrNull()?.coerceIn(5, 60) ?: 30
        val name = nameEdit.text.toString().trim().ifEmpty { if (cls == HubActivity::class.java) "hub" else "cam1" }
        val s = Settings(name, spec, w, h, fps, hubEdit.text.toString().trim().ifEmpty { null }, localCheck.isChecked)
        getSharedPreferences("camstitch", Context.MODE_PRIVATE).edit()
            .putString("name", name).putString("cam", spec.encode()).putInt("res", resSpinner.selectedItemPosition)
            .putInt("fps", fps).putString("hub", s.hubHost ?: "").putBoolean("local", s.useLocalCamera).apply()
        startActivity(s.toIntent(Intent(this, cls)))
    }
}
