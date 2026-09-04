package com.dualcamerarecording.camera

import android.hardware.camera2.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.WorkerThread
import kotlin.math.abs

/**
 * Camera2 API controller for a single camera.
 *
 * Manages camera opening, capture session, manual controls (focus / ISO / exposure time),
 * and flash (torch) — rear-only at the UI layer but the controller itself is camera-agnostic.
 *
 * Key fixes ported from the AndroidCamera reference:
 *  - selectPreviewSize picks a camera output size matching the target resolution aspect,
 *    and the chosen size is exposed via [previewSize] so the caller (TextureView) can
 *    set its SurfaceTexture default buffer size and apply a FIT_CENTER transform matrix.
 *  - Manual focus toggles CONTROL_AF_MODE OFF/CONTINUOUS_VIDEO; manual exposure toggles
 *    CONTROL_AE_MODE OFF/ON. Passing -1 for focus/iso/exposure clears the value and
 *    re-enables the corresponding auto mode (the fix for "settings don't work / stay").
 *  - Flash: FLASH_MODE is the LAST key set on the request builder, and changing torch
 *    kicks the session with a one-shot capture() so the torch state actually takes effect
 *    on devices that don't honor a repeating-request-only FLASH_MODE change. The flash
 *    is only enabled if FLASH_INFO_AVAILABLE is true (probed per camera id).
 */
