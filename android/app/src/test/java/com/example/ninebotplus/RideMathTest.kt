package com.example.ninebotplus

import com.example.ninebotplus.location.RideMath
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression tests for ride math.
 *
 * The first Android port computed acceleration AFTER overwriting previousSpeed,
 * so G was always 0. These cases lock the correct order of operations.
 */
class RideMathTest {

    @Test
    fun `acceleration is positive when speeding up`() {
        // 0 -> 20 km/h in 2s
        val g = RideMath.accelerationG(previousKmh = 0.0, currentKmh = 20.0, elapsedMillis = 2_000)
        assertThat(g).isGreaterThan(0.05)
        // 5.56 m/s² / 9.81 ≈ 0.57 G
        assertThat(g).isLessThan(RideMath.MAX_SENSOR_G)
    }

    @Test
    fun `acceleration is positive when braking`() {
        // 30 -> 10 km/h in 1s
        val g = RideMath.accelerationG(previousKmh = 30.0, currentKmh = 10.0, elapsedMillis = 1_000)
        assertThat(g).isGreaterThan(0.05)
    }

    @Test
    fun `acceleration is zero when speed unchanged`() {
        val g = RideMath.accelerationG(previousKmh = 20.0, currentKmh = 20.0, elapsedMillis = 1_000)
        assertThat(g).isEqualTo(0.0)
    }

    @Test
    fun `acceleration is zero for invalid elapsed`() {
        assertThat(RideMath.accelerationG(0.0, 50.0, 0)).isEqualTo(0.0)
        assertThat(RideMath.accelerationG(0.0, 50.0, -1)).isEqualTo(0.0)
    }

    @Test
    fun `acceleration is capped`() {
        // Impossible: 0 -> 132 km/h in 100ms
        val g = RideMath.accelerationG(0.0, 132.0, 100)
        assertThat(g).isEqualTo(RideMath.MAX_SENSOR_G)
    }

    @Test
    fun `smooth speed accelerates with lower alpha`() {
        val s1 = RideMath.smoothSpeed(0.0, 20.0)
        assertThat(s1).isGreaterThan(0.0)
        assertThat(s1).isLessThan(20.0)
    }

    @Test
    fun `smooth speed decelerates toward target`() {
        val s1 = RideMath.smoothSpeed(30.0, 10.0)
        assertThat(s1).isLessThan(30.0)
        assertThat(s1).isGreaterThan(10.0)
    }

    @Test
    fun `speed rate of change is capped on hard accel`() {
        // raw jumps to 100 but delta cap should limit the step
        val s = RideMath.smoothSpeed(0.0, 100.0)
        assertThat(s).isAtMost(4.5 * 3.6 + 0.01)
    }

    @Test
    fun `derive speed rejects bad segments`() {
        assertThat(RideMath.deriveSpeedKmh(50.0, 100)).isNull()          // too fast update
        assertThat(RideMath.deriveSpeedKmh(50.0, 10_000)).isNull()      // too slow update
        assertThat(RideMath.deriveSpeedKmh(200.0, 1_000)).isNull()      // jump too far
        val ok = RideMath.deriveSpeedKmh(25.0, 1_000)
        assertThat(ok).isNotNull()
        assertThat(ok!!).isWithin(0.5).of(90.0) // 25 m/s = 90 km/h
    }

    @Test
    fun `sample accuracy filter`() {
        assertThat(RideMath.isSampleAcceptable(5.0)).isTrue()
        assertThat(RideMath.isSampleAcceptable(60.0)).isTrue()
        assertThat(RideMath.isSampleAcceptable(61.0)).isFalse()
    }

    @Test
    fun `stationary noise gate zeros tiny wobble`() {
        val gated = RideMath.stationaryGate(g = 0.01, previousKmh = 5.0, currentKmh = 5.1)
        assertThat(gated).isEqualTo(0.0)
    }

    @Test
    fun `stationary gate keeps real movement`() {
        val kept = RideMath.stationaryGate(g = 0.2, previousKmh = 5.0, currentKmh = 8.0)
        assertThat(kept).isEqualTo(0.2)
    }

    @Test
    fun `sequence 0-20-30-10 produces non-zero G values`() {
        val g1 = RideMath.accelerationG(0.0, 20.0, 2_000)
        val g2 = RideMath.accelerationG(20.0, 30.0, 1_000)
        val g3 = RideMath.accelerationG(30.0, 10.0, 1_000)
        assertThat(g1).isGreaterThan(0.0)
        assertThat(g2).isGreaterThan(0.0)
        assertThat(g3).isGreaterThan(0.0)
    }
}
