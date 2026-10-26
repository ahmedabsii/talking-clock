package com.absi.talkingclock.schedule

import android.content.Context
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.data.EventLog
import com.absi.talkingclock.data.Settings
import com.absi.talkingclock.data.SettingsRepository
import com.absi.talkingclock.notify.StatusNotification
import com.absi.talkingclock.setup.SetupChecks
import java.time.Duration
import java.time.Instant

/** The user's actions, shared by the app's screens and the notification buttons. */
class AnnouncementController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scheduler: AnnouncementScheduler,
    private val eventLog: EventLog,
) {
    /** Returns false when announcements can't be turned on yet because exact alarms aren't allowed. */
    suspend fun setEnabled(on: Boolean): Boolean {
        if (on && !SetupChecks.exactAlarmsAllowed(context)) return false
        settings.update { it.copy(enabled = on, pausedUntil = 0) }
        eventLog.add(EventKind.INFO, if (on) "Turned on" else "Turned off")
        WatchdogWorker.ensure(context, on)
        refresh()
        return true
    }

    suspend fun pauseFor(duration: Duration) {
        settings.update { it.copy(pausedUntil = Instant.now().plus(duration).toEpochMilli()) }
        eventLog.add(EventKind.INFO, "Paused for ${duration.toMinutes()} min")
        refresh()
    }

    suspend fun resume() {
        settings.update { it.copy(pausedUntil = 0) }
        eventLog.add(EventKind.INFO, "Resumed")
        refresh()
    }

    /** Saves a settings change and re-arms the alarm, since most settings move the next slot. */
    suspend fun change(transform: (Settings) -> Settings) {
        settings.update(transform)
        refresh()
    }

    /**
     * Re-arms the alarm and brings the notifications up to date. Safe to call at any time.
     * Returns the armed time, or null when nothing could be armed.
     */
    suspend fun refresh(): Instant? {
        val next = scheduler.reschedule()
        StatusNotification.refresh(context)
        if (SetupChecks.exactAlarmsAllowed(context)) StatusNotification.cancelStoppedAlert(context)
        return next
    }
}
