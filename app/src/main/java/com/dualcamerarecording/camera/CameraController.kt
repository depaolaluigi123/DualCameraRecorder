package com.dualcamerarecording.camera

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.WorkerThread
import com.dualcamerarecording.model.AspectRatio

/**
 * Camera2 API controller for a single camera.
 *
 * Manages camera opening, capture session, manual controls (focus / ISO / exposure time),
 * flash (torch), digital zoom and tap-to-focus. Every capture request is built by
 * [newRequestBuilder], so each of them carries the same complete state: frame rate,
 * AF/AE mode, manual values, zoom crop, metering region and torch.
 *
 * Key points:
 *  - The capture session holds the main preview surface, the optional secondary
 *    (fullscreen) preview surface and, while recording, the encoder surface. Switching
 *    the visible preview is a pure view change, no session reconfiguration.
 *  - The frame rate is enforced with `CONTROL_AE_TARGET_FPS_RANGE = [fps, fps]` (and
 *    `SENSOR_FRAME_DURATION` when exposure is manual), so the camera really delivers the
 *    frame rate the encoder is configured for.
 *  - Manual focus takes a normalized value in [0, 1] (0 = infinity, 1 = closest focus of
 *    the lens) and converts it to diopters with `LENS_INFO_MINIMUM_FOCUS_DISTANCE`.
 *  - FLASH_MODE is the LAST key set on the request builder, and the torch is only driven
 *    when the lens has a flash unit (probed per camera id, physical sub-cameras included).
 */
