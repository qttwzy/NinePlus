package com.example.ninebotplus

import com.example.ninebotplus.domain.ServerTrackParser
import com.example.ninebotplus.network.JsonValue
import com.example.ninebotplus.network.NinePlusJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerTrackParserTest {

    @Test
    fun `parse trail string with lon lat speed distance`() {
        val trail = "121.5008193,31.2515891,0.0,29.8;121.5011291,31.2517451,24.0,28.2;121.5016003,31.2519226,26.7,29.7"
        val points = ServerTrackParser.parseTrailString(trail)
        assertThat(points).hasSize(3)
        assertThat(points[0].longitude).isWithin(1e-7).of(121.5008193)
        assertThat(points[0].latitude).isWithin(1e-7).of(31.2515891)
        assertThat(points[0].speedKmh).isWithin(0.01).of(0.0)
        assertThat(points[0].segmentDistanceMeters).isWithin(0.01).of(29.8)
        assertThat(points[1].speedKmh).isWithin(0.01).of(24.0)
        assertThat(points[2].speedKmh).isWithin(0.01).of(26.7)
        assertThat(points[1].index).isEqualTo(1)
    }

    @Test
    fun `parse trail from nested detail json`() {
        val raw = JsonValue.from(
            NinePlusJson.parseToJsonElement(
                """{"speed":"32.3","trail":"121.1,31.1,10.0,5.0;121.2,31.2,20.0,5.0"}""",
            ),
        )
        val points = ServerTrackParser.parsePoints(raw)
        assertThat(points).hasSize(2)
        assertThat(points[0].speedText).isEqualTo("10.0 km/h")
        assertThat(points[1].speedKmh).isWithin(0.01).of(20.0)
    }

    @Test
    fun `empty or missing trail returns empty`() {
        assertThat(ServerTrackParser.parsePoints(JsonValue.Obj(emptyMap()))).isEmpty()
        assertThat(ServerTrackParser.parseTrailString("")).isEmpty()
        assertThat(ServerTrackParser.parseTrailString(";;")).isEmpty()
    }

    @Test
    fun `malformed segments skipped`() {
        val points = ServerTrackParser.parseTrailString("bad;121.0,31.0,5.0,1.0;xx,yy")
        assertThat(points).hasSize(1)
    }
}
