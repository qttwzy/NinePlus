package com.example.ninebotplus.data

import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.ServerConfiguration

/**
 * Builds the effective request configuration from non-sensitive server URL
 * plus credentials.
 *
 * Session semantics (aligned with iOS NinebotViewModel.currentConfiguration):
 * - `appSessionToken` always comes from the persisted [LoginResult].
 * - It is never stored inside the server-URL configuration blob.
 * - logout clears LoginResult, so subsequent requests carry no session.
 * - changing the server URL must clear LoginResult (see SettingsStore.saveServerUrl).
 */
object AuthAssembler {
    fun effectiveConfiguration(
        baseUrlString: String,
        bearerToken: String,
        loginResult: LoginResult?,
    ): ServerConfiguration = ServerConfiguration(
        baseUrlString = baseUrlString.trim(),
        bearerToken = bearerToken.trim(),
        appSessionToken = loginResult?.sessionToken?.trim()?.takeIf { it.isNotEmpty() },
    )
}
