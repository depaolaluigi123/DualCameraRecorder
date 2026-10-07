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
 * Also hosts the shared MicCapture that drives the live audio meters and the recorded audio track.
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
     * Shared MicCapture instance: it feeds the live meters and, while recording, the
     * AAC encoder (one capture for both files). Started lazily so meters show
     * real-time levels even when no recording is in progress.
     */
    var micCapture: MicCapture? = null
        private set
    private val micHandler = Handler(Looper.getMainLooper())

    /**
     * Ensure MicCapture is initialized and capturing for live meter display.
     * Safe to call multiple times — only initializes once. The capture runs at the
     * audio sample rate selected in the settings, because the same capture also feeds
     * the AAC encoder of the recordings.
     */
    fun ensureMicCaptureStarted() {
        micHandler.post { startMicCaptureNow() }
    }

    /**
     * Restart the capture, e.g. after the user picked another sample rate. Queued on the
     * same handler as start/stop, so the order of calls is always respected.
     */
    fun restartMicCapture() {
        micHandler.post {
            stopMicCaptureNow()
            startMicCaptureNow()
        }
    }

    private fun startMicCaptureNow() {
        if (micCapture != null) return
        val sampleRate = settingsStore.config.value.audioSampleRateHz
        try {
            val mic = MicCapture()
            if (mic.initialize(
                    channelMode = MicCapture.ChannelMode.STEREO,
                    sampleRate = sampleRate,
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
                Log.d(TAG, "Live mic capture started (mode=${mic.channelMode}, $sampleRate Hz)")
            } else {
                Log.e(TAG, "Failed to initialize MicCapture for live metering")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No microphone permission for live metering: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting live mic capture: ${e.message}")
        }
    }

    private fun stopMicCaptureNow() {
        val mic = micCapture ?: return
        try {
            mic.stopCapture()
            mic.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing live mic: ${e.message}")
        }
        micCapture = null
    }

    /**
     * Stop and release the shared MicCapture (activity in background or destroyed).
     */
    fun stopLiveMicCapture() {
        micHandler.post { stopMicCaptureNow() }
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
