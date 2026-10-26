package com.absi.talkingclock.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.data.EventLog
import com.absi.talkingclock.data.SettingsRepository
import com.absi.talkingclock.notify.StatusNotification
import com.absi.talkingclock.setup.SetupChecks
import com.absi.talkingclock.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Owns the single pending alarm. Each announcement re-arms the next one, so the chain runs without
 * any long-lived process: Android wakes the app at each slot, even from Doze.
 */
class AnnouncementScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val eventLog: EventLog,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // Receivers, the watchdog and the service can all re-arm at the same moment (an app update
    // triggers several); one at a time keeps the armed alarm and the saved state in step.
    private val lock = Mutex()

    /**
     * Arms the alarm for the first announcement after [after], or cancels it when announcements are
     * off or can't be timed exactly. Returns the armed time, or null when nothing is armed.
     */
    suspend fun reschedule(after: Instant = Instant.now()): Instant? = lock.withLock { rescheduleLocked(after) }

    suspend fun cancel() = lock.withLock { cancelLocked() }

    private suspend fun rescheduleLocked(after: Instant): Instant? {
        val s = settings.current()
        if (!s.enabled) {
            cancelLocked()
            return null
        }
        if (!SetupChecks.exactAlarmsAllowed(context)) {
            // Android drops the app's alarms when the user turns the permission off. Report it once,
            // when the chain breaks, not every time something tries to re-arm it.
            if (settings.currentRuntime().nextTriggerAt != 0L) {
                eventLog.add(EventKind.PROBLEM, "Stopped: \"Alarms & reminders\" was turned off")
                StatusNotification.showStoppedAlert(context)
            }
            cancelLocked()
            return null
        }
        val from = maxOf(after, Instant.ofEpochMilli(s.pausedUntil - 1))
        val next = NextTimeCalculator.next(from, ZoneId.systemDefault(), s.intervalMinutes, s.quietHours)
        if (next == null) {
            cancelLocked()
            eventLog.add(EventKind.PROBLEM, "Quiet hours leave no time to announce")
            return null
        }
        if (!arm(next, s.alarmClockMode)) {
            cancelLocked()
            return null
        }
        settings.updateRuntime { it.copy(nextTriggerAt = next.toEpochMilli()) }
        return next
    }

    private suspend fun cancelLocked() {
        alarmManager.cancel(alarmIntent(scheduledAt = 0))
        settings.updateRuntime { it.copy(nextTriggerAt = 0) }
    }

    private fun arm(at: Instant, alarmClockMode: Boolean): Boolean {
        val millis = at.toEpochMilli()
        val operation = alarmIntent(scheduledAt = millis)
        return try {
            if (alarmClockMode) {
                // Strongest delivery guarantee, at the cost of a permanent alarm icon and of being
                // reported to other apps as the user's next alarm.
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, openAppIntent()), operation)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
            }
            true
        } catch (e: SecurityException) {
            eventLog.add(EventKind.PROBLEM, "Android refused the alarm: ${e.message}")
            false
        }
    }

    // One request code for every slot: arming a new time replaces the previous alarm.
    private fun alarmIntent(scheduledAt: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ALARM,
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, scheduledAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SHOW,
        MainActivity.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_ALARM = 1
        const val REQUEST_SHOW = 2
    }
}
