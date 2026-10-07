package com.dualcamerarecording.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.dualcamerarecording.audio.MicCapture
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AAC-LC encoder fed by the shared [MicCapture] (the same capture that drives the level
 * meters). The encoded stream is written to every [Mp4Writer] in [writers], so the front
 * and rear files carry the identical, sample-accurate audio track.
 *
 * The PCM arrives on the microphone thread; it is copied into a queue and encoded on a
 * dedicated thread so the capture loop never blocks on the codec.
 */
class AudioEncoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    bitrate: Int,
    private val writers: List<Mp4Writer>,
    private val onError: (Throwable) -> Unit
) : MicCapture.AudioSink {

    private class Chunk(val pcm: ShortArray, val frames: Int, val ptsUs: Long)

    private val codec: MediaCodec
    private val queue = ArrayBlockingQueue<Chunk>(QUEUE_CAPACITY)
    private val thread: Thread
    private val info = MediaCodec.BufferInfo()

    @Volatile
    private var accepting = true
    private val droppedChunks = AtomicInteger()
    private var mismatchLogged = false
    private var lastInputPtsUs = -1L

    var encodedFrames = 0
        private set

    init {
        val name = MediaCapabilities.aacEncoder?.name
        codec = if (name != null) MediaCodec.createByCodecName(name)
                else MediaCodec.createEncoderByType(MediaCapabilities.AUDIO_MIME)
        val format = MediaFormat.createAudioFormat(MediaCapabilities.AUDIO_MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        Log.d(TAG, "configured ${codec.name}: AAC-LC ${channelCount}ch $sampleRate Hz $bitrate bps")
        thread = Thread({ encodeLoop() }, "AudioEncoder").apply { start() }
    }

    override fun onAudioData(
        samples: ShortArray,
        frames: Int,
        numChannels: Int,
        sampleRate: Int,
        isMono: Boolean,
        ptsNs: Long
    ) {
        if (!accepting || frames <= 0) return
        if (numChannels != channelCount || sampleRate != this.sampleRate) {
            if (!mismatchLogged) {
                mismatchLogged = true
                Log.e(TAG, "PCM format ${numChannels}ch $sampleRate Hz does not match encoder " +
                    "${channelCount}ch ${this.sampleRate} Hz — audio ignored")
            }
            return
        }
        val chunk = Chunk(samples.copyOf(frames * numChannels), frames, ptsNs / 1000)
        if (!queue.offer(chunk)) droppedChunks.incrementAndGet()
    }

    /** Stop accepting PCM, flush the encoder and wait for the last frames to be written. */
    fun stop() {
        accepting = false
        queue.offer(END_OF_STREAM, 1, TimeUnit.SECONDS)
        thread.join(STOP_TIMEOUT_MS)
        if (thread.isAlive) {
            Log.w(TAG, "encoder thread did not finish in time")
            thread.interrupt()
            thread.join(500)
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        Log.d(TAG, "stopped: encodedFrames=$encodedFrames droppedChunks=${droppedChunks.get()}")
    }

    private fun encodeLoop() {
        var current: Chunk? = null
        var offsetFrames = 0
        var endQueued = false
        try {
            while (true) {
                if (current == null && !endQueued) {
                    current = queue.poll(10, TimeUnit.MILLISECONDS)
                    offsetFrames = 0
                }
                if (current === END_OF_STREAM) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, lastInputPtsUs.coerceAtLeast(0) + 1,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        endQueued = true
                        current = null
                    }
                } else if (current != null) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        offsetFrames += queueChunk(index, current, offsetFrames)
                        if (offsetFrames >= current.frames) current = null
                    }
                }
                if (drainOutput(if (endQueued) 10_000 else 0)) break
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "encoder thread interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "encoding failed: ${e.message}")
            onError(e)
        }
    }

    /** Copy as many frames of [chunk] as fit into input buffer [index]; returns the count. */
    private fun queueChunk(index: Int, chunk: Chunk, offsetFrames: Int): Int {
        val buffer = codec.getInputBuffer(index) ?: return 0
        buffer.clear()
        val bytesPerFrame = 2 * channelCount
        val frames = minOf(buffer.remaining() / bytesPerFrame, chunk.frames - offsetFrames)
        buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(chunk.pcm, offsetFrames * channelCount, frames * channelCount)
        var ptsUs = chunk.ptsUs + offsetFrames * 1_000_000L / sampleRate
        if (ptsUs <= lastInputPtsUs) ptsUs = lastInputPtsUs + 1
        lastInputPtsUs = ptsUs
        codec.queueInputBuffer(index, 0, frames * bytesPerFrame, ptsUs, 0)
        return frames
    }

    /** Write every available output frame to the writers. Returns true at end of stream. */
    private fun drainOutput(timeoutUs: Long): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    writers.forEach { it.addAudioTrack(format) }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && !isConfig && info.size > 0) {
                        for (writer in writers) {
                            writer.writeAudio(buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
                        }
                        encodedFrames++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    companion object {
        private const val TAG = "AudioEncoder"

        /** ~5 s of 1024-frame chunks at 48 kHz. */
        private const val QUEUE_CAPACITY = 256
        private const val STOP_TIMEOUT_MS = 3000L
        private val END_OF_STREAM = Chunk(ShortArray(0), 0, 0)
    }
}
