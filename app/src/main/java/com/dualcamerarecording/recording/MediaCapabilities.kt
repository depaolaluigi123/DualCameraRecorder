package com.dualcamerarecording.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Range

/**
 * Read-only view of the device's H.264 and AAC encoders.
 *
 * The UI uses it to offer only bitrates / sample rates / resolutions the encoders can
 * really produce, and [VideoEncoder] / [AudioEncoder] use it to pick the codec and an
 * explicit H.264 profile + level (the level caps the bitrate the encoder will accept, so
 * leaving it to the encoder default was one of the reasons the recorded bitrate did not
 * match the selected one).
 */
object MediaCapabilities {

    private const val TAG = "MediaCapabilities"

    const val VIDEO_MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC
    const val AUDIO_MIME: String = MediaFormat.MIMETYPE_AUDIO_AAC

    /**
     * AAC-LC carries at most 6144 bits per channel per 1024-sample frame, i.e. 6 bits per
     * sample per channel. Above that the encoder silently lowers the bitrate.
     */
    private const val AAC_MAX_BITS_PER_SAMPLE_PER_CHANNEL = 6

    /** Hardware H.264 encoder when available (software fallback otherwise). */
    val avcEncoder: MediaCodecInfo? by lazy { findEncoder(VIDEO_MIME, preferHardware = true) }

    /** AAC encoder (on most devices the platform software encoder). */
    val aacEncoder: MediaCodecInfo? by lazy { findEncoder(AUDIO_MIME, preferHardware = false) }

