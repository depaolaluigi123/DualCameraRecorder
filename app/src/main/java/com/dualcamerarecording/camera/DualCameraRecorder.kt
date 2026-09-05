package com.dualcamerarecording.camera

import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.graphics.SurfaceTexture
import com.dualcamerarecording.model.DualCameraConfig
import com.dualcamerarecording.model.StreamConfig
import com.dualcamerarecording.model.StreamOrientation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dual camera recorder using Camera2 + MediaRecorder.
 *
 * Records front + rear cameras simultaneously into two separate files
 * (front.mp4, rear.mp4) inside Movies/DualCameraRecording/<timestamp>/.
 *
 * Recording start order (the previous bug): the MediaRecorders MUST be prepared
 * and their surfaces added to the capture session BEFORE the recorders are
 * started. We now (1) prepare both recorders, (2) open the cameras with the
 * preview+record surfaces already in the session, (3) wait for both sessions to
 * be configured, (4) only then call MediaRecorder.start() — the order the
 * Camera2/MediaRecorder contract requires.
 */
class DualCameraRecorder {

    private var cameraManager: CameraManager? = null
    private var frontController: CameraController? = null
    private var rearController: CameraController? = null

    private var frontMediaRecorder: MediaRecorder? = null
    private var rearMediaRecorder: MediaRecorder? = null

    private var frontSurfaceTexture: SurfaceTexture? = null
    private var rearSurfaceTexture: SurfaceTexture? = null
    private var frontPreviewSurface: Surface? = null
    private var rearPreviewSurface: Surface? = null
    /**
     * Secondary preview surfaces (e.g. the fullscreen preview TextureView's
     * SurfaceTexture wrapped in a Surface). When non-null these are added to the
     * capture session AND every capture request alongside the main
     * [frontPreviewSurface] / [rearPreviewSurface], so the camera writes the
     * same frames to both pairs of previews continuously. Toggling which one
     * the user sees is then a pure TextureView visibility change — the camera
     * never needs to be reconfigured, so the `MediaRecorder` never loses frames
     * at the toggle.
     */
    private var frontPreviewSurfaceSecondary: Surface? = null
    private var rearPreviewSurfaceSecondary: Surface? = null
    private var frontRecordSurface: Surface? = null
    private var rearRecordSurface: Surface? = null

    private val isRecording = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val cameraHandlerThread: HandlerThread = HandlerThread("CameraHandlerThread").apply { start() }
    private val cameraHandler: Handler = Handler(cameraHandlerThread.looper)

    private var previewThread: Thread? = null
    private var recordingThread: Thread? = null
    private val previewInProgress = AtomicBoolean(false)

    private var config: DualCameraConfig? = null
    private var outputDir: File? = null

    private var singleCameraMode: Boolean = false
    var onSingleCameraMode: ((cameraPosition: String) -> Unit)? = null

    var onRecordingStarted: ((frontFile: File?, rearFile: File?) -> Unit)? = null
    var onRecordingStopped: ((frontFile: File?, rearFile: File?, error: Throwable?) -> Unit)? = null
    private var frontOutputFile: File? = null
    private var rearOutputFile: File? = null

    var onAudioCaptureStarted: (() -> Unit)? = null
    var onAudioCaptureStopped: (() -> Unit)? = null

    /**
     * Notified when the desired (front, rear) camera pair is not in the device's
     * concurrent-camera set and we had to pick an alternative pair. The new (front, rear)
     * are the IDs actually in use. Called on the main thread.
     */
    var onPairFallback: ((frontId: String, rearId: String) -> Unit)? = null

    /**
     * Called by the retry loop BEFORE the camera is opened with a fallback pair, so the
     * caller can resize the SurfaceTexture buffer to the picked size for the new camera.
     * The (front, rear) IDs are the ones that will be opened next. Called on the main
     * thread (handler.post). Use this to keep the buffer in sync with the actual camera
     * output size.
     */
    var onBeforeOpenFallback: ((frontId: String, rearId: String) -> Unit)? = null

    fun initialize(cameraManager: CameraManager) {
        this.cameraManager = cameraManager
    }

    /** Resolve the landscape target size for a StreamConfig (used to pick the preview size). */
    private fun targetSizeFor(config: StreamConfig?): Size? {
        if (config == null) return null
        return Size(config.resolution.landscapeWidth, config.resolution.landscapeHeight)
    }

    fun setupCameras(
        frontCameraId: String,
        rearCameraId: String,
        frontPreviewSurface: Surface,
        rearPreviewSurface: Surface,
        config: DualCameraConfig
    ) {
        this.frontPreviewSurface = frontPreviewSurface
        this.rearPreviewSurface = rearPreviewSurface
        this.config = config

        frontController = CameraController(
            cameraId = frontCameraId,
            cameraManager = cameraManager!!,
            previewSurface = frontPreviewSurface,
            recordSurface = null,
            imageReaderSurface = null,
            handler = cameraHandler,
            targetSize = targetSizeFor(config.frontConfig),
            facing = getCameraFacing(frontCameraId),
            previewSurfaceSecondary = frontPreviewSurfaceSecondary
        )
        rearController = CameraController(
            cameraId = rearCameraId,
            cameraManager = cameraManager!!,
            previewSurface = rearPreviewSurface,
            recordSurface = null,
            imageReaderSurface = null,
            handler = cameraHandler,
            targetSize = targetSizeFor(config.rearConfig),
            facing = getCameraFacing(rearCameraId),
            previewSurfaceSecondary = rearPreviewSurfaceSecondary
        )
    }

