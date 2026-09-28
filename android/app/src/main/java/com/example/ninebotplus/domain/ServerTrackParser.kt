package com.example.ninebotplus.domain

import com.example.ninebotplus.network.JsonValue

/**
 * A single point from the server ride `trail` string.
 *
 * ninecli / Ninebot format:
 *   "lon,lat,speed,distance;lon,lat,speed,distance;..."
 * - speed is km/h at that point
 * - distance is meters to the next point (as returned by the cloud)
 */
data class ServerTrackPoint(
    val index: Int,
    val longitude: Double,
    val latitude: Double,
    val speedKmh: Double?,
    val segmentDistanceMeters: Double?,
) {
    val coordinateText: String
        get() = "%.6f, %.6f".format(latitude, longitude)

    val speedText: String
        get() = speedKmh?.let { "%.1f km/h".format(it) } ?: "--"

    val distanceText: String
        get() = segmentDistanceMeters?.let { "%.1f m".format(it) } ?: "--"
}

/**
 * Extracts per-point track samples (including speed) from a ride detail payload.
 */
object ServerTrackParser {

    private val TRAIL_KEYS = listOf(
        "trail", "trails", "track", "track_points", "trackPoints",
        "trajectory", "points", "point_list", "pointList",
    )

    fun parsePoints(detail: JsonValue): List<ServerTrackPoint> {
        val root = detail.objectValue ?: return emptyList()
        val trailText = findTrailText(root) ?: return emptyList()
        return parseTrailString(trailText)
    }

    fun parseTrailString(trail: String): List<ServerTrackPoint> {
        if (trail.isBlank()) return emptyList()
        return trail.split(';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexedNotNull { index, segment ->
                val parts = segment.split(',').map { it.trim() }
                if (parts.size < 2) return@mapIndexedNotNull null
                val lon = parts[0].toDoubleOrNull() ?: return@mapIndexedNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
                val speed = parts.getOrNull(2)?.toDoubleOrNull()
                val dist = parts.getOrNull(3)?.toDoubleOrNull()
                ServerTrackPoint(
                    index = index,
                    longitude = lon,
                    latitude = lat,
                    speedKmh = speed,
                    segmentDistanceMeters = dist,
                )
            }
    }

    private fun findTrailText(root: Map<String, JsonValue>): String? {
        for (key in TRAIL_KEYS) {
            val value = root[key]
            when {
                value == null -> continue
                value is JsonValue.Str -> return value.value
                value is JsonValue.Arr -> return value.value.joinToString(";") { it.stringValue.orEmpty() }
                else -> {
                    // nested object { "trail": "..." }
                    value.objectValue?.let { nested ->
                        findTrailText(nested)?.let { return it }
                    }
                }
            }
        }
        return null
    }
}
