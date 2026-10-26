package com.absi.talkingclock.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.graph
import kotlinx.coroutines.launch

/**
 * Re-arms the alarm whenever Android may have dropped or misplaced it. It never plays audio:
 * Android 15+ forbids media playback started from boot, and Android 17 mutes it.
 */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "phone restarted"
            Intent.ACTION_BOOT_COMPLETED -> "phone unlocked after restart"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            Intent.ACTION_TIME_CHANGED -> "clock changed"
            Intent.ACTION_TIMEZONE_CHANGED -> "time zone changed"
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> "\"Alarms & reminders\" allowed"
            else -> return
        }
        val graph = context.graph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                val next = graph.controller.refresh()
                WatchdogWorker.ensure(context, graph.settings.current().enabled)
                if (next != null) graph.eventLog.add(EventKind.INFO, "Re-armed: $reason")
            } finally {
                pending.finish()
            }
        }
    }
}
