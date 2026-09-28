package com.example.ninebotplus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.ninebotplus.data.NinePlusDatabase
import com.example.ninebotplus.data.SettingsStore
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.push.PushManager

class NinePlusApp : Application() {
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var repository: VehicleRepository
        private set
    lateinit var pushManager: PushManager
        private set

    /** Navigation requests that arrive while the activity is already alive. */
    private val _pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingRoute: kotlinx.coroutines.flow.StateFlow<String?> = _pendingRoute

    /** One-shot events (e.g. widget dangerous-action confirmation). */
    private val _navEvents = kotlinx.coroutines.flow.MutableStateFlow<NavigationEvent?>(null)
    val navEvents: kotlinx.coroutines.flow.StateFlow<NavigationEvent?> = _navEvents

    fun onNewRoute(route: String) {
        _pendingRoute.value = route
    }

    fun consumeNewRoute() {
        _pendingRoute.value = null
    }

    fun onNavigationEvent(event: NavigationEvent) {
        _navEvents.value = event
    }

    fun consumeNavigationEvent() {
        _navEvents.value = null
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        repository = VehicleRepository(this, settingsStore, NinePlusDatabase.get(this))
        pushManager = PushManager(this, repository)
        createNotificationChannels()
        com.example.ninebotplus.location.DashboardRefreshWorker.schedule(this)
        initAmapPrivacy()
    }

    /** 高德 SDK 合规：必须在创建 MapView 前完成隐私同意。 */
    private fun initAmapPrivacy() {
        try {
            com.amap.api.maps.MapsInitializer.updatePrivacyShow(this, true, true)
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(this, true)
        } catch (_: Exception) {
            // Map screens show fallback UI if the SDK is unavailable.
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHARGING,
                "充电状态",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "车辆充电状态与充满提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RIDE,
                "骑行记录",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "本地骑行记录进行中通知"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WIDGET,
                "桌面组件",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台刷新车况"
            },
        )
    }

    companion object {
        const val CHANNEL_CHARGING = "charging"
        const val CHANNEL_RIDE = "ride_recording"
        const val CHANNEL_WIDGET = "widget"
    }
}
