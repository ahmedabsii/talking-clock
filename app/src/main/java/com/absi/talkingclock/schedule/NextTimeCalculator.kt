package com.absi.talkingclock.schedule

import com.absi.talkingclock.data.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A daily window, in minutes of the day, with no announcements. May cross midnight (23:00–07:00). */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    operator fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/** Pure timing rules, kept free of Android types so they can be unit-tested exhaustively. */
object NextTimeCalculator {

    /** An alarm that arrives later than this is skipped rather than announcing a stale time. */
    val MAX_LATENESS: Duration = Duration.ofMinutes(2)

    private const val SEARCH_LIMIT_MINUTES = 3 * 24 * 60

    /**
     * The first announcement strictly after [after]: a minute whose minute-of-day is a multiple of
     * [intervalMinutes] (so 15 gives :00, :15, :30, :45) and is outside [quiet]. Returns null when
     * quiet hours cover every such minute.
     *
     * It walks real instants a minute at a time, which handles daylight saving by construction:
     * local times skipped in spring never occur, and the hour repeated in autumn is announced twice,
     * just as the clock shows it.
     */
    fun next(after: Instant, zone: ZoneId, intervalMinutes: Int, quiet: QuietHours? = null): Instant? {
        require(intervalMinutes in 1..Settings.MAX_INTERVAL_MINUTES) { "interval $intervalMinutes" }
        var candidate = after.truncatedTo(ChronoUnit.MINUTES)
        if (!candidate.isAfter(after)) candidate = candidate.plus(1, ChronoUnit.MINUTES)
        repeat(SEARCH_LIMIT_MINUTES) {
            val local = candidate.atZone(zone).toLocalTime()
            val minuteOfDay = local.hour * 60 + local.minute
            if (minuteOfDay % intervalMinutes == 0 && (quiet == null || minuteOfDay !in quiet)) return candidate
            candidate = candidate.plus(1, ChronoUnit.MINUTES)
        }
        return null
    }

    fun isTooLate(scheduledAt: Instant, now: Instant): Boolean = Duration.between(scheduledAt, now) > MAX_LATENESS

    /** The minute to speak: the current one, or the scheduled one if the alarm fired a moment early. */
    fun spokenTime(scheduledAt: Instant, now: Instant, zone: ZoneId): LocalTime =
        maxOf(scheduledAt, now).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
}
