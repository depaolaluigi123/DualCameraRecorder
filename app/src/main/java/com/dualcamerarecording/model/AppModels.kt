package com.dualcamerarecording.model

import kotlin.math.abs

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

/** Shape of the recordings, and of the previews so that they show what is recorded. */
enum class AspectRatio(private val ratio: Double) {
    RATIO_4_3(4.0 / 3.0),
    RATIO_16_9(16.0 / 9.0);

    /** True when [width] x [height] has this shape (1% tolerance: 854x480 is 16:9). */
    fun matches(width: Int, height: Int): Boolean =
        height > 0 && abs(width.toDouble() / height - ratio) <= ratio * 0.01
}

// Recording resolutions, landscape (portrait swaps the dimensions, e.g. 1280x960 -> 960x1280),
// 4:3 and 16:9 up to 4K. The spinners offer the ones of the selected shape that the camera
// and the encoder support. The entry name is what the preferences store; the names used by
// earlier versions are mapped in PreferencesRepository.
enum class StreamResolution(val landscapeWidth: Int, val landscapeHeight: Int, val aspect: AspectRatio) {
    R640X480(640, 480, AspectRatio.RATIO_4_3),
    R768X576(768, 576, AspectRatio.RATIO_4_3),
    R800X600(800, 600, AspectRatio.RATIO_4_3),
    R1024X768(1024, 768, AspectRatio.RATIO_4_3),
    R1280X960(1280, 960, AspectRatio.RATIO_4_3),
    R1440X1080(1440, 1080, AspectRatio.RATIO_4_3),
    R1600X1200(1600, 1200, AspectRatio.RATIO_4_3),
    R1920X1440(1920, 1440, AspectRatio.RATIO_4_3),
    R2048X1536(2048, 1536, AspectRatio.RATIO_4_3),
    R2304X1728(2304, 1728, AspectRatio.RATIO_4_3),
    R2560X1920(2560, 1920, AspectRatio.RATIO_4_3),
    R2592X1944(2592, 1944, AspectRatio.RATIO_4_3),
    R2880X2160(2880, 2160, AspectRatio.RATIO_4_3),
    R3264X2448(3264, 2448, AspectRatio.RATIO_4_3),
    R4000X3000(4000, 3000, AspectRatio.RATIO_4_3),
    R4032X3024(4032, 3024, AspectRatio.RATIO_4_3),

    R640X360(640, 360, AspectRatio.RATIO_16_9),
    R854X480(854, 480, AspectRatio.RATIO_16_9),
    R960X540(960, 540, AspectRatio.RATIO_16_9),
    R1024X576(1024, 576, AspectRatio.RATIO_16_9),
    R1280X720(1280, 720, AspectRatio.RATIO_16_9),
    R1600X900(1600, 900, AspectRatio.RATIO_16_9),
    R1920X1080(1920, 1080, AspectRatio.RATIO_16_9),
    R2560X1440(2560, 1440, AspectRatio.RATIO_16_9),
    R2688X1512(2688, 1512, AspectRatio.RATIO_16_9),
    R3200X1800(3200, 1800, AspectRatio.RATIO_16_9),
    R3840X2160(3840, 2160, AspectRatio.RATIO_16_9);

    val pixels: Int get() = landscapeWidth * landscapeHeight

    fun widthFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeHeight else landscapeWidth

    fun heightFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeWidth else landscapeHeight

    fun labelFor(orientation: StreamOrientation): String =
        "${widthFor(orientation)}x${heightFor(orientation)}"

    /**
     * The resolution of [shape] that corresponds to this one: the same width when it exists
     * (1280x960 <-> 1280x720), otherwise the closest width, then the closest pixel count.
     */
    fun counterpart(shape: AspectRatio): StreamResolution =
        if (aspect == shape) this
        else of(shape).minWith(compareBy({ abs(it.landscapeWidth - landscapeWidth) }, { abs(it.pixels - pixels) }))

    companion object {
        fun of(shape: AspectRatio): List<StreamResolution> = entries.filter { it.aspect == shape }
    }
}

// Stream configuration for a single camera
data class StreamConfig(
    val cameraId: String = "",
    val facing: CameraFacing = CameraFacing.BACK,
    /** Shape of this camera's recording (and preview): 4:3 or 16:9. */
    val aspect: AspectRatio = AspectRatio.RATIO_4_3,
    val resolution: StreamResolution = StreamResolution.R1280X960,
    /** Frames per second; the spinners offer only the rates the selected camera can hold. */
    val fps: Int = 30,
    val bitrate: StreamBitrate = StreamBitrate.AUTO,
    val controlMode: ControlMode = ControlMode.AUTO,
    val manualControl: ManualControlState = ManualControlState(),
    val flashEnabled: Boolean = false,
    val captureIntent: CaptureIntent = CaptureIntent.VIDEO,
    val jpegQuality: JpegQuality = JpegQuality.HIGH,
    val streamOrientation: StreamOrientation = StreamOrientation.PORTRAIT
)

// Bitrate options. AUTO is computed from resolution and FPS (see
// MediaCapabilities.autoVideoBitrate); the spinners offer only the values the H.264 encoder
// can deliver. Per camera, so the two files may use different bitrates.
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
    MBPS_40("40 Mbps", 40_000_000),
    MBPS_50("50 Mbps", 50_000_000),
    MBPS_60("60 Mbps", 60_000_000),
    MBPS_80("80 Mbps", 80_000_000),
    MBPS_100("100 Mbps", 100_000_000),
    MBPS_120("120 Mbps", 120_000_000),
    MBPS_150("150 Mbps", 150_000_000),
    MBPS_200("200 Mbps", 200_000_000),
    MBPS_250("250 Mbps", 250_000_000),
    MBPS_300("300 Mbps", 300_000_000),
    MBPS_400("400 Mbps", 400_000_000),
    MBPS_500("500 Mbps", 500_000_000);

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
