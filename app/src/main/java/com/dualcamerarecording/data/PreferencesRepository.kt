package com.dualcamerarecording.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.MediaCodecList
import android.media.MediaFormat
import com.dualcamerarecording.model.AppLanguage
import com.dualcamerarecording.model.AppThemeMode
import com.dualcamerarecording.model.CameraAssignment
import com.dualcamerarecording.model.MeterStyle
import com.dualcamerarecording.model.StreamBitrate
import com.dualcamerarecording.model.StreamFps
import com.dualcamerarecording.model.StreamResolution

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
     * AAC bitrate in kbps. Coerced against [ALLOWED_BITRATES] on read/write so
     * a stale or hand-edited SharedPreferences value never lands in the UI.
     */
    var audioBitrateKbps: Int
        get() {
            val stored = prefs.getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE_KBPS)
            val allowed = ALLOWED_BITRATES
            return if (allowed.isEmpty() || stored in allowed) stored
                else allowed.firstOrNull() ?: DEFAULT_AUDIO_BITRATE_KBPS
        }
        set(value) {
            val allowed = ALLOWED_BITRATES
            val coerced = if (allowed.isEmpty() || value in allowed) value
                else allowed.firstOrNull() ?: DEFAULT_AUDIO_BITRATE_KBPS
            prefs.edit().putInt(KEY_AUDIO_BITRATE, coerced).apply()
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

    // ----------------- Front camera stream config -----------------

    var frontResolution: StreamResolution
        get() = decodeResolution(prefs.getString(KEY_FRONT_RESOLUTION, null))
        set(value) = prefs.edit().putString(KEY_FRONT_RESOLUTION, value.name).apply()

    var frontFps: StreamFps
        get() = decodeFps(prefs.getString(KEY_FRONT_FPS, null))
        set(value) = prefs.edit().putString(KEY_FRONT_FPS, value.name).apply()

    var frontBitrate: StreamBitrate
        get() = decodeBitrate(prefs.getString(KEY_FRONT_BITRATE, null))
        set(value) = prefs.edit().putString(KEY_FRONT_BITRATE, value.name).apply()

    // ----------------- Rear camera stream config -----------------

    var rearResolution: StreamResolution
        get() = decodeResolution(prefs.getString(KEY_REAR_RESOLUTION, null))
        set(value) = prefs.edit().putString(KEY_REAR_RESOLUTION, value.name).apply()

    var rearFps: StreamFps
        get() = decodeFps(prefs.getString(KEY_REAR_FPS, null))
        set(value) = prefs.edit().putString(KEY_REAR_FPS, value.name).apply()

    var rearBitrate: StreamBitrate
        get() = decodeBitrate(prefs.getString(KEY_REAR_BITRATE, null))
        set(value) = prefs.edit().putString(KEY_REAR_BITRATE, value.name).apply()

    // Autofocus-on-tap is intentionally NOT persisted: the user has to
    // pick the tap-focus state on every launch. The system reminder
    // explicitly asked to not save "Tap focus" in SharedPreferences.

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

    private fun decodeResolution(raw: String?): StreamResolution =
        raw?.let { name -> StreamResolution.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_FRONT_RESOLUTION

    private fun decodeFps(raw: String?): StreamFps =
        raw?.let { name -> StreamFps.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_FPS

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

        private const val KEY_FRONT_RESOLUTION = "front_resolution"
        private const val KEY_FRONT_FPS = "front_fps"
        private const val KEY_FRONT_BITRATE = "front_bitrate"

        private const val KEY_REAR_RESOLUTION = "rear_resolution"
        private const val KEY_REAR_FPS = "rear_fps"
        private const val KEY_REAR_BITRATE = "rear_bitrate"

        private const val KEY_SHOW_COMPAT_ALERT = "show_compat_alert"

        /** Default AAC bitrate in kbps, matching MicGainLevelerApp. */
        const val DEFAULT_AUDIO_BITRATE_KBPS = 128

        /**
         * Default sample rate in Hz. 44.1 kHz is universally supported and
         * is the AudioRecord rate the rest of the app also uses for live
         * meters, so the recording and the meter pipeline stay in sync.
         */
        const val DEFAULT_AUDIO_SAMPLE_RATE_HZ = 44100

        val DEFAULT_FRONT_RESOLUTION: StreamResolution = StreamResolution.RES_960P
        val DEFAULT_REAR_RESOLUTION: StreamResolution = StreamResolution.RES_960P
        val DEFAULT_FPS: StreamFps = StreamFps.FPS_30
        val DEFAULT_BITRATE: StreamBitrate = StreamBitrate.AUTO

        /**
         * AAC bitrate ladder filtered against the device's hardware AAC encoder's
         * max bitrate. Resolved lazily.
         */
        val ALLOWED_BITRATES: List<Int> by lazy {
            val ladder = listOf(
                32, 48, 64, 80, 96, 128, 160, 192, 224, 256,
                320, 384, 448, 512, 576, 640, 768, 896, 1024
            )
            val maxBps = aacEncoderMaxBitrateBps()
            if (maxBps <= 0) ladder
            else ladder.filter { it * 1000 <= maxBps }.ifEmpty { listOf(128) }
        }

        /**
         * Capture sample rates filtered against AudioRecord + the AAC encoder.
         * Resolved lazily. 44.1 kHz + 48 kHz are always included as a baseline
         * because they are the two rates every Android device supports.
         *
         * The list is hardcoded to rates UP TO 48000 Hz per the user request:
         * do not offer higher rates in the UI (88.2, 96, 176.4, 192 kHz are
         * not in the list at all). 48 kHz is the highest value the user is
         * allowed to pick.
         */
        val ALLOWED_SAMPLE_RATES: List<Int> by lazy {
            val candidates = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000)
            candidates.filter { rate ->
                runCatching {
                    val sampleFormat = AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                    val aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
                    aac.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                }.isSuccess
            }.ifEmpty { listOf(44100, 48000) }
        }

        /**
         * Returns the device's hardware AAC encoder max bitrate in bps, or -1
         * if the encoder is not present.
         */
        private fun aacEncoderMaxBitrateBps(): Int {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.name.contains("OMX.google.aac", ignoreCase = true) ||
                    info.name.contains("c2.android.aac", ignoreCase = true) ||
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
                ) {
                    for (type in info.supportedTypes) {
                        if (!type.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)) continue
                        val caps = info.getCapabilitiesForType(type)
                        val audioCaps = caps.audioCapabilities ?: continue
                        val maxBps = audioCaps.bitrateRange?.upper ?: -1
                        if (maxBps > 0) return maxBps
                    }
                }
            }
            return -1
        }
    }
}
