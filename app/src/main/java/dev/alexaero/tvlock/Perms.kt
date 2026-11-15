package dev.alexaero.tvlock

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object Perms {
    val service get() = ComponentName("dev.alexaero.tvlock", "dev.alexaero.tvlock.LockService")

    fun accessibilityOn(ctx: Context): Boolean {
        if (LockState.serviceRunning.value) return true
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == service }
    }

    fun overlayOn(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Opens the best Settings page we can find; false if the TV has none of them. */
    fun openAccessibility(ctx: Context): Boolean = open(
        ctx,
        if (Build.VERSION.SDK_INT >= 31) Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString()) else null,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun openOverlay(ctx: Context): Boolean = open(
        ctx,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    fun openAppInfo(ctx: Context): Boolean = open(
        ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    private fun open(ctx: Context, vararg intents: Intent?): Boolean {
        LockState.grace()
        for (i in intents.filterNotNull()) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    const val ADB_A11Y =
        "adb shell settings put secure enabled_accessibility_services dev.alexaero.tvlock/.LockService\n" +
            "adb shell settings put secure accessibility_enabled 1"
    const val ADB_OVERLAY = "adb shell appops set dev.alexaero.tvlock SYSTEM_ALERT_WINDOW allow"
    const val ADB_RESTRICTED = "adb shell appops set dev.alexaero.tvlock ACCESS_RESTRICTED_SETTINGS allow"
}
