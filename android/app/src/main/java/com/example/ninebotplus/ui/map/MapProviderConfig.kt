package com.example.ninebotplus.ui.map

/**
 * Map provider configuration.
 *
 * Coordinate policy:
 * - The transform MUST match the tile provider's coordinate system.
 * - Default MapLibre / OSM / OpenFreeMap style tiles use WGS-84 (Web Mercator
 *   projection with lat/lng as-is). Do NOT apply GCJ-02 on top of them.
 * - GCJ-02 transform is only for providers whose tiles are in GCJ-02
 *   (e.g. AMap / 某些国内瓦片). Set [needsGcj02] = true for those.
 *
 * Style URI: MapLibre 11.x `Style.getPredefinedStyle("streets")` does not
 * exist for the default `WellKnownTileServer.MapLibre` configuration (it only
 * exposes "Basic"). We therefore use an explicit style URI.
 */
object MapProviderConfig {

    /**
     * Default public MapLibre demo style (WGS-84 / standard Web Mercator).
     * For production mainland-China use, point this at a GCJ-02 tile style
     * and set [needsGcj02] = true.
     */
    const val DEFAULT_STYLE_URI: String = "https://demotiles.maplibre.org/style.json"

    /** True only when the active tile provider uses GCJ-02 coordinates. */
    const val needsGcj02: Boolean = false

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
