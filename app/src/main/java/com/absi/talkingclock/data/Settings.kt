package com.absi.talkingclock.data

import com.absi.talkingclock.schedule.QuietHours

enum class TimeFormat { H12, H24 }

/** What happens to music that is already playing when an announcement starts. */
enum class MusicMode { DUCK, PAUSE }

data class Settings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val volumePercent: Int = 80,
    val boost: Boolean = false,
    val format: TimeFormat = TimeFormat.H12,
    val chime: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    val musicMode: MusicMode = MusicMode.DUCK,
    val alarmClockMode: Boolean = false,
    /** Epoch millis until which announcements are paused; 0 when not paused. */
    val pausedUntil: Long = 0,
    /** The brand-specific background steps can't be detected, so the user ticks them off. */
    val oemStepsDone: Boolean = false,
) {
    val quietHours: QuietHours?
        get() = if (quietHoursEnabled) QuietHours(quietStartMinute, quietEndMinute) else null

    companion object {
        val INTERVAL_PRESETS = listOf(1, 5, 10, 15, 20, 30, 60, 120)
        const val MAX_INTERVAL_MINUTES = 720
    }
}

/** Bookkeeping the user never edits directly. */
data class RuntimeState(
    /** Epoch millis of the armed alarm; 0 when none is armed. */
    val nextTriggerAt: Long = 0,
    /** Alarm-stream index to put back if an announcement was cut short; -1 when none. */
    val pendingVolumeRestore: Int = -1,
)
