package com.example.ninebotplus.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date parsing that mirrors iOS NinebotServerClient date handling.
 * NinePlus / Ninebot payloads mix China-local wall-clock strings, compact digits,
 * epoch seconds/millis, and ISO-8601.
 */
object NineplusDates {
    val chinaTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun currentMonthString(now: Date = Date()): String = monthString(now)

    fun monthString(date: Date): String =
        SimpleDateFormat("yyyyMM", Locale.US).apply { timeZone = chinaTimeZone }.format(date)

    fun monthStrings(startDate: Date?, endDate: Date): List<String> {
        if (startDate == null) return listOf(monthString(endDate))
        val calendar = Calendar.getInstance(chinaTimeZone)
        calendar.time = startDate
        val startYear = calendar.get(Calendar.YEAR)
        val startMonth = calendar.get(Calendar.MONTH)
        calendar.time = endDate
        val endYear = calendar.get(Calendar.YEAR)
        val endMonth = calendar.get(Calendar.MONTH)

        if (startYear > endYear || (startYear == endYear && startMonth > endMonth)) {
            return listOf(monthString(endDate))
        }

        val result = mutableListOf<String>()
        val cursor = Calendar.getInstance(chinaTimeZone)
        cursor.set(startYear, startMonth, 1, 0, 0, 0)
        val end = Calendar.getInstance(chinaTimeZone)
        end.set(endYear, endMonth, 1, 0, 0, 0)
        while (!cursor.after(end)) {
            result += monthString(cursor.time)
            cursor.add(Calendar.MONTH, 1)
        }
        return result
    }

    fun displayMonth(month: String): String {
        if (month.length != 6) return month
        return "${month.substring(0, 4)}年${month.substring(4)}月"
    }

    fun displayMonthDot(month: String): String {
        if (month.length != 6) return month
        return "${month.substring(0, 4)}.${month.substring(4)}"
    }

    fun previousMonth(month: String): String {
        if (month.length != 6) return month
        val year = month.substring(0, 4).toIntOrNull() ?: return month
        val mon = month.substring(4).toIntOrNull() ?: return month
        val calendar = Calendar.getInstance(chinaTimeZone)
        calendar.set(year, mon - 1, 1, 0, 0, 0)
        calendar.add(Calendar.MONTH, -1)
        return monthString(calendar.time)
    }

    fun parse(value: JsonDateInput?): Date? {
        when (value) {
            is JsonDateInput.NumberValue -> return epochDate(value.value)
            is JsonDateInput.TextValue -> {
                val text = value.value.trim()
                if (text.isEmpty()) return null
                // Never let a single bad field crash parsing.
                return runCatching { parseText(text) }.getOrNull()
            }
            null -> return null
        }
    }

    private fun parseText(text: String): Date? {
        structuredChinaDate(text)?.let { return it }
        text.toDoubleOrNull()?.let { return epochDate(it) }
        // Timestamps with 'T' / offsets go through ISO first so a loose
        // "yyyy-MM-dd" prefix match cannot steal the date part.
        if (text.contains('T') || text.contains('+') || text.endsWith('Z')) {
            parseIso(text)?.let { return it }
        }
        for (format in DATE_FORMATS) {
            parseFull(format, text)?.let { return it }
        }
        return parseIso(text)
    }

