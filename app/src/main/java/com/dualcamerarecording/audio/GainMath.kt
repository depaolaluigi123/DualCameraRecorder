package com.dualcamerarecording.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * Audio gain math utilities.
 * Adapted from MicGainLevelerApp GainMath.
 */
object GainMath {

    const val FLOOR_DB: Double = -60.0
    const val HEADROOM_DB: Double = 6.0

    /**
     * Convert decibels to linear gain multiplier.
     */
    fun dbToLinear(db: Double): Double {
        return 10.0.pow(db / 20.0)
    }

    /**
     * Convert linear amplitude to decibels.
     */
    fun amplitudeToDb(amplitude: Double): Double {
        if (amplitude <= 0.0) return FLOOR_DB
        return 20.0 * kotlin.math.log10(amplitude / 32768.0)
    }

    /**
     * Apply digital gain to PCM audio samples.
     * Returns the clipped samples.
     */
    fun applyGain(samples: ShortArray, gainDb: Double): ShortArray {
        val linear = dbToLinear(gainDb)
        return samples.map { sample ->
            val amplified = sample * linear
            when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toInt().toShort()
            }
        }.toShortArray()
    }

    /**
     * Apply per-channel gain to stereo PCM audio.
     */
    fun applyGainStereo(samples: ShortArray, leftGainDb: Double, rightGainDb: Double): ShortArray {
        val leftLinear = dbToLinear(leftGainDb)
        val rightLinear = dbToLinear(rightGainDb)
        val result = ShortArray(samples.size)

        var i = 0
        while (i < samples.size - 1) {
            result[i] = clip(samples[i] * leftLinear)
            result[i + 1] = clip(samples[i + 1] * rightLinear)
            i += 2
        }
        return result
    }

    fun clip(value: Double): Short {
        return when {
            value > Short.MAX_VALUE -> Short.MAX_VALUE
            value < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> value.toInt().toShort()
        }
    }

    /**
     * Apply a linear gain to a single sample and return the clipped value as a Short.
     * Used in the capture loop where per-sample array allocation must be avoided.
     */
    fun applyGainSample(sample: Int, linear: Double): Short {
        val v = (sample * linear)
        return when {
            v > Short.MAX_VALUE -> Short.MAX_VALUE
            v < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> v.toInt().toShort()
        }
    }

    /**
     * Convert a 0..32768 linear amplitude to dBFS (clamped at the floor).
     */
    fun amplitudeToDbInt(amplitude: Int): Double {
        if (amplitude <= 0) return FLOOR_DB
        return 20.0 * kotlin.math.log10(amplitude / 32768.0)
    }

    /**
     * Calculate RMS level of audio samples.
     */
    fun calculateRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val d = sample.toDouble()
            sum += d * d
        }
        val rms = kotlin.math.sqrt(sum / samples.size)
        return amplitudeToDb(rms)
    }

    /**
     * Calculate peak level of audio samples.
     */
    fun calculatePeak(samples: ShortArray): Double {
        var peak = 0.0
        for (sample in samples) {
            val abs = kotlin.math.abs(sample.toDouble())
            if (abs > peak) peak = abs
        }
        return amplitudeToDb(peak)
    }
}