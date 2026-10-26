package com.absi.talkingclock.schedule

import com.absi.talkingclock.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class NextTimeCalculatorTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun local(text: String, zone: ZoneId = utc): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun next(after: Instant, interval: Int, zone: ZoneId = utc, quiet: QuietHours? = null): Instant? =
        NextTimeCalculator.next(after, zone, interval, quiet)

    @Test
    fun alignsToTheClockNotToWhenItWasTurnedOn() {
        assertEquals(local("2026-05-10T10:15"), next(local("2026-05-10T10:07:31"), 15))
    }

    @Test
    fun exactlyOnASlotMovesToTheFollowingOne() {
        assertEquals(local("2026-05-10T10:30"), next(local("2026-05-10T10:15"), 15))
    }

    @Test
    fun aFractionBeforeASlotReturnsThatSlot() {
        assertEquals(local("2026-05-10T10:15"), next(local("2026-05-10T10:14:59.500"), 15))
    }

    @Test
    fun everyPresetLandsOnAlignedMinutesWithoutGaps() {
        val zone = ZoneId.of("Europe/Berlin")
        for (interval in Settings.INTERVAL_PRESETS) {
            var cursor = local("2026-06-01T00:00", zone)
            repeat(100) {
                val n = next(cursor, interval, zone)!!
                val t = n.atZone(zone).toLocalTime()
                assertEquals("every $interval min, at $t", 0, (t.hour * 60 + t.minute) % interval)
                assertEquals("every $interval min, at $t", Duration.ofMinutes(interval.toLong()), Duration.between(cursor, n))
                cursor = n
            }
        }
    }

    @Test
    fun customIntervalsRestartAtMidnight() {
        assertEquals(local("2026-05-10T23:55"), next(local("2026-05-10T23:52"), 7)) // 23:55 = minute 1435 = 7 × 205
        assertEquals(local("2026-05-11T00:00"), next(local("2026-05-10T23:55"), 7))
    }

    @Test
    fun halfHourOffsetZonesAlignToLocalTime() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(local("2026-05-10T11:00", kolkata), next(local("2026-05-10T10:10", kolkata), 60, kolkata))
    }

    @Test
    fun springForwardSkipsTheMissingHour() {
        // New York, 8 March 2026: 02:00 EST jumps straight to 03:00 EDT.
        val n = next(local("2026-03-08T01:50", newYork), 15, newYork)!!
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), n)
        assertEquals(LocalTime.of(3, 0), n.atZone(newYork).toLocalTime())
    }

    @Test
    fun fallBackAnnouncesTheRepeatedHourTwice() {
        // New York, 1 November 2026: 02:00 EDT falls back to 01:00 EST, so 1 AM happens twice.
        val first = next(local("2026-11-01T00:30", newYork), 60, newYork)!!
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), first) // 01:00 EDT
        val second = next(first, 60, newYork)!!
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), second) // 01:00 EST
        assertEquals(LocalTime.of(1, 0), second.atZone(newYork).toLocalTime())
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), next(second, 60, newYork)) // 02:00 EST
    }

    @Test
    fun londonClockChanges() {
        // 29 March 2026: 01:00 GMT jumps to 02:00 BST, so after 00:45 the next half hour is 02:00 BST.
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), next(local("2026-03-29T00:45", london), 30, london))
        // 25 October 2026: 02:00 BST falls back to 01:00 GMT, so after 01:45 BST comes 01:00 GMT.
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"), next(Instant.parse("2026-10-25T00:45:00Z"), 30, london))
    }

    @Test
    fun quietHoursAcrossMidnightJumpToTheirEnd() {
        val night = QuietHours(23 * 60, 7 * 60)
        assertEquals(local("2026-05-10T22:45"), next(local("2026-05-10T22:40"), 15, quiet = night))
        assertEquals(local("2026-05-11T07:00"), next(local("2026-05-10T22:50"), 15, quiet = night))
        assertEquals(local("2026-05-11T07:00"), next(local("2026-05-11T06:59"), 15, quiet = night))
    }

    @Test
    fun quietHoursWithinTheDay() {
        val lunch = QuietHours(13 * 60, 14 * 60)
        assertEquals(local("2026-05-10T14:00"), next(local("2026-05-10T12:45"), 30, quiet = lunch))
    }

    @Test
    fun quietHoursContainment() {
        val night = QuietHours(23 * 60, 7 * 60)
        assertTrue(23 * 60 in night)
        assertTrue(0 in night)
        assertTrue(7 * 60 - 1 in night)
        assertFalse(7 * 60 in night)
        assertFalse(22 * 60 + 59 in night)
        assertFalse("equal start and end means no quiet hours", 12 * 60 in QuietHours(12 * 60, 12 * 60))
    }

    @Test
    fun returnsNullWhenQuietHoursCoverEverySlot() {
        assertNull(next(local("2026-05-10T10:00"), 720, quiet = QuietHours(0, 23 * 60 + 59)))
    }

    @Test
    fun announcementsUpToTwoMinutesLateStillPlay() {
        val slot = local("2026-05-10T10:15")
        assertFalse(NextTimeCalculator.isTooLate(slot, slot.plusSeconds(120)))
        assertTrue(NextTimeCalculator.isTooLate(slot, slot.plusMillis(120_001)))
    }

    @Test
    fun spokenTimeIsTheCurrentMinuteButNeverBeforeTheSlot() {
        val slot = local("2026-05-10T10:15")
        assertEquals(LocalTime.of(10, 15), NextTimeCalculator.spokenTime(slot, slot.minusMillis(200), utc))
        assertEquals(LocalTime.of(10, 15), NextTimeCalculator.spokenTime(slot, slot.plusSeconds(40), utc))
        assertEquals(LocalTime.of(10, 16), NextTimeCalculator.spokenTime(slot, slot.plusSeconds(70), utc))
    }
}
