package com.dualcamerarecording.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

import kotlin.math.abs
import kotlin.math.max

/**
 * Stereo microphone capture with a dedicated capture thread.
 *
 * Ports the proven MicGainLevelerApp implementation: an empirical fake-stereo probe
 * (STEREO requested, but some devices downmix to identical L/R) with a MONO_DUPLICATED
 * fallback, per-channel digital gain, per-buffer peak + running-max metering, and a
 * blocking [AudioRecord.read] loop on its own thread (NOT a 50 ms Handler poll — the
 * poll was the cause of the jumpy / unreliable meters).
 *
 * Public API preserved for callers (DualCameraRecorderApp, RecordingService):
 *  - initialize(channelMode, sampleRate, bufferSizeSeconds)
 *  - startCapture() / stopCapture() / release()
 *  - setMonitorTap(MonitorTap)  — pushed per buffer, left/right level + running peak
 *  - setLeftGain / setRightGain (dB)
 *  - setMuted
 *  - addAudioSink(AudioSink)
 *  - isCapturing
 *  - ChannelMode, AudioSink, MonitorTap
 */
class MicCapture {

    enum class ChannelMode { STEREO, MONO_DUPLICATED }

    interface AudioSink {
        fun onAudioData(samples: ShortArray, numChannels: Int, sampleRate: Int, isMono: Boolean)
    }

    interface MonitorTap {
        fun onAudioLevels(leftDb: Double, rightDb: Double, peakLeft: Double, peakRight: Double)
    }

    @Volatile
    var channelMode: ChannelMode = ChannelMode.STEREO
        private set

    val isCapturing = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    private var sampleRate: Int = 44100
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    @Volatile private var leftGainDb: Double = 0.0
    @Volatile private var rightGainDb: Double = 0.0

    private val audioSinks = CopyOnWriteArrayList<AudioSink>()
    @Volatile private var monitorTap: MonitorTap? = null

    // Running peak (linear, 0..32768) per channel — the "highest level measured"
    // shown under each meter. Reset on startCapture / stopCapture.
    @Volatile private var runningPeak1 = 0
    @Volatile private var runningPeak2 = 0

    /**
     * Initialize AudioRecord. If [channelMode] is STEREO we still probe whether the
     * device actually delivers two distinct channels; if not, we fall back to mono
     * (duplicated to stereo on the way out) so the meters always reflect a real signal.
     */
    fun initialize(
        channelMode: ChannelMode = ChannelMode.STEREO,
        sampleRate: Int = 44100,
        bufferSizeSeconds: Double = 2.0
    ): Boolean {
        this.sampleRate = sampleRate
        val requested = if (channelMode == ChannelMode.STEREO) ChannelMode.STEREO else ChannelMode.MONO_DUPLICATED
        val resolved = discoverAndInit(requested)
        if (resolved == null) {
            Log.e(TAG, "AudioRecord init failed for requested mode $channelMode")
            return false
        }
        this.channelMode = resolved
        return true
    }

    private fun discoverAndInit(requested: ChannelMode): ChannelMode? {
        val stereoCfg = AudioFormat.CHANNEL_IN_STEREO
        val monoCfg = AudioFormat.CHANNEL_IN_MONO
        if (requested == ChannelMode.STEREO) {
            if (tryInit(stereoCfg)) {
                if (probeStereoIsReal()) return ChannelMode.STEREO
                // Fake stereo (identical L/R) — release and fall back to real mono.
                releaseCurrent()
            }
        }
        return if (tryInit(monoCfg)) ChannelMode.MONO_DUPLICATED else null
    }

