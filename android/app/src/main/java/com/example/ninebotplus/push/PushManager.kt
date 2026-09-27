package com.example.ninebotplus.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.MainActivity
import com.example.ninebotplus.R
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Charging notification + FCM registration helper.
 *
 * Android semantic mapping of iOS charging Live Activity:
 * ongoing notification while charging, auto-dismiss on full / stop.
 */
class PushManager(
    private val context: Context,
    private val repository: VehicleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun syncChargingNotification(dashboard: Dashboard) {
        val vehicle = dashboard.primaryVehicle ?: run {
            cancelChargingNotification()
            return
        }
        val state = vehicle.state
        val charging = state.isCharging == true && !state.isFullyCharged && state.battery != null
        if (charging) {
            showChargingNotification(vehicle)
        } else {
            cancelChargingNotification()
        }
    }

    fun showChargingNotification(vehicle: VehicleSnapshot) {
        val state = vehicle.state
        val title = "${vehicle.vehicle.name} · 充电中"
        val text = buildString {
            append("${state.batteryText} · 续航 ${state.localEstimatedMileageText}")
            append(" · 约 ${state.estimatedFullChargeTimeText} 充满")
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "com.example.ninebotplus.ACTION_DASHBOARD"
        }
        val pending = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NinePlusApp.CHANNEL_CHARGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(CHARGING_NOTIFICATION_ID, notification)
        }
    }

    fun cancelChargingNotification() {
        NotificationManagerCompat.from(context).cancel(CHARGING_NOTIFICATION_ID)
    }

    fun onNewFcmToken(token: String) {
        scope.launch {
            repository.savePushToken(token)
            runCatching { repository.registerPushTokenToServer() }
        }
    }

    fun onPushMessage(title: String?, body: String?, vehicleSn: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "com.example.ninebotplus.ACTION_DASHBOARD"
            vehicleSn?.let { putExtra("vehicle_sn", it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NinePlusApp.CHANNEL_CHARGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: "NinePlus")
            .setContentText(body ?: "")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                (System.currentTimeMillis() % 10_000).toInt(),
                notification,
            )
        }
    }

    companion object {
        const val CHARGING_NOTIFICATION_ID = 9101
    }
}
