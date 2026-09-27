package com.example.ninebotplus

import com.example.ninebotplus.network.JsonValue
import com.example.ninebotplus.network.PayloadParser
import com.example.ninebotplus.network.NinePlusJson
import com.example.ninebotplus.util.CoordinateTransform
import com.example.ninebotplus.util.NineplusDates
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PayloadParserTest {

    private fun json(text: String): JsonValue =
        JsonValue.from(NinePlusJson.parseToJsonElement(text))

    @Test
    fun `unwrap envelope success`() {
        val root = json("""{"ok":true,"data":{"a":1}}""")
        val result = PayloadParser.unwrapEnvelope(root)
        assertThat(result["a"]?.intValue).isEqualTo(1)
    }

    @Test
    fun `unwrap envelope error`() {
        val root = json("""{"ok":false,"error":{"message":"boom"}}""")
        try {
            PayloadParser.unwrapEnvelope(root)
            throw AssertionError("expected throw")
        } catch (e: Exception) {
            assertThat(e.message).contains("boom")
        }
    }

    @Test
    fun `vehicle info snake and camel`() {
        val snake = json("""{"wnumber":"SN1","device_name":"小黑","vehicle_name":"E200P"}""")
        val v1 = PayloadParser.vehicleInfo(snake)!!
        assertThat(v1.sn).isEqualTo("SN1")
        assertThat(v1.name).isEqualTo("小黑")

        val camel = json("""{"sn":"SN2","deviceName":"N2","vehicleModel":"Moped"}""")
        val v2 = PayloadParser.vehicleInfo(camel)!!
        assertThat(v2.sn).isEqualTo("SN2")
        assertThat(v2.name).isEqualTo("N2")
    }

    @Test
    fun `has vehicle status and battery`() {
        val status = json("""{"status":{"dump_energy":80,"pwr":0}}""")
        assertThat(PayloadParser.hasVehicleStatus(status)).isTrue()

        val battery = json("""{"battery_info":{"electricity":70,"bms_volt":52.1}}""")
        assertThat(PayloadParser.hasBatteryData(battery)).isTrue()
    }

    @Test
    fun `vehicle state parses mixed fields`() {
        val status = json(
            """
            {"status":{
              "dump_energy":86,
              "precise_estimate_mileage":42.5,
              "charging":0,
              "pwr":1,
              "lock_status":1,
              "loc":{"lat":31230400,"lon":121473700},
              "total_mileage":1048.9
            }}
            """.trimIndent(),
        )
        val travel = json(
            """
            {"total_mileages":128.4,"ec":3.2,"used_electricity":2.8,
             "list":[{"start_time":"2025-01-01 08:00:00","end_time":"2025-01-01 08:10:00",
                      "mileages":4.6,"ec":200,"used_electricity":4}],
             "detail":[1.2,3.4,5.6]}
            """.trimIndent(),
        )
        val battery = json("""{"battery_list":[{"bms_volt":52300,"bat_temp":285,"bms_cycle":36}]}""")
        val state = PayloadParser.vehicleState(status, travel, battery, null, java.util.Date())
        assertThat(state.battery).isEqualTo(86)
        assertThat(state.endurance).isEqualTo(42.5)
        assertThat(state.isPoweredOn).isTrue()
        assertThat(state.isLocked).isTrue()
        assertThat(state.monthMileage).isEqualTo(128.4)
        assertThat(state.rides).hasSize(1)
        assertThat(state.dailyMileages).hasSize(3)
        // lat/lon scaled from 1e6 integers
        assertThat(state.latitude).isWithin(0.01).of(31.2304)
        assertThat(state.longitude).isWithin(0.01).of(121.4737)
    }

    @Test
    fun `normalized battery voltage and temperature`() {
        assertThat(PayloadParser.normalizedBatteryVoltage(52300.0)).isWithin(0.1).of(52.3)
        assertThat(PayloadParser.normalizedBatteryVoltage(285.0)).isWithin(0.1).of(28.5)
        assertThat(PayloadParser.normalizedBatteryTemperature(285.0)).isWithin(0.1).of(28.5)
    }

    @Test
    fun `ride record duration fallback from times`() {
        val value = json(
            """
            {"start_time":"2025-01-01 08:00:00","end_time":"2025-01-01 08:10:00",
             "mileages":4.6,"used_electricity":4}
            """.trimIndent(),
        )
        val record = PayloadParser.rideRecord(value, 0)!!
        assertThat(record.durationMinutes).isWithin(0.5).of(10.0)
        assertThat(record.mileage).isEqualTo(4.6)
    }

    @Test
    fun `clock duration parsing`() {
        // 2-part clock is M:S (mirrors iOS clockDurationMinutes)
        val twoPart = PayloadParser.rideRecord(json("""{"duration":"30:00","mileages":1.0}"""), 0)!!
        assertThat(twoPart.durationMinutes).isWithin(0.1).of(30.0)

        // 3-part is H:M:S
        val threePart = PayloadParser.rideRecord(json("""{"duration":"1:05:00","mileages":1.0}"""), 0)!!
        assertThat(threePart.durationMinutes).isWithin(0.1).of(65.0)
    }

    @Test
    fun `login result aliases`() {
        val value = json(
            """{"uuid":"u1","phone":"138","area_code":"+86","session_token":"tok","id":9}""",
        )
        val result = PayloadParser.loginResult(value)
        assertThat(result.sessionToken).isEqualTo("tok")
        assertThat(result.accountId).isEqualTo(9)
    }

    @Test
    fun `prediction parses range and charging`() {
        val value = json(
            """
            {"range":{"estimated_range_km":40.5,"km_per_percent":0.5,"sample_count":12,"ready":true},
             "charging":{"is_charging":true,"remaining_minutes":90,"estimated_full_at":"2025-01-01 10:00:00"}}
            """.trimIndent(),
        )
        val prediction = PayloadParser.serverPrediction(value)!!
        assertThat(prediction.range.estimatedRange).isEqualTo(40.5)
        assertThat(prediction.charging.isCharging).isTrue()
        assertThat(prediction.charging.remainingMinutes).isEqualTo(90.0)
    }
}

