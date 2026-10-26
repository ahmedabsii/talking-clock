package com.absi.talkingclock.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import com.absi.talkingclock.R
import com.absi.talkingclock.setup.SetupChecks

/**
 * Brands whose own battery managers kill apps that stock Android leaves alone. The steps follow
 * dontkillmyapp.com; the screens are private to each brand, so each one is tried in turn.
 */
data class OemGuide(val brand: String, @StringRes val steps: Int, val screens: List<ComponentName>)

object OemGuides {

    fun forThisDevice(): OemGuide? = forManufacturer(Build.MANUFACTURER)

    fun forManufacturer(manufacturer: String): OemGuide? = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> OemGuide(
            "Xiaomi", R.string.oem_xiaomi,
            listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        )
        "samsung" -> OemGuide(
            "Samsung", R.string.oem_samsung,
            listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ),
        )
        "huawei", "honor" -> OemGuide(
            "Huawei", R.string.oem_huawei,
            listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ),
        )
        "oneplus" -> OemGuide(
            "OnePlus", R.string.oem_oneplus,
            listOf(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
        )
        "oppo", "realme" -> OemGuide(
            "Oppo / Realme", R.string.oem_oppo,
            listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ),
        )
        "vivo", "iqoo" -> OemGuide(
            "Vivo", R.string.oem_vivo,
            listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ),
        )
        "asus" -> OemGuide(
            "Asus", R.string.oem_asus,
            listOf(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")),
        )
        else -> null
    }

    /** Opens the brand's own settings screen, falling back to App info when none can be opened. */
    fun open(context: Context, guide: OemGuide) {
        for (screen in guide.screens) {
            try {
                context.startActivity(Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: RuntimeException) {
                // Not present on this model, or not exported; try the next one.
            }
        }
        context.startActivity(SetupChecks.appInfo(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
