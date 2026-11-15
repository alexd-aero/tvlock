package dev.alexaero.tvlock.phone

import android.content.Context
import android.os.Build
import dadb.AdbKeyPair
import dadb.Dadb
import dev.alexaero.tvlock.Remote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Automatic setup over ADB (network debugging on the TV): installs or updates TV Lock on the TV,
 * grants everything it needs, pairs this phone and opens TV Lock on the TV. The TV asks once to
 * allow this phone; everything else happens without the remote.
 */
class AdbSetup(private val ctx: Context, private val ip: String) {
    enum class St { WAIT, RUN, DONE, SKIP, FAIL }
    data class Step(val title: String, val detail: String, val st: St = St.WAIT, val note: String? = null)

    val steps = MutableStateFlow(
        listOf(
            Step("Allow this phone on the TV", "The TV asks once. Press Allow with the remote and tick “Always allow”."),
            Step("Install TV Lock on the TV", "Copies this app to the TV, or updates it."),
            Step("Allow Accessibility", "Lets TV Lock show the lock screen over everything."),
            Step("Allow showing over other apps", "A backup way to show the lock right as the TV starts."),
            Step("Allow updates", "So TV Lock can update itself later."),
            Step("Lock when you switch HDMI inputs", "The box sleeps when the TV switches away, so it's locked when you come back."),
            Step("Connect this phone", "So this phone can unlock and control the TV."),
            Step("Open TV Lock on the TV", "The TV shows the setup screen while you finish here."),
        )
    )

    private fun set(i: Int, st: St, note: String? = null) {
        steps.value = steps.value.toMutableList().also { it[i] = it[i].copy(st = st, note = note) }
    }

    /** Runs every step; returns (host, token, TV name). Throws with a friendly message on failure. */
    suspend fun run(phoneName: String, existingToken: String? = null): Triple<String, String, String> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) throw ApiError("Automatic setup needs Android 8 or newer on this phone.")
        var i = 0
        try {
            set(i, St.RUN)
            val adb = connect()
            adb.use { d ->
                fun sh(cmd: String) = d.shell(cmd).allOutput.trim()
                val tvName = listOf(sh("getprop ro.product.manufacturer"), sh("getprop ro.product.model"))
                    .filter { it.isNotBlank() }.joinToString(" ").replace('_', ' ').ifBlank { "Android TV" }
                set(i, St.DONE, tvName)

                i = 1; set(i, St.RUN)
                val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val myCode = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else @Suppress("DEPRECATION") mine.versionCode.toLong()
                val theirs = Regex("versionCode=(\\d+)").find(sh("dumpsys package $PKG | grep versionCode"))?.groupValues?.get(1)?.toLongOrNull()
                if (theirs == null || theirs < myCode) {
                    d.install(File(ctx.applicationInfo.sourceDir), "-r")
                    set(i, St.DONE, if (theirs == null) "Installed version ${mine.versionName}" else "Updated to ${mine.versionName}")
                } else set(i, St.SKIP, "Already up to date")

                i = 2; set(i, St.RUN)
                sh("appops set $PKG ACCESS_RESTRICTED_SETTINGS allow")
                val cur = sh("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty()
                val others = cur.split(':').filter { it.isNotBlank() && !it.startsWith("$PKG/") }
                // Off and on again, so Android rereads the service after an update.
                if (others.isEmpty()) sh("settings delete secure enabled_accessibility_services")
                else sh("settings put secure enabled_accessibility_services '${others.joinToString(":")}'")
                delay(800)
                sh("settings put secure enabled_accessibility_services '${(others + SERVICE).joinToString(":")}'")
                sh("settings put secure accessibility_enabled 1")
                set(i, St.DONE)

                i = 3; set(i, St.RUN)
                set(i, if (sh("appops set $PKG SYSTEM_ALERT_WINDOW allow").isEmpty()) St.DONE else St.SKIP)

                i = 4; set(i, St.RUN)
                set(i, if (sh("appops set $PKG REQUEST_INSTALL_PACKAGES allow").isEmpty()) St.DONE else St.SKIP)

                i = 5; set(i, St.RUN)
                val cec = sh("cmd hdmi_control cec_setting set power_state_change_on_active_source_lost standby_now")
                if (cec.contains("rror") || cec.contains("nknown") || cec.contains("not found")) set(i, St.SKIP, "Not available on this TV")
                else set(i, St.DONE)

                i = 6; set(i, St.RUN)
                val token = existingToken ?: Remote.token(32)
                if (existingToken == null) {
                    val name = phoneName.replace(Regex("[^A-Za-z0-9 ._()-]"), "").take(40).ifBlank { "Phone" }
                    val out = sh("am broadcast -n $PKG/.SetupReceiver --es token $token --es name '$name'")
                    if (!out.contains("result=1")) throw ApiError("The TV didn't accept this phone. Open TV Lock on the TV once, then try again.")
                }
                // Wait for TV Lock's server to answer.
                var hello = TvApi("$ip:8642").hello()
                repeat(20) { if (hello == null) { delay(500); hello = TvApi("$ip:8642").hello() } }
                if (hello == null) throw ApiError("TV Lock on the TV isn't answering. Check that the TV and this phone are on the same home network.")
                set(i, St.DONE)

                i = 7; set(i, St.RUN)
                if (hello?.optBoolean("setupDone") == true) set(i, St.SKIP, "TV Lock was already set up")
                else {
                    sh("am start -n $PKG/.MainActivity --ez phoneSetup true")
                    set(i, St.DONE)
                }
                Triple("$ip:8642", token, hello?.optString("name")?.ifBlank { null } ?: tvName)
            }
        } catch (e: Exception) {
            set(i, St.FAIL, friendly(e, i))
            throw ApiError(friendly(e, i))
        }
    }

    private fun connect(): Dadb {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val d = Dadb.create(ip, 5555, AdbKeyPair.read(priv, pub), 8_000, 120_000)
        d.shell("echo ok") // the TV shows "Allow debugging?" here the first time
        return d
    }

    private fun friendly(e: Exception, step: Int): String = when {
        e is ApiError -> e.message ?: "Something went wrong"
        step == 0 -> "Couldn't connect. Make sure network debugging is on and press Allow on the TV, then try again."
        step == 1 -> "Couldn't install TV Lock on the TV (${e.message ?: e.javaClass.simpleName}). If the TV shows Play Protect, choose Install anyway."
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val PKG = "dev.alexaero.tvlock"
        const val SERVICE = "$PKG/$PKG.LockService"
    }
}
