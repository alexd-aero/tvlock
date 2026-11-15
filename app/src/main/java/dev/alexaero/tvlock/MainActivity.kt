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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvLockTheme { TvLockApp(authed, ::finish) } }
    }

    override fun onStop() {
        super.onStop()
        // Leaving for a Settings page we opened ourselves keeps you signed in; anything else asks again.
        if (!LockState.inGrace()) authed.value = false
    }
}
