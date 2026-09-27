package com.example.ninebotplus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ninebotplus.MainActivity
import com.example.ninebotplus.NavigationEvent
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.ui.dashboard.DashboardScreen
import com.example.ninebotplus.ui.recording.RecordingScreen
import com.example.ninebotplus.ui.settings.SettingsScreen
import com.example.ninebotplus.ui.trips.TripsScreen

enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "车控", Icons.Filled.Home),
    TRIPS("trips", "行程", Icons.Filled.Place),
    RECORDING("recording", "记录", Icons.Outlined.History),
    SETTINGS("settings", "我的", Icons.Filled.Settings),
}

@Composable
fun NinePlusRoot(
    app: NinePlusApp,
    startRoute: String = "dashboard",
    pendingAction: String? = null,
) {
    val viewModel: AppViewModel = viewModel(
        factory = AppViewModel.factory(app),
    )
    var selected by remember {
        mutableStateOf(
            AppTab.entries.firstOrNull { it.route == startRoute } ?: AppTab.DASHBOARD,
        )
    }

    // onNewIntent delivers a new route while the activity is alive.
    val newRoute by app.pendingRoute.collectAsState()
    LaunchedEffect(newRoute) {
        val route = newRoute ?: return@LaunchedEffect
        selected = AppTab.entries.firstOrNull { it.route == route } ?: selected
        app.consumeNewRoute()
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    // One-shot dangerous-action confirmation from widget / notification.
    val navEvent by app.navEvents.collectAsState()
    if (navEvent is NavigationEvent.PendingVehicleCommand) {
        AlertDialog(
            onDismissRequest = { app.consumeNavigationEvent() },
            title = { Text("需要确认车控操作") },
            text = {
                Text("桌面组件不会直接执行上电/熄火/开座桶。请在车控页使用确认按钮操作车辆。")
            },
            confirmButton = {
                TextButton(onClick = {
                    selected = AppTab.DASHBOARD
                    app.consumeNavigationEvent()
                }) { Text("前往车控") }
            },
            dismissButton = {
                TextButton(onClick = { app.consumeNavigationEvent() }) { Text("关闭") }
            },
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selected) {
                AppTab.DASHBOARD -> DashboardScreen(viewModel)
                AppTab.TRIPS -> TripsScreen(viewModel)
                AppTab.RECORDING -> RecordingScreen(viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
