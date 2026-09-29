package com.example.ninebotplus.ui

/**
 * 个人调试用登录预填值（构建时来自 android/local.properties，不入库）。
 * 正式包这些字段应为空。
 */
object DebugLoginDefaults {
    val server: String = com.example.ninebotplus.BuildConfig.NPP_DEFAULT_SERVER
    val bearer: String = com.example.ninebotplus.BuildConfig.NPP_DEFAULT_BEARER
    val phone: String = com.example.ninebotplus.BuildConfig.NPP_DEFAULT_PHONE
    val password: String = com.example.ninebotplus.BuildConfig.NPP_DEFAULT_PASSWORD

    val hasAny: Boolean
        get() = server.isNotBlank() || bearer.isNotBlank() || phone.isNotBlank() || password.isNotBlank()
}
