package com.example.ninebotplus.network

import com.example.ninebotplus.domain.BatteryChemistry
import com.example.ninebotplus.domain.BatteryChemistryInfo
import com.example.ninebotplus.domain.DailyMileageRecord
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.RideDetail
import com.example.ninebotplus.domain.RideRecord
import com.example.ninebotplus.domain.ServerChargingPrediction
import com.example.ninebotplus.domain.ServerPrediction
import com.example.ninebotplus.domain.ServerRangePrediction
import com.example.ninebotplus.domain.TravelPage
import com.example.ninebotplus.domain.VehicleInfo
import com.example.ninebotplus.domain.VehicleSnapshot
import com.example.ninebotplus.domain.VehicleState
import com.example.ninebotplus.network.JsonLookup.firstArrayObject
import com.example.ninebotplus.network.JsonLookup.firstBoolLike
import com.example.ninebotplus.network.JsonLookup.firstDouble
import com.example.ninebotplus.network.JsonLookup.firstInt
import com.example.ninebotplus.network.JsonLookup.firstObject
import com.example.ninebotplus.network.JsonLookup.firstString
import com.example.ninebotplus.network.JsonLookup.payloadObject
import com.example.ninebotplus.util.JsonDateInput
import com.example.ninebotplus.util.NineplusDates
import java.util.Date

/**
 * Tolerant parsers for NinePlus Platform / Ninebot payloads.
 *
 * Historical context: the server forwards heterogeneous Ninebot cloud fields
 * (snake_case and camelCase) and nested envelopes. Keeping this logic in ONE
 * place (and covering it with fixtures) is better than duplicating fragile
 * parsing in every screen. Mid-term the server should normalize; until then
 * this layer is the compatibility boundary.
 */
object PayloadParser {

    fun unwrapEnvelope(root: JsonValue): JsonValue {
        val obj = root.objectValue ?: return root
        if (!obj.containsKey("ok")) return root
        if (obj["ok"]?.boolValue == true) {
            return obj["data"] ?: JsonValue.Obj(emptyMap())
        }
        val error = obj["error"]?.objectValue
        val message = error?.let { firstString(listOf("message", "code"), it) }
            ?: "NinePlus 服务器请求失败"
        throw ApiException.Server(message)
    }

    fun errorMessage(data: String?): String {
        if (data.isNullOrBlank()) return ""
        val root = try {
            val element = NinePlusJson.parseToJsonElement(data)
            JsonValue.from(element)
        } catch (_: Exception) {
            return data
        }
        val obj = root.objectValue ?: return data
        obj["error"]?.objectValue?.let { error ->
            firstString(listOf("message", "code"), error)?.let { return it }
        }
        return firstString(listOf("message"), obj) ?: ""
    }

    fun loginResult(value: JsonValue): LoginResult {
        val obj = value.objectValue ?: emptyMap()
        return LoginResult(
            uuid = firstString(listOf("uuid"), obj),
            phone = firstString(listOf("phone"), obj),
            areaCode = firstString(listOf("area_code"), obj),
            region = firstString(listOf("region"), obj),
            businessUid = firstString(listOf("business_uid"), obj),
            accountId = firstInt(listOf("account_id", "id"), obj),
            sessionToken = firstString(listOf("session_token", "sessionToken"), obj),
        )
    }

    fun arrayPayload(value: JsonValue, preferredKeys: List<String>): List<JsonValue> {
        value.arrayValue?.let { return it }
        val obj = value.objectValue ?: return emptyList()
        for (key in preferredKeys) {
            obj[key]?.arrayValue?.let { return it }
        }
        return emptyList()
    }

    fun hasVehicleStatus(value: JsonValue?): Boolean {
        val root = value?.objectValue ?: return false
        val obj = payloadObject(root, listOf("status", "vehicle_status", "vehicleStatus", "data"))
        if (obj.isEmpty()) return false
        return firstDouble(
            listOf(
                "dump_energy", "dumpEnergy", "precise_estimate_mileage", "preciseEstimateMileage",
                "estimate_mileage", "estimateMileage", "pwr", "charging", "lock_status", "lockStatus",
            ),
            listOf(obj, root),
        ) != null || firstObject(listOf("loc", "locationInfo"), obj) != null
    }

