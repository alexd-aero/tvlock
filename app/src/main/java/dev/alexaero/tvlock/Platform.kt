package dev.alexaero.tvlock

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object Platform {
    fun isTv(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            ctx.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /** On a phone, switch off the parts that only make sense on a TV. */
    fun disableTvParts(ctx: Context) {
        val pm = ctx.packageManager
        for (cls in listOf(LockService::class.java, BootReceiver::class.java)) {
            val c = ComponentName(ctx, cls)
            if (pm.getComponentEnabledSetting(c) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
