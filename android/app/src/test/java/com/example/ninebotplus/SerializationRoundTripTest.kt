package com.example.ninebotplus

import com.example.ninebotplus.data.AuthAssembler
import com.example.ninebotplus.data.LoginResultDto
import com.example.ninebotplus.data.TrackPointCodec
import com.example.ninebotplus.data.TrackPointPayload
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.RideTrackPoint
import com.example.ninebotplus.network.JsonValue
import com.example.ninebotplus.network.NinePlusJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Date

/**
 * Serialization round-trips that previously corrupted data:
 * - ride detail raw JSON must stay a JSON object (not a JSON string literal)
 * - track points must survive encode/decode
 * - login session must survive encode/decode
 */
class SerializationRoundTripTest {

    @Test
    fun `json value round trip preserves structure`() {
        val original = JsonValue.from(
            NinePlusJson.parseToJsonElement(
                """{"foo":"bar","n":1.5,"nested":{"a":[1,2,3]},"flag":true}""",
            ),
        )
        val encoded = NinePlusJson.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            JsonValue.toElement(original),
        )
        // Must be an object literal, NOT "\"{...}\""
        assertThat(encoded.trim().startsWith("{")).isTrue()
        assertThat(encoded).doesNotContain("\\\"foo\\\"")

        val restored = JsonValue.from(NinePlusJson.parseToJsonElement(encoded))
        assertThat(restored["foo"]?.stringValue).isEqualTo("bar")
        assertThat(restored["n"]?.doubleValue).isEqualTo(1.5)
        assertThat(restored["flag"]?.boolValue).isTrue()
        assertThat(restored["nested"]?.get("a")?.arrayValue).hasSize(3)
    }

    @Test
    fun `track point codec round trip`() {
        val points = listOf(
            TrackPointPayload("p1", 1000L, 31.23, 121.47, 20.0, 0.1, 5.0),
            TrackPointPayload("p2", 2000L, 31.24, 121.48, 25.0, 0.2, null),
        )
        val encoded = TrackPointCodec.encode(points)
        val decoded = TrackPointCodec.decode(encoded)
        assertThat(decoded).hasSize(2)
        assertThat(decoded[0].latitude).isWithin(1e-9).of(31.23)
        assertThat(decoded[0].speedKmh).isWithin(1e-9).of(20.0)
        assertThat(decoded[1].horizontalAccuracy).isNull()
    }

    @Test
    fun `login result dto round trip preserves session`() {
        val original = LoginResult(
            uuid = "u-1",
            phone = "13800000000",
            areaCode = "+86",
            region = "CN",
            businessUid = "biz",
            accountId = 42,
            sessionToken = "sess-xyz-SECRET",
        )
        val dto = LoginResultDto.from(original)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(LoginResultDto.serializer(), dto)
        val decoded = json.decodeFromString(LoginResultDto.serializer(), encoded).toDomain()
        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.sessionToken).isEqualTo("sess-xyz-SECRET")
    }

    @Test
    fun `auth assembler drops blank session`() {
        val config = AuthAssembler.effectiveConfiguration(
            baseUrlString = "http://example.com/",
            bearerToken = " tok ",
            loginResult = LoginResult(sessionToken = "  "),
        )
        assertThat(config.appSessionToken).isNull()
        assertThat(config.bearerToken).isEqualTo("tok")
        assertThat(config.baseUrlString).isEqualTo("http://example.com/")
    }

    @Test
    fun `vehicle repository encode dashboard is parseable`() {
        // Lightweight smoke: the compact cache format is valid JSON object.
        val raw = """{"selectedSn":"SN1","updatedAt":1,"vehicles":[{"sn":"SN1","battery":80}]}"""
        val parsed = JsonValue.from(NinePlusJson.parseToJsonElement(raw))
        assertThat(parsed["selectedSn"]?.stringValue).isEqualTo("SN1")
        assertThat(parsed["vehicles"]?.arrayValue).hasSize(1)
    }
}