    fun hasBatteryData(value: JsonValue?): Boolean {
        val root = value?.objectValue ?: return false
        val obj = payloadObject(root, listOf("battery", "batteryInfo", "battery_info", "data"))
        if (obj.isEmpty()) return false
        return firstDouble(
            listOf(
                "electricity", "dump_energy", "dumpEnergy", "battery_voltage", "batteryVoltage",
                "bms_volt", "bmsVolt", "bat_temp", "batt_temp", "charging_power", "chargingPower",
            ),
            listOf(obj, root),
        ) != null || firstArrayObject(listOf("battery_list", "batteryList", "batteries"), obj) != null
    }

    fun vehicleInfo(value: JsonValue): VehicleInfo? {
        val obj = value.objectValue ?: return null
        val sn = firstString(listOf("wnumber", "sn"), obj)?.takeIf { it.isNotEmpty() } ?: return null
        var model = firstString(listOf("vehicle_name_en", "vehicle_name", "model", "vehicleModel"), obj) ?: sn
        val vehicleType = firstString(listOf("vehicle_type"), obj)
        if (!vehicleType.isNullOrEmpty()) {
            model = "$model ($vehicleType)"
        }
        return VehicleInfo(
            sn = sn,
            name = firstString(listOf("device_name", "deviceName", "ble_name"), obj) ?: sn,
            model = model,
            imageUrlString = firstString(listOf("v6_light_img_url", "img_url", "img"), obj),
            raw = obj,
        )
    }

    fun vehicleInfoAddingImage(
        vehicle: VehicleInfo,
        status: JsonValue?,
        battery: JsonValue?,
    ): VehicleInfo {
        if (!vehicle.imageUrlString.isNullOrBlank()) return vehicle
        val statusRoot = status?.objectValue ?: emptyMap()
        val batteryRoot = battery?.objectValue ?: emptyMap()
        val statusObject = payloadObject(statusRoot, listOf("status", "vehicle_status", "vehicleStatus", "data"))
        val statusVehicle = firstObject(listOf("vehicle", "vehicleInfo", "vehicle_info"), statusRoot) ?: emptyMap()
        val batteryObject = payloadObject(batteryRoot, listOf("battery", "batteryInfo", "battery_info", "data"))
        val image = firstString(
            listOf(
                "v6_light_img_url", "v6LightImgUrl", "img_url", "imgUrl",
                "img", "image_url", "imageUrl",
            ),
            listOf(statusObject, statusVehicle, statusRoot, batteryObject, batteryRoot),
        ) ?: return vehicle
        return vehicle.copy(imageUrlString = image)
    }

    fun serverPrediction(value: JsonValue): ServerPrediction? {
        val obj = value.objectValue ?: return null
        val range = obj["range"]?.objectValue ?: emptyMap()
        val charging = obj["charging"]?.objectValue ?: emptyMap()
        if (range.isEmpty() && charging.isEmpty()) return null
        return ServerPrediction(
            modelVersion = firstString(listOf("model_version", "modelVersion"), obj),
            updatedAt = serverDate(obj["updated_at"] ?: obj["updatedAt"]),
            batteryPercent = firstDouble(listOf("battery_percent", "batteryPercent"), obj),
            batteryChemistry = batteryChemistryInfo(obj["battery_chemistry"] ?: obj["batteryChemistry"]),
            range = ServerRangePrediction(
                estimatedRange = firstDouble(listOf("estimated_range_km", "estimatedRangeKm"), range),
                localRange = firstDouble(listOf("local_range_km", "localRangeKm"), range),
                officialRange = firstDouble(listOf("official_range_km", "officialRangeKm"), range),
                source = firstString(listOf("source"), range),
                kmPerPercent = firstDouble(listOf("km_per_percent", "kmPerPercent"), range),
                estimatedFullRange = firstDouble(listOf("estimated_full_range_km", "estimatedFullRangeKm"), range),
                sampleCount = firstInt(listOf("sample_count", "sampleCount"), range),
                totalUsedPercent = firstDouble(listOf("total_used_percent", "totalUsedPercent"), range),
                accuracyPercent = firstDouble(listOf("accuracy_percent", "accuracyPercent"), range),
                confidencePercent = firstDouble(listOf("confidence_percent", "confidencePercent"), range),
                accuracySource = firstString(listOf("accuracy_source", "accuracySource"), range),
                measuredSampleCount = firstInt(listOf("measured_sample_count", "measuredSampleCount"), range),
                isReady = range["ready"]?.boolValue,
            ),
            charging = ServerChargingPrediction(
                isCharging = charging["is_charging"]?.boolValue ?: charging["isCharging"]?.boolValue,
                remainingMinutes = firstDouble(listOf("remaining_minutes", "remainingMinutes"), charging),
                estimatedFullAt = serverDate(charging["estimated_full_at"] ?: charging["estimatedFullAt"]),
                fastMinutesPerPercent = firstDouble(listOf("fast_minutes_per_percent", "fastMinutesPerPercent"), charging),
                taperMinutesPerPercent = firstDouble(listOf("taper_minutes_per_percent", "taperMinutesPerPercent"), charging),
                sampleCount = firstInt(listOf("sample_count", "sampleCount"), charging),
                accuracyPercent = firstDouble(listOf("accuracy_percent", "accuracyPercent"), charging),
                confidencePercent = firstDouble(listOf("confidence_percent", "confidencePercent"), charging),
                accuracySource = firstString(listOf("accuracy_source", "accuracySource"), charging),
                measuredSampleCount = firstInt(listOf("measured_sample_count", "measuredSampleCount"), charging),
                isReady = charging["ready"]?.boolValue,
                estimatedSpeedKmh = firstDouble(listOf("estimated_speed_kmh", "estimatedSpeedKmh"), charging),
            ),
        )
    }

