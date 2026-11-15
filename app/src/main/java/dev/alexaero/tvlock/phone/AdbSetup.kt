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
            Step("Lock when you switch HDMI inputs", "The PIN is up when you switch back to this box. The box stays on."),
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
                    // The phone app is a different APK: fetch the TV app of the same version from GitHub.
                    val v = mine.versionName ?: ""
                    val apk = try {
                        if (dev.alexaero.tvlock.BuildConfig.TV) File(ctx.applicationInfo.sourceDir)
                        else dev.alexaero.tvlock.Updater.fetch(ctx, dev.alexaero.tvlock.Updater.downloadUrl(dev.alexaero.tvlock.Updater.tvApk(v), v), "tv.apk")
                    } catch (e: Exception) {
                        throw ApiError("Couldn't download TV Lock for the TV. Is this phone online? (${e.message})")
                    }
                    d.install(apk, "-r")
                    set(i, St.DONE, if (theirs == null) "Installed version ${mine.versionName}" else "Updated to ${mine.versionName}")
                } else set(i, St.SKIP, "Already up to date")

                grants(2) { sh(it) }

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

    /** Accessibility, overlay, updates and the HDMI input lock, from step [first] on. */
    private suspend fun grants(first: Int, sh: (String) -> String) {
        var i = first; set(i, St.RUN)
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

        i++; set(i, St.RUN)
        set(i, if (sh("appops set $PKG SYSTEM_ALERT_WINDOW allow").isEmpty()) St.DONE else St.SKIP)

        i++; set(i, St.RUN)
        set(i, if (sh("appops set $PKG REQUEST_INSTALL_PACKAGES allow").isEmpty()) St.DONE else St.SKIP)

        i++; set(i, St.RUN)
        // Read the HDMI-CEC state (to lock when the TV switches inputs), and keep the box awake when it does,
        // so it's on the moment you switch back.
        sh("cmd hdmi_control cec_setting set power_state_change_on_active_source_lost none")
        val grant = sh("pm grant $PKG android.permission.DUMP")
        if (grant.isNotEmpty()) set(i, St.SKIP, "Not available on this TV") else set(i, St.DONE)
    }

    /**
     * Setup on the TV itself: TV Lock connects to this TV's own network debugging, the TV asks once
     * ("Allow debugging?"), then TV Lock allows everything it needs by itself.
     */
    suspend fun runOnThisTv() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) throw ApiError("Automatic setup needs Android 8 or newer. Use the steps below instead.")
        steps.value = listOf(
            Step("Allow TV Lock to set itself up", "Press Allow, and tick “Always allow from this computer”."),
        ) + steps.value.subList(2, 6)
        var i = 0
        try {
            set(i, St.RUN)
            connect().use { d ->
                set(i, St.DONE)
                i = 1
                grants(1) { d.shell(it).allOutput.trim() }
            }
        } catch (e: Exception) {
            val msg = if (i == 0) "Couldn't connect to the TV's debugging. Press Allow when the TV asks, then try again." else friendly(e, i)
            set(i, St.FAIL, msg)
            throw ApiError(msg)
        }
    }

    private fun connect(): Dadb {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val d = Dadb.create(ip, PORT, AdbKeyPair.read(priv, pub), 8_000, 120_000)
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
        const val PORT = 5555

        /** Is network debugging switched on (on this TV: ip = 127.0.0.1)? */
        fun reachable(ip: String, timeout: Int = 700): Boolean = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, PORT), timeout) }
            true
        }.getOrDefault(false)
        const val SERVICE = "$PKG/$PKG.LockService"
    }
}
