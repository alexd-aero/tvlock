package dev.alexaero.tvlock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import dev.alexaero.tvlock.ui.LockSurface
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The lock itself. Watches which app is on screen and covers the TV with the PIN screen
 * when it starts/wakes and when a locked app opens.
 */
class LockService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay
    private lateinit var silencer: Silencer
    private val activityCache = HashMap<String, Boolean>()
    private var homes: Set<String> = emptySet()

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) {
                if (overlay.showing) verifyOverlay()
                return
            }
            if (i.action != Intent.ACTION_SCREEN_OFF) return
            LockState.unlocked.clear()
            if (LockState.current.value is Lock.App) LockState.current.value = null
            if (prefs.setupDone && prefs.enabled && prefs.bootLock && prefs.lockOnWake && prefs.hasSecret) {
                // Lock now, so the PIN screen is already up when the TV wakes.
                prefs.relockPending = true
                LockState.current.value = Lock.Boot
            }
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        overlay = Overlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        silencer = Silencer(this)
        homes = homePackages()
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        LockState.serviceRunning.value = true
        if (prefs.bootLockDue()) LockState.current.value = Lock.Boot
        scope.launch {
            LockState.current.collect { lock ->
                if (lock == null) {
                    overlay.hide()
                    silencer.release()
                } else {
                    silencer.take()
                    if (LockState.overlayBroken.value) {
                        LockState.startActivity(this@LockService)
                    } else if (!overlay.showing) {
                        overlay.show { LockSurface(onLeave = ::leave) }
                        verifyOverlay()
                    }
                }
            }
        }
    }

    /**
     * Some TVs may not hand an accessibility overlay the remote's keys. If the lock window never
     * gets focus while the screen is on, switch to the full-screen lock activity so nobody is stuck.
     */
    private var verifying: Job? = null
    private fun verifyOverlay() {
        verifying?.cancel()
        verifying = scope.launch {
            val power = getSystemService(PowerManager::class.java)
            var misses = 0
            repeat(12) {
                delay(700)
                if (!overlay.showing || LockState.overlayBroken.value) return@launch
                if (!power.isInteractive) return@repeat
                if (overlay.hasFocus()) return@launch
                if (++misses >= 3) {
                    LockState.overlayBroken.value = true
                    overlay.hide()
                    LockState.startActivity(this@LockService)
                    return@launch
                }
            }
        }
    }

    /** Back on an app lock: leave the app for the home screen. */
    private fun leave() {
        val l = LockState.current.value
        if (l is Lock.App) {
            LockState.current.value = null
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else if (l == Lock.Test) {
            LockState.cancelTest()
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        if (pkg == packageName || !isActivity(pkg, cls)) return
        onForeground(pkg)
    }

    private fun onForeground(pkg: String) {
        LockState.foreground = pkg
        val isHome = pkg in homes
        if (isHome && prefs.relockOnHome) LockState.unlocked.clear()

        val cur = LockState.current.value
        if (cur is Lock.App && cur.pkg != pkg) LockState.current.value = null // they left it
        if (cur == Lock.Boot || cur == Lock.Test) {
            // Home was pressed on the backup lock activity: bring it back.
            if (cur == Lock.Boot && LockState.overlayBroken.value) LockState.startActivity(this)
            return
        }

        if (prefs.setupDone && prefs.enabled && prefs.appLock && prefs.hasSecret &&
            pkg in prefs.lockedApps && pkg !in LockState.unlocked && !LockState.inGrace()
        ) {
            LockState.current.value = Lock.App(pkg)
        }
    }

    /** Only real activities count; dialogs, keyboards and toasts also send window events. */
    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut("$pkg/$cls") {
        try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun homePackages(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private var tornDown = false
    private fun teardown() {
        if (tornDown || !::prefs.isInitialized) return
        tornDown = true
        LockState.serviceRunning.value = false
        runCatching { unregisterReceiver(screen) }
        overlay.hide()
        silencer.release()
    }
}
