package com.dualcamerarecording.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * Atomic registry for running MicCapture instances.
 * Adapted from MicGainLevelerApp CaptureSession.
 */
class CaptureSession {

    private val _captureRef = AtomicReference<MicCapture?>(null)

    fun register(capture: MicCapture): Boolean {
        return _captureRef.compareAndSet(null, capture)
    }

    fun unregister() {
        _captureRef.set(null)
    }

    fun getActiveCapture(): MicCapture? {
        return _captureRef.get()
    }

    fun isRunning(): Boolean {
        val capture = _captureRef.get() ?: return false
        return capture.isCapturing.get()
    }
}