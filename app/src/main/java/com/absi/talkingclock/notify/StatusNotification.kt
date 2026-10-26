package com.absi.talkingclock.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.absi.talkingclock.R
import com.absi.talkingclock.data.TimeLabels
import com.absi.talkingclock.graph
import com.absi.talkingclock.ui.MainActivity

/**
 * One quiet, ongoing notification ("Next: 3:30 PM") with Pause and Turn off buttons. The
 * announcer's foreground service borrows the same ID, so nothing pops in or out at each
 * announcement. A second, high-priority channel warns when announcements have stopped.
 */
object StatusNotification {
    const val ID = 1
    private const val STOPPED_ALERT_ID = 2
    private const val CHANNEL_STATUS = "status"
    private const val CHANNEL_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val status = NotificationChannelCompat.Builder(CHANNEL_STATUS, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.channel_status))
            .setDescription(context.getString(R.string.channel_status_desc))
            .setShowBadge(false)
            .build()
        val alerts = NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.channel_alerts))
            .setDescription(context.getString(R.string.channel_alerts_desc))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(status, alerts))
    }

    /** Shown while an announcement plays; [refresh] replaces it as soon as it has finished. */
    fun speaking(context: Context): Notification =
        statusBuilder(context).setContentText(context.getString(R.string.status_speaking)).build()

    /**
     * Shows when the next announcement is, or removes the notification when announcements are off.
     * Returns whether it is showing.
     */
    suspend fun refresh(context: Context): Boolean {
        val graph = context.graph
        val settings = graph.settings.current()
        val manager = NotificationManagerCompat.from(context)
        if (!settings.enabled) {
            manager.cancel(ID)
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nextAt = graph.settings.currentRuntime().nextTriggerAt
        val paused = settings.pausedUntil > System.currentTimeMillis()
        val text = when {
            paused -> context.getString(R.string.status_paused_until, TimeLabels.label(settings.pausedUntil, settings.format))
            nextAt > 0 -> context.getString(R.string.status_next, TimeLabels.label(nextAt, settings.format))
            else -> context.getString(R.string.status_not_scheduled)
        }
        val builder = statusBuilder(context).setContentText(text)
        if (paused) {
            builder.addAction(0, context.getString(R.string.action_resume), ControlReceiver.pendingIntent(context, ControlReceiver.ACTION_RESUME))
        } else {
            builder.addAction(0, context.getString(R.string.action_pause_hour), ControlReceiver.pendingIntent(context, ControlReceiver.ACTION_PAUSE_HOUR))
        }
        builder.addAction(0, context.getString(R.string.action_turn_off), ControlReceiver.pendingIntent(context, ControlReceiver.ACTION_TURN_OFF))
        manager.notify(ID, builder.build())
        return true
    }

    fun showStoppedAlert(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = context.getString(R.string.alert_stopped_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(context.getString(R.string.alert_stopped_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, openSetup = true))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(STOPPED_ALERT_ID, notification)
    }

    fun cancelStoppedAlert(context: Context) {
        NotificationManagerCompat.from(context).cancel(STOPPED_ALERT_ID)
    }

    private fun statusBuilder(context: Context) = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_stat_clock)
        .setContentTitle(context.getString(R.string.app_name))
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setContentIntent(openApp(context, openSetup = false))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private fun openApp(context: Context, openSetup: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        if (openSetup) 11 else 10,
        MainActivity.intent(context, openSetup),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
