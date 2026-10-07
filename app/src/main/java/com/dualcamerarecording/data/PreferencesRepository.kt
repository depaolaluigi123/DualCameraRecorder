package com.dualcamerarecording.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import com.dualcamerarecording.model.AppLanguage
import com.dualcamerarecording.model.AspectRatio
import com.dualcamerarecording.model.AppThemeMode
import com.dualcamerarecording.model.CameraAssignment
import com.dualcamerarecording.model.MeterStyle
import com.dualcamerarecording.model.StreamBitrate
import com.dualcamerarecording.model.StreamResolution
import com.dualcamerarecording.recording.MediaCapabilities

/**
 * SharedPreferences-based settings persistence.
 * Adapted from AndroidCamera PreferencesRepository.
 *
 * Audio bitrate and sample rate are validated against the device's actual
 * capabilities, the same pattern used by MicGainLevelerApp's
 * PreferencesRepository.
 */
class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Theme settings
    var themeMode: AppThemeMode
        get() = AppThemeMode.values()[prefs.getInt(KEY_THEME_MODE, AppThemeMode.SYSTEM.ordinal)]
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value.ordinal).apply()

    // Language settings
    var language: AppLanguage
        get() = AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.code).apply()

    // Meter style
    var meterStyle: MeterStyle
        get() = MeterStyle.values()[prefs.getInt(KEY_METER_STYLE, MeterStyle.DIGITAL.ordinal)]
        set(value) = prefs.edit().putInt(KEY_METER_STYLE, value.ordinal).apply()

    // Camera assignment
    var cameraAssignment: CameraAssignment
        get() = CameraAssignment.values()[prefs.getInt(KEY_CAMERA_ASSIGNMENT, CameraAssignment.FRONT_BACK.ordinal)]
        set(value) = prefs.edit().putInt(KEY_CAMERA_ASSIGNMENT, value.ordinal).apply()

    // Front camera ID is intentionally NOT persisted: the user has to
    // pick the front and rear cameras on every launch. The system reminder
    // explicitly asked to not save the selected cameras in SharedPreferences.

    // Rear camera ID is intentionally NOT persisted (see frontCameraId).

    // Manual preferences
    var showManualControls: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MANUAL_CONTROLS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MANUAL_CONTROLS, value).apply()

    // Shared audio enabled (always true for this app)
    var sharedAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHARED_AUDIO_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHARED_AUDIO_ENABLED, value).apply()

    /**
     * AAC bitrate in kbps. Coerced against [allowedBitratesFor] the stored sample rate
     * on read/write, so the value always is one the encoder really produces.
     */
    var audioBitrateKbps: Int
        get() = coerceBitrate(prefs.getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE_KBPS), audioSampleRateHz)
        set(value) {
            prefs.edit().putInt(KEY_AUDIO_BITRATE, coerceBitrate(value, audioSampleRateHz)).apply()
        }

    /**
     * Capture sample rate in Hz. Coerced against [ALLOWED_SAMPLE_RATES] on
     * read/write (the device's AudioRecord has to support it for the recording
     * to succeed). Capped at 48 kHz — the user requested that no higher rate
     * is offered in the UI.
     */
    var audioSampleRateHz: Int
        get() {
            val stored = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, DEFAULT_AUDIO_SAMPLE_RATE_HZ)
            val allowed = ALLOWED_SAMPLE_RATES
            return if (allowed.isEmpty() || stored in allowed) stored
                else allowed.firstOrNull() ?: DEFAULT_AUDIO_SAMPLE_RATE_HZ
        }
        set(value) {
            val allowed = ALLOWED_SAMPLE_RATES
            val coerced = if (allowed.isEmpty() || value in allowed) value
                else allowed.firstOrNull() ?: DEFAULT_AUDIO_SAMPLE_RATE_HZ
            prefs.edit().putInt(KEY_AUDIO_SAMPLE_RATE, coerced).apply()
        }

    // ----------------- Recording shape -----------------

    /** Shape of each camera's recording, 4:3 or 16:9 (the "16:9" checkbox of each camera). */
    var frontAspect: AspectRatio
        get() = readAspect(KEY_FRONT_ASPECT_16_9)
        set(value) = prefs.edit().putBoolean(KEY_FRONT_ASPECT_16_9, value == AspectRatio.RATIO_16_9).apply()

    var rearAspect: AspectRatio
        get() = readAspect(KEY_REAR_ASPECT_16_9)
        set(value) = prefs.edit().putBoolean(KEY_REAR_ASPECT_16_9, value == AspectRatio.RATIO_16_9).apply()

    /** Per-camera shape; falls back to the single shared setting of the previous version. */
    private fun readAspect(key: String): AspectRatio {
        val wide = if (prefs.contains(key)) prefs.getBoolean(key, false) else prefs.getBoolean(KEY_ASPECT_16_9, false)
        return if (wide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
    }

    // ----------------- Front camera stream config -----------------

    /** Resolution of the camera's current shape: each shape keeps its own choice. */
    var frontResolution: StreamResolution
        get() = resolutionFor(KEY_FRONT_RESOLUTION, KEY_FRONT_RESOLUTION_16_9, frontAspect)
        set(value) = saveResolution(KEY_FRONT_RESOLUTION, KEY_FRONT_RESOLUTION_16_9, value)

    var frontFps: Int
        get() = readFps(KEY_FRONT_FPS)
        set(value) = prefs.edit().putInt(KEY_FRONT_FPS, value).apply()

    var frontBitrate: StreamBitrate
        get() = decodeBitrate(prefs.getString(KEY_FRONT_BITRATE, null))
        set(value) = prefs.edit().putString(KEY_FRONT_BITRATE, value.name).apply()

    // ----------------- Rear camera stream config -----------------

    var rearResolution: StreamResolution
        get() = resolutionFor(KEY_REAR_RESOLUTION, KEY_REAR_RESOLUTION_16_9, rearAspect)
        set(value) = saveResolution(KEY_REAR_RESOLUTION, KEY_REAR_RESOLUTION_16_9, value)

    var rearFps: Int
        get() = readFps(KEY_REAR_FPS)
        set(value) = prefs.edit().putInt(KEY_REAR_FPS, value).apply()

    var rearBitrate: StreamBitrate
        get() = decodeBitrate(prefs.getString(KEY_REAR_BITRATE, null))
        set(value) = prefs.edit().putString(KEY_REAR_BITRATE, value.name).apply()

    // Autofocus-on-tap is intentionally NOT persisted: the user has to
    // pick the tap-focus state on every launch. The system reminder
    // explicitly asked to not save "Tap focus" in SharedPreferences.

    // ----------------- Camera pair compatibility -----------------

    /**
     * (front id, rear id) pairs this device could not stream together (declared camera
     * conflict, or stream configuration refused by the camera HAL). Remembered so the app
     * does not retry them on every launch — a refused configuration can restart the HAL
     * (on the tested device it took up to ~20 s before any camera worked again).
     */
    fun isCameraPairIncompatible(frontId: String, rearId: String): Boolean =
        pairKey(frontId, rearId) in prefs.getStringSet(KEY_INCOMPATIBLE_PAIRS, emptySet()).orEmpty()

    fun setCameraPairIncompatible(frontId: String, rearId: String, incompatible: Boolean) {
        val current = prefs.getStringSet(KEY_INCOMPATIBLE_PAIRS, emptySet()).orEmpty().toMutableSet()
        val changed = if (incompatible) current.add(pairKey(frontId, rearId)) else current.remove(pairKey(frontId, rearId))
        if (changed) prefs.edit().putStringSet(KEY_INCOMPATIBLE_PAIRS, current).apply()
    }

    private fun pairKey(frontId: String, rearId: String) = "$frontId|$rearId"

    // ----------------- Device compatibility alert -----------------

    /**
     * Controls whether the device-compatibility alert is shown at app startup.
     * Defaults to true so the alert is shown on first launch; once the user
     * checks the "Don't show again" box and dismisses the dialog, this flips
     * to false and the alert is suppressed on every subsequent launch.
     */
    var showDeviceCompatibilityAlert: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COMPAT_ALERT, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COMPAT_ALERT, value).apply()

    /**
     * The saved resolution for [aspect]. A 16:9 resolution never chosen yet starts from
     * the counterpart of the 4:3 one (1280x960 -> 1280x720).
     */
    private fun resolutionFor(key4x3: String, key16x9: String, aspect: AspectRatio): StreamResolution {
        val saved4x3 = decodeResolution(prefs.getString(key4x3, null))
            ?.takeIf { it.aspect == AspectRatio.RATIO_4_3 } ?: DEFAULT_RESOLUTION
        if (aspect == AspectRatio.RATIO_4_3) return saved4x3
        return decodeResolution(prefs.getString(key16x9, null))
            ?.takeIf { it.aspect == AspectRatio.RATIO_16_9 }
            ?: saved4x3.counterpart(AspectRatio.RATIO_16_9)
    }

    private fun saveResolution(key4x3: String, key16x9: String, value: StreamResolution) {
        val key = if (value.aspect == AspectRatio.RATIO_16_9) key16x9 else key4x3
        prefs.edit().putString(key, value.name).apply()
    }

    private fun decodeResolution(raw: String?): StreamResolution? =
        raw?.let { name -> StreamResolution.entries.firstOrNull { it.name == name } ?: LEGACY_RESOLUTIONS[name] }

    /** Frames per second; earlier versions saved an enum name such as "FPS_30". */
    private fun readFps(key: String): Int =
        when (val raw = prefs.all[key]) {
            is Int -> raw
            is String -> raw.filter { it.isDigit() }.toIntOrNull()
            else -> null
        }?.takeIf { it > 0 } ?: DEFAULT_FPS

    private fun decodeBitrate(raw: String?): StreamBitrate =
        raw?.let { name -> StreamBitrate.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_BITRATE

    // Landscape mode is intentionally NOT persisted: the app always starts in portrait
    // (vertical) and the user has to explicitly enable landscape on each launch. The
    // helper below only clears any leftover preference to avoid surprising the user
    // with a stale "landscape" choice on the next start.

    companion object {
        const val PREFS_NAME = "dualcamera_prefs"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_METER_STYLE = "meter_style"
        private const val KEY_CAMERA_ASSIGNMENT = "camera_assignment"
        private const val KEY_SHOW_MANUAL_CONTROLS = "show_manual_controls"
        private const val KEY_SHARED_AUDIO_ENABLED = "shared_audio_enabled"

        private const val KEY_AUDIO_BITRATE = "audio_bitrate_kbps"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate_hz"

        /** Shared 16:9 setting of the previous version (read as fallback only). */
        private const val KEY_ASPECT_16_9 = "aspect_16_9"
        private const val KEY_FRONT_ASPECT_16_9 = "front_aspect_16_9"
        private const val KEY_REAR_ASPECT_16_9 = "rear_aspect_16_9"

        private const val KEY_FRONT_RESOLUTION = "front_resolution"
        private const val KEY_FRONT_RESOLUTION_16_9 = "front_resolution_16_9"
        private const val KEY_FRONT_FPS = "front_fps"
        private const val KEY_FRONT_BITRATE = "front_bitrate"

        private const val KEY_REAR_RESOLUTION = "rear_resolution"
        private const val KEY_REAR_RESOLUTION_16_9 = "rear_resolution_16_9"
        private const val KEY_REAR_FPS = "rear_fps"
        private const val KEY_REAR_BITRATE = "rear_bitrate"

        private const val KEY_SHOW_COMPAT_ALERT = "show_compat_alert"
        private const val KEY_INCOMPATIBLE_PAIRS = "incompatible_camera_pairs"

        /** Default AAC bitrate in kbps, matching MicGainLevelerApp. */
        const val DEFAULT_AUDIO_BITRATE_KBPS = 128

        /**
         * Default sample rate in Hz. 44.1 kHz is universally supported and
         * is the AudioRecord rate the rest of the app also uses for live
         * meters, so the recording and the meter pipeline stay in sync.
         */
        const val DEFAULT_AUDIO_SAMPLE_RATE_HZ = 44100

        val DEFAULT_RESOLUTION: StreamResolution = StreamResolution.R1280X960
        const val DEFAULT_FPS = 30
        val DEFAULT_BITRATE: StreamBitrate = StreamBitrate.AUTO

        /** Resolution names saved by earlier versions (all 4:3). */
        private val LEGACY_RESOLUTIONS = mapOf(
            "RES_480P" to StreamResolution.R640X480,
            "RES_576P" to StreamResolution.R768X576,
            "RES_768P" to StreamResolution.R1024X768,
            "RES_960P" to StreamResolution.R1280X960,
            "RES_1200P" to StreamResolution.R1600X1200,
            "RES_1440P" to StreamResolution.R1920X1440,
            "RES_1920P" to StreamResolution.R2560X1920
        )

        /** AAC bitrate ladder (kbps) before filtering. */
        private val BITRATE_LADDER = listOf(
            32, 48, 64, 80, 96, 128, 160, 192, 224, 256,
            320, 384, 448, 512, 576, 640, 768, 896, 1024
        )

        /** Recordings are always stereo AAC-LC. */
        const val AUDIO_CHANNELS = 2

        /**
         * AAC bitrates (kbps) the encoder really produces for stereo audio at
         * [sampleRate]: AAC-LC carries at most 6 bits per sample per channel, so the
         * maximum depends on the sample rate (e.g. 576 kbps at 48 kHz, 96 kbps at 8 kHz).
         */
        fun allowedBitratesFor(sampleRate: Int): List<Int> {
            val max = MediaCapabilities.aacMaxBitrate(sampleRate, AUDIO_CHANNELS)
            val min = MediaCapabilities.aacMinBitrate()
            return BITRATE_LADDER.filter { it * 1000 in min..max }.ifEmpty { listOf(BITRATE_LADDER.first()) }
        }

        /** [kbps] if allowed at [sampleRate], else the highest allowed value below it. */
        fun coerceBitrate(kbps: Int, sampleRate: Int): Int {
            val allowed = allowedBitratesFor(sampleRate)
            return if (kbps in allowed) kbps else allowed.filter { it <= kbps }.maxOrNull() ?: allowed.first()
        }

        /**
         * Capture sample rates supported by both the AAC encoder and AudioRecord in stereo.
         *
         * The list is hardcoded to rates UP TO 48000 Hz per the user request:
         * do not offer higher rates in the UI (88.2, 96, 176.4, 192 kHz are
         * not in the list at all). 48 kHz is the highest value the user is
         * allowed to pick.
         */
        val ALLOWED_SAMPLE_RATES: List<Int> by lazy {
            val candidates = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000)
            candidates.filter { rate ->
                MediaCapabilities.isAacSampleRateSupported(rate) &&
                    AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) > 0
            }.ifEmpty { listOf(44100, 48000) }
        }
    }
}