    fun batteryChemistryInfo(value: JsonValue?): BatteryChemistryInfo? {
        val obj = value?.objectValue ?: return null
        val configuredRaw = firstString(listOf("configured"), obj) ?: return null
        return BatteryChemistryInfo(
            configured = BatteryChemistry.from(configuredRaw),
            effective = firstString(listOf("effective"), obj) ?: "unknown",
            source = firstString(listOf("source"), obj) ?: "unresolved",
            nominalVoltage = firstDouble(listOf("nominal_voltage", "nominalVoltage"), obj),
            capacityWh = firstDouble(listOf("capacity_wh", "capacityWh"), obj),
            capacityAh = firstDouble(listOf("capacity_ah", "capacityAh"), obj),
        )
    }

    fun travelPage(value: JsonValue, fallbackMonth: String): TravelPage {
        val obj = value.objectValue ?: emptyMap()
        val rides = obj["list"]?.arrayValue ?: emptyList()
        val records = rides.mapIndexedNotNull { index, item -> rideRecord(item, index) }
        return TravelPage(
            month = firstString(listOf("month"), obj) ?: fallbackMonth,
            page = firstInt(listOf("page"), obj) ?: 1,
            pageSize = firstInt(listOf("page_size", "pageSize"), obj) ?: records.size,
            total = firstInt(listOf("total"), obj) ?: records.size,
            hasMore = obj["has_more"]?.boolValue ?: obj["hasMore"]?.boolValue ?: false,
            records = records,
            raw = value,
        )
    }

