package com.absi.talkingclock.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/**
 * Maps the app's 0–100 % slider onto the alarm stream, which only has a handful of coarse steps
 * (often 7 or 15). The step gets close, then a player gain fine-tunes within it, so every slider
 * position is a little louder than the one before.
 */
object VolumeMapping {

    data class Level(val index: Int, val gain: Float)

    /** Used when the device doesn't report its volume curve. */
    const val DEFAULT_STEP_DB = 3f

    /**
     * @param stepDb how many dB louder stream index `i` is than `i - 1` on the speaker.
     */
    fun map(percent: Int, minIndex: Int, maxIndex: Int, stepDb: (Int) -> Float): Level {
        if (percent <= 0) return Level(max(minIndex, 0), 0f)
        val exact = percent / 100f * maxIndex
        val index = ceil(exact).toInt().coerceIn(max(minIndex, 1), maxIndex)
        // How far through this step the slider is: 1 means the full step, so no attenuation.
        val fraction = (exact - (index - 1)).coerceIn(0f, 1f)
        val step = stepDb(index).takeIf { it.isFinite() && it > 0f } ?: DEFAULT_STEP_DB
        val gainDb = -step * (1f - fraction)
        return Level(index, 10f.pow(gainDb / 20f))
    }
}
