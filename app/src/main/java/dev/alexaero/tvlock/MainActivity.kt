package dev.alexaero.tvlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import dev.alexaero.tvlock.ui.TvLockApp
import dev.alexaero.tvlock.ui.TvLockTheme

class MainActivity : ComponentActivity() {
    /** PIN entered for this visit to the settings. */
    private val authed = mutableStateOf(false)
    private var tvUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "phoneUi" (from adb) shows the phone companion on a TV, for testing it without a phone.
        if (intent.getBooleanExtra("phoneUi", false)) {
            setContent { TvLockTheme { dev.alexaero.tvlock.phone.PhoneApp(intent.data) } }
            return
        }
        if (!Platform.isTv(this)) {
            // The same APK is the phone companion: no lock service or boot lock on a phone.
            Platform.disableTvParts(this)
            setContent { TvLockTheme { dev.alexaero.tvlock.phone.PhoneApp(intent.data) } }
            return
        }
        tvUi = true
        val phoneSetup = intent.getBooleanExtra("phoneSetup", false)
        setContent { TvLockTheme { TvLockApp(authed, ::finish, phoneSetup) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!Platform.isTv(this)) dev.alexaero.tvlock.phone.PhoneLinks.incoming.value = intent.data
        else if (intent.getBooleanExtra("phoneSetup", false)) recreate()
    }

    override fun onResume() {
        super.onResume()
        if (tvUi) LockState.settingsOpen.value = true
    }

    override fun onPause() {
        super.onPause()
        if (tvUi) LockState.settingsOpen.value = false
    }

    override fun onStop() {
        super.onStop()
        // Leaving for a Settings page we opened ourselves keeps you signed in; anything else asks again.
        if (!LockState.inGrace()) authed.value = false
    }
}
