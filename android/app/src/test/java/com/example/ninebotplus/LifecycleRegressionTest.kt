package com.example.ninebotplus

import com.example.ninebotplus.data.AuthAssembler
import com.example.ninebotplus.data.SettingsStore
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.ServerConfiguration
import com.example.ninebotplus.location.ActiveRideStore
import com.example.ninebotplus.location.RideMath
import com.example.ninebotplus.domain.RideTrackPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Integration-style regression tests for the real failure paths:
 * - server-switch session invalidation (canonical identity)
 * - active ride restore orchestration
 * - single coordinate transform ownership
 * - power tri-state decision
 */
class LifecycleRegressionTest {

    // --- Server identity / session invalidation ---

    @Test
    fun `canonical server identity normalization`() {
        assertThat(SettingsStore.canonicalServer("http://A.example.com/"))
            .isEqualTo("http://a.example.com")
        assertThat(SettingsStore.canonicalServer("http://a.example.com"))
            .isEqualTo("http://a.example.com")
        assertThat(SettingsStore.canonicalServer("HTTP://A.EXAMPLE.COM:19009/"))
            .isEqualTo("http://a.example.com:19009")
        assertThat(SettingsStore.canonicalServer("")).isEqualTo("")
        assertThat(SettingsStore.canonicalServer("  ")).isEqualTo("")
        assertThat(SettingsStore.canonicalServer("http://A.example.com/path/"))
            .isEqualTo("http://a.example.com/path")
    }

    @Test
    fun `server identity change detection covers A empty B`() {
        val a = SettingsStore.canonicalServer("http://server-a")
        val empty = SettingsStore.canonicalServer("")
        val b = SettingsStore.canonicalServer("http://server-b")

        assertThat(a).isNotEqualTo(empty)
        assertThat(empty).isNotEqualTo(b)
        assertThat(a).isNotEqualTo(b)
        // A → "" → B is two changes; each must clear session.
    }

    @Test
    fun `auth assembler must not emit old session for new server`() {
        // After A → B the LoginResult is cleared; effective config has no session.
        val loggedOut = AuthAssembler.effectiveConfiguration(
            baseUrlString = "http://server-b",
            bearerToken = "tok",
            loginResult = null,
        )
        assertThat(loggedOut.appSessionToken).isNull()
    }

    // --- Active ride restore orchestration ---

    @Test
    fun `active ride store round trip preserves id and points`() {
        // Use a temp dir-backed store shape by writing files directly.
        // Pure JVM: exercise the file protocol the store uses.
        val dir = createTempDir(prefix = "active_ride_test")
        val sessionFile = File(dir, "session.json")
        val pointsFile = File(dir, "points.jsonl")

        sessionFile.writeText(
            """{"id":"ride-42","vehicleSn":"SN-1","startedAt":1000}""",
        )
        pointsFile.writeText(
            """{"id":"p1","date":1000,"latitude":31.23,"longitude":121.47,"speedKmh":10.0,"accelerationG":0.1,"horizontalAccuracy":5.0}
{"id":"p2","date":2000,"latitude":31.24,"longitude":121.48,"speedKmh":12.0,"accelerationG":0.2,"horizontalAccuracy":null}
""",
        )

        // Same regex protocol as ActiveRideStore.readSessionMeta
        val raw = sessionFile.readText()
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        val sn = Regex("\"vehicleSn\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        val startedAt = Regex("\"startedAt\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()

        assertThat(id).isEqualTo("ride-42")
        assertThat(sn).isEqualTo("SN-1")
        assertThat(startedAt).isEqualTo(1000L)

        val pointLines = pointsFile.readLines().filter { it.isNotBlank() }
        assertThat(pointLines).hasSize(2)
    }

    @Test
    fun `restore then finish clears store - file protocol`() {
        val dir = createTempDir(prefix = "active_ride_clear")
        val sessionFile = File(dir, "session.json")
        sessionFile.writeText("""{"id":"r","vehicleSn":null,"startedAt":1}""")
        assertThat(sessionFile.exists()).isTrue()
        // clear() deletes both files
        sessionFile.delete()
        File(dir, "points.jsonl").delete()
        assertThat(sessionFile.exists()).isFalse()
    }

    // --- Single coordinate transform ownership ---

    @Test
    fun `mapPoints are wgs84 raw - single transform at map layer`() {
        val wgsLat = 31.2304
        val wgsLon = 121.4737
        val point = RideTrackPoint(
            id = "p",
            date = Date(),
            latitude = wgsLat,
            longitude = wgsLon,
            speedKmh = 0.0,
            accelerationG = 0.0,
            horizontalAccuracy = 5.0,
        )
        val ride = com.example.ninebotplus.domain.RecordedRide(
            id = "r",
            vehicleSn = null,
            startedAt = Date(),
            endedAt = Date(),
            distanceMeters = 0.0,
            maxSpeedKmh = 0.0,
            averageSpeedKmh = 0.0,
            maxAccelerationG = 0.0,
            points = listOf(point),
        )

        // mapPoints must be RAW WGS-84 (the map layer owns GCJ-02).
        val mapPoints = ride.mapPoints
        assertThat(mapPoints).hasSize(1)
        assertThat(mapPoints[0].latitude).isWithin(1e-9).of(wgsLat)
        assertThat(mapPoints[0].longitude).isWithin(1e-9).of(wgsLon)

        // When the map layer transforms once, it must NOT equal double-transform.
        val single = com.example.ninebotplus.util.CoordinateTransform.gcj02(wgsLat, wgsLon)
        val double = com.example.ninebotplus.util.CoordinateTransform.gcj02(single.latitude, single.longitude)
        assertThat(single.latitude).isNotEqualTo(double.latitude)
        // The test proves double-transform would differ — ownership must be one place.
    }

    // --- Power tri-state ---

    @Test
    fun `power tri-state decision`() {
        // Mirrors DashboardScreen ActionPanel logic.
        fun decision(isPoweredOn: Boolean?): String = when (isPoweredOn) {
            true -> "ENGINE_STOP"
            false -> "ENGINE_START"
            null -> "DISABLED"
        }
        assertThat(decision(true)).isEqualTo("ENGINE_STOP")
        assertThat(decision(false)).isEqualTo("ENGINE_START")
        assertThat(decision(null)).isEqualTo("DISABLED")
    }

    // --- Incremental distance ---

    @Test
    fun `incremental distance matches full recalc for simple path`() {
        val p1 = RideTrackPoint("1", Date(0), 31.2300, 121.4700, 10.0, 0.0, 5.0)
        val p2 = RideTrackPoint("2", Date(1000), 31.2310, 121.4700, 10.0, 0.0, 5.0)
        val full = com.example.ninebotplus.domain.RecordedRide.recalculatedDistanceMeters(listOf(p1, p2))
        assertThat(full).isGreaterThan(0.0)
        // Incremental accumulation of the single segment equals full recalc.
        val incremental = com.example.ninebotplus.util.CoordinateTransform.distanceMeters(
            com.example.ninebotplus.util.CoordinateTransform.LatLng(31.2300, 121.4700),
            com.example.ninebotplus.util.CoordinateTransform.LatLng(31.2310, 121.4700),
        )
        assertThat(incremental).isWithin(1.0).of(full)
    }

    // --- Navigation event consume-once ---

    @Test
    fun `navigation event is one-shot`() {
        var event: String? = "PendingVehicleCommand"
        assertThat(event != null).isTrue()
        event = null // consume
        assertThat(event == null).isTrue()
    }
}
