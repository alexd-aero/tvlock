package dev.alexaero.tvlock

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A full-screen window above every app (and the home screen) that takes the remote's keys.
 * As an accessibility overlay it needs no extra permission, and Home/Back can't dismiss it.
 */
class Overlay(private val ctx: Context, private val type: Int) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var owner: Owner? = null

    val showing get() = view != null

    /** True once the window really gets the remote's keys. */
    fun hasFocus() = view?.hasWindowFocus() == true

    fun show(content: @Composable () -> Unit) {
        if (view != null) return
        val o = Owner().also { it.start() }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT, // the unlock greeting fades into the app behind
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "TV Lock"
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { wm.addView(v, lp) }.onFailure { o.stop(); return }
        v.requestFocus()
        // Drawn while the screen was off (TV asleep or on another input): take the remote's focus
        // again whenever the window gets it, or the keys go nowhere after waking up.
        v.viewTreeObserver.addOnWindowFocusChangeListener { has -> if (has && !v.hasFocus()) v.requestFocus() }
        view = v
        owner = o
    }

    fun hide() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        owner?.stop()
        view = null
        owner = null
    }

    private class Owner : SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ctl = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry get() = ctl.savedStateRegistry
        fun start() {
            ctl.performRestore(null)
            reg.currentState = Lifecycle.State.RESUMED
        }
        fun stop() {
            reg.currentState = Lifecycle.State.DESTROYED
        }
    }
}
