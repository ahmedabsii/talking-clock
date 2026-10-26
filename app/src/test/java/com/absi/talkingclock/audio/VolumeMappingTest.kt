package com.absi.talkingclock.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class VolumeMappingTest {

    // A typical alarm-stream curve: stream index -> level in dB on the speaker (index 0 is silent).
    private val curveDb = floatArrayOf(Float.NEGATIVE_INFINITY, -42f, -35f, -28f, -21f, -14f, -7f, 0f)

    private fun stepDb(index: Int) = curveDb[index] - curveDb[index - 1]

    private fun map(percent: Int, minIndex: Int = 1) = VolumeMapping.map(percent, minIndex, curveDb.lastIndex, ::stepDb)

    private fun effectiveDb(level: VolumeMapping.Level) = curveDb[level.index] + 20 * log10(level.gain)

    @Test
    fun fullVolumeIsTheTopStepAtFullGain() {
        val level = map(100)
        assertEquals(7, level.index)
        assertEquals(1f, level.gain, 1e-6f)
    }

    @Test
    fun zeroIsSilent() {
        assertEquals(0f, map(0).gain, 0f)
    }

    @Test
    fun neverGoesBelowTheStreamMinimum() {
        for (percent in 1..100) assertTrue(map(percent, minIndex = 3).index >= 3)
    }

    @Test
    fun everySliderStepIsAtLeastAsLoudAsTheLast() {
        var previous = Float.NEGATIVE_INFINITY
        for (percent in 1..100) {
            val db = effectiveDb(map(percent))
            assertTrue("$percent% = $db dB, after $previous dB", db >= previous - 1e-4f)
            previous = db
        }
    }

    @Test
    fun unknownStepSizeFallsBackToTheDefault() {
        val level = VolumeMapping.map(55, 1, 10) { Float.NaN }
        assertEquals(6, level.index)
        // Halfway through step 6, so half the default step below full gain.
        assertEquals(-VolumeMapping.DEFAULT_STEP_DB / 2, 20 * log10(level.gain), 1e-3f)
    }
}
