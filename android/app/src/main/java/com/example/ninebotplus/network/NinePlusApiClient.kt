package com.example.ninebotplus.network

import com.example.ninebotplus.domain.BatteryChemistry
import com.example.ninebotplus.domain.BatteryChemistryInfo
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.RideDetail
import com.example.ninebotplus.domain.ServerConfiguration
import com.example.ninebotplus.domain.ServerPrediction
import com.example.ninebotplus.domain.TravelPage
import com.example.ninebotplus.domain.VehicleInfo
import com.example.ninebotplus.domain.VehicleSnapshot
import com.example.ninebotplus.domain.VehicleState
import com.example.ninebotplus.util.NineplusDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * NinePlus Platform HTTP client (server-only architecture).
 *
 * Endpoints (from iOS NinebotServerClient):
 *  GET  /healthz
 *  POST /accounts/login
 *  GET  /vehicles
 *  GET  /vehicles/{sn}/dashboard | /status | /battery | /prediction
 *  GET  /vehicles/{sn}/travel?month=yyyyMM
 *  POST /vehicles/{sn}/travel-sync?month=&page_size=
 *  GET  /vehicles/{sn}/travel/{travelId}
 *  POST /vehicles/{sn}/prediction-settings
 *  POST /vehicles/{sn}/bell | /buck | /engine/start | /engine/stop
 *  POST /devices/register
 *  POST /live-activities/register
 */
