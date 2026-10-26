package com.absi.talkingclock.setup

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import com.absi.talkingclock.oem.OemGuide
import com.absi.talkingclock.oem.OemGuides

data class SetupStatus(
    val exactAlarms: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val oemGuide: OemGuide?,
    val oemDone: Boolean,
) {
    /** Steps still worth doing. Only [exactAlarms] actually blocks announcements. */
    val openSteps: Int
        get() = listOf(exactAlarms, notifications, battery, oemGuide == null || oemDone).count { !it }
}

/** Reads the system settings that decide whether announcements arrive on time. */
object SetupChecks {

    fun status(context: Context, oemDone: Boolean) = SetupStatus(
        exactAlarms = exactAlarmsAllowed(context),
        notifications = notificationsAllowed(context),
        battery = batteryUnrestricted(context),
        oemGuide = OemGuides.forThisDevice(),
        oemDone = oemDone,
    )

    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun batteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return power.isIgnoringBatteryOptimizations(context.packageName) && !activity.isBackgroundRestricted
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun exactAlarmSettings(context: Context) =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))

    fun appInfo(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun notificationSettings(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
}
