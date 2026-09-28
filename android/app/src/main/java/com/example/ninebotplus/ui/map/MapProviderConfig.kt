package com.example.ninebotplus.ui.map

/**
 * Map provider configuration.
 *
 * Coordinate policy:
 * - The transform MUST match the tile provider's coordinate system.
 * - Default is AMap (高德) raster tiles, which are **GCJ-02** and fast in mainland China.
 * - If you switch to OSM / MapLibre demotiles (WGS-84), set [needsGcj02] = false.
 */
object MapProviderConfig {

    /**
     * Fast mainland-China basemap: AMap raster tiles via MapLibre style.
     * No API key required for these tile URLs (as commonly used by web maps).
     * Style is inlined so we do not depend on demotiles.maplibre.org.
     */
    val DEFAULT_STYLE_JSON: String = """
        {
          "version": 8,
          "name": "AMap-CN",
          "sources": {
            "amap": {
              "type": "raster",
              "tiles": [
                "https://wprd01.is.autonavi.com/appmaptile?x={x}&y={y}&z={z}&lang=zh_cn&size=1&scale=1&style=7",
                "https://wprd02.is.autonavi.com/appmaptile?x={x}&y={y}&z={z}&lang=zh_cn&size=1&scale=1&style=7",
                "https://wprd03.is.autonavi.com/appmaptile?x={x}&y={y}&z={z}&lang=zh_cn&size=1&scale=1&style=7",
                "https://wprd04.is.autonavi.com/appmaptile?x={x}&y={y}&z={z}&lang=zh_cn&size=1&scale=1&style=7"
              ],
              "tileSize": 256,
              "maxzoom": 18,
              "attribution": "© AutoNavi"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#E8E4D8"
              }
            },
            {
              "id": "amap",
              "type": "raster",
              "source": "amap",
              "minzoom": 3,
              "maxzoom": 22
            }
          ]
        }
    """.trimIndent()

    /** Tile source serves up to z=18; beyond that MapLibre overzooms. */
    const val TILE_MAX_ZOOM: Double = 18.0

    /** Camera ceiling — keep users inside overzoom, never past blank tiles. */
    const val MAX_ZOOM: Double = 18.0

    const val MIN_ZOOM: Double = 3.0

    /** AMap tiles are GCJ-02. */
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
