package com.dualcamerarecording.audio

import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * State holder for microphone capture state with Observer pattern.
 * Adapted from MicGainLevelerApp MicStateStore — synchronized updates,
 * @Volatile state, and main-thread dispatch.
 */
data class MicState(
    val gainDb: Double = 0.0,
    val leftLevel: Double = GainMath.FLOOR_DB,   // running dB
    val rightLevel: Double = GainMath.FLOOR_DB,  // running dB
    val leftPeak: Double = GainMath.FLOOR_DB,
    val rightPeak: Double = GainMath.FLOOR_DB,
    val isRecording: Boolean = false,
    val isMuted: Boolean = false,
    val elapsedMs: Long = 0L
) {
    val combinedLevel: Double
        get() = leftLevel.coerceAtLeast(rightLevel)
    val combinedPeak: Double
        get() = leftPeak.coerceAtLeast(rightPeak)
}

fun interface MicObserver {
    fun onStateChanged(state: MicState)
}

class MicStateStore {

    @Volatile
    private var state = MicState()

    private val observers = CopyOnWriteArrayList<MicObserver>()
    private val mainHandler: Handler = HandlerCompat.createAsync(Looper.getMainLooper())

    // --- Observer registration ---
    fun observe(observer: MicObserver): () -> Unit {
        observers.add(observer)
        return { observers.remove(observer) }
    }

    // --- State reads ---
    fun getState(): MicState = state

    // --- State writes (all go through update() for thread safety) ---
    @Synchronized
    fun update(updateFn: (MicState) -> MicState) {
        val prev = state
        val next = updateFn(prev)
        if (next !== prev) {
            state = next
            dispatch(next)
        }
    }

    @Synchronized
    fun updateLiveMeters(
        elapsedMs: Long,
        leftDb: Double,
        rightDb: Double,
        peakDb1: Double = GainMath.FLOOR_DB,
        peakDb2: Double = GainMath.FLOOR_DB
    ) {
        val prev = state
        val newLeftPeak = if (peakDb1 > prev.leftPeak) peakDb1 else {
            val decayed = prev.leftPeak - 2.0
            if (peakDb1 > decayed) peakDb1 else decayed
        }
        val newRightPeak = if (peakDb2 > prev.rightPeak) peakDb2 else {
            val decayed = prev.rightPeak - 2.0
            if (peakDb2 > decayed) peakDb2 else decayed
        }

        val next = prev.copy(
            elapsedMs = elapsedMs,
            leftLevel = leftDb,
            rightLevel = rightDb,
            leftPeak = newLeftPeak.coerceAtLeast(GainMath.FLOOR_DB),
            rightPeak = newRightPeak.coerceAtLeast(GainMath.FLOOR_DB)
        )
        if (next !== prev) {
            state = next
            dispatch(next)
        }
    }

    @Synchronized
    fun setRecording(recording: Boolean) {
        val next = state.copy(
            isRecording = recording,
            leftLevel = if (!recording) GainMath.FLOOR_DB else state.leftLevel,
            rightLevel = if (!recording) GainMath.FLOOR_DB else state.rightLevel,
            leftPeak = if (!recording) GainMath.FLOOR_DB else state.leftPeak,
            rightPeak = if (!recording) GainMath.FLOOR_DB else state.rightPeak,
            elapsedMs = 0L
        )
        if (next !== state) {
            state = next
            dispatch(next)
        }
    }

    @Synchronized
    fun startRecordingFresh(fileName: String) {
        val next = state.copy(
            isRecording = true,
            gainDb = 0.0,
            leftLevel = GainMath.FLOOR_DB,
            rightLevel = GainMath.FLOOR_DB,
            leftPeak = GainMath.FLOOR_DB,
            rightPeak = GainMath.FLOOR_DB,
            elapsedMs = 0L
        )
        state = next
        dispatch(next)
    }

    @Synchronized
    fun resetPeaks() {
        val next = state.copy(
            leftPeak = GainMath.FLOOR_DB,
            rightPeak = GainMath.FLOOR_DB
        )
        if (next !== state) {
            state = next
            dispatch(next)
        }
    }

    // --- Internal dispatch ---
    private fun dispatch(next: MicState) {
        if (observers.isEmpty()) return
        val looper = Looper.myLooper()
        val runnable = {
            for (observer in observers) {
                observer.onStateChanged(next)
            }
        }
        if (looper == Looper.getMainLooper()) {
            runnable()
        } else {
            mainHandler.post(runnable)
        }
    }
}