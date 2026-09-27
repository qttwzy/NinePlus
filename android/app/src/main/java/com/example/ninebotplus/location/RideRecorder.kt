package com.example.ninebotplus.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import com.example.ninebotplus.domain.RecordedRide
import com.example.ninebotplus.domain.RideTrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.UUID

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
 * Lifecycle:
 * - STOPPED: no GPS listener
 * - PREVIEWING: listener while Recording screen visible
 * - RECORDING: listener + foreground service
 *
 * Responsibility split (no recursion):
 * - [finishRecording] only finalizes ride data + GPS + ActiveRideStore.
 *   It does NOT start/stop the Service.
 * - [RideRecordingService] owns its own foreground lifecycle and calls
 *   finishRecording / restore as needed, then stopSelf.
 */
class RideRecorder private constructor(
    private val context: Context,
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val activeStore = ActiveRideStore(context)

    private val _session = MutableStateFlow(RecordingSessionState())
    val session: StateFlow<RecordingSessionState> = _session.asStateFlow()

    private val points = mutableListOf<RideTrackPoint>()
    private var vehicleSn: String? = null
    private var startedAt: Date? = null
    private var previousLocation: Location? = null

    /** Speed BEFORE the current sample was applied. Used for G calculation. */
    private var previousSpeedKmh = 0.0

    /** Incremental distance so we never rescan the whole track per sample. */
    private var incrementalDistanceMeters = 0.0

    /** How many points are already checkpointed to disk. */
    private var persistedPointCount = 0

    private var lastSampleAt = 0L
    private var previewing = false
    private var recording = false
    private var listenerRegistered = false

    private val locationListener = LocationListener { location ->
        onLocation(location)
    }

    enum class State { STOPPED, PREVIEWING, RECORDING }

    val state: State
        get() = when {
            recording -> State.RECORDING
            previewing -> State.PREVIEWING
            else -> State.STOPPED
        }

    val isRecording: Boolean get() = recording

    fun startPreview() {
        if (recording) return
        if (previewing && listenerRegistered) return
        if (!hasLocationPermission()) {
            _session.value = _session.value.copy(gpsQuality = "定位不可用")
            return
        }
        previewing = true
        registerListener()
    }

    /** Stop GPS when leaving the Recording screen and not recording. */
    fun stopPreview() {
        if (recording) return
        previewing = false
        unregisterListener()
        _session.value = _session.value.copy(gpsQuality = "等待 GPS")
    }

    fun start(vehicleSn: String?) {
        if (recording) return
        if (!hasLocationPermission()) {
            _session.value = _session.value.copy(gpsQuality = "需要定位权限")
            return
        }
        this.vehicleSn = vehicleSn
        startedAt = Date()
        points.clear()
        previousLocation = null
        previousSpeedKmh = 0.0
        incrementalDistanceMeters = 0.0
        persistedPointCount = 0
        lastSampleAt = 0L
        recording = true
        previewing = true
        _session.value = RecordingSessionState(
            isRecording = true,
            startedAt = startedAt,
            gpsQuality = "校准中",
        )
        registerListener()
        activeStore.beginSession(vehicleSn)
    }

    /**
     * Finalize ride data. Idempotent: a second call returns null and does not
     * re-clear the store. Does NOT touch the Service lifecycle.
     */
    fun finishRecording(vehicleSn: String? = null): RecordedRide? {
        if (!recording && startedAt == null) return null

        val started = startedAt
        recording = false

        if (started == null) {
            // Defensive: inconsistent state; clean up without producing a ride.
            previewing = false
            unregisterListener()
            activeStore.clear()
            persistedPointCount = 0
            incrementalDistanceMeters = 0.0
            _session.value = RecordingSessionState(isRecording = false, gpsQuality = "等待 GPS")
            return null
        }

        val ended = Date()
        val state = _session.value
        val snapshot = points.toList()
        val distance = incrementalDistanceMeters.takeIf { it > 0 }
            ?: RecordedRide.recalculatedDistanceMeters(snapshot).takeIf { it > 0 }
            ?: state.distanceMeters
        val durationSeconds = maxOf((ended.time - started.time) / 1000.0, 0.0)
        val averageSpeed = if (durationSeconds > 0) {
            (distance / durationSeconds) * 3.6
        } else {
            snapshot.map { it.speedKmh }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        }

        val ride = RecordedRide(
            id = activeStore.sessionId() ?: UUID.randomUUID().toString(),
            vehicleSn = vehicleSn ?: this.vehicleSn,
            associatedRideId = null,
            startedAt = started,
            endedAt = ended,
            distanceMeters = distance,
            maxSpeedKmh = state.maxSpeedKmh,
            averageSpeedKmh = averageSpeed,
            maxAccelerationG = state.maxAccelerationG,
            points = snapshot,
        )

        activeStore.clear()
        points.clear()
        startedAt = null
        previousLocation = null
        previousSpeedKmh = 0.0
        incrementalDistanceMeters = 0.0
        persistedPointCount = 0
        previewing = false
        unregisterListener()
        _session.value = RecordingSessionState(isRecording = false, gpsQuality = "等待 GPS")
        return ride
    }

    fun currentPoints(): List<RideTrackPoint> = points.toList()

    /**
     * Restore an in-progress session after process death.
     * Called from RideRecordingService.onStartCommand — the single restore entry.
     * Returns true when a recording session was restored.
     */
    fun restoreActiveSession(): Boolean {
        if (recording) return true
        val persisted = activeStore.loadSession() ?: return false

        vehicleSn = persisted.vehicleSn
        startedAt = persisted.startedAt
        points.clear()
        points.addAll(persisted.points)
        previousSpeedKmh = points.lastOrNull()?.speedKmh ?: 0.0
        incrementalDistanceMeters = RecordedRide.recalculatedDistanceMeters(persisted.points)
        persistedPointCount = points.size
        lastSampleAt = points.lastOrNull()?.date?.time ?: 0L
        recording = true
        previewing = true

        _session.value = RecordingSessionState(
            isRecording = true,
            startedAt = startedAt,
            speedKmh = previousSpeedKmh,
            maxSpeedKmh = points.maxOfOrNull { it.speedKmh } ?: 0.0,
            maxAccelerationG = points.maxOfOrNull { it.accelerationG } ?: 0.0,
            distanceMeters = incrementalDistanceMeters,
            durationSeconds = maxOf(
                (System.currentTimeMillis() - persisted.startedAt.time) / 1000.0,
                0.0,
            ),
            pointCount = points.size,
            gpsQuality = "校准中",
        )
        registerListener()
        return true
    }

    fun hasActivePersistedSession(): Boolean = activeStore.hasSession()

    private fun onLocation(location: Location) {
        if (!RideMath.isSampleAcceptable(location.accuracy.toDouble())) {
            if (recording) {
                _session.value = _session.value.copy(gpsQuality = "GPS 弱")
            }
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMs = if (lastSampleAt == 0L) 0L else now - lastSampleAt
        lastSampleAt = now

        val derivedSpeed = previousLocation?.let {
            RideMath.deriveSpeedKmh(it.distanceTo(location).toDouble(), elapsedMs)
        }
        val systemSpeed = if (location.hasSpeed()) location.speed * 3.6 else null
        val rawSpeed = (systemSpeed ?: derivedSpeed ?: previousSpeedKmh)
            .coerceIn(0.0, RideMath.MAX_SPEED_KMH)

        val speedBefore = previousSpeedKmh
        val smoothedSpeed = RideMath.smoothSpeed(speedBefore, rawSpeed)

        var g = RideMath.accelerationG(speedBefore, smoothedSpeed, elapsedMs)
        g = RideMath.stationaryGate(g, speedBefore, smoothedSpeed)

        previousSpeedKmh = smoothedSpeed

        val distanceDelta = previousLocation?.let {
            it.distanceTo(location).toDouble()
        } ?: 0.0

        if (recording && elapsedMs in 200..8_000 && distanceDelta in 0.0..160.0) {
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
            // Incremental distance: only previous ↔ current segment.
            if (points.size >= 2 && distanceDelta > 0) {
                incrementalDistanceMeters += distanceDelta
            }
            // Checkpoint only new points (O(1) append, no full rewrite).
            if (points.size - persistedPointCount >= CHECKPOINT_INTERVAL) {
                activeStore.appendPoints(points.drop(persistedPointCount))
                persistedPointCount = points.size
            }
        }

        previousLocation = location

        val state = _session.value
        val duration = state.startedAt?.let {
            (now - it.time) / 1000.0
        } ?: 0.0

        _session.value = state.copy(
            speedKmh = smoothedSpeed,
            maxSpeedKmh = maxOf(state.maxSpeedKmh, smoothedSpeed),
            accelerationG = g,
            maxAccelerationG = maxOf(state.maxAccelerationG, g),
            distanceMeters = incrementalDistanceMeters,
            durationSeconds = duration,
            pointCount = points.size,
            gpsQuality = if (location.accuracy < 20f) "GPS 稳定" else "GPS 弱",
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun registerListener() {
        if (listenerRegistered) return
        if (!hasLocationPermission()) return
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                1f,
                locationListener,
            )
            listenerRegistered = true
        }
    }

    private fun unregisterListener() {
        if (!listenerRegistered) return
        runCatching { locationManager.removeUpdates(locationListener) }
        listenerRegistered = false
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
        private const val CHECKPOINT_INTERVAL = 12

        @Volatile
        private var instance: RideRecorder? = null

        fun get(context: Context): RideRecorder =
            instance ?: synchronized(this) {
                instance ?: RideRecorder(context.applicationContext).also { instance = it }
            }
    }
}
