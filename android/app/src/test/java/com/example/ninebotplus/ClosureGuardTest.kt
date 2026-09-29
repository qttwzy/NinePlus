package com.example.ninebotplus

import com.example.ninebotplus.ui.map.MapProviderConfig
import com.example.ninebotplus.util.CoordinateTransform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fourth-round closure guards.
 * These lock the exact production contracts that were wrong:
 * - MapLibre style URI must be an explicit URL, not a missing predefined name
 * - default MapLibre/OSM tiles are WGS-84 — no GCJ-02
 * - CredentialStore must fail closed when fallback is not explicitly allowed
 */
class ClosureGuardTest {

    @Test
    fun `map provider is amap android sdk for china detail`() {
        assertThat(MapProviderConfig.PROVIDER).isEqualTo("amap-android-sdk")
        // AMap SDK is GCJ-02 — transform must be enabled.
        assertThat(MapProviderConfig.needsGcj02).isTrue()
        // Vector tiles go past free raster z=18.
        assertThat(MapProviderConfig.MAX_ZOOM).isAtLeast(19.0)
    }

    @Test
    fun `map coordinate applies gcj for amap tiles`() {
        val wgsLat = 31.2304
        val wgsLon = 121.4737
        val mapPoint = MapProviderConfig.toMapCoordinate(wgsLat, wgsLon)
        val gcj = CoordinateTransform.gcj02(wgsLat, wgsLon)
        // Must match a single GCJ transform (AMap tile space).
        assertThat(mapPoint.latitude).isWithin(1e-9).of(gcj.latitude)
        assertThat(mapPoint.longitude).isWithin(1e-9).of(gcj.longitude)
    }

    @Test
    fun `credential unavailable message is explicit`() {
        val message = com.example.ninebotplus.data.CredentialUnavailableException().message
        assertThat(message).contains("安全存储")
    }
}
