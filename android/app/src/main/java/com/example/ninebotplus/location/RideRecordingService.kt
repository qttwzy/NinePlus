package com.example.ninebotplus.location

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.MainActivity
import com.example.ninebotplus.R

/**
 * Foreground service that keeps ride recording alive while the app is backgrounded.
 * Uses FOREGROUND_SERVICE_LOCATION; no aggressive wake locks.
 */
class RideRecordingService : Service() {

    private lateinit var recorder: RideRecorder

    override fun onCreate() {
        super.onCreate()
        recorder = RideRecorder.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                recorder.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                recorder.startPreview()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "com.example.ninebotplus.ACTION_RECORDING"
        }
        val pending = PendingIntent.getActivity(
            this,
            3001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NinePlusApp.CHANNEL_RIDE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在记录骑行")
            .setContentText("记录 GPS 轨迹与速度，点击返回应用")
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.ninebotplus.action.STOP_RIDE_RECORDING"
        const val NOTIFICATION_ID = 9201

        fun start(context: Context) {
            val intent = Intent(context, RideRecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RideRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
