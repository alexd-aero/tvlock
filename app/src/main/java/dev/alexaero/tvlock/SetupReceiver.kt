package dev.alexaero.tvlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Automatic setup from the phone app: over ADB the phone registers its token here
 * (`am broadcast -n dev.alexaero.tvlock/.SetupReceiver --es token … --es name …`).
 * Protected by the DUMP permission, so only the ADB shell and the system can send it.
 */
class SetupReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        if (token.length < 32) return
        val p = Prefs(ctx)
        if (Devices.find(p, token) == null) Remote.addDevice(p, token, intent.getStringExtra("name") ?: "Phone")
        p.phoneAccess = true
        Remote.start(ctx)
        resultCode = 1
        // Automatic setup from a phone: let TV Lock press real keys (the TV asks "Allow debugging?" once more).
        if (intent.getBooleanExtra("enableKeys", false) && !p.selfAdbOk) {
            Thread { SelfAdb.enable(ctx.applicationContext) }.start()
        }
    }
}
