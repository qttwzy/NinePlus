package com.example.ninebotplus.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.example.ninebotplus.domain.RecordedRide
import com.example.ninebotplus.domain.RideTrackPoint
import com.example.ninebotplus.util.CoordinateTransform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class RecordingSessionState(
    val isRecording: Boolean = false,
    val startedAt: Date? = null,
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val accelerationG: Double = 0.0,
    val maxAccelerationG: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val pointCount: Int = 0,
    val gpsQuality: String = "等待 GPS",
)

/**
 * Local ride recorder.
 *
 * Android semantics of iOS NinebotRideRecorder:
 * - high-accuracy GPS sampling with distance filter 1m
 * - sample quality filtering (accuracy > 60m rejected)
 * - speed from system speed when reliable, else derived from consecutive fixes
 * - EMA smoothing + acceleration caps
 * - distance accumulation only on reliable segments
 *
 * Runtime lives in [RideRecordingService] (foreground) while recording; this
 * singleton holds the session state shared with UI.
 */
class RideRecorder private constructor(
    private val context: Context,
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _session = MutableStateFlow(RecordingSessionState())
    val session: StateFlow<RecordingSessionState> = _session.asStateFlow()

    private val points = mutableListOf<RideTrackPoint>()
    private var vehicleSn: String? = null
    private var startedAt: Date? = null
    private var previousLocation: Location? = null
    private var previousSpeed = 0.0
    private var lastSampleAt = 0L
    private var previewing = false

    private val locationListener = LocationListener { location ->
        onLocation(location)
    }

    fun startPreview() {
        if (previewing) return
        if (!hasLocationPermission()) return
        previewing = true
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                1f,
                locationListener,
            )
        }
    }

    fun start(vehicleSn: String?) {
        if (!hasLocationPermission()) {
            _session.value = _session.value.copy(gpsQuality = "需要定位权限")
            return
        }
        this.vehicleSn = vehicleSn
        startedAt = Date()
        points.clear()
        previousLocation = null
        previousSpeed = 0.0
        lastSampleAt = 0L
        _session.value = RecordingSessionState(
            isRecording = true,
            startedAt = startedAt,
            gpsQuality = "校准中",
        )
        startPreview()
        RideRecordingService.start(context)
    }

    fun stop(vehicleSn: String? = null): RecordedRide? {
        val started = startedAt ?: return null
        val ended = Date()
        val state = _session.value
        val distance = RecordedRide.recalculatedDistanceMeters(points)
            .takeIf { it > 0 } ?: state.distanceMeters
        val durationSeconds = maxOf((ended.time - started.time) / 1000.0, 0.0)
        val averageSpeed = if (durationSeconds > 0) {
            (distance / durationSeconds) * 3.6
        } else {
            points.map { it.speedKmh }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        }

        val ride = RecordedRide(
            id = UUID.randomUUID().toString(),
            vehicleSn = vehicleSn ?: this.vehicleSn,
            associatedRideId = null,
            startedAt = started,
            endedAt = ended,
            distanceMeters = distance,
            maxSpeedKmh = state.maxSpeedKmh,
            averageSpeedKmh = averageSpeed,
            maxAccelerationG = state.maxAccelerationG,
            points = points.toList(),
        )

        _session.value = RecordingSessionState(isRecording = false, gpsQuality = "等待 GPS")
        startedAt = null
        points.clear()
        RideRecordingService.stop(context)
        return ride
    }

    fun currentPoints(): List<RideTrackPoint> = points.toList()

    private fun onLocation(location: Location) {
        if (location.accuracy > 60f) {
            if (_session.value.isRecording) {
                _session.value = _session.value.copy(gpsQuality = "GPS 弱")
            }
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMs = if (lastSampleAt == 0L) 0L else now - lastSampleAt
        lastSampleAt = now

        val derivedSpeed = deriveSpeed(location, elapsedMs)
        val systemSpeed = if (location.hasSpeed() && hasReliableSpeed(location)) {
            location.speed * 3.6
        } else {
            null
        }
        val rawSpeed = systemSpeed ?: derivedSpeed ?: previousSpeed
        val smoothedSpeed = smoothSpeed(previousSpeed, rawSpeed)
        previousSpeed = smoothedSpeed

        val g = if (elapsedMs > 0 && previousLocation != null && previousSpeed > 0) {
            val accel = abs(smoothedSpeed / 3.6 - previousSpeed / 3.6) / (elapsedMs / 1000.0)
            (accel / 9.81).coerceAtMost(1.35)
        } else {
            0.0
        }

        val distanceDelta = previousLocation?.let {
            it.distanceTo(location).toDouble()
        } ?: 0.0

        if (_session.value.isRecording && elapsedMs in 200..8_000 && distanceDelta in 0.0..160.0) {
            val point = RideTrackPoint(
                id = UUID.randomUUID().toString(),
                date = Date(now),
                latitude = location.latitude,
                longitude = location.longitude,
                speedKmh = smoothedSpeed,
                accelerationG = g,
                horizontalAccuracy = location.accuracy.toDouble(),
            )
            points += point
        }

        previousLocation = location

        val state = _session.value
        val duration = state.startedAt?.let {
            (now - it.time) / 1000.0
        } ?: 0.0
        val totalDistance = if (state.isRecording) {
            RecordedRide.recalculatedDistanceMeters(points)
                .takeIf { it > 0 } ?: (state.distanceMeters + distanceDelta)
        } else {
            0.0
        }

        _session.value = state.copy(
            speedKmh = smoothedSpeed,
            maxSpeedKmh = maxOf(state.maxSpeedKmh, smoothedSpeed),
            accelerationG = g,
            maxAccelerationG = maxOf(state.maxAccelerationG, g),
            distanceMeters = totalDistance,
            durationSeconds = duration,
            pointCount = points.size,
            gpsQuality = if (location.accuracy < 20f) "GPS 稳定" else "GPS 弱",
        )
    }

    private fun deriveSpeed(location: Location, elapsedMs: Long): Double? {
        val previous = previousLocation ?: return null
        if (elapsedMs !in 450..6_000) return null
        val distance = previous.distanceTo(location).toDouble()
        if (distance > 160) return null
        return (distance / (elapsedMs / 1000.0)) * 3.6
    }

    private fun smoothSpeed(previous: Double, raw: Double): Double {
        val alpha = if (raw > previous) 0.34 else 0.48
        val smoothed = previous + alpha * (raw - previous)
        // Cap rate of change: ~4.5 m/s² accel, 7 m/s² decel over 1s.
        val maxDeltaAccel = 4.5 * 3.6
        val maxDeltaDecel = 7.0 * 3.6
        val delta = smoothed - previous
        return when {
            delta > maxDeltaAccel -> previous + maxDeltaAccel
            delta < -maxDeltaDecel -> previous - maxDeltaDecel
            else -> smoothed
        }
    }

    private fun hasReliableSpeed(location: Location): Boolean {
        // speedAccuracy requires API 31; older devices treat reported speed as usable.
        return location.hasSpeed()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        @Volatile
        private var instance: RideRecorder? = null

        fun get(context: Context): RideRecorder =
            instance ?: synchronized(this) {
                instance ?: RideRecorder(context.applicationContext).also { instance = it }
            }
    }
}
