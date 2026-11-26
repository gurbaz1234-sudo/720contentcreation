package com.camstitch

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.atan

/**
 * Which camera to open. Many phones only expose the ultra-wide lens as a
 * "physical" camera behind a logical multi-camera, or through a zoom ratio < 1.
 */
data class CamSpec(val logicalId: String, val physicalId: String?, val zoom: Float) {
    fun encode(): String = "$logicalId|${physicalId ?: ""}|$zoom"

    companion object {
        fun decode(s: String): CamSpec {
            val p = s.split("|")
            return CamSpec(p[0], p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.toFloatOrNull() ?: 1f)
        }
    }
}

data class CamOption(val spec: CamSpec, val label: String)

object CameraUtil {

    fun listOptions(ctx: Context): List<CamOption> {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val out = ArrayList<CamOption>()
        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "back"
                CameraMetadata.LENS_FACING_FRONT -> "front"
                else -> "ext"
            }
            out += CamOption(CamSpec(id, null, 1f), "Cam $id ($facing) ${lensDesc(ch)}")

            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                for (phys in ch.physicalCameraIds) {
                    val pch = try { mgr.getCameraCharacteristics(phys) } catch (e: Exception) { continue }
                    out += CamOption(CamSpec(id, phys, 1f), "Cam $id -> lens $phys ($facing) ${lensDesc(pch)}")
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zr != null && zr.lower < 0.99f) {
                    out += CamOption(CamSpec(id, null, zr.lower), "Cam $id ($facing) zoom ${"%.2f".format(zr.lower)}x (ultra-wide)")
                }
            }
        }
        return out
    }

    private fun lensDesc(ch: CameraCharacteristics): String {
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return ""
        val sz = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return "f=${"%.1f".format(f)}mm"
        val hfov = Math.toDegrees(2.0 * atan(sz.width / (2.0 * f)))
        return "f=${"%.1f".format(f)}mm ~${hfov.toInt()}° wide"
    }

    /** Exact size if offered, else same aspect closest in area, else closest in area. */
    fun chooseSize(map: StreamConfigurationMap, cls: Class<*>, w: Int, h: Int): Size {
        val sizes = map.getOutputSizes(cls) ?: throw IllegalStateException("no output sizes")
        sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        val aspect = w.toDouble() / h
        val area = w.toLong() * h
        val sameAspect = sizes.filter { abs(it.width.toDouble() / it.height - aspect) < 0.02 }
        val pool = sameAspect.ifEmpty { sizes.toList() }
        return pool.minByOrNull { abs(it.width.toLong() * it.height - area) }!!
    }

    fun characteristicsFor(ctx: Context, spec: CamSpec): CameraCharacteristics {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        return mgr.getCameraCharacteristics(spec.physicalId ?: spec.logicalId)
    }
}

/**
 * Opens a camera and streams into the given surfaces with settings suited to
 * stitching: stabilization and distortion correction off, and exposure, white
 * balance and focus locked a few seconds after start (or on demand).
 */
class CameraController(
    ctx: Context,
    private val spec: CamSpec,
    private val fps: Int,
    private val surfaces: List<Surface>,
    private val log: (String) -> Unit,
) {
    private val mgr = ctx.getSystemService(CameraManager::class.java)
    private val chars = mgr.getCameraCharacteristics(spec.logicalId)
    private val lensChars = spec.physicalId?.let { mgr.getCameraCharacteristics(it) } ?: chars
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @Volatile var locked = false
        private set
    @Volatile private var lastFocusDistance: Float? = null
    @Volatile private var closed = false

    /** True if sensor timestamps use elapsedRealtimeNanos (BOOTTIME), else System.nanoTime (MONOTONIC). */
    val timestampIsRealtime: Boolean =
        chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    @SuppressLint("MissingPermission")
    fun start() {
        mgr.openCamera(spec.logicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (closed) { d.close(); return }
                device = d
                createSession(d)
            }
            override fun onDisconnected(d: CameraDevice) { log("camera disconnected"); d.close() }
            override fun onError(d: CameraDevice, error: Int) { log("camera error $error"); d.close() }
        }, handler)
    }

    private fun createSession(d: CameraDevice) {
        val outs = surfaces.map { s ->
            OutputConfiguration(s).apply { spec.physicalId?.let { setPhysicalCameraId(it) } }
        }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { s.close(); return }
                    session = s
                    submit()
                    log("camera running (auto-lock in 3 s)")
                    handler.postDelayed({ setLocked(true) }, 3000)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    log("camera session configuration failed (try another resolution or lens)")
                }
            })
        d.createCaptureSession(cfg)
    }

    fun setLocked(on: Boolean) = handler.post {
        locked = on
        submit()
        log(if (on) "exposure/white-balance/focus LOCKED" else "auto exposure (unlocked)")
    }

    private fun fpsRange(): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.contains(fps) }.maxByOrNull { it.lower }
    }

    private fun submit() {
        val d = device ?: return
        val s = session ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            surfaces.forEach { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            fpsRange()?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

            // Anything that moves or warps the image breaks calibration.
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            val ois = lensChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            val dc = chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            if (dc?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true) {
                b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
            }
            if (Build.VERSION.SDK_INT >= 30 && spec.physicalId == null && spec.zoom != 1f) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, spec.zoom)
            }

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val minFocus = lensChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (locked) {
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                val fd = lastFocusDistance
                if (fd != null && minFocus > 0f && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
                }
            } else if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (!locked) result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
                }
            }, handler)
        } catch (e: Exception) {
            log("capture request failed: ${e.message}")
        }
    }

    fun stop() {
        closed = true
        handler.post {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            session = null; device = null
        }
        thread.quitSafely()
    }

    companion object {
        /** Convert a sensor timestamp to the System.nanoTime() clock. */
        fun toMonotonicNs(sensorNs: Long, isRealtime: Boolean): Long =
            if (isRealtime) sensorNs - (android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            else sensorNs
    }
}
