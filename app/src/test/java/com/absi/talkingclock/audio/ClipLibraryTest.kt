package com.absi.talkingclock.audio

import com.absi.talkingclock.data.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalTime

class ClipLibraryTest {

    @Test
    fun pathsAreKeyedByTwentyFourHourTime() {
        assertEquals("voice/en/h12/0000.ogg", ClipLibrary.phrasePath(LocalTime.MIDNIGHT, TimeFormat.H12))
        assertEquals("voice/en/h24/2359.ogg", ClipLibrary.phrasePath(LocalTime.of(23, 59), TimeFormat.H24))
        assertEquals("voice/en/h12/1505.ogg", ClipLibrary.phrasePath(LocalTime.of(15, 5), TimeFormat.H12))
    }

    @Test
    fun everyMinuteOfEveryFormatHasAClip() {
        val assets = File("src/main/assets") // unit tests run from the module directory
        assumeTrue("Voice clips not generated yet (see tools/voicegen)", File(assets, "voice/en").isDirectory)
        val missing = TimeFormat.entries.flatMap { format ->
            (0 until 24 * 60).map { ClipLibrary.phrasePath(LocalTime.of(it / 60, it % 60), format) }
        }.filterNot { File(assets, it).isFile }
        assertTrue("${missing.size} clips missing, e.g. ${missing.take(5)}", missing.isEmpty())
        assertTrue("chime missing", File(assets, ClipLibrary.CHIME).isFile)
    }
}
