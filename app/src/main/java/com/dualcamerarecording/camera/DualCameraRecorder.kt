package com.dualcamerarecording.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.dualcamerarecording.audio.MicCapture
import com.dualcamerarecording.model.AspectRatio
import com.dualcamerarecording.model.StreamConfig
import com.dualcamerarecording.model.StreamOrientation
import com.dualcamerarecording.recording.DualRecordingSession
import com.dualcamerarecording.recording.MediaCapabilities
import com.dualcamerarecording.recording.VideoEncoder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Dual camera recorder using Camera2 + MediaCodec/MediaMuxer.
 *
 * Records front + rear cameras simultaneously into two separate files (front.mp4,
 * rear.mp4) inside Movies/DualCameraRecording/<timestamp>/, with one stereo AAC track
 * (captured once by the shared [MicCapture]) written into both files.
 *
 * Camera pairs: many devices cannot stream every (front, rear) combination at the same
 * time (declared conflicts, ISP limits). When a pair fails for such a structural reason
 * [onPairIncompatible] reports it and the front camera keeps previewing alone — it is
 * re-opened if the failed configuration made the camera HAL restart. Pairs already known
 * to be incompatible are not attempted again (each attempt can restart the HAL).
 *
 * The user-facing state of each side (manual focus / ISO / exposure, zoom, torch, frame
 * rate) is kept here and applied to every controller that gets created, so camera
 * restarts and the switch to recording never lose it.
 */
class DualCameraRecorder {

    private var cameraManager: CameraManager? = null

    @Volatile
    private var frontController: CameraController? = null

    @Volatile
    private var rearController: CameraController? = null

    private var frontPreviewSurface: Surface? = null
    private var rearPreviewSurface: Surface? = null
    /**
     * Secondary preview surfaces (the fullscreen TextureViews). When non-null they are part
     * of the capture session and of every request, so toggling fullscreen is a pure view
     * change and never reconfigures the camera.
     */
    private var frontPreviewSurfaceSecondary: Surface? = null
    private var rearPreviewSurfaceSecondary: Surface? = null

    /** Selected cameras. */
    private var selectedFrontId: String? = null
    private var selectedRearId: String? = null

    /** The selected pair is known not to run together: only the front camera is opened. */
    @Volatile
    private var rearDisabled = false

    private val isRecording = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraHandlerThread: HandlerThread = HandlerThread("CameraHandlerThread").apply { start() }
    private val cameraHandler: Handler = Handler(cameraHandlerThread.looper)

    /** Incremented by every setup / close / recording change: stale open threads abort. */
    private val generation = AtomicInteger(0)
    private var openThread: Thread? = null

    /** Guards publishing / clearing [frontController] and [rearController] against [generation]. */
    private val controllerLock = Any()

    private var recordingSession: DualRecordingSession? = null

    private var singleCameraMode: Boolean = false
    var onSingleCameraMode: ((cameraPosition: String) -> Unit)? = null

    /**
     * The (front, rear) pair cannot stream together on this device (declared conflict or
     * stream configuration refused by the HAL). Main thread.
     */
    var onPairIncompatible: ((frontId: String, rearId: String) -> Unit)? = null

    /** Recording is really running (both files, or only one when a camera failed). Main thread. */
    var onRecordingStarted: ((frontFile: File?, rearFile: File?) -> Unit)? = null

    /**
     * Recording ended: on [stopRecording] completion or after a fatal failure
     * ([error] non-null). The files are null when they were not produced. Main thread.
     */
    var onRecordingStopped: ((frontFile: File?, rearFile: File?, error: Throwable?) -> Unit)? = null

    var onAudioCaptureStarted: (() -> Unit)? = null
    var onAudioCaptureStopped: (() -> Unit)? = null

    // ----------------------------------------------------------------------------------
    // Per-side state applied to every controller
    // ----------------------------------------------------------------------------------

    private class SideState {
        var iso: Int? = null
        var exposureTimeNanos: Long? = null
        var focusDistance: Float? = null
        var exposureCompensation: Int? = null
        var focusEnabled = false
        var exposureEnabled = false
        var torch = false
        var linearZoom = 0f
        var fps = 30
    }

    private val frontState = SideState()
    private val rearState = SideState()

    @Volatile
    private var targetRotation: Int = Surface.ROTATION_0

    @Volatile
    private var previewOutputsEnabled = true

