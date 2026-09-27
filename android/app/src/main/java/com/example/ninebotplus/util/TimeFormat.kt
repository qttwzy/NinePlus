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
                structuredChinaDate(text)?.let { return it }
                text.toDoubleOrNull()?.let { return epochDate(it) }
                for (format in DATE_FORMATS) {
                    parseWith(format)?.let { formatter ->
                        formatter.parse(text)?.let { return it }
                    }
                }
                return parseIso(text)
            }
            null -> return null
        }
    }

    fun serverDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val format = if (text.length > 19) "yyyy-MM-dd HH:mm:ss.SSS" else "yyyy-MM-dd HH:mm:ss"
        return parseWith(format)?.parse(text) ?: parse(JsonDateInput.TextValue(text))
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
        return parseWith(format)?.parse(text)
    }

    private fun parseIso(text: String): Date? {
        val isoFormats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
        for (format in isoFormats) {
            parseWith(format)?.parse(normalizeIso(text))?.let { return it }
        }
        return try {
            Date(java.time.Instant.parse(text).toEpochMilli())
        } catch (_: Exception) {
            null
        }
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
