package com.dualcamerarecording.recording

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dualcamerarecording.audio.MicCapture
import java.io.File

/**
 * One dual recording: two H.264 encoders (front / rear) and one shared AAC encoder,
 * writing `front.mp4` and `rear.mp4` in [outputDir].
 *
 * Lifecycle:
 *  1. Construct: encoders are configured and their input surfaces exist, so the camera
 *     sessions can be created with them. The AAC encoder subscribes to [micCapture].
 *  2. The cameras start streaming. When every expected camera has produced its first
 *     frame (or after [START_TIMEOUT_MS]) a common origin T0 is chosen and both encoders
 *     start writing from the first key frame after T0: the two files start together.
 *  3. [stop] flushes everything and finalizes the files.
 */
class DualRecordingSession(
    outputDir: File,
    frontSpec: VideoEncoder.Spec?,
    rearSpec: VideoEncoder.Spec?,
    frontOrientationHint: Int,
    rearOrientationHint: Int,
    audioSpec: AudioSpec?,
    private val micCapture: MicCapture?,
    private val listener: Listener
) : VideoEncoder.Callback {

    data class AudioSpec(val sampleRate: Int, val channels: Int, val bitrate: Int)

    data class Result(
        val frontFile: File?,
        val rearFile: File?,
        val summary: String
    )

    interface Listener {
        /** The common start instant was decided; [front] / [rear] tell which cameras are recorded. */
        fun onRecordingStreaming(front: Boolean, rear: Boolean)

        /** No camera delivered frames, or a file could not be written. Called once. */
        fun onRecordingFailure(message: String, error: Throwable?)

        /** The camera of this side runs faster than the frame rate: skip one recorded frame. */
        fun onSurplusFrame(isFront: Boolean)
    }

    val frontFile = File(outputDir, "front.mp4")
    val rearFile = File(outputDir, "rear.mp4")

    private val lock = Any()
    private val frontWriter: Mp4Writer?
    private val rearWriter: Mp4Writer?
    private val frontEncoder: VideoEncoder?
    private val rearEncoder: VideoEncoder?
    private val audioEncoder: AudioEncoder?
    private val writers: List<Mp4Writer>

    private val firstFrames = HashMap<VideoEncoder, Long>()
    private val failedEncoders = HashSet<VideoEncoder>()
    private var originUs = Long.MIN_VALUE
    private var failureReported = false
    private val startedAtMs = SystemClock.elapsedRealtime()

    @Volatile
    private var stopped = false
    private val watchdog: Thread

    val frontSurface: Surface? get() = frontEncoder?.inputSurface
    val rearSurface: Surface? get() = rearEncoder?.inputSurface
    val hasAudio: Boolean get() = audioEncoder != null

    init {
        val wantAudio = audioSpec != null && micCapture != null
        frontWriter = frontSpec?.let { Mp4Writer(frontFile, frontOrientationHint, wantAudio) }
        rearWriter = rearSpec?.let { Mp4Writer(rearFile, rearOrientationHint, wantAudio) }
        writers = listOfNotNull(frontWriter, rearWriter)

        audioEncoder = if (wantAudio) {
            try {
                AudioEncoder(audioSpec!!.sampleRate, audioSpec.channels, audioSpec.bitrate, writers) { e ->
                    Log.e(TAG, "audio encoder failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AAC encoder unavailable, recording without audio: ${e.message}")
                writers.forEach { it.disableAudio() }
                null
            }
        } else {
            null
        }

        frontEncoder = frontSpec?.let { createEncoder("front", it, frontWriter!!) }
        rearEncoder = rearSpec?.let { createEncoder("rear", it, rearWriter!!) }
        if (frontEncoder == null && rearEncoder == null) {
            audioEncoder?.stop()
            writers.forEach { it.finish() }
            throw IllegalStateException("No video encoder could be created")
        }

        audioEncoder?.let { micCapture?.addAudioSink(it) }
        watchdog = Thread({ watchdogLoop() }, "RecordingWatchdog").apply { start() }
    }

    private fun createEncoder(label: String, spec: VideoEncoder.Spec, writer: Mp4Writer): VideoEncoder? =
        try {
            VideoEncoder(label, spec, writer, this)
        } catch (e: Exception) {
            Log.e(TAG, "[$label] video encoder unavailable: ${e.message}")
            writer.finish()
            null
        }

    /** The camera feeding this side failed: do not wait for its frames before starting. */
    fun markCameraFailed(isFront: Boolean) {
        val encoder = (if (isFront) frontEncoder else rearEncoder) ?: return
        synchronized(lock) {
            failedEncoders += encoder
            maybeDecideOriginLocked(force = false)
        }
    }

    override fun onFirstFrame(encoder: VideoEncoder, sourcePtsUs: Long) {
        Log.d(TAG, "[${encoder.label}] first frame at $sourcePtsUs µs " +
            "(${SystemClock.elapsedRealtime() - startedAtMs} ms after start)")
        synchronized(lock) {
            firstFrames[encoder] = sourcePtsUs
            maybeDecideOriginLocked(force = false)
        }
    }

    override fun onEncoderError(encoder: VideoEncoder, error: Throwable) {
        reportFailure("Encoder ${encoder.label}: ${error.message}", error)
    }

    override fun onSurplusFrame(encoder: VideoEncoder) {
        listener.onSurplusFrame(encoder === frontEncoder)
    }

    private fun expectedEncoders(): List<VideoEncoder> =
        listOfNotNull(frontEncoder, rearEncoder).filter { it !in failedEncoders }

    private fun maybeDecideOriginLocked(force: Boolean) {
        if (originUs != Long.MIN_VALUE || stopped) return
        val expected = expectedEncoders()
        val streaming = expected.filter { it in firstFrames }
        if (streaming.isEmpty()) return
        if (!force && streaming.size < expected.size) return
        originUs = streaming.maxOf { firstFrames.getValue(it) }
        writers.forEach { it.setOrigin(originUs) }
        streaming.forEach { it.openGate(originUs) }
        val front = frontEncoder != null && frontEncoder in streaming
        val rear = rearEncoder != null && rearEncoder in streaming
        Log.d(TAG, "origin decided at $originUs µs: front=$front rear=$rear")
        listener.onRecordingStreaming(front, rear)
    }

    private fun watchdogLoop() {
        try {
            while (!stopped) {
                Thread.sleep(100)
                val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                var noFrames = false
                synchronized(lock) {
                    if (originUs == Long.MIN_VALUE && elapsed > START_TIMEOUT_MS) {
                        if (firstFrames.isNotEmpty()) {
                            maybeDecideOriginLocked(force = true)
                        } else if (elapsed > START_TIMEOUT_MS + 2000) {
                            noFrames = true
                        }
                    }
                }
                if (noFrames) {
                    reportFailure("No camera delivered frames", null)
                    return
                }
                // The muxer waits for the AAC format; never let a stalled microphone
                // block the video.
                if (elapsed > AUDIO_FORMAT_TIMEOUT_MS) {
                    writers.filter { !it.isStarted() && !it.hasAudioTrack() }.forEach { it.disableAudio() }
                }
                if (originUs != Long.MIN_VALUE && writers.all { it.isStarted() }) return
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun reportFailure(message: String, error: Throwable?) {
        synchronized(lock) {
            if (failureReported) return
            failureReported = true
        }
        Log.e(TAG, "recording failure: $message")
        listener.onRecordingFailure(message, error)
    }

    /**
     * Fix the common end of both files at "now": frames and audio captured later are not
     * written, so the two files end together even though the cameras stop one after the
     * other. Call it when the user presses Stop, before stopping the cameras.
     */
    fun markEnd() {
        val endUs = System.nanoTime() / 1000
        frontEncoder?.setEnd(endUs)
        rearEncoder?.setEnd(endUs)
        writers.forEach { it.setEnd(endUs) }
    }

    /**
     * Finish the recording. The cameras must already have stopped sending frames to
     * [frontSurface] / [rearSurface]. Blocks until the files are finalized.
     */
    fun stop(): Result {
        stopped = true
        watchdog.interrupt()
        audioEncoder?.let { micCapture?.removeAudioSink(it) }
        frontEncoder?.stop()
        rearEncoder?.stop()
        audioEncoder?.stop()
        val frontOk = frontWriter?.finish() == true
        val rearOk = rearWriter?.finish() == true
        val summary = buildString {
            frontWriter?.let { append(describe("front", it, frontEncoder)) }
            rearWriter?.let { append(describe("rear", it, rearEncoder)) }
        }
        Log.d(TAG, "recording finished:\n$summary")
        return Result(
            frontFile = if (frontOk) frontFile else null,
            rearFile = if (rearOk) rearFile else null,
            summary = summary
        )
    }

    private fun describe(label: String, writer: Mp4Writer, encoder: VideoEncoder?): String {
        val seconds = writer.lastVideoPtsUs / 1_000_000.0
        val fps = encoder?.let { (it.measuredFps().takeIf { f -> f > 0 } ?: 30.0) } ?: 30.0
        val videoSeconds = (writer.lastVideoPtsUs - writer.firstWrittenVideoPtsUs) / 1_000_000.0 + 1.0 / fps
        val videoKbps = if (writer.videoFrames > 0) writer.videoBytes * 8 / videoSeconds / 1000 else 0.0
        val audioKbps = if (seconds > 0) writer.audioBytes * 8 / seconds / 1000 else 0.0
        return "  $label: frames=${writer.videoFrames} duration=${"%.2f".format(seconds)}s " +
            "video≈${"%.0f".format(videoKbps)} kbps audioFrames=${writer.audioFrames} " +
            "audio≈${"%.1f".format(audioKbps)} kbps sourceFps=${"%.3f".format(encoder?.measuredFps() ?: 0.0)} " +
            "[${encoder?.configuration}]\n"
    }

    companion object {
        private const val TAG = "DualRecordingSession"

        /** Max wait for the second camera before recording with the first one only. */
        private const val START_TIMEOUT_MS = 6000L

        /** Max wait for the AAC format before the muxers start video-only. */
        private const val AUDIO_FORMAT_TIMEOUT_MS = 3000L
    }
}
