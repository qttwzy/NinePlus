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
                    pendingAction = intent?.getStringExtra(EXTRA_PENDING_ACTION),
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Compose reads startRoute only once; re-trigger navigation from the new intent.
        // Root observes this via a StateFlow below (pendingNavigation).
        (application as NinePlusApp).onNewRoute(resolveStartRoute(intent.action, intent.data?.path))
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
        const val EXTRA_PENDING_ACTION = "pending_vehicle_action"
        /** Widget / notification dangerous action: open app and require confirmation. */
        const val ACTION_PENDING_VEHICLE_COMMAND = "com.example.ninebotplus.ACTION_PENDING_VEHICLE_COMMAND"
    }
}

@Composable
fun AppPreviewPlaceholder() {
    NinePlusTheme {
        // Preview-only placeholder
    }
}
