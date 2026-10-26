package com.absi.talkingclock.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.absi.talkingclock.audio.AnnounceWakeLock
import com.absi.talkingclock.audio.AnnouncerService
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.graph
import kotlinx.coroutines.launch
import java.time.Instant

/** Fired by AlarmManager at each slot; hands the announcement to the foreground service. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        // AlarmManager keeps the CPU awake only until onReceive returns; the service releases this one.
        AnnounceWakeLock.acquire(context)
        try {
            // Allowed from the background because an exact alarm just fired.
            AnnouncerService.announce(context, scheduledAt)
        } catch (e: RuntimeException) {
            AnnounceWakeLock.release()
            // Without the service nothing re-arms the alarm, so do it here to keep the chain alive.
            val graph = context.graph
            val pending = goAsync()
            graph.appScope.launch {
                try {
                    graph.eventLog.add(EventKind.PROBLEM, "Android refused to start the announcer: ${e.javaClass.simpleName}")
                    graph.scheduler.reschedule(after = maxOf(Instant.now(), Instant.ofEpochMilli(scheduledAt)))
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
