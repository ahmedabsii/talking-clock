package com.absi.talkingclock.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.absi.talkingclock.graph
import kotlinx.coroutines.launch
import java.time.Duration

/** Handles the status notification's Pause / Resume / Turn off buttons. */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val graph = context.graph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                when (action) {
                    ACTION_PAUSE_HOUR -> graph.controller.pauseFor(Duration.ofHours(1))
                    ACTION_RESUME -> graph.controller.resume()
                    ACTION_TURN_OFF -> graph.controller.setEnabled(false)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_HOUR = "com.absi.talkingclock.action.PAUSE_HOUR"
        const val ACTION_RESUME = "com.absi.talkingclock.action.RESUME"
        const val ACTION_TURN_OFF = "com.absi.talkingclock.action.TURN_OFF"

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, ControlReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
