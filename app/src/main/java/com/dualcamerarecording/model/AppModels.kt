package com.dualcamerarecording.model

/**
 * Data classes and enums for camera stream configuration.
 * Adapted from AndroidCamera project.
 */

// Camera facing direction for recording assignment
enum class CameraFacing(val id: Int) {
    FRONT(0),
    BACK(1);

    companion object {
        fun fromId(id: Int): CameraFacing = entries.firstOrNull { it.id == id } ?: FRONT
    }
}

// Manual control mode
enum class ControlMode {
    AUTO,
    MANUAL
}

// Manual control state
data class ManualControlState(
    val focusEnabled: Boolean = false,
    val isoEnabled: Boolean = false,
    val exposureEnabled: Boolean = false,
    val focusDistance: Float? = null,
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val exposureCompensation: Int? = null
)

// Base landscape resolutions (4:3 aspect ratio). Portrait orientations use swapped dimensions
// (e.g. 1280x960 -> 960x1280). Matches AndroidCamera resolution set.
enum class StreamResolution(val landscapeWidth: Int, val landscapeHeight: Int) {
    RES_480P(640, 480),
    RES_576P(768, 576),
    RES_768P(1024, 768),
    RES_960P(1280, 960),
    RES_1200P(1600, 1200),
    RES_1440P(1920, 1440),
    RES_1920P(2560, 1920);

    val aspectRatio: Double get() = 4.0 / 3.0

    fun widthFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeHeight else landscapeWidth

    fun heightFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeWidth else landscapeHeight

    fun labelFor(orientation: StreamOrientation): String =
        "${widthFor(orientation)}x${heightFor(orientation)}"
}

// Stream configuration for a single camera
data class StreamConfig(
    val cameraId: String = "",
    val facing: CameraFacing = CameraFacing.BACK,
    val resolution: StreamResolution = StreamResolution.RES_960P,
    val fps: StreamFps = StreamFps.FPS_30,
    val bitrate: StreamBitrate = StreamBitrate.AUTO,
    val controlMode: ControlMode = ControlMode.AUTO,
    val manualControl: ManualControlState = ManualControlState(),
    val flashEnabled: Boolean = false,
    val captureIntent: CaptureIntent = CaptureIntent.VIDEO,
    val jpegQuality: JpegQuality = JpegQuality.HIGH,
    val streamOrientation: StreamOrientation = StreamOrientation.PORTRAIT
)

// FPS options. Declaration order is also the spinner display order:
// highest compatible rate first (300 FPS) down to 24 FPS. The per-camera
// `StreamConfig.fps` keeps front and rear FPS independent.
enum class StreamFps(val label: String, val value: Int) {
    FPS_300("300 FPS", 300),
    FPS_240("240 FPS", 240),
    FPS_120("120 FPS", 120),
    FPS_90("90 FPS", 90),
    FPS_75("75 FPS", 75),
    FPS_60("60 FPS", 60),
    FPS_50("50 FPS", 50),
    FPS_48("48 FPS", 48),
    FPS_30("30 FPS", 30),
    FPS_25("25 FPS", 25),
    FPS_24("24 FPS", 24);

    companion object {
        fun fromValue(value: Int): StreamFps = entries.firstOrNull { it.value == value } ?: FPS_30
    }
}

// Bitrate options. AUTO lets MediaRecorder/encoder pick a sensible default for the
// resolution/fps; the explicit levels give the user per-camera control (two files,
// so the two cameras may use different bitrates).
enum class StreamBitrate(val label: String, val bps: Int) {
    AUTO("Auto", 0),
    MBPS_2("2 Mbps", 2_000_000),
    MBPS_4("4 Mbps", 4_000_000),
    MBPS_6("6 Mbps", 6_000_000),
    MBPS_8("8 Mbps", 8_000_000),
    MBPS_12("12 Mbps", 12_000_000),
    MBPS_16("16 Mbps", 16_000_000),
    MBPS_20("20 Mbps", 20_000_000),
    MBPS_30("30 Mbps", 30_000_000),
    MBPS_50("50 Mbps", 50_000_000);

