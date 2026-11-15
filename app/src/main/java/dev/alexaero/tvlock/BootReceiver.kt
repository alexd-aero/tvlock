package dev.alexaero.tvlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Backup for the TV-start lock. The accessibility service normally locks on its own as soon as
 * Android starts it; if it isn't running (yet), this shows the lock activity instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return // the service restarts by itself
        val p = Prefs(ctx)
        if (!p.bootLockDue()) return
        val pending = goAsync()
        // Give the system a moment to bind the accessibility service; it draws a stronger lock.
        Handler(Looper.getMainLooper()).postDelayed({
            if (p.bootLockDue() && LockState.current.value == null) LockState.show(ctx.applicationContext, Lock.Boot)
            pending.finish()
        }, if (LockState.serviceRunning.value) 0 else 1500)
    }
}
