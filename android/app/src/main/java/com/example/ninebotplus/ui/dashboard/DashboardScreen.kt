package com.example.ninebotplus.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ninebotplus.domain.VehicleAction
import com.example.ninebotplus.domain.VehicleHealthLevel
import com.example.ninebotplus.domain.VehicleSnapshot
import com.example.ninebotplus.ui.AppViewModel
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.ui.theme.TeslaOrange
import com.example.ninebotplus.ui.theme.TeslaRed
import com.example.ninebotplus.util.NineplusDates

@Composable
fun DashboardScreen(viewModel: AppViewModel) {
    val dashboard by viewModel.dashboard.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val resolved by viewModel.resolvedAddresses.collectAsState()
    var showSwitcher by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<VehicleAction?>(null) }
    var confirmSn by remember { mutableStateOf<String?>(null) }
    var showRangeInfo by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }

    val primary = dashboard.primaryVehicle

    if (primary == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                title = if (ui.isLoading) "正在加载" else "暂无车辆数据",
                subtitle = "刷新后会显示九号车辆状态",
            )
            Button(onClick = { viewModel.refreshDashboard() }, enabled = !ui.isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("刷新车况")
            }
        }
        return
    }
    val vehicle = primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ui.statusMessage != null) {
            StatusBanner(ui.statusMessage!!, isError = false)
        }
        if (ui.errorMessage != null) {
            StatusBanner(ui.errorMessage!!, isError = true)
        }

        HeroSection(
            snapshot = vehicle,
            resolvedAddress = resolved[vehicle.vehicle.sn]?.address,
            onSwitchVehicle = { showSwitcher = true },
            onOpenMap = { showMap = true },
            onShowRangeInfo = { showRangeInfo = true },
            canSwitch = dashboard.vehicles.size > 1,
        )

        if (ui.activeAction != null && ui.activeActionSn == vehicle.vehicle.sn) {
            LoadingStrip(ui.activeAction!!.loadingTitle)
        }

        ActionPanel(
            snapshot = vehicle,
            isLoading = ui.isLoading,
            onAction = { action ->
                if (action.isDangerous) {
                    confirmAction = action
                    confirmSn = vehicle.vehicle.sn
                } else {
                    viewModel.perform(action, vehicle.vehicle.sn)
                }
            },
        )

        LocationRideCards(
            snapshot = vehicle,
            resolvedAddress = resolved[vehicle.vehicle.sn]?.address,
            privacyEnabled = viewModel.capturePrivacy.collectAsState().value,
            onOpenMap = { showMap = true },
        )

        BatteryCard(snapshot = vehicle)
        InfoCard(snapshot = vehicle)

        if (dashboard.vehicles.size > 1) {
            Text(
                "车辆概览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            dashboard.vehicles.forEach { snapshot ->
                VehicleRow(
                    snapshot = snapshot,
                    selected = snapshot.vehicle.sn == vehicle.vehicle.sn,
                    onClick = { viewModel.selectVehicle(snapshot.vehicle.sn) },
                )
            }
        }
    }

    if (showSwitcher) {
        VehicleSwitchSheet(
            vehicles = dashboard.vehicles,
            selectedSn = vehicle.vehicle.sn,
            onSelect = {
                viewModel.selectVehicle(it)
                showSwitcher = false
            },
            onDismiss = { showSwitcher = false },
        )
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = {
                confirmAction = null
                confirmSn = null
            },
            title = { Text(action.confirmationTitle) },
            text = { Text(action.confirmationMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sn = confirmSn ?: vehicle.vehicle.sn
                        viewModel.perform(action, sn)
                        confirmAction = null
                        confirmSn = null
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        confirmSn = null
                    },
                ) { Text("取消") }
            },
        )
    }

    if (showRangeInfo) {
        AlertDialog(
            onDismissRequest = { showRangeInfo = false },
            title = { Text("算法预估") },
            text = {
                Text(vehicle.state.localEstimateBasisText + "\n\n" + vehicle.state.rangeModelInsightText)
            },
            confirmButton = {
                TextButton(onClick = { showRangeInfo = false }) { Text("知道了") }
            },
        )
    }

    if (showMap) {
        val lat = vehicle.state.latitude
        val lon = vehicle.state.longitude
        AlertDialog(
            onDismissRequest = { showMap = false },
            title = { Text("车辆位置") },
            text = {
                if (lat == null || lon == null) {
                    Text("车辆暂无可显示的坐标")
                } else {
                    androidx.compose.foundation.layout.Column {
                        com.example.ninebotplus.ui.map.VehicleLocationMap(
                            latitude = lat,
                            longitude = lon,
                            title = vehicle.vehicle.name,
                            privacyEnabled = viewModel.capturePrivacy.collectAsState().value,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                        )
                        Text(
                            resolved[vehicle.vehicle.sn]?.address
                                ?: vehicle.state.locationText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMap = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun HeroSection(
    snapshot: VehicleSnapshot,
    resolvedAddress: String?,
    onSwitchVehicle: () -> Unit,
    onOpenMap: () -> Unit,
    onShowRangeInfo: () -> Unit,
    canSwitch: Boolean,
) {
    val state = snapshot.state
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            snapshot.vehicle.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (canSwitch) {
                            TextButton(onClick = onSwitchVehicle) {
                                Text("切换", color = TeslaGreen)
                            }
                        }
                    }
                    Text(
                        snapshot.vehicle.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        resolvedAddress ?: state.locationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.clickable(onClick = onOpenMap),
                    )
                }
                LockChip(isLocked = state.isLocked)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                state.localEstimatedMileageText,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 44.sp,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.predictionModelTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onShowRangeInfo) {
                    Text("说明", color = TeslaGreen, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.batteryFraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = batteryColor(state.battery, state.isCharging == true),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCell("电量", state.batteryText, Icons.Default.Bolt)
                MetricCell("官方预估", state.officialEstimatedMileageText, Icons.Default.Place)
                MetricCell("均速", state.averageSpeedText, Icons.Default.ElectricBolt)
            }

            if (state.isCharging == true && !state.isFullyCharged) {
                Spacer(Modifier.height(12.dp))
                ChargingStrip(state.chargeSummaryText)
            }

            if (state.isPoweredOn == false && state.isCharging != true) {
                Spacer(Modifier.height(12.dp))
                StatusBanner("车辆未上电 · 当前处于未上电状态，请确认车辆状态后再操作", isError = true)
            }
        }
    }
}

@Composable
private fun LockChip(isLocked: Boolean?) {
    val (text, color, icon) = when (isLocked) {
        true -> Triple("已上锁", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Lock)
        false -> Triple("已解锁", TeslaOrange, Icons.Default.LockOpen)
        null -> Triple("锁车未知", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Lock)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MetricCell(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun ChargingStrip(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(TeslaGreen.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null, tint = TeslaGreen)
        Spacer(Modifier.width(8.dp))
        Text("正在充电 · $text", color = TeslaGreen, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionPanel(
    snapshot: VehicleSnapshot,
    isLoading: Boolean,
    onAction: (VehicleAction) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionButton("寻车", enabled = !isLoading) { onAction(VehicleAction.BELL) }
            ActionButton("座桶", enabled = !isLoading) { onAction(VehicleAction.OPEN_BUCKET) }
            // Locked bikes often report pwr=1 (ECU awake). Pick the next useful
            // command from lock state first — locked ⇒ 上电, unlocked ⇒ 熄火.
            val choice = com.example.ninebotplus.domain.PowerActionDecision.decide(
                isLocked = snapshot.state.isLocked,
                isPoweredOn = snapshot.state.isPoweredOn,
            )
            val label = com.example.ninebotplus.domain.PowerActionDecision.label(choice)
            val action = com.example.ninebotplus.domain.PowerActionDecision.action(choice)
            ActionButton(label, enabled = !isLoading && action != null, emphasized = true) {
                action?.let(onAction)
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    if (emphasized) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(48.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(48.dp),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun LoadingStrip(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("$text · 发送完成后自动刷新车况")
    }
}

@Composable
private fun LocationRideCards(
    snapshot: VehicleSnapshot,
    resolvedAddress: String?,
    privacyEnabled: Boolean,
    onOpenMap: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenMap),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("车辆位置", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (privacyEnabled) {
                        Text("位置已隐藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            tint = TeslaGreen,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (privacyEnabled) "位置已隐藏" else (resolvedAddress ?: snapshot.state.locationText),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Card(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("行程", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    snapshot.state.lastMileage?.let {
                        com.example.ninebotplus.util.NumberFormats.distanceKm(it)
                    } ?: "-- km",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("最近骑行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    snapshot.state.totalMileageText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text("总行程", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BatteryCard(snapshot: VehicleSnapshot) {
    val state = snapshot.state
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("电池", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.chargeSummaryText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(state.batteryText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            if (state.warningTexts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                state.warningTexts.forEach {
                    Text("· $it", color = TeslaOrange, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(snapshot: VehicleSnapshot) {
    val state = snapshot.state
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("查看信息", fontWeight = FontWeight.SemiBold)
            Text(
                "${snapshot.vehicle.model} · 更新 ${NineplusDates.formatTime(state.updatedAt)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.health.title + " · " + state.health.message,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun VehicleRow(snapshot: VehicleSnapshot, selected: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                TeslaGreen.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(snapshot.vehicle.name, fontWeight = FontWeight.Medium)
                Text(
                    "${snapshot.state.enduranceText} · ${snapshot.state.primaryStatusText}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(snapshot.state.batteryText, fontWeight = FontWeight.SemiBold)
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.CheckCircle, contentDescription = "已选中", tint = TeslaGreen)
            }
        }
    }
}

@Composable
private fun StatusBanner(text: String, isError: Boolean) {
    val color = if (isError) TeslaRed else TeslaGreen
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleSwitchSheet(
    vehicles: List<VehicleSnapshot>,
    selectedSn: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换车辆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vehicles.forEach { snapshot ->
                    VehicleRow(
                        snapshot = snapshot,
                        selected = snapshot.vehicle.sn == selectedSn,
                        onClick = { onSelect(snapshot.vehicle.sn) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun batteryColor(battery: Int?, charging: Boolean): Color = when {
    charging -> TeslaGreen
    battery == null -> Color.Gray
    battery < 15 -> TeslaRed
    battery < 50 -> TeslaOrange
    else -> TeslaGreen
}

private fun VehicleHealthLevel.color(): Color = when (this) {
    VehicleHealthLevel.GOOD, VehicleHealthLevel.CHARGING -> TeslaGreen
    VehicleHealthLevel.ATTENTION -> TeslaOrange
    VehicleHealthLevel.CRITICAL -> TeslaRed
    VehicleHealthLevel.UNKNOWN -> Color.Gray
}
