package com.dualcamerarecording.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * One MP4 output file (H.264 video + optional AAC audio) written through [MediaMuxer].
 *
 * - The muxer starts once the video format and (when audio is expected) the audio
 *   format are known.
 * - Video samples arrive with timestamps already relative to the session origin.
 * - Audio samples arrive with absolute timestamps (monotonic clock, µs); they are
 *   converted with the session origin and written from the origin on, so the front and
 *   rear files share the same timeline (identical audio from the same instant). When a
 *   camera's first key frame comes a few frames after the origin, the MP4 records that
 *   offset for the video track (edit list) instead of shifting the audio. Audio that
 *   arrives before the muxer can start is kept in a small queue.
 *
 * All methods are thread-safe: the video encoder thread and the audio encoder thread
 * write concurrently.
 */
class Mp4Writer(val file: File, orientationHint: Int, expectAudio: Boolean) {

    private val lock = Any()
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientationHint)
    }
    private val sampleInfo = MediaCodec.BufferInfo()

    private var videoTrack = -1
    private var audioTrack = -1
    private var audioDisabled = !expectAudio
    private var started = false
    private var finished = false

    /** Session origin (µs, monotonic clock); audio timestamps are made relative to it. */
    private var originUs = Long.MIN_VALUE

    /** Common end of the recording (µs, monotonic clock): later audio is dropped. */
    private var endUs = Long.MAX_VALUE
    private var firstVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    private class PendingAudio(val data: ByteBuffer, val absPtsUs: Long, val flags: Int)

    private val pendingAudio = ArrayDeque<PendingAudio>()

    /** First write failure (e.g. storage full). Once set, nothing else is written. */
    @Volatile
    var failure: Throwable? = null
        private set

    var videoFrames = 0
        private set
    var audioFrames = 0
        private set
    var videoBytes = 0L
        private set
    var audioBytes = 0L
        private set
    var lastVideoPtsUs = 0L
        private set

    val firstWrittenVideoPtsUs: Long
        get() = synchronized(lock) { firstVideoPtsUs }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun hasAudioTrack(): Boolean = synchronized(lock) { audioTrack >= 0 }

    fun setOrigin(originUs: Long) = synchronized(lock) {
        this.originUs = originUs
    }

    fun setEnd(endUs: Long) = synchronized(lock) {
        this.endUs = endUs
    }

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        if (videoTrack >= 0 || started || finished) return
        videoTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        if (audioTrack >= 0 || audioDisabled || started || finished) return
        audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    /** Give up on the audio track (no audio format arrived in time): start video-only. */
    fun disableAudio() = synchronized(lock) {
        if (started || audioDisabled || audioTrack >= 0) return
        audioDisabled = true
        pendingAudio.clear()
        Log.w(TAG, "${file.name}: audio disabled, writing video only")
        maybeStart()
    }

    private fun maybeStart() {
        if (started || videoTrack < 0) return
        if (!audioDisabled && audioTrack < 0) return
        muxer.start()
        started = true
        Log.d(TAG, "${file.name}: muxer started (audio=${audioTrack >= 0})")
    }

    /**
     * Write one encoded video frame. [relPtsUs] is relative to the session origin.
     * Returns false when the frame was not written (muxer not started or failed).
     */
    fun writeVideo(buffer: ByteBuffer, offset: Int, size: Int, relPtsUs: Long, flags: Int): Boolean =
        synchronized(lock) {
            if (!started || finished || failure != null) return false
            try {
                sampleInfo.set(offset, size, relPtsUs, flags)
                muxer.writeSampleData(videoTrack, buffer, sampleInfo)
            } catch (e: Exception) {
                failure = e
                Log.e(TAG, "${file.name}: video write failed: ${e.message}")
                return false
            }
            videoFrames++
            videoBytes += size
            lastVideoPtsUs = relPtsUs
            if (firstVideoPtsUs < 0) firstVideoPtsUs = relPtsUs
            true
        }

    /** Write (or queue) one encoded AAC frame with an absolute timestamp. */
    fun writeAudio(buffer: ByteBuffer, offset: Int, size: Int, absPtsUs: Long, flags: Int) =
        synchronized(lock) {
            if (audioDisabled || audioTrack < 0 || finished || failure != null) return
            if (!started || originUs == Long.MIN_VALUE) {
                val copy = ByteBuffer.allocate(size)
                val src = buffer.duplicate()
                src.position(offset)
                src.limit(offset + size)
                copy.put(src)
                copy.flip()
                pendingAudio.addLast(PendingAudio(copy, absPtsUs, flags))
                while (pendingAudio.size > MAX_PENDING_AUDIO) pendingAudio.removeFirst()
                return
            }
            flushPendingAudio()
            writeAudioLocked(buffer, offset, size, absPtsUs, flags)
        }

    private fun flushPendingAudio() {
        while (pendingAudio.isNotEmpty()) {
            val sample = pendingAudio.removeFirst()
            writeAudioLocked(sample.data, 0, sample.data.remaining(), sample.absPtsUs, sample.flags)
        }
    }

    private fun writeAudioLocked(buffer: ByteBuffer, offset: Int, size: Int, absPtsUs: Long, flags: Int) {
        if (absPtsUs > endUs) return
        val relPtsUs = absPtsUs - originUs
        // Audio before the session origin is not part of the recording.
        if (relPtsUs < 0 || relPtsUs <= lastAudioPtsUs) return
        try {
            sampleInfo.set(offset, size, relPtsUs, flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv())
            muxer.writeSampleData(audioTrack, buffer, sampleInfo)
        } catch (e: Exception) {
            failure = e
            Log.e(TAG, "${file.name}: audio write failed: ${e.message}")
            return
        }
        lastAudioPtsUs = relPtsUs
        audioFrames++
        audioBytes += size
    }

    /**
     * Finalize the file. Returns true when it contains video; otherwise the (unplayable)
     * file is deleted and false is returned.
     */
    fun finish(): Boolean = synchronized(lock) {
        if (finished) return videoFrames > 0
        if (started && originUs != Long.MIN_VALUE && failure == null) flushPendingAudio()
        finished = true
        var ok = started && videoFrames > 0
        if (ok) {
            try {
                muxer.stop()
            } catch (e: Exception) {
                Log.e(TAG, "${file.name}: muxer stop failed: ${e.message}")
                ok = false
            }
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
        if (!ok && file.exists()) {
            file.delete()
            Log.w(TAG, "${file.name}: no video written, file deleted")
        }
        ok
    }

    companion object {
        private const val TAG = "Mp4Writer"

        /** ~10 s of AAC frames at 48 kHz. */
        private const val MAX_PENDING_AUDIO = 500
    }
}
