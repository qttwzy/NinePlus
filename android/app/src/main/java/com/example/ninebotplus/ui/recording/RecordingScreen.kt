package com.example.ninebotplus.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ninebotplus.domain.RecordedRide
import com.example.ninebotplus.location.RideRecorder
import com.example.ninebotplus.ui.AppViewModel
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.ui.theme.TeslaRed
import com.example.ninebotplus.util.NineplusDates
import com.example.ninebotplus.util.NumberFormats

@Composable
fun RecordingScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val recordedRides by viewModel.recordedRides.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    val recorder = remember { RideRecorder.get(context) }
    val session by recorder.session.collectAsState()

    var permissionAsked by remember { mutableStateOf(false) }
    var selectedRide by remember { mutableStateOf<RecordedRide?>(null) }
    var deleteCandidate by remember { mutableStateOf<RecordedRide?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            recorder.startPreview()
        }
    }

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    selectedRide?.let { ride ->
        RecordedRideDetail(ride = ride, onDismiss = { selectedRide = null }, onDelete = {
            deleteCandidate = ride
            selectedRide = null
        })
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            session.isRecording -> "正在记录速度、G 值和轨迹"
                            !hasLocationPermission -> "需要定位权限才能显示实时位置"
                            else -> "当前位置实时显示，点击开始记录"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(recording = session.isRecording)
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpeedGauge(speed = session.speedKmh, maxSpeed = session.maxSpeedKmh)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (session.isRecording) "REC" else "READY",
                        color = if (session.isRecording) TeslaRed else TeslaGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    if (!hasLocationPermission) {
                        permissionAsked = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                        return@Button
                    }
                    if (session.isRecording) {
                        val ride = recorder.stop(
                            vehicleSn = dashboard.primaryVehicle?.vehicle?.sn,
                        )
                        if (ride != null) viewModel.saveRecordedRide(ride)
                    } else {
                        recorder.start(vehicleSn = dashboard.primaryVehicle?.vehicle?.sn)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (session.isRecording) TeslaRed else TeslaGreen,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(
                    if (session.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (session.isRecording) "结束记录" else "开始记录",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MetricCell("当前 G", "%.2f G".format(session.accelerationG))
                    MetricCell("最大 G", "%.2f G".format(session.maxAccelerationG))
                    MetricCell("距离", NumberFormats.distanceKm(session.distanceMeters / 1000, 2))
                    MetricCell("时长", NineplusDates.formatClockDuration(session.durationSeconds))
                }
            }
        }

        item {
            Text("最近记录", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }

        if (recordedRides.isEmpty()) {
            item {
                Text(
                    "结束一次记录后会出现在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            items(recordedRides.take(5)) { ride ->
                RideHistoryRow(
                    ride = ride,
                    onClick = { selectedRide = ride },
                    onDelete = { deleteCandidate = ride },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    deleteCandidate?.let { ride ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除这条记录？") },
            text = {
                Text(
                    "${NineplusDates.formatShortDateTime(ride.startedAt)} · " +
                        NumberFormats.distanceKm(ride.distanceKilometers, 2),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecordedRide(ride.id)
                        deleteCandidate = null
                    },
                ) { Text("删除记录", color = TeslaRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusPill(recording: Boolean) {
    Text(
        if (recording) "REC" else "READY",
        color = if (recording) TeslaRed else TeslaGreen,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(
                (if (recording) TeslaRed else TeslaGreen).copy(alpha = 0.12f),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SpeedGauge(speed: Double, maxSpeed: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(180.dp)) {
            val stroke = 14.dp.toPx()
            drawArc(
                color = TeslaGreen.copy(alpha = 0.2f),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            val fraction = (speed / 132.0).toFloat().coerceIn(0f, 1f)
            drawArc(
                color = TeslaGreen,
                startAngle = 150f,
                sweepAngle = 240f * fraction,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Text(
            "${NumberFormats.number(speed, 1)}",
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text("km/h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "MAX ${NumberFormats.number(maxSpeed, 1)} km/h",
            fontSize = 12.sp,
            color = TeslaRed,
        )
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RideHistoryRow(ride: RecordedRide, onClick: () -> Unit, onDelete: () -> Unit) {
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
                    NineplusDates.formatShortDateTime(ride.startedAt),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (ride.associatedRideId != null) "已关联行程" else "未关联行程",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    NumberFormats.distanceKm(ride.distanceKilometers, 2),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "最快 ${NumberFormats.number(ride.maxSpeedKmh, 1)} km/h",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = TeslaRed)
            }
        }
    }
}

@Composable
private fun RecordedRideDetail(ride: RecordedRide, onDismiss: () -> Unit, onDelete: () -> Unit) {
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
                    "记录详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("返回") }
            }
            Text(
                "${NineplusDates.formatShortDateTime(ride.startedAt)} - ${NineplusDates.formatShortDateTime(ride.endedAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                NumberFormats.distanceKm(ride.distanceKilometers, 2),
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCell("时长", NineplusDates.formatClockDuration(ride.durationSeconds))
                MetricCell("均速", NumberFormats.speedKmh(ride.averageSpeedKmh))
                MetricCell("最快", NumberFormats.speedKmh(ride.maxSpeedKmh))
                MetricCell("最大 G", "%.2f G".format(ride.maxAccelerationG))
            }
            Text(
                "轨迹点 ${ride.points.size} 个",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (ride.associatedRideId != null) {
                    "已关联接口行程 · ${ride.associatedRideId}"
                } else {
                    "未关联接口行程"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除这条记录", color = TeslaRed)
            }
        }
    }
}
