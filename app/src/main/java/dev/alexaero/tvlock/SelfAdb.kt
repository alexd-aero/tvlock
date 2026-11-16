package dev.alexaero.tvlock

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * TV Lock pressing real keys on its own TV: one connection to this TV's network debugging (127.0.0.1:5555)
 * with TV Lock's key. Real key presses reach HDMI-CEC (TV / soundbar volume), every app's text boxes,
 * and the power/wake logic that apps can't use otherwise.
 *
 * The first connection makes the TV ask "Allow debugging?" once, so it only happens when asked for
 * ([enable], from setup or Options); afterwards [Prefs.selfAdbOk] is set and keys go through silently.
 */
object SelfAdb {
    private const val TAG = "TvLockKeys"
    private val lock = Any()
    private var conn: Dadb? = null

    private fun open(ctx: Context, waitForAllow: Boolean): Dadb {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val d = Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub), 3_000, if (waitForAllow) 120_000 else 6_000)
        d.shell("true")
        return d
    }

    /** Asks the TV once ("Allow debugging?"). Blocking; call off the main thread. */
    fun enable(ctx: Context): Boolean = synchronized(lock) {
        if (android.os.Build.VERSION.SDK_INT < 26) return false
        runCatching {
            conn?.close()
            conn = open(ctx, waitForAllow = true)
            Prefs(ctx).selfAdbOk = true
            Live.bump()
            true
        }.getOrElse { Log.w(TAG, "enable failed", it); conn = null; false }
    }

    val enabled get() = conn != null

    /** Runs a shell command as the TV's shell user; null if keys aren't allowed or it failed. */
    fun shell(ctx: Context, cmd: String): String? = synchronized(lock) {
        if (android.os.Build.VERSION.SDK_INT < 26 || !Prefs(ctx).selfAdbOk) return null
        repeat(2) {
            try {
                val d = conn ?: open(ctx, waitForAllow = false).also { conn = it }
                return d.shell(cmd).allOutput
            } catch (e: Exception) {
                Log.w(TAG, "shell failed: $cmd", e)
                runCatching { conn?.close() }
                conn = null
            }
        }
        null
    }

    fun key(ctx: Context, vararg codes: String) = shell(ctx, "input keyevent " + codes.joinToString(" ")) != null

    /** Types text into whatever text box has focus on the TV. */
    fun type(ctx: Context, text: String): Boolean {
        if (text.isEmpty()) return true
        // `input text` needs %s for spaces; everything else goes in single quotes for the shell.
        val arg = text.replace("%", "\\%").replace(" ", "%s").replace("'", "'\\''")
        return shell(ctx, "input text '$arg'") != null
    }
}