    private fun tryInit(channels: Int): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize(channels=$channels)=$minBuf")
            return false
        }
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channels,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                false
            } else {
                audioRecord = rec
                rec.startRecording()
                true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No microphone permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed for channels=$channels: ${e.message}")
            false
        }
    }

    private fun releaseCurrent() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    /**
     * Empirical probe: read a stereo buffer and compare the L and R samples.
     * If they are identical (downmix), ratio ~ 0 and we treat the device as mono.
     */
    private fun probeStereoIsReal(): Boolean {
        val rec = audioRecord ?: return false
        val probeFrames = 2048
        val buf = ShortArray(probeFrames * 2)
        readForProbe(rec, buf) // warm-up read
        val n = readForProbe(rec, buf)
        var total = 0
        var diff = 0L
        var sum = 0L
        for (i in 0 until n step 2) {
            val l = abs(buf[i].toInt())
            val r = abs(buf[i + 1].toInt())
            diff += abs(l - r)
            sum += max(l, r)
            total++
        }
        if (total == 0 || sum == 0L) return true // can't tell — assume stereo exists
        val ratio = diff.toDouble() / sum.toDouble()
        Log.d(TAG, "stereo probe ratio=$ratio (frames=$total)")
        return ratio > 1e-3
    }

    private fun readForProbe(rec: AudioRecord, buf: ShortArray): Int =
        try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { 0 }

    fun startCapture(): Boolean {
        if (isCapturing.getAndSet(true)) return true
        val rec = audioRecord
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            isCapturing.set(false)
            return false
        }
        runningPeak1 = 0
        runningPeak2 = 0
        running = true
        thread = Thread({ captureLoop() }, "mic-capture").apply { isDaemon = true; start() }
        Log.d(TAG, "Audio capture started (mode=$channelMode)")
        return true
    }

    fun stopCapture() {
        if (!isCapturing.getAndSet(false)) return
        running = false
        thread?.join(1500)
        thread = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        runningPeak1 = 0
        runningPeak2 = 0
        Log.d(TAG, "Audio capture stopped")
    }

    private fun captureLoop() {
        val rec = audioRecord ?: return
        val channels = when (channelMode) {
            ChannelMode.STEREO -> 2
            ChannelMode.MONO_DUPLICATED -> 1
        }
        val chunkFrames = 1024
        val chunkShorts = chunkFrames * channels
        val buf = ShortArray(chunkShorts)
        // For mono-duplicated we expand mono → stereo into a separate buffer.
        val stereoOut = ShortArray(chunkFrames * 2)

        while (running) {
            val read = try { rec.read(buf, 0, chunkShorts) } catch (_: Throwable) { -1 }
            if (read <= 0) continue
            val frames = read / channels
            if (frames <= 0) continue

            val lin1 = GainMath.dbToLinear(leftGainDb)
            val lin2 = GainMath.dbToLinear(rightGainDb)
            val muted = isMuted.get()

            var peak1 = 0
            var peak2 = 0

            if (muted) {
                // No signal reaches the sinks or the meters.
                java.util.Arrays.fill(buf, 0, read, 0)
                dispatchSinks(buf, frames, 2, channelMode == ChannelMode.MONO_DUPLICATED)
            } else if (channelMode == ChannelMode.STEREO) {
                // The OS mic delivers (right, left) interleaved on this device,
                // not the (left, right) the convention assumes. Treat slot 1
                // as the actual left mic and slot 0 as the actual right mic,
                // apply each gain to its actual channel, and write the result
                // back into the (L, R) slots the OS / MediaRecorder expect —
                // so the leftmost meter (state.leftLevel) tracks the actual
                // left mic and any dispatched buffer carries the audio in
                // canonical LRLR order.
                for (i in 0 until read step 2) {
                    val actualRight = buf[i].toInt()
                    val actualLeft = buf[i + 1].toInt()
                    val processedLeft = GainMath.applyGainSample(actualLeft, lin1)
                    val processedRight = GainMath.applyGainSample(actualRight, lin2)
                    buf[i] = processedLeft       // OS slot 0 (file L) = processed actual left
                    buf[i + 1] = processedRight  // OS slot 1 (file R) = processed actual right
                    val al = abs(processedLeft.toInt())
                    val ar = abs(processedRight.toInt())
                    if (al > peak1) peak1 = al
                    if (ar > peak2) peak2 = ar
                }
                dispatchSinks(buf, frames, 2, false)
            } else {
                for (i in 0 until frames) {
                    val mono = buf[i].toInt()
                    val l = GainMath.applyGainSample(mono, lin1)
                    val r = GainMath.applyGainSample(mono, lin2)
                    stereoOut[i * 2] = l
                    stereoOut[i * 2 + 1] = r
                    val al = abs(l.toInt())
                    val ar = abs(r.toInt())
                    if (al > peak1) peak1 = al
                    if (ar > peak2) peak2 = ar
                }
                dispatchSinks(stereoOut, frames, 2, true)
            }

            if (peak1 > runningPeak1) runningPeak1 = peak1
            if (peak2 > runningPeak2) runningPeak2 = peak2

            val leftDb = GainMath.amplitudeToDb(peak1.toDouble())
            val rightDb = GainMath.amplitudeToDb(peak2.toDouble())
            val runDb1 = GainMath.amplitudeToDb(runningPeak1.toDouble())
            val runDb2 = GainMath.amplitudeToDb(runningPeak2.toDouble())
            monitorTap?.onAudioLevels(leftDb, rightDb, runDb1, runDb2)
        }
    }

    private fun dispatchSinks(samples: ShortArray, frames: Int, channels: Int, isMono: Boolean) {
        if (audioSinks.isEmpty()) return
        for (sink in audioSinks) {
            try { sink.onAudioData(samples, channels, sampleRate, isMono) } catch (_: Throwable) {}
        }
    }

    fun setLeftGain(gainDb: Double) { leftGainDb = gainDb }
    fun setRightGain(gainDb: Double) { rightGainDb = gainDb }

    fun setMuted(muted: Boolean) { isMuted.set(muted) }

    fun addAudioSink(sink: AudioSink) { audioSinks.addIfAbsent(sink) }
    fun removeAudioSink(sink: AudioSink) { audioSinks.remove(sink) }

    fun setMonitorTap(tap: MonitorTap?) { monitorTap = tap }

    fun release() {
        stopCapture()
        releaseCurrent()
    }

    companion object {
        private const val TAG = "MicCapture"
    }
}
