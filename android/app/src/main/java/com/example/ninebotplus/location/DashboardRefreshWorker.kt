package com.example.ninebotplus.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.data.NinePlusDatabase
import com.example.ninebotplus.data.SettingsStore
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.RefreshEvent
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Periodic dashboard refresh (Android counterpart of BGAppRefreshTask).
 *
 * Interval adapts to vehicle state, matching iOS NinebotBackgroundTaskManager:
 * charging -> 15m, unlocked/powered -> 20m, default 30m.
 */
class DashboardRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NinePlusApp ?: return Result.retry()
        val repository = app.repository
        return try {
            val dashboard = repository.refreshDashboard()
            app.pushManager.syncChargingNotification(dashboard)
            schedule(applicationContext)
            Result.success()
        } catch (error: Exception) {
            repository.let {
                // keep last cache; record failure event is handled inside repository
            }
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "nineplus_dashboard_refresh"

        fun schedule(context: Context) {
            val app = context.applicationContext as? NinePlusApp
            val minutes = when {
                app?.repository?.dashboard?.value?.primaryVehicle?.state?.isCharging == true -> 15L
                app?.repository?.dashboard?.value?.primaryVehicle?.state?.isLocked == false -> 20L
                else -> 30L
            }
            val request = PeriodicWorkRequestBuilder<DashboardRefreshWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
