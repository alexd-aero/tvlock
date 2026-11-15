package dev.alexaero.tvlock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub Releases: the newest release's .apk is downloaded and installed over
 * this one. Same signing key, so the PIN and settings survive the update.
 */
object Updater {
    const val REPO = "alexd-aero/tvlock"

    /** Release files: TV-Lock-1.4.0.apk is the TV app, TV-Lock-Phone-1.4.0.apk the phone app. */
    fun tvApk(version: String) = "TV-Lock-$version.apk"
    fun phoneApk(version: String) = "TV-Lock-Phone-$version.apk"
    fun downloadUrl(name: String, version: String) = "https://github.com/$REPO/releases/download/v$version/$name"

    @Volatile private var latestPhone: Pair<Long, String>? = null

    /** The newest phone app on GitHub (looked up at most every 10 minutes); this TV's version if GitHub can't be reached. */
    fun latestPhoneApkUrl(ctx: Context): String {
        latestPhone?.let { (at, url) -> if (System.currentTimeMillis() - at < 10 * 60_000) return url }
        val url = runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "TV-Lock")
            val j = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            c.disconnect()
            val assets = j.getJSONArray("assets")
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .first { it.getString("name").contains("Phone") && it.getString("name").endsWith(".apk") }
                .getString("browser_download_url")
        }.getOrNull()
        if (url != null) latestPhone = System.currentTimeMillis() to url
        val v = current(ctx)
        return url ?: downloadUrl(phoneApk(v), v)
    }

    private fun isMine(name: String) =
        if (BuildConfig.TV) name.endsWith(".apk") && !name.contains("Phone") else name.contains("Phone") && name.endsWith(".apk")

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "1.10.0" > "1.9.2"; a leading v is ignored. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(ctx: Context) {
        val s = state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return
        state.value = State.Checking
        state.value = try {
            val rel = latest()
            when {
                rel == null -> State.Failed("No releases found on github.com/$REPO yet")
                isNewer(rel.version, current(ctx)) -> State.Available(rel)
                else -> State.UpToDate
            }
        } catch (e: Exception) {
            State.Failed("Couldn't reach GitHub (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "TV-Lock")
        try {
            if (c.responseCode == 404) return@withContext null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (isMine(a.getString("name"))) {
                    return@withContext Release(
                        j.getString("tag_name").removePrefix("v"),
                        j.optString("body", "").trim(),
                        a.getString("browser_download_url"),
                        a.optLong("size", 0),
                    )
                }
            }
            null
        } finally {
            c.disconnect()
        }
    }

    /** Android asks once whether TV Lock may install apps ("Unknown sources"). */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context): Boolean {
        LockState.grace()
        val tries = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 26)
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")) else null,
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in tries) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    suspend fun downloadAndInstall(ctx: Context, rel: Release) {
        val app = ctx.applicationContext
        try {
            state.value = State.Downloading(rel, 0f)
            val file = withContext(Dispatchers.IO) { download(app, rel) }
            state.value = State.Installing(rel)
            withContext(Dispatchers.IO) { install(app, file) }
        } catch (e: Exception) {
            state.value = State.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Downloads any APK (used by the phone to fetch the TV app for automatic setup). */
    fun fetch(ctx: Context, url: String, name: String): File = download(ctx, Release("", "", url, 0), name)

    private fun download(ctx: Context, rel: Release, name: String = "update.apk"): File {
        val out = File(ctx.cacheDir, name)
        var url = URL(rel.apkUrl)
        // GitHub answers with a redirect to its download host.
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "TV-Lock")
            when (c.responseCode) {
                in 300..399 -> {
                    url = URL(url, c.getHeaderField("Location"))
                    c.disconnect()
                }
                in 200..299 -> {
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                    c.inputStream.use { inp ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0 && rel.version.isNotEmpty()) state.value = State.Downloading(rel, done.toFloat() / total)
                            }
                        }
                    }
                    c.disconnect()
                    return out
                }
                else -> error("HTTP ${c.responseCode}")
            }
        }
        error("too many redirects")
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("tvlock.apk", 0, apk.length()).use { o ->
                apk.inputStream().use { it.copyTo(o) }
                s.fsync(o)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            LockState.grace()
            s.commit(cb.intentSender)
        }
    }
}

/** Gets the installer's answer; shows Android's "Update this app?" prompt when it needs one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                LockState.grace()
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {} // this process is replaced right after
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Updater.state.value = Updater.State.Failed("Install failed: $msg")
            }
        }
    }
}