    /**
     * Set up preview only (live preview before recording). Closes existing controllers.
     *
     * If a recording is currently in progress (see [isRecording] / [frontRecordSurface] /
     * [rearRecordSurface]), the active `MediaRecorder` surfaces are kept in the new capture
     * session alongside the new preview surfaces. Without this, a main<->fullscreen toggle
     * while recording would tear down the recording cameras via [closePreview] and the
     * follow-up `setupPreview` would build a preview-only capture session, leaving the
     * `MediaRecorder` surfaces without any active session. Frames would stop flowing to
     * the recorders and the resulting video would freeze at the exact moment of the
     * preview-surface change (visible as a permanent freeze in both the front and rear
     * output files, even though the live previews keep working on the new surfaces).
     *
     * The `MediaRecorder` `Surface` is independent of the Camera2 capture session and
     * remains valid for the entire lifetime of the recorder, so it can be safely added to
     * the freshly configured session on the new preview surface set.
     *
     * The optional [frontPreviewSurfaceSecondary] / [rearPreviewSurfaceSecondary] are the
     * fullscreen preview surfaces. When provided, they're added to the capture session
     * AND every capture request alongside the main preview surfaces, so the camera writes
     * to both pairs of previews continuously. Toggling which pair the user sees is then
     * a pure TextureView visibility change — the camera is never reconfigured, so
     * toggling fullscreen during recording leaves the recorded video gap-free.
     */
    fun setupPreview(
        frontCameraId: String,
        rearCameraId: String,
        frontPreviewSurface: Surface,
        rearPreviewSurface: Surface,
        frontSurfaceTexture: SurfaceTexture? = null,
        rearSurfaceTexture: SurfaceTexture? = null,
        frontTargetSize: Size? = null,
        rearTargetSize: Size? = null,
        /**
         * Optional fullscreen preview surfaces. When non-null, the camera writes
         * the same frames to these as to the main preview pair, so a fullscreen
         * toggle later in the session requires no camera reconfiguration.
         */
        frontPreviewSurfaceSecondary: Surface? = null,
        rearPreviewSurfaceSecondary: Surface? = null
    ) {
        frontController?.stopPreview()
        frontController?.closeCamera()
        rearController?.stopPreview()
        rearController?.closeCamera()

        this.frontPreviewSurface = frontPreviewSurface
        this.rearPreviewSurface = rearPreviewSurface
        this.frontSurfaceTexture = frontSurfaceTexture
        this.rearSurfaceTexture = rearSurfaceTexture
        this.frontPreviewSurfaceSecondary = frontPreviewSurfaceSecondary
        this.rearPreviewSurfaceSecondary = rearPreviewSurfaceSecondary

        this.config = DualCameraConfig(
            frontCameraId = frontCameraId,
            rearCameraId = rearCameraId
        )

        // Preserve the MediaRecorder surfaces in the new capture session when a
        // recording is in progress. See the method KDoc above for the full
        // explanation of why this is needed during a main<->fullscreen toggle.
        val frontRecord = if (isRecording.get()) frontRecordSurface else null
        val rearRecord = if (isRecording.get()) rearRecordSurface else null

        frontController = CameraController(
            cameraId = frontCameraId,
            cameraManager = cameraManager!!,
            previewSurface = frontPreviewSurface,
            recordSurface = frontRecord,
            imageReaderSurface = null,
            handler = cameraHandler,
            targetSize = frontTargetSize,
            facing = getCameraFacing(frontCameraId),
            previewSurfaceSecondary = frontPreviewSurfaceSecondary
        )
        rearController = CameraController(
            cameraId = rearCameraId,
            cameraManager = cameraManager!!,
            previewSurface = rearPreviewSurface,
            recordSurface = rearRecord,
            imageReaderSurface = null,
            handler = cameraHandler,
            targetSize = rearTargetSize,
            facing = getCameraFacing(rearCameraId),
            previewSurfaceSecondary = rearPreviewSurfaceSecondary
        )
    }

    fun closePreview() {
        previewThread?.let { oldThread ->
            if (oldThread.isAlive) {
                oldThread.interrupt()
                // Was join(500) — interrupt() should make the thread die on its next
                // Thread.sleep / isInterrupted check (both well under 50 ms), so a
                // 50 ms cap is plenty and keeps the fullscreen toggle snappy.
                try { oldThread.join(50) } catch (e: InterruptedException) {}
            }
        }
        previewThread = null
        previewInProgress.set(false)

        frontController?.stopPreview()
        frontController?.closeCamera()
        rearController?.stopPreview()
        rearController?.closeCamera()
        frontController = null
        rearController = null
    }

