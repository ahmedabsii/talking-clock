package com.absi.talkingclock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "events.log") }
    private val log by lazy { EventLog(file) }

    @Test
    fun returnsEventsNewestFirst() {
        log.add(EventKind.SPOKEN, "3:15 PM", delayMs = 420, at = 1_000)
        log.add(EventKind.SKIPPED, "3:30 PM: phone call", at = 2_000)
        val events = log.recent()
        assertEquals(listOf(2_000L, 1_000L), events.map { it.at })
        assertEquals(Event(1_000, EventKind.SPOKEN, "3:15 PM", 420), events[1])
        assertNull(events[0].delayMs)
    }

    @Test
    fun tabsAndNewlinesInMessagesCannotBreakTheFormat() {
        log.add(EventKind.INFO, "a\tb\nc")
        assertEquals("a b c", log.recent().single().message)
    }

    @Test
    fun keepsOnlyTheNewestEvents() {
        repeat(EventLog.MAX_EVENTS + 120) { log.add(EventKind.INFO, "event $it", at = it.toLong()) }
        val events = log.recent(limit = Int.MAX_VALUE)
        assertTrue("${events.size} kept", events.size in EventLog.MAX_EVENTS..EventLog.MAX_EVENTS + 50)
        assertEquals((EventLog.MAX_EVENTS + 119).toLong(), events.first().at)
    }

    @Test
    fun skipsCorruptLines() {
        file.writeText("garbage\n1\tSPOKEN\t\tok\n")
        assertEquals(listOf("ok"), log.recent().map { it.message })
    }
}