    fun vehicleState(
        status: JsonValue?,
        travel: JsonValue?,
        battery: JsonValue? = null,
        prediction: ServerPrediction? = null,
        updatedAt: Date,
    ): VehicleState {
        val statusRoot = status?.objectValue ?: emptyMap()
        val statusObject = payloadObject(statusRoot, listOf("status", "vehicle_status", "vehicleStatus", "data"))
        val travelObject = travel?.objectValue ?: emptyMap()
        val batteryRoot = battery?.objectValue ?: emptyMap()
        val batteryPayload = payloadObject(batteryRoot, listOf("battery", "batteryInfo", "battery_info", "data"))
        val statusBattery = firstObject(
            listOf("battery", "batteryInfo", "battery_info", "bms", "bmsInfo", "bms_info"),
            statusObject,
        ) ?: firstObject(
            listOf("battery", "batteryInfo", "battery_info", "bms", "bmsInfo", "bms_info"),
            statusRoot,
        ) ?: emptyMap()
        val batteryList = firstArrayObject(listOf("battery_list", "batteryList", "batteries"), batteryPayload)
            ?: firstArrayObject(listOf("battery_list", "batteryList", "batteries"), batteryRoot)
            ?: emptyMap()
        val batteryMain = firstObject(listOf("battery_main", "batteryMain"), batteryPayload)
            ?: firstObject(listOf("battery_main", "batteryMain"), batteryRoot)
            ?: emptyMap()
        val statusSources = listOf(statusObject, statusRoot)
        val batterySources = statusSources + listOf(statusBattery, batteryPayload, batteryRoot, batteryList, batteryMain)
        val loc = statusObject["loc"]?.objectValue ?: statusRoot["loc"]?.objectValue
        val locationInfo = statusObject["locationInfo"]?.objectValue ?: statusRoot["locationInfo"]?.objectValue
        val lockNumber = loc?.get("lock")?.intValue ?: firstInt(listOf("lock_status", "lockStatus"), statusSources)
        val rides = travelObject["list"]?.arrayValue ?: emptyList()
        val rideRecords = rides.mapIndexedNotNull { index, item -> rideRecord(item, index) }
        val daily = dailyMileageRecords(travelObject)

        return VehicleState(
            battery = firstInt(
                listOf("dump_energy", "dumpEnergy", "electricity", "battery_percent", "batteryPercent"),
                batterySources,
            ),
            batteryVoltage = normalizedBatteryVoltage(
                firstDouble(
                    listOf(
                        "battery_voltage", "batteryVoltage", "battery_vol", "batteryVol",
                        "batt_voltage", "battVoltage", "bat_voltage", "batVoltage",
                        "bms_voltage", "bmsVoltage", "bms_volt", "bmsVolt", "voltage", "volt",
                    ),
                    batterySources,
                ),
            ),
            batteryTemperature = normalizedBatteryTemperature(
                firstDouble(
                    listOf(
                        "battery_temperature", "batteryTemperature", "battery_temp", "batteryTemp",
                        "batt_temperature", "battTemperature", "batt_temp", "battTemp",
                        "bat_temperature", "batTemperature", "bat_temp", "batTemp",
                        "bms_temperature", "bmsTemperature", "bms_temp", "bmsTemp",
                        "temperature", "temp",
                    ),
                    batterySources,
                ),
            ),
            batteryCycleCount = firstInt(listOf("bms_cycle", "bmsCycle", "cycle", "cycles"), batterySources),
            chargingPower = firstDouble(listOf("charging_power", "chargingPower", "charge_power", "chargePower"), batterySources),
            endurance = firstDouble(
                listOf("estimate_mileage", "estimateMileage", "precise_estimate_mileage", "preciseEstimateMileage"),
                statusSources,
            ),
            aiEstimatedMileage = firstDouble(
                listOf("ai_estimate_mileage", "aiEstimateMileage", "ai_estimated_mileage", "aiEstimatedMileage"),
                statusSources,
            ),
            isCharging = firstBoolLike(listOf("charging", "chargingState"), batterySources, trueValue = 1),
            isPoweredOn = firstBoolLike(listOf("pwr", "powerStatus"), statusSources, trueValue = 1),
            isLocked = lockNumber?.let { it == 1 },
            remainingChargeTime = firstDouble(
                listOf("remain_charge_time", "remainChargeTime", "remainingChargeTime"),
                batterySources,
            ),
            locationDescription = firstString(listOf("locationDesc", "desc"), locationInfo ?: emptyMap()),
            latitude = normalizedCoordinate(
                loc?.get("lat")?.doubleValue ?: locationInfo?.get("lat")?.doubleValue,
                limit = 90.0,
            ),
            longitude = normalizedCoordinate(
                loc?.get("lon")?.doubleValue ?: locationInfo?.get("lon")?.doubleValue,
                limit = 180.0,
            ),
            totalMileage = firstDouble(listOf("total_mileage", "totalMileage", "total_mileages"), statusSources)
                ?: firstDouble(listOf("total_mileage", "totalMileage"), travelObject),
            monthMileage = firstDouble(listOf("total_mileages", "monthMileage"), travelObject),
            monthEnergy = firstDouble(listOf("ec", "monthEnergy"), travelObject),
            monthUsedElectricity = firstDouble(listOf("used_electricity", "usedElectricity"), travelObject),
            lastMileage = rideRecords.firstOrNull()?.mileage,
            lastEnergy = rideRecords.firstOrNull()?.energy,
            lastUsedElectricity = rideRecords.firstOrNull()?.usedElectricity,
            rideRecords = rideRecords.takeIf { it.isNotEmpty() },
            dailyMileageRecords = daily.takeIf { it.isNotEmpty() },
            updatedAt = updatedAt,
            rawStatus = statusRoot.takeIf { it.isNotEmpty() },
            rawTravel = travelObject.takeIf { it.isNotEmpty() },
            rawBattery = batteryRoot.takeIf { it.isNotEmpty() },
            serverPrediction = prediction,
        )
    }

