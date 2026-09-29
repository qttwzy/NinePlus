package com.example.ninebotplus.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.ninebotplus.BuildConfig
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.MainActivity
import com.example.ninebotplus.R
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.VehicleSnapshot
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Charging notification + FCM registration helper.
 *
 * Android semantic mapping of iOS charging Live Activity:
 * ongoing notification while charging, auto-dismiss on full / stop.
 *
 * FCM bootstrap (either path is enough):
 * 1. `android/app/google-services.json` + google-services plugin (standard)
 * 2. `firebase.*` keys in local.properties → manual [FirebaseApp] init
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
        notifySafely(CHARGING_NOTIFICATION_ID, notification)
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

    /**
     * Ensure a usable FCM token: reuse the cached one, otherwise ask Firebase.
     * `onNewToken` only fires on rotation, so a fresh install can have a token
     * that was never delivered to us — pull it explicitly.
     */
    suspend fun ensureFcmToken(): String? {
        maybeInitFirebase()
        repository.currentPushToken()?.takeIf { it.isNotBlank() }?.let { return it }
        val token = awaitFcmToken() ?: return null
        repository.savePushToken(token)
        return token
    }

    /** True when Firebase has been initialized (json plugin or local.properties). */
    fun isFirebaseReady(): Boolean = runCatching {
        FirebaseApp.getApps(context).isNotEmpty()
    }.getOrDefault(false)

    /** True when local.properties carries a full manual Firebase config. */
    fun hasManualFirebaseConfig(): Boolean {
        return BuildConfig.FCM_API_KEY.isNotBlank() &&
            BuildConfig.FCM_APP_ID.isNotBlank() &&
            BuildConfig.FCM_PROJECT_ID.isNotBlank() &&
            BuildConfig.FCM_SENDER_ID.isNotBlank()
    }

    private fun maybeInitFirebase() {
        if (isFirebaseReady()) return
        if (!hasManualFirebaseConfig()) return
        runCatching {
            val builder = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            if (BuildConfig.FCM_DEFAULT_WEB_CLIENT_ID.isNotBlank()) {
                builder.setDatabaseUrl("https://${BuildConfig.FCM_PROJECT_ID}.firebaseio.com")
            }
            FirebaseApp.initializeApp(context, builder.build())
        }
    }

    private suspend fun awaitFcmToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (cont.isActive) {
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
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
        val notification = NotificationCompat.Builder(context, NinePlusApp.CHANNEL_PUSH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: "NinePlus")
            .setContentText(body ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: ""))
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        notifySafely((System.currentTimeMillis() % 10_000).toInt(), notification)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, notification: android.app.Notification) {
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHARGING_NOTIFICATION_ID = 9101
    }
}

