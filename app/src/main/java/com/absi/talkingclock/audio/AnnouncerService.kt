package com.absi.talkingclock.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.data.Settings
import com.absi.talkingclock.data.TimeLabels
import com.absi.talkingclock.graph
import com.absi.talkingclock.notify.StatusNotification
import com.absi.talkingclock.schedule.NextTimeCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Speaks one announcement, then stops (a few seconds). Android 17 only lets audio play, take focus
 * or change volume from the background inside a foreground service, and, for a service started by
 * an alarm, only on the alarm channel with the exact-alarm permission. This is that service.
 */
class AnnouncerService : Service() {

    private val scope = MainScope()
    private val queue = Mutex()
    private var activeJobs = 0
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First thing: Android stops a foreground-service start that doesn't promote itself within seconds.
        ServiceCompat.startForeground(
            this,
            StatusNotification.ID,
            StatusNotification.speaking(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        latestStartId = startId
        activeJobs++
        scope.launch {
            try {
                queue.withLock { handle(intent) } // a "Test now" tap during an announcement waits its turn
            } finally {
                activeJobs--
                if (activeJobs == 0) finish(startId = latestStartId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        AnnounceWakeLock.release()
        super.onDestroy()
    }

    private suspend fun handle(intent: Intent?) {
        val graph = applicationContext.graph
        val now = Instant.now()
        val isTest = intent?.action == ACTION_TEST
        val scheduledAt = intent?.getLongExtra(EXTRA_SCHEDULED_AT, 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: now
        try {
            graph.volume.restore() // left over from an announcement that was cut short
            // Re-arm first, so nothing that goes wrong below can break the chain.
            if (!isTest) graph.scheduler.reschedule(after = maxOf(now, scheduledAt))

            val settings = graph.settings.current()
            val zone = ZoneId.systemDefault()
            val time = if (isTest) now.atZone(zone).toLocalTime() else NextTimeCalculator.spokenTime(scheduledAt, now, zone)
            val label = TimeLabels.label(time, settings.format)
            if (!isTest) {
                val reason = skipReason(settings, scheduledAt, now, time)
                if (reason != null) {
                    graph.eventLog.add(EventKind.SKIPPED, "$label: $reason")
                    return
                }
            }

            val gain = graph.volume.apply(settings.volumePercent)
            val startedAt = Instant.now()
            val result = try {
                graph.player.play(ClipLibrary.phrasePath(time, settings.format), settings.chime, gain, settings.boost, settings.musicMode)
            } finally {
                withContext(NonCancellable) { graph.volume.restore() }
            }
            when (result) {
                is PlayResult.Played -> graph.eventLog.add(
                    EventKind.SPOKEN,
                    if (isTest) "$label (test)" else label,
                    delayMs = if (isTest) null else Duration.between(scheduledAt, startedAt).toMillis(),
                )
                is PlayResult.Missing -> graph.eventLog.add(EventKind.PROBLEM, "$label: voice clip missing (${result.path})")
                is PlayResult.Failed -> graph.eventLog.add(EventKind.PROBLEM, "$label: playback failed (${result.error.message})")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.eventLog.add(EventKind.PROBLEM, "Announcement failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun skipReason(settings: Settings, scheduledAt: Instant, now: Instant, time: LocalTime): String? = when {
        !settings.enabled -> "announcements are off"
        now.toEpochMilli() < settings.pausedUntil -> "paused"
        settings.quietHours?.contains(time.hour * 60 + time.minute) == true -> "quiet hours"
        NextTimeCalculator.isTooLate(scheduledAt, now) ->
            "Android delivered the alarm ${Duration.between(scheduledAt, now).seconds} s late"
        settings.volumePercent == 0 -> "volume is 0"
        getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL -> "phone call in progress"
        else -> null
    }

    private suspend fun finish(startId: Int) {
        val keepStatus = StatusNotification.refresh(this)
        if (activeJobs > 0) return // another announcement arrived while the notification was updated
        // Detach rather than remove: the "Next: …" notification stays without flickering.
        ServiceCompat.stopForeground(
            this,
            if (keepStatus) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        stopSelf(startId)
        AnnounceWakeLock.release()
    }

    companion object {
        private const val ACTION_ANNOUNCE = "com.absi.talkingclock.action.ANNOUNCE"
        private const val ACTION_TEST = "com.absi.talkingclock.action.TEST"
        private const val EXTRA_SCHEDULED_AT = "scheduled_at"

        /** True while the service exists, so the UI doesn't touch the volume mid-announcement. */
        @Volatile
        var isRunning = false
            private set

        fun announce(context: Context, scheduledAt: Long) {
            context.startForegroundService(
                Intent(context, AnnouncerService::class.java)
                    .setAction(ACTION_ANNOUNCE)
                    .putExtra(EXTRA_SCHEDULED_AT, scheduledAt),
            )
        }

        fun test(context: Context) {
            context.startForegroundService(Intent(context, AnnouncerService::class.java).setAction(ACTION_TEST))
        }
    }
}
