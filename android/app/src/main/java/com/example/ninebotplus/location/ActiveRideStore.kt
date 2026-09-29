package com.example.ninebotplus.location

import android.content.Context
import com.example.ninebotplus.data.NinePlusDatabase
import com.example.ninebotplus.data.TrackPointCodec
import com.example.ninebotplus.data.TrackPointPayload
import com.example.ninebotplus.domain.RideTrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Crash-safe persistence for an in-progress ride.
 *
 * Design:
 * - session metadata + compact start marker in a small JSON file
 * - track points checkpointed as JSONL append (no full rewrite per sample)
 * - on stop, the file is cleared and the finalized RecordedRide is saved to Room
 * - on process recreation, [loadSession] rebuilds the active session
 */
class ActiveRideStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "active_ride").apply { mkdirs() }
    private val sessionFile = File(dir, "session.json")
    private val pointsFile = File(dir, "points.jsonl")

    private val _hasSession = MutableStateFlow(false)
    val hasSessionFlow: StateFlow<Boolean> = _hasSession

    fun beginSession(vehicleSn: String?) {
        val id = UUID.randomUUID().toString()
        sessionFile.writeText(
            """{"id":"$id","vehicleSn":${vehicleSn?.let { "\"$it\"" } ?: "null"},"startedAt":${System.currentTimeMillis()}}""",
        )
        pointsFile.delete()
        pointsFile.writeText("")
        _hasSession.value = true
    }

    fun sessionId(): String? = readSessionMeta()?.first

    fun hasSession(): Boolean {
        val exists = sessionFile.exists() && sessionFile.length() > 0
        _hasSession.value = exists
        return exists
    }

    fun checkpoint(points: List<RideTrackPoint>) {
        appendPoints(points)
    }

    /** Append only the given points (no full rewrite). */
    fun appendPoints(points: List<RideTrackPoint>) {
        if (points.isEmpty()) return
        val payload = points.joinToString("\n") { point ->
            val p = TrackPointPayload(
                id = point.id,
                date = point.date.time,
                latitude = point.latitude,
                longitude = point.longitude,
                speedKmh = point.speedKmh,
                accelerationG = point.accelerationG,
                horizontalAccuracy = point.horizontalAccuracy,
            )
            TrackPointCodec.encode(listOf(p)).trim('[', ']')
        }
        pointsFile.appendText(payload + "\n")
    }

    fun loadSession(): ActiveRideSnapshot? {
        val (id, vehicleSn, startedAt) = readSessionMeta() ?: return null
        val points = pointsFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching {
                    TrackPointCodec.decode("[$line]").firstOrNull()
                }.getOrNull()
            }
            .map {
                RideTrackPoint(
                    id = it.id,
                    date = Date(it.date),
                    latitude = it.latitude,
                    longitude = it.longitude,
                    speedKmh = it.speedKmh,
                    accelerationG = it.accelerationG,
                    horizontalAccuracy = it.horizontalAccuracy,
                )
            }
        return ActiveRideSnapshot(
            id = id,
            vehicleSn = vehicleSn,
            startedAt = Date(startedAt),
            points = points,
        )
    }

    fun clear() {
        sessionFile.delete()
        pointsFile.delete()
        _hasSession.value = false
    }

    private fun countPersistedPoints(): Int {
        if (!pointsFile.exists()) return 0
        return pointsFile.readLines().count { it.isNotBlank() }
    }

    private fun readSessionMeta(): Triple<String, String?, Long>? {
        if (!sessionFile.exists()) return null
        val raw = runCatching { sessionFile.readText() }.getOrNull() ?: return null
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: return null
        val sn = Regex("\"vehicleSn\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        val startedAt = Regex("\"startedAt\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        return Triple(id, sn, startedAt)
    }
}

data class ActiveRideSnapshot(
    val id: String,
    val vehicleSn: String?,
    val startedAt: Date,
    val points: List<RideTrackPoint>,
)
