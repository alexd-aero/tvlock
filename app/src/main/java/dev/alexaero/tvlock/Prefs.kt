package dev.alexaero.tvlock

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/** What the user unlocks with. */
enum class SecretKind { PIN, PIN_ANY, PASSWORD }

class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("tvlock", Context.MODE_PRIVATE)

    var setupDone by bool("setupDone", false)
    var enabled by bool("enabled", true)
    var bootLock by bool("bootLock", true)
    var appLock by bool("appLock", false)
    /** Also lock when the TV wakes from standby (most TVs rarely really reboot). */
    var lockOnWake by bool("lockOnWake", true)
    /** Relock unlocked apps whenever the home screen shows (otherwise only when the TV sleeps). */
    var relockOnHome by bool("relockOnHome", true)
    /** Lock after this many minutes without remote use (0 = never). Skipped while a video plays. */
    var idleMinutes by int("idleMinutes", 20)

    /** TV app look: theme id, custom background ("", image, gif, video) and how much it's dimmed. */
    var theme by str("theme")
    var bgType: String
        get() = sp.getString("bgType", "") ?: ""
        set(v) = sp.edit().putString("bgType", v).apply()
    var bgVersion by long("bgVersion", 0L)
    var bgDim by int("bgDim", 55)

    /** TV Lock may press keys on this TV through its own network debugging (volume, typing, waking the TV). */
    var selfAdbOk by bool("selfAdbOk", false)

    /** Paired phones may reach TV Lock's web remote on the home network. */
    var phoneAccess by bool("phoneAccess", false)
    /** JSON array of paired phones (see [Devices]). */
    var devices by str("devices")

    var lockedApps: Set<String>
        get() = sp.getStringSet("lockedApps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("lockedApps", v).apply()

    var secretKind: SecretKind
        get() = runCatching { SecretKind.valueOf(sp.getString("kind", null)!!) }.getOrDefault(SecretKind.PIN)
        set(v) = sp.edit().putString("kind", v.name).apply()
    var pinLength by int("pinLength", 4)

    var hash by str("hash")
    var salt by str("salt")
    var alg by str("alg")

    /** Boot count of the last boot the user already unlocked. */
    var unlockedBoot by int("unlockedBoot", -1)
    /** The TV went to sleep: lock again even though it is the same boot. */
    var relockPending by bool("relockPending", false)

    var fails by int("fails", 0)
    var lockoutUntil by long("lockoutUntil", 0L)

    /** Profiles (people and their PINs) as JSON; see [Profiles]. */
    var profiles by str("profiles")

    val hasSecret get() = Profiles.list(this).isNotEmpty()

    fun bootCount(): Int =
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)

    /** Should the TV-start lock be showing right now? */
    fun bootLockDue(): Boolean =
        setupDone && enabled && bootLock && hasSecret && (relockPending || unlockedBoot != bootCount())

    fun markBootUnlocked() {
        sp.edit().putInt("unlockedBoot", bootCount()).putBoolean("relockPending", false).apply()
    }

    private fun bool(k: String, d: Boolean) = Delegate({ sp.getBoolean(k, d) }, { sp.edit().putBoolean(k, it).apply() })
    private fun int(k: String, d: Int) = Delegate({ sp.getInt(k, d) }, { sp.edit().putInt(k, it).apply() })
    private fun long(k: String, d: Long) = Delegate({ sp.getLong(k, d) }, { sp.edit().putLong(k, it).apply() })
    private fun str(k: String) = Delegate<String?>({ sp.getString(k, null) }, { sp.edit().putString(k, it).apply() })

    class Delegate<T>(val get: () -> T, val set: (T) -> Unit) {
        operator fun getValue(r: Any?, p: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(r: Any?, p: kotlin.reflect.KProperty<*>, v: T) = set(v)
    }
}
