package com.example.ninebotplus

import com.example.ninebotplus.domain.ServerTrackParser
import com.example.ninebotplus.domain.TripSpeedAnalysis
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TripSpeedAnalysisTest {

    private fun trail(vararg segments: String): String = segments.joinToString(";")

    @Test
    fun `analyze builds cumulative distance and speed stats`() {
        val points = ServerTrackParser.parseTrailString(
            trail(
                "121.0,31.0,0.0,10.0",
                "121.001,31.0,20.0,20.0",
                "121.002,31.0,40.0,30.0",
                "121.003,31.0,0.0,0.0",
            ),
        )
        val profile = TripSpeedAnalysis.analyze(points)

        assertThat(profile.samples).hasSize(4)
        assertThat(profile.samples[0].cumulativeDistanceMeters).isWithin(0.01).of(10.0)
        assertThat(profile.samples[1].cumulativeDistanceMeters).isWithin(0.01).of(30.0)
        assertThat(profile.samples[2].cumulativeDistanceMeters).isWithin(0.01).of(60.0)
        assertThat(profile.samples[3].cumulativeDistanceMeters).isWithin(0.01).of(60.0)

        val stats = profile.stats
        assertThat(stats.sampleCount).isEqualTo(4)
        assertThat(stats.speedSampleCount).isEqualTo(4)
        assertThat(stats.averageKmh).isWithin(0.01).of(15.0)
        assertThat(stats.movingAverageKmh).isWithin(0.01).of(30.0)
        assertThat(stats.maxKmh).isWithin(0.01).of(40.0)
        assertThat(stats.minKmh).isWithin(0.01).of(0.0)
        assertThat(stats.totalDistanceMeters).isWithin(0.01).of(60.0)
        assertThat(stats.stopRatio).isWithin(0.01).of(0.5)
        assertThat(stats.medianKmh).isNotNull()
        assertThat(stats.p90Kmh).isAtLeast(stats.medianKmh!!)
    }

    @Test
    fun `missing speeds leave aggregate nulls`() {
        val points = ServerTrackParser.parseTrailString("121.0,31.0;121.1,31.1")
        val profile = TripSpeedAnalysis.analyze(points)
        assertThat(profile.stats.speedSampleCount).isEqualTo(0)
        assertThat(profile.stats.averageKmh).isNull()
        assertThat(profile.stats.maxKmh).isNull()
        assertThat(profile.stats.stopRatio).isNull()
        assertThat(profile.histogram).isEmpty()
    }

    @Test
    fun `histogram buckets cover observed speeds`() {
        val histogram = TripSpeedAnalysis.histogram(listOf(0.5, 3.0, 6.2, 12.0, 12.4))
        assertThat(histogram).isNotEmpty()
        assertThat(histogram.sumOf { it.count }).isEqualTo(5)
        assertThat(histogram[0].count).isEqualTo(2) // 0.5, 3.0
        assertThat(histogram[1].count).isEqualTo(1) // 6.2
        assertThat(histogram[2].count).isEqualTo(2) // 12.0, 12.4
        assertThat(histogram[0].fromKmh).isWithin(0.01).of(0.0)
        assertThat(histogram[0].toKmh).isWithin(0.01).of(5.0)
        assertThat(histogram.sumOf { it.fraction }).isWithin(0.001).of(1.0)
    }

    @Test
    fun `percentile and median on sorted list`() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        assertThat(TripSpeedAnalysis.median(sorted)).isWithin(0.001).of(5.5)
        assertThat(TripSpeedAnalysis.percentile(sorted, 0.5)).isEqualTo(5.0)
        assertThat(TripSpeedAnalysis.percentile(sorted, 0.9)).isEqualTo(9.0)
        assertThat(TripSpeedAnalysis.percentile(emptyList(), 0.5)).isNull()
        assertThat(TripSpeedAnalysis.percentile(listOf(7.5), 0.9)).isEqualTo(7.5)
        assertThat(TripSpeedAnalysis.median(listOf(1.0, 3.0, 5.0))).isEqualTo(3.0)
        assertThat(TripSpeedAnalysis.median(listOf(1.0, 2.0, 3.0, 4.0))).isWithin(0.001).of(2.5)
    }

    @Test
    fun `relative speed scales against max`() {
        assertThat(TripSpeedAnalysis.relativeSpeed(25.0, 50.0)).isWithin(0.001).of(0.5)
        assertThat(TripSpeedAnalysis.relativeSpeed(80.0, 50.0)).isWithin(0.001).of(1.0)
        assertThat(TripSpeedAnalysis.relativeSpeed(0.0, 50.0)).isWithin(0.001).of(0.0)
        assertThat(TripSpeedAnalysis.relativeSpeed(null, 50.0)).isWithin(0.001).of(0.0)
    }
}
