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
import dev.alexaero.tvlock.Lock
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
        val ctx = LocalContext.current
        val l = lock
        if (l == null) {
            Backdrop {}
            return@TvLockTheme
        }
        key(l) {
            val noun = nounFor(Prefs(ctx).secretKind)
            when (l) {
                Lock.Boot -> LockScreen(
                    title = "Welcome back",
                    subtitle = "Enter your $noun to use the TV",
                    onBack = null,
                    onUnlocked = { LockState.unlock(ctx) },
                )
                is Lock.App -> {
                    val (label, icon) = remember(l.pkg) { appInfo(ctx, l.pkg) }
                    LockScreen(
                        title = "$label is locked",
                        subtitle = "Enter your $noun to open it.  Back leaves the app.",
                        icon = icon,
                        onBack = onLeave,
                        leaveLabel = "Go to home screen",
                        onUnlocked = { LockState.unlock(ctx) },
                    )
                }
                Lock.Test -> LockScreen(
                    title = "Try your $noun",
                    subtitle = "This is exactly what the lock looks like. Press Back to stop the test.",
                    test = true,
                    onBack = onLeave,
                    leaveLabel = "Stop test",
                    onUnlocked = { LockState.unlock(ctx) },
                )
            }
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
    onUnlocked: () -> Unit,
    onBack: (() -> Unit)?,
    icon: ImageBitmap? = null,
    test: Boolean = false,
    leaveLabel: String? = null,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val kind = remember { prefs.secretKind }
    val length = remember { prefs.pinLength }
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
            val ok = withContext(Dispatchers.Default) { Secret.check(prefs, s) }
            busy = false
            if (ok) {
                Secret.recordSuccess(prefs)
                onUnlocked()
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
    InitialFocus(first)
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

