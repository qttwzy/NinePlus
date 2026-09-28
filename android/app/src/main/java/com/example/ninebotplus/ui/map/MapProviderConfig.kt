package com.example.ninebotplus.ui.map

/**
 * Map provider configuration.
 *
 * Coordinate policy:
 * - The transform MUST match the map provider's coordinate system.
 * - Default is AMap (高德) official Android Map SDK, which expects **GCJ-02**.
 * - If you switch to OSM / MapLibre (WGS-84), set [needsGcj02] = false.
 */
object MapProviderConfig {

    /**
     * High-detail vector basemap via AMap Android Map SDK (requires amap.key
     * in android/local.properties). Vector tiles keep street detail past z=18,
     * unlike free wprd raster tiles.
     */
    const val PROVIDER: String = "amap-android-sdk"

    /** Official AMap SDK serves vector tiles up to z=20. */
    const val MAX_ZOOM: Double = 20.0

    const val MIN_ZOOM: Double = 3.0

    /** AMap expects GCJ-02. */
    const val needsGcj02: Boolean = true

    /**
     * Convert a raw WGS-84 vehicle/track point into map coordinates.
     * Single ownership of the transform lives here.
     */
    fun toMapCoordinate(latitude: Double, longitude: Double): com.example.ninebotplus.util.CoordinateTransform.LatLng {
        return if (needsGcj02) {
            com.example.ninebotplus.util.CoordinateTransform.gcj02(latitude, longitude)
        } else {
            com.example.ninebotplus.util.CoordinateTransform.LatLng(latitude, longitude)
        }
    }
}