    /**
     * Shape of each camera's preview streams, set by the activity to that camera's recording
     * shape (4:3 / 16:9) before the previews are (re)started, so they show what is recorded.
     */
    @Volatile
    var frontPreviewAspect: AspectRatio = AspectRatio.RATIO_4_3

    @Volatile
    var rearPreviewAspect: AspectRatio = AspectRatio.RATIO_4_3

    fun previewAspectOf(isFront: Boolean): AspectRatio = if (isFront) frontPreviewAspect else rearPreviewAspect

    /** Ids listed by the camera service when it was healthy (used to detect a HAL restart). */
    private var publicCameraIds: Set<String> = emptySet()

    fun initialize(cameraManager: CameraManager) {
        this.cameraManager = cameraManager
        if (publicCameraIds.isEmpty()) {
            publicCameraIds = try { cameraManager.cameraIdList.toSet() } catch (_: Exception) { emptySet() }
        }
    }

    private fun stateOf(isFront: Boolean) = if (isFront) frontState else rearState

    private fun applyState(controller: CameraController, state: SideState) {
        controller.applyManualControls(
            iso = state.iso ?: -1,
            exposureTimeNanos = state.exposureTimeNanos ?: -1L,
            focusDistance = state.focusDistance ?: -1f,
            exposureCompensation = state.exposureCompensation ?: 0,
            torchEnabled = state.torch,
            focusEnabled = state.focusEnabled,
            exposureEnabled = state.exposureEnabled
        )
        controller.setTargetFps(state.fps)
        controller.setTargetRotation(targetRotation)
        controller.setLinearZoom(state.linearZoom)
        controller.setPreviewTargetsEnabled(previewOutputsEnabled)
    }

    /** Preview stream size for [cameraId] with [aspect]; both preview SurfaceTextures must use it. */
    fun previewSizeFor(cameraId: String, aspect: AspectRatio): Size? {
        val cm = cameraManager ?: return null
        return CameraCapabilities.previewSize(cm, cameraId, aspect)
    }

    // ----------------------------------------------------------------------------------
    // Preview
    // ----------------------------------------------------------------------------------

    /**
     * Set up the preview surfaces and the selected cameras. Closes the cameras of a
     * previous setup.
     *
     * If a recording is in progress nothing is torn down: the recording session keeps
     * its cameras.
     */
    fun setupPreview(
        frontCameraId: String,
        rearCameraId: String,
        frontPreviewSurface: Surface,
        rearPreviewSurface: Surface,
        frontSurfaceTexture: SurfaceTexture? = null,
        rearSurfaceTexture: SurfaceTexture? = null,
        frontPreviewSurfaceSecondary: Surface? = null,
        rearPreviewSurfaceSecondary: Surface? = null
    ) {
        if (isRecording.get()) {
            Log.w(TAG, "setupPreview ignored: recording in progress")
            return
        }
        closeControllers()
        previewOutputsEnabled = true
        this.frontPreviewSurface = frontPreviewSurface
        this.rearPreviewSurface = rearPreviewSurface
        this.frontPreviewSurfaceSecondary = frontPreviewSurfaceSecondary
        this.rearPreviewSurfaceSecondary = rearPreviewSurfaceSecondary
        this.selectedFrontId = frontCameraId
        this.selectedRearId = rearCameraId
    }

    /** Release the preview cameras (no-op on a running recording). */
    fun closePreview() {
        if (isRecording.get()) return
        closeControllers()
    }

