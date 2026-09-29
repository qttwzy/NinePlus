package com.example.ninebotplus.domain

/**
 * Full-trip and per-point speed analytics for a server ride trail.
 * Pure computation — UI only formats and draws the result.
 */
data class SpeedSample(
    val index: Int,
    val speedKmh: Double?,
    /** Distance from trip start (meters), using each trail point's segment distance. */
    val cumulativeDistanceMeters: Double,
    val segmentDistanceMeters: Double?,
) {
    val speedOrZero: Double get() = speedKmh ?: 0.0
}

data class SpeedHistogramBucket(
    val fromKmh: Double,
    val toKmh: Double,
    val count: Int,
    val fraction: Double,
) {
    val label: String
        get() = if (toKmh == Double.POSITIVE_INFINITY) {
            "≥ ${fromKmh.toInt()} km/h"
        } else {
            "${fromKmh.toInt()}–${toKmh.toInt()} km/h"
        }
}

data class TripSpeedStats(
    val sampleCount: Int,
    val speedSampleCount: Int,
    val averageKmh: Double?,
    /** Average of samples with speed above the stop threshold. */
    val movingAverageKmh: Double?,
    val maxKmh: Double?,
    val minKmh: Double?,
    val medianKmh: Double?,
    val p90Kmh: Double?,
    val totalDistanceMeters: Double?,
    /** Share of samples with speed below the stop threshold. */
    val stopRatio: Double?,
)

data class TripSpeedProfile(
    val samples: List<SpeedSample>,
    val stats: TripSpeedStats,
    val histogram: List<SpeedHistogramBucket>,
)

object TripSpeedAnalysis {

    /** Below this speed a sample counts as stopped (km/h). */
    const val STOP_SPEED_KMH = 1.0

    /** Histogram bucket width (km/h). */
    private const val BUCKET_WIDTH = 5.0

    fun analyze(points: List<ServerTrackPoint>): TripSpeedProfile {
        var cumulative = 0.0
        val samples = points.map { pt ->
            val segment = pt.segmentDistanceMeters
            if (segment != null && segment > 0.0) {
                cumulative += segment
            }
            SpeedSample(
                index = pt.index,
                speedKmh = pt.speedKmh,
                cumulativeDistanceMeters = cumulative,
                segmentDistanceMeters = pt.segmentDistanceMeters,
            )
        }

        val speeds = samples.mapNotNull { it.speedKmh }
        val sorted = speeds.sorted()
        val moving = speeds.filter { it >= STOP_SPEED_KMH }
        val totalDistance = points.mapNotNull { it.segmentDistanceMeters }
            .filter { it > 0.0 }
            .sum()
            .takeIf { points.any { p -> p.segmentDistanceMeters != null && p.segmentDistanceMeters > 0.0 } }

        val stats = TripSpeedStats(
            sampleCount = samples.size,
            speedSampleCount = speeds.size,
            averageKmh = speeds.takeIf { it.isNotEmpty() }?.average(),
            movingAverageKmh = moving.takeIf { it.isNotEmpty() }?.average(),
            maxKmh = sorted.lastOrNull(),
            minKmh = sorted.firstOrNull(),
            medianKmh = median(sorted),
            p90Kmh = percentile(sorted, 0.9),
            totalDistanceMeters = totalDistance,
            stopRatio = if (speeds.isEmpty()) {
                null
            } else {
                speeds.count { it < STOP_SPEED_KMH }.toDouble() / speeds.size
            },
        )

        return TripSpeedProfile(
            samples = samples,
            stats = stats,
            histogram = histogram(speeds),
        )
    }

    /** Standard median (average of two middle values when even). */
    fun median(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Nearest-rank percentile: smallest value at or above the requested fraction of samples. */
    fun percentile(sorted: List<Double>, fraction: Double): Double? {
        if (sorted.isEmpty()) return null
        if (fraction <= 0.0) return sorted.first()
        if (fraction >= 1.0) return sorted.last()
        val rank = kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun histogram(
        speeds: List<Double>,
        bucketWidth: Double = BUCKET_WIDTH,
    ): List<SpeedHistogramBucket> {
        if (speeds.isEmpty()) return emptyList()
        val max = speeds.max()
        val bucketCount = maxOf(1, kotlin.math.floor(max / bucketWidth).toInt() + 1)
        val counts = IntArray(bucketCount)
        speeds.forEach { speed ->
            val idx = (speed / bucketWidth).toInt().coerceIn(0, bucketCount - 1)
            counts[idx] += 1
        }
        val total = speeds.size.toDouble()
        return counts.mapIndexed { index, count ->
            val from = index * bucketWidth
            val to = (index + 1) * bucketWidth
            SpeedHistogramBucket(
                fromKmh = from,
                toKmh = to,
                count = count,
                fraction = count / total,
            )
        }
    }

    /**
     * Relative speed in 0..1 for bar length / color scale.
     * Uses [maxKmh] as the scale top; stopped samples stay near 0.
     */
    fun relativeSpeed(speedKmh: Double?, maxKmh: Double?): Double {
        if (speedKmh == null || speedKmh <= 0.0) return 0.0
        val top = (maxKmh ?: return 1.0).takeIf { it > 0.0 } ?: return 1.0
        return (speedKmh / top).coerceIn(0.0, 1.0)
    }
}
