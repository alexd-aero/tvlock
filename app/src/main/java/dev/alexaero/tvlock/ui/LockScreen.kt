package dev.alexaero.tvlock.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.KeyEvent as AKey
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import dev.alexaero.tvlock.Lock
import dev.alexaero.tvlock.Profile
import dev.alexaero.tvlock.Profiles
import dev.alexaero.tvlock.LockState
import dev.alexaero.tvlock.Prefs
import dev.alexaero.tvlock.Secret
import dev.alexaero.tvlock.SecretKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** What the overlay / fallback activity draws for the current [LockState]. */
@Composable
fun LockSurface(onLeave: () -> Unit) {
    TvLockTheme {
        val lock by LockState.current.collectAsState()
        val hello by LockState.greeting.collectAsState()
        val ctx = LocalContext.current
        // The PIN screen melts away and the greeting card springs in; then it all fades into the TV.
        val phase: Any? = lock ?: hello
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(320, delayMillis = 90)) togetherWith
                    (fadeOut(tween(260)) + scaleOut(tween(300), targetScale = 0.94f))).using(SizeTransform(clip = false))
            },
            label = "lock",
        ) { ph ->
            when (ph) {
                is Lock -> LockFor(ph, onLeave)
                is Profile -> Greeting(ph) { LockState.greeting.value = null }
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun LockFor(l: Lock, onLeave: () -> Unit) {
    val ctx = LocalContext.current
    key(l) {
            val noun = nounFor(Profiles.padFor(Prefs(ctx)).first)
            when (l) {
                Lock.Boot -> LockScreen(
                    title = "Welcome back",
                    subtitle = "Enter your $noun to use the TV",
                    onBack = null,
                    onUnlocked = { LockState.unlock(ctx, it) },
                )
                is Lock.App -> {
                    val (label, icon) = remember(l.pkg) { appInfo(ctx, l.pkg) }
                    LockScreen(
                        title = "$label is locked",
                        subtitle = "Enter your $noun to open it.  Back leaves the app.",
                        icon = icon,
                        onBack = onLeave,
                        leaveLabel = "Go to home screen",
                        onUnlocked = { LockState.unlock(ctx, it) },
                    )
                }
                Lock.Test -> LockScreen(
                    title = "Try your $noun",
                    subtitle = "This is exactly what the lock looks like. Press Back to stop the test.",
                    test = true,
                    onBack = onLeave,
                    leaveLabel = "Stop test",
                    onUnlocked = { LockState.unlock(ctx, it) },
                )
            }
    }
}

/** "Hello, Name": their picture in a card in the middle, then everything fades into the TV. */
@Composable
fun Greeting(p: Profile, done: () -> Unit) {
    val enter = remember { Animatable(0f) }
    val out = remember { Animatable(0f) }
    LaunchedEffect(p) {
        enter.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessLow))
        delay(1400)
        out.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        done()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - out.value }) {
        Backdrop {}
        Column(
            Modifier.align(Alignment.Center)
                .graphicsLayer {
                    val sc = (0.82f + 0.18f * enter.value) * (1f + 0.06f * out.value)
                    scaleX = sc; scaleY = sc
                    alpha = enter.value.coerceIn(0f, 1f)
                    translationY = (1f - enter.value) * 40f
                }
                .shadow(40.dp, RoundedCornerShape(40.dp), ambientColor = C.caramel, spotColor = C.caramel)
                .clip(RoundedCornerShape(40.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A241D), Color(0xFF1C1814))))
                .border(1.dp, C.cream.copy(alpha = 0.16f), RoundedCornerShape(40.dp))
                .padding(horizontal = 72.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(p, 164.dp, glow = true)
            Spacer(Modifier.height(28.dp))
            Text("Hello, ${p.name}", fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = C.text)
            Spacer(Modifier.height(6.dp))
            Text("The TV is unlocked", style = MaterialTheme.typography.bodyLarge, color = C.dim)
        }
    }
}

fun nounFor(kind: SecretKind) = if (kind == SecretKind.PASSWORD) "password" else "PIN"

private fun appInfo(ctx: Context, pkg: String): Pair<String, ImageBitmap?> {
    val pm = ctx.packageManager
    return runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai).toBitmap(192, 192).asImageBitmap()
    }.getOrDefault(pkg to null)
}

/**
 * The PIN/password screen: checks against the saved secret, counts wrong tries and
 * enforces the cool-down.
 */
