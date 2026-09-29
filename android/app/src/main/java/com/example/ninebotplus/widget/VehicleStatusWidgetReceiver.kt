package com.example.ninebotplus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.ninebotplus.MainActivity
import com.example.ninebotplus.R
import com.example.ninebotplus.data.NinePlusDatabase
import com.example.ninebotplus.data.SettingsStore
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.util.NineplusDates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget provider.
 *
 * SECURITY: this receiver is `exported="true"` because the system must deliver
 * APPWIDGET_UPDATE. It therefore MUST NOT execute vehicle commands.
 *
 * - Refresh / ring-bell go through [WidgetActionReceiver] (`exported=false`).
 * - Dangerous commands (engine start/stop, open bucket) never execute from the
 *   widget. Those buttons open the app with a pending action that the user must
 *   confirm in the foreground UI.
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
                val dashboard = loadDashboard(context)
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(id, buildViews(context, dashboard))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun refreshAll(context: Context, dashboard: Dashboard) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, VehicleStatusWidgetReceiver::class.java),
            )
            ids.forEach { id ->
                manager.updateAppWidget(id, buildViews(context, dashboard))
            }
        }

        private suspend fun loadDashboard(context: Context): Dashboard {
            val repository = VehicleRepository(
                context.applicationContext,
                SettingsStore(context.applicationContext),
                NinePlusDatabase.get(context.applicationContext),
            )
            repository.initialize()
            return repository.dashboard.value
        }

        internal fun buildViews(context: Context, dashboard: Dashboard): RemoteViews {
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

            // Root click: open the app.
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                activityPendingIntent(context, "com.example.ninebotplus.ACTION_DASHBOARD", 1),
            )
            // Safe actions: non-exported receiver, explicit component.
            views.setOnClickPendingIntent(
                R.id.widgetRefresh,
                WidgetActionReceiver.pendingIntent(context, WidgetActionReceiver.ACTION_REFRESH, 2),
            )
            views.setOnClickPendingIntent(
                R.id.widgetBell,
                WidgetActionReceiver.pendingIntent(context, WidgetActionReceiver.ACTION_BELL, 3),
            )
            // Dangerous action: open the app and let the user confirm.
            // NEVER execute engine start/stop or bucket open from a widget tap.
            views.setOnClickPendingIntent(
                R.id.widgetControl,
                activityPendingIntent(context, MainActivity.ACTION_PENDING_VEHICLE_COMMAND, 4),
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
    }
}