    private fun findEncoder(mime: String, preferHardware: Boolean): MediaCodecInfo? {
        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodecList unavailable: ${e.message}")
            return null
        }
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val picked = if (preferHardware) {
            candidates.firstOrNull { !isSoftwareCodec(it) } ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }
        Log.d(TAG, "Encoder for $mime: ${picked?.name} (candidates=${candidates.map { it.name }})")
        return picked
    }

    private fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.")
    }

    private fun videoCaps(): MediaCodecInfo.VideoCapabilities? = try {
        avcEncoder?.getCapabilitiesForType(VIDEO_MIME)?.videoCapabilities
    } catch (e: Exception) {
        null
    }

    private fun audioCaps(): MediaCodecInfo.AudioCapabilities? = try {
        aacEncoder?.getCapabilitiesForType(AUDIO_MIME)?.audioCapabilities
    } catch (e: Exception) {
        null
    }

    /**
     * Highest video bitrate (bps) the H.264 encoder can deliver: the top of its bitrate range,
     * capped by the bitrate limit of the highest profile + level it supports. Null when unknown.
     */
    fun maxVideoBitrate(): Int? = listOfNotNull(videoBitrateRange()?.upper, maxLevelBitrate()).minOrNull()

    private fun maxLevelBitrate(): Int? {
        val caps = try {
            avcEncoder?.getCapabilitiesForType(VIDEO_MIME)
        } catch (e: Exception) {
            null
        } ?: return null
        return caps.profileLevels.mapNotNull { pl ->
            val factor = PROFILE_BITRATE_FACTORS[pl.profile] ?: return@mapNotNull null
            val level = AVC_LEVELS.lastOrNull { it.constant <= pl.level } ?: return@mapNotNull null
            (level.maxBrKbps * 1000 * factor).toLong()
        }.maxOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    /** Bitrate range of the H.264 encoder in bps, or null when unknown. */
    fun videoBitrateRange(): Range<Int>? = videoCaps()?.bitrateRange

    /** True when the H.264 encoder can encode [width]x[height] at [fps]. Unknown = true. */
    fun isVideoSupported(width: Int, height: Int, fps: Int): Boolean {
        val caps = videoCaps() ?: return true
        return try {
            caps.areSizeAndRateSupported(width, height, fps.toDouble())
        } catch (e: Exception) {
            true
        }
    }

    /** True when the AAC encoder accepts [sampleRate]. Unknown = true. */
    fun isAacSampleRateSupported(sampleRate: Int): Boolean {
        val caps = audioCaps() ?: return true
        return caps.isSampleRateSupported(sampleRate)
    }

    /**
     * Highest AAC-LC bitrate (bps) that the encoder really produces for the given
     * sample rate and channel count.
     */
    fun aacMaxBitrate(sampleRate: Int, channels: Int): Int {
        val physical = AAC_MAX_BITS_PER_SAMPLE_PER_CHANNEL * sampleRate * channels
        val encoderMax = audioCaps()?.bitrateRange?.upper ?: physical
        return minOf(physical, encoderMax)
    }

    /** Lowest AAC bitrate (bps) the encoder accepts. */
    fun aacMinBitrate(): Int = audioCaps()?.bitrateRange?.lower ?: 8_000

    /**
     * Default "Auto" video bitrate: 0.2 bits per pixel, a common quality target for
     * H.264 camera footage (≈7.4 Mbps for 1280x960 @ 30 fps), clamped to the encoder range.
     */
    fun autoVideoBitrate(width: Int, height: Int, fps: Int): Int {
        val bps = (width.toLong() * height * fps * 0.2).toLong().coerceIn(1_000_000L, Int.MAX_VALUE.toLong()).toInt()
        val range = videoBitrateRange() ?: return bps
        return bps.coerceIn(range.lower, range.upper)
    }

    // ---------------------------------------------------------------------------------
    // H.264 profile / level
    // ---------------------------------------------------------------------------------

    /** H.264 Table A-1: max macroblocks/s, max frame size (MBs), max bitrate (kbps, Main). */
    private class AvcLevel(val constant: Int, val maxMbps: Long, val maxFs: Int, val maxBrKbps: Long)

    private val AVC_LEVELS = listOf(
        AvcLevel(CodecProfileLevel.AVCLevel3, 40_500, 1_620, 10_000),
        AvcLevel(CodecProfileLevel.AVCLevel31, 108_000, 3_600, 14_000),
        AvcLevel(CodecProfileLevel.AVCLevel32, 216_000, 5_120, 20_000),
        AvcLevel(CodecProfileLevel.AVCLevel4, 245_760, 8_192, 20_000),
        AvcLevel(CodecProfileLevel.AVCLevel41, 245_760, 8_192, 50_000),
        AvcLevel(CodecProfileLevel.AVCLevel42, 522_240, 8_704, 50_000),
        AvcLevel(CodecProfileLevel.AVCLevel5, 589_824, 22_080, 135_000),
        AvcLevel(CodecProfileLevel.AVCLevel51, 983_040, 36_864, 240_000),
        AvcLevel(CodecProfileLevel.AVCLevel52, 2_073_600, 36_864, 240_000),
        // Levels 6.x (CodecProfileLevel.AVCLevel6 / 61 / 62, API 29+ constants).
        AvcLevel(0x20000, 4_177_920, 139_264, 240_000),
        AvcLevel(0x40000, 8_355_840, 139_264, 480_000),
        AvcLevel(0x80000, 16_711_680, 139_264, 800_000)
    )

    /** Level bitrate multiplier per profile: High allows 1.25x the Main-profile bitrate. */
    private val PROFILE_BITRATE_FACTORS = mapOf(
        CodecProfileLevel.AVCProfileHigh to 1.25,
        CodecProfileLevel.AVCProfileMain to 1.0,
        CodecProfileLevel.AVCProfileBaseline to 1.0
    )

    /**
     * Pick (profile, level) for the given stream: High profile when supported (Main /
     * Baseline otherwise) and the smallest level whose limits cover the frame size, the
     * macroblock rate and the bitrate. The level's bitrate cap is what makes the encoder
     * honour high bitrates, so it must be explicit. Returns null when the encoder does not
     * report profile/level information.
     */
    fun avcProfileLevel(width: Int, height: Int, fps: Int, bitrate: Int): Pair<Int, Int>? {
        val caps = try {
            avcEncoder?.getCapabilitiesForType(VIDEO_MIME)
        } catch (e: Exception) {
            null
        } ?: return null
        val frameMbs = ((width + 15) / 16) * ((height + 15) / 16)
        val mbps = frameMbs.toLong() * fps
        for ((profile, brFactor) in PROFILE_BITRATE_FACTORS) {
            val maxSupported = caps.profileLevels.filter { it.profile == profile }.maxOfOrNull { it.level }
                ?: continue
            val needed = AVC_LEVELS.firstOrNull { level ->
                level.maxFs >= frameMbs && level.maxMbps >= mbps &&
                    level.maxBrKbps * 1000 * brFactor >= bitrate
            }
            val level = if (needed != null && needed.constant <= maxSupported) needed.constant else maxSupported
            return profile to level
        }
        return null
    }
}
