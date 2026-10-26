package com.absi.talkingclock.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** On-screen times, in the same 12/24-hour format the user chose to hear. */
object TimeLabels {

    fun label(time: LocalTime, format: TimeFormat): String = time.format(timeFormatter(format))

    fun label(epochMillis: Long, format: TimeFormat): String =
        label(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime(), format)

    /** "3:15 PM" for today, "Mon 3:15 PM" for earlier days. */
    fun dayAndTime(epochMillis: Long, format: TimeFormat): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val time = label(zoned.toLocalTime(), format)
        if (zoned.toLocalDate() == LocalDate.now()) return time
        return zoned.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + " " + time
    }

    private fun timeFormatter(format: TimeFormat): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (format == TimeFormat.H12) "h:mm a" else "HH:mm", Locale.getDefault())
}
