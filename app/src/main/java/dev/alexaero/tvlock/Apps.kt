package dev.alexaero.tvlock

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings

data class AppEntry(
    val pkg: String,
    val label: String,
    val art: Drawable?,
    /** The art is a wide TV banner rather than a square icon. */
    val wide: Boolean,
    val suggested: Boolean,
)

object Apps {
    private val storePkgs = setOf("com.android.vending")

    /** Every app you can open from the TV's home screen, plus Settings and the Play Store. */
    fun list(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, AppEntry>()
        val settingsPkgs = pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), 0)
            .map { it.activityInfo.packageName }.toSet()

        fun addAll(category: String) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val ai = ri.activityInfo
                val pkg = ai.packageName
                if (pkg == ctx.packageName || pkg in out) continue
                val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
                out[pkg] = AppEntry(
                    pkg, ri.loadLabel(pm).toString(),
                    banner ?: runCatching { ai.loadIcon(pm) }.getOrNull(), banner != null,
                    pkg in settingsPkgs || pkg in storePkgs,
                )
            }
        }
        addAll(Intent.CATEGORY_LEANBACK_LAUNCHER)
        addAll(Intent.CATEGORY_LAUNCHER)

        // TV Settings usually has no home-screen tile; add it so it can be locked.
        for (pkg in settingsPkgs + storePkgs) {
            if (pkg in out || pkg == ctx.packageName) continue
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val banner = runCatching { ai.loadBanner(pm) }.getOrNull()
            out[pkg] = AppEntry(pkg, pm.getApplicationLabel(ai).toString(), banner ?: ai.loadIcon(pm), banner != null, true)
        }
        return out.values.sortedWith(compareByDescending<AppEntry> { it.suggested }.thenBy { it.label.lowercase() })
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: if (pkg in pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), 0).map { it.activityInfo.packageName })
                Intent(Settings.ACTION_SETTINGS) else null
        i ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
