package com.absi.talkingclock.audio

import com.absi.talkingclock.data.TimeFormat
import java.time.LocalTime
import java.util.Locale

/**
 * Where each pre-rendered announcement lives in the APK's assets. Every minute of the day has one
 * complete sentence per format, keyed by its 24-hour time, as produced by `tools/voicegen`.
 */
object ClipLibrary {
    const val CHIME = "chime.ogg"

    fun phrasePath(time: LocalTime, format: TimeFormat): String {
        val folder = when (format) {
            TimeFormat.H12 -> "h12"
            TimeFormat.H24 -> "h24"
        }
        return String.format(Locale.ROOT, "voice/en/%s/%02d%02d.ogg", folder, time.hour, time.minute)
    }
}
