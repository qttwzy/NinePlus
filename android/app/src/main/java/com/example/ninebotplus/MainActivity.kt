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
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun resolveStartRoute(action: String?, path: String?): String {
        return when {
            action == "com.example.ninebotplus.ACTION_TRIPS" || path == "/trips" -> "trips"
            action == "com.example.ninebotplus.ACTION_RECORDING" || path == "/recording" -> "recording"
            action == "com.example.ninebotplus.ACTION_SETTINGS" || path == "/settings" -> "settings"
            else -> "dashboard"
        }
    }
}

@Composable
fun AppPreviewPlaceholder() {
    NinePlusTheme {
        // Preview-only placeholder
    }
}
