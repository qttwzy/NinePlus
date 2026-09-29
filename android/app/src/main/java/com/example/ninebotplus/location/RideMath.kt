package com.example.ninebotplus.location

import kotlin.math.abs

/**
 * Pure ride-sample math. Extracted from [RideRecorder] so it can be unit-tested
 * without Android LocationManager.
 */
object RideMath {

    /** Reject samples that are too inaccurate to trust. */
    fun isSampleAcceptable(horizontalAccuracyMeters: Double, maxAccuracy: Double = 60.0): Boolean =
        horizontalAccuracyMeters <= maxAccuracy

    /** Maximum plausible speed for an e-moped (km/h). */
    const val MAX_SPEED_KMH = 132.0

    /** Maximum plausible GPS acceleration (G). */
    const val MAX_ACCEL_G = 0.75

    /** Maximum plausible sensor acceleration (G). */
    const val MAX_SENSOR_G = 1.35

    /**
     * Derive speed (km/h) from two GPS fixes.
     * Returns null when the segment is not trustworthy.
     */
    fun deriveSpeedKmh(
        distanceMeters: Double,
        elapsedMillis: Long,
    ): Double? {
        if (elapsedMillis !in 450L..6_000L) return null
        if (distanceMeters < 0 || distanceMeters > 160) return null
        return (distanceMeters / (elapsedMillis / 1000.0)) * 3.6
    }

    /**
     * EMA speed smoothing with rate-of-change caps.
     * Acceleration cap ~4.5 m/s², deceleration cap ~7 m/s² over one second.
     */
    fun smoothSpeed(previousKmh: Double, rawKmh: Double): Double {
        val alpha = if (rawKmh > previousKmh) 0.34 else 0.48
        val smoothed = previousKmh + alpha * (rawKmh - previousKmh)
        val maxDeltaAccel = 4.5 * 3.6   // km/h per second
        val maxDeltaDecel = 7.0 * 3.6
        val delta = smoothed - previousKmh
        return when {
            delta > maxDeltaAccel -> previousKmh + maxDeltaAccel
            delta < -maxDeltaDecel -> previousKmh - maxDeltaDecel
            else -> smoothed
        }
    }

    /**
     * Acceleration in G from two consecutive speed samples.
     *
     * IMPORTANT: callers must pass the speed BEFORE it was overwritten as
     * [previousKmh] and the NEW speed as [currentKmh]. Using the same value
     * for both yields zero — this was a real bug in the first Android port.
     */
    fun accelerationG(previousKmh: Double, currentKmh: Double, elapsedMillis: Long): Double {
        if (elapsedMillis <= 0) return 0.0
        val deltaMs = (currentKmh / 3.6) - (previousKmh / 3.6)
        val accel = abs(deltaMs) / (elapsedMillis / 1000.0)
        return (accel / 9.81).coerceIn(0.0, MAX_SENSOR_G)
    }

    /** Stationary noise gate: tiny wobble should not register as G. */
    fun stationaryGate(g: Double, previousKmh: Double, currentKmh: Double): Double {
        val speedDelta = abs(currentKmh - previousKmh)
        return if (speedDelta < 0.5 && g < 0.025) 0.0 else g
    }
}
