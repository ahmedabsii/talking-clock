package com.absi.talkingclock.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.absi.talkingclock.ui.theme.TalkingClockTheme

class MainActivity : ComponentActivity() {

    /** A screen asked for by the launching intent (the "stopped" alert opens Setup). */
    private var requestedScreen by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestedScreen = screenFor(intent)
        setContent {
            TalkingClockTheme {
                TalkingClockApp(requestedScreen = requestedScreen, onRequestHandled = { requestedScreen = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedScreen = screenFor(intent)
    }

    private fun screenFor(intent: Intent?): Screen? =
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETUP, false) == true) Screen.SETUP else null

    companion object {
        private const val EXTRA_OPEN_SETUP = "open_setup"

        fun intent(context: Context, openSetup: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_SETUP, openSetup)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
