package com.example.ninebotplus.ui.map

import android.os.Build

/**
 * AMap 3D SDK 在部分模拟器 / 软件渲染环境下 EGL context 创建失败
 * （GlesUtility.createContext failed: 12288），且异常抛在 GL 线程，
 * 进程会被直接杀掉。真机 GPU 正常。
 *
 * 地图 Composable 在 [shouldRenderAmap] 为 false 时必须走无 GL 兜底，
 * 不能创建 AMap MapView。
 */
object AmapSupport {

    val isEmulator: Boolean by lazy {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        fp.startsWith("generic") ||
            fp.startsWith("sdk_gphone") ||
            fp.contains("emulator") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator") ||
            model.contains("android sdk") ||
            product.contains("sdk_gphone") ||
            product.contains("emulator") ||
            product == "google_sdk" ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            manufacturer.contains("genymotion") ||
            System.getProperty("ro.kernel.qemu") == "1"
    }

    /** False → do not inflate AMap MapView; render the static fallback instead. */
    fun shouldRenderAmap(): Boolean = !isEmulator
}