    private fun getCameraFacing(cameraId: String): Int {
        val cm = cameraManager ?: return android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        return try {
            cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_FACING)
                ?: android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        } catch (e: Exception) {
            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        }
    }

    /**
     * Start preview by opening both cameras concurrently. If a camera fails to open, we log
     * a warning and tell the UI which camera is not working — but we do NOT auto-pick a
     * different one. The user is asked to change the camera themselves (via the spinner).
     */
    fun startPreview(
        desiredFront: String? = null,
        desiredRear: String? = null,
        alternateFronts: List<String> = emptyList(),
        alternateRears: List<String> = emptyList()
    ) {
        val front = frontController
        val rear = rearController
        if (front == null || rear == null) {
            Log.e(TAG, "Controllers not initialized")
            return
        }
        if (!previewInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "startPreview() already in progress, skipping duplicate call")
            return
        }

        previewThread?.let { oldThread ->
            if (oldThread.isAlive) {
                oldThread.interrupt()
                // Was join(500) — interrupt() should reap the thread within milliseconds
                // on its next Thread.sleep / isInterrupted check. 50 ms cap is plenty.
                try { oldThread.join(50) } catch (e: InterruptedException) {}
            }
        }

        singleCameraMode = false

        val f0 = desiredFront ?: front.cameraId
        val r0 = desiredRear ?: rear.cameraId

        val thread = Thread {
            try {
                val localCm = cameraManager
                if (localCm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        val concurrent = localCm.concurrentCameraIds
                        if (concurrent.isEmpty()) {
                            Log.w(TAG, "Device advertises no concurrentCameraIds")
                        } else {
                            Log.d(TAG, "Device concurrent pairs: $concurrent")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking concurrent camera support: ${e.message}")
                    }
                }

                val fC = frontController
                val rC = rearController
                if (fC == null || rC == null) {
                    Log.e(TAG, "Controllers missing at start of preview")
                    return@Thread
                }

                // Attach listeners to the current controllers.
                fC.onCameraOpened = { fC.startPreview() }
                rC.onCameraOpened = { rC.startPreview() }
                fC.onError = { code, msg ->
                    Log.w(TAG, "Front camera '$f0' is not working: $msg (code=$code). Please change the camera.")
                    handler.post { onSingleCameraMode?.invoke("front-not-working") }
                }
                rC.onError = { code, msg ->
                    Log.w(TAG, "Rear camera '$r0' is not working: $msg (code=$code). Please change the camera.")
                    handler.post { onSingleCameraMode?.invoke("rear-not-working") }
                }

                // Try to open both cameras. We open them in sequence (front first, then rear) because
                // on most devices opening two cameras in the same callback can race the
                // HAL. The 10 ms gap is the minimum the HAL needs to register the first
                // open before the second; the previous 50 ms was overkill and added up
                // to a noticeable pause on fast fullscreen toggles.
                try { fC.openCamera() } catch (e: Exception) { Log.e(TAG, "openCamera front threw: ${e.message}") }
                Thread.sleep(10)
                try { rC.openCamera() } catch (e: Exception) { Log.e(TAG, "openCamera rear threw: ${e.message}") }

                var waitTime = 0
                while (waitTime < 3000 && (!fC.isReady() || !rC.isReady())) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    // Poll every 10 ms (was 50 ms) so a fast fullscreen toggle that
                    // interrupts this thread unblocks within a few ms, not 50 ms.
                    Thread.sleep(10)
                    waitTime += 10
                }

                val frontReady = fC.isReady()
                val rearReady = rC.isReady()

                if (frontReady && rearReady) {
                    Log.d(TAG, "Both cameras ready: front=$f0 rear=$r0")
                    return@Thread
                }

                // One or both cameras failed. Log a warning; do NOT auto-fallback to a
                // different camera pair — the user asked us not to silently swap cameras.
                if (!frontReady && rearReady) {
                    Log.w(TAG, "Front camera '$f0' is not working. Rear camera '$r0' is open. " +
                        "Please change the front camera via the spinner.")
                    handler.post { onSingleCameraMode?.invoke("front-not-working") }
                    return@Thread
                }
                if (frontReady && !rearReady) {
                    Log.w(TAG, "Rear camera '$r0' is not working. Front camera '$f0' is open. " +
                        "Please change the rear camera via the spinner.")
                    handler.post { onSingleCameraMode?.invoke("rear-not-working") }
                    return@Thread
                }

                Log.w(TAG, "Neither camera is working. front='$f0', rear='$r0'. " +
                    "Please change one or both cameras via the spinners.")
                handler.post { onSingleCameraMode?.invoke("both-not-working") }
            } catch (e: Exception) {
                Log.e(TAG, "Error opening cameras: ${e.message}")
                handler.post { onSingleCameraMode?.invoke("error") }
            } finally {
                previewInProgress.set(false)
                previewThread = null
            }
        }
        previewThread = thread
        thread.start()
    }

    /** Returns the set of concurrent-camera pairs the device advertises (API 30+). */
    private fun concurrentPairs(cm: CameraManager?): Set<Set<String>> {
        if (cm == null) return emptySet()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return emptySet()
        return try {
            cm.concurrentCameraIds ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Error reading concurrentCameraIds: ${e.message}")
            emptySet()
        }
    }

    /** True if the (front, rear) pair is in the device's concurrent set. Empty set = unknown
     *  (we assume OK so we don't reject a working combination just because the API is missing). */
    private fun isConcurrent(cm: CameraManager?, frontId: String, rearId: String): Boolean {
        val pairs = concurrentPairs(cm)
        if (pairs.isEmpty()) return true
        return pairs.any { it.contains(frontId) && it.contains(rearId) }
    }

    /**
     * Given a desired (front, rear) pair, find one the device supports concurrently.
     * Tries same front + alternate rears first, then alternate fronts + desired rear.
     * Returns the original pair on failure — the caller will still attempt to open it
     * and may fall back to single-camera mode.
     *
     * Exposed (public) so the Activity can size the SurfaceTexture buffers for the
     * pair that will actually be opened, BEFORE the camera is opened.
     */
    fun pickWorkingPair(
        desiredFront: String,
        desiredRear: String,
        allFronts: List<String>,
        allRears: List<String>
    ): Pair<String, String> {
        val cm = cameraManager
        return pickWorkingPairInternal(cm, desiredFront, desiredRear, allFronts, allRears)
    }

    private fun pickWorkingPairInternal(
        cm: CameraManager?,
        desiredFront: String,
        desiredRear: String,
        allFronts: List<String>,
        allRears: List<String>
    ): Pair<String, String> {
        if (isConcurrent(cm, desiredFront, desiredRear)) {
            return desiredFront to desiredRear
        }
        // Try same front, alternate rear.
        for (r in allRears) {
            if (r == desiredRear) continue
            if (isConcurrent(cm, desiredFront, r)) {
                Log.w(TAG, "Fallback: rear $desiredRear -> $r (works concurrently with front=$desiredFront)")
                return desiredFront to r
            }
        }
        // Try alternate front with desired rear.
        for (f in allFronts) {
            if (f == desiredFront) continue
            if (isConcurrent(cm, f, desiredRear)) {
                Log.w(TAG, "Fallback: front $desiredFront -> $f (works concurrently with rear=$desiredRear)")
                return f to desiredRear
            }
        }
        Log.w(TAG, "No concurrent pair found among fronts=$allFronts rears=$allRears")
        return desiredFront to desiredRear
    }

    /**
     * Start recording from both cameras with shared audio.
     *
     * Order (the fix for the non-working record button):
     *   1. Build the output dir + files.
     *   2. Prepare both MediaRecorders (prepare() — not start yet).
     *   3. Close the preview controllers.
     *   4. Recreate the controllers WITH the recorder surfaces, so the capture session
     *      includes the record surface.
     *   5. Open both cameras; when each session is configured the repeating request
     *      targets preview + record surfaces.
     *   6. Wait for both cameras ready.
     *   7. Only then call MediaRecorder.start() on each (the surface is now a known
     *      session output). Fire onRecordingStarted.
     */
    fun startRecording(
        frontConfig: StreamConfig,
        rearConfig: StreamConfig,
        screenOrientation: StreamOrientation = StreamOrientation.PORTRAIT,
        audioBitrateKbps: Int = 128,
        audioSampleRateHz: Int = 44100
    ): FilePair? {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Already recording")
            return null
        }

        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        // Recordings land under Movies/DualCameraRecording/<timestamp>/
        val moviesRoot = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
        outputDir = File(moviesRoot, "DualCameraRecording/$timestamp")
        outputDir?.mkdirs()

        frontOutputFile = File(outputDir, "front.mp4")
        rearOutputFile = File(outputDir, "rear.mp4")
        if (outputDir == null) {
            isRecording.set(false)
            onRecordingStopped?.invoke(null, null, IllegalArgumentException("Failed to create output directory"))
            return null
        }

        val frontConfigOriented = frontConfig.copy(streamOrientation = screenOrientation)
        val rearConfigOriented = rearConfig.copy(streamOrientation = screenOrientation)

        try {
            // 1-2. Prepare recorders. prepare() is called inside createMediaRecorder().
            // `isFront` is forwarded so the orientation hint can compensate for the
            // front-facing sensor being mounted upside-down relative to the rear sensor
            // (see createMediaRecorder). Without this the recorded front-camera video
            // plays back rotated 180°. The preview is unaffected — the orientationHint
            // is MP4 playback metadata only, while preview rotation is driven by the
            // CaptureRequest's JPEG_ORIENTATION + the SurfaceTexture transform matrix.
            frontMediaRecorder = createMediaRecorder(frontConfigOriented, frontOutputFile!!, audioBitrateKbps, audioSampleRateHz, isFront = true)
            rearMediaRecorder = createMediaRecorder(rearConfigOriented, rearOutputFile!!, audioBitrateKbps, audioSampleRateHz, isFront = false)

            frontRecordSurface = frontMediaRecorder?.surface
            rearRecordSurface = rearMediaRecorder?.surface

            val frontCameraId = config?.frontCameraId ?: frontConfigOriented.cameraId
            val rearCameraId = config?.rearCameraId ?: rearConfigOriented.cameraId
            if (frontCameraId.isEmpty() || rearCameraId.isEmpty()) {
                throw IllegalStateException("Camera IDs not configured")
            }

            // 3. Close existing preview controllers.
            frontController?.stopPreview()
            rearController?.stopPreview()
            frontController?.closeCamera()
            rearController?.closeCamera()

            // 4. Recreate controllers with the recorder surfaces in the session.
            // The secondary (fullscreen) preview surfaces are kept in the session
            // too, so a fullscreen toggle during recording is a pure view-level
            // change with no camera reconfiguration — the MediaRecorder sees a
            // continuous frame stream and the output video has no gap.
            frontController = CameraController(
                cameraId = frontCameraId,
                cameraManager = cameraManager!!,
                previewSurface = frontPreviewSurface!!,
                recordSurface = frontRecordSurface,
                imageReaderSurface = null,
                handler = cameraHandler,
                targetSize = targetSizeFor(frontConfigOriented),
                facing = getCameraFacing(frontCameraId),
                previewSurfaceSecondary = frontPreviewSurfaceSecondary
            )
            rearController = CameraController(
                cameraId = rearCameraId,
                cameraManager = cameraManager!!,
                previewSurface = rearPreviewSurface!!,
                recordSurface = rearRecordSurface,
                imageReaderSurface = null,
                handler = cameraHandler,
                targetSize = targetSizeFor(rearConfigOriented),
                facing = getCameraFacing(rearCameraId),
                previewSurfaceSecondary = rearPreviewSurfaceSecondary
            )

            // Apply manual controls + torch up front (so the very first frames are correct).
            applyManualControls(frontController!!, frontConfigOriented, isFront = true)
            applyManualControls(rearController!!, rearConfigOriented, isFront = false)
            rearController!!.setTorchMode(rearConfigOriented.flashEnabled)

            onAudioCaptureStarted?.invoke()

            var frontOpened = false
            var rearOpened = false
            frontController!!.onCameraOpened = { frontOpened = true; Log.d(TAG, "Front camera opened for recording") }
            rearController!!.onCameraOpened = { rearOpened = true; Log.d(TAG, "Rear camera opened for recording") }
            frontController!!.onError = { code, msg -> Log.e(TAG, "Front camera error during recording: $msg (code=$code)") }
            rearController!!.onError = { code, msg ->
                Log.e(TAG, "Rear camera error during recording: $msg (code=$code)")
                if (!singleCameraMode) {
                    singleCameraMode = true
                    onSingleCameraMode?.invoke("front")
                }
            }

            val recordThread = Thread {
                try {
                    frontController!!.openCamera()
                    var waitTime = 0
                    while (waitTime < 1500 && !frontController!!.isReady()) {
                        if (Thread.currentThread().isInterrupted) break
                        // Was sleep(50) — 10 ms is fine on a worker thread and lets a
                        // stopRecording() interrupt unblock within ~10 ms instead of ~50 ms.
                        Thread.sleep(10); waitTime += 10
                    }
                    // Was sleep(150) between front/rear opens — 30 ms is enough for the
                    // HAL to register the first open before the second.
                    Thread.sleep(30)
                    rearController!!.openCamera()
                    waitTime = 0
                    while (waitTime < 1500 && !rearController!!.isReady()) {
                        if (Thread.currentThread().isInterrupted) break
                        Thread.sleep(10); waitTime += 10
                    }

                    // 7. Session(s) configured with the record surface — start the recorders.
                    if (frontController!!.isReady()) {
                        try { frontMediaRecorder?.start() } catch (e: Exception) { Log.e(TAG, "front recorder start failed: ${e.message}") }
                    }
                    if (rearController!!.isReady()) {
                        try { rearMediaRecorder?.start() } catch (e: Exception) { Log.e(TAG, "rear recorder start failed: ${e.message}") }
                    }

                    if (frontController!!.isReady() && rearController!!.isReady()) {
                        onRecordingStarted?.invoke(frontOutputFile!!, rearOutputFile!!)
                    } else if (frontController!!.isReady()) {
                        singleCameraMode = true
                        onSingleCameraMode?.invoke("front")
                        // Make sure a (possibly empty) rear file exists so callers see a path.
                        try { rearOutputFile?.createNewFile() } catch (_: Exception) {}
                        onRecordingStarted?.invoke(frontOutputFile, null)
                    } else if (rearController!!.isReady()) {
                        singleCameraMode = true
                        onSingleCameraMode?.invoke("rear")
                        try { frontOutputFile?.createNewFile() } catch (_: Exception) {}
                        onRecordingStarted?.invoke(null, rearOutputFile)
                    } else {
                        onRecordingStopped?.invoke(null, null, IllegalStateException("Neither camera opened"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening cameras for recording: ${e.message}")
                    onRecordingStopped?.invoke(null, null, e)
                }
            }
            recordingThread = recordThread
            recordThread.start()

            return FilePair(frontOutputFile!!, rearOutputFile!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            stopRecording()
            onRecordingStopped?.invoke(null, null, e)
            return null
        }
    }

    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return

        recordingThread?.let { rThread ->
            if (rThread.isAlive) {
                rThread.interrupt()
                // Was join(500) — interrupt() makes the thread die on its next sleep
                // check. 50 ms cap keeps stopRecording snappy.
                try { rThread.join(50) } catch (e: InterruptedException) {}
            }
        }
        recordingThread = null

        try { frontMediaRecorder?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping front recorder: ${e.message}") }
        try { rearMediaRecorder?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping rear recorder: ${e.message}") }

        onAudioCaptureStopped?.invoke()

        frontController?.closeCamera()
        rearController?.closeCamera()

        releaseMediaRecorders()

        onRecordingStopped?.invoke(frontOutputFile, rearOutputFile, null)
    }

    /**
     * Swap the preview surfaces on the active capture sessions WITHOUT closing the
     * camera devices. Used by the main<->fullscreen transition path while a recording
     * is in progress, so the `MediaRecorder` keeps receiving frames throughout the
     * toggle (the resulting video has no freeze at the toggle point).
     *
     * **Deprecated for runtime use.** The capture session is now created with BOTH
     * the main and the fullscreen preview surfaces from the start, so the camera
     * writes to both pairs of previews continuously and the activity can switch
     * which one the user sees with a pure TextureView visibility toggle. This
     * method is kept as a no-op for source-compatibility with the previous
     * swap-based flow — any caller (e.g. a future test) is a no-op now because
     * the camera no longer needs the swap. The original
     * (close-session + recreate) implementation is still in
     * [CameraController.swapPreviewSurface] if it's ever needed again.
     *
     * Safe to call when no recording is in progress.
     */
    fun swapPreviewSurfaces(frontSurface: Surface, rearSurface: Surface) {
        // The dual-surface design means the camera is always already writing to
        // the new surfaces — there is nothing to swap. We still update the
        // stored references in case any caller relies on the side effect.
        this.frontPreviewSurface = frontSurface
        this.rearPreviewSurface = rearSurface
        Log.d(TAG, "swapPreviewSurfaces: no-op (dual-surface session keeps both pairs alive)")
    }

    private fun applyManualControls(controller: CameraController, config: StreamConfig, isFront: Boolean) {
        val manual = config.manualControl
        controller.applyManualControls(
            iso = manual.iso,
            exposureTimeNanos = manual.exposureTimeNanos,
            focusDistance = manual.focusDistance,
            exposureCompensation = manual.exposureCompensation,
            torchEnabled = if (!isFront) config.flashEnabled else null,
            focusEnabled = manual.focusEnabled,
            exposureEnabled = manual.isoEnabled || manual.exposureEnabled
        )
    }

    /**
     * Create and prepare a MediaRecorder for a config + output file.
     * prepare() is called here; start() is deferred to after the capture session is configured.
     */
    private fun createMediaRecorder(
        config: StreamConfig,
        outputFile: File,
        audioBitrateKbps: Int,
        audioSampleRateHz: Int,
        isFront: Boolean
    ): MediaRecorder {
        val mr = MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(outputFile.absolutePath)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // --- Audio settings (from the user's selected values) ---
        // The MediaRecorder treats these as hints and may pick a different
        // value if the encoder / source do not support the requested one,
        // but in practice the AAC encoder honors the requested bitrate and
        // the AudioRecord source honors the requested sample rate on every
        // device we ship to. The values are taken from the persisted
        // PreferencesRepository via CameraSettingsStore.
        mr.setAudioSamplingRate(audioSampleRateHz)
        mr.setAudioEncodingBitRate(audioBitrateKbps * 1000)

        // --- Video settings (from the per-camera StreamConfig) ---
        val resolution = config.resolution
        val orientation = config.streamOrientation
        // Always encode in the camera sensor's native (landscape) orientation so the
        // encoder never has to rotate frames. The orientationHint tells the player to
        // rotate for portrait playback — landscape playback is unrotated. Using
        // landscape dimensions for the encoder in BOTH orientations was the fix for the
        // "Landscape-mode UI but portrait-recorded video" bug: the previous code wrote
        // 1280x960 in landscape mode AND set orientationHint=90, so the player rotated
        // the wide frame by 90° and played it back as 960x1280 (vertical). Now the
        // landscape frame is written as-is and the hint is 0, so it plays back as
        // 1280x960 (horizontal). Portrait recordings still get hint=90 and play back
        // as 960x1280.
        mr.setVideoSize(resolution.landscapeWidth, resolution.landscapeHeight)
        val bitrate = if (config.bitrate.bps > 0) config.bitrate.bps else 8_000_000
        mr.setVideoEncodingBitRate(bitrate)
        mr.setVideoFrameRate(config.fps.value)

        // The base hint rotates the encoded frame to match the requested display
        // orientation: 0 in landscape (frame is already wide), 90 in portrait (player
        // rotates 90° CCW to display tall).
        val baseHint = if (orientation.isLandscape) 0 else 90
        // Front-facing sensors are physically mounted upside-down relative to the rear
        // sensor (typical sensor orientation 270° vs 90°), so the encoded frame from
        // the front camera is rotated 180° compared to the rear-camera frame. We add
        // 180° to the hint for the front camera so the player rotates the recorded
        // front-camera video to the correct upright orientation. This is metadata only
        // — it does NOT affect the live preview, which is driven by the HAL via
        // JPEG_ORIENTATION on the CaptureRequest plus the SurfaceTexture transform
        // matrix in MainActivity.applyPreviewTransform. Modulo 360 keeps the hint in
        // the documented supported range {0, 90, 180, 270}.
        val orientationHint = if (isFront) (baseHint + 180) % 360 else baseHint
        mr.setOrientationHint(orientationHint)

        Log.d(TAG, "createMediaRecorder: file=${outputFile.name} " +
            "resolution=${resolution.landscapeWidth}x${resolution.landscapeHeight} " +
            "(display orientation=$orientation, isFront=$isFront, hint=${orientationHint}°) " +
            "fps=${config.fps.value} videoBitrate=$bitrate bps " +
            "audioBitrate=${audioBitrateKbps} kbps audioSampleRate=$audioSampleRateHz Hz")

        mr.prepare()
        return mr
    }

    private fun releaseMediaRecorders() {
        try { frontMediaRecorder?.reset(); frontMediaRecorder?.release() } catch (e: Exception) {}
        try { rearMediaRecorder?.reset(); rearMediaRecorder?.release() } catch (e: Exception) {}
        frontMediaRecorder = null
        rearMediaRecorder = null
    }

    /**
     * Apply manual controls to a running camera. Sentinel -1 clears a field back to auto;
     * null leaves it unchanged.
     *
     * Note: `flashMode` is intentionally NOT exposed — passing an AE mode constant
     * through a flash-mode parameter was a long-standing footgun that turned the
     * torch off the moment a caller touched another manual control. The torch is
     * toggled exclusively via [setTorchMode] / [setRearCameraTorch], which drive
     * the [CaptureRequest.FLASH_MODE_TORCH] key on the rear capture session only.
     *
     * The OS-level [CameraManager.setTorchMode] API is intentionally NOT used
     * anymore (see [setRearCameraTorch] KDoc for the reason — on logical
     * multi-cameras the two APIs target different camera ids and the OS-level
     * call is silently a no-op while the session still has TORCH set, which
     * caused the "flash can't be turned off" bug). The AndroidCamera reference
     * (`Camera2StreamSession.setFlashTorch`) follows the same session-only
     * approach.
     *
     * Focus state preservation: this method does NOT touch the torch. Toggling
     * focus manual/auto, ISO, exposure time or exposure compensation leaves
     * `torchOn` unchanged. Likewise, [setTorchMode] never touches
     * `manualFocusEnabled` / `manualExposureEnabled` / `focusDistance` /
     * `isoValue` / `exposureTimeNanos` / `exposureCompensation` — the two
     * paths are independent.
     */
    fun setManualControls(
        isFront: Boolean,
        iso: Int? = null,
        exposureTimeNanos: Long? = null,
        focusDistance: Float? = null,
        exposureCompensation: Int? = null,
        torchEnabled: Boolean? = null,
        focusEnabled: Boolean? = null,
        exposureEnabled: Boolean? = null
    ) {
        val controller = if (isFront) frontController else rearController
        controller?.applyManualControls(
            iso = iso,
            exposureTimeNanos = exposureTimeNanos,
            focusDistance = focusDistance,
            exposureCompensation = exposureCompensation,
            torchEnabled = torchEnabled,
            focusEnabled = focusEnabled,
            exposureEnabled = exposureEnabled
        )
    }

    fun setTorchMode(isFront: Boolean, enabled: Boolean) {
        val controller = if (isFront) frontController else rearController
        controller?.setTorchMode(enabled)
    }

    /**
     * Apply a digital zoom level to one of the two cameras.
     *
     * [linearZoom] is in `[0, 1]` (0 = no zoom, 1 = max digital zoom). The value is
     * applied to the active controller via [CameraController.setLinearZoom], which
     * crops the sensor active array to a centred rectangle and pushes the crop via
     * `SCALER_CROP_REGION`. The controller remembers the value across session
     * rebuilds (see [CameraController.reapplyLinearZoom]).
     *
     * No-op when the chosen camera's controller is not currently open (e.g. before
     * the first preview starts, or after [closePreview] / [release]).
     */
    fun setLinearZoom(isFront: Boolean, linearZoom: Float) {
        val controller = if (isFront) frontController else rearController
        controller?.setLinearZoom(linearZoom)
    }

    /**
     * Re-apply the user-set zoom level on both cameras. Called by
     * [com.dualcamerarecording.MainActivity] right after a fresh preview / recording
     * session is set up, so the zoom survives a camera restart (each preview
     * restart creates fresh controllers that have no memory of the previous crop).
     *
     * The values are supplied by the Activity, which is the single owner of the
     * user-facing zoom state (it survives fullscreen toggles inside the Activity
     * instance). The new controllers have no way of knowing what zoom level was
     * previously chosen, so passing the value explicitly is the only way to keep
     * the camera output in sync with the SeekBar.
     *
     * Each controller is a no-op when its own [linearZoom] is 0 (1.0× crop is the
     * default and produces no `SCALER_CROP_REGION` write).
     */
    fun reapplyLinearZoom(frontLinearZoom: Float, rearLinearZoom: Float) {
        frontController?.reapplyLinearZoom(frontLinearZoom)
        rearController?.reapplyLinearZoom(rearLinearZoom)
    }

    /**
     * Toggle the torch of the REAR camera. This is the dedicated API the UI's
     * flash button calls — it ALWAYS targets the rear camera (which is the
     * only flash unit on the phone) regardless of which physical camera is
     * currently selected as "rear" in the spinners.
     *
     * Implementation:
     *  1. Picks a camera id that actually exposes a flash unit. For a logical
     *     multi-camera, the logical id may report FLASH_INFO_AVAILABLE = false
     *     even though one of its physical sub-cameras does have a flash unit.
     *     In that case we use the physical sub-camera id so the torch actually
     *     turns on.
     *  2. Drives the LED entirely through [CameraController.setTorchMode], which
     *     applies `FLASH_MODE_TORCH` / `FLASH_MODE_OFF` on the active capture
     *     session. We intentionally do NOT call [CameraManager.setTorchMode]:
     *     on logical multi-cameras the two APIs target different camera ids
     *     (the OS-level one wants the physical sub-camera, the session-level
     *     one wants the logical camera) and on some OEM HALs the OS-level
     *     call is silently ignored while the capture session still has
     *     `FLASH_MODE_TORCH` set — the visible symptom was "once the flash is
     *     on, it cannot be turned off". The AndroidCamera reference
     *     (`Camera2StreamSession.setFlashTorch`) follows the same session-only
     *     approach for the same reason.
     *  3. The controller also remembers the user's intent when called before
     *     the capture session is configured (right after a camera restart) so
     *     the first [CameraController.startPreview] (and the explicit
     *     `applyFlashStateAfterCameraStart()` from the activity) re-arms the
     *     LED on the freshly opened session.
     *
     * Returns true if a flash unit was found for the selected rear camera,
     * false otherwise. The boolean does NOT reflect whether the LED actually
     * lit up — see the controller logs for that.
     */
    fun setRearCameraTorch(rearCameraId: String, enabled: Boolean): Boolean {
        if (rearCameraId.isEmpty()) {
            Log.w(TAG, "setRearCameraTorch: empty rear camera id")
            return false
        }
        val cm = cameraManager ?: run {
            Log.w(TAG, "setRearCameraTorch: cameraManager not initialized")
            return false
        }
        val torchCameraId = findRearCameraWithFlash(cm, rearCameraId)
        if (torchCameraId == null) {
            Log.w(TAG, "setRearCameraTorch: no camera with flash found for $rearCameraId")
            return false
        }
        Log.d(TAG, "setRearCameraTorch: logical=$rearCameraId flashUnit=$torchCameraId enabled=$enabled (session-only)")
        // Drive the LED purely through the capture session. See KDoc above for
        // why we no longer call CameraManager.setTorchMode here.
        rearController?.setTorchMode(enabled)
        return true
    }

    /**
     * Return the camera id (logical or physical) that should be used to control
     * the flash for the given rear camera. The given id is returned if it
     * advertises a flash unit. Otherwise we look at the physical sub-cameras of
     * a logical multi-camera and return the first one that has a flash unit.
     * Returns null if no flash unit can be found in this camera group.
     */
    private fun findRearCameraWithFlash(cm: CameraManager, cameraId: String): String? {
        val chars = try { cm.getCameraCharacteristics(cameraId) } catch (e: Exception) {
            Log.w(TAG, "findRearCameraWithFlash: getCameraCharacteristics($cameraId) failed: ${e.message}")
            return null
        }
        if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == java.lang.Boolean.TRUE) {
            return cameraId
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physicalIds = try { chars.physicalCameraIds } catch (e: Exception) { emptySet() }
            for (pid in physicalIds) {
                try {
                    val pChars = cm.getCameraCharacteristics(pid)
                    if (pChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == java.lang.Boolean.TRUE) {
                        Log.d(TAG, "findRearCameraWithFlash: using physical sub-camera $pid (logical=$cameraId) for flash")
                        return pid
                    }
                } catch (e: Exception) { /* skip this physical */ }
            }
        }
        return null
    }

    /**
     * True when no camera controller is currently open. The UI uses this to
     * wait until the previous preview is fully torn down before starting a new
     * one (e.g. when switching between main and fullscreen).
     */
    fun isClosed(): Boolean = frontController == null && rearController == null

    /** Trigger a one-shot autofocus cycle on the front camera at the given normalized coords. */
    fun triggerAutoFocusFront(normX: Float, normY: Float) {
        frontController?.triggerAutoFocus(normX, normY)
    }

    /** Trigger a one-shot autofocus cycle on the rear camera at the given normalized coords. */
    fun triggerAutoFocusRear(normX: Float, normY: Float) {
        rearController?.triggerAutoFocus(normX, normY)
    }

    /**
     * Block (or unblock) image capture on both cameras. While blocked, calls to
     * [CameraController.captureImage] are dropped. Used during camera switching so an
     * in-flight tap can't capture from the old camera while the new pair is being set up.
     */
    fun setCaptureBlocked(blocked: Boolean) {
        frontController?.captureBlocked = blocked
        rearController?.captureBlocked = blocked
    }

    /**
     * Forward the current display rotation to the camera controllers so the HAL
     * applies the right rotation to preview frames. Call this whenever the
     * activity's display rotation changes (e.g. when toggling landscape mode).
     * Rotation is a [Surface] rotation constant: 0, 1, 2, or 3.
     */
    fun setTargetRotation(rotation: Int) {
        frontController?.setTargetRotation(rotation)
        rearController?.setTargetRotation(rotation)
    }

    fun isRecording(): Boolean = isRecording.get()
    fun isSingleCameraMode(): Boolean = singleCameraMode

    /** Actual negotiated front preview size (null until the session is configured). */
    fun frontPreviewSize(): Size? = frontController?.previewSize

    /** Actual negotiated rear preview size (null until the session is configured). */
    fun rearPreviewSize(): Size? = rearController?.previewSize

    fun release() {
        if (isRecording.get()) stopRecording()
        frontController?.stopPreview()
        rearController?.stopPreview()
        frontController?.closeCamera()
        rearController?.closeCamera()
        frontController = null
        rearController = null
        releaseMediaRecorders()
        cameraManager = null
        cameraHandlerThread.quitSafely()
    }

    data class FilePair(val front: File, val rear: File)

    companion object {
        const val TAG = "DualCameraRecorder"
    }
}
