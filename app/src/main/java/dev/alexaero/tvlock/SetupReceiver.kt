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
        Remote.addDevice(p, token, intent.getStringExtra("name") ?: "Phone")
        p.phoneAccess = true
        Remote.start(ctx)
        resultCode = 1
    }
}
