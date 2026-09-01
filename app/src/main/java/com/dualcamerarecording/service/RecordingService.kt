package com.dualcamerarecording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dualcamerarecording.DualCameraRecorderApp
import com.dualcamerarecording.R
import com.dualcamerarecording.audio.CaptureSession
import com.dualcamerarecording.audio.MicCapture
import com.dualcamerarecording.audio.MicStateStore
import com.dualcamerarecording.camera.CameraInventory
import com.dualcamerarecording.camera.DualCameraRecorder
import com.dualcamerarecording.model.AudioSourceOption
import com.dualcamerarecording.model.DualCameraConfig
import com.dualcamerarecording.model.StreamConfig
import com.dualcamerarecording.settings.CameraSettingsStore

/**
 * Foreground service for dual camera recording with shared audio capture.
 * Runs the MediaRecorders and MicCapture session.
 */
class RecordingService : Service() {

    private lateinit var dualCameraRecorder: DualCameraRecorder
    private lateinit var micCapture: MicCapture
    private lateinit var captureSession: CaptureSession
    private var micStateStore: MicStateStore = MicStateStore()
    private lateinit var cameraInventory: CameraInventory

    private var config: DualCameraConfig? = null

    // Preview surfaces (set before starting service)
    private var frontPreviewSurface: android.view.Surface? = null
    private var rearPreviewSurface: android.view.Surface? = null

    companion object {
        const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording_channel"
        const val ACTION_START = "com.dualcamerarecording.START_RECORDING"
        const val ACTION_STOP = "com.dualcamerarecording.STOP_RECORDING"

        @Volatile
        private var instance: RecordingService? = null

        fun isRunning(): Boolean = instance != null

        fun setPreviewSurfaces(front: android.view.Surface?, rear: android.view.Surface?) {
            instance?.frontPreviewSurface = front
            instance?.rearPreviewSurface = rear
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Reuse the Application-level MicStateStore so that meters in MainActivity
        // and metering from this service read/write the same StateFlow.
        val app = application as? DualCameraRecorderApp
        micStateStore = app?.micStateStore ?: MicStateStore()

        dualCameraRecorder = DualCameraRecorder()
        micCapture = MicCapture()
        captureSession = CaptureSession()
        cameraInventory = CameraInventory(this)

        dualCameraRecorder.onSingleCameraMode = { position ->
            Log.w(TAG, "Single camera mode activated: recording with $position only")
        }

        dualCameraRecorder.onRecordingStarted = { frontFile, rearFile ->
            Log.d(TAG, "Recording started: front=${frontFile?.absolutePath}, rear=${rearFile?.absolutePath}")
        }

        dualCameraRecorder.onRecordingStopped = { frontFile, rearFile, error ->
            Log.d(TAG, "Recording stopped: front=${frontFile?.absolutePath}, rear=${rearFile?.absolutePath}, error=$error")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                // Build config from intent extras
                val frontCameraId = intent.getStringExtra("front_camera_id") ?: ""
                val rearCameraId = intent.getStringExtra("rear_camera_id") ?: ""
                config = DualCameraConfig(
                    frontCameraId = frontCameraId,
                    rearCameraId = rearCameraId
                )
                startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Set configuration for recording.
     */
    fun setConfiguration(config: DualCameraConfig) {
        this.config = config
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Dual camera recording"

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_in_progress))
            .setSmallIcon(R.drawable.ic_record_dot)
            .build()
    }

    /**
     * Start recording with shared audio.
     */
    private fun startRecording() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        dualCameraRecorder.initialize(cameraManager)

        val cfg = config ?: run {
            Log.e(TAG, "Config not set, cannot start recording")
            return
        }

        // Set up preview surfaces
        frontPreviewSurface?.let { frontSurface ->
            rearPreviewSurface?.let { rearSurface ->
                dualCameraRecorder.setupCameras(
                    frontCameraId = cfg.frontCameraId,
                    rearCameraId = cfg.rearCameraId,
                    frontPreviewSurface = frontSurface,
                    rearPreviewSurface = rearSurface,
                    config = cfg
                )
            }
        }

        // Start preview first
        dualCameraRecorder.startPreview()

        // Start shared audio capture
        setupAudioCapture()
        micCapture.startCapture()

        // Set audio capture callbacks for DualCameraRecorder
        dualCameraRecorder.onAudioCaptureStarted = {
            micStateStore.setRecording(true)
        }
        dualCameraRecorder.onAudioCaptureStopped = {
            micStateStore.setRecording(false)
        }

        // Update mic state store with levels using live meter updates
        var elapsedMs = 0L
        micCapture.setMonitorTap(object : MicCapture.MonitorTap {
            override fun onAudioLevels(leftDb: Double, rightDb: Double, peakLeft: Double, peakRight: Double) {
                elapsedMs += 100
                micStateStore.updateLiveMeters(elapsedMs, leftDb, rightDb, peakLeft, peakRight)
            }
        })

        // Start recording
        val filePair = dualCameraRecorder.startRecording(
            frontConfig = cfg.frontConfig,
            rearConfig = cfg.rearConfig,
            screenOrientation = cfg.rearConfig.streamOrientation,
            audioBitrateKbps = cfg.audioBitrateKbps,
            audioSampleRateHz = cfg.audioSampleRateHz
        )
        filePair?.let {
            Log.d(TAG, "Recording started: front=${it.front.absolutePath}, rear=${it.rear.absolutePath}")
        }
    }

    /**
     * Set up audio capture for metering.
     */
    private fun setupAudioCapture() {
        micCapture.apply {
            initialize(
                channelMode = MicCapture.ChannelMode.MONO_DUPLICATED,
                sampleRate = 44100,
                bufferSizeSeconds = 2.0
            )
        }

        captureSession.register(micCapture)
    }

    /**
     * Stop recording.
     */
    private fun stopRecording() {
        dualCameraRecorder.stopRecording()
        micCapture.stopCapture()
        captureSession.unregister()
        micCapture.release()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null

        if (dualCameraRecorder.isRecording()) {
            dualCameraRecorder.stopRecording()
        }
        dualCameraRecorder.release()

        try {
            micCapture.release()
        } catch (e: Exception) { }
    }

    /**
     * Get the mic state store for UI updates.
     */
    fun getMicStateStore(): MicStateStore = micStateStore

    /**
     * Get the dual camera recorder instance.
     */
    fun getDualCameraRecorder(): DualCameraRecorder = dualCameraRecorder
}
