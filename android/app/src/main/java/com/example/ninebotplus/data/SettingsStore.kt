package com.example.ninebotplus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 * Non-sensitive app settings + cache.
 *
 * Credentials (bearer / session) live in [CredentialStore].
 * The effective API configuration is assembled by [AuthAssembler].
 */
class SettingsStore(
    private val context: Context,
    private val credentials: CredentialStore = CredentialStore(context),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private object Keys {
        val serverBaseUrl = stringPreferencesKey("server_base_url")
        val pushToken = stringPreferencesKey("push_device_token")
        val capturePrivacy = booleanPreferencesKey("capture_privacy")
        val pendingRoute = stringPreferencesKey("pending_route")
        val lastError = stringPreferencesKey("last_error")
        val lastAppRefresh = stringPreferencesKey("last_app_refresh")
        val lastWidgetRefresh = stringPreferencesKey("last_widget_refresh")
        val resolvedAddresses = stringPreferencesKey("resolved_addresses")
        val dashboardCache = stringPreferencesKey("dashboard_cache")
        val activeRideId = stringPreferencesKey("active_ride_id")
    }

    val serverBaseUrlFlow: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.serverBaseUrl].orEmpty()
    }

    val loginResultFlow: Flow<LoginResult?> = context.settingsDataStore.data.map {
        credentials.loadLoginResult()
    }

    val capturePrivacyFlow: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.capturePrivacy] ?: false
    }

    val pushTokenFlow: Flow<String?> = context.settingsDataStore.data.map { it[Keys.pushToken] }

    /**
     * Effective configuration for every API caller (App, Widget, Worker).
     * Session token always comes from the persisted login result.
     */
    suspend fun effectiveConfiguration(): ServerConfiguration {
        val baseUrl = context.settingsDataStore.data.first()[Keys.serverBaseUrl].orEmpty()
        val bearer = credentials.loadBearerToken()
        val login = credentials.loadLoginResult()
        return AuthAssembler.effectiveConfiguration(baseUrl, bearer, login)
    }

    suspend fun saveServerUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        val previous = context.settingsDataStore.data.first()[Keys.serverBaseUrl].orEmpty()
        if (previous.trim() != trimmed && previous.isNotBlank() && trimmed.isNotBlank()) {
            // Server changed: any existing session belongs to the old server.
            credentials.clearLoginResult()
        }
        context.settingsDataStore.edit { it[Keys.serverBaseUrl] = trimmed }
    }

    suspend fun saveBearerToken(token: String) {
        credentials.saveBearerToken(token)
    }

    suspend fun bearerToken(): String = credentials.loadBearerToken()

    suspend fun loginResult(): LoginResult? = credentials.loadLoginResult()

    suspend fun saveLoginResult(result: LoginResult) {
        credentials.saveLoginResult(result)
    }

    suspend fun clearLoginResult() {
        credentials.clearLoginResult()
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

    suspend fun activeRideId(): String? = context.settingsDataStore.data.first()[Keys.activeRideId]

    suspend fun setActiveRideId(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(Keys.activeRideId) else prefs[Keys.activeRideId] = id
        }
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
