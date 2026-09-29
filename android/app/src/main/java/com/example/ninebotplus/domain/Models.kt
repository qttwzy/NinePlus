package com.example.ninebotplus.domain

import com.example.ninebotplus.network.JsonValue
import com.example.ninebotplus.util.CoordinateTransform
import com.example.ninebotplus.util.NineplusDates
import com.example.ninebotplus.util.NumberFormats
import java.util.Date

data class ServerConfiguration(
    val baseUrlString: String,
    val bearerToken: String,
    val appSessionToken: String? = null,
) {
    val baseUrl: String?
        get() {
            val trimmed = baseUrlString.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            return withScheme.trimEnd('/')
        }

    val isUsable: Boolean get() = baseUrl != null
}

data class LoginResult(
    val uuid: String? = null,
    val phone: String? = null,
    val areaCode: String? = null,
    val region: String? = null,
    val businessUid: String? = null,
    val accountId: Int? = null,
    val sessionToken: String? = null,
)

data class RefreshEvent(
    val source: String,
    val operation: String,
    val startedAt: Date,
    val endedAt: Date,
    val success: Boolean,
    val message: String? = null,
) {
    val durationSeconds: Double get() = maxOf((endedAt.time - startedAt.time) / 1000.0, 0.0)
}

data class VehicleInfo(
    val sn: String,
    val name: String,
    val model: String,
    val imageUrlString: String? = null,
    val raw: Map<String, JsonValue>? = null,
) {
    val vin: String?
        get() = firstRawString(listOf("vin", "VIN", "vehicle_vin", "vehicleVin", "car_vin", "carVin"))

    val identifierSummaryText: String
        get() {
            val v = vin
            return if (!v.isNullOrEmpty()) "SN $sn · VIN $v" else "SN $sn"
        }

    val authDate: Date?
        get() = firstRawDate(listOf("auth_date", "authDate", "bind_time", "bindTime", "created_at", "createdAt"))

    fun imageUrl(prefersDark: Boolean): String? {
        val preferred = if (prefersDark) {
            listOf("v6_dark_img_url", "v6DarkImgUrl", "dark_img_url", "darkImgUrl")
        } else {
            listOf(
                "v6_light_img_url", "v6LightImgUrl", "light_img_url", "lightImgUrl",
                "img_url", "imgUrl", "img", "image_url", "imageUrl",
            )
        }
        val fallback = if (prefersDark) {
            listOf(
                "v6_light_img_url", "v6LightImgUrl", "img_url", "imgUrl", "img", "image_url", "imageUrl",
            )
        } else {
            listOf("v6_dark_img_url", "v6DarkImgUrl", "dark_img_url", "darkImgUrl")
        }
        return firstRawString(preferred) ?: firstRawString(fallback) ?: imageUrlString
    }

    private fun firstRawString(keys: List<String>): String? {
        val map = raw ?: return null
        for (key in keys) {
            val text = map[key]?.stringValue?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun firstRawDate(keys: List<String>): Date? {
        val map = raw ?: return null
        for (key in keys) {
            val value = map[key] ?: continue
            val number = value.doubleValue ?: continue
            val seconds = if (number > 1_000_000_000_000) number / 1000 else number
            if (seconds > 0) return Date((seconds * 1000).toLong())
        }
        return null
    }
}

enum class VehicleHealthLevel { GOOD, ATTENTION, CRITICAL, CHARGING, UNKNOWN }

enum class BatteryChemistry(val raw: String) {
    AUTO("auto"),
    LITHIUM("lithium"),
    LEAD_ACID("lead_acid");

    val title: String
        get() = when (this) {
            AUTO -> "自动识别"
            LITHIUM -> "锂电池"
            LEAD_ACID -> "铅酸电池"
        }

    val detail: String
        get() = when (this) {
            AUTO -> "仅在接口明确返回类型时自动采用"
            LITHIUM -> "按锂电充电平台期与尾段涓流建模"
            LEAD_ACID -> "按铅酸吸收充电与容量衰减建模"
        }

    companion object {
        fun from(raw: String?): BatteryChemistry =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

data class BatteryChemistryInfo(
    val configured: BatteryChemistry,
    val effective: String,
    val source: String,
    val nominalVoltage: Double? = null,
    val capacityWh: Double? = null,
    val capacityAh: Double? = null,
) {
    val effectiveTitle: String
        get() = when (effective) {
            BatteryChemistry.LITHIUM.raw -> "锂电池"
            BatteryChemistry.LEAD_ACID.raw -> "铅酸电池"
            else -> "待选择"
        }

    val specificationText: String get() {
        val parts = buildList {
            nominalVoltage?.let { add("${NumberFormats.number(it, 1)} V") }
            capacityWh?.let { add("${NumberFormats.number(it, 1)} Wh") }
        }
        return if (parts.isEmpty()) "未填写电压与容量" else parts.joinToString(" · ")
    }
}

data class VehicleHealth(
    val level: VehicleHealthLevel,
    val title: String,
    val message: String,
)

data class ServerRangePrediction(
    val estimatedRange: Double? = null,
    val localRange: Double? = null,
    val officialRange: Double? = null,
    val source: String? = null,
    val kmPerPercent: Double? = null,
    val estimatedFullRange: Double? = null,
    val sampleCount: Int? = null,
    val totalUsedPercent: Double? = null,
    val accuracyPercent: Double? = null,
    val confidencePercent: Double? = null,
    val accuracySource: String? = null,
    val measuredSampleCount: Int? = null,
    val isReady: Boolean? = null,
)

data class ServerChargingPrediction(
    val isCharging: Boolean? = null,
    val remainingMinutes: Double? = null,
    val estimatedFullAt: Date? = null,
    val fastMinutesPerPercent: Double? = null,
    val taperMinutesPerPercent: Double? = null,
    val sampleCount: Int? = null,
    val accuracyPercent: Double? = null,
    val confidencePercent: Double? = null,
    val accuracySource: String? = null,
    val measuredSampleCount: Int? = null,
    val isReady: Boolean? = null,
    val estimatedSpeedKmh: Double? = null,
)

data class ServerPrediction(
    val modelVersion: String? = null,
    val updatedAt: Date? = null,
    val batteryPercent: Double? = null,
    val batteryChemistry: BatteryChemistryInfo? = null,
    val range: ServerRangePrediction,
    val charging: ServerChargingPrediction,
)

data class RideRecord(
    val id: String,
    val startedAt: Date? = null,
    val endedAt: Date? = null,
    val mileage: Double? = null,
    val energy: Double? = null,
    val usedElectricity: Double? = null,
    val durationMinutes: Double? = null,
    val speed: Double? = null,
    val raw: Map<String, JsonValue>? = null,
) {
    val stableIdentityKey: String
        get() {
            firstRawText(listOf("travel_id", "travelId"))?.let { return "travel:$it" }
            firstRawText(listOf("ride_id", "rideId", "record_id", "recordId", "id"))?.let { return "id:$it" }
            firstRawText(
                listOf(
                    "start_time", "startTime", "begin_time", "beginTime",
                    "stime", "date", "day", "create_time", "createTime",
                ),
            )?.let { start ->
                val end = firstRawText(
                    listOf("end_time", "endTime", "stop_time", "stopTime", "etime", "finish_time", "finishTime"),
                ) ?: "none"
                val km = firstRawText(listOf("mileages", "mileage", "distance", "rideMileage"))
                    ?: metricText(mileage, 100)
                val used = firstRawText(
                    listOf("used_electricity", "usedElectricity", "usedElectric", "useElectricity"),
                ) ?: metricText(usedElectricity, 100)
                return "raw:start=$start|end=$end|km=$km|used=$used"
            }
            val started = startedAt
            if (started != null) {
                return listOf(
                    "start:${(started.time / 1000)}",
                    "end:${endedAt?.let { it.time / 1000 } ?: "none"}",
                    "km:${metricText(mileage, 100)}",
                    "used:${metricText(usedElectricity, 100)}",
                ).joinToString("|")
            }
            return listOf(
                "fallback:$id",
                "km:${metricText(mileage, 100)}",
                "energy:${metricText(energy, 10)}",
                "used:${metricText(usedElectricity, 100)}",
                "duration:${metricText(durationMinutes, 10)}",
                "speed:${metricText(speed, 10)}",
            ).joinToString("|")
        }

    private fun firstRawText(keys: List<String>): String? {
        val map = raw ?: return null
        for (key in keys) {
            val text = map[key]?.stringValue?.trim()
            if (!text.isNullOrEmpty()) return "$key=$text"
        }
        return null
    }

    private fun metricText(value: Double?, scale: Int): String {
        if (value == null) return "none"
        return ((value * scale.toDouble()).toInt()).toString()
    }
}

data class TravelPage(
    val month: String,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val hasMore: Boolean,
    val records: List<RideRecord>,
    val raw: JsonValue,
)

data class DailyMileageRecord(
    val id: String,
    val day: Int,
    val date: Date?,
    val mileage: Double,
)

data class RideTrackPoint(
    val id: String,
    val date: Date,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val accelerationG: Double,
    val horizontalAccuracy: Double?,
)

data class RecordedRide(
    val id: String,
    val vehicleSn: String?,
    val associatedRideId: String? = null,
    val startedAt: Date,
    val endedAt: Date,
    val distanceMeters: Double,
    val maxSpeedKmh: Double,
    val averageSpeedKmh: Double,
    val maxAccelerationG: Double,
    val points: List<RideTrackPoint>,
) {
    val durationSeconds: Double get() = maxOf((endedAt.time - startedAt.time) / 1000.0, 0.0)

    val displayDistanceMeters: Double
        get() {
            val recalculated = recalculatedDistanceMeters(points)
            return if (recalculated > 0) recalculated else distanceMeters
        }

    val distanceKilometers: Double get() = displayDistanceMeters / 1000

    val validPoints: List<RideTrackPoint>
        get() = points.sortedBy { it.date }.filter {
            it.latitude in -90.0..90.0 &&
                it.longitude in -180.0..180.0 &&
                (it.horizontalAccuracy ?: 0.0) <= 120
        }

    val mapPoints: List<CoordinateTransform.LatLng>
        // Raw WGS-84. The map layer is the single owner of the GCJ-02 transform.
        get() = validPoints.map { CoordinateTransform.LatLng(it.latitude, it.longitude) }

    companion object {
        fun recalculatedDistanceMeters(points: List<RideTrackPoint>): Double {
            val sorted = points.sortedBy { it.date }
            if (sorted.size <= 1) return 0.0
            var total = 0.0
            var previous: RideTrackPoint? = null
            for (point in sorted) {
                if (point.latitude !in -90.0..90.0 ||
                    point.longitude !in -180.0..180.0 ||
                    (point.horizontalAccuracy ?: 0.0) > 120
                ) {
                    continue
                }
                val prev = previous
                if (prev != null) {
                    val deltaTime = (point.date.time - prev.date.time) / 1000.0
                    val distance = CoordinateTransform.distanceMeters(
                        CoordinateTransform.LatLng(prev.latitude, prev.longitude),
                        CoordinateTransform.LatLng(point.latitude, point.longitude),
                    )
                    if (deltaTime in 0.0..30.0 && distance in 0.0..300.0) {
                        total += distance
                    }
                }
                previous = point
            }
            return total
        }
    }
}

data class VehicleState(
    val battery: Int? = null,
    val batteryVoltage: Double? = null,
    val batteryTemperature: Double? = null,
    val batteryCycleCount: Int? = null,
    val chargingPower: Double? = null,
    val endurance: Double? = null,
    val aiEstimatedMileage: Double? = null,
    val isCharging: Boolean? = null,
    val isPoweredOn: Boolean? = null,
    val isLocked: Boolean? = null,
    val remainingChargeTime: Double? = null,
    val locationDescription: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val totalMileage: Double? = null,
    val monthMileage: Double? = null,
    val monthEnergy: Double? = null,
    val monthUsedElectricity: Double? = null,
    val lastMileage: Double? = null,
    val lastEnergy: Double? = null,
    val lastUsedElectricity: Double? = null,
    val rideRecords: List<RideRecord>? = null,
    val dailyMileageRecords: List<DailyMileageRecord>? = null,
    val updatedAt: Date,
    val rawStatus: Map<String, JsonValue>? = null,
    val rawTravel: Map<String, JsonValue>? = null,
    val rawBattery: Map<String, JsonValue>? = null,
    val serverPrediction: ServerPrediction? = null,
) {
    val batteryText: String get() = battery?.let { "$it%" } ?: "--%"

    val batteryFraction: Double
        get() = ((battery ?: 0).toDouble() / 100.0).coerceIn(0.0, 1.0)

    val isFullyCharged: Boolean get() = (battery ?: 0) >= 100

    val officialEstimatedMileage: Double?
        get() = endurance?.let { maxOf(it, 0.0) }

    val localEstimatedMileage: Double?
        get() {
            val server = serverPrediction?.range?.estimatedRange
            if (server != null && server >= 0) return server
            return officialEstimatedMileage
        }

    val usesServerAlgorithmEstimate: Boolean
        get() {
            val range = serverPrediction?.range?.estimatedRange ?: return false
            return range >= 0
        }

    val predictionModelTitle: String
        get() = if (usesServerAlgorithmEstimate) "算法预估" else "官方预估"

    val localEstimatedMileageText: String
        get() = localEstimatedMileage?.let { "${NumberFormats.number(it, 1)} km" } ?: "-- km"

    val officialEstimatedMileageText: String
        get() = officialEstimatedMileage?.let { "${NumberFormats.number(it, 1)} km" } ?: "接口未返回"

    val enduranceText: String
        get() = NumberFormats.distanceKm(localEstimatedMileage ?: endurance)

    val chargingStateText: String
        get() = when {
            isFullyCharged -> "已充满"
            isCharging == null -> "未知"
            isCharging -> "充电中"
            else -> "未充电"
        }

    val powerText: String
        get() = when {
            isFullyCharged -> "已充满"
            isCharging == true -> "充电中"
            isPoweredOn == null -> "离线"
            isPoweredOn -> "已上电"
            else -> "已熄火"
        }

    val lockText: String
        get() = when (isLocked) {
            null -> "未知"
            true -> "已锁"
            false -> "未锁"
        }

    val primaryStatusText: String get() = health.title

    val monthMileageText: String get() = NumberFormats.distanceKm(monthMileage)

    val totalMileageText: String get() = NumberFormats.distanceKm(totalMileage)

    val todayMileage: Double?
        get() {
            val fromDaily = dailyMileages.lastOrNull { record ->
                val date = record.date
                date != null && NineplusDates.monthString(date) == NineplusDates.monthString(updatedAt) &&
                    isSameDay(date, updatedAt)
            }?.mileage
            if (fromDaily != null) return fromDaily
            val day = java.util.Calendar.getInstance(NineplusDates.chinaTimeZone).apply {
                time = updatedAt
            }.get(java.util.Calendar.DAY_OF_MONTH)
            return dailyMileages.lastOrNull { it.day == day }?.mileage
        }

    val todayMileageText: String get() = NumberFormats.distanceKm(todayMileage)

    val dailyAverageMileage: Double?
        get() {
            val mileage = monthMileage ?: return null
            val day = maxOf(
                java.util.Calendar.getInstance(NineplusDates.chinaTimeZone).apply { time = updatedAt }
                    .get(java.util.Calendar.DAY_OF_MONTH),
                1,
            )
            return mileage / day
        }

    val dailyAverageMileageText: String
        get() = dailyAverageMileage?.let { "${NumberFormats.number(it, 1)} km/日" } ?: "-- km/日"

    val averageSpeed: Double?
        get() {
            val samples = rides.mapNotNull { rideSpeed(it) }
            if (samples.isEmpty()) return null
            return samples.sum() / samples.size
        }

    val averageSpeedText: String get() = NumberFormats.speedKmh(averageSpeed)

    val lastRideSummaryText: String get() {
        val mileage = lastMileage?.let { "${NumberFormats.number(it, 1)} km" } ?: "-- km"
        val energy = lastEnergy?.let { "${NumberFormats.number(it, 0)} Wh" } ?: "-- Wh"
        return "$mileage · $energy"
    }

    val monthEnergyPerKm: Double?
        get() {
            val mileage = monthMileage ?: return null
            if (mileage <= 0) return null
            val energy = monthUsedElectricity ?: monthEnergy ?: return null
            return energy / mileage
        }

    val monthEnergyPerKmText: String
        get() = monthEnergyPerKm?.let { "${NumberFormats.number(it, 1)} Wh/km" } ?: "-- Wh/km"

    val remainingChargeTimeText: String
        get() {
            val minutes = serverRemainingChargeMinutes ?: remainingChargeTime ?: return "未知"
            return NineplusDates.formatDuration(minutes)
        }

    val estimatedFullChargeMinutes: Double?
        get() {
            if (isCharging != true) return null
            serverRemainingChargeMinutes?.let { return maxOf(it, 0.0) }
            val level = (battery ?: return remainingChargeTime).toDouble().coerceIn(0.0, 100.0)
            if (level >= 100) return 0.0
            val fast = (FAST_CHARGE_UPPER - level.coerceAtMost(FAST_CHARGE_UPPER)).coerceAtLeast(0.0)
            val taper = (100 - level.coerceAtLeast(FAST_CHARGE_UPPER)).coerceAtLeast(0.0)
            val minutes = fast * FAST_MINUTES_PER_PERCENT + taper * TAPER_MINUTES_PER_PERCENT
            return Math.ceil(minutes / 5) * 5
        }

    val estimatedFullChargeTimeText: String
        get() = when {
            isCharging != true -> "未充电"
            estimatedFullChargeMinutes == null -> "计算中"
            estimatedFullChargeMinutes!! <= 0 -> "已充满"
            else -> NineplusDates.formatDuration(estimatedFullChargeMinutes!!)
        }

    val estimatedFullChargeClockText: String
        get() {
            if (isCharging != true || estimatedFullChargeMinutes == null) return "--"
            val minutes = estimatedFullChargeMinutes!!
            if (minutes <= 0) return "已充满"
            val fullAt = serverPrediction?.charging?.estimatedFullAt
                ?: Date(updatedAt.time + (minutes * 60_000).toLong())
            return NineplusDates.formatTime(fullAt)
        }

    val estimatedChargeTo80Minutes: Double?
        get() {
            if (isCharging != true) return null
            val level = (battery ?: return null).toDouble().coerceIn(0.0, 100.0)
            if (level >= 80) return 0.0
            val minutes = (80 - level) * (serverPrediction?.charging?.fastMinutesPerPercent ?: FAST_MINUTES_PER_PERCENT)
            return Math.ceil(minutes / 5) * 5
        }

    val estimatedChargeTo80TimeText: String
        get() = when {
            isCharging != true -> "未充电"
            estimatedChargeTo80Minutes == null -> "计算中"
            estimatedChargeTo80Minutes!! <= 0 -> "已超过 80%"
            else -> NineplusDates.formatDuration(estimatedChargeTo80Minutes!!)
        }

    val chargeSummaryText: String
        get() = when {
            isFullyCharged -> "已充满"
            isCharging == null -> "充电未知"
            isCharging -> "充电中 · 约 $estimatedFullChargeTimeText 充满"
            else -> "未充电"
        }

    val rangePerBatteryPercent: Double?
        get() {
            if (usesServerAlgorithmEstimate) {
                val server = serverPrediction?.range?.kmPerPercent
                if (server != null && server > 0) return server
            }
            val level = battery ?: return null
            if (level <= 0) return null
            val end = endurance ?: return null
            return maxOf(end, 0.0) / level
        }

    val rangePerBatteryPercentText: String
        get() = rangePerBatteryPercent?.let { "${NumberFormats.number(it, 2)} km/%" } ?: "-- km/%"

    val rangeEstimateAccuracy: Double?
        get() = serverPrediction?.range?.accuracyPercent?.let { (it / 100.0).coerceIn(0.0, 1.0) }

    val rangeEstimateAccuracyText: String
        get() = rangeEstimateAccuracy?.let { "${NumberFormats.number(it * 100, 0)}%" } ?: "样本不足"

    val observedRangeSampleCount: Int
        get() = serverPrediction?.range?.sampleCount?.takeIf { it > 0 } ?: 0

    val rangeEstimateAccuracyDetailText: String
        get() {
            val count = serverPrediction?.range?.sampleCount
            if (count != null && count > 0) {
                if (serverPrediction?.range?.accuracySource == "measured") {
                    val verified = serverPrediction?.range?.measuredSampleCount ?: count
                    return "实测预测误差 · $verified 次已验证行程"
                }
                return "算法服务端 · $count 次有效行程"
            }
            return if (serverPrediction == null) "服务端未返回算法指标" else "服务端样本不足"
        }

    val rangeModelInsightText: String
        get() = when {
            usesServerAlgorithmEstimate && serverPrediction?.range?.source == "default" ->
                "服务端样本不足，当前使用默认算法估算。"
            usesServerAlgorithmEstimate && (rangeEstimateAccuracy ?: 0.0) >= 0.82 ->
                "服务端近期样本稳定，估算可信。"
            usesServerAlgorithmEstimate ->
                "服务端已根据近期行程持续校准。"
            serverPrediction != null ->
                "服务端未给出可用算法续航，当前显示官方预估。"
            else ->
                "服务端未返回算法预测，当前显示官方预估。"
        }

    val localEstimateBasisText: String
        get() {
            val prediction = serverPrediction
            val estimated = prediction?.range?.estimatedRange
            if (prediction != null && estimated != null && estimated >= 0) {
                val sampleText = prediction.range.sampleCount?.let { "$it 次有效行程" } ?: "历史样本"
                return when (prediction.range.source) {
                    "personalized", "personalized_blend" ->
                        "算法服务端结合官方预估和 $sampleText 持续校准。"
                    else ->
                        "服务端默认算法基于 $sampleText 计算。"
                }
            }
            return "服务端未返回算法预测，当前显示官方预估。"
        }

    val batteryVoltageText: String
        get() = batteryVoltage?.let { "${NumberFormats.number(it, 1)} V" } ?: "接口未返回"

    val batteryTemperatureText: String
        get() = batteryTemperature?.let { "${NumberFormats.number(it, 1)} °C" } ?: "接口未返回"

    val batteryCycleCountText: String
        get() = batteryCycleCount?.let { "$it 次" } ?: "接口未返回"

    val chargingPowerText: String
        get() = chargingPower?.let { "${NumberFormats.number(it, 0)} W" } ?: "接口未返回"

    val locationText: String get() {
        val desc = locationDescription
        return if (desc.isNullOrEmpty()) "未知位置" else desc
    }

    val coordinateText: String?
        get() {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            return "${NumberFormats.coordinate(lat)}, ${NumberFormats.coordinate(lon)}"
        }

    val rides: List<RideRecord>
        get() = deduplicatedRideRecords(rideRecords ?: emptyList())

    val dailyMileages: List<DailyMileageRecord>
        get() = dailyMileageRecords ?: emptyList()

    val health: VehicleHealth
        get() = when {
            isFullyCharged -> VehicleHealth(
                VehicleHealthLevel.GOOD,
                "已充满",
                "电量已满，可以拔掉充电器",
            )
            isCharging == true -> VehicleHealth(
                VehicleHealthLevel.CHARGING,
                "充电中",
                "约 $estimatedFullChargeTimeText 充满，$estimatedFullChargeClockText 左右",
            )
            battery != null && battery < 15 -> VehicleHealth(
                VehicleHealthLevel.CRITICAL,
                "低电量",
                "当前 $battery%，建议尽快充电",
            )
            isLocked == false -> VehicleHealth(
                VehicleHealthLevel.ATTENTION,
                "未锁车",
                "车辆未锁定，请确认停放环境",
            )
            battery != null && battery < 25 -> VehicleHealth(
                VehicleHealthLevel.ATTENTION,
                "电量偏低",
                "当前 $battery%，续航约 $enduranceText",
            )
            isLocked == true || isPoweredOn == false -> VehicleHealth(
                VehicleHealthLevel.GOOD,
                "状态正常",
                "车辆已停放，续航约 $enduranceText",
            )
            else -> VehicleHealth(
                VehicleHealthLevel.UNKNOWN,
                "状态未知",
                "部分车况字段暂未返回",
            )
        }

    val warningTexts: List<String>
        get() = buildList {
            val level = battery
            if (level != null && level < 15) add("电量低于 15%，建议尽快充电")
            else if (level != null && level < 25) add("电量偏低，出门前建议确认续航")
            if (isPoweredOn == false) add("上电状态为 0，请确认车辆电源")
            if (isLocked == false) add("车辆当前未锁车")
        }

    private val serverRemainingChargeMinutes: Double?
        get() {
            val charging = serverPrediction?.charging ?: return null
            charging.estimatedFullAt?.let { return maxOf((it.time - System.currentTimeMillis()) / 60_000.0, 0.0) }
            return charging.remainingMinutes
        }

    private fun rideSpeed(ride: RideRecord): Double? {
        val speed = ride.speed
        if (speed != null && speed > 0) return speed
        val mileage = ride.mileage ?: return null
        val duration = ride.durationMinutes ?: return null
        if (mileage <= 0 || duration <= 0) return null
        return mileage / (duration / 60)
    }

    private fun isSameDay(a: Date, b: Date): Boolean {
        val ca = java.util.Calendar.getInstance(NineplusDates.chinaTimeZone).apply { time = a }
        val cb = java.util.Calendar.getInstance(NineplusDates.chinaTimeZone).apply { time = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    companion object {
        private const val FAST_CHARGE_UPPER = 80.0
        private const val FAST_MINUTES_PER_PERCENT = 4.0
        private const val TAPER_MINUTES_PER_PERCENT = 7.0

        private fun deduplicatedRideRecords(records: List<RideRecord>): List<RideRecord> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<RideRecord>()
            for (record in records) {
                val key = record.stableIdentityKey
                if (seen.add(key)) result += record
            }
            return result
        }
    }
}

data class VehicleHistoryPoint(
    val id: String,
    val sn: String,
    val date: Date,
    val battery: Int? = null,
    val endurance: Double? = null,
    val totalMileage: Double? = null,
    val isCharging: Boolean? = null,
    val isLocked: Boolean? = null,
    val isPoweredOn: Boolean? = null,
) {
    companion object {
        fun from(sn: String, state: VehicleState): VehicleHistoryPoint =
            VehicleHistoryPoint(
                id = "$sn-${state.updatedAt.time / 1000}",
                sn = sn,
                date = state.updatedAt,
                battery = state.battery,
                endurance = state.endurance,
                totalMileage = state.totalMileage,
                isCharging = state.isCharging,
                isLocked = state.isLocked,
                isPoweredOn = state.isPoweredOn,
            )
    }
}

data class ResolvedAddress(
    val sn: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Date,
    val source: String? = null,
)

data class VehicleSnapshot(
    val vehicle: VehicleInfo,
    val state: VehicleState,
)

data class Dashboard(
    val vehicles: List<VehicleSnapshot>,
    val selectedSn: String?,
    val updatedAt: Date,
) {
    val primaryVehicle: VehicleSnapshot?
        get() = selectedSn?.let { sn -> vehicles.firstOrNull { it.vehicle.sn == sn } }
            ?: vehicles.firstOrNull()

    companion object {
        val empty = Dashboard(emptyList(), null, Date(0))
    }
}

data class RideDetail(
    val vehicleSn: String,
    val rideId: String,
    val fetchedAt: Date,
    val raw: JsonValue,
    val parsedRecord: RideRecord? = null,
)

/**
 * Decides which engine command is the next useful action.
 *
 * Real vehicle telemetry: a **locked** Ninebot e-bike can still report
 * `pwr=1` (ECU awake / BLE connected). Driving the button from `pwr` alone
 * then shows 「熄火」 on a parked locked vehicle, which is wrong.
 *
 * Order:
 * 1. lock state (what the user sees on the bike)
 * 2. power state (fallback when lock is unknown)
 * 3. unknown → no dangerous command
 */
object PowerActionDecision {
    enum class Choice { ENGINE_START, ENGINE_STOP, NONE }

    fun decide(isLocked: Boolean?, isPoweredOn: Boolean?): Choice = when {
        isLocked == true -> Choice.ENGINE_START
        isLocked == false -> Choice.ENGINE_STOP
        isPoweredOn == true -> Choice.ENGINE_STOP
        isPoweredOn == false -> Choice.ENGINE_START
        else -> Choice.NONE
    }

    fun label(choice: Choice): String = when (choice) {
        Choice.ENGINE_START -> "上电"
        Choice.ENGINE_STOP -> "熄火"
        Choice.NONE -> "电源未知"
    }

    fun action(choice: Choice): VehicleAction? = when (choice) {
        Choice.ENGINE_START -> VehicleAction.ENGINE_START
        Choice.ENGINE_STOP -> VehicleAction.ENGINE_STOP
        Choice.NONE -> null
    }
}

data class VehicleAction(
    val id: String,
    val title: String,
    val resultTitle: String,
    val loadingTitle: String,
    val subtitle: String,
    val confirmationTitle: String,
    val confirmationMessage: String,
    val isDangerous: Boolean,
) {
    companion object {
        val BELL = VehicleAction(
            id = "bell",
            title = "寻车铃",
            resultTitle = "寻车铃已发送",
            loadingTitle = "正在寻车鸣笛",
            subtitle = "让车辆发出提示音",
            confirmationTitle = "发送寻车铃？",
            confirmationMessage = "车辆会发出提示音。",
            isDangerous = false,
        )
        val OPEN_BUCKET = VehicleAction(
            id = "openBucket",
            title = "开座桶",
            resultTitle = "开座桶指令已发送",
            loadingTitle = "正在打开座桶",
            subtitle = "打开座桶",
            confirmationTitle = "打开座桶？",
            confirmationMessage = "座桶会被打开，请确认车辆在你身边。",
            isDangerous = true,
        )
        val ENGINE_START = VehicleAction(
            id = "engineStart",
            title = "上电",
            resultTitle = "上电指令已发送",
            loadingTitle = "正在上电",
            subtitle = "车辆进入可骑行状态",
            confirmationTitle = "车辆上电？",
            confirmationMessage = "车辆会进入上电状态，请确认车辆在你身边。",
            isDangerous = true,
        )
        val ENGINE_STOP = VehicleAction(
            id = "engineStop",
            title = "熄火",
            resultTitle = "熄火指令已发送",
            loadingTitle = "正在熄火",
            subtitle = "关闭电源",
            confirmationTitle = "车辆熄火？",
            confirmationMessage = "车辆会进入熄火状态，请确认不会影响当前骑行。",
            isDangerous = true,
        )

        val all = listOf(BELL, OPEN_BUCKET, ENGINE_START, ENGINE_STOP)
    }
}

data class DiagnosticsSnapshot(
    val hasConfiguration: Boolean,
    val serverText: String,
    val accountText: String,
    val vehicleCount: Int,
    val selectedVehicleName: String,
    val dashboardUpdatedAt: Date?,
    val lastAppRefreshEvent: RefreshEvent?,
    val lastWidgetRefreshEvent: RefreshEvent?,
    val lastError: String?,
    val interfaceRideCount: Int,
    val historyPointCount: Int,
    val recordedRideCount: Int,
    val rideDetailCount: Int,
    val resolvedAddressCount: Int,
    val dashboardCacheBytes: Int,
)
