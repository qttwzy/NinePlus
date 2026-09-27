package com.example.ninebotplus.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.ninebotplus.domain.DailyMileageRecord
import com.example.ninebotplus.domain.RideDetail
import com.example.ninebotplus.domain.RideRecord
import com.example.ninebotplus.ui.AppViewModel
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.ui.theme.TeslaOrange
import com.example.ninebotplus.util.NineplusDates
import com.example.ninebotplus.util.NumberFormats

@Composable
fun TripsScreen(viewModel: AppViewModel) {
    val dashboard by viewModel.dashboard.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val interfaceRides by viewModel.interfaceRides.collectAsState()
    val rideDetails by viewModel.rideDetails.collectAsState()

    val primary = dashboard.primaryVehicle
    var selectedMonth by remember { mutableStateOf(NineplusDates.currentMonthString()) }
    var selectedRide by remember { mutableStateOf<RideRecord?>(null) }
    var visibleLimit by remember { mutableStateOf(30) }

    if (primary == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("暂无车辆", style = MaterialTheme.typography.titleMedium)
            Text("刷新车况后可查看行程", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val allRides = interfaceRides[primary.vehicle.sn].orEmpty()
    val months = remember(allRides, selectedMonth) {
        val set = linkedSetOf(NineplusDates.currentMonthString(), selectedMonth)
        allRides.forEach { ride ->
            val date = ride.startedAt ?: ride.endedAt
            if (date != null) set += NineplusDates.monthString(date)
        }
        set.sortedDescending()
    }
    val monthRides = allRides.filter { ride ->
        val date = ride.startedAt ?: ride.endedAt
        date != null && NineplusDates.monthString(date) == selectedMonth
    }

    selectedRide?.let { ride ->
        RideDetailDialog(
            viewModel = viewModel,
            ride = ride,
            detail = rideDetails["${primary.vehicle.sn}|${ride.id}"],
            onDismiss = { selectedRide = null },
        )
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("行程", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }

        item {
            val state = primary.state
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("行程概要", fontWeight = FontWeight.SemiBold)
                    Text(primary.vehicle.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.localEstimatedMileageText,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("预计可行驶", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("今日里程", state.todayMileageText)
                        Metric("平均速度", state.averageSpeedText)
                        Metric("有效样本", "${state.observedRangeSampleCount} 次")
                        Metric("本月日均", state.dailyAverageMileageText)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.rangeEstimateAccuracyDetailText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("月份筛选", fontWeight = FontWeight.SemiBold)
                    Text(
                        "当前 ${NineplusDates.displayMonthDot(selectedMonth)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        months.forEach { month ->
                            val selected = month == selectedMonth
                            Text(
                                NineplusDates.displayMonthDot(month),
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .background(
                                        if (selected) TeslaGreen else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(50),
                                    )
                                    .clickable {
                                        selectedMonth = month
                                        visibleLimit = 30
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val oldest = months.lastOrNull() ?: selectedMonth
                            viewModel.syncTravelMonth(primary.vehicle.sn, NineplusDates.previousMonth(oldest))
                        },
                        enabled = ui.syncingMonth == null && !ui.isLoading,
                    ) {
                        Text(
                            if (ui.syncingMonth != null) {
                                "正在获取 ${NineplusDates.displayMonth(ui.syncingMonth!!)}"
                            } else {
                                "获取 ${NineplusDates.displayMonthDot(NineplusDates.previousMonth(months.lastOrNull() ?: selectedMonth))}"
                            },
                        )
                    }
                }
            }
        }

        item {
            Row {
                Text("行程列表", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${monthRides.size} 条", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        if (monthRides.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${NineplusDates.displayMonth(selectedMonth)} 暂无行程")
                        Text(
                            "可以切换已有月份，或继续向前获取服务器归档。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(monthRides.take(visibleLimit)) { ride ->
                RideRow(ride) { selectedRide = ride }
            }
            if (monthRides.size > visibleLimit) {
                item {
                    TextButton(onClick = { visibleLimit += 30 }) {
                        Text("显示更多 ${monthRides.size - visibleLimit}")
                    }
                }
            }
        }

        item {
            TrendCard(primary.state.dailyMileages, primary.state.monthMileage)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RideRow(ride: RideRecord, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ride.startedAt?.let { NineplusDates.formatRideDate(it) } ?: "行程",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    buildString {
                        append(
                            ride.endedAt?.let { "结束 ${NineplusDates.formatTime(it)}" } ?: "结束时间未知",
                        )
                        ride.durationMinutes?.let {
                            append(" · ")
                            append(NineplusDates.formatDuration(it))
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val metrics = buildList {
                    ride.energy?.let { add("${NumberFormats.number(it, 0)} Wh") }
                    ride.usedElectricity?.let { add("${NumberFormats.number(it, 1)}%") }
                    ride.speed?.let { add("${NumberFormats.number(it, 1)} km/h") }
                }
                if (metrics.isNotEmpty()) {
                    Text(metrics.joinToString(" · "), fontSize = 11.sp, color = TeslaGreen)
                }
            }
            Text(
                ride.mileage?.let { "${NumberFormats.number(it, 1)} km" } ?: "-- km",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun TrendCard(daily: List<DailyMileageRecord>, monthMileage: Double?) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("趋势分析", fontWeight = FontWeight.SemiBold)
            Text(
                if (daily.isEmpty()) "等待接口返回本月 detail" else "最近 ${daily.size} 天",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (daily.isEmpty()) {
                Text("暂无每日里程趋势", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                BarChart(daily.takeLast(14))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "日均 ${NumberFormats.distanceKm(daily.map { it.mileage }.average())}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "最高 ${NumberFormats.distanceKm(daily.maxOf { it.mileage })}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "本月 ${NumberFormats.distanceKm(monthMileage)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarChart(records: List<DailyMileageRecord>) {
    val max = records.maxOfOrNull { it.mileage }?.takeIf { it > 0 } ?: 1.0
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        val barWidth = size.width / records.size.coerceAtLeast(1)
        records.forEachIndexed { index, record ->
            val h = (record.mileage / max).toFloat() * size.height
            drawRoundRect(
                color = TeslaGreen,
                topLeft = androidx.compose.ui.geometry.Offset(index * barWidth + 4f, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth - 8f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
            )
        }
    }
}

@Composable
private fun RideDetailDialog(
    viewModel: AppViewModel,
    ride: RideRecord,
    detail: RideDetail?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(ride.id) {
        val dashboard = viewModel.dashboard.value
        val sn = dashboard.primaryVehicle?.vehicle?.sn ?: return@LaunchedEffect
        viewModel.refreshRideDetail(sn, ride.id)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text(
                    "行程详情",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onDismiss) { Text("返回") }
            }
            Text(
                ride.startedAt?.let { NineplusDates.formatRideDate(it) } ?: "行程详情",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                ride.mileage?.let { "${NumberFormats.number(it, 1)} km" } ?: "-- km",
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("里程", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(8.dp))
            DetailRow("开始时间", ride.startedAt?.let { NineplusDates.formatDateTime(it) } ?: "未知")
            DetailRow("结束时间", ride.endedAt?.let { NineplusDates.formatDateTime(it) } ?: "未知")
            DetailRow("时长", ride.durationMinutes?.let { NineplusDates.formatDuration(it) } ?: "未知")
            DetailRow("速度", NumberFormats.speedKmh(ride.speed))
            DetailRow("能耗", NumberFormats.energyWh(ride.energy))
            DetailRow("用电", ride.usedElectricity?.let { "${NumberFormats.number(it, 1)}%" } ?: "未知")
            DetailRow("行程 ID", ride.id)

            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                Text("接口轨迹", fontWeight = FontWeight.SemiBold)
                Text(
                    "详情已加载，轨迹点见下方原始字段。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
