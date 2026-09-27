package com.example.ninebotplus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.ninebotplus.domain.LoginResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encrypted-at-rest storage for credentials (bearer token and login session).
 *
 * Auth model:
 * - Server URL is non-sensitive and lives in [SettingsStore].
 * - App Bearer Token and the NinePlus session token are credentials and live here.
 * - The session token is the canonical source of truth for `X-NinePlus-Session`,
 *   mirroring iOS `LoginResult.sessionToken`. It is composed into every request
 *   via [com.example.ninebotplus.data.AuthAssembler].
 * - logout() and server URL change must clear the session so it can never leak
 *   across accounts or servers.
 */
class CredentialStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "nineplus_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Some devices / test environments lack StrongBox; fall back to
            // a private SharedPreferences rather than crash. Documented limitation.
            context.getSharedPreferences("nineplus_credentials_fallback", Context.MODE_PRIVATE)
        }
    }

    fun loadBearerToken(): String =
        prefs.getString(KEY_BEARER, null)?.trim().orEmpty()

    fun saveBearerToken(token: String) {
        prefs.edit().putString(KEY_BEARER, token.trim()).apply()
    }

    fun loadLoginResult(): LoginResult? {
        val raw = prefs.getString(KEY_LOGIN, null) ?: return null
        return runCatching {
            json.decodeFromString<LoginResultDto>(raw).toDomain()
        }.getOrNull()
    }

    fun saveLoginResult(result: LoginResult) {
        prefs.edit().putString(KEY_LOGIN, json.encodeToString(LoginResultDto.from(result))).apply()
    }

    fun clearLoginResult() {
        prefs.edit().remove(KEY_LOGIN).apply()
    }

    /** Drop every credential. Used on logout and on server URL change. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_BEARER = "bearer_token"
        private const val KEY_LOGIN = "login_result"
    }
}