class CameraController(
    val cameraId: String,
    private val cameraManager: CameraManager,
    private val previewSurface: Surface?,
    private val recordSurface: Surface?,
    private val imageReaderSurface: Surface?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    /** Target landscape preview size (from StreamResolution) to match; null = pick best 4:3. */
    private val targetSize: Size? = null,
    /** Facing for convenience. */
    val facing: Int = android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
) {
    /** The preview size the camera actually uses; set after openCamera, read by the
     *  TextureView owner to set SurfaceTexture default buffer size + transform. */
    @Volatile
    var previewSize: Size? = null
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest? = null

    // Manual control state. -1 sentinel clears and returns to auto.
    private var isoValue: Int? = null
    private var exposureTimeNanos: Long? = null
    private var focusDistance: Float? = null
    private var exposureCompensation: Int? = null

    // Manual-mode toggles. When false the corresponding control reverts to its auto mode.
    var manualFocusEnabled: Boolean = false
        private set
    var manualExposureEnabled: Boolean = false
        private set

    /**
     * Last digital-zoom crop applied to the sensor active array; null = full frame.
     * Tracked so subsequent capture requests (e.g. after a focus change or target
     * rotation) re-apply the same crop and the zoom doesn't snap back to 1.0x.
     */
    private var currentCrop: android.graphics.Rect? = null

    /**
     * Linear zoom value in [0, 1] — 0 is no zoom, 1 is the device's max digital
     * zoom. Kept in sync with [currentCrop] so we can re-apply the crop on every
     * capture request (start preview, focus change, target rotation, etc.).
     */
    private var linearZoom: Float = 0f

    // Torch. null = not yet set; otherwise on/off.
    private var torchOn: Boolean = false
    private var flashAvailable: Boolean = false
    /**
     * Cached read of [CameraCharacteristics.FLASH_INFO_AVAILABLE] for the
     * current lens. The probe in [openCamera] checks both the logical id
     * and any physical sub-cameras (logical multi-cameras on modern phones
     * may report FLASH_INFO_AVAILABLE = false even when a physical
     * sub-camera has the flash unit). Mirrors the same flag in the
     * AndroidCamera reference (`Camera2StreamSession.flashModeSupported`).
     * Used to short-circuit any FLASH_MODE change on lenses without a
     * flash unit, which can deadlock the capture session on OEM aux lenses.
     */
    private var flashModeSupported: Boolean = false

    // When true, captureImage() is a no-op. Set by the activity while cameras are
    // being switched (front <-> rear), so an in-flight tap can't trigger a capture
    // against the old camera. Re-enabled once the new pair is set up.
    @Volatile
    var captureBlocked: Boolean = false

    var onCameraOpened: (() -> Unit)? = null
    var onCameraClosed: (() -> Unit)? = null
    var onError: ((Int, String) -> Unit)? = null

    @WorkerThread
    fun openCamera() {
        try {
            captureSession = null
            cameraDevice = null
            flashAvailable = flashModeSupported()
            // Same flag as the AndroidCamera reference. Used to skip FLASH_MODE
            // changes on lenses that don't expose a flash unit (ultrawide /
            // tele on some OEM devices accept the option but deadlock the
            // capture session when FLASH_MODE goes OFF after a TORCH cycle).
            flashModeSupported = flashAvailable
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    selectPreviewSize(device)
                    createCaptureSession()
                    handler.post { onCameraOpened?.invoke() }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    onCameraClosed?.invoke()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    try { device.close() } catch (e: Exception) {}
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

    /**
     * Pick the preview size the camera will use, without opening the camera. This lets the
     * caller (e.g. the Activity) set the SurfaceTexture buffer to the same size so the
     * camera and the texture view agree on the source dimensions. Without this the camera
     * may pick a size that doesn't match the buffer, leaving the image tiny or off-center
     * after the rotation/scale transform.
     */
    fun pickPreviewSize(): Size? {
        val chars = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.w(TAG, "pickPreviewSize: getCameraCharacteristics failed: ${e.message}")
            return null
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes: Array<Size> = run {
            val st = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            if (!st.isNullOrEmpty()) st
            else map.getOutputSizes(Surface::class.java) ?: return null
        }
        if (sizes.isEmpty()) return null
        val target = targetSize
        return if (target != null) {
            val exact = sizes.firstOrNull { it.width == target.width && it.height == target.height }
            if (exact != null) {
                exact
            } else {
                val targetRatio = target.width.toDouble() / target.height.toDouble()
                sizes.minByOrNull {
                    val r = it.width.toDouble() / it.height.toDouble()
                    abs(r - targetRatio) * 1000 - it.width
                } ?: sizes[0]
            }
        } else {
            val targetRatio = 4.0 / 3.0
            sizes.minByOrNull {
                val r = it.width.toDouble() / it.height.toDouble()
                abs(r - targetRatio) * 1000 - it.width
            } ?: sizes[0]
        }
    }

    private fun flashModeSupported(): Boolean {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            // For logical multi-cameras (a single logical id exposing multiple
            // physical sub-cameras) FLASH_INFO_AVAILABLE may be reported as
            // false on the logical id even when one of the physical sub-cameras
            // does have a flash unit. Without checking the physical sub-cameras
            // the user would see the "Flash" button do nothing on devices that
            // expose the rear camera as a logical camera (most modern phones).
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

    /**
     * Select the camera output size closest to [targetSize] (if provided), else the
     * largest 4:3 size. Falls back to the largest size overall.
     *
     * Uses the same selection logic as [pickPreviewSize] so the buffer size set by the
     * caller (via [pickPreviewSize]) and the size returned to the caller (via
     * [previewSize]) always agree. Without this the camera might pick a different size
     * than the one the SurfaceTexture was sized to, leaving the texture view mis-scaled.
     */
    private fun selectPreviewSize(device: CameraDevice) {
        val best = pickPreviewSize()
        previewSize = best
        // DIAGNOSTIC: log all supported sizes so we can see what the camera actually
        // accepts for SurfaceTexture output and compare with what the camera might
        // actually write.
        val chars = try { cameraManager.getCameraCharacteristics(cameraId) } catch (e: Exception) { null }
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedST = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        Log.d(TAG, "Selected preview size for $cameraId: ${best?.width}x${best?.height} " +
            "(flash=$flashAvailable) supportedST_count=${supportedST?.size ?: 0} " +
            "supportedST_sample=${supportedST?.take(8)?.joinToString { "${it.width}x${it.height}" }}")
    }

    @WorkerThread
    fun createCaptureSession() {
        val device = cameraDevice ?: run {
            Log.e(TAG, "Camera device not opened")
            return
        }

        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces.add(it) }
        recordSurface?.let { surfaces.add(it) }
        imageReaderSurface?.let { surfaces.add(it) }

        if (surfaces.isEmpty()) {
            Log.e(TAG, "No surfaces to create capture session")
            return
        }

        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    onError?.invoke(-1, "Capture session failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        }
    }

    @WorkerThread
    fun startPreview() {
        val session = captureSession ?: return
        try {
            // Target the record surface too when present so frames flow to the
            // MediaRecorder while recording. (A no-op when recordSurface is null.)
            val sensorOrientation = try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (e: Exception) { 0 }
            val jpegOrientation = (sensorOrientation - targetRotation * 90 + 360) % 360
            val builder = createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
            previewRequest = builder.build()
            session.setRepeatingRequest(previewRequest!!, null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview: ${e.message}")
        }
    }

    /**
     * Apply manual control settings.
     *
     * Sentinel -1 (for focus/iso/exposure) clears the stored value and reverts that
     * control to its auto mode — this is the fix for "unchecking the manual button
     * doesn't reset". null means "no change" (leave the stored value alone).
     *
     * focusEnabled / exposureEnabled toggle the AF/AE modes directly: when true the
     * mode is OFF (manual); when false the mode reverts to auto.
     *
     * Torch is fully independent of AF/AE (mirrors the AndroidCamera reference):
     * turning the manual adjustments on/off does NOT toggle the torch. The
     * previous implementation silently turned the torch off when callers passed
     * `flashMode = CONTROL_AE_MODE_ON`, which is the wrong constant for the
     * parameter (CONTROL_AE_MODE_* is the AE mode, not the flash mode) and
     * produced the "flash turns off the moment I touch another control" symptom.
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
            this.focusDistance = if (focusDistance < 0) null else focusDistance
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
            // Only gate the user's intent on whether the lens actually
            // supports a flash unit — `flashAvailable` may lag behind
            // `flashModeSupported` (the latter is the conservative probe
            // result we use to skip FLASH_MODE writes). Toggling the
            // torch on a lens with no flash unit would be a no-op anyway,
            // so we keep the user's intent recorded for when the flag
            // flips after the camera opens.
            this.torchOn = torchEnabled
        }

        if (cameraDevice != null && captureSession != null) {
            updatePreviewRequest()
        }
    }

    /** Toggle torch on/off. No-op if the camera has no flash. */
    /**
     * Update the preview target rotation to match the current display rotation.
     * The HAL rotates the sensor frames based on the value we set on
     * [CaptureRequest.JPEG_ORIENTATION] (offset by the sensor's natural
     * orientation from [CameraCharacteristics.SENSOR_ORIENTATION]). The preview
     * is therefore always upright in the view without any manual Matrix
     * rotation. Called whenever the activity's display rotation changes (e.g.
     * when toggling landscape mode).
     */
    @Volatile
    private var targetRotation: Int = android.view.Surface.ROTATION_0

    @WorkerThread
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        if (cameraDevice == null || captureSession == null) return
        try {
            val sensorOrientation = try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (e: Exception) { 0 }
            val jpegOrientation = (sensorOrientation - rotation * 90 + 360) % 360
            val builder = createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
            previewRequest = builder.build()
            captureSession?.setRepeatingRequest(previewRequest!!, null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set target rotation: ${e.message}")
        }
    }

    /**
     * Toggle torch on/off. No-op when the lens has no flash unit.
     *
     * Builds ONE complete capture request (re-applying AF / AE / ISO /
     * exposure / focus / FPS / crop) and pushes it via
     * [CameraCaptureSession.setRepeatingRequest] with `FLASH_MODE` applied
     * LAST in the builder. No capture() kicks, no OFF flush, no synthetic
     * tap-to-focus — those workarounds were all retired. The torch is
     * driven entirely through the capture session; on logical multi-cameras
     * we do not call `CameraManager.setTorchMode` (see [DualCameraRecorder]
     * for the reason). Focus state is preserved: this method only flips
     * `torchOn` and lets [createCaptureRequest] re-emit whatever AF / AE
     * / ISO / exposure values the user already has.
     */
    @WorkerThread
    fun setTorchMode(enabled: Boolean) {
        if (!flashModeSupported && enabled) {
            Log.w(TAG, "Torch requested but flash not supported on $cameraId")
        }
        torchOn = enabled
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        try {
            val builder = createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
            }
            session.setRepeatingRequest(builder.build(), null, handler)
            Log.d(TAG, "Torch mode=$enabled applied (FLASH_MODE last in builder)")
        } catch (e: Exception) {
            Log.w(TAG, "setTorchMode failed: ${e.message}")
        }
    }

    /**
     * Trigger an autofocus cycle at the given normalized sensor coordinates
     * (each in [0..1]). The AF region is set on a preview request, CONTROL_AF_TRIGGER
     * is fired with START, then a follow-up repeating request resets the trigger to
     * IDLE so the camera returns to continuous AF. Used by tap-to-focus when Manual
     * Focus is OFF (regardless of the Manual Adjustments checkbox state).
     *
     * No-op when manual focus is enabled (the user has locked focus on a fixed distance)
     * or when there is no camera session yet.
     *
     * Implementation note: the AF cycle is driven entirely through
     * [CameraCaptureSession.setRepeatingRequest] (never [CameraCaptureSession.capture]).
     * The previous implementation used one-shot session.capture() for the AF START
     * and the AF-state polling, which on some devices produced a visible "freeze"
     * of the preview stream and briefly dropped the torch (the camera re-evaluated
     * the FLASH_MODE on the one-shot request). Going through setRepeatingRequest
     * keeps the repeating preview stream alive throughout the AF cycle, so the user
     * sees no stutter and the torch stays on. Each step uses the same TEMPLATE_RECORD
     * + JPEG_ORIENTATION as the regular preview request, so the camera never
     * re-enters reconfiguration.
     */
    @WorkerThread
    fun triggerAutoFocus(normX: Float, normY: Float) {
        if (manualFocusEnabled) {
            Log.d(TAG, "triggerAutoFocus ignored: manual focus is enabled")
            return
        }
        val session = captureSession ?: run {
            Log.d(TAG, "triggerAutoFocus ignored: no capture session")
            return
        }
        val device = cameraDevice ?: run {
            Log.d(TAG, "triggerAutoFocus ignored: no camera device")
            return
        }
        try {
            // Clamp the normalized coordinates to the valid sensor range.
            val x = normX.coerceIn(0f, 1f)
            val y = normY.coerceIn(0f, 1f)

            // Convert the touch point to a sensor region. Use a small region so a precise
            // tap focuses only on what the user pointed at.
            val region = android.hardware.camera2.params.MeteringRectangle(
                /* x */ ((x * 2000f) - 100f).toInt().coerceIn(0, 2000),
                /* y */ ((y * 2000f) - 100f).toInt().coerceIn(0, 2000),
                /* width */ 200,
                /* height */ 200,
                android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX - 1
            )

            val sensorOrientation = try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (e: Exception) { 0 }
            val jpegOrientation = (sensorOrientation - targetRotation * 90 + 360) % 360

            // 1) Cancel any in-flight AF using a repeating request. Using
            //    setRepeatingRequest (rather than a one-shot capture) keeps the
            //    preview stream alive — the camera continues producing frames
            //    while it processes the AF cancel.
            val cancelBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyFlashToBuilder(this)
            }
            val cancelCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    r1: TotalCaptureResult
                ) {
                    // 2) After ONE cancelled frame, switch the repeating request
                    //    to the AF start frame. The camera consumes the START
                    //    trigger on the first frame and continues with the new
                    //    AF mode + region for the rest of the cycle.
                    triggerStartRepeating(s, device, region, jpegOrientation)
                }
            }
            session.setRepeatingRequest(cancelBuilder.build(), cancelCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "triggerAutoFocus failed: ${e.message}")
        }
    }

    private fun triggerStartRepeating(
        session: CameraCaptureSession,
        device: CameraDevice,
        region: android.hardware.camera2.params.MeteringRectangle,
        jpegOrientation: Int
    ) {
        try {
            val startBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                applyFlashToBuilder(this)
            }
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
                        // AF still scanning — re-apply the same repeating request
                        // so we keep getting capture results until the lens settles.
                        try {
                            session.setRepeatingRequest(startBuilder.build(), this, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "triggerStartRepeating poll failed: ${e.message}")
                            resumeContinuousAf(session, device, jpegOrientation)
                        }
                    } else {
                        resumeContinuousAf(session, device, jpegOrientation)
                    }
                }
            }
            session.setRepeatingRequest(startBuilder.build(), pollCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "triggerStartRepeating failed: ${e.message}")
        }
    }

    private fun resumeContinuousAf(
        session: CameraCaptureSession,
        device: CameraDevice,
        jpegOrientation: Int
    ) {
        try {
            val resume = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyFlashToBuilder(this)
            }
            // Restore the normal repeating preview stream. This is identical to
            // what [updatePreviewRequest] would have produced, so the camera does
            // not reconfigure and the torch (set via applyFlashToBuilder) stays on.
            session.setRepeatingRequest(resume.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "resumeContinuousAf failed: ${e.message}")
        }
    }

    private fun updatePreviewRequest() {
        val session = captureSession ?: return
        val previewSurf = previewSurface ?: return
        try {
            val sensorOrientation = try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (e: Exception) { 0 }
            val jpegOrientation = (sensorOrientation - targetRotation * 90 + 360) % 360
            val builder = createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurf)
                recordSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
            previewRequest = builder.build()
            session.setRepeatingRequest(previewRequest!!, null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview request: ${e.message}")
        }
    }

    /**
     * Build a capture request with the current manual settings. FLASH_MODE is applied
     * LAST (after AF/AE) — this ordering matters on some devices for the torch to light.
     *
     * AE mode is now controlled ONLY by [manualExposureEnabled]. Previously the
     * torch also forced AE off, which produced a near-black frame on first activation
     * (no exposure values had been computed yet) and made the rear camera look as if
     * it had jumped from Autofocus to Manual focus. The AndroidCamera reference
     * keeps AE on while the torch is on — the LED is driven entirely by FLASH_MODE,
     * independent of AE. This matches what Camera2 spec describes and is what most
     * stock camera apps do.
     */
    private fun createCaptureRequest(template: Int): CaptureRequest.Builder {
        val device = cameraDevice ?: error("Camera not opened")
        val builder = device.createCaptureRequest(template)

        // Auto/Manual focus. AF is independent of the torch: torch does not
        // need AF off to keep the LED lit, and toggling AF here was the
        // visible symptom reported as "flash turns the camera dark".
        if (manualFocusEnabled) {
            focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // Auto/Manual exposure. ONLY manualExposureEnabled gates AE off — see
        // the KDoc above for why the torch no longer forces AE off.
        if (manualExposureEnabled) {
            isoValue?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            exposureTimeNanos?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            exposureCompensation?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        }

        // Re-apply the current digital-zoom crop on every capture request. Without
        // this, any subsequent preview update (focus change, target rotation, torch
        // toggle) would clear SCALER_CROP_REGION and snap zoom back to 1.0x.
        currentCrop?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }

        // Flash is applied last and only if available, so it overrides correctly.
        applyFlashToBuilder(builder)

        return builder
    }

    private fun applyFlashToBuilder(builder: CaptureRequest.Builder) {
        // Skip FLASH_MODE entirely on lenses that don't expose a flash unit.
        // Some OEM aux (ultrawide / tele) lenses accept the option but deadlock
        // the capture session when FLASH_MODE_TORCH is followed by FLASH_MODE_OFF.
        // Mirrors the same guard in the AndroidCamera reference.
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
     * Apply a digital zoom level.
     *
     * [linearZoom] is in `[0, 1]`, where 0 = no zoom (full frame) and 1 = the device's
     * `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` factor. The math mirrors the AndroidCamera
     * reference: the sensor active array is cropped to a centred rectangle of size
     * `sensor / zoom` and the crop is pushed via `SCALER_CROP_REGION` on the same
     * TEMPLATE_RECORD preview request used for everything else.
     *
     * Implementation notes (also matching the reference):
     *  - The crop region is stored in [currentCrop] and re-applied on every subsequent
     *    capture request (see [createCaptureRequest]) so other manual-control changes
     *    (focus, ISO, exposure, torch) don't accidentally snap zoom back to 1.0x.
     *  - The full manual-control state is preserved on the builder (AF/AE mode, ISO,
     *    exposure, focus distance, FPS, flash) so toggling zoom never reverts the
     *    user to AUTO mode.
     *  - If the repeating request fails (e.g. on OEM HALs that drop the request when
     *    the crop is too small) we kick the session with a one-shot capture() and
     *    retry the repeating request.
     *  - **State is written before the device/session null-check**: the user-set
     *    [linearZoom] (and the computed [currentCrop]) are stored eagerly so that
     *    a `setLinearZoom` call made before the camera finishes opening is not
     *    lost. The Activity's reapply path (`DualCameraRecorder.reapplyLinearZoom`)
     *    is racy: it runs synchronously after `startPreview()` (which only spawns
     *    the async open thread) and previously the state was discarded because
     *    the device and session were still null. Storing the state eagerly means
     *    the FIRST `createCaptureRequest` after the session is configured (i.e.
     *    the very first preview frame) already carries the right crop, so the
     *    user never sees the preview snap back to 1.0x on fullscreen toggle /
     *    preview restart / recording start.
     */
    @WorkerThread
    fun setLinearZoom(linearZoom: Float) {
        val clamped = linearZoom.coerceIn(0f, 1f)
        this.linearZoom = clamped
        // Compute and cache the crop eagerly. Reading camera characteristics does
        // not require the camera to be open, so this works even when called from
        // a reapply that happens before `cameraDevice` is non-null.
        currentCrop = computeCropForLinearZoom(clamped)

        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val crop = currentCrop
        runCatching {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
                recordSurface?.let { addTarget(it) }
                if (crop != null) {
                    set(CaptureRequest.SCALER_CROP_REGION, crop)
                }
                // Preserve all manual settings so zoom changes never revert the
                // capture session to AUTO mode.
                if (manualFocusEnabled) {
                    focusDistance?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                if (manualExposureEnabled) {
                    isoValue?.let { set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                    exposureTimeNanos?.let { set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    exposureCompensation?.let { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
                }
                applyFlashToBuilder(this)
            }
            try {
                session.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Exception) {
                Log.w(TAG, "setLinearZoom: setRepeatingRequest failed, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, handler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
            }
        }.onFailure {
            Log.w(TAG, "Camera2 zoom failed: ${it.message}")
        }
    }

    /**
     * Compute the `SCALER_CROP_REGION` rectangle for a given linear zoom level.
     * Returns null when the value is 0 (full frame, no crop needed) or when the
     * camera characteristics cannot be read (the camera id is invalid or the
     * service is down). Reading characteristics is cheap and does not require
     * the camera to be open, so this can be called from a reapply that races
     * the asynchronous open callback.
     */
    private fun computeCropForLinearZoom(linearZoom: Float): android.graphics.Rect? {
        if (linearZoom <= 0f) return null
        val cm = cameraManager
        val chars = try {
            cm?.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            null
        } ?: return null
        val range = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = 1f + (range - 1f) * linearZoom
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val centerX = sensor.width() / 2
        val centerY = sensor.height() / 2
        val halfW = (sensor.width() / (2f * zoom)).toInt()
        val halfH = (sensor.height() / (2f * zoom)).toInt()
        return android.graphics.Rect(
            centerX - halfW,
            centerY - halfH,
            centerX + halfW,
            centerY + halfH
        )
    }

    /**
     * Apply the user-set zoom level to this controller's camera. Called from
     * [DualCameraRecorder] right after the camera is opened and the capture session
     * is configured, so the user-visible zoom state matches what the camera
     * actually does. The CameraController instance is replaced on every preview
     * restart, so the new one has no memory of the previous crop; the caller
     * (the Activity) supplies the value from its in-memory `frontLinearZoom` /
     * `rearLinearZoom` fields. No-op when [linearZoom] is 0 (the default value
     * already produces a 1.0x crop with no SCALER_CROP_REGION write).
     */
    @WorkerThread
    fun reapplyLinearZoom(linearZoom: Float) {
        if (linearZoom > 0f) {
            setLinearZoom(linearZoom)
        }
    }

    fun stopPreview() {
        try {
            captureSession?.abortCaptures()
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping preview: ${e.message}")
        }
    }

    fun closeCamera() {
        try { captureSession?.abortCaptures() } catch (e: Exception) {}
        try { captureSession?.stopRepeating() } catch (e: Exception) {}
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device: ${e.message}")
        }
        cameraDevice = null
        previewRequest = null
    }

    companion object {
        const val TAG = "CameraController"
    }
}
