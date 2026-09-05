package com.dualcamerarecording

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dualcamerarecording.audio.MicObserver
import com.dualcamerarecording.audio.MicState
import com.dualcamerarecording.audio.view.AnalogMeterView
import com.dualcamerarecording.audio.view.DigitalMeterView
import com.dualcamerarecording.camera.CameraInventory
import com.dualcamerarecording.camera.DualCameraRecorder
import com.dualcamerarecording.data.PreferencesRepository
import com.dualcamerarecording.databinding.ActivityMainBinding
import com.dualcamerarecording.locale.LocaleManager
import com.dualcamerarecording.model.AppLanguage
import com.dualcamerarecording.model.AppThemeMode
import com.dualcamerarecording.model.ControlMode
import com.dualcamerarecording.model.DualCameraConfig
import com.dualcamerarecording.model.MeterStyle
import com.dualcamerarecording.model.StreamBitrate
import com.dualcamerarecording.model.StreamConfig
import com.dualcamerarecording.model.StreamFps
import com.dualcamerarecording.model.StreamOrientation
import com.dualcamerarecording.model.StreamResolution
import com.dualcamerarecording.settings.CameraSettingsStore
import com.dualcamerarecording.theme.ThemeManager
import com.dualcamerarecording.ui.AlertDialogHelper
import com.dualcamerarecording.ui.SettingsBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: DualCameraRecorderApp
    private lateinit var preferences: PreferencesRepository
    private lateinit var themeManager: ThemeManager
    private lateinit var settingsStore: CameraSettingsStore
    private lateinit var cameraInventory: CameraInventory
    private lateinit var dualCameraRecorder: DualCameraRecorder
    private lateinit var mainViewModel: MainViewModel

    // Audio meter observation — observer pattern (NOT polling)
    private var micUnsubscribe: (() -> Unit)? = null

    // Camera lists for spinners
    private val frontCameraOptions = mutableListOf<CameraInventory.CameraInfo>()
    private val rearCameraOptions = mutableListOf<CameraInventory.CameraInfo>()

    // Recording state
    private var isRecording = false
    private var isFullscreen = false
    private var isLandscapeMode = false
    private var fullscreenControlsVisible = true
    private var recordingStartTime: Long = 0L
    private var recordingElapsedJob: Job? = null

    private var flashOn = false
    private var bindingInProgress = false

    // Re-entrancy guard: prevent multiple concurrent calls to startCamerasIfReady /
    // startFullscreenCamerasIfReady / restartPreview. Without this the SurfaceTexture
    // listener + global-layout listener both fire startCamerasIfReady on cold start,
    // each call interrupts the previous retry thread and starts a new one, and the
    // camera HAL ends up reporting ERROR_CAMERA_IN_USE because the previous camera
    // is still being released. The flag is reset after 5 s, which covers the worst
    // case (4 attempts × ~3 s each) of the empirical retry loop.
    private var camerasStarting: Boolean = false

    // While a main<->fullscreen transition is in progress we don't want the
    // SurfaceTexture listener (which fires on the same main thread between our
    // yields) to start cameras before our stopCurrentPreviewAndWait() has
    // finished. The listener checks this flag and skips its auto-start while
    // it is set. It is cleared by the enter/exitFullscreen flow right before
    // it explicitly starts the new cameras.
    private var inFullscreenTransition: Boolean = false

    // When the user changes a camera while camerasStarting is true (e.g. mid-retry), the
    // spinner listener would silently skip restartPreview() and the new camera would
    // never be opened. Instead, we mark a pending change and re-run restartPreview() as
    // soon as the guard clears.
    private var pendingCameraChange: Boolean = false

    // Manual control state — separate per camera
    private var frontFocusDistance = 0.5f
    private var rearFocusDistance = 0.5f
    private var frontIsoValue: Int? = null
    private var rearIsoValue: Int? = null
    private var frontExposureTimeNanos: Long? = null
    private var rearExposureTimeNanos: Long? = null
    private var isFrontManualFocus = false
    private var isRearManualFocus = false
    private var isFrontManualAdjustments = false
    private var isRearManualAdjustments = false

    // Per-camera linear-zoom state in `[0, 1]` (0 = no zoom, 1 = max digital zoom).
    // Held in-memory only: the user has to re-pick zoom on every launch (matches
    // the AndroidCamera reference's "not persisted" pattern for transient capture
    // state, and keeps the persisted preference set minimal). The CameraController
    // also remembers the value across session rebuilds, so the zoom is re-applied
    // on every preview restart via [dualCameraRecorder.reapplyLinearZoom].
    private var frontLinearZoom = 0f
    private var rearLinearZoom = 0f

    // All permissions the app needs in order to function. The list is built
    // once on construction from the manifest + API level so we request exactly
    // what the OS expects:
    //  - CAMERA + RECORD_AUDIO: always required (preview + audio capture).
    //  - WRITE_EXTERNAL_STORAGE: required to save MP4 files to the public Movies
    //    directory. ONLY asked on API 26-29, where legacy storage is in effect
    //    and the permission actually grants write access. On API 30+ scoped
    //    storage is mandatory, the permission is a no-op (and on API 33+ it
    //    is also no longer shown in the system dialog), so requesting it just
    //    makes `checkSelfPermission` return DENIED forever and triggers a
    //    bogus "storage required" toast on every launch. With `requestLegacy
    //    ExternalStorage="true"` the app still writes to Movies up to API 29;
    //    on API 30+ it relies on scoped storage instead.
    //  - READ_EXTERNAL_STORAGE / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO: required
    //    so the user can browse their recordings with the system file picker
    //    and so the app's own listing of saved files survives a process death.
    //    On API 33+ the system replaced READ_EXTERNAL_STORAGE with the
    //    granular READ_MEDIA_* permissions, so we ask for those instead.
    private val permissionsNeeded: Array<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // API 33+ — WRITE_EXTERNAL_STORAGE and READ_EXTERNAL_STORAGE
                // are both deprecated; ask for the granular media permissions
                // so the user sees only the categories we need.
                list.add(Manifest.permission.READ_MEDIA_VIDEO)
                list.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // API 30-32 — WRITE_EXTERNAL_STORAGE is a no-op under scoped
                // storage, so don't request it (the system won't grant it).
                // READ_EXTERNAL_STORAGE is still the right read permission.
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                // API 26-29 — legacy storage is in effect, WRITE_EXTERNAL_STORAGE
                // is required to write to the public Movies directory and is
                // paired with the matching READ_EXTERNAL_STORAGE.
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        list.toTypedArray()
    }

    // Storage-only subset of `permissionsNeeded` (CAMERA and RECORD_AUDIO
    // filtered out). Used by the storage-granted check and by the re-request
    // flow when the user already has CAMERA but denied storage. RECORD_AUDIO
    // is excluded on purpose: the storage toast must not depend on the mic
    // permission, which is tracked separately by `audioGranted`.
    private val storageOnlyPermissionsNeeded: Array<String> by lazy {
        permissionsNeeded
            .filter { it != Manifest.permission.CAMERA && it != Manifest.permission.RECORD_AUDIO }
            .toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // CAMERA is the hard gate — without it the preview / recording can't run.
        // RECORD_AUDIO powers both the audio meter and the audio track inside the
        // MP4. WRITE/READ storage are required to save the resulting files.
        val cameraGranted = permissions[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val storageGranted = storageOnlyPermissionsNeeded.all { perm ->
            permissions[perm]
                ?: (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
        }
        if (cameraGranted && audioGranted) {
            // Storage is needed for RECORDING (saving the MP4 files), but the
            // app's preview + meters still work without it. We log a warning
            // and proceed so the user can see the preview even if they denied
            // storage; the recording itself will fail later with a clear error.
            if (!storageGranted) {
                Log.w(TAG, "Storage permission not granted — recordings will fail to save")
                Toast.makeText(this, R.string.permission_storage_required, Toast.LENGTH_LONG).show()
            }
            initializeCameraSystems()
            // The SurfaceTexture listeners attached in initListeners() fired
            // during the first layout pass BEFORE the user granted the permission,
            // so they returned early via the hasRequiredPermissions() guard inside
            // startCamerasIfReady(). Now that permissions are granted, kick off
            // the main-view cameras explicitly. Without this the main-view
            // previews would stay black until the user entered and exited the
            // fullscreen overlay (which calls startFullscreenCamerasIfReady /
            // startCamerasIfReady directly via the fullscreen SurfaceTexture
            // listeners and exitFullscreen).
            startCamerasIfReady()
            // Also start live mic capture now that permission is granted.
            app.ensureMicCaptureStarted()
        } else {
            val missing = when {
                !cameraGranted && !audioGranted -> getString(R.string.permission_camera_audio_required)
                !cameraGranted -> getString(R.string.permission_camera_required)
                else -> getString(R.string.permission_audio_required)
            }
            Toast.makeText(this, missing, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Apply the app's saved language before the Activity (and its views) are created so
     * resources load in the chosen locale. Also neutralizes uiMode (LocaleManager does this)
     * so the Light theme stays light even when the system is in dark mode.
     */
    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = LocaleManager.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleManager.wrapWithSavedLocale(newBase, savedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        app = application as DualCameraRecorderApp
        preferences = app.preferences
        themeManager = app.themeManager
        settingsStore = app.settingsStore

        // Apply the selected theme BEFORE the view hierarchy is inflated so the correct
        // Light/Dark style resources are used. This is the fix for "Light theme doesn't work".
        setTheme(themeManager.styleRes(preferences.themeMode, this))

        super.onCreate(savedInstanceState)

        cameraInventory = CameraInventory(this)
        dualCameraRecorder = DualCameraRecorder()
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Landscape mode is intentionally NOT persisted: the app always starts in portrait
        // (vertical). The user has to enable Landscape on each launch via the checkbox.
        isLandscapeMode = false

        // Inflate the views and attach the SurfaceTexture listeners BEFORE
        // asking for permissions. The listeners attached in [initListeners]
        // fire on the first layout pass and call startCamerasIfReady(); if
        // the user hasn't granted CAMERA yet the listener returns early via
        // the hasRequiredPermissions() guard, and the permissionLauncher
        // callback explicitly calls startCamerasIfReady() once the permission
        // is granted (otherwise the main-view previews would stay black until
        // the user entered and exited the fullscreen overlay — see the
        // permission callback for the full explanation).
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initListeners()
        observeSettings()

        // Request runtime permissions and initialize the camera systems. The
        // TextureView SurfaceTexture listeners (attached in initListeners) fire
        // during the first layout pass and call startCamerasIfReady(); if the
        // user hasn't granted CAMERA yet the listener returns early, so we
        // also need to start the cameras explicitly once the permission
        // callback fires. checkPermissionsAndInit handles both the already-
        // granted path (calls startCamerasIfReady via a global-layout listener)
        // and the denied-then-granted path (calls startCamerasIfReady from the
        // permissionLauncher callback).
        checkPermissionsAndInit()

        // Show the device-compatibility alert ONLY on a fresh app launch
        // (savedInstanceState is null), not on every Activity recreate. The
        // recreate is triggered by theme / language changes, and we don't
        // want the alert to reappear every time the user changes theme or
        // language. The preference (`showDeviceCompatibilityAlert`) still
        // suppresses it across actual app launches once the user ticks
        // the "Don't show again" checkbox.
        if (savedInstanceState == null) {
            showDeviceCompatibilityAlertIfNeeded()
        }

        // Apply the saved landscape mode AFTER listeners attach (and after the binding
        // exists) so the checkbox state, requested orientation, preview-container layout,
        // and resolution labels all initialize consistently.
        if (isLandscapeMode) {
            setLandscapeMode(true)
        }

        // Observe audio meters using the observer pattern (NOT polling)
        micUnsubscribe = app.micStateStore.observe(object : MicObserver {
            override fun onStateChanged(state: MicState) {
                // Separate per-camera meters: left = front mic, right = rear mic
                val frontLevel = state.leftLevel.toFloat().coerceIn(-60f, 0f)
                val rearLevel = state.rightLevel.toFloat().coerceIn(-60f, 0f)
                updateMeters(frontLevel, rearLevel)
            }
        })

        Log.d(TAG, "MainActivity created")
    }

    override fun onDestroy() {
        super.onDestroy()
        dualCameraRecorder.release()
        stopRecordingTimer()
        micUnsubscribe?.invoke()
        micUnsubscribe = null
        app.stopLiveMicCapture()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")
        if (!isFullscreen && !isRecording) {
            // Reapply the letterbox transform for the new view dimensions.
            requestPreviewTransforms()
        }
    }

    private fun updateMeters(frontLevel: Float, rearLevel: Float) {
        // Update main digital meters (one per camera)
        binding.digitalMeterFront.setLevelWithText(frontLevel)
        binding.digitalMeterRear.setLevelWithText(rearLevel)

        // Update main analog meters (one per channel)
        binding.analogMeterFront.setValue(frontLevel)
        binding.analogMeterRear.setValue(rearLevel)

        // Update fullscreen digital meters (one per camera)
        binding.digitalMeterFrontFullscreen.setLevelWithText(frontLevel)
        binding.digitalMeterRearFullscreen.setLevelWithText(rearLevel)

        // Update fullscreen analog meters (one per channel)
        binding.analogMeterFrontFullscreen.setValue(frontLevel)
        binding.analogMeterRearFullscreen.setValue(rearLevel)
    }

    // ==================== Listeners ====================

    private fun initListeners() {
        setupSurfaceCallbacks()
        setupRecordingButton()
        setupFullscreenButton()
        setupRestoreCamerasButton()
        setupBackButton()
        setupFullscreenControlsToggle()
        setupFullscreenLandscapeCheckbox()
        setupSettingsAndFlashButtons()
        setupLandscapeCheckbox()
        setupAutofocusOnTapCheckbox()
        setupCameraSpinners()
        setupResolutionFpsSpinners()
        setupBitrateSpinners()
        setupAudioSpinners()
        setupManualControls()
        setupFocusSeekBar()
        setupZoomSeekBar()

        // Notify the user (via Toast) when the empirical retry loop in DualCameraRecorder
        // swapped to a different (front, rear) pair than the one we asked for (because
        // the device doesn't support the chosen combination concurrently). Also update
        // the spinners to reflect the cameras actually in use, so the UI doesn't lie
        // about which camera is being recorded.
        dualCameraRecorder.onPairFallback = { frontId, rearId ->
            val rearLabel = rearCameraOptions.firstOrNull { it.cameraId == rearId }?.label
                ?: rearId
            val frontLabel = frontCameraOptions.firstOrNull { it.cameraId == frontId }?.label
                ?: frontId
            runOnUiThread {
                // Persist the actual pair so the spinners + recording use them.
                val cfg = settingsStore.config.value
                var changed = false
                if (cfg.frontCameraId != frontId) {
                    settingsStore.updateFrontCameraId(frontId)
                    changed = true
                }
                if (cfg.rearCameraId != rearId) {
                    settingsStore.updateRearCameraId(rearId)
                    changed = true
                }
                Toast.makeText(
                    this,
                    getString(R.string.fallback_pair, "$frontLabel + $rearLabel"),
                    Toast.LENGTH_LONG
                ).show()
                if (changed) {
                    // Re-apply transform for the new (actual) camera pair.
                    requestPreviewTransforms()
                }
            }
        }

        // Called by the retry loop BEFORE the camera is opened with a fallback pair, so we
        // can resize the SurfaceTexture buffer to the picked size for the new camera. The
        // empirical retry may swap to a different (front, rear) than pickWorkingPair
        // returned (e.g. on devices where concurrentCameraIds is empty, so pickWorkingPair
        // is optimistic). The actual pair is only known after the first open attempt.
        dualCameraRecorder.onBeforeOpenFallback = { frontId, rearId ->
            val frontSt = if (isFullscreen) fullscreenFrontSurfaceView.surfaceTexture
                          else frontSurfaceView.surfaceTexture
            val rearSt = if (isFullscreen) fullscreenRearSurfaceView.surfaceTexture
                         else rearSurfaceView.surfaceTexture
            if (frontSt != null) {
                resizeBufferToPickedSizeForPair(frontId, isFront = true, isFullscreen = isFullscreen, surface = frontSt)
            }
            if (rearSt != null) {
                resizeBufferToPickedSizeForPair(rearId, isFront = false, isFullscreen = isFullscreen, surface = rearSt)
            }
        }

        // Called by DualCameraRecorder when a camera fails to open (or when the open itself
        // fails with an exception). The position is one of:
//   - "front-not-working" / "rear-not-working" / "both-not-working" / "error"
// We do NOT auto-pick an alternate camera — the user picks one via the spinner. The
// preview cards remain visible (the failing one shows a black TextureView since its
// camera never opened). Per the user's request, no Toast is shown here: the failing
// preview card is the visual indicator.
        dualCameraRecorder.onSingleCameraMode = { _ ->
            runOnUiThread {
                // Keep both preview cards visible so the layout stays consistent.
                if (isFullscreen) {
                    val frontHolder = fullscreenFrontSurfaceView.parent as View?
                    val rearHolder = fullscreenRearSurfaceView.parent as View?
                    frontHolder?.visibility = View.VISIBLE
                    rearHolder?.visibility = View.VISIBLE
                } else {
                    binding.frontPreviewCard.visibility = View.VISIBLE
                    binding.rearPreviewCard.visibility = View.VISIBLE
                }
                requestPreviewTransforms()
            }
        }
    }

    /**
     * Re-size the SurfaceTexture buffer to match the size the camera with id [camId] will
     * pick, but using the configuration currently in effect for the given [isFront] +
     * [isFullscreen] pair. Called when the retry loop swaps to a different camera so the
     * texture view doesn't end up with a buffer that's a different size from what the
     * new camera writes.
     *
     * Optionally takes an explicit [surface] to resize (otherwise looks up the
     * main-activity or fullscreen view by isFront/isFullscreen).
     */
    private fun resizeBufferToPickedSizeForPair(
        camId: String,
        isFront: Boolean,
        isFullscreen: Boolean,
        surface: SurfaceTexture? = null
    ) {
        val st = surface ?: run {
            val view = if (isFullscreen) {
                if (isFront) fullscreenFrontSurfaceView else fullscreenRearSurfaceView
            } else {
                if (isFront) frontSurfaceView else rearSurfaceView
            }
            view.surfaceTexture
        } ?: return
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cfg = settingsStore.config.value
        val streamConfig = if (isFront) cfg.frontConfig else cfg.rearConfig
        val orientation = currentStreamOrientation(forFullscreen = isFullscreen)
        val target = Size(
            streamConfig.resolution.widthFor(orientation),
            streamConfig.resolution.heightFor(orientation)
        )
        val sizes = try {
            val chars = cm.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sts = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            if (!sts.isNullOrEmpty()) sts else map?.getOutputSizes(Surface::class.java)
        } catch (e: Exception) { null }
        if (sizes == null || sizes.isEmpty()) return
        val exact = sizes.firstOrNull { it.width == target.width && it.height == target.height }
        val picked = exact ?: sizes.minByOrNull {
            val r = it.width.toDouble() / it.height.toDouble()
            val tR = target.width.toDouble() / target.height.toDouble()
            kotlin.math.abs(r - tR) * 1000 - it.width
        } ?: return
        Log.d(TAG, "Buffer for ${if (isFront) "front" else "rear"}${if (isFullscreen) "(fs)" else ""} ($camId) -> $picked")
        st.setDefaultBufferSize(picked.width, picked.height)
    }

    private fun setupSurfaceCallbacks() {
        frontSurfaceView.surfaceTextureListener = makeSurfaceListener(isFront = true, isFullscreen = false)
        rearSurfaceView.surfaceTextureListener = makeSurfaceListener(isFront = false, isFullscreen = false)
        fullscreenFrontSurfaceView.surfaceTextureListener = makeSurfaceListener(isFront = true, isFullscreen = true)
        fullscreenRearSurfaceView.surfaceTextureListener = makeSurfaceListener(isFront = false, isFullscreen = true)
    }

    private fun makeSurfaceListener(isFront: Boolean, isFullscreen: Boolean): TextureView.SurfaceTextureListener {
        return object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                prepareSurfaceTexture(surface, isFront, isFullscreen)
                if (isFullscreen) startFullscreenCamerasIfReady() else startCamerasIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                requestPreviewTransform(isFront, isFullscreen)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /** Set the buffer size on the SurfaceTexture to the selected resolution's camera size. */
    private fun prepareSurfaceTexture(surface: SurfaceTexture, isFront: Boolean, isFullscreen: Boolean) {
        val cfg = settingsStore.config.value
        val streamConfig = if (isFront) cfg.frontConfig else cfg.rearConfig
        val orientation = currentStreamOrientation(forFullscreen = isFullscreen)
        surface.setDefaultBufferSize(
            streamConfig.resolution.widthFor(orientation),
            streamConfig.resolution.heightFor(orientation)
        )
    }

    private fun setupRecordingButton() {
        binding.recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        // Fullscreen mirror of the main Record button — same toggle behaviour, no text
        // label (icon-only). The icon swaps between the red circle (idle / start) and
        // the green square (recording / stop); applyRecordButtonState() keeps it in sync
        // with isRecording on every start/stop.
        binding.btnRecordFullscreen.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        // Initial state for both buttons (no recording in progress on a fresh activity).
        applyMainRecordButtonState()
        applyFullscreenRecordButtonState()
    }

    private fun setupFullscreenButton() {
        binding.btnFullscreenPreview.setOnClickListener { toggleFullscreen() }
        binding.btnExitFullscreen.setOnClickListener { toggleFullscreen() }
    }

    /**
     * Wire the "Restore Cameras" button. When clicked, it stops the current
     * preview and restarts it through the same dual-surface capture-session
     * path the fullscreen enter used to use (stopCurrentPreviewAndWait +
     * startCamerasIfReady / startFullscreenCamerasIfReady). This is a
     * full refresh: both previews will block briefly while the cameras are
     * re-opened, but the dual-surface design means the fullscreen-side
     * preview (when the user is in fullscreen) re-uses the same code path
     * and ends up in the correct state. Disabled while a recording is in
     * progress — see [lockOrientationControls].
     */
    private fun setupRestoreCamerasButton() {
        binding.btnRestoreCameras.setOnClickListener {
            // Guard against concurrent calls: if a start is already in flight
            // the re-entrancy guard inside restartPreview will skip, so bounce
            // off here too to keep the button from being a no-op and leaving
            // the user wondering why nothing happened.
            if (camerasStarting) {
                Log.d(TAG, "btnRestoreCameras ignored: camerasStarting is true")
                return@setOnClickListener
            }
            // The button is disabled while recording (lockOrientationControls),
            // but a defensive check keeps the behaviour correct if the disabled
            // state ever lags the actual recording state (e.g. focus timing).
            if (isRecording) {
                Log.d(TAG, "btnRestoreCameras ignored: recording in progress")
                return@setOnClickListener
            }
            Log.d(TAG, "btnRestoreCameras: restarting preview")
            // Use restartPreview() — the exact same path the camera-spinner
            // callback takes when the user changes a camera. That path is the
            // proven-working one for "re-open the cameras and show the live
            // preview again"; using it here means the button behaves identically
            // to a no-op camera change. In particular, restartPreview() reads
            // cfg.frontCameraId / cfg.rearCameraId from the settings store, so
            // the previews come back showing exactly the cameras currently
            // selected in the spinners at the moment the button is pressed.
            //
            // Note: we deliberately do NOT call stopCurrentPreviewAndWait() or
            // reset camerasStarting here — restartPreview() does both itself
            // (it owns the full close-then-reopen cycle and the re-entrancy
            // guard). Calling them up-front would either be a no-op or would
            // race the guard inside restartPreview().
            restartPreview()
        }
    }

    /**
     * Keep the main Record button's icon, label and background in sync with the
     * current [isRecording] state. Mirrors the icon swap the fullscreen button does
     * (red circle `ic_record_circle` when idle, green square `ic_stop_square` when
     * recording) so the two surfaces tell the same story. Called from
     * [setupRecordingButton] (initial state), [startRecording] (after `isRecording`
     * flips to true) and [stopRecording] (after it flips back to false).
     */
    private fun applyMainRecordButtonState() {
        if (isRecording) {
            binding.recordButton.setIconResource(R.drawable.ic_stop_square)
            binding.recordButton.text = getString(R.string.stop_recording)
            binding.recordButton.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.recordButton.setIconResource(R.drawable.ic_record_circle)
            binding.recordButton.text = getString(R.string.start_recording)
            binding.recordButton.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    /**
     * Update the fullscreen Record button's icon and content
     * description so it always reflects the current [isRecording] state
     *
     * The icon is tinted white in XML so the colour you read on the button comes
     * from the stroke — keeping the icon and the outline in lock-step (red ↔ red,
     * green ↔ green) reads as a single, consistent visual unit.
     *
     * Called from [startRecording] / [stopRecording] and once from
     * [setupRecordingButton] so the initial state after a config change or
     * fullscreen entry is correct.
     */
    private fun applyFullscreenRecordButtonState() {
        if (isRecording) {
            binding.btnRecordFullscreen.setIconResource(R.drawable.ic_stop_square)
            binding.btnRecordFullscreen.contentDescription = getString(R.string.record_fullscreen_stop)
        } else {
            binding.btnRecordFullscreen.setIconResource(R.drawable.ic_record_circle)
            binding.btnRecordFullscreen.contentDescription = getString(R.string.record_fullscreen_start)
        }
    }

    /**
     * Intercept the back button:
     *  - In the Fullscreen Preview overlay: behave as if the user tapped the
     *    "Indietro" / Exit button (i.e. return to the main activity view).
     *  - Otherwise (we're in the main activity): show a confirmation dialog
     *    asking if the user wants to close the app. Same dialog UX as
     *    MicGainLevelerApp (Cancel + Close).
     *
     * During a recording the dialog is suppressed — pressing back from the
     * main view while recording does nothing, so the recording can't be
     * accidentally abandoned.
     */
    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    exitFullscreen()
                    return
                }
                if (isRecording) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.back_blocked_while_recording,
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                AlertDialogHelper.showWithAction(
                    context = this@MainActivity,
                    title = getString(R.string.exit_app_title),
                    message = getString(R.string.exit_app_message),
                    actionLabel = getString(R.string.exit_app_confirm),
                    onAction = { finish() }
                )
            }
        })
    }

    private fun setupFullscreenControlsToggle() {
        binding.btnToggleFullscreenControls.setOnClickListener {
            fullscreenControlsVisible = !fullscreenControlsVisible
            applyFullscreenControlsVisibility()
        }
    }

    private fun applyFullscreenControlsVisibility() {
        val v = if (fullscreenControlsVisible) View.VISIBLE else View.GONE
        binding.fullscreenBottomScroll.visibility = v
        binding.btnToggleFullscreenControls.setText(
            if (fullscreenControlsVisible) R.string.hide_controls else R.string.show_controls
        )
    }

    /**
     * True when the current activity theme is the Light variant. The theme is fixed for the
     * lifetime of the activity (set in onCreate via setTheme()); LocaleManager forces the
     * configuration uiMode to mirror the saved theme, so reading it back here agrees with
     * what was actually applied to the inflated views. SYSTEM mode is resolved by mirroring
     * the same logic that [themeManager.styleRes] uses in onCreate.
     */
    private fun isLightThemeActive(): Boolean {
        return themeManager.styleRes(preferences.themeMode, this) == R.style.Theme_DualCameraRecorder_Light
    }

    /**
     * Apply the Light-theme overrides to the Fullscreen Preview overlay controls. In the
     * Dark theme the XML defaults (white text on tonal background, theme-default checkbox
     * tint) already look right on top of the dark preview background, so we leave them
     * alone. In the Light theme the preview background is white, so the controls need
     * explicit contrast: black text on a white background for the "Hide controls" and
     * "Back" buttons, and a white square tint for the four checkboxes that sit BELOW
     * the audio meters (the autofocus-on-tap checkbox is in the top bar — outside this
     * group — and keeps its existing tint).
     *
     * Called from [enterFullscreen] so the colors are always correct for the current
     * theme at the moment the overlay becomes visible (the activity is recreated on
     * theme change, so this is sufficient — no separate configuration-change hook).
     */
    private fun applyFullscreenOverlayColors() {
        if (!isLightThemeActive()) return
        val whiteTint = ColorStateList.valueOf(Color.WHITE)

        // "Hide controls" / "Show controls" button — black text on white background.
        binding.btnToggleFullscreenControls.setTextColor(Color.BLACK)
        binding.btnToggleFullscreenControls.backgroundTintList = whiteTint
        // "Back" button — black text on white background.
        binding.btnExitFullscreen.setTextColor(Color.BLACK)
        binding.btnExitFullscreen.backgroundTintList = whiteTint

        // Checkbox squares below the audio meters (manual focus + manual adjustments
        // for both front and rear cameras) — white square so the box stays visible
        // on the white preview surface.
        binding.manualFocusCheckboxFullscreenFront.buttonTintList = whiteTint
        binding.manualAdjustmentsCheckboxFullscreenFront.buttonTintList = whiteTint
        binding.manualFocusCheckboxFullscreenRear.buttonTintList = whiteTint
        binding.manualAdjustmentsCheckboxFullscreenRear.buttonTintList = whiteTint
    }

    private fun setupFullscreenLandscapeCheckbox() {
        // The fullscreen landscape checkbox was removed: the fullscreen view follows the
        // activity's main landscape state (set in onCreate from preferences and toggled by
        // the main landscape checkbox). This is a no-op now but kept to avoid touching
        // the init order in initListeners().
    }

    private fun applyFullscreenOrientation() {
        // The fullscreen follows the activity's main landscape mode (set in onCreate from
        // preferences and toggled by the main landscape checkbox). The fullscreen no longer
        // has its own independent orientation toggle.
        requestedOrientation =
            if (isLandscapeMode) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun setupSettingsAndFlashButtons() {
        binding.btnSettings.setOnClickListener { showSettingsSheet() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnFlashFullscreen.setOnClickListener { toggleFlash() }

        frontSurfaceView.setOnTouchListener { _, event ->
            handleTapToFocus(event.x, event.y, frontSurfaceView, isFront = true)
            true
        }
        rearSurfaceView.setOnTouchListener { _, event ->
            handleTapToFocus(event.x, event.y, rearSurfaceView, isFront = false)
            true
        }
        fullscreenFrontSurfaceView.setOnTouchListener { _, event ->
            handleTapToFocus(event.x, event.y, fullscreenFrontSurfaceView, isFront = true)
            true
        }
        fullscreenRearSurfaceView.setOnTouchListener { _, event ->
            handleTapToFocus(event.x, event.y, fullscreenRearSurfaceView, isFront = false)
            true
        }
    }

    /**
     * Tap-to-focus on a preview.
     *
     * - If the "Autofocus on tap" checkbox is UNCHECKED, this is a no-op (the user
     *   has chosen to use the flash button instead, so taps do nothing).
     * - If "Manual Focus" is checked, the seekbar's current value is re-applied so the
     *   change is visible immediately. The camera stays in manual-focus mode (the user
     *   is responsible for picking the focus distance via the seekbar / -+ buttons).
     * - If "Manual Focus" is NOT checked, we trigger an autofocus cycle at the tap point
     *   (regardless of whether "Manual Adjustments" is enabled — the focus mode is
     *   independent of the ISO/exposure toggles).
     */
    private fun handleTapToFocus(x: Float, y: Float, view: TextureView, isFront: Boolean) {
        // Taps are no-ops when "Autofocus on tap" is off — the user has explicitly
        // disabled the feature and is using the flash button instead.
        if (!settingsStore.config.value.autofocusOnTap) return

        val (reticle, checkbox) = when (view) {
            frontSurfaceView -> binding.frontFocusReticle to binding.manualFocusCheckboxFront
            rearSurfaceView -> binding.rearFocusReticle to binding.manualFocusCheckboxRear
            fullscreenFrontSurfaceView -> binding.fullscreenFrontFocusReticle to binding.manualFocusCheckboxFullscreenFront
            fullscreenRearSurfaceView -> binding.fullscreenRearFocusReticle to binding.manualFocusCheckboxFullscreenRear
            else -> return
        }

        // Show the reticle at the tap point as visual feedback.
        reticle.visibility = View.VISIBLE
        reticle.showFocusAt(x, y)
        reticle.postDelayed({ reticle.visibility = View.GONE }, 100)

        if (checkbox.isChecked) {
            // Manual focus: re-apply the seekbar's current focus distance so the change
            // is visible immediately.
            if (isFront) applyFocusToFrontCamera() else applyFocusToRearCamera()
        } else {
            // Auto focus: trigger an AF cycle at the tap point. Independent of whether
            // "Manual Adjustments" is enabled — focus is its own checkbox.
            val viewW = view.width.toFloat().coerceAtLeast(1f)
            val viewH = view.height.toFloat().coerceAtLeast(1f)
            val normX = (x / viewW).coerceIn(0f, 1f)
            val normY = (y / viewH).coerceIn(0f, 1f)
            if (isFront) {
                dualCameraRecorder.triggerAutoFocusFront(normX, normY)
            } else {
                dualCameraRecorder.triggerAutoFocusRear(normX, normY)
            }
        }
    }

    private fun setupLandscapeCheckbox() {
        binding.landscapeCheckbox.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            if (isRecording) {
                // Locked during recording — revert the checkbox to reflect reality.
                binding.landscapeCheckbox.isChecked = isLandscapeMode
                Toast.makeText(this, R.string.landscape_locked_while_recording, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            setLandscapeMode(checked)
        }
    }

    private fun setLandscapeMode(enabled: Boolean) {
        bindingInProgress = true
        binding.landscapeCheckbox.isChecked = enabled
        if (isLandscapeMode != enabled) {
            isLandscapeMode = enabled
            requestedOrientation =
                if (enabled) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Landscape preference is intentionally NOT persisted: the app must always
            // start in portrait mode regardless of the last session's choice.

            // Keep the main preview container in a horizontal layout (front left, rear right)
            // in BOTH portrait and landscape — the user asked for the landscape view to mirror
            // the fullscreen overlay (side-by-side cards) while the screen stays in landscape
            // orientation. The card sizing itself adapts to the available width/height via
            // applyPreviewCardSizing.
            binding.previewContainer.orientation = LinearLayout.HORIZONTAL

            // Resize the cards so they fit side-by-side in the landscape screen height.
            applyPreviewCardSizing(enabled)

            // Push the target rotation to the camera controllers so the HAL applies
            // the right rotation to the preview frames. We use the activity's
            // orientation (not the physical sensor rotation) because the activity is
            // locked to portrait/landscape via requestedOrientation. The orientation
            // change is asynchronous, so we use a post() to let the framework settle
            // into the new rotation first.
            val targetRot = if (enabled) Surface.ROTATION_90 else Surface.ROTATION_0
            binding.root.post {
                dualCameraRecorder.setTargetRotation(targetRot)
            }

            // Refresh resolution labels (3:4 vs 4:3) and restore preview with the new aspect.
            rebuildResolutionAdapters()
            restartPreview()
            requestPreviewTransforms()

            // Scroll to the previews so the user actually sees them after the screen rotates
            // (without this, they end up below the fold and the screen looks empty). Wait for
            // the layout to settle first, then scroll. The orientation change is asynchronous
            // and can take a few hundred ms; using a one-shot global-layout listener ensures
            // we read the correct previewContainer.top AFTER the new layout.
            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val y = binding.previewContainer.top
                    if (y >= 0) binding.rootScroll.smoothScrollTo(0, y)
                }
            }
            binding.root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
        bindingInProgress = false
    }

    /**
     * "Autofocus on tap" checkbox — switches between two mutually exclusive modes:
     *  - checked: tap-to-focus is enabled on every preview surface, and the flash
     *    button is disabled.
     *  - unchecked: the flash button is enabled and the touch listeners are
     *    no-ops (taps do nothing).
     *
     * The state is persisted via [settingsStore] so the user's choice survives
     * app restarts. The two checkboxes (main activity + fullscreen overlay)
     * are kept in sync by routing both through the same settings field, and
     * [syncLocalFromStore] re-derives the flash button enable state on every
     * settings change.
     */
    private fun setupAutofocusOnTapCheckbox() {
        binding.autofocusOnTapCheckbox.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            settingsStore.updateAutofocusOnTap(checked)
            // The fullscreen checkbox is bound to the same state via
            // syncLocalFromStore, so toggling the main one automatically
            // mirrors the fullscreen overlay.
        }
        binding.autofocusOnTapCheckboxFullscreen.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            settingsStore.updateAutofocusOnTap(checked)
        }
    }

    /**
     * In landscape the preview cards are laid out side-by-side (matching the fullscreen
     * overlay layout) so the user can see both cameras at once. The card height is capped
     * to the available landscape height (~0.8 of the screen height) and the width is split
     * evenly between front/rear via layout_width=0dp + weight=1.
     *
     * In portrait the cards also share the row via 0dp + weight=1 but use the original
     * 220dp height so the card stays compact.
     */
    private fun applyPreviewCardSizing(landscape: Boolean) {
        // In both modes the cards share a row via weight=1, so each card's width is
        // roughly half the available width. The user asked for the previews to keep
        // a 4:3 aspect ratio, so we size the card height from the per-card width.
        val marginPx = 5.dpToPx()
        val lpFront = binding.frontPreviewCard.layoutParams as android.widget.LinearLayout.LayoutParams
        val lpRear = binding.rearPreviewCard.layoutParams as android.widget.LinearLayout.LayoutParams
        // In portrait, the card is just 220dp tall (compact card under the controls).
        // In landscape, set the height from the actual measured card width so the card
        // is always 4:3 even when the parent (previewContainer) is narrower than the
        // full screen (because of the parent padding, nav bar, etc.).
        lpFront.width = 0  // 0dp + weight=1 in the XML
        lpRear.width = 0
        if (landscape) {
            lpFront.bottomMargin = 0
            lpFront.marginEnd = marginPx
            lpRear.topMargin = 0
            lpRear.marginStart = 0
        } else {
            lpFront.bottomMargin = 0
            lpFront.marginEnd = marginPx
            lpRear.topMargin = 0
            lpRear.marginStart = marginPx
        }
        binding.frontPreviewCard.layoutParams = lpFront
        binding.rearPreviewCard.layoutParams = lpRear
        // Wait for the actual layout (the activity's orientation change is async, so the
        // card width we read right after a toggle is still the previous orientation's).
        // We store the listener on the view's tag so it can be removed later; the listener
        // is removed once the height is stable across two consecutive layout passes.
        removeCardSizingListener()
        val tagKey = R.id.frontPreviewCard  // any stable view id is fine as a tag key
        val treeObs = binding.root.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            private var lastWidth = 0
            private var stableCount = 0
            override fun onGlobalLayout() {
                val cardW = binding.frontPreviewCard.width
                if (cardW <= 0) return
                if (cardW == lastWidth) {
                    stableCount++
                    if (stableCount >= 2) {
                        // Width is stable — remove the listener and apply the final size.
                        treeObs.removeOnGlobalLayoutListener(this)
                        binding.root.setTag(tagKey, null)
                        applyTargetHeight(landscape, cardW)
                        return
                    }
                } else {
                    lastWidth = cardW
                    stableCount = 0
                }
                applyTargetHeight(landscape, cardW)
            }
        }
        binding.root.setTag(tagKey, listener)
        treeObs.addOnGlobalLayoutListener(listener)
    }

    private fun removeCardSizingListener() {
        val tagKey = R.id.frontPreviewCard
        val listener = binding.root.getTag(tagKey) as? android.view.ViewTreeObserver.OnGlobalLayoutListener ?: return
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        binding.root.setTag(tagKey, null)
    }

    private fun applyTargetHeight(landscape: Boolean, cardW: Int) {
        val targetHeight = if (landscape) (cardW * 3 / 4) else 220.dpToPx()
        val lpf = binding.frontPreviewCard.layoutParams as android.widget.LinearLayout.LayoutParams
        val lpr = binding.rearPreviewCard.layoutParams as android.widget.LinearLayout.LayoutParams
        if (lpf.height == targetHeight && lpr.height == targetHeight) return
        lpf.height = targetHeight
        lpr.height = targetHeight
        binding.frontPreviewCard.layoutParams = lpf
        binding.rearPreviewCard.layoutParams = lpr
        requestPreviewTransforms()
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun setupCameraSpinners() {
        binding.frontCameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val cam = frontCameraOptions[position]
                val cfg = settingsStore.config.value
                if (cfg.frontCameraId == cam.cameraId) return // No change
                settingsStore.updateFrontCameraId(cam.cameraId)
                // Block capture from BOTH cameras immediately so an in-flight tap can't
                // trigger a capture against the OLD cameras. The block is cleared again
                // by restartPreview() once the new pair is set up.
                dualCameraRecorder.setCaptureBlocked(true)
                if (camerasStarting) {
                    // The retry loop is in flight — queue the change so it lands when the
                    // guard releases instead of being silently dropped.
                    pendingCameraChange = true
                    Log.d(TAG, "Front camera change queued: camerasStarting is true")
                } else {
                    restartPreview()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.rearCameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val cam = rearCameraOptions[position]
                val cfg = settingsStore.config.value
                if (cfg.rearCameraId == cam.cameraId) return // No change
                settingsStore.updateRearCameraId(cam.cameraId)
                updateFlashButton(cam.hasFlash)
                // Block capture from BOTH cameras immediately (see comment above).
                dualCameraRecorder.setCaptureBlocked(true)
                if (camerasStarting) {
                    pendingCameraChange = true
                    Log.d(TAG, "Rear camera change queued: camerasStarting is true")
                } else {
                    restartPreview()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupResolutionFpsSpinners() {
        rebuildResolutionAdapters()

        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsLabels())
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.frontFpsSpinner.adapter = fpsAdapter
        binding.rearFpsSpinner.adapter = fpsAdapter

        binding.frontResolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val res = StreamResolution.entries[position]
                val cfg = settingsStore.config.value.frontConfig
                settingsStore.updateFrontConfig(cfg.copy(resolution = res))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.rearResolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val res = StreamResolution.entries[position]
                val cfg = settingsStore.config.value.rearConfig
                settingsStore.updateRearConfig(cfg.copy(resolution = res))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.frontFpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val fps = StreamFps.entries[position]
                val cfg = settingsStore.config.value.frontConfig
                settingsStore.updateFrontConfig(cfg.copy(fps = fps))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.rearFpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val fps = StreamFps.entries[position]
                val cfg = settingsStore.config.value.rearConfig
                settingsStore.updateRearConfig(cfg.copy(fps = fps))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    /** Rebuild the two resolution spinners with labels for the current orientation. */
    private fun rebuildResolutionAdapters() {
        val prevFrontSel = binding.frontResolutionSpinner.selectedItemPosition
        val prevRearSel = binding.rearResolutionSpinner.selectedItemPosition
        val orientation = currentStreamOrientation()
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            StreamResolution.entries.map { it.labelFor(orientation) })
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.frontResolutionSpinner.adapter = resAdapter
        binding.rearResolutionSpinner.adapter = resAdapter
        if (prevFrontSel in 0 until StreamResolution.entries.size) binding.frontResolutionSpinner.setSelection(prevFrontSel, false)
        if (prevRearSel in 0 until StreamResolution.entries.size) binding.rearResolutionSpinner.setSelection(prevRearSel, false)
    }

    private fun setupBitrateSpinners() {
        val bitrateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrateLabels())
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.frontBitrateSpinner.adapter = bitrateAdapter
        binding.rearBitrateSpinner.adapter = bitrateAdapter

        binding.frontBitrateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val bitrate = StreamBitrate.entries[position]
                val cfg = settingsStore.config.value.frontConfig
                settingsStore.updateFrontConfig(cfg.copy(bitrate = bitrate))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.rearBitrateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val bitrate = StreamBitrate.entries[position]
                val cfg = settingsStore.config.value.rearConfig
                settingsStore.updateRearConfig(cfg.copy(bitrate = bitrate))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    /**
     * Audio settings (format / bitrate / sample rate). Mirrors the spinner
     * pattern used by MicGainLevelerApp:
     *  - format: WAV16 / WAV24 / AAC (always shown)
     *  - bitrate: AAC kbps — only visible when format = AAC, hidden for WAV
     *  - sample rate: Hz — always shown
     * The values are persisted via PreferencesRepository (which validates
     * against the device's actual capabilities) and exposed as
     * ALLOWED_BITRATES / ALLOWED_SAMPLE_RATES.
     */
    private fun setupAudioSpinners() {
        // Audio format is fixed to AAC (the only format MediaRecorder/MPEG_4 supports
        // for audio inside the MP4 container). Show "AAC" as a static label and disable
        // the spinner so the layout has a stable shape.
        val formatAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioFormatLabels())
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioFormatSpinner.adapter = formatAdapter
        binding.audioFormatSpinner.setSelection(0, false)
        binding.audioFormatSpinner.isEnabled = false
        applyAudioBitrateVisibility()

        // AAC bitrate spinner.
        val bitrates = PreferencesRepository.ALLOWED_BITRATES
        val bitrateLabels = bitrates.map { getString(R.string.audio_bitrate_format, it) }
        val bitrateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrateLabels)
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioBitrateSpinner.adapter = bitrateAdapter
        binding.audioBitrateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val kbps = bitrates.getOrNull(position) ?: return
                if (kbps != settingsStore.config.value.audioBitrateKbps) {
                    settingsStore.updateAudioBitrateKbps(kbps)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Sample rate spinner.
        val sampleRates = PreferencesRepository.ALLOWED_SAMPLE_RATES
        val sampleRateLabels = sampleRates.map { getString(R.string.audio_sample_rate_format, it) }
        val sampleRateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sampleRateLabels)
        sampleRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioSampleRateSpinner.adapter = sampleRateAdapter
        binding.audioSampleRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (bindingInProgress) return
                val hz = sampleRates.getOrNull(position) ?: return
                if (hz != settingsStore.config.value.audioSampleRateHz) {
                    settingsStore.updateAudioSampleRateHz(hz)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    /** Format labels for the audio format spinner. Format is fixed to AAC for MP4. */
    private fun audioFormatLabels(): List<String> = listOf(
        getString(R.string.audio_format_aac)
    )

    /** Show / hide the AAC bitrate spinner. Always visible because the format is fixed to AAC. */
    private fun applyAudioBitrateVisibility() {
        binding.audioBitrateLabel.visibility = View.VISIBLE
        binding.audioBitrateSpinner.visibility = View.VISIBLE
    }

    // ==================== Manual Controls (separate front & rear) ====================

    private fun setupManualControls() {
        val isoAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, isoLabels.toList())
        isoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val exposureAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, exposureLabels.toList())
        exposureAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // --- Front camera manual focus ---
        binding.manualFocusCheckboxFront.setOnCheckedChangeListener { _, isChecked ->
            binding.manualFocusControlsFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusControlsFullscreenFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusCheckboxFullscreenFront.setOnCheckedChangeListener(null)
            binding.manualFocusCheckboxFullscreenFront.isChecked = isChecked
            binding.manualFocusCheckboxFullscreenFront.setOnCheckedChangeListener(fullscreenFrontFocusListener)
            isFrontManualFocus = isChecked
            toggleManualFocusFront(isChecked)
        }
        binding.manualFocusCheckboxFullscreenFront.setOnCheckedChangeListener(fullscreenFrontFocusListener)

        // --- Rear camera manual focus ---
        binding.manualFocusCheckboxRear.setOnCheckedChangeListener { _, isChecked ->
            binding.manualFocusControlsRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusControlsFullscreenRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusCheckboxFullscreenRear.setOnCheckedChangeListener(null)
            binding.manualFocusCheckboxFullscreenRear.isChecked = isChecked
            binding.manualFocusCheckboxFullscreenRear.setOnCheckedChangeListener(fullscreenRearFocusListener)
            isRearManualFocus = isChecked
            toggleManualFocusRear(isChecked)
        }
        binding.manualFocusCheckboxFullscreenRear.setOnCheckedChangeListener(fullscreenRearFocusListener)

        // --- Front camera manual adjustments (ISO, Exposure) ---
        binding.manualAdjustmentsCheckboxFront.setOnCheckedChangeListener { _, isChecked ->
            binding.manualAdjustmentsControlsFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsControlsFullscreenFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsCheckboxFullscreenFront.setOnCheckedChangeListener(null)
            binding.manualAdjustmentsCheckboxFullscreenFront.isChecked = isChecked
            binding.manualAdjustmentsCheckboxFullscreenFront.setOnCheckedChangeListener(fullscreenFrontAdjustmentsListener)
            isFrontManualAdjustments = isChecked
            applyManualAdjustmentsFront(isChecked)
        }
        binding.manualAdjustmentsCheckboxFullscreenFront.setOnCheckedChangeListener(fullscreenFrontAdjustmentsListener)

        // --- Rear camera manual adjustments (ISO, Exposure) ---
        binding.manualAdjustmentsCheckboxRear.setOnCheckedChangeListener { _, isChecked ->
            binding.manualAdjustmentsControlsRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsControlsFullscreenRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsCheckboxFullscreenRear.setOnCheckedChangeListener(null)
            binding.manualAdjustmentsCheckboxFullscreenRear.isChecked = isChecked
            binding.manualAdjustmentsCheckboxFullscreenRear.setOnCheckedChangeListener(fullscreenRearAdjustmentsListener)
            isRearManualAdjustments = isChecked
            applyManualAdjustmentsRear(isChecked)
        }
        binding.manualAdjustmentsCheckboxFullscreenRear.setOnCheckedChangeListener(fullscreenRearAdjustmentsListener)

        // --- ISO spinners (separate per front/rear) ---
        binding.isoSpinnerFront.adapter = isoAdapter
        binding.isoSpinnerFullscreenFront.adapter = isoAdapter
        binding.isoSpinnerRear.adapter = isoAdapter
        binding.isoSpinnerFullscreenRear.adapter = isoAdapter

        binding.isoSpinnerFront.onItemSelectedListener = isoSpinnerListenerFront
        binding.isoSpinnerFullscreenFront.onItemSelectedListener = isoSpinnerListenerFront
        binding.isoSpinnerRear.onItemSelectedListener = isoSpinnerListenerRear
        binding.isoSpinnerFullscreenRear.onItemSelectedListener = isoSpinnerListenerRear

        // --- Exposure spinners (separate per front/rear) ---
        binding.exposureSpinnerFront.adapter = exposureAdapter
        binding.exposureSpinnerFullscreenFront.adapter = exposureAdapter
        binding.exposureSpinnerRear.adapter = exposureAdapter
        binding.exposureSpinnerFullscreenRear.adapter = exposureAdapter

        binding.exposureSpinnerFront.onItemSelectedListener = exposureSpinnerListenerFront
        binding.exposureSpinnerFullscreenFront.onItemSelectedListener = exposureSpinnerListenerFront
        binding.exposureSpinnerRear.onItemSelectedListener = exposureSpinnerListenerRear
        binding.exposureSpinnerFullscreenRear.onItemSelectedListener = exposureSpinnerListenerRear
    }

    private val fullscreenFrontFocusListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            binding.manualFocusControlsFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusControlsFullscreenFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusCheckboxFront.setOnCheckedChangeListener(null)
            binding.manualFocusCheckboxFront.isChecked = isChecked
            binding.manualFocusCheckboxFront.setOnCheckedChangeListener { _, v ->
                binding.manualFocusCheckboxFullscreenFront.setOnCheckedChangeListener(null)
                binding.manualFocusCheckboxFullscreenFront.isChecked = v
                binding.manualFocusCheckboxFullscreenFront.setOnCheckedChangeListener(fullscreenFrontFocusListener)
                binding.manualFocusControlsFront.visibility = if (v) View.VISIBLE else View.GONE
                binding.manualFocusControlsFullscreenFront.visibility = if (v) View.VISIBLE else View.GONE
                isFrontManualFocus = v
                toggleManualFocusFront(v)
            }
            isFrontManualFocus = isChecked
            toggleManualFocusFront(isChecked)
        }

    private val fullscreenRearFocusListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            binding.manualFocusControlsRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusControlsFullscreenRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusCheckboxRear.setOnCheckedChangeListener(null)
            binding.manualFocusCheckboxRear.isChecked = isChecked
            binding.manualFocusCheckboxRear.setOnCheckedChangeListener { _, v ->
                binding.manualFocusCheckboxFullscreenRear.setOnCheckedChangeListener(null)
                binding.manualFocusCheckboxFullscreenRear.isChecked = v
                binding.manualFocusCheckboxFullscreenRear.setOnCheckedChangeListener(fullscreenRearFocusListener)
                binding.manualFocusControlsRear.visibility = if (v) View.VISIBLE else View.GONE
                binding.manualFocusControlsFullscreenRear.visibility = if (v) View.VISIBLE else View.GONE
                isRearManualFocus = v
                toggleManualFocusRear(v)
            }
            isRearManualFocus = isChecked
            toggleManualFocusRear(isChecked)
        }

    private val fullscreenFrontAdjustmentsListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            binding.manualAdjustmentsControlsFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsControlsFullscreenFront.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsCheckboxFront.setOnCheckedChangeListener(null)
            binding.manualAdjustmentsCheckboxFront.isChecked = isChecked
            binding.manualAdjustmentsCheckboxFront.setOnCheckedChangeListener { _, v ->
                binding.manualAdjustmentsCheckboxFullscreenFront.setOnCheckedChangeListener(null)
                binding.manualAdjustmentsCheckboxFullscreenFront.isChecked = v
                binding.manualAdjustmentsCheckboxFullscreenFront.setOnCheckedChangeListener(fullscreenFrontAdjustmentsListener)
                binding.manualAdjustmentsControlsFront.visibility = if (v) View.VISIBLE else View.GONE
                binding.manualAdjustmentsControlsFullscreenFront.visibility = if (v) View.VISIBLE else View.GONE
                isFrontManualAdjustments = v
                applyManualAdjustmentsFront(v)
            }
            isFrontManualAdjustments = isChecked
            applyManualAdjustmentsFront(isChecked)
        }

    private val fullscreenRearAdjustmentsListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            binding.manualAdjustmentsControlsRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsControlsFullscreenRear.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsCheckboxRear.setOnCheckedChangeListener(null)
            binding.manualAdjustmentsCheckboxRear.isChecked = isChecked
            binding.manualAdjustmentsCheckboxRear.setOnCheckedChangeListener { _, v ->
                binding.manualAdjustmentsCheckboxFullscreenRear.setOnCheckedChangeListener(null)
                binding.manualAdjustmentsCheckboxFullscreenRear.isChecked = v
                binding.manualAdjustmentsCheckboxFullscreenRear.setOnCheckedChangeListener(fullscreenRearAdjustmentsListener)
                binding.manualAdjustmentsControlsRear.visibility = if (v) View.VISIBLE else View.GONE
                binding.manualAdjustmentsControlsFullscreenRear.visibility = if (v) View.VISIBLE else View.GONE
                isRearManualAdjustments = v
                applyManualAdjustmentsRear(v)
            }
            isRearManualAdjustments = isChecked
            applyManualAdjustmentsRear(isChecked)
        }

    private val isoSpinnerListenerFront = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (bindingInProgress) return
            val iso = if (position == 0) null else isoValues[position - 1]
            applyIsoFront(iso)
        }
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private val isoSpinnerListenerRear = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (bindingInProgress) return
            val iso = if (position == 0) null else isoValues[position - 1]
            applyIsoRear(iso)
        }
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private val exposureSpinnerListenerFront = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (bindingInProgress) return
            val exposure = if (position == 0) null else exposureNanosValues[position - 1]
            applyExposureTimeFront(exposure)
        }
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private val exposureSpinnerListenerRear = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (bindingInProgress) return
            val exposure = if (position == 0) null else exposureNanosValues[position - 1]
            applyExposureTimeRear(exposure)
        }
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private fun setupFocusSeekBar() {
        // Front camera focus seekbar
        binding.focusSeekBarFront.max = 1000
        binding.focusSeekBarFront.progress = (frontFocusDistance * 1000).toInt()
        binding.focusSeekBarFront.setOnSeekBarChangeListener(frontSeekListener)

        binding.focusSeekBarFullscreenFront.max = 1000
        binding.focusSeekBarFullscreenFront.progress = (frontFocusDistance * 1000).toInt()
        binding.focusSeekBarFullscreenFront.setOnSeekBarChangeListener(fullscreenFrontSeekListener)

        // Rear camera focus seekbar
        binding.focusSeekBarRear.max = 1000
        binding.focusSeekBarRear.progress = (rearFocusDistance * 1000).toInt()
        binding.focusSeekBarRear.setOnSeekBarChangeListener(rearSeekListener)

        binding.focusSeekBarFullscreenRear.max = 1000
        binding.focusSeekBarFullscreenRear.progress = (rearFocusDistance * 1000).toInt()
        binding.focusSeekBarFullscreenRear.setOnSeekBarChangeListener(fullscreenRearSeekListener)

        // Front focus buttons
        binding.btnFocusMinusFront.setOnClickListener { changeFocusFront(-50) }
        binding.btnFocusPlusFront.setOnClickListener { changeFocusFront(50) }
        binding.btnFocusMinusFullscreenFront.setOnClickListener { changeFocusFront(-50) }
        binding.btnFocusPlusFullscreenFront.setOnClickListener { changeFocusFront(50) }

        // Rear focus buttons
        binding.btnFocusMinusRear.setOnClickListener { changeFocusRear(-50) }
        binding.btnFocusPlusRear.setOnClickListener { changeFocusRear(50) }
        binding.btnFocusMinusFullscreenRear.setOnClickListener { changeFocusRear(-50) }
        binding.btnFocusPlusFullscreenRear.setOnClickListener { changeFocusRear(50) }
    }

    // Programmatic progress changes call onProgressChanged with fromUser=false, so the
    // fromUser guard alone prevents the two paired seekbars from recursing into each other.
    private val frontSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                frontFocusDistance = progress / 1000f
                binding.focusSeekBarFullscreenFront.progress = progress
                applyFocusToFrontCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val fullscreenFrontSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                frontFocusDistance = progress / 1000f
                binding.focusSeekBarFront.progress = progress
                applyFocusToFrontCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val rearSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                rearFocusDistance = progress / 1000f
                binding.focusSeekBarFullscreenRear.progress = progress
                applyFocusToRearCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val fullscreenRearSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                rearFocusDistance = progress / 1000f
                binding.focusSeekBarRear.progress = progress
                applyFocusToRearCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // ==================== Zoom SeekBars (per camera, main + fullscreen) ====================
    //
    // Mirrors the AndroidCamera reference's setLinearZoom + SeekBar pattern, but the
    // slider is theme-aware (colorPrimary / colorOutline via the custom progressDrawable)
    // and a single SeekBarListener pair is shared between the main-activity and
    // fullscreen-overlay bars (the two are kept in sync the same way the focus
    // seekbars are).

    private fun setupZoomSeekBar() {
        // Front camera zoom seekbars
        binding.zoomSeekBarFront.max = 100
        binding.zoomSeekBarFront.progress = (frontLinearZoom * 100f).toInt().coerceIn(0, 100)
        binding.zoomSeekBarFront.setOnSeekBarChangeListener(frontZoomSeekListener)

        binding.zoomSeekBarFullscreenFront.max = 100
        binding.zoomSeekBarFullscreenFront.progress = (frontLinearZoom * 100f).toInt().coerceIn(0, 100)
        binding.zoomSeekBarFullscreenFront.setOnSeekBarChangeListener(fullscreenFrontZoomSeekListener)

        // Rear camera zoom seekbars
        binding.zoomSeekBarRear.max = 100
        binding.zoomSeekBarRear.progress = (rearLinearZoom * 100f).toInt().coerceIn(0, 100)
        binding.zoomSeekBarRear.setOnSeekBarChangeListener(rearZoomSeekListener)

        binding.zoomSeekBarFullscreenRear.max = 100
        binding.zoomSeekBarFullscreenRear.progress = (rearLinearZoom * 100f).toInt().coerceIn(0, 100)
        binding.zoomSeekBarFullscreenRear.setOnSeekBarChangeListener(fullscreenRearZoomSeekListener)

        // Front +/- buttons
        binding.btnZoomMinusFront.setOnClickListener { changeZoomFront(-5) }
        binding.btnZoomPlusFront.setOnClickListener { changeZoomFront(5) }
        binding.btnZoomMinusFullscreenFront.setOnClickListener { changeZoomFront(-5) }
        binding.btnZoomPlusFullscreenFront.setOnClickListener { changeZoomFront(5) }

        // Rear +/- buttons
        binding.btnZoomMinusRear.setOnClickListener { changeZoomRear(-5) }
        binding.btnZoomPlusRear.setOnClickListener { changeZoomRear(5) }
        binding.btnZoomMinusFullscreenRear.setOnClickListener { changeZoomRear(-5) }
        binding.btnZoomPlusFullscreenRear.setOnClickListener { changeZoomRear(5) }
    }

    // Programmatic progress changes call onProgressChanged with fromUser=false, so the
    // fromUser guard alone prevents the two paired seekbars from recursing into each other.
    private val frontZoomSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                frontLinearZoom = (progress / 100f).coerceIn(0f, 1f)
                binding.zoomSeekBarFullscreenFront.progress = progress
                applyLinearZoomToFrontCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val fullscreenFrontZoomSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                frontLinearZoom = (progress / 100f).coerceIn(0f, 1f)
                binding.zoomSeekBarFront.progress = progress
                applyLinearZoomToFrontCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val rearZoomSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                rearLinearZoom = (progress / 100f).coerceIn(0f, 1f)
                binding.zoomSeekBarFullscreenRear.progress = progress
                applyLinearZoomToRearCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val fullscreenRearZoomSeekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                rearLinearZoom = (progress / 100f).coerceIn(0f, 1f)
                binding.zoomSeekBarRear.progress = progress
                applyLinearZoomToRearCamera()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // ==================== State Observation ====================

    private fun observeSettings() {
        lifecycleScope.launch {
            settingsStore.config.collect { config ->
                syncLocalFromStore(config)
            }
        }
    }

    private fun syncLocalFromStore(config: DualCameraConfig) {
        bindingInProgress = true

        val frontAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            frontCameraOptions.map { it.label })
        frontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.frontCameraSpinner.adapter = frontAdapter
        val frontSelIdx = frontCameraOptions.indexOfFirst { it.cameraId == config.frontCameraId }
        binding.frontCameraSpinner.setSelection(frontSelIdx.coerceAtLeast(0), false)

        val rearAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            rearCameraOptions.map { it.label })
        rearAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.rearCameraSpinner.adapter = rearAdapter
        val rearSelIdx = rearCameraOptions.indexOfFirst { it.cameraId == config.rearCameraId }
        binding.rearCameraSpinner.setSelection(rearSelIdx.coerceAtLeast(0), false)

        val frontResIdx = StreamResolution.entries.indexOf(config.frontConfig.resolution).coerceAtLeast(0)
        binding.frontResolutionSpinner.setSelection(frontResIdx, false)
        val rearResIdx = StreamResolution.entries.indexOf(config.rearConfig.resolution).coerceAtLeast(0)
        binding.rearResolutionSpinner.setSelection(rearResIdx, false)
        binding.frontFpsSpinner.setSelection(
            StreamFps.entries.indexOf(config.frontConfig.fps).coerceAtLeast(0), false)
        binding.rearFpsSpinner.setSelection(
            StreamFps.entries.indexOf(config.rearConfig.fps).coerceAtLeast(0), false)
        binding.frontBitrateSpinner.setSelection(
            StreamBitrate.entries.indexOf(config.frontConfig.bitrate).coerceAtLeast(0), false)
        binding.rearBitrateSpinner.setSelection(
            StreamBitrate.entries.indexOf(config.rearConfig.bitrate).coerceAtLeast(0), false)

        // Audio settings — format is fixed to AAC, AAC bitrate, sample rate.
        binding.audioFormatSpinner.setSelection(0, false)
        applyAudioBitrateVisibility()
        val allowedBitrates = PreferencesRepository.ALLOWED_BITRATES
        val bitrateIdx = if (allowedBitrates.isEmpty()) 0
            else allowedBitrates.indexOf(config.audioBitrateKbps).let { if (it < 0) allowedBitrates.lastIndex else it }
        binding.audioBitrateSpinner.setSelection(bitrateIdx.coerceAtLeast(0), false)
        val allowedSampleRates = PreferencesRepository.ALLOWED_SAMPLE_RATES
        val sampleRateIdx = if (allowedSampleRates.isEmpty()) 0
            else allowedSampleRates.indexOf(config.audioSampleRateHz).let { if (it < 0) allowedSampleRates.lastIndex else it }
        binding.audioSampleRateSpinner.setSelection(sampleRateIdx.coerceAtLeast(0), false)

        val showDigital = config.meterStyle == MeterStyle.DIGITAL
        binding.digitalMeterFront.visibility = if (showDigital) View.VISIBLE else View.GONE
        binding.digitalMeterRear.visibility = if (showDigital) View.VISIBLE else View.GONE
        binding.analogMeterFront.visibility = if (showDigital) View.GONE else View.VISIBLE
        binding.analogMeterRear.visibility = if (showDigital) View.GONE else View.VISIBLE
        binding.digitalMeterFrontFullscreen.visibility = if (showDigital) View.VISIBLE else View.GONE
        binding.digitalMeterRearFullscreen.visibility = if (showDigital) View.VISIBLE else View.GONE
        binding.analogMeterFrontFullscreen.visibility = if (showDigital) View.GONE else View.VISIBLE
        binding.analogMeterRearFullscreen.visibility = if (showDigital) View.GONE else View.VISIBLE

        updateFlashButton(rearCameraOptions.getOrNull(rearSelIdx)?.hasFlash ?: false)

        // Sync the "Autofocus on tap" checkboxes (main activity + fullscreen
        // overlay) to the persisted state. updateFlashButton below reads
        // config.autofocusOnTap to decide whether the flash button is
        // enabled, so the two controls stay consistent.
        binding.autofocusOnTapCheckbox.isChecked = config.autofocusOnTap
        binding.autofocusOnTapCheckboxFullscreen.isChecked = config.autofocusOnTap

        bindingInProgress = false
    }

    // ==================== Camera Initialization ====================

    private val frontSurfaceView get() = binding.frontSurfaceView
    private val rearSurfaceView get() = binding.rearSurfaceView
    private val fullscreenFrontSurfaceView get() = binding.fullscreenFrontSurface
    private val fullscreenRearSurfaceView get() = binding.fullscreenRearSurface

    private fun initializeCameraSystems() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        dualCameraRecorder.initialize(cm)
        populateCameraSpinners()
    }

    private var hasPopulatedCameraList = false

    private fun populateCameraSpinners() {
        if (hasPopulatedCameraList) return
        hasPopulatedCameraList = true

        frontCameraOptions.clear()
        rearCameraOptions.clear()
        frontCameraOptions.addAll(cameraInventory.getFrontCameras())
        rearCameraOptions.addAll(cameraInventory.getBackCameras())

        if (frontCameraOptions.isEmpty() && rearCameraOptions.isEmpty()) {
            Toast.makeText(this, R.string.camera_none_available, Toast.LENGTH_LONG).show()
            return
        }

        val defaultFront = frontCameraOptions.firstOrNull()?.cameraId ?: ""
        val defaultRear = rearCameraOptions.firstOrNull()?.cameraId ?: ""
        if (defaultFront.isNotEmpty()) settingsStore.updateFrontCameraId(defaultFront)
        if (defaultRear.isNotEmpty()) settingsStore.updateRearCameraId(defaultRear)

        // Restore the user's previous selection after a theme / language recreate
        // (NOT persisted — see [DualCameraRecorderApp.transientFrontCameraId]).
        // If the user closed and reopened the app the transient fields are null
        // and we keep the defaults set above.
        val savedFront = app.transientFrontCameraId
        val savedRear = app.transientRearCameraId
        if (!savedFront.isNullOrEmpty() &&
            frontCameraOptions.any { it.cameraId == savedFront }
        ) {
            settingsStore.updateFrontCameraId(savedFront)
        }
        if (!savedRear.isNullOrEmpty() &&
            rearCameraOptions.any { it.cameraId == savedRear }
        ) {
            settingsStore.updateRearCameraId(savedRear)
        }
        // Consume the transient values so the next app start does not re-use
        // them by accident. The user explicitly wants the defaults to come
        // back when the app is killed and reopened.
        app.transientFrontCameraId = null
        app.transientRearCameraId = null

        syncLocalFromStore(settingsStore.config.value)
    }

    private fun checkPermissionsAndInit() {
        val permissionsToRequest = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            binding.root.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        initializeCameraSystems()
                        startCamerasIfReady()
                    }
                }
            )
        }
    }

    /**
     * Return true iff CAMERA + RECORD_AUDIO are both granted. Storage
     * permission is intentionally NOT part of this gate: the live preview
     * and audio meters must keep working even when the user denied storage,
     * since the only thing that fails without storage is the recording
     * itself (saving the MP4). The permission launcher surfaces a Toast in
     * that case so the user knows why recording won't work.
     */
    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamerasIfReady() {
        if (camerasStarting) {
            Log.d(TAG, "startCamerasIfReady skipped: already starting")
            return
        }
        // Hard guard: the SurfaceTexture listener can fire before the user has
        // granted CAMERA (e.g. on a fresh launch where the permission dialog
        // hasn't been answered). Opening a camera without the permission
        // throws SecurityException, so silently skip until permissions are
        // granted — the next permission callback will re-arm the start.
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "startCamerasIfReady skipped: CAMERA permission not yet granted")
            return
        }
        val cfg = settingsStore.config.value
        val frontCamId = cfg.frontCameraId
        val rearCamId = cfg.rearCameraId

        if (frontCamId.isEmpty() || rearCamId.isEmpty()) return

        val frontSt = frontSurfaceView.surfaceTexture
        val rearSt = rearSurfaceView.surfaceTexture
        // We deliberately do NOT require the fullscreen pair's SurfaceTextures
        // here, even though the dual-surface design includes them in the
        // capture session when they're available. In practice the fullscreen
        // pair's TextureViews (which live inside the INVISIBLE fullscreen
        // overlay) do not always have a SurfaceTexture ready at the exact
        // moment the main pair's surface listener fires — the listener for
        // the fullscreen pair either fires slightly later or, on some
        // devices, not at all until the user actually enters fullscreen for
        // the first time. If we gate startup on the fullscreen pair being
        // ready, the first call to startCamerasIfReady() fails the all-4
        // check, schedules a 100 ms retry, and the retry races the re-entrancy
        // guard and the other surface listeners; on a cold start the
        // previews stay black until the user changes the camera (which goes
        // through restartPreview(), which only needs the active pair) or
        // enters fullscreen (which causes the fullscreen listener to fire
        // and triggers startFullscreenCamerasIfReady() once all 4 are
        // available). Matching the camera-spinner path — only require the
        // active pair — gets the previews up at app start. The fullscreen
        // pair is still passed into setupPreview() below when its
        // SurfaceTexture IS available, so a later fullscreen toggle is the
        // pure-visibility-change path on devices where the surfaces were
        // ready in time. On devices where they weren't, the first fullscreen
        // toggle re-runs setupPreview() to add the fullscreen pair to the
        // session (the reconfigure path is what restartPreview() already
        // takes, so behaviour stays consistent with the camera-change path).
        val fullscreenFrontSt = fullscreenFrontSurfaceView.surfaceTexture
        val fullscreenRearSt = fullscreenRearSurfaceView.surfaceTexture
        val fullscreenPairReady = fullscreenFrontSt != null && fullscreenRearSt != null

        if (frontSt != null && rearSt != null) {
            camerasStarting = true
            pendingCameraChange = false
            // Re-entrancy guard — 100 ms is the longest stale "starting" window we
            // tolerate. Anything longer makes a fast main<->fullscreen toggle land on
            // a stale flag and silently skip its restart. The DualCameraRecorder
            // open-camera thread is interrupt-driven, so a new start will preempt the
            // in-flight one even within the 100 ms window.
            binding.root.postDelayed({
                camerasStarting = false
                if (pendingCameraChange) {
                    pendingCameraChange = false
                    Log.d(TAG, "Applying pending camera change after guard released")
                    if (isFullscreen) startFullscreenCamerasIfReady() else startCamerasIfReady()
                }
            }, 100)
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            dualCameraRecorder.initialize(cm)
            // Size the SurfaceTexture buffers for the desired pair as a starting point.
            // The empirical retry loop in DualCameraRecorder may swap to a different
            // (front, rear) and call onBeforeOpenFallback before opening, which resizes
            // the buffers to the picked size for the new cameras.
            // Size the active pair's buffers up front. The fullscreen pair's buffers
            // are only sized here if its SurfaceTexture is already available — on a
            // cold start it often is (the INVISIBLE overlay is laid out), but we
            // don't block startup on it. If the fullscreen pair's SurfaceTexture
            // becomes available later, requestPreviewTransforms() (called below)
            // will size it via setDefaultBufferSize() and the first fullscreen
            // entry will reconfigure the session to include it.
            resizeBufferToPickedSizeForPair(frontCamId, isFront = true, isFullscreen = false, surface = frontSt)
            resizeBufferToPickedSizeForPair(rearCamId, isFront = false, isFullscreen = false, surface = rearSt)
            if (fullscreenPairReady) {
                resizeBufferToPickedSizeForPair(frontCamId, isFront = true, isFullscreen = true, surface = fullscreenFrontSt!!)
                resizeBufferToPickedSizeForPair(rearCamId, isFront = false, isFullscreen = true, surface = fullscreenRearSt!!)
            }
            // Stop the current preview AND yield briefly so the camera HAL has a moment
            // to start releasing the old devices before we ask it to open a new one.
            // closePreview() interrupts the previous preview thread and joins for 50 ms,
            // but CameraManager.openCamera() is a blocking call that does not always
            // honour the interrupt, so the join can time out while the HAL still has
            // the previous camera "in use". A short 10 ms yield before setupPreview()
            // is enough to let the HAL release the previous devices; without it the
            // new openCamera() on a consecutive camera switch returns
            // ERROR_CAMERA_IN_USE and the previews freeze black until the next
            // surface-availability event (e.g. entering fullscreen, which itself
            // goes through stopCurrentPreviewAndWait). Mirrors the pattern in
            // enterFullscreen / exitFullscreen.
            stopCurrentPreviewAndWait()
            dualCameraRecorder.setupPreview(
                frontCameraId = frontCamId,
                rearCameraId = rearCamId,
                frontPreviewSurface = Surface(frontSt),
                rearPreviewSurface = Surface(rearSt),
                frontSurfaceTexture = frontSt,
                rearSurfaceTexture = rearSt,
                // Include the fullscreen preview surfaces in the session if they're
                // already available, so a later fullscreen toggle on those devices
                // is a pure view-level change. On devices where they're not ready
                // at startup, we pass null and the first fullscreen entry will
                // reconfigure the session to add them.
                frontPreviewSurfaceSecondary = if (fullscreenPairReady) Surface(fullscreenFrontSt!!) else null,
                rearPreviewSurfaceSecondary = if (fullscreenPairReady) Surface(fullscreenRearSt!!) else null
            )
            // Apply the activity's current orientation to the cameras so the HAL
            // rotates the preview frames correctly from the very first frame.
            // Without this, the cameras default to rotation=0 (portrait) and the
            // landscape previews would be rotated wrong until the user toggles the
            // landscape checkbox.
            dualCameraRecorder.setTargetRotation(
                if (isLandscapeMode) Surface.ROTATION_90 else Surface.ROTATION_0
            )
            dualCameraRecorder.startPreview(
                desiredFront = frontCamId,
                desiredRear = rearCamId,
                alternateFronts = frontCameraOptions.map { it.cameraId },
                alternateRears = rearCameraOptions.map { it.cameraId }
            )
            // Re-enable capture now that the new pair is set up.
            dualCameraRecorder.setCaptureBlocked(false)
            // Re-apply the saved flash state. The CameraController is created fresh on
            // every camera switch, so its internal torchOn is reset to false. Without
            // this re-apply, the user-visible flash state is "on" (settings + icon) but
            // the rear camera is not actually firing the torch.
            applyFlashStateAfterCameraStart()
            // Re-apply the user's zoom level on the new controllers — every preview
            // restart creates a fresh CameraController whose internal linearZoom is
            // 0, so without this the user would see the preview snap to 1.0x after
            // every camera switch / fullscreen toggle.
            dualCameraRecorder.reapplyLinearZoom(frontLinearZoom, rearLinearZoom)
            requestPreviewTransforms()
            Log.d(TAG, "Preview started: front=$frontCamId, rear=$rearCamId")
        } else {
            // SurfaceTexture not ready — back off briefly and retry. Was 300 ms;
            // 100 ms is enough on every device and keeps the first-frame latency low.
            frontSurfaceView.postDelayed({ startCamerasIfReady() }, 100)
        }
    }

    private fun startFullscreenCamerasIfReady() {
        if (camerasStarting) {
            Log.d(TAG, "startFullscreenCamerasIfReady skipped: already starting")
            return
        }
        // Same permission guard as [startCamerasIfReady] — see comment there.
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "startFullscreenCamerasIfReady skipped: CAMERA permission not yet granted")
            return
        }
        val cfg = settingsStore.config.value
        val frontCamId = cfg.frontCameraId
        val rearCamId = cfg.rearCameraId
        if (frontCamId.isEmpty() || rearCamId.isEmpty()) return

        val frontSt = fullscreenFrontSurfaceView.surfaceTexture
        val rearSt = fullscreenRearSurfaceView.surfaceTexture
        // Symmetric to startCamerasIfReady(): only require the ACTIVE pair (the
        // fullscreen pair here), so a fullscreen entry recovers the previews even
        // if the main pair's SurfaceTextures haven't been created yet. The main
        // pair is still passed into setupPreview() below when its SurfaceTexture
        // IS available, so a later exitFullscreen can be a pure view-level change
        // on devices where the main surfaces were ready in time.
        val mainFrontSt = frontSurfaceView.surfaceTexture
        val mainRearSt = rearSurfaceView.surfaceTexture
        val mainPairReady = mainFrontSt != null && mainRearSt != null

        if (frontSt != null && rearSt != null) {
            camerasStarting = true
            pendingCameraChange = false
            // Re-entrancy guard — 100 ms max (see comment in startCamerasIfReady).
            binding.root.postDelayed({
                camerasStarting = false
                if (pendingCameraChange) {
                    pendingCameraChange = false
                    Log.d(TAG, "Applying pending camera change after guard released")
                    if (isFullscreen) startFullscreenCamerasIfReady() else startCamerasIfReady()
                }
            }, 100)
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            dualCameraRecorder.initialize(cm)
            // Resize the fullscreen SurfaceTexture buffers to the picked size for
            // each camera. The fullscreen SurfaceTextures are part of the layout
            // and their buffers were sized once when onSurfaceTextureAvailable
            // fired (potentially for a different orientation or camera). Resizing
            // here guarantees the buffer dimensions match the camera's negotiated
            // preview size — without this, a fullscreen view whose buffer was set
            // for the wrong camera/orientation shows a black square.
            resizeBufferToPickedSizeForPair(frontCamId, isFront = true, isFullscreen = true, surface = frontSt)
            resizeBufferToPickedSizeForPair(rearCamId, isFront = false, isFullscreen = true, surface = rearSt)
            // Also pre-size the main pair's buffers if they're already available.
            // The main pair is part of the capture session too (the session
            // always holds both pairs) and the first exitFullscreen would
            // otherwise need to re-allocate the main pair's SurfaceTexture
            // buffers to match the camera's picked size, causing a brief preview
            // freeze on the first exit. Symmetric to the startCamerasIfReady
            // path. We don't block the fullscreen start on the main pair's
            // readiness — if it isn't ready, we pass null for the secondary
            // surfaces and the first exitFullscreen will reconfigure the session.
            if (mainPairReady) {
                resizeBufferToPickedSizeForPair(frontCamId, isFront = true, isFullscreen = false, surface = mainFrontSt!!)
                resizeBufferToPickedSizeForPair(rearCamId, isFront = false, isFullscreen = false, surface = mainRearSt!!)
            }
            // Yield briefly so the camera HAL has a moment to start releasing the
            // old devices before we ask it to open a new one. Mirrors the
            // startCamerasIfReady / enterFullscreen / exitFullscreen pattern.
            stopCurrentPreviewAndWait()
            dualCameraRecorder.setupPreview(
                frontCameraId = frontCamId,
                rearCameraId = rearCamId,
                frontPreviewSurface = Surface(frontSt),
                rearPreviewSurface = Surface(rearSt),
                frontSurfaceTexture = frontSt,
                rearSurfaceTexture = rearSt,
                // Include the main preview surfaces in the session if they're
                // already available, so a later exitFullscreen on those devices
                // is a pure view-level change. On devices where they aren't
                // ready, we pass null and the first exitFullscreen will
                // reconfigure the session to add them.
                frontPreviewSurfaceSecondary = if (mainPairReady) Surface(mainFrontSt!!) else null,
                rearPreviewSurfaceSecondary = if (mainPairReady) Surface(mainRearSt!!) else null
            )
            // Apply the activity's current orientation so the HAL rotates the
            // preview frames correctly for the fullscreen view as well.
            dualCameraRecorder.setTargetRotation(
                if (isLandscapeMode) Surface.ROTATION_90 else Surface.ROTATION_0
            )
            dualCameraRecorder.startPreview(
                desiredFront = frontCamId,
                desiredRear = rearCamId,
                alternateFronts = frontCameraOptions.map { it.cameraId },
                alternateRears = rearCameraOptions.map { it.cameraId }
            )
            // Re-enable capture now that the new pair is set up.
            dualCameraRecorder.setCaptureBlocked(false)
            // Re-apply the saved flash state (see comment in startCamerasIfReady).
            applyFlashStateAfterCameraStart()
            // Re-apply the user's zoom level on the new controllers (see comment
            // in startCamerasIfReady).
            dualCameraRecorder.reapplyLinearZoom(frontLinearZoom, rearLinearZoom)
            requestPreviewTransforms()
        } else {
            // SurfaceTexture not ready — back off briefly and retry. Was 300 ms;
            // 100 ms is enough on every device and keeps the first-frame latency low.
            fullscreenFrontSurfaceView.postDelayed({ startFullscreenCamerasIfReady() }, 100)
        }
    }

    /**
     * Pick the preview size the camera will use (without opening it) and set the
     * SurfaceTexture buffer to that size. This is needed because [prepareSurfaceTexture]
     * runs in onSurfaceTextureAvailable BEFORE we know which camera will be used; the
     * actual camera may pick a different size than the stream config resolution, and
     * the texture view will be mis-scaled if the buffer is left at the wrong size.
     */
    private fun resizeBufferToPickedSize(surface: SurfaceTexture, isFront: Boolean, fullscreen: Boolean = false) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cfg = settingsStore.config.value
        val streamConfig = if (isFront) cfg.frontConfig else cfg.rearConfig
        val orientation = currentStreamOrientation(forFullscreen = fullscreen)
        val target = Size(
            streamConfig.resolution.widthFor(orientation),
            streamConfig.resolution.heightFor(orientation)
        )
        val camId = if (isFront) cfg.frontCameraId else cfg.rearCameraId
        if (camId.isEmpty()) return
        val sizes = try {
            val chars = cm.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val st = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            if (!st.isNullOrEmpty()) st else map?.getOutputSizes(Surface::class.java)
        } catch (e: Exception) { null }
        if (sizes == null || sizes.isEmpty()) return
        val exact = sizes.firstOrNull { it.width == target.width && it.height == target.height }
        val picked = exact ?: sizes.minByOrNull {
            val r = it.width.toDouble() / it.height.toDouble()
            val tR = target.width.toDouble() / target.height.toDouble()
            kotlin.math.abs(r - tR) * 1000 - it.width
        } ?: return
        Log.d(TAG, "Buffer for ${if (isFront) "front" else "rear"}${if (fullscreen) "(fs)" else ""} -> $picked")
        surface.setDefaultBufferSize(picked.width, picked.height)
    }

    private fun restartPreview() {
        if (camerasStarting) {
            // The retry loop is in flight; remember that a fresh restart was requested
            // and re-trigger once the guard releases.
            pendingCameraChange = true
            Log.d(TAG, "restartPreview queued: camerasStarting is true")
            return
        }
        // Same permission guard as [startCamerasIfReady] — see comment there.
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "restartPreview skipped: CAMERA permission not yet granted")
            return
        }
        val cfg = settingsStore.config.value
        val frontSt = if (isFullscreen) fullscreenFrontSurfaceView.surfaceTexture else frontSurfaceView.surfaceTexture
        val rearSt = if (isFullscreen) fullscreenRearSurfaceView.surfaceTexture else rearSurfaceView.surfaceTexture

        if (frontSt == null || rearSt == null) {
            // SurfaceTexture not ready — back off briefly and retry. Was 300 ms;
            // 100 ms keeps the first-frame latency low on the fullscreen toggle path.
            frontSurfaceView.postDelayed({ restartPreview() }, 100)
            return
        }
        camerasStarting = true
        pendingCameraChange = false
        // Re-entrancy guard — 100 ms max (see comment in startCamerasIfReady).
        binding.root.postDelayed({
            camerasStarting = false
            if (pendingCameraChange) {
                pendingCameraChange = false
                Log.d(TAG, "Applying pending camera change after guard released")
                if (isFullscreen) startFullscreenCamerasIfReady() else startCamerasIfReady()
            }
        }, 100)

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        dualCameraRecorder.initialize(cm)
        // Size buffers for the desired pair; the empirical retry will resize them
        // for the actual pair via onBeforeOpenFallback.
        resizeBufferToPickedSizeForPair(cfg.frontCameraId, isFront = true, isFullscreen = isFullscreen, surface = frontSt)
        resizeBufferToPickedSizeForPair(cfg.rearCameraId, isFront = false, isFullscreen = isFullscreen, surface = rearSt)
        // Close the existing preview AND yield briefly so the camera HAL has a moment
        // to start releasing the old devices before we ask it to open a new one.
        // Without the yield, a rapid second camera change (e.g. switching rear from
        // Y to Z then back to Y) races the HAL: closePreview()'s 50 ms join on the
        // previous preview thread can time out while CameraManager.openCamera() is
        // still pending, and the new openCamera() then returns ERROR_CAMERA_IN_USE
        // — both previews stay black until the next surface-availability event
        // (e.g. entering fullscreen, which itself goes through
        // stopCurrentPreviewAndWait). Mirrors the pattern used by
        // enterFullscreen / exitFullscreen.
        stopCurrentPreviewAndWait()
        dualCameraRecorder.setupPreview(
            frontCameraId = cfg.frontCameraId,
            rearCameraId = cfg.rearCameraId,
            frontPreviewSurface = Surface(frontSt),
            rearPreviewSurface = Surface(rearSt),
            frontSurfaceTexture = frontSt,
            rearSurfaceTexture = rearSt,
            // Always include the fullscreen preview surfaces in the session so
            // a future fullscreen toggle is a pure view-level change.
            frontPreviewSurfaceSecondary = fullscreenFrontSurfaceView.surfaceTexture?.let { Surface(it) },
            rearPreviewSurfaceSecondary = fullscreenRearSurfaceView.surfaceTexture?.let { Surface(it) }
        )
        // Apply the activity's current orientation so the HAL rotates the preview
        // frames correctly after the restart.
        dualCameraRecorder.setTargetRotation(
            if (isLandscapeMode) Surface.ROTATION_90 else Surface.ROTATION_0
        )
        dualCameraRecorder.startPreview(
            desiredFront = cfg.frontCameraId,
            desiredRear = cfg.rearCameraId,
            alternateFronts = frontCameraOptions.map { it.cameraId },
            alternateRears = rearCameraOptions.map { it.cameraId }
        )
        // Re-enable capture now that the new pair is set up.
        dualCameraRecorder.setCaptureBlocked(false)
        // Re-apply the saved flash state (see comment in startCamerasIfReady).
        applyFlashStateAfterCameraStart()
        // Re-apply the user's zoom level on the new controllers (see comment
        // in startCamerasIfReady).
        dualCameraRecorder.reapplyLinearZoom(frontLinearZoom, rearLinearZoom)
        requestPreviewTransforms()
    }

    // ==================== Preview Transform (FIT_CENTER letterbox, no deformation) ====================

    /**
     * Poap jealousy the FIT_CENTER transform for the given TextureView. The camera buffer is
     * always width>height (sensor landscape). In portrait mode we rotate it 90° so the
     * 4:3 buffer displays as 3:4; in landscape mode it stays unrotated. The buffer is
     * scaled to fit fully inside the view with letterbox bars (no cropping, no stretching).
     *
     * The actual preview size is read from the recorder once the session is configured; until
     * then we retry a few times.
     */
    private fun requestPreviewTransforms() {
        // Always apply the transform to BOTH the main and fullscreen pair, regardless
        // of the current isFullscreen state. The capture session holds both pairs from
        // camera-open onwards, so both SurfaceTextures are live and need the correct
        // FIT_CENTER transform and buffer size from the start. Skipping the fullscreen
        // pair here (the previous behaviour) meant the first call to
        // requestPreviewTransforms() after the user entered fullscreen had to
        // setDefaultBufferSize() on the fullscreen pair's SurfaceTexture for the
        // first time, which re-allocates the texture's buffers and produces a brief
        // preview freeze the user reported. The fullscreen TextureViews live inside
        // the INVISIBLE overlay (not GONE), so they are laid out from activity
        // start — the transform can be computed against real dimensions even when
        // the overlay is not currently visible.
        requestPreviewTransform(isFront = true, isFullscreen = false)
        requestPreviewTransform(isFront = false, isFullscreen = false)
        requestPreviewTransform(isFront = true, isFullscreen = true)
        requestPreviewTransform(isFront = false, isFullscreen = true)
    }

    private fun requestPreviewTransform(isFront: Boolean, isFullscreen: Boolean) {
        val view = if (isFullscreen) {
            if (isFront) fullscreenFrontSurfaceView else fullscreenRearSurfaceView
        } else {
            if (isFront) frontSurfaceView else rearSurfaceView
        }
        // The "portrait" flag drives the rotation inside applyPreviewTransform. The fullscreen
        // follows the same activity-level landscape mode (no separate fullscreen toggle).
        val portrait = !isLandscapeMode
        val size = if (isFront) dualCameraRecorder.frontPreviewSize() else dualCameraRecorder.rearPreviewSize()
        val cfg = settingsStore.config.value
        val streamConfig = if (isFront) cfg.frontConfig else cfg.rearConfig
        val fallback = Size(
            streamConfig.resolution.widthFor(currentStreamOrientation(isFullscreen)),
            streamConfig.resolution.heightFor(currentStreamOrientation(isFullscreen))
        )
        val resolved = size ?: fallback

        // If the session hasn't selected a preview size yet, retry shortly. Was 200 ms;
        // 50 ms keeps the preview transform in sync with the camera session without
        // stalling the fullscreen transition on slow camera handshakes.
        if (size == null) {
            view.postDelayed({ requestPreviewTransform(isFront, isFullscreen) }, 50)
            // Still apply a transform with the fallback so the buffer (set to fallback) is
            // rotated correctly while we wait.
            applyPreviewTransform(view, fallback, portrait)
            return
        }

        // Resize the SurfaceTexture buffer to match the actual preview size the camera
        // negotiated. Without this the camera HAL may write at a different size than the
        // buffer claims, leaving the image tiny or off-center inside the card. SurfaceTexture
        // does not expose the current buffer size via a getter, so we always set it; this is
        // a no-op when the value is already correct.
        view.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        applyPreviewTransform(view, resolved, portrait)
    }

    private fun applyPreviewTransform(view: TextureView, size: Size, portrait: Boolean) {
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || size.width <= 0 || size.height <= 0) return
        val isFront = (view === frontSurfaceView || view === fullscreenFrontSurfaceView)
        val facing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK

        val srcW = size.width.toFloat()
        val srcH = size.height.toFloat()

        // The camera buffer is always width>height (sensor landscape). The HAL
        // rotates the buffer CONTENT for us based on the target rotation, but
        // the buffer DIMENSIONS stay landscape. We need the post-rotation result
        // to keep the requested aspect ratio:
        //   - Portrait (3:4 vertical): the user wants the previews in 3:4 (taller
        //     than wide). Pre-rotation is 4:3 (the buffer). We must swap W/H in
        //     FIT_CENTER (use 90° mapping) so the buffer's "effective" dimensions
        //     become 3:4. The post-rotation (no extra rotation in portrait) is 3:4.
        //   - Landscape (4:3 horizontal): the user wants the previews rotated 90°
        //     to the LEFT and the result to look 4:3 (wider than tall). Pre-rotation
        //     is 4:3 (the buffer). We must swap W/H in FIT_CENTER (use 90° mapping)
        //     so the pre-rotation image is 3:4 (taller than wide). After the 90°
        //     left rotation, the visible result is 4:3 (wider than tall). This
        //     matches the user's request: the angle is rotated, but the visible
        //     aspect ratio of the preview area is 4:3.
        //
        // The `rot` here drives:
        //   1) the W/H swap for the FIT_CENTER rectangle (90 means swap, 0 means no swap), and
        //   2) the actual image content rotation (only in landscape — 90° counter-clockwise).
        val rot = 90f
        val contentRotationDeg = if (portrait) 0f else -90f  // landscape: rotate 90° left

        // FIT_CENTER math on the buffer. We always swap W/H so the pre-rotation
        // image is taller than wide; in portrait this is the final image (3:4), in
        // landscape it becomes 4:3 after the additional 90° left rotation.
        val effW = srcH
        val effH = srcW
        val scale = minOf(viewW / effW, viewH / effH)
        val scaledW = effW * scale
        val scaledH = effH * scale
        val cx = viewW / 2f
        val cy = viewH / 2f
        val dx = cx - scaledW / 2f
        val dy = cy - scaledH / 2f

        // Build the FIT_CENTER mapping first: scale + translate to letterbox the
        // (swapped) buffer into the view rectangle.
        val matrix = Matrix()
        matrix.setScale(scaledW / viewW, scaledH / viewH)
        matrix.postTranslate(dx, dy)

        // In landscape (4:3), rotate the actual image 90° to the LEFT on top of the
        // FIT_CENTER transform. The rotation pivot is the view centre so the image
        // stays centred after rotation.
        if (contentRotationDeg != 0f) {
            matrix.postRotate(contentRotationDeg, viewW / 2f, viewH / 2f)
        }

        val bmp = try { view.getBitmap() } catch (e: Exception) { null }
        val bmpW = bmp?.width ?: -1
        val bmpH = bmp?.height ?: -1
        Log.d(TAG, "applyPreviewTransform view=${viewW}x${viewH} src=${srcW}x${srcH} " +
            "rot=$rot contentRot=$contentRotationDeg effW=$effW effH=$effH " +
            "scale=$scale dx=$dx dy=$dy portrait=$portrait facing=$facing " +
            "bmp=${bmpW}x${bmpH} scaledW=$scaledW scaledH=$scaledH " +
            "vals=${matrixValues(matrix)}")
        view.setTransform(matrix)
    }

    private fun matrixValues(m: Matrix): String {
        val v = FloatArray(9)
        m.getValues(v)
        return "[${v[0].format(3)},${v[1].format(3)},${v[2].format(3)}; ${v[3].format(3)},${v[4].format(3)},${v[5].format(3)}]"
    }

    private fun Float.format(d: Int): String = "%.${d}f".format(this)

    /** Display rotation, preferring the modern `Display` property on API 30+ and falling back
     *  to the deprecated `windowManager.defaultDisplay` on older API levels. */
    private fun currentDisplayRotation(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    // ==================== Recording ====================

    private fun startRecording() {
        if (isRecording) return

        // Wire up audio capture callbacks so the MicStateStore tracks recording
        // state in sync with the DualCameraRecorder.
        dualCameraRecorder.onAudioCaptureStarted = {
            app.micStateStore.setRecording(true)
        }
        dualCameraRecorder.onAudioCaptureStopped = {
            app.micStateStore.setRecording(false)
        }

        val cfg = settingsStore.config.value
        // The fullscreen follows the activity-level landscape checkbox, so a single
        // currentStreamOrientation() call covers both the main and fullscreen recording.
        val screenOrientation = currentStreamOrientation()
        // Update the config with current orientation so MediaRecorder uses correct dimensions
        settingsStore.updateFrontConfig(cfg.frontConfig.copy(streamOrientation = screenOrientation))
        settingsStore.updateRearConfig(cfg.rearConfig.copy(streamOrientation = screenOrientation))
        val files = dualCameraRecorder.startRecording(
            frontConfig = cfg.frontConfig.copy(streamOrientation = screenOrientation),
            rearConfig = cfg.rearConfig.copy(streamOrientation = screenOrientation),
            screenOrientation = screenOrientation,
            audioBitrateKbps = cfg.audioBitrateKbps,
            audioSampleRateHz = cfg.audioSampleRateHz
        )

        if (files != null) {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            startRecordingTimer()
            lockOrientationControls(true)

            // Swap both Record buttons to the green-square "stop" variant.
            applyMainRecordButtonState()
            applyFullscreenRecordButtonState()

            binding.statusTitle.setText(R.string.recording_in_progress)
            binding.statusDetail.setText(R.string.status_recording_detail)
            binding.elapsedText.visibility = View.VISIBLE

            // Re-apply the user's zoom level on the freshly-created recording
            // controllers. startRecording() builds new CameraController instances
            // whose internal linearZoom is 0, so without this the recorded video
            // would lose the zoom the user set during preview.
            dualCameraRecorder.reapplyLinearZoom(frontLinearZoom, rearLinearZoom)

            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        } else {
            isRecording = false
            // Live mic capture remains running — no restart needed
            Toast.makeText(this, R.string.recording_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        dualCameraRecorder.stopRecording()
        stopRecordingTimer()

        isRecording = false
        lockOrientationControls(false)

        // Restore both Record buttons to the red-circle "start" variant.
        applyMainRecordButtonState()
        applyFullscreenRecordButtonState()

        binding.statusTitle.setText(R.string.service_stopped)
        binding.statusDetail.setText(R.string.status_idle_detail)
        binding.elapsedText.visibility = View.GONE

        // Restart the preview cameras after stopping the recording. DualCameraRecorder.stopRecording()
        // closes the camera devices but leaves the frontController/rearController references alive
        // pointing at the now-closed cameras, so the TextureViews keep their last frame and look
        // frozen until the user toggles fullscreen (which closes the preview and reopens it on the
        // other surface set). Reuse the same flow as enter/exitFullscreen — fully close the
        // preview, wait for the cameras to release, then re-arm on the active surface set.
        stopCurrentPreviewAndWait()
        // Release the re-entrancy guard in case any prior start path left it set; otherwise
        // startCamerasIfReady / startFullscreenCamerasIfReady would return immediately and the
        // previews would stay frozen.
        camerasStarting = false
        if (isFullscreen) startFullscreenCamerasIfReady() else startCamerasIfReady()

        // Live mic capture was never stopped, so meters resume automatically.
        restartMicObservation()

        Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun lockOrientationControls(locked: Boolean) {
        binding.landscapeCheckbox.isEnabled = !locked
        // The front/rear camera selection is also locked while a recording is in
        // progress. Switching the camera mid-recording would tear down the current
        // capture session and re-open the cameras with a new id, which is exactly
        // the same kind of gap-producing close+reopen cycle that the fullscreen
        // swap path works around — the recorded video would freeze at the switch
        // point. The MediaRecorder was also prepared against the current camera
        // ids, so silently swapping cameras would also be confusing. Disable the
        // spinners instead so the user sees the cameras that are actually in use.
        binding.frontCameraSpinner.isEnabled = !locked
        binding.rearCameraSpinner.isEnabled = !locked
        // The "Restore Cameras" button stops and re-opens both previews, which
        // would tear down the capture session the MediaRecorder is currently
        // writing into. Block it while a recording is in progress for the same
        // reason the camera-selection spinners are blocked: keep the recorded
        // video gap-free and avoid confusing state where the running cameras
        // don't match what the user selected.
        binding.btnRestoreCameras.isEnabled = !locked
        // Per-camera video settings (resolution, FPS, bitrate) cannot be
        // changed while a recording is in progress — touching the
        // StreamConfig mid-recording would have no effect on the running
        // MediaRecorders, and silently dropping the change would be
        // confusing. Disable the spinners instead so the user can see
        // the values that are in effect for the current recording.
        binding.frontResolutionSpinner.isEnabled = !locked
        binding.rearResolutionSpinner.isEnabled = !locked
        binding.frontFpsSpinner.isEnabled = !locked
        binding.rearFpsSpinner.isEnabled = !locked
        binding.frontBitrateSpinner.isEnabled = !locked
        binding.rearBitrateSpinner.isEnabled = !locked
        // Audio settings are only modifiable when no recording is in progress,
        // matching the MicGainLevelerApp UX. The format spinner is permanently
        // disabled (fixed to AAC) so it stays as-is during recording too.
        binding.audioBitrateSpinner.isEnabled = !locked
        binding.audioSampleRateSpinner.isEnabled = !locked
    }

    /**
     * Show the device-compatibility alert the first time the user opens
     * the app, and on every subsequent launch until they tick the
     * "Don't show again" box. The state of the checkbox is persisted
     * via [PreferencesRepository.showDeviceCompatibilityAlert]: when it
     * is true (default) the alert is shown; when the user dismisses the
     * dialog with the checkbox ticked, we flip it to false and the
     * alert is suppressed for every future launch.
     */
    private fun showDeviceCompatibilityAlertIfNeeded() {
        if (!preferences.showDeviceCompatibilityAlert) return
        AlertDialogHelper.showWithCheckbox(
            context = this,
            title = getString(R.string.compat_alert_title),
            message = getString(R.string.compat_alert_message),
            checkboxLabel = getString(R.string.compat_alert_dont_show_again),
            okLabel = getString(R.string.compat_alert_ok),
            initiallyChecked = false
        ) { checked ->
            if (checked) {
                // Persist the "don't show again" choice. The next time the
                // activity is created the early-return at the top of this
                // method will skip the alert entirely.
                preferences.showDeviceCompatibilityAlert = false
            }
        }
    }

    private fun restartMicObservation() {
        // Re-start live mic capture and re-observe the state store
        app.ensureMicCaptureStarted()
        if (micUnsubscribe == null) {
            micUnsubscribe = app.micStateStore.observe(object : MicObserver {
                override fun onStateChanged(state: MicState) {
                    val frontLevel = state.leftLevel.toFloat().coerceIn(-60f, 0f)
                    val rearLevel = state.rightLevel.toFloat().coerceIn(-60f, 0f)
                    updateMeters(frontLevel, rearLevel)
                }
            })
        }
    }

    private fun startRecordingTimer() {
        recordingElapsedJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val mins = elapsed / 60000
                val secs = (elapsed % 60000) / 1000
                binding.elapsedText.text = String.format("%02d:%02d", mins, secs)
                // 100 ms tick — keeps the elapsed time smooth without burning CPU on
                // a coroutine that runs for the whole recording.
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingElapsedJob?.cancel()
        recordingElapsedJob = null
    }

    // ==================== Flash (always controls the REAR camera) ====================

    /**
     * Toggles the rear-camera torch. The button is ALWAYS enabled and ALWAYS acts on
     * the rear camera (the front camera has no flash). When the selected rear camera
     * is a logical multi-camera whose physical sub-camera holds the flash unit, we
     * route the torch to that physical sub-camera so the LED actually turns on —
     * otherwise the button would be a no-op on devices that expose the rear camera
     * as a logical id (most modern phones).
     *
     * Flash is INDEPENDENT of the manual control mode (mirrors AndroidCamera's
     * `StreamConfig.flashTorchEnabled`): the previous implementation flipped the
     * rear config's `controlMode` to MANUAL whenever the flash was on, which then
     * forced AE off in the controller and made the preview go near-black. The
     * torch is driven entirely by `FLASH_MODE_TORCH` on the capture request and
     * the dedicated `CameraManager.setTorchMode()` API on the OS side, neither of
     * which requires AE off.
     */
    private fun toggleFlash() {
        flashOn = !flashOn
        val cfg = settingsStore.config.value
        val rearCamId = cfg.rearCameraId
        val updatedConfig = cfg.rearConfig.copy(flashEnabled = flashOn)
        settingsStore.updateRearConfig(updatedConfig)

        // Dedicated CameraManager.setTorchMode() — picks a physical sub-camera with
        // a flash if needed, and works even before a capture session is configured.
        val ok = dualCameraRecorder.setRearCameraTorch(rearCamId, flashOn)
        if (!ok) {
            Log.w(TAG, "toggleFlash: no camera with flash found for $rearCamId")
        }

        // Icon-only button — only the lightning-bolt icon swaps between on/off.
        val flashIcon = if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        binding.btnFlash.setIconResource(flashIcon)
        binding.btnFlashFullscreen.setIconResource(flashIcon)
        // Mutual exclusion: when the flash is enabled, the "Autofocus on tap"
        // checkbox is disabled (the two modes cannot coexist).
        val autofocusEnabled = !flashOn
        binding.autofocusOnTapCheckbox.isEnabled = autofocusEnabled
        binding.autofocusOnTapCheckboxFullscreen.isEnabled = autofocusEnabled

        Log.d(TAG, "Rear flash: ${if (flashOn) "ON" else "OFF"} (ok=$ok)")
    }

    /**
     * Refreshes the flash button icon to reflect the current torch state. The button is
     * disabled when "Autofocus on tap" is checked (the two modes are mutually exclusive —
     * the user explicitly opts into one or the other via the checkbox). If the current
     * rear camera has no flash, the icon is just the "off" icon and a hint Toast is shown
     * on first press.
     *
     * The reverse direction is also enforced: when the flash is on, the
     * "Autofocus on tap" checkboxes (main + fullscreen) are disabled, so the
     * user cannot tap into the other mode while the torch is firing.
     */
    private fun updateFlashButton(hasFlash: Boolean) {
        val autofocusOnTap = settingsStore.config.value.autofocusOnTap
        // When autofocus-on-tap is enabled, the flash button is disabled because
        // the user has chosen to use taps to focus instead of the physical flash
        // button. The two modes are mutually exclusive.
        binding.btnFlash.isEnabled = !autofocusOnTap
        binding.btnFlashFullscreen.isEnabled = !autofocusOnTap
        // Icon-only button — only the lightning-bolt icon swaps between on/off.
        val flashIcon = if (flashOn && hasFlash) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        binding.btnFlash.setIconResource(flashIcon)
        binding.btnFlashFullscreen.setIconResource(flashIcon)
        // Mutual exclusion (reverse direction): when the flash is on, the
        // "Autofocus on tap" checkboxes are disabled.
        val autofocusEnabled = !flashOn
        binding.autofocusOnTapCheckbox.isEnabled = autofocusEnabled
        binding.autofocusOnTapCheckboxFullscreen.isEnabled = autofocusEnabled
    }

    /**
     * Re-apply the current flash state to the freshly-opened camera. Called after
     * every startCamerasIfReady / startFullscreenCamerasIfReady / restartPreview
     * because each of those creates a new CameraController whose internal torchOn
     * flag is reset to false (the flag is per-controller, not persisted in the
     * settings store). Without this re-apply, the user-visible flash state and the
     * actual torch output drift apart after every camera switch — the icon says
     * "on" but the rear camera is not firing the torch (or vice versa).
     */
    private fun applyFlashStateAfterCameraStart() {
        // Always send the requested state to the rear camera. setRearCameraTorch
        // routes to a physical sub-camera with flash if needed, and is a no-op
        // when the current rear camera has no flash at all.
        dualCameraRecorder.setRearCameraTorch(settingsStore.config.value.rearCameraId, flashOn)
    }

    // ==================== Manual Controls (per camera) ====================

    // Front camera manual focus
    private fun toggleManualFocusFront(enabled: Boolean) {
        val cfg = settingsStore.config.value
        val distance = if (enabled) frontFocusDistance else null

        val updatedFront = cfg.frontConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.frontConfig.manualControl.copy(
                focusEnabled = enabled,
                focusDistance = distance
            )
        )
        settingsStore.updateFrontConfig(updatedFront)

        dualCameraRecorder.setManualControls(
            isFront = true,
            focusDistance = distance ?: -1f,
            focusEnabled = enabled
        )
    }

    // Rear camera manual focus
    private fun toggleManualFocusRear(enabled: Boolean) {
        val cfg = settingsStore.config.value
        val distance = if (enabled) rearFocusDistance else null

        val updatedRear = cfg.rearConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.rearConfig.manualControl.copy(
                focusEnabled = enabled,
                focusDistance = distance
            )
        )
        settingsStore.updateRearConfig(updatedRear)

        dualCameraRecorder.setManualControls(
            isFront = false,
            focusDistance = distance ?: -1f,
            focusEnabled = enabled
        )
    }

    // Front camera ISO
    private fun applyIsoFront(iso: Int?) {
        frontIsoValue = iso
        val cfg = settingsStore.config.value
        val enabled = isFrontManualAdjustments
        val updatedFront = cfg.frontConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.frontConfig.manualControl.copy(iso = iso)
        )
        settingsStore.updateFrontConfig(updatedFront)
        dualCameraRecorder.setManualControls(
            isFront = true,
            iso = iso ?: -1,
            exposureEnabled = enabled
        )
    }

    // Rear camera ISO
    private fun applyIsoRear(iso: Int?) {
        rearIsoValue = iso
        val cfg = settingsStore.config.value
        val enabled = isRearManualAdjustments
        val updatedRear = cfg.rearConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.rearConfig.manualControl.copy(iso = iso)
        )
        settingsStore.updateRearConfig(updatedRear)
        dualCameraRecorder.setManualControls(
            isFront = false,
            iso = iso ?: -1,
            exposureEnabled = enabled
        )
    }

    // Front camera exposure time
    private fun applyExposureTimeFront(exposureNanos: Long?) {
        frontExposureTimeNanos = exposureNanos
        val cfg = settingsStore.config.value
        val enabled = isFrontManualAdjustments
        val updatedFront = cfg.frontConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.frontConfig.manualControl.copy(exposureTimeNanos = exposureNanos)
        )
        settingsStore.updateFrontConfig(updatedFront)
        dualCameraRecorder.setManualControls(
            isFront = true,
            exposureTimeNanos = exposureNanos ?: -1L,
            exposureEnabled = enabled
        )
    }

    // Rear camera exposure time
    private fun applyExposureTimeRear(exposureNanos: Long?) {
        rearExposureTimeNanos = exposureNanos
        val cfg = settingsStore.config.value
        val enabled = isRearManualAdjustments
        val updatedRear = cfg.rearConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.rearConfig.manualControl.copy(exposureTimeNanos = exposureNanos)
        )
        settingsStore.updateRearConfig(updatedRear)
        dualCameraRecorder.setManualControls(
            isFront = false,
            exposureTimeNanos = exposureNanos ?: -1L,
            exposureEnabled = enabled
        )
    }

    // Disable manual adjustments for front camera
    private fun applyManualAdjustmentsFront(enabled: Boolean) {
        val cfg = settingsStore.config.value
        val updatedFront = cfg.frontConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.frontConfig.manualControl.copy(
                isoEnabled = enabled,
                exposureEnabled = enabled,
                iso = if (enabled) frontIsoValue else null,
                exposureTimeNanos = if (enabled) frontExposureTimeNanos else null
            )
        )
        settingsStore.updateFrontConfig(updatedFront)

        if (enabled) {
            dualCameraRecorder.setManualControls(
                isFront = true,
                iso = frontIsoValue ?: -1,
                exposureTimeNanos = frontExposureTimeNanos ?: -1L,
                exposureEnabled = true
            )
        } else {
            frontIsoValue = null
            frontExposureTimeNanos = null
            dualCameraRecorder.setManualControls(
                isFront = true,
                iso = -1,
                exposureTimeNanos = -1L,
                exposureEnabled = false
            )
            resetSpinnerToAuto(binding.isoSpinnerFront)
            resetSpinnerToAuto(binding.isoSpinnerFullscreenFront)
            resetSpinnerToAuto(binding.exposureSpinnerFront)
            resetSpinnerToAuto(binding.exposureSpinnerFullscreenFront)
        }
    }

    // Disable manual adjustments for rear camera
    private fun applyManualAdjustmentsRear(enabled: Boolean) {
        val cfg = settingsStore.config.value
        val updatedRear = cfg.rearConfig.copy(
            controlMode = if (enabled) ControlMode.MANUAL else ControlMode.AUTO,
            manualControl = cfg.rearConfig.manualControl.copy(
                isoEnabled = enabled,
                exposureEnabled = enabled,
                iso = if (enabled) rearIsoValue else null,
                exposureTimeNanos = if (enabled) rearExposureTimeNanos else null
            )
        )
        settingsStore.updateRearConfig(updatedRear)

        if (enabled) {
            dualCameraRecorder.setManualControls(
                isFront = false,
                iso = rearIsoValue ?: -1,
                exposureTimeNanos = rearExposureTimeNanos ?: -1L,
                exposureEnabled = true
            )
        } else {
            rearIsoValue = null
            rearExposureTimeNanos = null
            dualCameraRecorder.setManualControls(
                isFront = false,
                iso = -1,
                exposureTimeNanos = -1L,
                exposureEnabled = false
            )
            resetSpinnerToAuto(binding.isoSpinnerRear)
            resetSpinnerToAuto(binding.isoSpinnerFullscreenRear)
            resetSpinnerToAuto(binding.exposureSpinnerRear)
            resetSpinnerToAuto(binding.exposureSpinnerFullscreenRear)
        }
    }

    private fun resetSpinnerToAuto(spinner: android.widget.Spinner) {
        bindingInProgress = true
        spinner.setSelection(0, false)
        bindingInProgress = false
    }

    // Apply front camera focus
    private fun applyFocusToFrontCamera() {
        dualCameraRecorder.setManualControls(isFront = true, focusDistance = frontFocusDistance)
    }

    // Apply rear camera focus
    private fun applyFocusToRearCamera() {
        dualCameraRecorder.setManualControls(isFront = false, focusDistance = rearFocusDistance)
    }

    // Change front camera focus
    private fun changeFocusFront(delta: Int) {
        val next = (frontFocusDistance * 1000 + delta).toInt().coerceIn(0, 1000)
        frontFocusDistance = next / 1000f
        binding.focusSeekBarFront.progress = next
        binding.focusSeekBarFullscreenFront.progress = next
        applyFocusToFrontCamera()
    }

    // Change rear camera focus
    private fun changeFocusRear(delta: Int) {
        val next = (rearFocusDistance * 1000 + delta).toInt().coerceIn(0, 1000)
        rearFocusDistance = next / 1000f
        binding.focusSeekBarRear.progress = next
        binding.focusSeekBarFullscreenRear.progress = next
        applyFocusToRearCamera()
    }

    // Apply the current linear zoom to the front camera. No-op when the camera
    // is not yet open — the controller remembers the last value and re-applies
    // it on the next preview start (see [DualCameraRecorder.reapplyLinearZoom]).
    private fun applyLinearZoomToFrontCamera() {
        dualCameraRecorder.setLinearZoom(isFront = true, linearZoom = frontLinearZoom)
    }

    // Apply the current linear zoom to the rear camera. Same semantics as the
    // front version.
    private fun applyLinearZoomToRearCamera() {
        dualCameraRecorder.setLinearZoom(isFront = false, linearZoom = rearLinearZoom)
    }

    // Change front camera zoom by [delta] progress units (5 ≈ 5% of the slider range).
    private fun changeZoomFront(delta: Int) {
        val next = (frontLinearZoom * 100f + delta).toInt().coerceIn(0, 100)
        frontLinearZoom = next / 100f
        binding.zoomSeekBarFront.progress = next
        binding.zoomSeekBarFullscreenFront.progress = next
        applyLinearZoomToFrontCamera()
    }

    // Change rear camera zoom by [delta] progress units.
    private fun changeZoomRear(delta: Int) {
        val next = (rearLinearZoom * 100f + delta).toInt().coerceIn(0, 100)
        rearLinearZoom = next / 100f
        binding.zoomSeekBarRear.progress = next
        binding.zoomSeekBarFullscreenRear.progress = next
        applyLinearZoomToRearCamera()
    }

    // ==================== Fullscreen ====================

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        Log.d(TAG, "enterFullscreen: stopping main preview first")
        isFullscreen = true
        // The fullscreen follows the activity's main landscape mode (no separate toggle).
        applyFullscreenOrientation()
        // The overlay starts with clickable=false / focusable=false in the layout
        // so it can lay out its TextureViews (creating their SurfaceTextures) but
        // doesn't intercept touches over the main view. Make the overlay a real
        // fullscreen takeover now that we're showing it.
        binding.fullscreenPreviewOverlay.isClickable = true
        binding.fullscreenPreviewOverlay.isFocusable = true
        binding.fullscreenPreviewOverlay.visibility = View.VISIBLE
        binding.fullscreenPreviewOverlay.requestFocus()
        // Reset to "controls visible" each time the user enters fullscreen.
        fullscreenControlsVisible = true
        applyFullscreenControlsVisibility()
        // Apply Light-theme overrides for the overlay buttons + the four checkboxes
        // below the audio meters. No-op in the Dark theme where the XML defaults are
        // already correct on top of the dark preview background.
        applyFullscreenOverlayColors()

        // The fullscreen digital meters sit on top of the live video and are
        // physically smaller than the in-card meters, so render them in the
        // brighter (wider segments + glow) variant. The main-activity meters
        // stay on the default rendering.
        binding.digitalMeterFrontFullscreen.setBrighterMode(true)
        binding.digitalMeterRearFullscreen.setBrighterMode(true)

        // In landscape the user asked for the fullscreen previews to keep a 4:3
        // aspect ratio, so we resize the content frame accordingly. In portrait the
        // overlay fills the whole screen as before.
        applyFullscreenContentSizing()

        // The "Front Camera" / "Rear Camera" labels are placed at the top of each
        // preview frame in XML, but in portrait fullscreen each frame is
        // half-screen-wide × screen-height while the camera buffer is 4:3 (displayed
        // 3:4). FIT_CENTER fits the image to the frame width and leaves equal
        // vertical letterbox bars above and below the visible video. The static
        // 12dp top margin would put the labels inside that upper letterbox — far
        // above the video itself. Shift the labels down so they sit just above the
        // top edge of the visible video. The layout settles asynchronously after
        // the orientation switch above, so post() until the frame has its final
        // dimensions.
        binding.root.post { positionFullscreenLabels() }

        if (isRecording) {
            // The capture session was set up with BOTH the main and fullscreen
            // preview surfaces from the start (see startCamerasIfReady), so the
            // camera is already writing to the fullscreen TextureView's
            // SurfaceTexture. Entering fullscreen during recording is therefore a
            // pure view-level change — flip the overlay's visibility and
            // clickable/focusable flags so it covers the screen and intercepts
            // touches. The MediaRecorder keeps receiving frames uninterrupted,
            // so the recorded video has no gap at the toggle.
        } else {
            // Same as the recording case: the capture session was set up with
            // BOTH the main and fullscreen preview surfaces from the start, so
            // the camera is already writing to the fullscreen TextureView's
            // SurfaceTexture. There's no MediaRecorder to worry about, but
            // there are live preview TextureView consumers on both pairs of
            // surfaces, and the previous (close+reopen) implementation
            // produced a visible brief freeze at every fullscreen toggle
            // because the camera HAL had to re-allocate buffers for the
            // "new" preview surface. The dual-surface design eliminates that
            // gap — the fullscreen toggle is a pure TextureView visibility
            // flip here too.
            //
            // We still need to apply the FIT_CENTER preview transforms to the
            // fullscreen TextureViews now that they're being shown: the
            // transforms are computed against the TextureView's actual
            // dimensions and the camera's negotiated preview size, and they
            // were only applied for the main pair when startCamerasIfReady()
            // ran (because isFullscreen was false at that point). The
            // requestPreviewTransform calls retry briefly until the
            // CameraController exposes its preview size, so this works on the
            // first toggle after a fresh camera open.
            requestPreviewTransforms()
        }
    }

    /**
     * Force-stop the currently-running preview (whichever surface set is active).
     * Called on entry/exit of fullscreen so the old cameras are fully torn down
     * before the new ones are opened — without this, the SurfaceTexture listeners
     * can re-arm the start path on the still-bound surfaces and the new cameras
     * never get a clean handoff.
     */
    private fun stopCurrentPreview() {
        try {
            dualCameraRecorder.closePreview()
        } catch (e: Exception) {
            Log.w(TAG, "stopCurrentPreview: closePreview threw ${e.message}")
        }
    }

    /**
     * Stop the current preview and yield briefly so the camera HAL has a moment
     * to start releasing the old devices before we ask it to open a new one.
     *
     * closePreview() already nulls out the controllers synchronously (isClosed()
     * returns true the moment it returns), so the previous 50 ms × 30 iterations
     * polling loop was effectively a single 50 ms sleep on the main thread —
     * and with oldThread.join(500) inside closePreview() the full transition
     * could block the UI for up to 1.5 s, freezing the preview when the user
     * toggled fullscreen fast. A short 10 ms yield is enough to give the HAL
     * a chance to release; any remaining latency is covered by the retry loop
     * in DualCameraRecorder.startPreview().
     */
    private fun stopCurrentPreviewAndWait() {
        stopCurrentPreview()
        try { Thread.sleep(10) } catch (_: InterruptedException) { return }
    }

    /**
     * Resize the fullscreen content frame so the two preview frames are visible ABOVE
     * the 240dp bottom controls panel in BOTH portrait and landscape. The bottom
     * panel is a sibling of the content frame inside the fullscreen overlay, so
     * anything that extends behind it is hidden. Reserving room for the panel here
     * makes the previews actually visible.
     *
     * - Portrait (3:4 vertical): width fills the screen, height = screen - bottom panel,
     *   anchored to TOP so the user can read the controls below.
     * - Landscape (4:3 horizontal): the two preview cards are sized so each card is
     *   exactly 4:3 (matching the main activity's preview card aspect ratio). The
     *   content frame spans the full screen width, with each card getting half the
     *   width. The frame is centred vertically — the bottom controls panel may
     *   cover the bottom of the cards, but the user can hide the controls via
     *   the "Hide controls" button to see the full previews.
     */
    private fun applyFullscreenContentSizing() {
        val frame = binding.fullscreenContentFrame.layoutParams as android.widget.FrameLayout.LayoutParams
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        // 240dp bottom panel -> subtract its pixel height from the available area.
        val bottomPanelPx = (240f * resources.displayMetrics.density).toInt()
        if (isLandscapeMode) {
            // Two 4:3 cards side by side fill the full screen width. The total
            // content height is screenW * 3 / 8 so each card is cardW=screenW/2 wide
            // and cardH=screenW/2 * 3/4 = screenW * 3/8 tall (4:3 ratio). This matches
            // the main activity's card sizing, so the FIT_CENTER transform produces
            // a preview that fills the card exactly (no letterbox bars).
            val contentW = screenW
            val contentH = (screenW * 3 / 8).coerceAtLeast(1)
            frame.width = contentW
            frame.height = contentH
            frame.gravity = android.view.Gravity.CENTER
        } else {
            // Portrait: full width, available height (screen - bottom panel), anchored
            // to TOP so the previews sit above the bottom controls panel.
            val availableH = (screenH - bottomPanelPx).coerceAtLeast(1)
            frame.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            frame.height = availableH
            frame.gravity = android.view.Gravity.TOP
        }
        binding.fullscreenContentFrame.layoutParams = frame

        // The frame dimensions may have just changed — reposition the labels so
        // they sit at the top of the visible video (portrait) or stay at the
        // frame top (landscape).
        positionFullscreenLabels()
    }

    /**
     * Align the "Front Camera" / "Rear Camera" labels with the top edge of the
     * VISIBLE video, not the top of the preview frame.
     *
     * In portrait fullscreen each preview frame is half-screen-wide × screen-height
     * (the content frame has horizontal orientation and weight=1 splits the width
     * evenly between the two cameras). The camera buffer is 4:3 (width>height),
     * rendered as 3:4 in portrait via the FIT_CENTER swap in applyPreviewTransform.
     * FIT_CENTER scales the image to fit the frame width and leaves equal vertical
     * letterbox bars above and below — the visible video is `(frameW / (3/4)) = frameW
     * * 4 / 3` tall, centred vertically. The XML anchors each label to `top|start`
     * of the frame with a 12dp margin, which puts the label inside that top
     * letterbox — visually far above the video. Here we shift the label down by
     * the letterbox offset (keeping the 12dp gap from the top of the visible image)
     * so it sits flush with the top of the video, just inside the frame.
     *
     * In landscape fullscreen the frame is sized exactly 4:3 to match the rotated
     * buffer, so FIT_CENTER produces no vertical letterbox — the labels already sit
     * at the top of the video via the static 12dp margin. We still (re-)apply the
     * default margin here in case a previous portrait call left them shifted down,
     * and we apply the update unconditionally so the call is idempotent across
     * orientation toggles.
     *
     * Only relevant while the fullscreen overlay is visible; in the main activity
     * the labels live inside their preview cards and follow the cards directly.
     */
    private fun positionFullscreenLabels() {
        if (!isFullscreen) return
        val marginPx = (12f * resources.displayMetrics.density).toInt()
        // Pre-rotation image aspect ratio used by FIT_CENTER (see
        // applyPreviewTransform: effW = srcH, effH = srcW, so effW/effH = 3/4).
        val imageAspectPortrait = 3f / 4f
        listOf(
            binding.fullscreenFrontFrame to binding.fullscreenFrontLabel,
            binding.fullscreenRearFrame to binding.fullscreenRearLabel
        ).forEach { (frame, label) ->
            val w = frame.width
            val h = frame.height
            if (w <= 0 || h <= 0) return@forEach
            val lp = label.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.topMargin = if (!isLandscapeMode) {
                // Portrait: 3:4 image fills the frame width, vertical letterbox
                // top + bottom. Shift the label down to sit just above the image.
                val videoH = w / imageAspectPortrait
                val letterbox = ((h - videoH) / 2f).toInt().coerceAtLeast(0)
                letterbox + marginPx
            } else {
                // Landscape: frame is exactly 4:3 (set in applyFullscreenContentSizing)
                // so FIT_CENTER has no vertical letterbox. Restore the default 12dp
                // top margin in case a previous portrait call shifted it.
                marginPx
            }
            label.layoutParams = lp
        }
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        Log.d(TAG, "exitFullscreen: stopping fullscreen preview first")
        isFullscreen = false
        // Restore to the checkbox-driven orientation, not the device sensor.
        requestedOrientation =
            if (isLandscapeMode) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // Drop the overlay's touch interception and go back to INVISIBLE — the
        // TextureViews keep their SurfaceTextures alive (so the camera is still
        // writing to them), but the overlay no longer draws or blocks the main
        // view. The INVISIBLE state also matches the XML default, so future
        // fullscreen entries start from the same baseline.
        binding.fullscreenPreviewOverlay.isClickable = false
        binding.fullscreenPreviewOverlay.isFocusable = false
        binding.fullscreenPreviewOverlay.visibility = View.INVISIBLE

        // Switch the digital meters back to the default rendering for the
        // in-card layout (see enterFullscreen for the matching brighter-mode
        // call). Idempotent so it's safe even if the overlay was never shown.
        binding.digitalMeterFrontFullscreen.setBrighterMode(false)
        binding.digitalMeterRearFullscreen.setBrighterMode(false)

        if (isRecording) {
            // The capture session was set up with BOTH the main and fullscreen
            // preview surfaces from the start (see startCamerasIfReady), so the
            // camera is still writing to the main TextureView's SurfaceTexture
            // after the overlay goes back to INVISIBLE. Exiting fullscreen
            // during recording is therefore a pure view-level change — no
            // camera reconfiguration, no MediaRecorder frame drop, no gap in
            // the recorded video.
        } else {
            // Same as the recording case: the capture session already has BOTH
            // pairs of preview surfaces, so the camera is still writing to the
            // main TextureView's SurfaceTexture after the overlay goes back to
            // INVISIBLE. The fullscreen toggle is a pure view-level change
            // here too — no close+reopen, no brief preview freeze.
            //
            // Re-apply the FIT_CENTER preview transforms for the main pair
            // (the only ones visible now) so the freshly-revealed TextureView
            // shows the camera at the right aspect ratio. The transforms were
            // applied for both pairs when startFullscreenCamerasIfReady()
            // originally opened the cameras, but it's cheap to re-apply on
            // every exit since the computation reads the TextureView's
            // current dimensions.
            requestPreviewTransforms()
        }
    }

    // ==================== Settings ====================

    private fun showSettingsSheet() {
        SettingsBottomSheet(this, settingsStore) {
            // Theme or language changed - recreate activity for resource reload.
            // Stash the currently selected cameras in the Application's transient
            // holder so the freshly created Activity restores them instead of
            // resetting to the first front + first rear defaults. The holder
            // lives in-memory only — when the app is killed and reopened the
            // fields are gone and the defaults come back, per the user request.
            val cfg = settingsStore.config.value
            if (cfg.frontCameraId.isNotEmpty()) {
                app.transientFrontCameraId = cfg.frontCameraId
            }
            if (cfg.rearCameraId.isNotEmpty()) {
                app.transientRearCameraId = cfg.rearCameraId
            }
            recreate()
        }.show()
    }

    // ==================== Helpers ====================

    /**
     * Determine the stream orientation from the Landscape-mode checkbox (never from the
     * physical sensor, so the user has explicit control and no auto-rotation happens).
     * The fullscreen follows the same checkbox (no separate fullscreen toggle).
     */
    private fun currentStreamOrientation(forFullscreen: Boolean = false): StreamOrientation {
        return if (isLandscapeMode) StreamOrientation.LANDSCAPE else StreamOrientation.PORTRAIT
    }

    private fun resolutionLabels(): List<String> {
        val orientation = currentStreamOrientation()
        return StreamResolution.entries.map { it.labelFor(orientation) }
    }
    private fun fpsLabels(): List<String> = StreamFps.entries.map { it.label }
    private fun bitrateLabels(): List<String> = StreamBitrate.entries.map { it.label }

    companion object {
        const val TAG = "MainActivity"

        // ISO options from ISO 12232 standard (full progression up to 1000000)
        val isoValues = arrayOf(
            100, 160, 200, 250, 320, 400, 500,
            600, 640, 800, 1000, 1250, 1600, 2000, 2500,
            3200, 4000, 5000, 6400, 8000, 10000, 12800,
            16000, 20000, 25600, 32000, 40000, 51200, 64000,
            80000, 102400, 128000, 160000, 200000, 260000,
            300000, 400000, 500000, 650000, 750000, 1000000
        )
        val isoLabels = arrayOf(
            "Auto",
            "100", "160", "200", "250", "320", "400", "500",
            "600", "640", "800", "1000", "1250", "1600", "2000", "2500",
            "3200", "4000", "5000", "6400", "8000", "10000", "12800",
            "16000", "20000", "25600", "32000", "40000", "51200", "64000",
            "80000", "102400", "128000", "160000", "200000", "260000",
            "300000", "400000", "500000", "650000", "750000", "1000000"
        )

        // Exposure time options (shutter speeds from 1/20000s to 60s)
        val exposureNanosValues = arrayOf(
            50_000L,            // 1/20000
            66_666L,            // 1/15000
            100_000L,           // 1/10000
            133_333L,           // 1/7500
            153_846L,           // 1/6500
            200_000L,           // 1/5000
            333_333L,           // 1/3000
            500_000L,           // 1/2000
            666_666L,           // 1/1500
            800_000L,           // 1/1250
            1_000_000L,         // 1/1000
            1_250_000L,         // 1/800
            2_000_000L,         // 1/500
            4_000_000L,         // 1/250
            5_000_000L,         // 1/200
            6_250_000L,         // 1/160
            8_333_333L,         // 1/120
            10_000_000L,        // 1/100
            12_500_000L,        // 1/80
            16_666_666L,        // 1/60
            20_000_000L,        // 1/50
            20_833_333L,        // 1/48
            33_333_333L,        // 1/30
            41_666_666L,        // 1/24
            66_666_666L,        // 1/15
            125_000_000L,       // 1/8
            250_000_000L,       // 1/4
            500_000_000L,       // 1/2
            1_000_000_000L,     // 1s
            2_000_000_000L,     // 2s
            3_000_000_000L,     // 3s
            5_000_000_000L,     // 5s
            10_000_000_000L,    // 10s
            15_000_000_000L,    // 15s
            20_000_000_000L,    // 20s
            30_000_000_000L,    // 30s
            45_000_000_000L,    // 45s
            60_000_000_000L     // 60s
        )
        val exposureLabels = arrayOf(
            "Auto",
            "1/20000", "1/15000", "1/10000", "1/7500", "1/6500",
            "1/5000", "1/3000", "1/2000", "1/1500", "1/1250",
            "1/1000", "1/800", "1/500", "1/250", "1/200", "1/160",
            "1/120", "1/100", "1/80", "1/60", "1/50", "1/48",
            "1/30", "1/24", "1/15", "1/8", "1/4", "1/2",
            "1s", "2s", "3s", "5s", "10s", "15s", "20s", "30s", "45s", "60s"
        )
    }
}