class NinePlusApiClient(
    private val configuration: ServerConfiguration,
    private val client: OkHttpClient = defaultClient,
) {
    suspend fun healthCheck() {
        request("GET", listOf("healthz"))
    }

    suspend fun login(account: String, password: String): LoginResult {
        val payload = request(
            method = "POST",
            path = listOf("accounts", "login"),
            body = buildJsonObject {
                put("account", account)
                put("password", password)
            },
        )
        return PayloadParser.loginResult(payload)
    }

    suspend fun ringBell(sn: String): JsonValue =
        request("POST", listOf("vehicles", sn, "bell"))

    suspend fun openBucket(sn: String): JsonValue =
        request("POST", listOf("vehicles", sn, "buck"))

    suspend fun engineStart(sn: String): JsonValue =
        request("POST", listOf("vehicles", sn, "engine", "start"))

    suspend fun engineStop(sn: String): JsonValue =
        request("POST", listOf("vehicles", sn, "engine", "stop"))

    suspend fun updateBatteryChemistry(
        sn: String,
        chemistry: BatteryChemistry,
        nominalVoltage: Double?,
        capacityWh: Double?,
    ): BatteryChemistryInfo? {
        val payload = request(
            method = "POST",
            path = listOf("vehicles", sn, "prediction-settings"),
            body = buildJsonObject {
                put("battery_chemistry", chemistry.raw)
                put("nominal_voltage", numberInputText(nominalVoltage))
                put("capacity_wh", numberInputText(capacityWh))
            },
        )
        val obj = payload.objectValue ?: emptyMap()
        return PayloadParser.batteryChemistryInfo(obj["battery_chemistry"] ?: obj["batteryChemistry"])
    }

    suspend fun fetchDashboard(selectedSn: String? = null): Dashboard = withContext(Dispatchers.IO) {
        val vehiclesPayload = request("GET", listOf("vehicles"))
        val vehicleValues = PayloadParser.arrayPayload(vehiclesPayload, listOf("vehicles", "data"))
        val vehicles = vehicleValues.mapNotNull { PayloadParser.vehicleInfo(it) }
        val currentMonth = NineplusDates.currentMonthString()

        val snapshots = vehicles.map { vehicle ->
            async {
                fetchVehicleSnapshot(vehicle, currentMonth)
            }
        }.awaitAll()

        val resolvedSelected = selectedSn?.takeIf { sn -> snapshots.any { it.vehicle.sn == sn } }
            ?: snapshots.firstOrNull()?.vehicle?.sn

        Dashboard(
            vehicles = snapshots,
            selectedSn = resolvedSelected,
            updatedAt = Date(),
        )
    }

    private suspend fun fetchVehicleSnapshot(
        vehicle: VehicleInfo,
        currentMonth: String,
    ): VehicleSnapshot {
        val dashboard: JsonValue? = try {
            request("GET", listOf("vehicles", vehicle.sn, "dashboard"))
        } catch (_: Exception) {
            null
        }
        val dashboardObject = dashboard?.objectValue
        val status: JsonValue?
        val travel: JsonValue?
        val battery: JsonValue?
        val prediction: ServerPrediction?

        val stableState = dashboardObject?.get("state")
        if (stableState != null && PayloadParser.hasVehicleStatus(stableState)) {
            status = stableState
            travel = dashboardObject["travel"]
            battery = if (PayloadParser.hasBatteryData(dashboardObject["battery"])) {
                dashboardObject["battery"]
            } else {
                stableState
            }
            prediction = dashboardObject["prediction"]?.let { PayloadParser.serverPrediction(it) }
        } else if (dashboardObject != null &&
            PayloadParser.hasVehicleStatus(dashboardObject["status"]) &&
            PayloadParser.hasBatteryData(dashboardObject["battery"])
        ) {
            status = dashboardObject["status"]
            travel = dashboardObject["travel"]
            battery = dashboardObject["battery"]
            prediction = dashboardObject["prediction"]?.let { PayloadParser.serverPrediction(it) }
        } else {
            val fallbackStatus = request("GET", listOf("vehicles", vehicle.sn, "status"))
            val fallbackBattery = request("GET", listOf("vehicles", vehicle.sn, "battery"))
            if (!PayloadParser.hasVehicleStatus(fallbackStatus)) {
                throw ApiException.Server("服务器没有返回车辆状态，请在管理端检查该车辆最近一次轮询")
            }
            if (!PayloadParser.hasBatteryData(fallbackBattery)) {
                throw ApiException.Server("服务器没有返回电池数据，请在管理端检查该车辆最近一次轮询")
            }
            status = fallbackStatus
            travel = try {
                request("GET", listOf("vehicles", vehicle.sn, "travel"), query = mapOf("month" to currentMonth))
            } catch (_: Exception) {
                null
            }
            battery = fallbackBattery
            prediction = try {
                request("GET", listOf("vehicles", vehicle.sn, "prediction"))
                    .let { PayloadParser.serverPrediction(it) }
            } catch (_: Exception) {
                null
            }
        }

        val state = PayloadParser.vehicleState(
            status = status,
            travel = travel,
            battery = battery,
            prediction = prediction,
            updatedAt = PayloadParser.serverDate(
                dashboardObject?.get("updated_at") ?: dashboardObject?.get("updatedAt"),
            ) ?: Date(),
        )
        val dashboardVehicle = dashboardObject?.get("vehicle")?.let { PayloadParser.vehicleInfo(it) } ?: vehicle
        val resolved = PayloadParser.vehicleInfoAddingImage(dashboardVehicle, status, battery)
        return VehicleSnapshot(vehicle = resolved, state = state)
    }

    /** 单月行程列表；历史月按需拉取，不参与 dashboard 热路径。 */
    suspend fun fetchTravelMonth(sn: String, month: String): JsonValue {
        return request(
            "GET",
            listOf("vehicles", sn, "travel"),
            query = mapOf("month" to month),
        )
    }

    suspend fun fetchTravelDetail(sn: String, travelId: String): RideDetail {
        val payload = request("GET", listOf("vehicles", sn, "travel", travelId))
        return RideDetail(
            vehicleSn = sn,
            rideId = travelId,
            fetchedAt = Date(),
            raw = payload,
            parsedRecord = PayloadParser.rideRecord(payload, 0),
        )
    }

    suspend fun syncTravelMonth(sn: String, month: String, pageSize: Int = 20): TravelPage {
        val payload = request(
            method = "POST",
            path = listOf("vehicles", sn, "travel-sync"),
            query = mapOf("month" to month, "page_size" to pageSize.toString()),
        )
        return PayloadParser.travelPage(payload, month)
    }

    suspend fun registerPushDevice(
        token: String,
        bundleId: String,
        environment: String,
    ) {
        request(
            method = "POST",
            path = listOf("devices", "register"),
            body = buildJsonObject {
                put("token", token)
                put("bundle_id", bundleId)
                put("environment", environment)
            },
        )
    }

    suspend fun registerLiveActivityToken(
        token: String,
        tokenKind: String,
        bundleId: String,
        environment: String,
        deviceToken: String? = null,
        activityId: String? = null,
        vehicleSn: String? = null,
    ) {
        request(
            method = "POST",
            path = listOf("live-activities", "register"),
            body = buildJsonObject {
                put("token", token)
                put("token_kind", tokenKind)
                put("bundle_id", bundleId)
                put("environment", environment)
                activityId?.takeIf { it.isNotBlank() }?.let { put("activity_id", it) }
                deviceToken?.takeIf { it.isNotBlank() }?.let { put("device_token", it) }
                vehicleSn?.takeIf { it.isNotBlank() }?.let { put("vehicle_sn", it) }
            },
        )
    }

    private suspend fun request(
        method: String,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): JsonValue = withContext(Dispatchers.IO) {
        val base = configuration.baseUrl?.toHttpUrlOrNull()
            ?: throw ApiException.InvalidBaseUrl()

        val builder = base.newBuilder()
        for (component in path) {
            builder.addPathSegment(component)
        }
        for ((key, value) in query) {
            builder.addQueryParameter(key, value)
        }

        val requestBuilder = Request.Builder()
            .url(builder.build())
            .header("Accept", "application/json")
            .timeout(Timeouts.REQUEST)

        val bearer = configuration.bearerToken.trim()
        if (bearer.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $bearer")
        }
        configuration.appSessionToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
            requestBuilder.header("X-NinePlus-Session", it)
        }

        if (body != null) {
            requestBuilder.header("Content-Type", "application/json")
            requestBuilder.method(
                method,
                body.toString().toRequestBody(JSON_MEDIA),
            )
        } else {
            requestBuilder.method(method, if (method == "POST") EMPTY_BODY else null)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val data = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException.HttpStatus(response.code, PayloadParser.errorMessage(data))
            }
            if (data.isBlank()) {
                return@withContext JsonValue.Obj(emptyMap())
            }
            val root = JsonValue.from(parseJsonElement(data))
            PayloadParser.unwrapEnvelope(root)
        }
    }

    private fun numberInputText(value: Double?): String {
        if (value == null) return ""
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA)

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Shorter timeouts for widget / background refresh. */
        val widgetClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

private object Timeouts {
    val REQUEST = 20L
}

private fun Request.Builder.timeout(seconds: Long): Request.Builder =
    // OkHttp Request.Builder has no per-request timeout; kept for call-site readability.
    this

fun IoExceptionMessage(error: Throwable): String =
    when (error) {
        is IOException -> error.message ?: "网络连接失败"
        else -> error.message ?: "未知错误"
    }
