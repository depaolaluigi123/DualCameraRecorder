package com.dualcamerarecording.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import com.dualcamerarecording.model.AspectRatio
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Per-camera capability queries shared by the UI (which values to offer) and the camera
 * pipeline (how to configure the streams). Characteristics are cached per camera id.
 */
object CameraCapabilities {

    private const val TAG = "CameraCapabilities"

    /**
     * Largest preview stream. The previews are shown in cards / half the screen, so a
     * bigger stream only costs ISP bandwidth — and running two cameras with full-sensor
     * preview streams is exactly what made some camera pairs fail to configure.
     */
    private const val PREVIEW_MAX_WIDTH = 1280
    private const val PREVIEW_MAX_HEIGHT = 960

    /**
     * Qualcomm vendor table of `[width, height, minFps, maxFps]` rows: the rates the sensor
     * can stream at for a given largest stream size, beyond the standard AE target ranges.
     * Some phones only reach 60 fps through it (e.g. the main camera of the Redmi Note 9
     * Pro declares AE ranges up to 30 but 60 fps up to 2304×1728 here, which is how the
     * system camera app records 1080p60).
     */
    private const val QTI_STREAM_FPS_TABLE = "org.quic.camera2.streamBasedFPS.info.StreamBasedFPSTable"

    private val cache = ConcurrentHashMap<String, CameraCharacteristics>()

    fun characteristics(cm: CameraManager, cameraId: String): CameraCharacteristics? {
        cache[cameraId]?.let { return it }
        return try {
            cm.getCameraCharacteristics(cameraId).also { cache[cameraId] = it }
        } catch (e: Exception) {
            Log.w(TAG, "getCameraCharacteristics($cameraId) failed: ${e.message}")
            null
        }
    }

    /**
     * Preview size (landscape, sensor orientation) used for both preview surfaces: the
     * largest one of [aspect] within the preview cap, so the preview shows the framing of
     * the recording (a 16:9 stream is a centred crop of the 4:3 sensor image).
     */
    fun previewSize(cm: CameraManager, cameraId: String, aspect: AspectRatio = AspectRatio.RATIO_4_3): Size {
        val map = characteristics(cm, cameraId)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val fitting = sizes.filter { it.width <= PREVIEW_MAX_WIDTH && it.height <= PREVIEW_MAX_HEIGHT }
        return fitting.filter { aspect.matches(it.width, it.height) }.maxByOrNull { it.width * it.height }
            ?: fitting.filter { AspectRatio.RATIO_4_3.matches(it.width, it.height) }.maxByOrNull { it.width * it.height }
            ?: fitting.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
    }

    /** True when the camera can stream [size] to a video encoder surface. */
    fun isRecordingSizeSupported(cm: CameraManager, cameraId: String, size: Size): Boolean {
        val map = characteristics(cm, cameraId)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        return map.getOutputSizes(MediaCodec::class.java)?.any { it == size } == true
    }

    /**
     * Frame rates the camera can hold constant ([fps, fps] AE target range) while
     * streaming [size] to an encoder.
     */
    fun constantFrameRates(cm: CameraManager, cameraId: String, size: Size?): List<Int> {
        val chars = characteristics(cm, cameraId) ?: return emptyList()
        val maxFps = maxFrameRate(chars, size)
        val standard = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .filter { it.lower == it.upper && it.upper <= maxFps }
            .map { it.upper }
        return (standard + vendorFixedRates(chars, size)).distinct().sortedDescending()
    }

    /**
     * Constant rates the vendor table ([QTI_STREAM_FPS_TABLE]) allows at [size]: rows
     * whose size covers it and whose range is fixed ([f, f]).
     */
    private fun vendorFixedRates(chars: CameraCharacteristics, size: Size?): List<Int> {
        size ?: return emptyList()
        val table = vendorFpsTable(chars) ?: return emptyList()
        return table.toList().chunked(4).filter { row ->
            row.size == 4 && row[2] == row[3] && row[0] >= size.width && row[1] >= size.height
        }.map { it[3] }.distinct()
    }