    fun normalizedCoordinate(value: Double?, limit: Double): Double? {
        if (value == null) return null
        if (abs(value) <= limit) return value
        for (divisor in listOf(1_000_000.0, 10_000_000.0, 100_000.0)) {
            val normalized = value / divisor
            if (abs(normalized) <= limit) return normalized
        }
        return null
    }

    fun rideRecord(value: JsonValue, index: Int): RideRecord? {
        val obj = value.objectValue ?: return null
        val startedAt = firstDate(
            listOf(
                "start_time", "startTime", "begin_time", "beginTime",
                "stime", "date", "day", "create_time", "createTime",
            ),
            obj,
        )
        val endedAt = firstDate(
            listOf("end_time", "endTime", "stop_time", "stopTime", "etime", "finish_time", "finishTime"),
            obj,
        )
        val mileage = firstDouble(listOf("mileages", "mileage", "distance", "rideMileage"), obj)
        val energy = firstDouble(listOf("ec", "energy", "electricity", "consume"), obj)
        val usedElectricity = firstDouble(
            listOf("used_electricity", "usedElectricity", "usedElectric", "useElectricity"),
            obj,
        )
        val durationMinutes = firstDurationMinutes(obj, startedAt, endedAt)
        val speed = firstDouble(listOf("speed", "avg_speed", "avgSpeed", "average_speed", "averageSpeed"), obj)
        val id = firstString(
            listOf("travel_id", "travelId", "ride_id", "rideId", "record_id", "recordId", "id"),
            obj,
        ) ?: startedAt?.let { "${it.time / 1000}" }
        ?: "$index"

        return RideRecord(
            id = id,
            startedAt = startedAt,
            endedAt = endedAt,
            mileage = mileage,
            energy = energy,
            usedElectricity = usedElectricity,
            durationMinutes = durationMinutes,
            speed = speed,
            raw = obj,
        )
    }

    fun dailyMileageRecords(travelObject: Map<String, JsonValue>): List<DailyMileageRecord> {
        val detail = travelObject["detail"]?.arrayValue ?: return emptyList()
        val month = firstString(listOf("month"), travelObject)
        val currentMonth = NineplusDates.currentMonthString()
        val currentDay = java.util.Calendar.getInstance(NineplusDates.chinaTimeZone)
            .get(java.util.Calendar.DAY_OF_MONTH)
        val limit = if (month == currentMonth) minOf(detail.size, currentDay) else detail.size

        return detail.take(limit).mapIndexedNotNull { index, value ->
            val mileage = value.doubleValue ?: return@mapIndexedNotNull null
            val day = index + 1
            DailyMileageRecord(
                id = "${month ?: "month"}-$day",
                day = day,
                date = NineplusDates.date(month, day),
                mileage = mileage,
            )
        }
    }

    fun totalMileageFromMonthlyTravels(travels: List<JsonValue>?): Double? {
        if (travels == null) return null
        var total = 0.0
        var hasMileage = false
        for (travel in travels) {
            val obj = travel.objectValue ?: continue
            val mileage = firstDouble(listOf("total_mileages", "totalMileage", "monthMileage", "mileage"), obj)
            if (mileage != null) {
                total += maxOf(mileage, 0.0)
                hasMileage = true
                continue
            }
            val dailyTotal = dailyMileageRecords(obj).sumOf { maxOf(it.mileage, 0.0) }
            if (dailyTotal > 0) {
                total += dailyTotal
                hasMileage = true
            }
        }
        return if (hasMileage) total else null
    }

    fun normalizedBatteryVoltage(value: Double?): Double? {
        if (value == null) return null
        return when {
            value > 1_000 -> value / 1_000
            value > 120 -> value / 10
            else -> value
        }
    }

    fun normalizedBatteryTemperature(value: Double?): Double? {
        if (value == null) return null
        return if (abs(value) > 120) value / 10 else value
    }

