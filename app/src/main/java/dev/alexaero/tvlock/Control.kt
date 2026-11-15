package dev.alexaero.tvlock

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** What a paired phone can do to the TV. Called from the web server's threads. */
object Control {
    private val main = Handler(Looper.getMainLooper())
    private val icons = ConcurrentHashMap<String, ByteArray>()

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val t = FutureTask(java.util.concurrent.Callable { block() })
        main.post(t)
        return t.get(5, TimeUnit.SECONDS)
    }

    private val svc get() = LockService.instance

    fun state(ctx: Context, me: Device): JSONObject {
        val p = Prefs(ctx)
        val lock = LockState.current.value
        val fg = LockState.foreground
        val tv = JSONObject()
            .put("name", Remote.tvName())
            .put("version", Updater.current(ctx))
            .put("android", Build.VERSION.RELEASE)
            .put("service", svc != null)
            .put("awake", ctx.getSystemService(PowerManager::class.java).isInteractive)
            .put("lock", when (lock) { Lock.Boot -> "tv"; is Lock.App -> "app"; Lock.Test -> "test"; null -> null })
            .put("lockApp", (lock as? Lock.App)?.let { Apps.label(ctx, it.pkg) })
            .put("foreground", fg?.let { Apps.label(ctx, it) })
            .put("address", Remote.baseUrl())
        val settings = JSONObject()
            .put("enabled", p.enabled).put("bootLock", p.bootLock).put("appLock", p.appLock)
            .put("lockOnWake", p.lockOnWake).put("relockOnHome", p.relockOnHome).put("idleMinutes", p.idleMinutes)
            .put("kind", Profiles.padFor(p).first.name).put("pinLength", Profiles.padFor(p).second)
            .put("lockedApps", JSONArray(p.lockedApps.toList()))
        val devices = JSONArray()
        Devices.list(p).forEach { devices.put(it.json(it.id == me.id)) }
        val profiles = JSONArray()
        Profiles.list(p).forEach { profiles.put(it.json()) }
        return JSONObject().put("tv", tv).put("settings", settings).put("devices", devices).put("me", me.json(true))
            .put("profiles", profiles)
    }

    // ------------------------------------------------------------- lock

    fun unlock(ctx: Context): Boolean = onMain {
        if (LockState.current.value == null) return@onMain false
        Secret.recordSuccess(Prefs(ctx))
        LockState.unlock(ctx)
        true
    }

    fun lockNow(ctx: Context): Boolean = onMain {
        val p = Prefs(ctx)
        if (!p.hasSecret) return@onMain false
        LockState.unlocked.clear()
        p.relockPending = true
        LockState.show(ctx, Lock.Boot)
        true
    }

    // ------------------------------------------------------------- remote control

    fun key(ctx: Context, k: String): Boolean {
        val audio = ctx.getSystemService(AudioManager::class.java)
        when (k) {
            "playpause" -> return media(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> return media(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> return media(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "rewind" -> return media(audio, KeyEvent.KEYCODE_MEDIA_REWIND)
            "ffwd" -> return media(audio, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "volup", "voldown", "mute" -> {
                val dir = when (k) {
                    "volup" -> AudioManager.ADJUST_RAISE
                    "voldown" -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                }
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                return true
            }
            "wake" -> return wake(ctx)
            "settings" -> return onMain {
                LockState.grace()
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
        }
        val s = svc ?: return false
        return onMain {
            val global = when (k) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "sleep" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else null
                "up", "down", "left", "right", "center" ->
                    if (Build.VERSION.SDK_INT >= 33) when (k) {
                        "up" -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                        "down" -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                        "left" -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                        "right" -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                        else -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                    } else return@onMain dpadFallback(s, k)
                else -> null
            }
            global != null && s.performGlobalAction(global)
        }
    }

    private fun media(audio: AudioManager, code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return true
    }

    /** Android 12 and older have no D-pad global actions: move focus by hand. */
    private fun dpadFallback(s: LockService, k: String): Boolean {
        val f = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (k == "center") return f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val dir = when (k) {
            "up" -> View.FOCUS_UP
            "down" -> View.FOCUS_DOWN
            "left" -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        return f.focusSearch(dir)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun wake(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return true
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "tvlock:wake",
        )
        wl.acquire(3_000)
        return true
    }

    /** Types into the focused text box on the TV (search fields and so on). */
    fun type(text: String, submit: Boolean): Boolean {
        val s = svc ?: return false
        return onMain {
            val node = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node == null || !node.isEditable) return@onMain false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (submit && Build.VERSION.SDK_INT >= 30) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            ok
        }
    }

    // ------------------------------------------------------------- apps

    fun apps(ctx: Context): JSONArray {
        val locked = Prefs(ctx).lockedApps
        val a = JSONArray()
        Apps.list(ctx).forEach {
            a.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("wide", it.wide)
                .put("suggested", it.suggested).put("locked", it.pkg in locked))
            if (!icons.containsKey(it.pkg)) it.art?.let { d -> icons[it.pkg] = png(d, it.wide) }
        }
        return a
    }

    fun icon(ctx: Context, pkg: String): ByteArray? = icons[pkg] ?: runCatching {
        Apps.list(ctx).firstOrNull { it.pkg == pkg }?.let { a -> a.art?.let { png(it, a.wide) } }
    }.getOrNull()?.also { icons[pkg] = it }

    private fun png(d: Drawable, wide: Boolean): ByteArray {
        val b = if (wide) d.toBitmap(320, 180) else d.toBitmap(144, 144)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun launch(ctx: Context, pkg: String): Boolean = onMain {
        LockState.graceUntil = 0
        Apps.launch(ctx, pkg)
    }

    // ------------------------------------------------------------- settings

    fun settings(ctx: Context, b: JSONObject): Boolean {
        val p = Prefs(ctx)
        if (b.has("enabled")) {
            p.enabled = b.getBoolean("enabled")
            if (p.enabled) p.markBootUnlocked() else onMain { LockState.unlocked.clear(); if (LockState.current.value is Lock.App) LockState.current.value = null }
        }
        if (b.has("bootLock")) { p.bootLock = b.getBoolean("bootLock"); p.markBootUnlocked() }
        if (b.has("appLock")) p.appLock = b.getBoolean("appLock")
        if (b.has("lockOnWake")) p.lockOnWake = b.getBoolean("lockOnWake")
        if (b.has("relockOnHome")) p.relockOnHome = b.getBoolean("relockOnHome")
        if (b.has("idleMinutes")) p.idleMinutes = b.getInt("idleMinutes").coerceIn(0, 240)
        if (b.has("lockedApps")) {
            val a = b.getJSONArray("lockedApps")
            p.lockedApps = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        return true
    }

    // ------------------------------------------------------------- profiles

    private fun kindOf(b: JSONObject) = runCatching { SecretKind.valueOf(b.optString("kind", "PIN")) }.getOrDefault(SecretKind.PIN)

    /** New profile. Returns an error message, or null. */
    fun addProfile(ctx: Context, b: JSONObject): Pair<String?, Profile?> {
        val p = Prefs(ctx)
        val name = b.optString("name").trim()
        if (name.isEmpty()) return "Give the profile a name." to null
        val kind = kindOf(b)
        val s = b.optString("pin")
        Profiles.validate(p, s, kind)?.let { return it to null }
        val count = Profiles.list(p).size
        return null to Profiles.add(p, name, b.optInt("color", count % Profiles.palette.size), s, kind)
    }

    fun profilePin(ctx: Context, b: JSONObject): String? {
        val p = Prefs(ctx)
        val id = b.optString("id")
        if (Profiles.get(p, id) == null) return "No such profile."
        val kind = kindOf(b)
        val s = b.optString("pin")
        Profiles.validate(p, s, kind, id)?.let { return it }
        Profiles.setSecret(p, id, s, kind)
        return null
    }

    /** Saves an uploaded photo as a 512 px square JPEG. */
    fun setPhoto(ctx: Context, id: String, bytes: ByteArray): Boolean {
        val p = Prefs(ctx)
        if (Profiles.get(p, id) == null) return false
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(sq, 512, 512, true)
        Profiles.photoFile(ctx, id).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        Profiles.update(p, id, photo = System.currentTimeMillis())
        return true
    }

    fun removePhoto(ctx: Context, id: String): Boolean {
        Profiles.photoFile(ctx, id).delete()
        Profiles.update(Prefs(ctx), id, photo = 0)
        return true
    }

}
