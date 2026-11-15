package dev.alexaero.tvlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.alexaero.tvlock.ui.LockSurface
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Fallback lock screen, used only while the accessibility service is not running. */
class LockActivity : ComponentActivity() {
    private lateinit var silencer: Silencer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        silencer = Silencer(this)
        setContent { LockSurface(onLeave = ::leave) }
        lifecycleScope.launch {
            combine(LockState.current, LockState.serviceRunning, LockState.overlayBroken) { l, s, broken ->
                l == null || (s && !broken)
            }
                .collect { done -> if (done) finish() }
        }
    }

    private fun leave() {
        when (LockState.current.value) {
            Lock.Test -> LockState.cancelTest()
            is Lock.App -> {
                LockState.current.value = null
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            else -> {}
        }
    }

    override fun onResume() {
        super.onResume()
        silencer.take()
    }

    override fun onPause() {
        super.onPause()
        silencer.release()
    }

    @Deprecated("Back is handled by the lock screen itself")
    override fun onBackPressed() {
        // The lock screen handles Back; never let it close a start lock.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pressed Home on the start lock: bring it straight back.
        if (LockState.current.value == Lock.Boot) {
            window.decorView.postDelayed({
                if (LockState.current.value == Lock.Boot &&
                    (!LockState.serviceRunning.value || LockState.overlayBroken.value)
                ) {
                    startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, 300)
        }
    }
}