    private fun firstDate(keys: List<String>, source: Map<String, JsonValue>): Date? {
        for (key in keys) {
            val value = source[key] ?: continue
            parseDate(value)?.let { return it }
        }
        return null
    }

    fun serverDate(value: JsonValue?): Date? {
        val text = value?.stringValue?.trim()
        if (!text.isNullOrEmpty()) {
            NineplusDates.serverDate(text)?.let { return it }
            return parseDate(JsonValue.Str(text))
        }
        return parseDate(value)
    }

    private fun parseDate(value: JsonValue?): Date? {
        if (value == null) return null
        if (value is JsonValue.Num) {
            val number = value.value
            return when {
                number > 1_000_000_000_000 -> Date((number / 1000).toLong() * 1000)
                number > 1_000_000_000 -> Date(number.toLong() * 1000)
                else -> null
            }
        }
        val text = value.stringValue?.trim()
        if (text.isNullOrEmpty()) return null
        text.toDoubleOrNull()?.let { number ->
            return when {
                number > 1_000_000_000_000 -> Date((number / 1000).toLong() * 1000)
                number > 1_000_000_000 -> Date(number.toLong() * 1000)
                else -> null
            }
        }
        return NineplusDates.parse(JsonDateInput.TextValue(text))
    }

    private fun firstDurationMinutes(
        obj: Map<String, JsonValue>,
        startedAt: Date?,
        endedAt: Date?,
    ): Double? {
        val derived = durationMinutes(startedAt, endedAt)
        firstDurationValue(listOf("durationMinutes", "duration_min", "durationMin"), obj)?.let {
            return saneDuration(it, derived)
        }
        firstDurationValue(
            listOf("duration_seconds", "durationSeconds", "ride_seconds", "riding_seconds"),
            obj,
        )?.let {
            return saneDuration(it / 60, derived)
        }
        firstDurationValue(
            listOf("duration", "ride_time", "rideTime", "riding_time", "ridingTime", "use_time", "useTime", "cost_time", "costTime"),
            obj,
        )?.let {
            return saneDuration(ambiguousDurationMinutes(it, derived), derived)
        }
        return derived
    }

    private fun firstDurationValue(keys: List<String>, source: Map<String, JsonValue>): Double? {
        for (key in keys) {
            val value = source[key] ?: continue
            clockDurationMinutes(value)?.let { return it }
            val numeric = value.doubleValue
            if (numeric != null && numeric > 0) return numeric
        }
        return null
    }

    private fun clockDurationMinutes(value: JsonValue): Double? {
        val text = value.stringValue?.trim()
        if (text.isNullOrEmpty() || !text.contains(":")) return null
        val parts = text.split(":").mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) {
            2 -> parts[0] + parts[1] / 60
            3 -> parts[0] * 60 + parts[1] + parts[2] / 60
            else -> null
        }
    }

    private fun durationMinutes(startedAt: Date?, endedAt: Date?): Double? {
        if (startedAt == null || endedAt == null) return null
        val minutes = (endedAt.time - startedAt.time) / 60_000.0
        return if (minutes > 0 && minutes <= 48 * 60) minutes else null
    }

    private fun ambiguousDurationMinutes(value: Double, derived: Double?): Double {
        if (derived == null) return if (value > 300) value / 60 else value
        val minuteCandidate = value
        val secondCandidate = value / 60
        return if (abs(secondCandidate - derived) < abs(minuteCandidate - derived)) {
            secondCandidate
        } else {
            minuteCandidate
        }
    }

    private fun saneDuration(value: Double, fallback: Double?): Double? {
        return if (value > 0 && value <= 48 * 60) value else fallback
    }

    private fun abs(value: Double): Double = kotlin.math.abs(value)
}

sealed class ApiException(message: String) : Exception(message) {
    class InvalidBaseUrl : ApiException("服务器地址无效")
    class InvalidResponse : ApiException("服务器返回的数据格式无效")
    class Server(val serverMessage: String) : ApiException(serverMessage)
    class HttpStatus(val code: Int, val bodyMessage: String) :
        ApiException(if (bodyMessage.isEmpty()) "HTTP $code" else "HTTP $code: $bodyMessage")

    class MissingServer : ApiException("请先填写 NinePlus 服务器地址")
}