    @Suppress("UNCHECKED_CAST")
    private fun vendorFpsTable(chars: CameraCharacteristics): IntArray? = try {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CameraCharacteristics.Key(QTI_STREAM_FPS_TABLE, IntArray::class.java)
        } else {
            chars.keys.firstOrNull { it.name == QTI_STREAM_FPS_TABLE } as CameraCharacteristics.Key<IntArray>?
        }
        key?.let { chars.get(it) }
    } catch (e: Exception) {
        // Absent on non-Qualcomm devices (IllegalArgumentException for an unknown vendor tag).
        null
    }

    /** True when [fps] comes from the vendor table, so it is not among the standard AE ranges. */
    private fun isVendorRate(cm: CameraManager, cameraId: String, fps: Int): Boolean {
        val table = characteristics(cm, cameraId)?.let { vendorFpsTable(it) } ?: return false
        return table.toList().chunked(4).any { it.size == 4 && it[2] == fps && it[3] == fps }
    }

    /**
     * Frame rates to offer for [size], highest first: the ones the camera can hold constant
     * ([constantFrameRates]). A camera that declares no constant rate gets the top of its
     * variable ranges instead (e.g. 30 for [8, 30]), so the list is never empty.
     */
    fun frameRateOptions(cm: CameraManager, cameraId: String, size: Size?): List<Int> {
        constantFrameRates(cm, cameraId, size).takeIf { it.isNotEmpty() }?.let { return it }
        val chars = characteristics(cm, cameraId) ?: return emptyList()
        val maxFps = maxFrameRate(chars, size)
        return chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .map { it.upper }
            .filter { it <= maxFps }
            .distinct()
            .sortedDescending()
    }

    /** Highest rate the camera can stream [size] at to an encoder (unlimited when unknown). */
    private fun maxFrameRate(chars: CameraCharacteristics, size: Size?): Int {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Int.MAX_VALUE
        if (size == null) return Int.MAX_VALUE
        val minFrameNs = try {
            map.getOutputMinFrameDuration(MediaCodec::class.java, size)
        } catch (e: Exception) {
            0L
        }
        return if (minFrameNs > 0) (1_000_000_000L / minFrameNs).toInt() else Int.MAX_VALUE
    }

    /** AE target range for [fps]: the fixed [fps, fps] range if present, else the closest one. */
    fun fpsRange(cm: CameraManager, cameraId: String, fps: Int): Range<Int>? {
        val ranges = characteristics(cm, cameraId)
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: Range(fps, fps).takeIf { isVendorRate(cm, cameraId, fps) }
            ?: ranges.filter { it.upper >= fps }.minByOrNull { it.upper - it.lower + abs(it.upper - fps) }
    }

    /** Highest digital zoom factor (1 = no zoom), the 100% end of the zoom sliders. */
    fun maxDigitalZoom(cm: CameraManager, cameraId: String): Float =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    /** Closest focus distance in diopters (0 = fixed-focus lens). */
    fun minimumFocusDistance(cm: CameraManager, cameraId: String): Float =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun supportsManualFocus(cm: CameraManager, cameraId: String): Boolean =
        minimumFocusDistance(cm, cameraId) > 0f

    fun availableAfModes(cm: CameraManager, cameraId: String): IntArray =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)

    fun maxAfRegions(cm: CameraManager, cameraId: String): Int =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0

    fun maxAeRegions(cm: CameraManager, cameraId: String): Int =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

    fun sensorOrientation(cm: CameraManager, cameraId: String): Int =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    fun isFrontFacing(cm: CameraManager, cameraId: String): Boolean =
        characteristics(cm, cameraId)?.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT

    /**
     * Rotation to store in the MP4 so the recorded video plays upright, for the
     * activity's fixed orientation: portrait (natural, device rotation 0°) or the app's
     * landscape (display ROTATION_90, i.e. the device turned 90° counter-clockwise).
     *
     * Standard Camera2 formula: the sensor orientation plus the device rotation, with
     * the rotation reversed for front-facing cameras because they look the other way.
     * For the typical sensors (rear 90°, front 270°) this gives portrait rear 90 /
     * front 270 and landscape rear 0 / front 0.
     */
    fun recordingOrientationHint(cm: CameraManager, cameraId: String, landscape: Boolean): Int {
        val sensor = sensorOrientation(cm, cameraId)
        val deviceDegrees = if (landscape) 270 else 0
        val signed = if (isFrontFacing(cm, cameraId)) -deviceDegrees else deviceDegrees
        return ((sensor + signed) % 360 + 360) % 360
    }
}
