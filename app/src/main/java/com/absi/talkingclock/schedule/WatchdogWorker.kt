package com.absi.talkingclock.schedule

import android.content.Context
import android.os.UserManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.absi.talkingclock.data.EventKind
import com.absi.talkingclock.data.TimeLabels
import com.absi.talkingclock.graph
import com.absi.talkingclock.setup.SetupChecks
import java.util.concurrent.TimeUnit

/**
 * A second line of defence that runs every 15 minutes: if the alarm chain has broken (a slot never
 * fired, or no alarm is armed), it re-arms it. If the exact-alarm permission was taken away, the
 * re-arm attempt is what tells the user, since nothing can be scheduled until they allow it again.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val settings = graph.settings.current()
        if (!settings.enabled) return Result.success()

        val armedAt = graph.settings.currentRuntime().nextTriggerAt
        if (!SetupChecks.exactAlarmsAllowed(applicationContext)) {
            // The scheduler reports the revoked permission (once) and updates the notification.
            graph.controller.refresh()
        } else if (armedAt == 0L || armedAt < System.currentTimeMillis() - STALE_AFTER_MS) {
            graph.controller.refresh()
            val what = if (armedAt == 0L) "no alarm was set" else "the ${TimeLabels.label(armedAt, settings.format)} alarm never fired"
            graph.eventLog.add(EventKind.PROBLEM, "Watchdog: $what; re-armed")
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "watchdog"
        private const val STALE_AFTER_MS = 3 * 60_000L

        fun ensure(context: Context, enabled: Boolean) {
            // WorkManager's database is in credential-encrypted storage, unreadable before the
            // first unlock; BOOT_COMPLETED calls this again once the user has unlocked.
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            val workManager = WorkManager.getInstance(context)
            if (enabled) {
                workManager.enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
                )
            } else {
                workManager.cancelUniqueWork(NAME)
            }
        }
    }
}