class CameraController(
    val cameraId: String,
    private val cameraManager: CameraManager,
    previewSurface: Surface?,
    recordSurface: Surface?,
    private val imageReaderSurface: Surface?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    /** Facing for convenience. */
    val facing: Int = android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
    /**
     * Optional second preview surface (the fullscreen preview TextureView). When non-null
     * it is part of the capture session and of every request, so the camera writes the
     * same frames to both previews and toggling fullscreen never reconfigures the camera.
     */
    previewSurfaceSecondary: Surface? = null,
    /** Shape of the preview streams: the shape of the recording, so the previews show its framing. */
    val previewAspect: AspectRatio = AspectRatio.RATIO_4_3
) {
    private val previewSurface: Surface? = previewSurface
    private val previewSurfaceSecondary: Surface? = previewSurfaceSecondary

    /** Encoder input surface while recording (part of the session and of every request). */
    @Volatile
    private var recordSurface: Surface? = recordSurface

    /** Size of the preview streams (both preview surfaces must be sized to it). */
    val previewSize: Size = CameraCapabilities.previewSize(cameraManager, cameraId, previewAspect)

    private val characteristics = CameraCapabilities.characteristics(cameraManager, cameraId)
    private val sensorOrientation = CameraCapabilities.sensorOrientation(cameraManager, cameraId)
    private val minimumFocusDistance = CameraCapabilities.minimumFocusDistance(cameraManager, cameraId)
    private val afModes = CameraCapabilities.availableAfModes(cameraManager, cameraId)
    private val maxAfRegions = CameraCapabilities.maxAfRegions(cameraManager, cameraId)
    private val maxAeRegions = CameraCapabilities.maxAeRegions(cameraManager, cameraId)

    @Volatile
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null

    // Manual control state. -1 sentinel clears and returns to auto.
    private var isoValue: Int? = null
    private var exposureTimeNanos: Long? = null
    /** Normalized manual focus position: 0 = infinity, 1 = closest focus distance. */
    private var focusDistance: Float? = null
    private var exposureCompensation: Int? = null

    // Manual-mode toggles. When false the corresponding control reverts to its auto mode.
    var manualFocusEnabled: Boolean = false
        private set
    var manualExposureEnabled: Boolean = false
        private set

    /** Frame rate the camera must hold; applied to every request. */
    @Volatile
    private var targetFps: Int = 30
    private var fpsRange: Range<Int>? = CameraCapabilities.fpsRange(cameraManager, cameraId, targetFps)

    /**
     * When false, requests only target the encoder surface (the activity is in the
     * background during a recording and nobody draws the previews).
     */
    @Volatile
    private var previewTargetsEnabled: Boolean = true

    /** A tap-to-focus scan is running (its requests use AF_MODE_AUTO). */
    @Volatile
    private var afScanActive = false

    /** Tap-to-focus region (active-array coordinates); kept until zoom or a new tap. */
    @Volatile
    private var meteringRegion: MeteringRectangle? = null

    /**
     * Last digital-zoom crop applied to the sensor active array; null = full frame.
     * Re-applied on every request so the zoom never snaps back to 1.0x.
     */
    @Volatile
    private var currentCrop: Rect? = null

    /** Linear zoom value in [0, 1] — 0 is no zoom, 1 is the device's max digital zoom. */
    private var linearZoom: Float = 0f

    // Torch on/off as requested by the user.
    @Volatile
    private var torchOn: Boolean = false

    /**
     * FLASH_INFO_AVAILABLE for this lens (logical id or any physical sub-camera). FLASH_MODE
     * changes are skipped on lenses without a flash unit: some OEM aux lenses accept the key
     * but deadlock the session when FLASH_MODE goes OFF after a TORCH cycle.
     */
    private var flashModeSupported: Boolean = false

    // When true, captureImage() is a no-op (set while cameras are being switched).
    @Volatile
    var captureBlocked: Boolean = false

    var onCameraOpened: (() -> Unit)? = null
    var onCameraClosed: (() -> Unit)? = null
    var onError: ((Int, String) -> Unit)? = null

    @Volatile
    private var closed = false

    /** Target display rotation (Surface.ROTATION_*), used for JPEG_ORIENTATION. */
    @Volatile
    private var targetRotation: Int = Surface.ROTATION_0

    @WorkerThread
    fun openCamera() {
        try {
            captureSession = null
            cameraDevice = null
            flashModeSupported = flashModeSupported()
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) {
                        device.close()
                        return
                    }
                    cameraDevice = device
                    Log.d(TAG, "Camera $cameraId opened (preview ${previewSize.width}x${previewSize.height}, " +
                        "record=${recordSurface != null}, flash=$flashModeSupported)")
                    createCaptureSession()
                    handler.post { onCameraOpened?.invoke() }
                }

                override fun onDisconnected(device: CameraDevice) {
                    // Another client (or a conflicting camera opened by this app) took the
                    // device: report it like an error so callers do not wait forever.
                    try { device.close() } catch (_: Exception) {}
                    cameraDevice = null
                    captureSession = null
                    onCameraClosed?.invoke()
                    if (!closed) onError?.invoke(ERROR_DISCONNECTED, "Camera $cameraId disconnected")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    try { device.close() } catch (_: Exception) {}
                    cameraDevice = null
                    captureSession = null
                    onError?.invoke(error, "Camera $cameraId error: $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "No camera permission for $cameraId: ${e.message}")
            onError?.invoke(-1, "No camera permission")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera $cameraId: ${e.message}")
            onError?.invoke(-1, "Failed to open camera: ${e.message}")
        }
    }

    fun isReady(): Boolean = cameraDevice != null && captureSession != null

    private fun flashModeSupported(): Boolean {
        return try {
            val chars = characteristics ?: return false
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == java.lang.Boolean.TRUE) {
                return true
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val physicalIds = try { chars.physicalCameraIds } catch (e: Exception) { emptySet() }
                for (pid in physicalIds) {
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(pid)
                        if (pChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == java.lang.Boolean.TRUE) {
                            Log.d(TAG, "flashModeSupported: flash found on physical sub-camera $pid of $cameraId")
                            return true
                        }
                    } catch (e: Exception) { /* skip this physical */ }
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "flashModeSupported probe failed: ${e.message}")
            false
        }
    }

    @WorkerThread
    fun createCaptureSession() {
        afScanActive = false
        val device = cameraDevice ?: run {
            Log.e(TAG, "Camera device not opened")
            return
        }

        val surfaces = listOfNotNull(previewSurface, previewSurfaceSecondary, recordSurface, imageReaderSurface)
        if (surfaces.isEmpty()) {
            Log.e(TAG, "No surfaces to create capture session")
            return
        }

        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closed) {
                        try { session.close() } catch (_: Exception) {}
                        return
                    }
                    captureSession = session
                    startPreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed for $cameraId")
                    onError?.invoke(-1, "Capture session failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
            onError?.invoke(-1, "Failed to create capture session: ${e.message}")
        }
    }

    @WorkerThread
    fun startPreview() {
        submitRepeating("start preview")
    }

    /**
     * Add (or remove, with null) the encoder surface by creating a new capture session on
     * the already open device — much faster than closing and re-opening the camera, which
     * on some HALs takes seconds. [isReady] is false until the new session is configured.
     */
    fun setRecordSurface(surface: Surface?) {
        recordSurface = surface
        // Not ready until the new session is configured (the old one keeps streaming
        // until the device replaces it).
        captureSession = null
        handler.post {
            if (closed || cameraDevice == null) return@post
            createCaptureSession()
        }
    }

    /**
     * Build a request targeting the current outputs, carrying the complete state
     * (see [applyState]). Callers may override individual keys before building.
     */
    private fun newRequestBuilder(): CaptureRequest.Builder {
        val device = cameraDevice ?: error("Camera not opened")
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        if (previewTargetsEnabled || recordSurface == null) {
            previewSurface?.let { builder.addTarget(it) }
            previewSurfaceSecondary?.let { builder.addTarget(it) }
        }
        recordSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation - targetRotation * 90 + 360) % 360)
        applyState(builder)
        return builder
    }

    /** Push a fresh repeating request with the current state. */
    private fun submitRepeating(reason: String) {
        val session = captureSession ?: return
        if (cameraDevice == null) return
        try {
            session.setRepeatingRequest(newRequestBuilder().build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to $reason on $cameraId: ${e.message}")
        }
    }

    /**
     * Apply manual control settings.
     *
     * Sentinel -1 (for focus/iso/exposure) clears the stored value and reverts that
     * control to its auto mode. null means "no change".
     *
     * focusEnabled / exposureEnabled toggle the AF/AE modes directly: when true the
     * mode is OFF (manual); when false the mode reverts to auto. The torch is fully
     * independent of AF/AE.
     */
    @WorkerThread
    fun applyManualControls(
        iso: Int? = null,
        exposureTimeNanos: Long? = null,
        focusDistance: Float? = null,
        exposureCompensation: Int? = null,
        torchEnabled: Boolean? = null,
        focusEnabled: Boolean? = null,
        exposureEnabled: Boolean? = null
    ) {
        if (focusEnabled != null) manualFocusEnabled = focusEnabled
        if (exposureEnabled != null) manualExposureEnabled = exposureEnabled
        if (focusDistance != null) {
            this.focusDistance = if (focusDistance < 0) null else focusDistance.coerceIn(0f, 1f)
            this.focusDistance?.let {
                Log.d(TAG, "Manual focus on $cameraId: $it -> ${it * minimumFocusDistance} diopters " +
                    "(lens range 0..$minimumFocusDistance)")
            }
        }
        if (iso != null) {
            this.isoValue = if (iso < 0) null else iso
        }
        if (exposureTimeNanos != null) {
            this.exposureTimeNanos = if (exposureTimeNanos < 0) null else exposureTimeNanos
        }
        if (exposureCompensation != null) {
            this.exposureCompensation = if (exposureCompensation == 0) null else exposureCompensation
        }
        if (torchEnabled != null) {
            this.torchOn = torchEnabled
        }
        submitRepeating("update manual controls")
    }

    /** Set the frame rate the camera has to hold (preview and recording). */
    fun setTargetFps(fps: Int) {
        if (fps <= 0 || fps == targetFps && fpsRange != null) return
        targetFps = fps
        fpsRange = CameraCapabilities.fpsRange(cameraManager, cameraId, fps)
        Log.d(TAG, "Camera $cameraId target fps=$fps range=$fpsRange")
        submitRepeating("apply frame rate")
    }

    /**
     * Include or exclude the preview surfaces from the requests (the session keeps them
     * configured). Used while recording with the activity in the background.
     */
    fun setPreviewTargetsEnabled(enabled: Boolean) {
        if (previewTargetsEnabled == enabled) return
        previewTargetsEnabled = enabled
        submitRepeating("toggle preview outputs")
    }

    /**
     * Leave one frame out of the recording: a single request targeting only the preview
     * outputs, captured between two repeating ones. Used when the camera runs slightly
     * faster than the frame rate. Not possible without a preview to target (activity in the
     * background), and not done during a tap-to-focus scan, whose AF mode it would reset.
     */
    fun skipRecordFrame() {
        handler.post {
            val session = captureSession ?: return@post
            val device = cameraDevice ?: return@post
            if (recordSurface == null || !previewTargetsEnabled || afScanActive) return@post
            val previews = listOfNotNull(previewSurface, previewSurfaceSecondary)
            if (previews.isEmpty()) return@post
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                previews.forEach { builder.addTarget(it) }
                applyState(builder)
                session.capture(builder.build(), null, handler)
            } catch (e: Exception) {
                Log.w(TAG, "skipRecordFrame failed on $cameraId: ${e.message}")
            }
        }
    }

    /**
     * Update the display rotation (Surface.ROTATION_*). Only affects JPEG_ORIENTATION;
     * preview rotation is handled by the TextureView transform in the activity.
     */
    @WorkerThread
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        submitRepeating("set target rotation")
    }

    /** Toggle torch on/off. No-op on the LED when the lens has no flash unit. */
    @WorkerThread
    fun setTorchMode(enabled: Boolean) {
        if (!flashModeSupported && enabled) {
            Log.w(TAG, "Torch requested but flash not supported on $cameraId")
        }
        torchOn = enabled
        submitRepeating("set torch")
    }

    /**
     * Tap-to-focus at a point of the preview. [viewMatrix] is the TextureView transform,
     * ([x], [y]) the tap in view coordinates and [viewWidth] x [viewHeight] the view size.
     *
     * The point is mapped back through the view transform and the camera's own preview
     * transform (sensor rotation, plus horizontal mirroring for front cameras) to the
     * sensor, then into the current zoom crop of the active array. An AF scan is run on
     * that region; the region is kept for continuous AF/AE until zoom or the next tap.
     *
     * No-op when manual focus is enabled; on fixed-focus lenses only the AE region is set.
     */
    @WorkerThread
    fun triggerAutoFocusAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int, viewMatrix: Matrix) {
        if (manualFocusEnabled) {
            Log.d(TAG, "triggerAutoFocus ignored: manual focus is enabled")
            return
        }
        val session = captureSession ?: return
        if (cameraDevice == null) return
        val region = meteringRegionForTap(x, y, viewWidth, viewHeight, viewMatrix) ?: return
        meteringRegion = region
        if (!supportsAutoFocusScan()) {
            submitRepeating("apply metering region")
            return
        }
        try {
            afScanActive = true
            // 1) Cancel any in-flight AF, keeping the repeating stream alive.
            val cancelBuilder = newRequestBuilder().apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }
            val cancelCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    r1: TotalCaptureResult
                ) {
                    // 2) After one cancelled frame, switch to the AF start request.
                    triggerStartRepeating(s)
                }
            }
            session.setRepeatingRequest(cancelBuilder.build(), cancelCallback, handler)
        } catch (e: Exception) {
            afScanActive = false
            Log.e(TAG, "triggerAutoFocus failed: ${e.message}")
        }
    }

    private fun supportsAutoFocusScan(): Boolean =
        maxAfRegions > 0 && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) && minimumFocusDistance > 0f

    /** Map a tap on the preview to a metering rectangle in active-array coordinates. */
    private fun meteringRegionForTap(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        viewMatrix: Matrix
    ): MeteringRectangle? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val activeArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        // View coordinates -> preview content (the buffer stretched over the view).
        val inverse = Matrix()
        if (!viewMatrix.invert(inverse)) return null
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        // Content -> normalized displayed image -> normalized sensor image. The camera
        // shows the sensor image rotated by the sensor orientation and, for front cameras,
        // mirrored horizontally first (rotation reversed): invert that mapping.
        val mirrored = facing == CameraCharacteristics.LENS_FACING_FRONT
        val rotation = if (mirrored) (360 - sensorOrientation) % 360 else sensorOrientation
        val toDisplay = Matrix().apply {
            if (mirrored) postScale(-1f, 1f, 0.5f, 0.5f)
            postRotate(rotation.toFloat(), 0.5f, 0.5f)
        }
        val toSensor = Matrix()
        if (!toDisplay.invert(toSensor)) return null
        val normalized = floatArrayOf(
            (point[0] / viewWidth).coerceIn(0f, 1f),
            (point[1] / viewHeight).coerceIn(0f, 1f)
        )
        toSensor.mapPoints(normalized)
        // Sensor image -> active array, inside the current zoom crop. The preview stream shows
        // the largest centred part of the crop with its own shape (the middle band for 16:9).
        val crop = currentCrop ?: Rect(0, 0, activeArray.width(), activeArray.height())
        val visible = visibleCrop(crop, previewSize.width.toFloat() / previewSize.height)
        val cx = visible.left + normalized[0].coerceIn(0f, 1f) * visible.width()
        val cy = visible.top + normalized[1].coerceIn(0f, 1f) * visible.height()
        val half = maxOf(crop.width(), crop.height()) * REGION_FRACTION / 2f
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        val bounds = RectF(0f, 0f, activeArray.width().toFloat() - 1, activeArray.height().toFloat() - 1)
        if (!rect.intersect(bounds)) return null
        Log.d(TAG, "Tap ($x, $y) on $cameraId -> sensor region $rect")
        return MeteringRectangle(
            rect.left.toInt(), rect.top.toInt(),
            rect.width().toInt().coerceAtLeast(1), rect.height().toInt().coerceAtLeast(1),
            MeteringRectangle.METERING_WEIGHT_MAX - 1
        )
    }

    /** Largest centred rectangle of [crop] with the aspect ratio [streamAspect] (width / height). */
    private fun visibleCrop(crop: Rect, streamAspect: Float): RectF {
        val cropAspect = crop.width().toFloat() / crop.height()
        return if (streamAspect > cropAspect) {
            val height = crop.width() / streamAspect
            RectF(crop.left.toFloat(), crop.exactCenterY() - height / 2f, crop.right.toFloat(), crop.exactCenterY() + height / 2f)
        } else {
            val width = crop.height() * streamAspect
            RectF(crop.exactCenterX() - width / 2f, crop.top.toFloat(), crop.exactCenterX() + width / 2f, crop.bottom.toFloat())
        }
    }

    private fun triggerStartRepeating(session: CameraCaptureSession) {
        try {
            // Re-apply the AF-start request while the lens scans, built fresh each time so
            // the latest zoom / manual / flash state is always honoured.
            val pollCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    r1: TotalCaptureResult
                ) {
                    val afState = r1.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null ||
                        afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {
                        try {
                            session.setRepeatingRequest(buildAfStartRequest(), this, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "triggerStartRepeating poll failed: ${e.message}")
                            resumeContinuousAf(session)
                        }
                    } else {
                        resumeContinuousAf(session)
                    }
                }
            }
            session.setRepeatingRequest(buildAfStartRequest(), pollCallback, handler)
        } catch (e: Exception) {
            afScanActive = false
            Log.e(TAG, "triggerStartRepeating failed: ${e.message}")
        }
    }

    private fun buildAfStartRequest(): CaptureRequest {
        return newRequestBuilder().apply {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        }.build()
    }

    private fun resumeContinuousAf(session: CameraCaptureSession) {
        afScanActive = false
        try {
            val resume = newRequestBuilder().apply {
                // AF_TRIGGER=IDLE releases the START latch of the previous request.
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            session.setRepeatingRequest(resume.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "resumeContinuousAf failed: ${e.message}")
        }
    }

    /**
     * Write the complete state into [builder]: AF/AE mode and manual values, frame rate,
     * zoom crop, metering region, and the torch LAST (ordering matters on some devices).
     */
    private fun applyState(builder: CaptureRequest.Builder) {
        val manualFocus = manualFocusEnabled && minimumFocusDistance > 0f
        if (manualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it * minimumFocusDistance) }
        } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        if (manualExposureEnabled) {
            isoValue?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            exposureTimeNanos?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            // With AE off the frame rate comes from the frame duration.
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            exposureCompensation?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        }
        fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        currentCrop?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }

        meteringRegion?.let { region ->
            if (maxAfRegions > 0 && !manualFocus && minimumFocusDistance > 0f) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            if (maxAeRegions > 0 && !manualExposureEnabled) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }

        applyFlashToBuilder(builder)
    }

    private fun applyFlashToBuilder(builder: CaptureRequest.Builder) {
        if (!flashModeSupported) return
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    fun captureImage() {
        if (captureBlocked) {
            Log.d(TAG, "captureImage skipped: captureBlocked=true")
            return
        }
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                imageReaderSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }
            session.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture image: ${e.message}")
        }
    }

    /**
     * Apply a digital zoom level. [linearZoom] is in `[0, 1]` (0 = full frame, 1 = the
     * device's `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`). The crop is stored eagerly, so a call
     * made before the session is configured is applied by the first request.
     */
    @WorkerThread
    fun setLinearZoom(linearZoom: Float) {
        val clamped = linearZoom.coerceIn(0f, 1f)
        if (clamped != this.linearZoom) meteringRegion = null
        this.linearZoom = clamped
        currentCrop = computeCropForLinearZoom(clamped)
        submitRepeating("apply zoom")
    }

    private fun computeCropForLinearZoom(linearZoom: Float): Rect? {
        if (linearZoom <= 0f) return null
        val chars = characteristics ?: return null
        val range = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = 1f + (range - 1f) * linearZoom
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val centerX = sensor.width() / 2
        val centerY = sensor.height() / 2
        val halfW = (sensor.width() / (2f * zoom)).toInt()
        val halfH = (sensor.height() / (2f * zoom)).toInt()
        return Rect(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
    }

    /** Re-apply a zoom level on a fresh controller (no-op at 1.0x). */
    @WorkerThread
    fun reapplyLinearZoom(linearZoom: Float) {
        if (linearZoom > 0f) {
            setLinearZoom(linearZoom)
        }
    }

    /** Stop the repeating request (no more frames to any output). */
    fun stopPreview() {
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping preview: ${e.message}")
        }
    }

    fun closeCamera() {
        closed = true
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device: ${e.message}")
        }
        cameraDevice = null
    }

    companion object {
        const val TAG = "CameraController"

        /** Error code passed to [onError] when the device was disconnected. */
        const val ERROR_DISCONNECTED = -2

        /** Size of the tap-to-focus region relative to the visible sensor area. */
        private const val REGION_FRACTION = 0.12f
    }
}
