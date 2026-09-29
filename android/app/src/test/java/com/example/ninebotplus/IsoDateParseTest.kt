package com.example.ninebotplus

import com.example.ninebotplus.util.JsonDateInput
import com.example.ninebotplus.util.NineplusDates
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Date

/**
 * Regression: Python `datetime.isoformat()` values from nineplus-ha-server
 * (`2026-09-27T16:52:25.446895+00:00`) used to throw
 * `ParseException: Unparseable date` from SimpleDateFormat and abort
 * the whole dashboard refresh.
 */
class IsoDateParseTest {

    @Test
    fun `python isoformat with micros and plus offset`() {
        val raw = "2026-09-27T16:52:25.446895+00:00"
        val date = NineplusDates.parse(JsonDateInput.TextValue(raw))
        assertThat(date).isNotNull()
        // 16:52:25 UTC == 00:52:25 +08 next day (CST)
        val expected = Date(java.time.Instant.parse("2026-09-27T16:52:25.446895Z").toEpochMilli())
        assertThat(date!!.time).isEqualTo(expected.time)
    }

    @Test
    fun `python isoformat with millis and Z`() {
        val date = NineplusDates.parse(JsonDateInput.TextValue("2026-09-27T16:52:25.446Z"))
        assertThat(date).isNotNull()
    }

    @Test
    fun `python isoformat with three digit fraction and offset`() {
        val date = NineplusDates.parse(JsonDateInput.TextValue("2026-09-27T16:52:25.446+08:00"))
        assertThat(date).isNotNull()
    }

    @Test
    fun `iso without fraction`() {
        val date = NineplusDates.parse(JsonDateInput.TextValue("2026-09-27T16:52:25+00:00"))
        assertThat(date).isNotNull()
    }

    @Test
    fun `invalid date does not throw`() {
        assertThat(NineplusDates.parse(JsonDateInput.TextValue("not-a-date"))).isNull()
        assertThat(NineplusDates.parse(JsonDateInput.TextValue("2026-13-45T99:99:99.999999+00:00"))).isNull()
        assertThat(NineplusDates.parse(JsonDateInput.TextValue(""))).isNull()
    }

    @Test
    fun `china wall clock still works`() {
        val date = NineplusDates.parse(JsonDateInput.TextValue("2025-01-01 08:00:00"))
        assertThat(date).isNotNull()
    }

    @Test
    fun `serverDate helper never throws`() {
        assertThat(NineplusDates.serverDate("2026-09-27T16:52:25.446895+00:00")).isNotNull()
        assertThat(NineplusDates.serverDate("garbage")).isNull()
    }
}
