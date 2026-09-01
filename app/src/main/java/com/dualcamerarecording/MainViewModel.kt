package com.dualcamerarecording

import androidx.lifecycle.ViewModel

/**
 * ViewModel surviving Activity recreation (orientation change).
 * Holds state that must not reset when the Activity is recreated.
 */
class MainViewModel : ViewModel() {
    var previewAttempted = false
}