    companion object {
        fun fromBps(bps: Int): StreamBitrate =
            entries.firstOrNull { it.bps == bps } ?: AUTO
    }
}

// Screen orientation for stream
enum class StreamOrientation {
    PORTRAIT,
    LANDSCAPE;

    val isPortrait: Boolean
        get() = this == PORTRAIT

    val isLandscape: Boolean
        get() = this == LANDSCAPE
}

// Capture intent
enum class CaptureIntent {
    VIDEO,
    PHOTO
}

// JPEG quality
enum class JpegQuality(val value: Int) {
    LOW(80),
    MEDIUM(85),
    HIGH(90),
    ULTRA(95);

    companion object {
        fun fromValue(value: Int): JpegQuality = entries.firstOrNull { it.value == value } ?: HIGH
    }
}

// Theme modes
enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

// App languages
enum class AppLanguage(val code: String) {
    ENGLISH("en"),
    ITALIAN("it");

    companion object {
        fun fromCode(code: String): AppLanguage = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

// Meter styles for audio volume display
enum class MeterStyle {
    DIGITAL,
    ANALOG
}

// Full dual camera configuration
data class DualCameraConfig(
    val frontCameraId: String = "",
    val rearCameraId: String = "",
    val frontConfig: StreamConfig = StreamConfig(),
    val rearConfig: StreamConfig = StreamConfig(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val meterStyle: MeterStyle = MeterStyle.DIGITAL,
    val cameraAssignment: CameraAssignment = CameraAssignment.FRONT_BACK,
    val audioSource: AudioSourceOption = AudioSourceOption.PHONE_MIC,
    val sharedAudioEnabled: Boolean = true,
    val recordingDurationLimitSeconds: Int = 0,
    /**
     * When true, tapping on a preview surface triggers an autofocus cycle at
     * the tap point. When false, the flash button is enabled and tap-to-focus
     * is disabled (the two modes are mutually exclusive — see MainActivity
     * for the wiring). Persisted across app restarts via the settings store.
     */
    val autofocusOnTap: Boolean = false,
    /**
     * Audio recording format. Fixed to AAC for now because the MP4 container
     * only supports compressed audio tracks (the public Android [MediaMuxer]
     * does not expose an MKV output format). Kept in the model for forward
     * compatibility with future WAV support.
     */
    val audioFormat: AudioRecordFormat = AudioRecordFormat.AAC,
    /** AAC bitrate in kbps. */
    val audioBitrateKbps: Int = 128,
    /** Capture / encode sample rate in Hz. */
    val audioSampleRateHz: Int = 44100
)

// Which physical camera maps to front/rear recording
enum class CameraAssignment {
    FRONT_BACK,     // First found = front, second = back
    BACK_FRONT,     // First found = back, second = front
    CUSTOM          // User selects specific cameras
}

// Audio source options
enum class AudioSourceOption {
    PHONE_MIC        // Single shared microphone track
}

/**
 * Audio container format for the recorded MP4 file. Only AAC is supported
 * today because the public Android [MediaMuxer] does not expose an MKV
 * output format. WAV entries are kept for forward compatibility but are
 * not reachable from the UI.
 */
enum class AudioRecordFormat(
    val storageValue: String,
    val label: String,
    /** MIME type used for the MediaMuxer audio track. */
    val mime: String,
    /** PCM encoding constant for `audio/raw` tracks (irrelevant for AAC). */
    val pcmEncoding: Int
) {
    AAC("aac", "AAC", "audio/mp4a-latm", android.media.AudioFormat.ENCODING_INVALID);

    companion object {
        fun fromStorage(value: String?): AudioRecordFormat =
            entries.firstOrNull { it.storageValue == value } ?: AAC
    }
}
