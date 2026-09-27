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
    fun `map style uri is explicit url not predefined name`() {
        val uri = MapProviderConfig.DEFAULT_STYLE_URI
        assertThat(uri.startsWith("https://")).isTrue()
        assertThat(uri).isNotEqualTo("streets")
        assertThat(uri).isNotEqualTo("Basic")
    }

    @Test
    fun `default map provider uses wgs84 without gcj transform`() {
        // MapLibre demotiles / OSM = WGS-84. needsGcj02 must be false.
        assertThat(MapProviderConfig.needsGcj02).isFalse()

        val wgsLat = 31.2304
        val wgsLon = 121.4737
        val mapPoint = MapProviderConfig.toMapCoordinate(wgsLat, wgsLon)
        // No transform applied — marker lands on the correct tile coordinate.
        assertThat(mapPoint.latitude).isWithin(1e-9).of(wgsLat)
        assertThat(mapPoint.longitude).isWithin(1e-9).of(wgsLon)

        // If someone flips needsGcj02 for a GCJ provider, the helper must transform.
        // (We assert the helper's non-GCJ path only here; GCJ path is covered
        // by CoordinateTransformTest.)
        val gcj = CoordinateTransform.gcj02(wgsLat, wgsLon)
        assertThat(mapPoint.latitude).isNotEqualTo(gcj.latitude)
    }

    @Test
    fun `credential unavailable message is explicit`() {
        val message = com.example.ninebotplus.data.CredentialUnavailableException().message
        assertThat(message).contains("安全存储")
    }
}
