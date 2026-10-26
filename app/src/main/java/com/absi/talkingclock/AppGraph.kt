package com.absi.talkingclock

import android.app.Application
import com.absi.talkingclock.audio.ClipPlayer
import com.absi.talkingclock.audio.VolumeController
import com.absi.talkingclock.data.EventLog
import com.absi.talkingclock.data.SettingsRepository
import com.absi.talkingclock.schedule.AnnouncementController
import com.absi.talkingclock.schedule.AnnouncementScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppGraph(app: Application) {
    // Device-protected storage stays readable before the first unlock after a reboot, so the
    // announcement chain can restart while the phone is still locked.
    private val storage = app.createDeviceProtectedStorageContext()

    /** For work that must outlive a receiver's onReceive (always paired with goAsync()). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(storage)
    val eventLog = EventLog(File(storage.filesDir, "events.log"))
    val scheduler = AnnouncementScheduler(app, settings, eventLog)
    val controller = AnnouncementController(app, settings, scheduler, eventLog)
    val volume = VolumeController(app, settings, eventLog)
    val player = ClipPlayer(app)
}
