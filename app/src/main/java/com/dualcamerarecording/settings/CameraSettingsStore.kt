package com.dualcamerarecording.settings

import com.dualcamerarecording.data.PreferencesRepository
import com.dualcamerarecording.model.AspectRatio
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
 * The front camera id and the rear camera id are NOT persisted: per the
 * user request, the user has to pick them on every launch.
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
        // pick in the UI — only the ones that changed: the in-memory values may be
        // a camera-specific reduction of the saved preference (see
        // applyEffectiveConfig), which must not overwrite it when another field
        // (manual controls, flash) is updated.
        val current = _config.value.frontConfig
        if (config.resolution != current.resolution) prefs?.frontResolution = config.resolution
        if (config.fps != current.fps) prefs?.frontFps = config.fps
        if (config.bitrate != current.bitrate) prefs?.frontBitrate = config.bitrate
        _config.update { it.copy(frontConfig = config) }
    }

    fun updateRearConfig(config: StreamConfig) {
        val current = _config.value.rearConfig
        if (config.resolution != current.resolution) prefs?.rearResolution = config.resolution
        if (config.fps != current.fps) prefs?.rearFps = config.fps
        if (config.bitrate != current.bitrate) prefs?.rearBitrate = config.bitrate
        _config.update { it.copy(rearConfig = config) }
    }

    /**
     * Switch one camera to [aspect] (its "16:9" checkbox). Each shape keeps its own saved
     * resolution, so switching back restores the previous choice.
     */
    fun updateAspect(isFront: Boolean, aspect: AspectRatio) {
        val current = if (isFront) _config.value.frontConfig else _config.value.rearConfig
        if (aspect == current.aspect) return
        if (isFront) prefs?.frontAspect = aspect else prefs?.rearAspect = aspect
        val resolution = (if (isFront) prefs?.frontResolution else prefs?.rearResolution)
            ?: current.resolution.counterpart(aspect)
        val updated = current.copy(aspect = aspect, resolution = resolution)
        _config.update { if (isFront) it.copy(frontConfig = updated) else it.copy(rearConfig = updated) }
    }

    /**
     * Use [config] for the current session without saving it: the values the selected
     * camera can deliver when the saved preference is not supported by it. The saved
     * preference is kept, so selecting a camera that supports it restores it.
     */
    fun applyEffectiveConfig(isFront: Boolean, config: StreamConfig) {
        _config.update { if (isFront) it.copy(frontConfig = config) else it.copy(rearConfig = config) }
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

    fun updateAudioBitrateKbps(kbps: Int) {
        val coerced = PreferencesRepository.coerceBitrate(kbps, _config.value.audioSampleRateHz)
        prefs?.audioBitrateKbps = coerced
        _config.update { it.copy(audioBitrateKbps = coerced) }
    }

    /**
     * Change the sample rate. The AAC bitrate ceiling depends on it, so the bitrate is
     * lowered to the highest valid value when it would exceed the new limit.
     */
    fun updateAudioSampleRateHz(hz: Int) {
        prefs?.audioSampleRateHz = hz
        val kbps = PreferencesRepository.coerceBitrate(_config.value.audioBitrateKbps, hz)
        prefs?.audioBitrateKbps = kbps
        _config.update { it.copy(audioSampleRateHz = hz, audioBitrateKbps = kbps) }
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
        // frontCameraId / rearCameraId fields are
        // intentionally left at their default empty values —
        // they are not persisted per the user request.
        return DualCameraConfig(
            frontConfig = DualCameraConfig().frontConfig.copy(
                aspect = p.frontAspect,
                resolution = p.frontResolution,
                fps = p.frontFps,
                bitrate = p.frontBitrate
            ),
            rearConfig = DualCameraConfig().rearConfig.copy(
                aspect = p.rearAspect,
                resolution = p.rearResolution,
                fps = p.rearFps,
                bitrate = p.rearBitrate
            ),
            audioBitrateKbps = p.audioBitrateKbps,
            audioSampleRateHz = p.audioSampleRateHz
        )
    }
}
