package com.example.ninebotplus

import com.example.ninebotplus.network.JsonValue
import com.example.ninebotplus.network.NinePlusJson
import com.example.ninebotplus.network.PayloadParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parser parity with iOS NinebotServerClient.
 * iOS accepts both snake_case and camelCase aliases; Android must too.
 */
class ParserParityTest {

    private fun json(text: String): JsonValue =
        JsonValue.from(NinePlusJson.parseToJsonElement(text))

    @Test
    fun `prediction snake_case aliases`() {
        val value = json(
            """
            {
              "model_version":"m1",
              "updated_at":"2025-01-01 10:00:00",
              "battery_percent":80,
              "battery_chemistry":{"configured":"lithium","effective":"lithium","source":"manual",
                                   "nominal_voltage":72,"capacity_wh":1440,"capacity_ah":20},
              "range":{
                "estimated_range_km":40.5,"local_range_km":38.0,"official_range_km":45.0,
                "source":"personalized","km_per_percent":0.5,"estimated_full_range_km":50.0,
                "sample_count":12,"total_used_percent":30.0,"accuracy_percent":82.0,
                "confidence_percent":88.0,"accuracy_source":"measured","measured_sample_count":8,
                "ready":true
              },
              "charging":{
                "is_charging":true,"remaining_minutes":90.0,
                "estimated_full_at":"2025-01-01 12:00:00",
                "fast_minutes_per_percent":4.0,"taper_minutes_per_percent":7.0,
                "sample_count":3,"accuracy_percent":70.0,"confidence_percent":75.0,
                "accuracy_source":"measured","measured_sample_count":2,"ready":true,
                "estimated_speed_kmh":25.0
              }
            }
            """.trimIndent(),
        )
        val p = PayloadParser.serverPrediction(value)!!
        assertThat(p.modelVersion).isEqualTo("m1")
        assertThat(p.batteryPercent).isEqualTo(80.0)
        assertThat(p.batteryChemistry!!.nominalVoltage).isEqualTo(72.0)
        assertThat(p.batteryChemistry!!.capacityWh).isEqualTo(1440.0)
        assertThat(p.batteryChemistry!!.capacityAh).isEqualTo(20.0)
        assertThat(p.range.estimatedRange).isEqualTo(40.5)
        assertThat(p.range.localRange).isEqualTo(38.0)
        assertThat(p.range.officialRange).isEqualTo(45.0)
        assertThat(p.range.kmPerPercent).isEqualTo(0.5)
        assertThat(p.range.estimatedFullRange).isEqualTo(50.0)
        assertThat(p.range.sampleCount).isEqualTo(12)
        assertThat(p.range.totalUsedPercent).isEqualTo(30.0)
        assertThat(p.range.accuracyPercent).isEqualTo(82.0)
        assertThat(p.range.confidencePercent).isEqualTo(88.0)
        assertThat(p.range.accuracySource).isEqualTo("measured")
        assertThat(p.range.measuredSampleCount).isEqualTo(8)
        assertThat(p.range.isReady).isTrue()
        assertThat(p.charging.remainingMinutes).isEqualTo(90.0)
        assertThat(p.charging.fastMinutesPerPercent).isEqualTo(4.0)
        assertThat(p.charging.taperMinutesPerPercent).isEqualTo(7.0)
        assertThat(p.charging.sampleCount).isEqualTo(3)
        assertThat(p.charging.accuracyPercent).isEqualTo(70.0)
        assertThat(p.charging.confidencePercent).isEqualTo(75.0)
        assertThat(p.charging.accuracySource).isEqualTo("measured")
        assertThat(p.charging.measuredSampleCount).isEqualTo(2)
        assertThat(p.charging.isReady).isTrue()
        assertThat(p.charging.estimatedSpeedKmh).isEqualTo(25.0)
        assertThat(p.charging.isCharging).isTrue()
    }