@Composable
fun LockScreen(
    title: String,
    subtitle: String,
    onUnlocked: (Profile) -> Unit,
    onBack: (() -> Unit)?,
    icon: ImageBitmap? = null,
    test: Boolean = false,
    leaveLabel: String? = null,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val changed by Profiles.changed.collectAsState()
    val pad = remember(changed) { Profiles.padFor(prefs) }
    val kind = pad.first
    val length = pad.second
    val entry = remember { Entry() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var lockoutUntil by remember { mutableLongStateOf(prefs.lockoutUntil) }
    val first = remember { FocusRequester() }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val lockedOut = now < lockoutUntil

    fun submit() {
        if (busy || System.currentTimeMillis() < lockoutUntil) return
        val s = entry.text
        if (s.isEmpty()) return
        if (kind == SecretKind.PIN && s.length != length || s.length < MIN_LEN) {
            message = if (kind == SecretKind.PIN) "Enter all $length digits" else "Enter at least $MIN_LEN characters"
            error = true
            return
        }
        busy = true
        scope.launch {
            val who = withContext(Dispatchers.Default) { Profiles.match(prefs, s) }
            busy = false
            if (who != null) {
                Secret.recordSuccess(prefs)
                onUnlocked(who)
            } else {
                Secret.recordFail(prefs)
                lockoutUntil = prefs.lockoutUntil
                error = true
                val left = 5 - prefs.fails
                message = when {
                    left > 0 -> "Wrong ${nounFor(kind)}. $left ${if (left == 1) "try" else "tries"} left before a pause."
                    else -> null
                }
                entry.fail()
            }
        }
    }

    LaunchedEffect(entry.text) {
        if (entry.text.isNotEmpty()) error = false
        if (kind == SecretKind.PIN && entry.text.length == length) {
            delay(140)
            submit()
        }
    }
    // Again each time the window gets focus (for a lock that was drawn while the screen was off).
    InitialFocus(first, androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused)
    if (test) {
        // Never leave someone stuck in a test: it cancels itself after a minute without input.
        LaunchedEffect(entry.text, entry.shakes) {
            delay(60_000)
            onBack?.invoke()
        }
    }

    Backdrop(
        Modifier.onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent.keyCode
            if (k == AKey.KEYCODE_BACK || k == AKey.KEYCODE_ESCAPE) {
                if (e.type == KeyEventType.KeyUp) onBack?.invoke()
                return@onPreviewKeyEvent true
            }
            if (lockedOut) return@onPreviewKeyEvent k in AKey.KEYCODE_0..AKey.KEYCODE_9
            typeKey(e, kind, entry, ::submit)
        }
    ) {
        TopBar(test)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 32.dp)) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)))
                } else {
                    Badge(Icons.Rounded.Lock, 84.dp)
                }
                Spacer(Modifier.height(24.dp))
                Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = C.dim)
                Spacer(Modifier.height(30.dp))
                EntryDisplay(kind, length, entry, error)
                Spacer(Modifier.height(18.dp))
                val msg = when {
                    lockedOut -> {
                        val s = ((lockoutUntil - now) / 1000 + 1).toInt()
                        "Too many wrong tries. Try again in ${s / 60}:${"%02d".format(s % 60)}"
                    }
                    else -> message
                }
                Text(
                    msg ?: if (kind == SecretKind.PASSWORD) "A keyboard plugged into the TV works too"
                    else "The number buttons on your remote work too",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (msg != null) C.error else C.dim.copy(alpha = 0.7f),
                )
                if (leaveLabel != null && onBack != null) {
                    Spacer(Modifier.height(22.dp))
                    TvButton(leaveLabel, onBack, icon = if (test) Icons.Rounded.Close else Icons.Rounded.Home)
                }
            }
            Box(
                Modifier.fillMaxHeight().width(if (kind == SecretKind.PASSWORD) 470.dp else 300.dp),
                contentAlignment = Alignment.Center,
            ) {
                EntryPad(kind, entry, ::submit, first)
            }
        }
    }
}

@Composable
private fun TopBar(test: Boolean) {
    val ctx = LocalContext.current
    val now by produceState(Date()) {
        while (true) { value = Date(); delay(5_000) }
    }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp), tint = C.dim)
            Spacer(Modifier.width(8.dp))
            Text("TV Lock", style = MaterialTheme.typography.labelLarge, color = C.dim)
            if (test) {
                Spacer(Modifier.width(14.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(C.warn.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Science, null, Modifier.size(16.dp), tint = C.warn)
                    Spacer(Modifier.width(6.dp))
                    Text("TEST", color = C.warn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(DateFormat.getTimeFormat(ctx).format(now), fontSize = 34.sp, fontWeight = FontWeight.Light)
            Text(
                DateFormat.format("EEEE, MMMM d", now).toString(),
                style = MaterialTheme.typography.bodyMedium, color = C.dim, textAlign = TextAlign.End,
            )
        }
    }
}

