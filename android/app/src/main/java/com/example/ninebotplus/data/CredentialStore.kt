package com.example.ninebotplus.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.ninebotplus.domain.LoginResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encrypted-at-rest storage for credentials (bearer token and login session).
 *
 * Failure policy (fail-closed):
 * - RELEASE ([allowPlaintextFallback] = false): if the Keystore-backed store
 *   cannot be created, credentials cannot be saved or loaded and
 *   [isAvailable] is false. Never silently downgrade to plaintext.
 * - DEBUG: a plaintext fallback is allowed ONLY when explicitly injected via
 *   [allowPlaintextFallback] (production passes [com.example.ninebotplus.BuildConfig.DEBUG]).
 *
 * Auth model:
 * - Session token lives in [LoginResult] and is injected per request by
 *   [AuthAssembler]. Logout and server-URL change clear it.
 */
class CredentialStore(
    context: Context,
    private val allowPlaintextFallback: Boolean = false,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val prefs: SharedPreferences?
    private val fallback: Boolean

    init {
        val encrypted = try {
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
            null
        }

        if (encrypted != null) {
            prefs = encrypted
            fallback = false
        } else if (allowPlaintextFallback) {
            // Explicitly allowed (debug/tests only). Release passes false.
            prefs = context.getSharedPreferences("nineplus_credentials_debug", Context.MODE_PRIVATE)
            fallback = true
        } else {
            // FAIL CLOSED: no plaintext downgrade.
            prefs = null
            fallback = true
        }
    }

    /** False when the encrypted store could not be created and fallback is not allowed. */
    val isAvailable: Boolean
        get() = prefs != null

    private fun requirePrefs(): SharedPreferences {
        return prefs ?: throw CredentialUnavailableException()
    }

    fun loadBearerToken(): String {
        val p = prefs ?: return ""
        return p.getString(KEY_BEARER, null)?.trim().orEmpty()
    }

    fun saveBearerToken(token: String) {
        requirePrefs().edit().putString(KEY_BEARER, token.trim()).apply()
    }

    fun loadLoginResult(): LoginResult? {
        val p = prefs ?: return null
        val raw = p.getString(KEY_LOGIN, null) ?: return null
        return runCatching {
            json.decodeFromString<LoginResultDto>(raw).toDomain()
        }.getOrNull()
    }

    fun saveLoginResult(result: LoginResult) {
        requirePrefs().edit()
            .putString(KEY_LOGIN, json.encodeToString(LoginResultDto.from(result)))
            .apply()
    }

    fun clearLoginResult() {
        prefs?.edit()?.remove(KEY_LOGIN)?.apply()
    }

    /** Drop every credential. Used on logout and on server URL change. */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }

    /** True when the store fell back to plaintext (only possible when explicitly allowed). */
    val usingPlaintextFallback: Boolean
        get() = fallback

    companion object {
        private const val KEY_BEARER = "bearer_token"
        private const val KEY_LOGIN = "login_result"
    }
}

class CredentialUnavailableException :
    Exception("设备安全存储不可用，无法保存登录凭据。请检查系统 Keystore 后重试。")