class CoordinateTransformTest {
    @Test
    fun `outside china unchanged`() {
        val result = CoordinateTransform.gcj02(40.7128, -74.0060)
        assertThat(result.latitude).isEqualTo(40.7128)
        assertThat(result.longitude).isEqualTo(-74.0060)
    }

    @Test
    fun `inside china shifts slightly`() {
        val result = CoordinateTransform.gcj02(31.2304, 121.4737)
        assertThat(result.latitude).isGreaterThan(31.22)
        assertThat(result.latitude).isLessThan(31.25)
        assertThat(result.longitude).isGreaterThan(121.46)
        assertThat(result.longitude).isLessThan(121.49)
    }

    @Test
    fun `distance is positive for distinct points`() {
        val d = CoordinateTransform.distanceMeters(
            CoordinateTransform.LatLng(31.23, 121.47),
            CoordinateTransform.LatLng(31.24, 121.48),
        )
        assertThat(d).isGreaterThan(100.0)
    }
}

class NineplusDatesTest {
    @Test
    fun `month strings from auth date`() {
        val start = NineplusDates.parse(
            com.example.ninebotplus.util.JsonDateInput.TextValue("2024-11-15 10:00:00"),
        )!!
        val months = NineplusDates.monthStrings(start, java.util.Date())
        assertThat(months.first()).isEqualTo("202411")
        assertThat(months).contains(NineplusDates.currentMonthString())
    }

    @Test
    fun `previous month`() {
        assertThat(NineplusDates.previousMonth("202501")).isEqualTo("202412")
        assertThat(NineplusDates.previousMonth("202503")).isEqualTo("202502")
    }

    @Test
    fun `parse compact china date`() {
        val date = NineplusDates.parse(
            com.example.ninebotplus.util.JsonDateInput.TextValue("20250101123000"),
        )
        assertThat(date).isNotNull()
    }
}