    private fun closeControllers() {
        val (front, rear) = synchronized(controllerLock) {
            generation.incrementAndGet()
            val pair = frontController to rearController
            frontController = null
            rearController = null
            pair
        }
        openThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
                try { thread.join(50) } catch (_: InterruptedException) {}
            }
        }
        openThread = null
        front?.closeCamera()
        rear?.closeCamera()
    }

    /**
     * Open the selected cameras for preview. With [rearKnownIncompatible] only the front
     * camera is opened (the pair is known not to run together). When the pair fails for a
     * structural reason, [onPairIncompatible] reports it and the front camera is kept (or
     * re-opened, if the failed configuration restarted the camera HAL).
     */
    fun startPreview(
        desiredFront: String? = null,
        desiredRear: String? = null,
        rearKnownIncompatible: Boolean = false
    ) {
        if (isRecording.get()) return
        if (desiredFront != null) selectedFrontId = desiredFront
        if (desiredRear != null) selectedRearId = desiredRear
        val front = selectedFrontId
        val rear = selectedRearId
        if (front.isNullOrEmpty() || rear.isNullOrEmpty()) {
            Log.e(TAG, "startPreview: cameras not set up")
            return
        }
        rearDisabled = rearKnownIncompatible
        val gen = generation.incrementAndGet()
        openThread?.interrupt()
        val thread = Thread({ openPreview(gen, front, rear, rearKnownIncompatible) }, "CameraOpen")
        openThread = thread
        thread.start()
    }

    private fun openPreview(gen: Int, frontId: String, rearId: String, rearKnownIncompatible: Boolean) {
        try {
            if (rearKnownIncompatible) {
                val result = openPairBlocking(gen, frontId, rearId, null, null, openRear = false)
                singleCameraMode = true
                Log.w(TAG, "Pair $frontId+$rearId known incompatible: front only (${result.reason})")
                mainHandler.post { onSingleCameraMode?.invoke("rear-not-working") }
                return
            }
            val result = openPairBlocking(gen, frontId, rearId, null, null, openRear = true)
            if (result.frontOk && result.rearOk) {
                singleCameraMode = false
                Log.d(TAG, "Both cameras ready: front=$frontId rear=$rearId")
                return
            }
            singleCameraMode = true
            Log.w(TAG, "Camera pair $frontId+$rearId not working: ${result.reason}")
            if (result.structuralFailure) {
                rearDisabled = true
                mainHandler.post { onPairIncompatible?.invoke(frontId, rearId) }
                rearController?.closeCamera()
                if (result.rearCode != CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) {
                    // A refused stream configuration can restart the camera HAL a moment
                    // later, killing the front camera too: re-open it alone.
                    reopenFrontAlone(gen, frontId, rearId)
                }
            }
            val position = when {
                result.structuralFailure -> "rear-not-working"
                !result.frontOk && !result.rearOk -> "both-not-working"
                !result.frontOk -> "front-not-working"
                else -> "rear-not-working"
            }
            mainHandler.post { onSingleCameraMode?.invoke(position) }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "Error opening cameras: ${e.message}")
            mainHandler.post { onSingleCameraMode?.invoke("error") }
        }
    }

    /**
     * Open only the front camera, retrying while the camera service is unavailable (a
     * refused stream configuration can make the camera HAL restart for a few seconds).
     */
    private fun reopenFrontAlone(gen: Int, frontId: String, rearId: String) {
        val deadline = System.currentTimeMillis() + HAL_RECOVERY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            closeControllersOf(gen)
            sleepChecking(gen, HAL_RETRY_DELAY_MS)
            // While the camera HAL restarts its public cameras are not listed: wait for them.
            if (frontId in publicCameraIds) {
                val listed = try {
                    cameraManager?.cameraIdList?.contains(frontId) == true
                } catch (_: Exception) {
                    false
                }
                if (!listed) continue
            }
            val result = openPairBlocking(gen, frontId, rearId, null, null, openRear = false)
            if (result.frontOk) {
                Log.d(TAG, "Front camera $frontId re-opened alone")
                return
            }
        }
        Log.w(TAG, "Front camera $frontId could not be re-opened")
    }

    private fun closeControllersOf(gen: Int) {
        val (front, rear) = synchronized(controllerLock) {
            if (gen != generation.get()) return
            val pair = frontController to rearController
            frontController = null
            rearController = null
            pair
        }
        front?.closeCamera()
        rear?.closeCamera()
    }

    private class OpenResult(
        val frontOk: Boolean,
        val rearOk: Boolean,
        val structuralFailure: Boolean,
        val reason: String,
        val rearCode: Int = 0
    )

    /** Tracks one camera of an open attempt. */
    private class OpenWatch {
        @Volatile var failed = false
        @Volatile var code = 0
        @Volatile var message = ""
    }

    /**
     * Create controllers for (front, rear), open the front, then the rear, and wait until
     * both sessions are configured (or fail). Opening sequentially lets the HAL register
     * the first camera before the second one. A short settle time catches a conflicting
     * second open that evicts the first camera.
     */
    private fun openPairBlocking(
        gen: Int,
        frontId: String,
        rearId: String,
        frontRecord: Surface?,
        rearRecord: Surface?,
        openRear: Boolean
    ): OpenResult {
        val cm = cameraManager ?: return OpenResult(false, false, false, "no camera manager")
        val frontWatch = OpenWatch()
        val rearWatch = OpenWatch()
        val front = createController(cm, frontId, frontPreviewSurface, frontPreviewSurfaceSecondary, frontRecord,
            frontWatch, frontPreviewAspect)
        val rear = createController(cm, rearId, rearPreviewSurface, rearPreviewSurfaceSecondary, rearRecord,
            rearWatch, rearPreviewAspect)
        applyState(front, frontState)
        applyState(rear, rearState)
        synchronized(controllerLock) {
            if (gen != generation.get()) throw InterruptedException()
            frontController = front
            rearController = rear
        }

        try {
            front.openCamera()
            waitReady(gen, front, frontWatch, OPEN_TIMEOUT_MS)
            if (openRear) {
                rear.openCamera()
                waitReady(gen, rear, rearWatch, OPEN_TIMEOUT_MS)
            }
            if (front.isReady() && rear.isReady()) {
                // A conflicting open may evict the first camera shortly after.
                sleepChecking(gen, SETTLE_MS)
            }
        } catch (e: InterruptedException) {
            // Superseded by a newer setup / close. Cameras still published now belong to
            // whoever superseded this attempt (a recording reuses them); close the others.
            val (keepFront, keepRear) = synchronized(controllerLock) {
                (frontController === front) to (rearController === rear)
            }
            if (!keepFront) front.closeCamera()
            if (!keepRear) rear.closeCamera()
            throw e
        }
        val frontOk = front.isReady() && !frontWatch.failed
        val rearOk = openRear && rear.isReady() && !rearWatch.failed
        // The rear open failed for a reason tied to the pair while the front was fine.
        val structural = openRear && !frontWatch.failed && isStructural(rearWatch)
        val reason = listOfNotNull(
            frontWatch.takeIf { it.failed }?.let { "front $frontId: ${it.message} (${it.code})" },
            rearWatch.takeIf { it.failed }?.let { "rear $rearId: ${it.message} (${it.code})" },
            "front $frontId not ready".takeIf { !front.isReady() && !frontWatch.failed },
            "rear $rearId not ready".takeIf { openRear && !rear.isReady() && !rearWatch.failed },
            "rear $rearId not opened".takeIf { !openRear }
        ).joinToString("; ")
        return OpenResult(frontOk, rearOk, structural, reason, rearWatch.code)
    }

    private fun isStructural(watch: OpenWatch): Boolean = watch.failed && (
        watch.code == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
            watch.code == CameraController.ERROR_DISCONNECTED ||
            watch.code == -1
        )

    private fun createController(
        cm: CameraManager,
        cameraId: String,
        preview: Surface?,
        previewSecondary: Surface?,
        record: Surface?,
        watch: OpenWatch,
        aspect: AspectRatio
    ): CameraController {
        val controller = CameraController(
            cameraId = cameraId,
            cameraManager = cm,
            previewSurface = preview,
            recordSurface = record,
            imageReaderSurface = null,
            handler = cameraHandler,
            facing = cameraFacing(cm, cameraId),
            previewSurfaceSecondary = previewSecondary,
            previewAspect = aspect
        )
        controller.onError = { code, msg ->
            watch.code = code
            watch.message = msg
            watch.failed = true
            Log.w(TAG, "Camera $cameraId: $msg (code=$code)")
            onControllerError(controller, code, msg)
        }
        return controller
    }

    private fun waitReady(gen: Int, controller: CameraController, watch: OpenWatch, timeoutMs: Long) {
        var waited = 0L
        while (waited < timeoutMs && !controller.isReady() && !watch.failed) {
            if (gen != generation.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            Thread.sleep(10)
            waited += 10
        }
    }

    private fun sleepChecking(gen: Int, ms: Long) {
        var waited = 0L
        while (waited < ms) {
            if (gen != generation.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            Thread.sleep(10)
            waited += 10
        }
    }

    /** A camera failed after it was set up (preview or recording). */
    private fun onControllerError(controller: CameraController, code: Int, message: String) {
        val isFront = controller === frontController
        val isRear = controller === rearController
        if (!isFront && !isRear) return
        if (isRecording.get()) {
            recordingSession?.markCameraFailed(isFront)
        }
    }

    private fun cameraFacing(cm: CameraManager, cameraId: String): Int =
        CameraCapabilities.characteristics(cm, cameraId)?.get(CameraCharacteristics.LENS_FACING)
            ?: CameraMetadata.LENS_FACING_BACK

    // ----------------------------------------------------------------------------------
    // Recording
    // ----------------------------------------------------------------------------------

    data class FilePair(val front: File, val rear: File)

    /**
     * Start recording from both cameras with the shared audio of [micCapture].
     *
     *  1. Create the output folder and the recording session (H.264 encoders configured
     *     with the selected resolution / fps / bitrate, AAC encoder with the selected
     *     sample rate / bitrate in stereo).
     *  2. Re-open the cameras with the encoder surfaces in the capture session.
     *  3. The session starts both files at the same instant once both cameras stream
     *     ([onRecordingStarted]); a camera that fails is reported and the other one keeps
     *     recording.
     */
    fun startRecording(
        frontConfig: StreamConfig,
        rearConfig: StreamConfig,
        screenOrientation: StreamOrientation = StreamOrientation.PORTRAIT,
        audioBitrateKbps: Int = 128,
        audioSampleRateHz: Int = 44100,
        micCapture: MicCapture? = null
    ): FilePair? {
        val cm = cameraManager ?: return null
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Already recording")
            return null
        }
        val frontId = selectedFrontId
        val rearId = selectedRearId
        if (frontId == null || rearId == null || frontPreviewSurface == null || rearPreviewSurface == null) {
            Log.e(TAG, "startRecording: cameras not set up")
            isRecording.set(false)
            return null
        }

        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        // Recordings land under Movies/DualCameraRecording/<timestamp>/
        val moviesRoot = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
        val outputDir = File(moviesRoot, "DualCameraRecording/$timestamp")
        if (!outputDir.mkdirs() && !outputDir.isDirectory) {
            Log.e(TAG, "Cannot create output directory $outputDir")
            isRecording.set(false)
            return null
        }

        val landscape = screenOrientation.isLandscape
        val frontSpec = videoSpecFor(cm, frontId, frontConfig)
        // A pair that cannot run together records the front camera only.
        val recordRear = !rearDisabled
        val rearSpec = if (recordRear) videoSpecFor(cm, rearId, rearConfig) else null
        frontState.fps = frontSpec.fps
        rearSpec?.let { rearState.fps = it.fps }
        val audioSpec = audioSpecFor(micCapture, audioSampleRateHz, audioBitrateKbps)

        // The encoders are created while the preview still runs: if this fails the
        // preview is left untouched.
        val session = try {
            DualRecordingSession(
                outputDir = outputDir,
                frontSpec = frontSpec,
                rearSpec = rearSpec,
                frontOrientationHint = CameraCapabilities.recordingOrientationHint(cm, frontId, landscape),
                rearOrientationHint = CameraCapabilities.recordingOrientationHint(cm, rearId, landscape),
                audioSpec = audioSpec,
                micCapture = micCapture,
                listener = sessionListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create the recording session: ${e.message}")
            outputDir.delete()
            isRecording.set(false)
            return null
        }
        recordingSession = session
        Log.d(TAG, "Recording to $outputDir: front=$frontId $frontSpec, rear=$rearId $rearSpec, audio=$audioSpec " +
            "(hint front=${CameraCapabilities.recordingOrientationHint(cm, frontId, landscape)}°, " +
            "rear=${CameraCapabilities.recordingOrientationHint(cm, rearId, landscape)}°)")
        onAudioCaptureStarted?.invoke()
        singleCameraMode = false

        // Normally the preview cameras are open: add the encoder surfaces with a new
        // capture session on the same devices (fast). Otherwise open them from scratch.
        val (previewFront, previewRear) = synchronized(controllerLock) {
            generation.incrementAndGet() // a preview open still running must stop here
            frontController to rearController
        }
        openThread?.interrupt()
        val gen = generation.get()
        val reuse = previewFront != null && previewFront.isReady() && previewFront.cameraId == frontId &&
            previewFront.previewAspect == frontPreviewAspect &&
            (!recordRear || (previewRear != null && previewRear.isReady() && previewRear.cameraId == rearId &&
                previewRear.previewAspect == rearPreviewAspect))
        val thread = Thread({
            try {
                if (reuse) {
                    switchSessions(gen, previewFront!!, if (recordRear) previewRear else null,
                        session.frontSurface, session.rearSurface, session)
                } else {
                    closeControllersOf(gen)
                    val result = openPairBlocking(gen, frontId, rearId, session.frontSurface, session.rearSurface,
                        openRear = recordRear)
                    if (gen != generation.get()) return@Thread
                    if (!result.frontOk) session.markCameraFailed(isFront = true)
                    if (!result.rearOk) session.markCameraFailed(isFront = false)
                    if (result.structuralFailure) {
                        rearDisabled = true
                        mainHandler.post { onPairIncompatible?.invoke(frontId, rearId) }
                    }
                    if (!result.frontOk || !result.rearOk) {
                        singleCameraMode = true
                        Log.w(TAG, "Recording camera problem: ${result.reason}")
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "Error opening cameras for recording: ${e.message}")
                session.markCameraFailed(isFront = true)
                session.markCameraFailed(isFront = false)
            }
        }, "CameraOpenRecording")
        openThread = thread
        thread.start()

        return FilePair(session.frontFile, session.rearFile)
    }

    /**
     * Give the already open cameras new capture sessions with [frontRecord] / [rearRecord]
     * as encoder outputs (null = preview only) and wait until they are configured. A side
     * that does not come up is reported to [session] (when recording).
     */
    private fun switchSessions(
        gen: Int,
        front: CameraController,
        rear: CameraController?,
        frontRecord: Surface?,
        rearRecord: Surface?,
        session: DualRecordingSession?
    ) {
        // Both cameras idle first, then one reconfiguration at a time: some HALs fail the
        // requests of a camera reconfigured while the other sensor keeps streaming.
        front.stopPreview()
        rear?.stopPreview()
        front.setRecordSurface(frontRecord)
        val frontOk = waitSessionReady(gen, front)
        rear?.setRecordSurface(rearRecord)
        val rearOk = rear != null && waitSessionReady(gen, rear)
        if (!frontOk) {
            Log.w(TAG, "Front camera ${front.cameraId}: session with the new outputs not ready")
            session?.markCameraFailed(isFront = true)
        }
        if (rear != null && !rearOk) {
            Log.w(TAG, "Rear camera ${rear.cameraId}: session with the new outputs not ready")
            session?.markCameraFailed(isFront = false)
        }
    }

    private fun waitSessionReady(gen: Int, controller: CameraController): Boolean {
        var waited = 0L
        while (waited < OPEN_TIMEOUT_MS && !controller.isReady()) {
            if (gen != generation.get()) throw InterruptedException()
            Thread.sleep(10)
            waited += 10
        }
        return controller.isReady()
    }

    /** True when the preview cameras are open and streaming (e.g. right after a recording). */
    fun isPreviewActive(): Boolean {
        if (isRecording.get()) return false
        val front = frontController ?: return false
        val rearOk = rearDisabled || rearController?.isReady() == true
        return front.isReady() && rearOk
    }

    private val sessionListener = object : DualRecordingSession.Listener {
        override fun onRecordingStreaming(front: Boolean, rear: Boolean) {
            val session = recordingSession ?: return
            mainHandler.post {
                onRecordingStarted?.invoke(
                    if (front) session.frontFile else null,
                    if (rear) session.rearFile else null
                )
            }
        }

        override fun onRecordingFailure(message: String, error: Throwable?) {
            mainHandler.post {
                if (!isRecording.get()) return@post
                stopRecording(error ?: IllegalStateException(message))
            }
        }

        override fun onSurplusFrame(isFront: Boolean) {
            (if (isFront) frontController else rearController)?.skipRecordFrame()
        }
    }

    /** Encoder configuration for one camera, validated against camera and encoder. */
    private fun videoSpecFor(cm: CameraManager, cameraId: String, config: StreamConfig): VideoEncoder.Spec {
        var size = Size(config.resolution.landscapeWidth, config.resolution.landscapeHeight)
        if (!CameraCapabilities.isRecordingSizeSupported(cm, cameraId, size)) {
            // Largest supported size of the same shape that is not wider.
            val sameShape = CameraCapabilities.characteristics(cm, cameraId)
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.media.MediaCodec::class.java)
                ?.filter { config.resolution.aspect.matches(it.width, it.height) }
                .orEmpty()
            val fallback = sameShape.filter { it.width <= size.width }.maxByOrNull { it.width * it.height }
                ?: sameShape.minByOrNull { it.width * it.height }
            Log.w(TAG, "Camera $cameraId does not support ${size.width}x${size.height} for video, using $fallback")
            if (fallback != null) size = fallback
        }
        val supportedFps = CameraCapabilities.frameRateOptions(cm, cameraId, size)
        val fps = when {
            supportedFps.isEmpty() || config.fps in supportedFps -> config.fps
            else -> supportedFps.minByOrNull { abs(it - config.fps) } ?: 30
        }
        if (fps != config.fps) Log.w(TAG, "Camera $cameraId cannot hold ${config.fps} fps, using $fps")
        var bitrate = if (config.bitrate.bps > 0) config.bitrate.bps
                      else MediaCapabilities.autoVideoBitrate(size.width, size.height, fps)
        MediaCapabilities.videoBitrateRange()?.let { bitrate = bitrate.coerceAtLeast(it.lower) }
        MediaCapabilities.maxVideoBitrate()?.let { bitrate = bitrate.coerceAtMost(it) }
        return VideoEncoder.Spec(size.width, size.height, fps, bitrate)
    }

    private fun audioSpecFor(mic: MicCapture?, sampleRateHz: Int, bitrateKbps: Int): DualRecordingSession.AudioSpec? {
        if (mic == null || !mic.isCapturing.get()) {
            Log.w(TAG, "Microphone capture not running: recording without audio")
            return null
        }
        if (mic.sampleRate != sampleRateHz) {
            Log.w(TAG, "Microphone runs at ${mic.sampleRate} Hz instead of $sampleRateHz Hz")
        }
        val rate = mic.sampleRate
        val bitrate = (bitrateKbps * 1000).coerceIn(MediaCapabilities.aacMinBitrate(), MediaCapabilities.aacMaxBitrate(rate, 2))
        return DualRecordingSession.AudioSpec(sampleRate = rate, channels = 2, bitrate = bitrate)
    }

    /**
     * Stop the recording. The cameras go back to preview-only sessions (or are closed if
     * they are not running), then the encoders are flushed and the files finalized on a
     * background thread; [onRecordingStopped] is posted to the main thread when done (with
     * [error] when the stop was caused by a failure). When [isPreviewActive] is true
     * afterwards the preview is already running again.
     */
    fun stopRecording(error: Throwable? = null) {
        if (!isRecording.get()) return
        val session = recordingSession ?: run {
            isRecording.set(false)
            mainHandler.post { onRecordingStopped?.invoke(null, null, error) }
            return
        }
        recordingSession = null
        session.markEnd()
        val (front, rear) = synchronized(controllerLock) {
            generation.incrementAndGet()
            frontController to rearController
        }
        openThread?.interrupt()
        openThread = null
        val gen = generation.get()
        Thread({
            val keepPreview = error == null && front?.isReady() == true
            if (keepPreview) {
                // No frame reaches the encoders from here on; the previews keep running.
                try {
                    switchSessions(gen, front!!, rear?.takeIf { it.isReady() }, null, null, session = null)
                } catch (_: InterruptedException) {
                }
            } else {
                closeControllersOf(gen)
                front?.closeCamera()
                rear?.closeCamera()
            }
            val result = try {
                session.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing recording: ${e.message}")
                null
            }
            mainHandler.post {
                isRecording.set(false)
                onAudioCaptureStopped?.invoke()
                onRecordingStopped?.invoke(result?.frontFile, result?.rearFile, error)
            }
        }, "RecordingStop").start()
    }

    /**
     * Include / exclude the preview surfaces from the camera requests. Used while the
     * activity is in the background during a recording: only the encoders get frames.
     */
    fun setPreviewOutputsEnabled(enabled: Boolean) {
        previewOutputsEnabled = enabled
        frontController?.setPreviewTargetsEnabled(enabled)
        rearController?.setPreviewTargetsEnabled(enabled)
    }

    // ----------------------------------------------------------------------------------
    // Controls
    // ----------------------------------------------------------------------------------

    /**
     * Apply manual controls to one camera. Sentinel -1 clears a field back to auto; null
     * leaves it unchanged. [focusDistance] is normalized (0 = infinity, 1 = closest focus).
     * The torch is controlled separately ([setRearCameraTorch]).
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
        val state = stateOf(isFront)
        if (iso != null) state.iso = iso.takeIf { it >= 0 }
        if (exposureTimeNanos != null) state.exposureTimeNanos = exposureTimeNanos.takeIf { it >= 0 }
        if (focusDistance != null) state.focusDistance = focusDistance.takeIf { it >= 0f }
        if (exposureCompensation != null) state.exposureCompensation = exposureCompensation.takeIf { it != 0 }
        if (torchEnabled != null) state.torch = torchEnabled
        if (focusEnabled != null) state.focusEnabled = focusEnabled
        if (exposureEnabled != null) state.exposureEnabled = exposureEnabled
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
        stateOf(isFront).torch = enabled
        val controller = if (isFront) frontController else rearController
        controller?.setTorchMode(enabled)
    }

    /** Frame rate the camera holds for preview and recording. */
    fun setTargetFps(isFront: Boolean, fps: Int) {
        stateOf(isFront).fps = fps
        val controller = if (isFront) frontController else rearController
        controller?.setTargetFps(fps)
    }

    /** Digital zoom in `[0, 1]` (0 = no zoom, 1 = max digital zoom). */
    fun setLinearZoom(isFront: Boolean, linearZoom: Float) {
        stateOf(isFront).linearZoom = linearZoom.coerceIn(0f, 1f)
        val controller = if (isFront) frontController else rearController
        controller?.setLinearZoom(linearZoom)
    }

    /** Re-apply the zoom levels owned by the activity (also stored for future controllers). */
    fun reapplyLinearZoom(frontLinearZoom: Float, rearLinearZoom: Float) {
        frontState.linearZoom = frontLinearZoom.coerceIn(0f, 1f)
        rearState.linearZoom = rearLinearZoom.coerceIn(0f, 1f)
        frontController?.reapplyLinearZoom(frontLinearZoom)
        rearController?.reapplyLinearZoom(rearLinearZoom)
    }

    /**
     * Toggle the torch of the REAR camera (the flash unit), driven through the capture
     * session only. CameraManager.setTorchMode is intentionally not used: on logical
     * multi-cameras it targets a different id and some HALs ignore it while the session
     * still has FLASH_MODE_TORCH set ("flash can't be turned off").
     *
     * Returns true if a flash unit was found for the selected rear camera.
     */
    fun setRearCameraTorch(rearCameraId: String, enabled: Boolean): Boolean {
        rearState.torch = enabled
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
        rearController?.setTorchMode(enabled)
        return true
    }

    /**
     * Return the camera id (logical or physical) that owns the flash for the given rear
     * camera, or null if no flash unit can be found in this camera group.
     */
    private fun findRearCameraWithFlash(cm: CameraManager, cameraId: String): String? {
        val chars = CameraCapabilities.characteristics(cm, cameraId) ?: return null
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

    /** True when no camera controller is currently open. */
    fun isClosed(): Boolean = frontController == null && rearController == null

    /**
     * Tap-to-focus on one camera at ([x], [y]) of a preview view of size
     * [viewWidth] x [viewHeight] whose TextureView transform is [viewMatrix].
     */
    fun triggerAutoFocusAt(isFront: Boolean, x: Float, y: Float, viewWidth: Int, viewHeight: Int, viewMatrix: Matrix) {
        val controller = if (isFront) frontController else rearController
        controller?.triggerAutoFocusAt(x, y, viewWidth, viewHeight, viewMatrix)
    }

    /** Block (or unblock) still capture on both cameras while cameras are being switched. */
    fun setCaptureBlocked(blocked: Boolean) {
        frontController?.captureBlocked = blocked
        rearController?.captureBlocked = blocked
    }

    /** Forward the activity's display rotation (Surface.ROTATION_*) to the controllers. */
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        frontController?.setTargetRotation(rotation)
        rearController?.setTargetRotation(rotation)
    }

    fun isRecording(): Boolean = isRecording.get()
    fun isSingleCameraMode(): Boolean = singleCameraMode

    /** Front preview stream size (known as soon as the cameras are set up). */
    fun frontPreviewSize(): Size? = selectedFrontId?.let { previewSizeFor(it, frontPreviewAspect) }

    /** Rear preview stream size (known as soon as the cameras are set up). */
    fun rearPreviewSize(): Size? = selectedRearId?.let { previewSizeFor(it, rearPreviewAspect) }

    /**
     * Release everything. A recording still in progress is finalized synchronously so the
     * files stay playable.
     */
    fun release() {
        if (isRecording.getAndSet(false)) {
            val session = recordingSession
            recordingSession = null
            session?.markEnd()
            val (front, rear) = synchronized(controllerLock) {
                generation.incrementAndGet()
                frontController to rearController
            }
            openThread?.interrupt()
            front?.stopPreview()
            rear?.stopPreview()
            front?.closeCamera()
            rear?.closeCamera()
            try {
                session?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing recording on release: ${e.message}")
            }
            onAudioCaptureStopped?.invoke()
        }
        closeControllers()
        cameraManager = null
        cameraHandlerThread.quitSafely()
    }

    companion object {
        const val TAG = "DualCameraRecorder"

        private const val OPEN_TIMEOUT_MS = 3500L
        private const val SETTLE_MS = 300L
        private const val HAL_RETRY_DELAY_MS = 1000L

        /** A camera HAL restarting after a refused configuration took up to ~21 s in tests. */
        private const val HAL_RECOVERY_TIMEOUT_MS = 45_000L
    }
}
