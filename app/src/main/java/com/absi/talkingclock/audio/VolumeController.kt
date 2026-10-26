package com.absi.talkingclock.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.data.EventLog
import com.absi.talkingclock.data.SettingsRepository

/**
 * Gives the app its own volume: for the few seconds of an announcement the alarm stream is set to
 * the app's level, then put back, so the phone's media, ringer and alarm volumes never matter and
 * never change. Only effective inside the foreground service (Android 17 ignores background calls).
 */
class VolumeController(
    context: Context,
    private val settings: SettingsRepository,
    private val eventLog: EventLog,
) {
    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Sets the alarm stream for [percent] and returns the player gain that fine-tunes it. The
     * original level is saved first, so [restore] can put it back even after a crash mid-announcement.
     */
    suspend fun apply(percent: Int): Float {
        if (audio.isVolumeFixed) return percent / 100f
        val level = VolumeMapping.map(
            percent,
            audio.getStreamMinVolume(STREAM),
            audio.getStreamMaxVolume(STREAM),
            ::stepDb,
        )
        val current = audio.getStreamVolume(STREAM)
        settings.updateRuntime { if (it.pendingVolumeRestore < 0) it.copy(pendingVolumeRestore = current) else it }
        if (current != level.index) setIndex(level.index)
        return level.gain
    }

    /** Puts back the alarm volume saved by [apply], if any. */
    suspend fun restore() {
        val original = settings.currentRuntime().pendingVolumeRestore
        if (original < 0) return
        if (!audio.isVolumeFixed && audio.getStreamVolume(STREAM) != original) setIndex(original)
        settings.updateRuntime { it.copy(pendingVolumeRestore = -1) }
    }

    private fun setIndex(index: Int) {
        try {
            audio.setStreamVolume(STREAM, index, 0)
        } catch (e: SecurityException) {
            eventLog.add(EventKind.PROBLEM, "Android blocked the volume change: ${e.message}")
        }
    }

    private fun stepDb(index: Int): Float = try {
        audio.getStreamVolumeDb(STREAM, index, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) -
            audio.getStreamVolumeDb(STREAM, index - 1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    } catch (e: IllegalArgumentException) {
        Float.NaN
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_ALARM
    }
}
