package com.dualcamerarecording.settings

import com.dualcamerarecording.data.PreferencesRepository
import com.dualcamerarecording.model.DualCameraConfig
import com.dualcamerarecording.model.StreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * StateFlow-based single source of truth for camera settings.
 * Adapted from AndroidCamera WebcamSettingsStore.
 *
 * The store is now wired to [PreferencesRepository] so every change to the
 * stream / audio settings is persisted to SharedPreferences. On
 * construction the in-memory state is seeded with whatever was saved on
 * the previous launch — the user no longer has to re-pick the resolution,
 * FPS, bitrate, audio bitrate, or sample rate every time the app starts.
 *
 * The "Tap focus" (autofocus on tap) toggle, the front camera id, and the
 * rear camera id are NOT persisted: per the user request, the user has to
 * pick them on every launch.
 */
class CameraSettingsStore(
    private val prefs: PreferencesRepository? = null
) {

    private val _config = MutableStateFlow(loadInitialConfig())
    val config: StateFlow<DualCameraConfig> = _config.asStateFlow()

    fun updateConfig(update: (DualCameraConfig) -> DualCameraConfig) {
        _config.update(update)
    }

    fun getConfig(): DualCameraConfig = _config.value

    // Convenience update methods
    fun updateFrontCameraId(cameraId: String) {
        // The selected front camera is intentionally NOT persisted (per
        // the user request). The value is held in the StateFlow so the
        // current session still works, but it will not survive a restart.
        _config.update { it.copy(frontCameraId = cameraId) }
    }

    fun updateRearCameraId(cameraId: String) {
        // The selected rear camera is intentionally NOT persisted (per
        // the user request). The value is held in the StateFlow so the
        // current session still works, but it will not survive a restart.
        _config.update { it.copy(rearCameraId = cameraId) }
    }

    fun updateFrontConfig(config: StreamConfig) {
        // Persist the parts of the stream config that the user is allowed to
        // pick in the UI. Manual control flags, facing and camera id are
        // already in the store from other paths.
        prefs?.frontResolution = config.resolution
        prefs?.frontFps = config.fps
        prefs?.frontBitrate = config.bitrate
        _config.update { it.copy(frontConfig = config) }
    }

    fun updateRearConfig(config: StreamConfig) {
        prefs?.rearResolution = config.resolution
        prefs?.rearFps = config.fps
        prefs?.rearBitrate = config.bitrate
        _config.update { it.copy(rearConfig = config) }
    }

    fun updateThemeMode(mode: com.dualcamerarecording.model.AppThemeMode) {
        _config.update { it.copy(themeMode = mode) }
    }

    fun updateLanguage(language: com.dualcamerarecording.model.AppLanguage) {
        _config.update { it.copy(language = language) }
    }

    fun updateMeterStyle(style: com.dualcamerarecording.model.MeterStyle) {
        // Persist alongside the in-memory StateFlow so the user's choice
        // (Digital vs Analog) is restored on the next app launch.
        prefs?.meterStyle = style
        _config.update { it.copy(meterStyle = style) }
    }

    fun updateCameraAssignment(assignment: com.dualcamerarecording.model.CameraAssignment) {
        _config.update { it.copy(cameraAssignment = assignment) }
    }

    fun updateAutofocusOnTap(enabled: Boolean) {
        // The "Tap focus" toggle is intentionally NOT persisted (per
        // the user request). The value is held in the StateFlow so the
        // current session still works, but it will not survive a restart.
        _config.update { it.copy(autofocusOnTap = enabled) }
    }

    fun updateAudioBitrateKbps(kbps: Int) {
        prefs?.audioBitrateKbps = kbps
        _config.update { it.copy(audioBitrateKbps = kbps) }
    }

    fun updateAudioSampleRateHz(hz: Int) {
        prefs?.audioSampleRateHz = hz
        _config.update { it.copy(audioSampleRateHz = hz) }
    }

    /**
     * Re-read audio settings from [PreferencesRepository] into the in-memory
     * config. Called after a fresh install / first launch so the audio
     * defaults (computed against the device's hardware) override the
     * hard-coded values in [DualCameraConfig].
     */
    fun hydrateAudioSettings() {
        val p = prefs ?: return
        _config.update {
            it.copy(
                audioBitrateKbps = p.audioBitrateKbps,
                audioSampleRateHz = p.audioSampleRateHz
            )
        }
    }

    private fun loadInitialConfig(): DualCameraConfig {
        val p = prefs ?: return DualCameraConfig()
        // Seed every persisted field from SharedPreferences. The
        // frontCameraId / rearCameraId / autofocusOnTap fields are
        // intentionally left at their default empty / false values —
        // they are not persisted per the user request.
        return DualCameraConfig(
            frontConfig = DualCameraConfig().frontConfig.copy(
                resolution = p.frontResolution,
                fps = p.frontFps,
                bitrate = p.frontBitrate
            ),
            rearConfig = DualCameraConfig().rearConfig.copy(
                resolution = p.rearResolution,
                fps = p.rearFps,
                bitrate = p.rearBitrate
            ),
            audioBitrateKbps = p.audioBitrateKbps,
            audioSampleRateHz = p.audioSampleRateHz
        )
    }
}
