package com.dualcamerarecording

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dualcamerarecording.audio.MicCapture
import com.dualcamerarecording.audio.MicStateStore
import com.dualcamerarecording.data.PreferencesRepository
import com.dualcamerarecording.locale.LocaleManager
import com.dualcamerarecording.settings.CameraSettingsStore
import com.dualcamerarecording.theme.ThemeManager

/**
 * Application class: initializes preferences, theme, locale, and settings store.
 * Also hosts a shared MicCapture for live audio metering across MainActivity and RecordingService.
 * Adapted from AndroidCamera AndroidCameraApp.
 */
class DualCameraRecorderApp : Application() {

    val preferences: PreferencesRepository by lazy {
        PreferencesRepository(this)
    }

    /**
     * In-memory copy of the user's currently selected front + rear cameras,
     * kept across an Activity recreate (theme / language change) so the user's
     * choice does NOT reset to the two defaults. Lives only in the running
     * process: when the app is closed and reopened the defaults are restored,
     * per the user request. NOT persisted to SharedPreferences.
     *
     * The recreate flow stashes the live values here right before calling
     * [androidx.appcompat.app.AppCompatActivity.recreate]; the freshly
     * created Activity reads them back in [populateCameraSpinners] before
     * defaulting to the first front + first rear.
     *
     * The fields are reset to null as soon as the new Activity consumes
     * them so subsequent restarts (without a recreate in between) still
     * get the defaults.
     */
    @Transient
    var transientFrontCameraId: String? = null
    @Transient
    var transientRearCameraId: String? = null

    val themeManager: ThemeManager by lazy {
        ThemeManager()
    }

    /**
     * Settings store wired to the [PreferencesRepository] so the
     * resolution / FPS / bitrate / audio bitrate / sample rate / autofocus
     * the user picks in the UI are persisted across app restarts.
     */
    val settingsStore: CameraSettingsStore by lazy {
        CameraSettingsStore(preferences)
    }

    val micStateStore: MicStateStore by lazy {
        MicStateStore()
    }

    /**
     * Shared MicCapture instance for live audio metering.
     * Initialized and started lazily so meters show real-time levels even
     * when no recording is in progress. RecordingService reuses this instance
     * instead of creating its own.
     */
    var micCapture: MicCapture? = null
        private set
    private val micHandler = Handler(Looper.getMainLooper())
    private var micCaptureStarted = false

    /**
     * Ensure MicCapture is initialized and capturing for live meter display.
     * Safe to call multiple times — only initializes once.
     */
    fun ensureMicCaptureStarted() {
        if (micCaptureStarted) return
        micCaptureStarted = true
        micHandler.post {
            try {
                val mic = MicCapture()
                if (mic.initialize(
                        channelMode = MicCapture.ChannelMode.STEREO,
                        sampleRate = 44100,
                        bufferSizeSeconds = 2.0
                    )) {
                    // Wire up the monitor tap to feed levels into the shared MicStateStore.
                    // leftDb  -> left meter  -> front mic; rightDb -> right meter -> rear mic.
                    // peakLeft/peakRight are the running-max (highest-measured) values.
                    mic.setMonitorTap(object : MicCapture.MonitorTap {
                        override fun onAudioLevels(
                            leftDb: Double,
                            rightDb: Double,
                            peakLeft: Double,
                            peakRight: Double
                        ) {
                            micStateStore.updateLiveMeters(
                                elapsedMs = System.currentTimeMillis(),
                                leftDb = leftDb,
                                rightDb = rightDb,
                                peakDb1 = peakLeft,
                                peakDb2 = peakRight
                            )
                        }
                    })
                    micCapture = mic
                    mic.startCapture()
                    Log.d(TAG, "Live mic capture started for metering (mode=${mic.channelMode})")
                } else {
                    Log.e(TAG, "Failed to initialize MicCapture for live metering")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "No microphone permission for live metering: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting live mic capture: ${e.message}")
            }
        }
    }

    /**
     * Stop and release the shared MicCapture. Called by RecordingService
     * before it starts its own capture, and by onDestroy for cleanup.
     */
    fun stopLiveMicCapture() {
        micHandler.post {
            val mic = micCapture
            if (mic != null) {
                try {
                    mic.stopCapture()
                    mic.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing live mic: ${e.message}")
                }
            }
            micCapture = null
            micCaptureStarted = false
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Apply saved theme
        val savedTheme = preferences.themeMode
        themeManager.applyTheme(savedTheme)
        settingsStore.updateThemeMode(savedTheme)

        // Apply saved language
        val savedLanguage = preferences.language
        settingsStore.updateLanguage(savedLanguage)

        // Apply saved meter style
        val savedMeterStyle = preferences.meterStyle
        settingsStore.updateMeterStyle(savedMeterStyle)

        // Apply saved camera assignment
        val savedAssignment = preferences.cameraAssignment
        settingsStore.updateCameraAssignment(savedAssignment)

        // The store has already been constructed with the persisted
        // resolution / FPS / bitrate / audio settings via the lazy
        // initializer (see [settingsStore]). Audio bitrate / sample rate
        // have a hardware-dependent allowed list that can change between
        // app launches (e.g. after the user plugs in a USB mic), so refresh
        // them one more time in case the device now exposes different
        // values than the previous session.
        settingsStore.hydrateAudioSettings()
    }

    override fun attachBaseContext(base: Context?) {
        val savedLanguage = LocaleManager.getSavedLanguage(base!!)
        super.attachBaseContext(LocaleManager.wrapWithSavedLocale(base, savedLanguage))
    }

    companion object {
        const val TAG = "DualCameraRecorderApp"
    }
}
