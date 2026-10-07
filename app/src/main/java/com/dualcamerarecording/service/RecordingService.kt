package com.dualcamerarecording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dualcamerarecording.MainActivity
import com.dualcamerarecording.R

/**
 * Foreground service (type camera | microphone) that runs while a recording is in
 * progress. The recording itself is driven by the activity's DualCameraRecorder; this
 * service only keeps the app in the foreground state, so Android does not revoke camera
 * and microphone access when the screen turns off or the user switches app (without it
 * the video froze and the audio went silent in the background).
 */
class RecordingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            createNotificationChannel()
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            // Recording still works while the activity is visible.
            Log.e(TAG, "Cannot start the foreground service: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_in_progress))
            .setSmallIcon(R.drawable.ic_record_dot)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    companion object {
        const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording_channel"
        const val ACTION_STOP = "com.dualcamerarecording.STOP_RECORDING_SERVICE"
        private const val NOTIFICATION_ID = 1

        /** Enter the foreground state for the duration of a recording. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