    @Test
    fun `prediction camelCase aliases match snake_case`() {
        val value = json(
            """
            {
              "modelVersion":"m1",
              "updatedAt":"2025-01-01 10:00:00",
              "batteryPercent":80,
              "batteryChemistry":{"configured":"lithium","effective":"lithium","source":"manual",
                                   "nominalVoltage":72,"capacityWh":1440,"capacityAh":20},
              "range":{
                "estimatedRangeKm":40.5,"localRangeKm":38.0,"officialRangeKm":45.0,
                "source":"personalized","kmPerPercent":0.5,"estimatedFullRangeKm":50.0,
                "sampleCount":12,"totalUsedPercent":30.0,"accuracyPercent":82.0,
                "confidencePercent":88.0,"accuracySource":"measured","measuredSampleCount":8,
                "ready":true
              },
              "charging":{
                "isCharging":true,"remainingMinutes":90.0,
                "estimatedFullAt":"2025-01-01 12:00:00",
                "fastMinutesPerPercent":4.0,"taperMinutesPerPercent":7.0,
                "sampleCount":3,"accuracyPercent":70.0,"confidencePercent":75.0,
                "accuracySource":"measured","measuredSampleCount":2,"ready":true,
                "estimatedSpeedKmh":25.0
              }
            }
            """.trimIndent(),
        )
        val p = PayloadParser.serverPrediction(value)!!
        assertThat(p.modelVersion).isEqualTo("m1")
        assertThat(p.batteryPercent).isEqualTo(80.0)
        assertThat(p.batteryChemistry!!.nominalVoltage).isEqualTo(72.0)
        assertThat(p.batteryChemistry!!.capacityWh).isEqualTo(1440.0)
        assertThat(p.range.estimatedRange).isEqualTo(40.5)
        assertThat(p.range.kmPerPercent).isEqualTo(0.5)
        assertThat(p.range.estimatedFullRange).isEqualTo(50.0)
        assertThat(p.range.sampleCount).isEqualTo(12)
        assertThat(p.range.accuracyPercent).isEqualTo(82.0)
        assertThat(p.range.accuracySource).isEqualTo("measured")
        assertThat(p.range.measuredSampleCount).isEqualTo(8)
        assertThat(p.charging.remainingMinutes).isEqualTo(90.0)
        assertThat(p.charging.fastMinutesPerPercent).isEqualTo(4.0)
        assertThat(p.charging.taperMinutesPerPercent).isEqualTo(7.0)
        assertThat(p.charging.estimatedSpeedKmh).isEqualTo(25.0)
    }

    @Test
    fun `prediction mixed aliases`() {
        val value = json(
            """
            {
              "model_version":"m1",
              "batteryPercent":50,
              "range":{"estimated_range_km":20.0,"kmPerPercent":0.4,"sample_count":2},
              "charging":{"remaining_minutes":30.0,"estimatedSpeedKmh":10.0}
            }
            """.trimIndent(),
        )
        val p = PayloadParser.serverPrediction(value)!!
        assertThat(p.batteryPercent).isEqualTo(50.0)
        assertThat(p.range.estimatedRange).isEqualTo(20.0)
        assertThat(p.range.kmPerPercent).isEqualTo(0.4)
        assertThat(p.range.sampleCount).isEqualTo(2)
        assertThat(p.charging.remainingMinutes).isEqualTo(30.0)
        assertThat(p.charging.estimatedSpeedKmh).isEqualTo(10.0)
    }

    @Test
    fun `login aliases snake and camel`() {
        val snake = PayloadParser.loginResult(
            json("""{"session_token":"s","account_id":1,"business_uid":"b","area_code":"+86"}"""),
        )
        assertThat(snake.sessionToken).isEqualTo("s")
        assertThat(snake.accountId).isEqualTo(1)

        val camel = PayloadParser.loginResult(
            json("""{"sessionToken":"s","id":2,"businessUid":"b","areaCode":"+86"}"""),
        )
        assertThat(camel.sessionToken).isEqualTo("s")
        assertThat(camel.accountId).isEqualTo(2)
    }

    @Test
    fun `travel page aliases`() {
        val page = PayloadParser.travelPage(
            json(
                """
                {"month":"202501","page":1,"page_size":20,"total":1,"has_more":false,
                 "list":[{"travel_id":"t1","mileages":3.2}]}
                """.trimIndent(),
            ),
            "202501",
        )
        assertThat(page.month).isEqualTo("202501")
        assertThat(page.pageSize).isEqualTo(20)
        assertThat(page.hasMore).isFalse()
        assertThat(page.records).hasSize(1)

        val camel = PayloadParser.travelPage(
            json(
                """
                {"month":"202501","pageSize":20,"hasMore":true,
                 "list":[{"travelId":"t1","mileages":3.2}]}
                """.trimIndent(),
            ),
            "202501",
        )
        assertThat(camel.pageSize).isEqualTo(20)
        assertThat(camel.hasMore).isTrue()
    }

    @Test
    fun `vehicle state battery voltage aliases`() {
        // snake with bms_volt style
        val snake = PayloadParser.vehicleState(
            status = json("""{"status":{"dump_energy":50,"pwr":1}}"""),
            travel = null,
            battery = json("""{"battery_info":{"battery_voltage":52.1,"battery_temperature":25.5,"charging_power":100}}"""),
            prediction = null,
            updatedAt = java.util.Date(),
        )
        assertThat(snake.battery).isEqualTo(50)
        assertThat(snake.batteryVoltage).isWithin(0.01).of(52.1)
        assertThat(snake.batteryTemperature).isWithin(0.01).of(25.5)
        assertThat(snake.chargingPower).isEqualTo(100.0)

        val camel = PayloadParser.vehicleState(
            status = json("""{"status":{"dump_energy":50,"pwr":1}}"""),
            travel = null,
            battery = json("""{"batteryInfo":{"batteryVoltage":52.1,"batteryTemperature":25.5,"chargingPower":100}}"""),
            prediction = null,
            updatedAt = java.util.Date(),
        )
        assertThat(camel.batteryVoltage).isWithin(0.01).of(52.1)
    }
}
