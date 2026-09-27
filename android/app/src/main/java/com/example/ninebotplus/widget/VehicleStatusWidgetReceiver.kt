package com.example.ninebotplus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.MainActivity
import com.example.ninebotplus.R
import com.example.ninebotplus.data.NinePlusDatabase
import com.example.ninebotplus.data.SettingsStore
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.VehicleAction
import com.example.ninebotplus.network.NinePlusApiClient
import com.example.ninebotplus.util.NineplusDates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: battery, range, status, last-update time, quick refresh/actions.
 *
 * Android widgets cannot refresh as often as WidgetKit timelines; WorkManager
 * schedules periodic updates and user taps force refresh.
 */
class VehicleStatusWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = VehicleRepository(
                    context.applicationContext,
                    SettingsStore(context.applicationContext),
                    NinePlusDatabase.get(context.applicationContext),
                )
                repository.initialize()
                val dashboard = repository.dashboard.value
                appWidgetIds.forEach { id ->
                    val views = buildViews(context, dashboard)
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val repository = VehicleRepository(
                            context.applicationContext,
                            SettingsStore(context.applicationContext),
                            NinePlusDatabase.get(context.applicationContext),
                        )
                        repository.initialize()
                        val dashboard = runCatching {
                            repository.refreshDashboard()
                        }.getOrElse { repository.dashboard.value }
                        val manager = AppWidgetManager.getInstance(context)
                        val ids = manager.getAppWidgetIds(
                            android.content.ComponentName(context, VehicleStatusWidgetReceiver::class.java),
                        )
                        ids.forEach { id ->
                            manager.updateAppWidget(id, buildViews(context, dashboard))
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_BELL, ACTION_OPEN_BUCKET, ACTION_ENGINE_START, ACTION_ENGINE_STOP -> {
                val action = when (intent.action) {
                    ACTION_BELL -> VehicleAction.BELL
                    ACTION_OPEN_BUCKET -> VehicleAction.OPEN_BUCKET
                    ACTION_ENGINE_START -> VehicleAction.ENGINE_START
                    else -> VehicleAction.ENGINE_STOP
                }
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val repository = VehicleRepository(
                            context.applicationContext,
                            SettingsStore(context.applicationContext),
                            NinePlusDatabase.get(context.applicationContext),
                        )
                        repository.initialize()
                        val sn = repository.dashboard.value.primaryVehicle?.vehicle?.sn
                            ?: return@launch
                        runCatching { repository.performAction(action, sn) }
                        val manager = AppWidgetManager.getInstance(context)
                        val ids = manager.getAppWidgetIds(
                            android.content.ComponentName(context, VehicleStatusWidgetReceiver::class.java),
                        )
                        ids.forEach { id ->
                            manager.updateAppWidget(
                                id,
                                buildViews(context, repository.dashboard.value),
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun buildViews(context: Context, dashboard: Dashboard): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_vehicle_status)
        val vehicle = dashboard.primaryVehicle
        if (vehicle == null) {
            views.setTextViewText(R.id.widgetTitle, "暂无车辆")
            views.setTextViewText(R.id.widgetRange, "-- km")
            views.setTextViewText(R.id.widgetBattery, "--%")
            views.setTextViewText(R.id.widgetStatus, "请先在 App 配置并刷新")
            views.setTextViewText(R.id.widgetUpdated, "")
        } else {
            val state = vehicle.state
            views.setTextViewText(R.id.widgetTitle, vehicle.vehicle.name)
            views.setTextViewText(R.id.widgetRange, state.localEstimatedMileageText)
            views.setTextViewText(R.id.widgetBattery, state.batteryText)
            views.setTextViewText(R.id.widgetStatus, state.primaryStatusText)
            views.setTextViewText(
                R.id.widgetUpdated,
                "${NineplusDates.formatTime(state.updatedAt)} 更新",
            )
        }

        views.setOnClickPendingIntent(
            R.id.widgetRoot,
            activityPendingIntent(context, "com.example.ninebotplus.ACTION_DASHBOARD", 1),
        )
        views.setOnClickPendingIntent(
            R.id.widgetRefresh,
            broadcastPendingIntent(context, ACTION_REFRESH, 2),
        )
        views.setOnClickPendingIntent(
            R.id.widgetBell,
            broadcastPendingIntent(context, ACTION_BELL, 3),
        )
        views.setOnClickPendingIntent(
            R.id.widgetLock,
            broadcastPendingIntent(
                context,
                if (vehicle?.state?.isLocked == false) ACTION_ENGINE_STOP else ACTION_ENGINE_START,
                4,
            ),
        )
        return views
    }

    private fun activityPendingIntent(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcastPendingIntent(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, VehicleStatusWidgetReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.example.ninebotplus.WIDGET_REFRESH"
        const val ACTION_BELL = "com.example.ninebotplus.WIDGET_BELL"
        const val ACTION_OPEN_BUCKET = "com.example.ninebotplus.WIDGET_OPEN_BUCKET"
        const val ACTION_ENGINE_START = "com.example.ninebotplus.WIDGET_ENGINE_START"
        const val ACTION_ENGINE_STOP = "com.example.ninebotplus.WIDGET_ENGINE_STOP"
    }
}
