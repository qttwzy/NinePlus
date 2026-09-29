package com.example.ninebotplus

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amap.api.maps.MapsInitializer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-test AMap GL map view on the current device/emulator GPU stack.
 * Fails fast if EGL context creation regresses (createContext failed).
 */
@RunWith(AndroidJUnit4::class)
class AmapGlSmokeTest {

    @Test
    fun amapMapViewCreatesMapWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)

        val mapView = com.amap.api.maps.MapView(context)
        try {
            mapView.onCreate(null)
            mapView.onResume()
            val amap = mapView.map
            assertThat(amap).isNotNull()
            amap.setMaxZoomLevel(20f)
        } finally {
            try {
                mapView.onPause()
                mapView.onDestroy()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun amapMapViewCreateDestroyCycleDoesNotAbort() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)

        // Tab switches dispose/recreate map views — this is the production crash path.
        repeat(3) {
            val mapView = com.amap.api.maps.MapView(context)
            mapView.onCreate(null)
            mapView.onResume()
            assertThat(mapView.map).isNotNull()
            mapView.onPause()
            Thread.sleep(200)
            mapView.onDestroy()
            Thread.sleep(150)
        }
    }
}
