package com.dualcamerarecording.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * H.264 encoder fed by a camera through its input [Surface].
 *
 * - Bitrate control is CBR whenever the encoder accepts it (MediaRecorder always used
 *   VBR, which is why recorded files fell short of the selected bitrate), with an
 *   explicit profile + level so the level cap does not clip the bitrate.
 * - Frames before the session origin are dropped; writing starts at the first key frame
 *   after it, so both cameras' files begin at the same instant.
 * - Timestamps are written on an exact 1/fps grid, so the file reports a constant frame
 *   rate equal to the selected one. Some cameras run slightly faster than the requested
 *   rate (about 30.2 fps for 30): the camera is then asked to leave a frame out of the
 *   recording now and then, so the video does not drift away from the audio.
 */
class VideoEncoder(
    val label: String,
    private val spec: Spec,
    private val writer: Mp4Writer,
    private val callback: Callback
) {

    data class Spec(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    interface Callback {
        /** First encoded frame (any, before gating); [sourcePtsUs] is on the monotonic clock. */
        fun onFirstFrame(encoder: VideoEncoder, sourcePtsUs: Long)
        fun onEncoderError(encoder: VideoEncoder, error: Throwable)

        /**
         * The camera delivers more frames than the frame rate: it should leave one frame
         * out of the recording (dropping an encoded frame would break the H.264 stream).
         */
        fun onSurplusFrame(encoder: VideoEncoder)
    }

    private val codec: MediaCodec
    val inputSurface: Surface

    /** Human-readable configuration actually accepted by the encoder (for logs). */
    var configuration: String = ""
        private set

    private val info = MediaCodec.BufferInfo()
    private val drainThread: Thread

    @Volatile
    private var running = true

    /** Frames whose source timestamp is below this are dropped (session not started yet). */
    @Volatile
    private var gateUs = Long.MAX_VALUE

    /** Session origin, subtracted from every timestamp. */
    @Volatile
    private var originUs = 0L

    /** Frames whose source timestamp is above this are dropped (common end of the files). */
    @Volatile
    private var endUs = Long.MAX_VALUE

    private var waitingForKeyFrame = true
    private var lastSyncRequestMs = 0L
    private var lastFrameIndex = -1L
    private var lastPtsUs = -1L
    private var firstPtsUs = -1L
    private var shiftedFrames = 0
    private var offGridFrames = 0
    private var skipRequests = 0
    /** [framesWritten] when the pending frame skip was requested, -1 when none is pending. */
    private var skipRequestedAt = -1
    private var sourceOffsetUs = Long.MIN_VALUE
    private var firstSourcePtsUs = -1L
    private var writeErrorReported = false

    var framesWritten = 0
        private set
    private var firstWrittenSourceUs = -1L
    private var lastWrittenSourceUs = -1L

    init {
        codec = createConfiguredCodec()
        inputSurface = codec.createInputSurface()
        codec.start()
        drainThread = Thread({ drainLoop() }, "VideoEncoder-$label").apply { start() }
    }

    private fun createConfiguredCodec(): MediaCodec {
        val name = MediaCapabilities.avcEncoder?.name
        val profileLevel = MediaCapabilities.avcProfileLevel(spec.width, spec.height, spec.fps, spec.bitrate)
        // Most specific configuration first; fall back if the encoder rejects it.
        val attempts = listOfNotNull(
            profileLevel?.let { Triple(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, it, "CBR") },
            Triple(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, null, "CBR"),
            profileLevel?.let { Triple(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, it, "VBR") },
            Triple(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, null, "VBR")
        )
        var lastError: Exception? = null
        for ((mode, pl, modeName) in attempts) {
            val codec = if (name != null) MediaCodec.createByCodecName(name)
                        else MediaCodec.createEncoderByType(MediaCapabilities.VIDEO_MIME)
            val format = MediaFormat.createVideoFormat(MediaCapabilities.VIDEO_MIME, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (pl != null) {
                    setInteger(MediaFormat.KEY_PROFILE, pl.first)
                    setInteger(MediaFormat.KEY_LEVEL, pl.second)
                }
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configuration = "${codec.name} ${spec.width}x${spec.height}@${spec.fps} " +
                    "${spec.bitrate} bps $modeName" +
                    (pl?.let { " profile=${it.first} level=0x${Integer.toHexString(it.second)}" } ?: "")
                Log.d(TAG, "[$label] configured: $configuration")
                return codec
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "[$label] configure $modeName profileLevel=$pl rejected: ${e.message}")
                codec.release()
            }
        }
        throw IllegalStateException("No usable H.264 configuration", lastError)
    }

    /**
     * Start writing frames from [originUs] on (monotonic clock, µs). Requests an
     * immediate key frame so the file starts without waiting for the next GOP.
     */
    fun openGate(originUs: Long) {
        this.originUs = originUs
        this.gateUs = originUs
        requestSyncFrame()
    }

    /** Stop writing frames captured after [endUs] (monotonic clock, µs). */
    fun setEnd(endUs: Long) {
        this.endUs = endUs
    }

    private fun requestSyncFrame() {
        lastSyncRequestMs = SystemClock.elapsedRealtime()
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "[$label] sync frame request failed: ${e.message}")
        }
    }

    /** Signal end of stream and wait for the pending frames to be written. */
    fun stop() {
        try {
            codec.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "[$label] signalEndOfInputStream failed: ${e.message}")
        }
        drainThread.join(STOP_TIMEOUT_MS)
        if (drainThread.isAlive) {
            Log.w(TAG, "[$label] end of stream not received in time")
            running = false
            drainThread.join(500)
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
        Log.d(TAG, "[$label] stopped: frames=$framesWritten measuredFps=${"%.3f".format(measuredFps())} " +
            "shifted=$shiftedFrames offGrid=$offGridFrames skipRequests=$skipRequests")
    }

    /** Average source frame rate of the written frames (diagnostics). */
    fun measuredFps(): Double {
        val span = lastWrittenSourceUs - firstWrittenSourceUs
        return if (framesWritten > 1 && span > 0) (framesWritten - 1) * 1_000_000.0 / span else 0.0
    }

    private fun drainLoop() {
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.addVideoTrack(codec.outputFormat)
                    index >= 0 -> {
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            codec.getOutputBuffer(index)?.let { handleFrame(it) }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (endOfStream) break
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "[$label] drain failed: ${e.message}")
                callback.onEncoderError(this, e)
            }
        }
    }

    private fun handleFrame(buffer: ByteBuffer) {
        if (sourceOffsetUs == Long.MIN_VALUE) {
            // Encoder-surface timestamps are on the monotonic clock (as System.nanoTime);
            // a HAL that breaks this would desync audio, so re-base it once if needed.
            val nowUs = System.nanoTime() / 1000
            sourceOffsetUs = if (abs(nowUs - info.presentationTimeUs) > 5_000_000L) {
                Log.w(TAG, "[$label] frame timestamps are not monotonic-clock based, re-basing")
                nowUs - info.presentationTimeUs
            } else {
                0L
            }
        }
        val sourceUs = info.presentationTimeUs + sourceOffsetUs
        if (firstSourcePtsUs < 0) {
            firstSourcePtsUs = sourceUs
            callback.onFirstFrame(this, sourceUs)
        }
        if (sourceUs < gateUs || sourceUs > endUs) return

        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (waitingForKeyFrame) {
            if (!keyFrame || !writer.isStarted()) {
                if (SystemClock.elapsedRealtime() - lastSyncRequestMs > 500) requestSyncFrame()
                return
            }
            waitingForKeyFrame = false
        }

        val frameUs = 1_000_000.0 / spec.fps
        if (skipRequestedAt >= 0 && sourceUs - lastWrittenSourceUs > frameUs * 1.5) {
            skipRequestedAt = -1 // the requested frame was skipped
        }
        val skipFailed = skipRequestedAt >= 0 && framesWritten - skipRequestedAt > SKIP_TIMEOUT_FRAMES
        if (skipFailed) skipRequestedAt = -1

        // Constant frame rate: each frame takes the slot after the previous one while that
        // slot is within MAX_GRID_OFFSET_FRAMES of the frame's capture time. This absorbs
        // timestamp jitter, the timestamp steps of exposure changes and the skipped frames
        // without leaving holes in the file. A real gap in the camera stream leaves empty
        // slots. When there are more frames than slots and the camera could not skip one
        // (activity in the background), the frame goes between two slots instead, so the
        // video never drifts away from the audio.
        val frameIndex = ((sourceUs - originUs) * spec.fps / 1_000_000.0).roundToLong()
        val nextIndex = lastFrameIndex + 1
        val ptsUs = if (lastPtsUs >= 0 && (skipFailed || frameIndex < nextIndex - MAX_GRID_OFFSET_FRAMES)) {
            offGridFrames++
            (lastPtsUs + slotPtsUs(nextIndex)) / 2
        } else {
            lastFrameIndex = when {
                lastPtsUs < 0 -> frameIndex
                frameIndex > nextIndex + MAX_GRID_OFFSET_FRAMES -> frameIndex - MAX_GRID_OFFSET_FRAMES
                else -> nextIndex
            }
            if (lastFrameIndex != frameIndex) shiftedFrames++
            slotPtsUs(lastFrameIndex)
        }
        lastPtsUs = ptsUs
        if (firstPtsUs < 0) firstPtsUs = ptsUs

        // A camera slightly faster than the frame rate puts every frame a little later on the
        // grid than it was captured. Once the video lags SKIP_LAG_FRACTION of a frame, the
        // camera is asked to leave one frame out; the gap it leaves brings the video back.
        if (skipRequestedAt < 0 && ptsUs - (sourceUs - originUs) > frameUs * SKIP_LAG_FRACTION) {
            skipRequestedAt = framesWritten
            skipRequests++
            callback.onSurplusFrame(this)
        }

        val (sample, size) = padToBitrate(buffer, info.offset, info.size, ptsUs)
        val offset = if (sample === buffer) info.offset else 0
        if (writer.writeVideo(sample, offset, size, ptsUs, info.flags)) {
            if (firstWrittenSourceUs < 0) firstWrittenSourceUs = sourceUs
            lastWrittenSourceUs = sourceUs
            framesWritten++
        } else if (!writeErrorReported) {
            writer.failure?.let {
                writeErrorReported = true
                callback.onEncoderError(this, it)
            }
        }
    }

    // Strict CBR: bytes the encoder produced and filler bytes added so far.
    private var encodedBytes = 0L
    private var fillerBytes = 0L
    private var paddingBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)

    /**
     * Keep the stream at the selected bitrate. On low-detail scenes (a blank wall, a
     * blurred macro image) the hardware encoder spends fewer bits than requested even in
     * CBR mode, so the file bitrate fell below the selected value. When the running total
     * is short of `bitrate × time`, an H.264 filler-data NAL unit (type 12, the standard
     * CBR stuffing, ignored by decoders) is appended after the frame's slices. Time is
     * the span the file covers once this frame (at [ptsUs]) is written, so dropped frames
     * do not lower the bitrate. Returns the buffer to write and its size (the original
     * buffer when no filler is needed).
     */
    private fun padToBitrate(buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long): Pair<ByteBuffer, Int> {
        encodedBytes += size
        val frameBudget = spec.bitrate / 8.0 / spec.fps
        val expected = spec.bitrate / 8.0 * (ptsUs - firstPtsUs) / 1_000_000.0 + frameBudget
        val deficit = (expected - encodedBytes - fillerBytes).toLong()
        if (deficit < MIN_FILLER_BYTES) return buffer to size
        // Spread a large deficit over several frames instead of one huge sample.
        val payload = minOf(deficit, (frameBudget * 2).toLong()).toInt() - FILLER_OVERHEAD
        if (payload <= 0) return buffer to size
        val total = size + FILLER_OVERHEAD + payload
        if (paddingBuffer.capacity() < total) paddingBuffer = ByteBuffer.allocateDirect(total * 2)
        val out = paddingBuffer
        out.clear()
        val src = buffer.duplicate()
        src.position(offset)
        src.limit(offset + size)
        out.put(src)
        out.put(FILLER_START)
        var remaining = payload
        while (remaining > 0) {
            val chunk = minOf(remaining, FILLER_FF.size)
            out.put(FILLER_FF, 0, chunk)
            remaining -= chunk
        }
        out.put(0x80.toByte()) // rbsp_trailing_bits
        out.flip()
        fillerBytes += FILLER_OVERHEAD + payload
        return out to total
    }

    private fun slotPtsUs(frameIndex: Long): Long = (frameIndex * 1_000_000.0 / spec.fps).roundToLong()

    companion object {
        private const val TAG = "VideoEncoder"
        private const val STOP_TIMEOUT_MS = 3000L

        private const val MAX_GRID_OFFSET_FRAMES = 1L
        /**
         * Lag (fraction of a frame) at which a frame skip is requested. Above half a frame
         * (the grid rounding) plus room for the timestamp steps of exposure changes (5 ms
         * measured), so a camera at the nominal rate is not mistaken for a faster one.
         */
        private const val SKIP_LAG_FRACTION = 0.75
        /** Frames to wait for a requested skip (the camera pipeline is a few frames deep). */
        private const val SKIP_TIMEOUT_FRAMES = 15

        /** Annex-B start code + NAL header of a filler-data NAL unit (nal_unit_type 12). */
        private val FILLER_START = byteArrayOf(0, 0, 0, 1, 0x0C)
        private val FILLER_FF = ByteArray(64 * 1024) { 0xFF.toByte() }
        /** Start code + header + trailing byte. */
        private const val FILLER_OVERHEAD = 6
        private const val MIN_FILLER_BYTES = 256L
    }
}
