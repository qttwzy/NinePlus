package com.example.ninebotplus.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ninebotplus.data.NinePlusDatabase
import com.example.ninebotplus.data.SettingsStore
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.VehicleAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Internal-only widget action receiver (`exported=false`).
 *
 * External apps cannot deliver to this component. Only PendingIntents created
 * by this app (widget RemoteViews) can.
 *
 * Allowed from widget without confirmation:
 * - refresh dashboard
 * - ring bell (non-destructive)
 *
 * NOT handled here (must be confirmed in the app UI):
 * - engine start / engine stop
 * - open seat bucket
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> handleRefresh(context)
            ACTION_BELL -> handleBell(context)
            else -> Unit
        }
    }

    private fun handleRefresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = newRepository(context)
                repository.initialize()
                val startedAt = java.util.Date()
                val result = runCatching { repository.refreshDashboard() }
                val dashboard = result.getOrElse { repository.dashboard.value }
                // Diagnostics must not lie: a cache fallback is not a success.
                val success = result.isSuccess
                val message = when {
                    success -> dashboard.primaryVehicle?.vehicle?.name
                    else -> "网络刷新失败，已显示缓存"
                }
                settings(context).saveLastWidgetRefresh(
                    com.example.ninebotplus.domain.RefreshEvent(
                        source = "Widget",
                        operation = "刷新车况",
                        startedAt = startedAt,
                        endedAt = java.util.Date(),
                        success = success,
                        message = message,
                    ),
                )
                if (!success) {
                    settings(context).saveLastError(result.exceptionOrNull()?.message ?: "刷新失败")
                }
                VehicleStatusWidgetReceiver.refreshAll(context, dashboard)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleBell(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = newRepository(context)
                repository.initialize()
                val sn = repository.dashboard.value.primaryVehicle?.vehicle?.sn
                if (sn == null) {
                    settings(context).saveLastError("没有找到可操作的车辆")
                    return@launch
                }
                val result = runCatching {
                    repository.performAction(com.example.ninebotplus.domain.VehicleAction.BELL, sn)
                }
                settings(context).saveLastWidgetRefresh(
                    com.example.ninebotplus.domain.RefreshEvent(
                        source = "Widget",
                        operation = "寻车鸣笛",
                        startedAt = java.util.Date(),
                        endedAt = java.util.Date(),
                        success = result.isSuccess,
                        message = result.exceptionOrNull()?.message
                            ?: "寻车指令已发送",
                    ),
                )
                if (result.isFailure) {
                    settings(context).saveLastError(result.exceptionOrNull()?.message ?: "寻车失败")
                }
                VehicleStatusWidgetReceiver.refreshAll(context, repository.dashboard.value)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.ninebotplus.action.WIDGET_REFRESH"
        const val ACTION_BELL = "com.example.ninebotplus.action.WIDGET_BELL"

        fun pendingIntent(context: Context, action: String, code: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun newRepository(context: Context): VehicleRepository = VehicleRepository(
            context.applicationContext,
            SettingsStore(context.applicationContext),
            NinePlusDatabase.get(context.applicationContext),
        )

        private fun settings(context: Context) = SettingsStore(context.applicationContext)
    }
}
