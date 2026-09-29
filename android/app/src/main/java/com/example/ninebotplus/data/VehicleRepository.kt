package com.example.ninebotplus.data

import android.content.Context
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.RecordedRide
import com.example.ninebotplus.domain.RefreshEvent
import com.example.ninebotplus.domain.ResolvedAddress
import com.example.ninebotplus.domain.RideDetail
import com.example.ninebotplus.domain.RideRecord
import com.example.ninebotplus.domain.RideTrackPoint
import com.example.ninebotplus.domain.ServerConfiguration
import com.example.ninebotplus.domain.VehicleAction
import com.example.ninebotplus.domain.VehicleHistoryPoint
import com.example.ninebotplus.network.ApiException
import com.example.ninebotplus.network.JsonValue
import com.example.ninebotplus.network.NinePlusApiClient
import com.example.ninebotplus.network.NinePlusJson
import com.example.ninebotplus.network.PayloadParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Date

/**
 * Single application repository for vehicle data, auth, local rides and cache.
 */
class VehicleRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val database: NinePlusDatabase,
) {
    private val dao get() = database.rideDao()

    private val _dashboard = MutableStateFlow(Dashboard.empty)
    val dashboard: StateFlow<Dashboard> = _dashboard.asStateFlow()

    private val _loginResult = MutableStateFlow<LoginResult?>(null)
    val loginResult: StateFlow<LoginResult?> = _loginResult.asStateFlow()

    private val _resolvedAddresses = MutableStateFlow<Map<String, ResolvedAddress>>(emptyMap())
    val resolvedAddresses: StateFlow<Map<String, ResolvedAddress>> = _resolvedAddresses.asStateFlow()

    private val _recordedRides = MutableStateFlow<List<RecordedRide>>(emptyList())
    val recordedRides: StateFlow<List<RecordedRide>> = _recordedRides.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastAppRefresh = MutableStateFlow<RefreshEvent?>(null)
    val lastAppRefresh: StateFlow<RefreshEvent?> = _lastAppRefresh.asStateFlow()

    private val _lastWidgetRefresh = MutableStateFlow<RefreshEvent?>(null)
    val lastWidgetRefresh: StateFlow<RefreshEvent?> = _lastWidgetRefresh.asStateFlow()

    private val _capturePrivacy = MutableStateFlow(false)
    val capturePrivacy: StateFlow<Boolean> = _capturePrivacy.asStateFlow()

    private val _pushToken = MutableStateFlow<String?>(null)
    val pushToken: StateFlow<String?> = _pushToken.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        currentConfigurationCache = settings.effectiveConfiguration()
        _loginResult.value = settings.loginResult()
        _resolvedAddresses.value = settings.resolvedAddresses()
        _lastError.value = settings.lastError()
        _lastAppRefresh.value = settings.lastAppRefresh()
        _lastWidgetRefresh.value = settings.lastWidgetRefresh()
        _capturePrivacy.value = settings.capturePrivacyEnabled()
        _pushToken.value = settings.pushToken()
        _recordedRides.value = loadRecordedRides()
        loadCachedDashboard()?.let { _dashboard.value = it }
    }

    suspend fun configuration(): ServerConfiguration = settings.effectiveConfiguration()

    suspend fun saveConfiguration(configuration: ServerConfiguration) {
        val sessionCleared = settings.saveServerUrl(configuration.baseUrlString)
        settings.saveBearerToken(configuration.bearerToken)
        if (sessionCleared) {
            // Sync in-memory login state immediately so UI cannot show a stale account.
            _loginResult.value = null
        }
        // Session is never persisted with the URL; rebuild the effective config.
        currentConfigurationCache = settings.effectiveConfiguration()
    }

    suspend fun testConnection() {
        val configuration = requireConfiguration()
        NinePlusApiClient(configuration).healthCheck()
    }

    suspend fun login(account: String, password: String) {
        val configuration = requireConfiguration()
        val result = NinePlusApiClient(configuration).login(account, password)
        val normalized = result.copy(phone = result.phone?.takeIf { it.isNotBlank() } ?: account)
        // Canonical session source is LoginResult; AuthAssembler injects it
        // into every subsequent request (App, Widget, Worker).
        settings.saveLoginResult(normalized)
        _loginResult.value = normalized
        currentConfigurationCache = settings.effectiveConfiguration()
    }

    suspend fun logout() {
        settings.clearLoginResult()
        _loginResult.value = null
        // Drop the in-memory cache so the next request cannot reuse a session.
        currentConfigurationCache = settings.effectiveConfiguration()
    }

    suspend fun refreshDashboard(selectedSn: String? = null): Dashboard {
        val configuration = requireConfiguration()
        val startedAt = Date()
        return try {
            val dashboard = NinePlusApiClient(configuration)
                .fetchDashboard(selectedSn ?: _dashboard.value.selectedSn)
            val archived = saveDashboard(dashboard)
            cacheVehicleImages(archived)
            resolveAddresses(archived, force = false)
            val event = RefreshEvent(
                source = "App",
                operation = "刷新车况",
                startedAt = startedAt,
                endedAt = Date(),
                success = true,
                message = archived.primaryVehicle?.vehicle?.name,
            )
            settings.saveLastAppRefresh(event)
            settings.saveLastError(null)
            _lastAppRefresh.value = event
            _lastError.value = null
            archived
        } catch (error: Exception) {
            val message = error.message ?: "刷新失败"
            val event = RefreshEvent(
                source = "App",
                operation = "刷新车况",
                startedAt = startedAt,
                endedAt = Date(),
                success = false,
                message = message,
            )
            settings.saveLastAppRefresh(event)
            settings.saveLastError(message)
            _lastAppRefresh.value = event
            _lastError.value = message
            throw error
        }
    }

    suspend fun performAction(action: VehicleAction, sn: String): Dashboard {
        val configuration = requireConfiguration()
        val client = NinePlusApiClient(configuration)
        when (action.id) {
            "bell" -> client.ringBell(sn)
            "openBucket" -> client.openBucket(sn)
            "engineStart" -> client.engineStart(sn)
            "engineStop" -> client.engineStop(sn)
        }
        return saveDashboard(client.fetchDashboard(sn)).also {
            cacheVehicleImages(it)
            resolveAddresses(it, force = false)
        }
    }

    suspend fun updateBatteryChemistry(
        sn: String,
        chemistry: com.example.ninebotplus.domain.BatteryChemistry,
        nominalVoltage: Double?,
        capacityWh: Double?,
    ): Dashboard {
        val configuration = requireConfiguration()
        val client = NinePlusApiClient(configuration)
        client.updateBatteryChemistry(sn, chemistry, nominalVoltage, capacityWh)
        return saveDashboard(client.fetchDashboard(sn)).also { cacheVehicleImages(it) }
    }

    suspend fun syncTravelMonth(sn: String, month: String, pageSize: Int = 100) {
        val configuration = requireConfiguration()
        val client = NinePlusApiClient(configuration)
        val page = client.syncTravelMonth(sn, month, pageSize)
        upsertInterfaceRideRecords(page.records, sn)
    }

    /** 按需拉取某月行程并归档到 Room，不触发完整 dashboard 刷新。 */
    suspend fun loadTravelMonth(sn: String, month: String) {
        val configuration = requireConfiguration()
        val payload = NinePlusApiClient(configuration).fetchTravelMonth(sn, month)
        val page = PayloadParser.travelPage(payload, month)
        upsertInterfaceRideRecords(page.records, sn)
    }

    suspend fun refreshRideDetail(sn: String, rideId: String, force: Boolean = false): RideDetail {
        val key = "$sn|$rideId"
        if (!force) {
            dao.rideDetail(key)?.let { cached ->
                return RideDetail(
                    vehicleSn = cached.vehicleSn,
                    rideId = cached.rideId,
                    fetchedAt = Date(cached.fetchedAt),
                    raw = JsonValue.from(NinePlusJson.parseToJsonElement(cached.rawJson)),
                    parsedRecord = PayloadParser.rideRecord(
                        JsonValue.from(NinePlusJson.parseToJsonElement(cached.rawJson)),
                        0,
                    ),
                )
            }
        }
        val configuration = requireConfiguration()
        val detail = NinePlusApiClient(configuration).fetchTravelDetail(sn, rideId)
        dao.upsertRideDetail(
            RideDetailEntity(
                key = key,
                vehicleSn = sn,
                rideId = rideId,
                fetchedAt = detail.fetchedAt.time,
                // Store the raw payload as a JSON object text (NOT a JSON string literal).
                rawJson = encodeJson(detail.raw),
            ),
        )
        return detail
    }

    suspend fun interfaceRideRecords(sn: String): List<RideRecord> {
        return dao.interfaceRides(sn).map { entity ->
            val raw = entity.rawJson?.let {
                runCatching { JsonValue.from(NinePlusJson.parseToJsonElement(it)) }.getOrNull()
            }
            RideRecord(
                id = entity.recordId,
                startedAt = entity.startedAt?.let { Date(it) },
                endedAt = entity.endedAt?.let { Date(it) },
                mileage = entity.mileage,
                energy = entity.energy,
                usedElectricity = entity.usedElectricity,
                durationMinutes = entity.durationMinutes,
                speed = entity.speed,
                raw = raw?.objectValue,
            )
        }
    }

    suspend fun historyPoints(sn: String): List<VehicleHistoryPoint> {
        return dao.historyPoints(sn).map {
            VehicleHistoryPoint(
                id = it.id,
                sn = it.vehicleSn,
                date = Date(it.date),
                battery = it.battery,
                endurance = it.endurance,
                totalMileage = it.totalMileage,
                isCharging = it.isCharging,
                isLocked = it.isLocked,
                isPoweredOn = it.isPoweredOn,
            )
        }
    }

    suspend fun loadRecordedRides(): List<RecordedRide> {
        return dao.recordedRides().map { it.toDomain() }
    }

    suspend fun saveRecordedRide(ride: RecordedRide) {
        dao.upsertRecordedRide(ride.toEntity())
        _recordedRides.value = loadRecordedRides()
    }

    suspend fun deleteRecordedRide(id: String) {
        dao.deleteRecordedRide(id)
        _recordedRides.value = loadRecordedRides()
    }

    suspend fun selectVehicle(sn: String) {
        val current = _dashboard.value
        saveDashboard(current.copy(selectedSn = sn))
    }

    suspend fun setCapturePrivacy(enabled: Boolean) {
        settings.setCapturePrivacyEnabled(enabled)
        _capturePrivacy.value = enabled
    }

    suspend fun savePushToken(token: String?) {
        settings.savePushToken(token)
        _pushToken.value = token
    }

    suspend fun currentPushToken(): String? = settings.pushToken()

    suspend fun registerPushTokenToServer() {
        val token = settings.pushToken() ?: throw ApiException.Server("还没有推送设备 Token")
        val configuration = requireConfiguration()
        NinePlusApiClient(configuration).registerPushDevice(
            token = token,
            bundleId = context.packageName,
            environment = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "development"
            } else {
                "production"
            },
        )
    }

    suspend fun diagnostics(): com.example.ninebotplus.domain.DiagnosticsSnapshot {
        val configuration = settings.effectiveConfiguration()
        val dashboard = _dashboard.value
        val vehicles = dashboard.vehicles
        val interfaceRideCount = vehicles.sumOf { dao.interfaceRideCount(it.vehicle.sn) }
        val historyPointCount = vehicles.sumOf { dao.historyCount(it.vehicle.sn) }
        return com.example.ninebotplus.domain.DiagnosticsSnapshot(
            hasConfiguration = configuration.isUsable,
            serverText = if (configuration.baseUrlString.isBlank()) {
                "服务器未配置"
            } else {
                "服务器 · ${configuration.baseUrlString.trim()}"
            },
            accountText = _loginResult.value?.phone?.takeIf { it.isNotBlank() } ?: "未绑定账号",
            vehicleCount = vehicles.size,
            selectedVehicleName = dashboard.primaryVehicle?.vehicle?.name ?: "暂无车辆",
            dashboardUpdatedAt = dashboard.updatedAt.takeIf { it.time > 0 },
            lastAppRefreshEvent = _lastAppRefresh.value,
            lastWidgetRefreshEvent = _lastWidgetRefresh.value,
            lastError = _lastError.value ?: settings.lastError(),
            interfaceRideCount = interfaceRideCount,
            historyPointCount = historyPointCount,
            recordedRideCount = dao.recordedRideCount(),
            rideDetailCount = dao.rideDetailCount(),
            resolvedAddressCount = _resolvedAddresses.value.size,
            dashboardCacheBytes = settings.dashboardCache()?.toByteArray()?.size ?: 0,
        )
    }

    suspend fun clearMessages() {
        settings.saveLastError(null)
        _lastError.value = null
    }

    suspend fun resolveAddressesNow() {
        resolveAddresses(_dashboard.value, force = true)
    }

    fun resolvedAddress(sn: String): String? = _resolvedAddresses.value[sn]?.address

    private suspend fun requireConfiguration(): ServerConfiguration {
        // Always re-assemble so a fresh LoginResult is picked up and a cleared
        // one cannot linger in the cache (logout / server change).
        val configuration = settings.effectiveConfiguration().also {
            currentConfigurationCache = it
        }
        if (!configuration.isUsable) throw ApiException.MissingServer()
        return configuration
    }

    @Volatile
    private var currentConfigurationCache: ServerConfiguration? = null

    private suspend fun saveDashboard(dashboard: Dashboard): Dashboard {
        _dashboard.value = dashboard
        archiveInterfaceRides(dashboard)
        appendHistory(dashboard)
        settings.saveDashboardCache(encodeDashboard(dashboard))
        return dashboard
    }

    private suspend fun archiveInterfaceRides(dashboard: Dashboard) {
        for (snapshot in dashboard.vehicles) {
            val incoming = snapshot.state.rides
            if (incoming.isEmpty()) continue
            upsertInterfaceRideRecords(incoming, snapshot.vehicle.sn)
        }
    }

    private suspend fun upsertInterfaceRideRecords(records: List<RideRecord>, sn: String) {
        if (records.isEmpty()) return
        val entities = records.map { record ->
            InterfaceRideEntity(
                identityKey = "${sn}|${record.stableIdentityKey}",
                vehicleSn = sn,
                recordId = record.id,
                startedAt = record.startedAt?.time,
                endedAt = record.endedAt?.time,
                mileage = record.mileage,
                energy = record.energy,
                usedElectricity = record.usedElectricity,
                durationMinutes = record.durationMinutes,
                speed = record.speed,
                rawJson = record.raw?.let { encodeJson(JsonValue.Obj(it)) },
            )
        }
        dao.upsertInterfaceRides(entities)
    }

    private suspend fun appendHistory(dashboard: Dashboard) {
        for (snapshot in dashboard.vehicles) {
            val point = VehicleHistoryPoint.from(snapshot.vehicle.sn, snapshot.state)
            val existing = historyPoints(snapshot.vehicle.sn)
            val last = existing.lastOrNull()
            if (last != null && shouldSkipHistory(point, last)) continue
            dao.insertHistoryPoints(
                listOf(
                    HistoryPointEntity(
                        id = point.id,
                        vehicleSn = point.sn,
                        date = point.date.time,
                        battery = point.battery,
                        endurance = point.endurance,
                        totalMileage = point.totalMileage,
                        isCharging = point.isCharging,
                        isLocked = point.isLocked,
                        isPoweredOn = point.isPoweredOn,
                    ),
                ),
            )
        }
    }

    private fun shouldSkipHistory(point: VehicleHistoryPoint, last: VehicleHistoryPoint): Boolean {
        val sameValues = last.battery == point.battery &&
            last.endurance == point.endurance &&
            last.totalMileage == point.totalMileage &&
            last.isCharging == point.isCharging &&
            last.isLocked == point.isLocked &&
            last.isPoweredOn == point.isPoweredOn
        val deltaSeconds = (point.date.time - last.date.time) / 1000.0
        if (deltaSeconds < 60 && sameValues) return true
        if (deltaSeconds < 300 && sameValues) return true
        return false
    }

    private suspend fun cacheVehicleImages(dashboard: Dashboard) {
        for (snapshot in dashboard.vehicles) {
            val url = snapshot.vehicle.imageUrlString?.trim().orEmpty()
            if (url.isEmpty()) continue
            val file = vehicleImageFile(snapshot.vehicle.sn)
            if (file.exists() && file.length() > 0) continue
            runCatching {
                val connection = java.net.URI(url).toURL().openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                }
                val bytes = connection.getInputStream().use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > 2_500_000) return@runCatching
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
    }

    private fun vehicleImageFile(sn: String): File {
        val safe = sn.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(File(context.filesDir, "VehicleImages"), "${safe.ifEmpty { "vehicle" }}.image")
    }

    fun loadVehicleImage(sn: String): ByteArray? {
        val file = vehicleImageFile(sn)
        return if (file.exists()) file.readBytes() else null
    }

    private suspend fun resolveAddresses(dashboard: Dashboard, force: Boolean) {
        val geocoder = android.location.Geocoder(context, java.util.Locale.CHINA)
        var next = _resolvedAddresses.value
        for (snapshot in dashboard.vehicles) {
            val lat = snapshot.state.latitude ?: continue
            val lon = snapshot.state.longitude ?: continue
            if (!force) {
                val cached = next[snapshot.vehicle.sn]
                if (cached != null &&
                    kotlin.math.abs(cached.latitude - lat) < 0.00001 &&
                    kotlin.math.abs(cached.longitude - lon) < 0.00001 &&
                    System.currentTimeMillis() - cached.updatedAt.time < 15 * 60_000
                ) {
                    continue
                }
            }
            runCatching {
                val gcj = com.example.ninebotplus.util.CoordinateTransform.gcj02(lat, lon)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(gcj.latitude, gcj.longitude, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0)
                if (!address.isNullOrBlank()) {
                    next = next + (
                        snapshot.vehicle.sn to ResolvedAddress(
                            sn = snapshot.vehicle.sn,
                            address = address,
                            latitude = lat,
                            longitude = lon,
                            updatedAt = Date(),
                            source = "android-geocoder",
                        )
                        )
                }
            }
        }
        _resolvedAddresses.value = next
        settings.saveResolvedAddresses(next)
    }

    private fun encodeJson(value: JsonValue): String =
        NinePlusJson.encodeToString(JsonValue.toElement(value))

    private fun encodeDashboard(dashboard: Dashboard): String {
        // Compact offline cache of the fields the UI needs before a refresh.
        val vehicles = dashboard.vehicles.joinToString(",") { snapshot ->
            val s = snapshot.state
            val v = snapshot.vehicle
            fun q(value: String?) = value?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
            fun n(value: Number?) = value?.toString() ?: "null"
            fun b(value: Boolean?) = value?.toString() ?: "null"
            """{"sn":${q(v.sn)},"name":${q(v.name)},"model":${q(v.model)},"imageUrl":${q(v.imageUrlString)},"battery":${n(s.battery)},"estimate":${n(s.estimateMileage)},"precise":${n(s.preciseEstimateMileage)},"endurance":${n(s.preferredOfficialRange)},"isCharging":${b(s.isCharging)},"isLocked":${b(s.isLocked)},"isPoweredOn":${b(s.isPoweredOn)},"latitude":${n(s.latitude)},"longitude":${n(s.longitude)},"totalMileage":${n(s.totalMileage)},"monthMileage":${n(s.monthMileage)},"updatedAt":${s.updatedAt.time}}"""
        }
        return """{"selectedSn":${dashboard.selectedSn?.let { "\"$it\"" } ?: "null"},"updatedAt":${dashboard.updatedAt.time},"vehicles":[$vehicles]}"""
    }

    private suspend fun loadCachedDashboard(): Dashboard? {
        return runCatching {
            val raw = settings.dashboardCache() ?: return null
            val root = JsonValue.from(NinePlusJson.parseToJsonElement(raw))
            val obj = root.objectValue ?: return null
            val vehicles = obj["vehicles"]?.arrayValue ?: return null
            val snapshots = vehicles.mapNotNull { item ->
                val v = item.objectValue ?: return@mapNotNull null
                val sn = v["sn"]?.stringValue ?: return@mapNotNull null
                com.example.ninebotplus.domain.VehicleSnapshot(
                    vehicle = com.example.ninebotplus.domain.VehicleInfo(
                        sn = sn,
                        name = v["name"]?.stringValue ?: sn,
                        model = v["model"]?.stringValue ?: sn,
                        imageUrlString = v["imageUrl"]?.stringValue,
                    ),
                    state = com.example.ninebotplus.domain.VehicleState(
                        battery = v["battery"]?.intValue,
                        estimateMileage = v["estimate"]?.doubleValue ?: v["endurance"]?.doubleValue,
                        preciseEstimateMileage = v["precise"]?.doubleValue,
                        isCharging = v["isCharging"]?.boolValue,
                        isLocked = v["isLocked"]?.boolValue,
                        isPoweredOn = v["isPoweredOn"]?.boolValue,
                        latitude = v["latitude"]?.doubleValue,
                        longitude = v["longitude"]?.doubleValue,
                        totalMileage = v["totalMileage"]?.doubleValue,
                        monthMileage = v["monthMileage"]?.doubleValue,
                        updatedAt = Date(v["updatedAt"]?.doubleValue?.toLong() ?: 0L),
                    ),
                )
            }
            Dashboard(
                vehicles = snapshots,
                selectedSn = obj["selectedSn"]?.stringValue,
                updatedAt = Date(obj["updatedAt"]?.doubleValue?.toLong() ?: 0L),
            )
        }.getOrNull()
    }

    private fun RecordedRideEntity.toDomain(): RecordedRide {
        val points = TrackPointCodec.decode(pointsJson).map {
            RideTrackPoint(
                id = it.id,
                date = Date(it.date),
                latitude = it.latitude,
                longitude = it.longitude,
                speedKmh = it.speedKmh,
                accelerationG = it.accelerationG,
                horizontalAccuracy = it.horizontalAccuracy,
            )
        }
        return RecordedRide(
            id = id,
            vehicleSn = vehicleSn,
            associatedRideId = associatedRideId,
            startedAt = Date(startedAt),
            endedAt = Date(endedAt),
            distanceMeters = distanceMeters,
            maxSpeedKmh = maxSpeedKmh,
            averageSpeedKmh = averageSpeedKmh,
            maxAccelerationG = maxAccelerationG,
            points = points,
        )
    }

    private fun RecordedRide.toEntity(): RecordedRideEntity = RecordedRideEntity(
        id = id,
        vehicleSn = vehicleSn,
        associatedRideId = associatedRideId,
        startedAt = startedAt.time,
        endedAt = endedAt.time,
        distanceMeters = distanceMeters,
        maxSpeedKmh = maxSpeedKmh,
        averageSpeedKmh = averageSpeedKmh,
        maxAccelerationG = maxAccelerationG,
        pointsJson = TrackPointCodec.encode(
            points.map {
                TrackPointPayload(
                    id = it.id,
                    date = it.date.time,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    speedKmh = it.speedKmh,
                    accelerationG = it.accelerationG,
                    horizontalAccuracy = it.horizontalAccuracy,
                )
            },
        ),
    )
}
