package dev.alexaero.tvlock

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** What the lock screen is guarding right now. */
sealed interface Lock {
    /** The TV just started (or woke up). */
    data object Boot : Lock
    /** A locked app came to the front. */
    data class App(val pkg: String) : Lock
    /** Started from the TV Lock app to try the lock out; Back cancels it. */
    data object Test : Lock
}

/** Shared in-process state between the accessibility service, the fallback activity and the app. */
object LockState {
    val current = MutableStateFlow<Lock?>(null)
    val serviceRunning = MutableStateFlow(false)
    /** This TV never gave the overlay key focus: use the lock activity instead. */
    val overlayBroken = MutableStateFlow(false)
    /** null = no test yet, true = unlocked with the right PIN, false = cancelled. */
    val testResult = MutableStateFlow<Boolean?>(null)

    /** Who just unlocked: the lock shows "Hello, Name" before it fades away. */
    val greeting = MutableStateFlow<Profile?>(null)

    /** Apps unlocked since the last home screen / sleep. */
    val unlocked = mutableSetOf<String>()
    /** Last real app on screen (from the accessibility service). */
    @Volatile var foreground: String? = null
    /** TV Lock opened a Settings page itself: don't lock Settings for a few minutes. */
    @Volatile var graceUntil = 0L
    /** Last time someone used the remote on this box (elapsedRealtime). */
    @Volatile var lastActivity = SystemClock.elapsedRealtime()

    fun grace(minutes: Int = 3) {
        graceUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun inGrace() = SystemClock.elapsedRealtime() < graceUntil

    /** The right PIN was entered on the lock screen. */
    fun unlock(ctx: Context, who: Profile? = null) {
        // Greeting first, so the overlay stays up for it.
        if (who != null && current.value != null) greeting.value = who
        when (val l = current.value) {
            Lock.Boot -> {
                Prefs(ctx).markBootUnlocked()
                // Whatever app is already behind the start lock counts as unlocked too.
                foreground?.let { unlocked += it }
            }
            is Lock.App -> unlocked += l.pkg
            Lock.Test -> testResult.value = true
            null -> {}
        }
        current.value = null
    }

    fun cancelTest() {
        if (current.value == Lock.Test) {
            testResult.value = false
            current.value = null
        }
    }

    /** Show the lock: through the service's overlay, or the fallback activity if it isn't running. */
    fun show(ctx: Context, lock: Lock) {
        current.value = lock
        if (!serviceRunning.value || overlayBroken.value) startActivity(ctx)
    }

    fun startActivity(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