    fun serverDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        return runCatching {
            if (text.contains('T') || text.contains('+') || text.endsWith('Z')) {
                parseIso(text)
            } else {
                parseFull("yyyy-MM-dd HH:mm:ss.SSS", text)
                    ?: parseFull("yyyy-MM-dd HH:mm:ss", text)
                    ?: parse(JsonDateInput.TextValue(text))
            }
        }.getOrNull()
    }

    /** SimpleDateFormat.parse only needs a prefix match — require full consumption. */
    private fun parseFull(format: String, text: String): Date? {
        val formatter = parseWith(format) ?: return null
        return runCatching {
            val pos = java.text.ParsePosition(0)
            val date = formatter.parse(text, pos) ?: return@runCatching null
            if (pos.index != text.length) null else date
        }.getOrNull()
    }

    fun date(month: String?, day: Int): Date? {
        if (month == null || month.length != 6) return null
        val year = month.substring(0, 4).toIntOrNull() ?: return null
        val mon = month.substring(4).toIntOrNull() ?: return null
        val calendar = Calendar.getInstance(chinaTimeZone)
        calendar.clear()
        calendar.set(year, mon - 1, day)
        return calendar.time
    }

    fun formatDateTime(date: Date?): String {
        if (date == null) return "--"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = chinaTimeZone
        }.format(date)
    }

    fun formatTime(date: Date?): String {
        if (date == null) return "--"
        return SimpleDateFormat("HH:mm", Locale.CHINA).apply {
            timeZone = chinaTimeZone
        }.format(date)
    }

    fun formatShortDateTime(date: Date?): String {
        if (date == null) return "--"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = chinaTimeZone
        }.format(date)
    }

    fun formatRideDate(date: Date?): String {
        if (date == null) return "行程"
        return SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).apply {
            timeZone = chinaTimeZone
        }.format(date)
    }

    fun formatDuration(minutes: Double): String {
        return if (minutes >= 60) {
            val hours = minutes / 60
            "${NumberFormats.number(hours, 1)} 小时"
        } else {
            "${NumberFormats.number(minutes, 1)} 分钟"
        }
    }

    fun formatClockDuration(seconds: Double): String {
        val total = maxOf(seconds, 0.0).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun epochDate(number: Double): Date? {
        if (number > 1_000_000_000_000) return Date((number / 1000).toLong() * 1000)
        if (number > 1_000_000_000) return Date((number).toLong() * 1000)
        return null
    }

    private fun structuredChinaDate(text: String): Date? {
        if (!text.all { it.isDigit() }) return null
        val format = when (text.length) {
            14 -> "yyyyMMddHHmmss"
            12 -> "yyyyMMddHHmm"
            8 -> "yyyyMMdd"
            else -> return null
        }
        return parseWith(format)?.let { runCatching { it.parse(text) }.getOrNull() }
    }

    /**
     * ISO-8601 including Python `datetime.isoformat()` output
     * (`2026-09-27T16:52:25.446895+00:00`) and Java `Instant` / `OffsetDateTime` forms.
     *
     * Never throws: SimpleDateFormat.parse raises ParseException on mismatch,
     * which used to escape as "Unparseable date" and break the whole refresh.
     */
    private fun parseIso(text: String): Date? {
        // 1) java.time is strict and handles variable fraction digits + offsets.
        runCatching {
            return Date(java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli())
        }
        runCatching {
            return Date(java.time.Instant.parse(text).toEpochMilli())
        }
        runCatching {
            return Date(java.time.ZonedDateTime.parse(text).toInstant().toEpochMilli())
        }

        // 2) Fallback: SimpleDateFormat, each attempt caught.
        val normalized = normalizeIso(text)
        for (format in isoFormats) {
            val parsed = parseWith(format)?.let { runCatching { it.parse(normalized) }.getOrNull() }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun normalizeIso(text: String): String =
        text.replace("Z", "+0000").replace(Regex("(\\d{2}):(\\d{2})$"), "$1$2")

    private fun parseWith(format: String): SimpleDateFormat? = try {
        SimpleDateFormat(format, Locale.US).apply {
            timeZone = chinaTimeZone
            isLenient = false
        }
    } catch (_: Exception) {
        null
    }

    private val isoFormats = listOf(
        // Variable fractional seconds (Python isoformat can emit 3–6 digits).
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
    )

    private val DATE_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
    )
}

sealed class JsonDateInput {
    data class NumberValue(val value: Double) : JsonDateInput()
    data class TextValue(val value: String) : JsonDateInput()
}

object NumberFormats {
    fun number(value: Double, maxFraction: Int, minFraction: Int = 0): String {
        val format = java.text.NumberFormat.getNumberInstance(Locale.CHINA).apply {
            maximumFractionDigits = maxFraction
            minimumFractionDigits = minFraction
        }
        return format.format(value)
    }

    fun percent(value: Int): String = "$value%"

    fun distanceKm(value: Double?, maxFraction: Int = 1): String {
        if (value == null) return "-- km"
        return "${number(value, maxFraction)} km"
    }

    fun speedKmh(value: Double?, maxFraction: Int = 1): String {
        if (value == null) return "-- km/h"
        return "${number(value, maxFraction)} km/h"
    }

    fun energyWh(value: Double?): String {
        if (value == null) return "-- Wh"
        return "${number(value, 0)} Wh"
    }

    fun coordinate(value: Double?): String {
        if (value == null) return "--"
        return number(value, 8)
    }
}
