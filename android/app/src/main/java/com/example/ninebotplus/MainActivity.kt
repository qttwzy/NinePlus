package com.example.ninebotplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.ninebotplus.ui.NinePlusRoot
import com.example.ninebotplus.ui.theme.NinePlusTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NinePlusApp

        var startRoute by mutableStateOf(resolveStartRoute(intent?.action, intent?.data?.path))
        lifecycleScope.launch {
            val pending = app.settingsStore.consumePendingRoute()
            if (!pending.isNullOrBlank()) {
                startRoute = pending
            }
        }

        setContent {
            NinePlusTheme {
                NinePlusRoot(
                    app = app,
                    startRoute = startRoute,
                )
            }
        }

        // Cold-start: translate Intent into a one-shot event too.
        translateIntent(intent)?.let { app.onNavigationEvent(it) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as NinePlusApp
        // Single event contract: translate the Intent once and let Compose consume it.
        translateIntent(intent)?.let { event ->
            app.onNavigationEvent(event)
        }
        resolveStartRoute(intent.action, intent.data?.path).let { route ->
            app.onNewRoute(route)
        }
    }

    private fun translateIntent(intent: android.content.Intent): NavigationEvent? {
        return when (intent.action) {
            ACTION_PENDING_VEHICLE_COMMAND -> NavigationEvent.PendingVehicleCommand
            else -> null
        }
    }

    private fun resolveStartRoute(action: String?, path: String?): String {
        return when {
            action == "com.example.ninebotplus.ACTION_TRIPS" || path == "/trips" -> "trips"
            action == "com.example.ninebotplus.ACTION_RECORDING" || path == "/recording" -> "recording"
            action == "com.example.ninebotplus.ACTION_SETTINGS" || path == "/settings" -> "settings"
            else -> "dashboard"
        }
    }

    companion object {
        /**
         * Widget / notification dangerous action: open app and require confirmation.
         * Translated into [NavigationEvent.PendingVehicleCommand] exactly once.
         */
        const val ACTION_PENDING_VEHICLE_COMMAND = "com.example.ninebotplus.ACTION_PENDING_VEHICLE_COMMAND"
    }
}

/**
 * One-shot navigation / confirmation events. Compose consumes and clears them.
 */
sealed class NavigationEvent {
    data object PendingVehicleCommand : NavigationEvent()
}

@Composable
fun AppPreviewPlaceholder() {
    NinePlusTheme {
        // Preview-only placeholder
    }
}
