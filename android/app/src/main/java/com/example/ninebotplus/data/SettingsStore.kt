package com.example.ninebotplus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.RefreshEvent
import com.example.ninebotplus.domain.ResolvedAddress
import com.example.ninebotplus.domain.ServerConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nineplus_settings")

/**
 * App settings / auth / cache store.
 * Large payloads (rides, track points) live in Room; this store keeps config,
 * login session, small caches and diagnostics events.
 */
class SettingsStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private object Keys {
        val serverBaseUrl = stringPreferencesKey("server_base_url")
        val serverBearer = stringPreferencesKey("server_bearer")
        val loginResult = stringPreferencesKey("login_result")
        val pushToken = stringPreferencesKey("push_device_token")
        val capturePrivacy = booleanPreferencesKey("capture_privacy")
        val pendingRoute = stringPreferencesKey("pending_route")
        val lastError = stringPreferencesKey("last_error")
        val lastAppRefresh = stringPreferencesKey("last_app_refresh")
        val lastWidgetRefresh = stringPreferencesKey("last_widget_refresh")
        val resolvedAddresses = stringPreferencesKey("resolved_addresses")
        val dashboardCache = stringPreferencesKey("dashboard_cache")
    }

    val configurationFlow: Flow<ServerConfiguration> = context.settingsDataStore.data.map { prefs ->
        ServerConfiguration(
            baseUrlString = prefs[Keys.serverBaseUrl].orEmpty(),
            bearerToken = prefs[Keys.serverBearer].orEmpty(),
            appSessionToken = null,
        )
    }

    val loginResultFlow: Flow<LoginResult?> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.loginResult]?.let { runCatching { json.decodeFromString<LoginResultDto>(it) }.getOrNull()?.toDomain() }
    }

    val capturePrivacyFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.capturePrivacy] ?: false }

    val pushTokenFlow: Flow<String?> = context.settingsDataStore.data.map { it[Keys.pushToken] }

    suspend fun configuration(): ServerConfiguration = configurationFlow.first()

    suspend fun saveConfiguration(configuration: ServerConfiguration) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.serverBaseUrl] = configuration.baseUrlString.trim()
            prefs[Keys.serverBearer] = configuration.bearerToken.trim()
        }
    }

    suspend fun loginResult(): LoginResult? = loginResultFlow.first()

    suspend fun saveLoginResult(result: LoginResult) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.loginResult] = json.encodeToString(LoginResultDto.from(result))
        }
    }

    suspend fun clearLoginResult() {
        context.settingsDataStore.edit { it.remove(Keys.loginResult) }
    }

    suspend fun pushToken(): String? = pushTokenFlow.first()

    suspend fun savePushToken(token: String?) {
        context.settingsDataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.pushToken) else prefs[Keys.pushToken] = token
        }
    }

    suspend fun capturePrivacyEnabled(): Boolean = capturePrivacyFlow.first()

    suspend fun setCapturePrivacyEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.capturePrivacy] = enabled }
    }

    suspend fun savePendingRoute(route: String?) {
        context.settingsDataStore.edit { prefs ->
            if (route.isNullOrBlank()) prefs.remove(Keys.pendingRoute) else prefs[Keys.pendingRoute] = route
        }
    }

    suspend fun consumePendingRoute(): String? {
        var route: String? = null
        context.settingsDataStore.edit { prefs ->
            route = prefs[Keys.pendingRoute]
            prefs.remove(Keys.pendingRoute)
        }
        return route
    }

    suspend fun saveLastError(message: String?) {
        context.settingsDataStore.edit { prefs ->
            if (message.isNullOrBlank()) prefs.remove(Keys.lastError) else prefs[Keys.lastError] = message
        }
    }

    suspend fun lastError(): String? = context.settingsDataStore.data.first()[Keys.lastError]

    suspend fun saveLastAppRefresh(event: RefreshEvent) {
        context.settingsDataStore.edit {
            it[Keys.lastAppRefresh] = json.encodeToString(RefreshEventDto.from(event))
        }
    }

    suspend fun lastAppRefresh(): RefreshEvent? =
        context.settingsDataStore.data.first()[Keys.lastAppRefresh]
            ?.let { runCatching { json.decodeFromString<RefreshEventDto>(it) }.getOrNull()?.toDomain() }

    suspend fun saveLastWidgetRefresh(event: RefreshEvent) {
        context.settingsDataStore.edit {
            it[Keys.lastWidgetRefresh] = json.encodeToString(RefreshEventDto.from(event))
        }
    }

    suspend fun lastWidgetRefresh(): RefreshEvent? =
        context.settingsDataStore.data.first()[Keys.lastWidgetRefresh]
            ?.let { runCatching { json.decodeFromString<RefreshEventDto>(it) }.getOrNull()?.toDomain() }

    suspend fun resolvedAddresses(): Map<String, ResolvedAddress> {
        val raw = context.settingsDataStore.data.first()[Keys.resolvedAddresses] ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ResolvedAddressDto>>(raw).mapValues { it.value.toDomain() }
        }.getOrDefault(emptyMap())
    }

    suspend fun saveResolvedAddresses(addresses: Map<String, ResolvedAddress>) {
        val dto = addresses.mapValues { ResolvedAddressDto.from(it.value) }
        context.settingsDataStore.edit {
            it[Keys.resolvedAddresses] = json.encodeToString(dto)
        }
    }

    suspend fun dashboardCache(): String? = context.settingsDataStore.data.first()[Keys.dashboardCache]

    suspend fun saveDashboardCache(raw: String) {
        context.settingsDataStore.edit { it[Keys.dashboardCache] = raw }
    }
}

@kotlinx.serialization.Serializable
data class LoginResultDto(
    val uuid: String? = null,
    val phone: String? = null,
    val areaCode: String? = null,
    val region: String? = null,
    val businessUid: String? = null,
    val accountId: Int? = null,
    val sessionToken: String? = null,
) {
    fun toDomain() = LoginResult(uuid, phone, areaCode, region, businessUid, accountId, sessionToken)

    companion object {
        fun from(value: LoginResult) = LoginResultDto(
            value.uuid, value.phone, value.areaCode, value.region,
            value.businessUid, value.accountId, value.sessionToken,
        )
    }
}

@kotlinx.serialization.Serializable
data class RefreshEventDto(
    val source: String,
    val operation: String,
    val startedAt: Long,
    val endedAt: Long,
    val success: Boolean,
    val message: String? = null,
) {
    fun toDomain() = RefreshEvent(
        source, operation, Date(startedAt), Date(endedAt), success, message,
    )

    companion object {
        fun from(value: RefreshEvent) = RefreshEventDto(
            value.source, value.operation, value.startedAt.time, value.endedAt.time,
            value.success, value.message,
        )
    }
}

@kotlinx.serialization.Serializable
data class ResolvedAddressDto(
    val sn: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
    val source: String? = null,
) {
    fun toDomain() = ResolvedAddress(sn, address, latitude, longitude, Date(updatedAt), source)

    companion object {
        fun from(value: ResolvedAddress) = ResolvedAddressDto(
            value.sn, value.address, value.latitude, value.longitude, value.updatedAt.time, value.source,
        )
    }
}
