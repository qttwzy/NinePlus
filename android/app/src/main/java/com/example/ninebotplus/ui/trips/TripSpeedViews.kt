package com.example.ninebotplus.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ninebotplus.domain.SpeedSample
import com.example.ninebotplus.domain.TripSpeedAnalysis
import com.example.ninebotplus.domain.TripSpeedProfile
import com.example.ninebotplus.domain.TripSpeedStats
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.ui.theme.TeslaOrange
import com.example.ninebotplus.ui.theme.TeslaRed
import com.example.ninebotplus.util.NumberFormats

/**
 * Green → orange → red by relative speed. Shared by charts, list bars, and the map track.
 */
fun speedAccentColor(relative: Double): Color {
    val t = relative.coerceIn(0.0, 1.0)
    return when {
        t < 0.5 -> lerpColor(TeslaGreen, TeslaOrange, (t / 0.5).toFloat())
        else -> lerpColor(TeslaOrange, TeslaRed, ((t - 0.5) / 0.5).toFloat())
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = 1f,
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (selected) TeslaGreen else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun TripSpeedStatsCard(stats: TripSpeedStats, reportAverageKmh: Double?) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("全程速度", fontWeight = FontWeight.SemiBold)
            Text(
                "${stats.speedSampleCount} 个速度样本 · 停车阈值 ${TripSpeedAnalysis.STOP_SPEED_KMH} km/h",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SpeedStatCell("轨迹均速", NumberFormats.speedKmh(stats.averageKmh))
                SpeedStatCell("行驶均速", NumberFormats.speedKmh(stats.movingAverageKmh))
                SpeedStatCell("最高", NumberFormats.speedKmh(stats.maxKmh))
                SpeedStatCell("中位", NumberFormats.speedKmh(stats.medianKmh))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SpeedStatCell("P90", NumberFormats.speedKmh(stats.p90Kmh))
                SpeedStatCell("最低", NumberFormats.speedKmh(stats.minKmh))
                SpeedStatCell(
                    "停车占比",
                    stats.stopRatio?.let { NumberFormats.number(it * 100.0, 0) + "%" } ?: "--",
                )
                SpeedStatCell(
                    "轨迹里程",
                    stats.totalDistanceMeters?.let {
                        if (it >= 1000) NumberFormats.distanceKm(it / 1000.0) else "${NumberFormats.number(it, 0)} m"
                    } ?: "--",
                )
            }
            if (reportAverageKmh != null && stats.averageKmh != null) {
                val delta = stats.averageKmh - reportAverageKmh
                Spacer(Modifier.height(10.dp))
                Text(
                    "行程卡均速 ${NumberFormats.speedKmh(reportAverageKmh)} · 轨迹均速差 ${"%.1f".format(delta)} km/h",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedStatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SpeedProfileCard(profile: TripSpeedProfile) {
    var byDistance by remember { mutableStateOf(true) }
    val samples = profile.samples.filter { it.speedKmh != null }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("速度曲线", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ModeChip("按里程", byDistance) { byDistance = true }
                Spacer(Modifier.width(6.dp))
                ModeChip("按序号", !byDistance) { byDistance = false }
            }
            Text(
                if (byDistance) "横轴：距起点里程（m）" else "横轴：轨迹点序号",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (samples.isEmpty()) {
                Text("无速度样本", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                SpeedLineChart(samples = samples, byDistance = byDistance)
                Spacer(Modifier.height(8.dp))
                SpeedLegend()
            }
        }
    }
}

@Composable
private fun SpeedLineChart(samples: List<SpeedSample>, byDistance: Boolean) {
    val maxSpeed = samples.mapNotNull { it.speedKmh }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val maxX = if (byDistance) {
        samples.maxOfOrNull { it.cumulativeDistanceMeters }?.takeIf { it > 0 } ?: 1.0
    } else {
        (samples.size - 1).coerceAtLeast(1).toDouble()
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        val left = 4f
        val top = 8f
        val bottom = size.height - 22f
        val right = size.width - 4f
        val chartH = (bottom - top).coerceAtLeast(1f)
        val chartW = (right - left).coerceAtLeast(1f)

        // Horizontal guides at 0 / mid / max.
        listOf(0f, 0.5f, 1f).forEach { frac ->
            val y = bottom - frac * chartH
            drawLine(
                color = Color(0x226B7280),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1f,
            )
        }

        val points = samples.mapIndexed { i, sample ->
            val x = if (byDistance) {
                (sample.cumulativeDistanceMeters / maxX).toFloat()
            } else {
                i / maxX.toFloat()
            }
            val y = ((sample.speedKmh ?: 0.0) / maxSpeed).toFloat().coerceIn(0f, 1f)
            Offset(left + x * chartW, bottom - y * chartH)
        }

        if (points.size >= 2) {
            val fill = Path().apply {
                moveTo(points.first().x, bottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, bottom)
                close()
            }
            drawPath(fill, color = TeslaGreen.copy(alpha = 0.18f))
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(line, color = TeslaGreen, style = Stroke(width = 3f))
        } else if (points.size == 1) {
            drawCircle(color = TeslaGreen, radius = 5f, center = points.first())
        }

        // Axis labels: max speed and distance/end index.
        drawLine(
            color = Color(0x446B7280),
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1f,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            if (byDistance) "0 m" else "点 1",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "峰值 ${NumberFormats.speedKmh(maxSpeed)}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (byDistance) {
                if (maxX >= 1000) NumberFormats.distanceKm(maxX / 1000.0) else "${NumberFormats.number(maxX, 0)} m"
            } else {
                "点 ${samples.size}"
            },
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeedLegend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(0.0, 0.5, 1.0).forEach { t ->
            Box(
                Modifier
                    .width(28.dp)
                    .height(6.dp)
                    .background(speedAccentColor(t), RoundedCornerShape(3.dp)),
            )
        }
        Text("慢 → 快", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SpeedHistogramCard(profile: TripSpeedProfile) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("速度分布", fontWeight = FontWeight.SemiBold)
            Text(
                "每个区间内轨迹点数量占比",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val buckets = profile.histogram
            if (buckets.isEmpty()) {
                Text("无速度样本", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1)
                val maxSpeed = profile.stats.maxKmh ?: 1.0
                buckets.forEach { bucket ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            bucket.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(78.dp),
                        )
                        val rel = TripSpeedAnalysis.relativeSpeed(bucket.fromKmh + 2.5, maxSpeed)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(14.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction = (bucket.count.toFloat() / maxCount).coerceIn(0.02f, 1f))
                                    .height(14.dp)
                                    .background(speedAccentColor(rel), RoundedCornerShape(4.dp)),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${bucket.count} · ${NumberFormats.number(bucket.fraction * 100, 0)}%",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact per-point row: index, speed bar + value, segment distance, optional coords.
 */
@Composable
fun SpeedPointRow(
    sample: SpeedSample,
    maxSpeedKmh: Double?,
    showCoordinates: Boolean = true,
    latitude: Double? = null,
    longitude: Double? = null,
) {
    val speed = sample.speedKmh
    val rel = TripSpeedAnalysis.relativeSpeed(speed, maxSpeedKmh)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${sample.index + 1}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
            Text(
                speed?.let { "%.1f".format(it) } ?: "--",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(40.dp),
            )
            Text("km/h", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            ) {
                if (speed != null && speed > 0.0) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = rel.toFloat().coerceIn(0.03f, 1f))
                            .height(8.dp)
                            .background(speedAccentColor(rel), RoundedCornerShape(4.dp)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                sample.segmentDistanceMeters?.let { "%.0f m".format(it) } ?: "--",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(42.dp),
            )
        }
        if (showCoordinates && latitude != null && longitude != null) {
            Text(
                "%.5f, %.5f".format(latitude, longitude),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp),
            )
        }
    }
}

/** Rounded bar used by mini sparkline-style per-point previews. */
@Composable
fun SpeedBarPreview(speeds: List<Double?>, maxSpeedKmh: Double?) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (speeds.isEmpty()) return@Canvas
        val barWidth = size.width / speeds.size
        val max = (maxSpeedKmh ?: 1.0).coerceAtLeast(1.0)
        speeds.forEachIndexed { index, speed ->
            val rel = ((speed ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
            val h = rel * size.height
            if (h > 0.5f) {
                drawRoundRect(
                    color = speedAccentColor(rel.toDouble()),
                    topLeft = Offset(index * barWidth + 0.5f, size.height - h),
                    size = Size(barWidth.coerceAtLeast(1f) - 1f, h),
                    cornerRadius = CornerRadius(2f),
                )
            }
        }
    }
}
