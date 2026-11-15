package dev.alexaero.tvlock.ui

import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.alexaero.tvlock.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { WELCOME, PERMISSIONS, MODE, APPS, KIND, CREATE, CONFIRM, TEST, HOME, OPTIONS }

@Composable
fun TvLockApp(authed: MutableState<Boolean>, finish: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val stack = remember { mutableStateListOf(if (prefs.setupDone) Screen.HOME else Screen.WELCOME) }
    var setup by rememberSaveable { mutableStateOf(!prefs.setupDone) }
    var newKind by remember { mutableStateOf(SecretKind.PIN) }
    var newLen by remember { mutableIntStateOf(4) }
    var firstTry by remember { mutableStateOf("") }
    var createMsg by remember { mutableStateOf<String?>(null) }

    if (prefs.setupDone && !authed.value) {
        LockScreen(
            title = "TV Lock settings",
            subtitle = "Enter your ${nounFor(prefs.secretKind)} to change the lock",
            onUnlocked = { authed.value = true },
            onBack = finish,
        )
        return
    }

    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
    fun home() { stack.clear(); stack.add(Screen.HOME) }
    BackHandler { back() }

    Backdrop {
        Box(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
            when (stack.last()) {
                Screen.WELCOME -> Welcome { go(Screen.PERMISSIONS) }
                Screen.PERMISSIONS -> Permissions(setup) { if (setup) go(Screen.MODE) else back() }
                Screen.MODE -> Mode(prefs, setup) {
                    if (!setup) prefs.markBootUnlocked()
                    when {
                        prefs.appLock -> go(Screen.APPS)
                        setup -> go(Screen.KIND)
                        else -> back()
                    }
                }
                Screen.APPS -> AppPicker(prefs, setup) { if (setup) go(Screen.KIND) else home() }
                Screen.KIND -> Kind(setup) { k, n -> newKind = k; newLen = n; createMsg = null; go(Screen.CREATE) }
                Screen.CREATE -> PadScreen(
                    step = if (setup) "Step 4 of 5" else null,
                    title = when (newKind) {
                        SecretKind.PIN -> "Create a $newLen-digit PIN"
                        SecretKind.PIN_ANY -> "Create a PIN"
                        SecretKind.PASSWORD -> "Create a password"
                    },
                    sub = createMsg ?: when (newKind) {
                        SecretKind.PIN -> "Pick $newLen numbers you'll remember."
                        SecretKind.PIN_ANY -> "Use $MIN_LEN to 16 digits, then press ✓."
                        SecretKind.PASSWORD -> "At least $MIN_LEN characters, then press ✓."
                    },
                    error = createMsg != null,
                    kind = newKind, length = newLen, key = "create$createMsg",
                ) { s -> firstTry = s; go(Screen.CONFIRM) }
                Screen.CONFIRM -> {
                    val scope = rememberCoroutineScope()
                    PadScreen(
                        step = if (setup) "Step 4 of 5" else null,
                        title = "Enter it again",
                        sub = "Just to make sure.",
                        kind = newKind, length = newLen, key = "confirm",
                    ) { s ->
                        if (s != firstTry) {
                            createMsg = "Those didn't match. Let's start over."
                            back()
                            return@PadScreen
                        }
                        scope.launch {
                            withContext(Dispatchers.Default) { Secret.set(prefs, s, newKind, newLen) }
                            authed.value = true
                            if (setup) {
                                prefs.enabled = true
                                prefs.markBootUnlocked()
                                prefs.setupDone = true
                                stack.clear(); stack.add(Screen.TEST)
                            } else home()
                        }
                    }
                }
                Screen.TEST -> TestScreen(prefs, setup) { setup = false; home() }
                Screen.HOME -> Home(prefs) { go(it) }
                Screen.OPTIONS -> Options(prefs)
            }
        }
    }
}

// ---------------------------------------------------------------- welcome

@Composable
private fun Welcome(next: () -> Unit) {
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Badge(Icons.Rounded.Lock, 76.dp)
            Spacer(Modifier.height(24.dp))
            Text("TV Lock", style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp))
            Spacer(Modifier.height(10.dp))
            Text(
                "A PIN for your TV. Show it when the TV turns on, put it on the apps you pick, or both.",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp), color = C.dim,
                modifier = Modifier.widthIn(max = 440.dp),
            )
            Spacer(Modifier.height(30.dp))
            TvButton("Get started", next, icon = Icons.AutoMirrored.Rounded.ArrowForward, primary = true, focusRequester = fr)
        }
        Column(Modifier.width(360.dp), verticalArrangement = Gap) {
            Feature(Icons.Rounded.PowerSettingsNew, "Lock when the TV starts", "The PIN screen comes up instead of the home screen.")
            Feature(Icons.Rounded.Apps, "Lock any app", "YouTube, Netflix, Settings, the Play Store…")
            Feature(Icons.Rounded.Dialpad, "Your kind of PIN", "4 digits by default, any length, or a password.")
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.cream.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = C.latte)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = C.dim)
        }
    }
}

// ---------------------------------------------------------------- permissions

@Composable
private fun Permissions(setup: Boolean, next: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val running by LockState.serviceRunning.collectAsState()
    val a11y = remember(tick, running) { Perms.accessibilityOn(ctx) }
    val overlay = remember(tick) { Perms.overlayOn(ctx) }
    var noA11yPage by remember { mutableStateOf(false) }
    var noOverlayPage by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val cont = remember { FocusRequester() }
    InitialFocus(if (a11y && overlay) cont else first, a11y to overlay)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                ScreenTitle(
                    "Allow TV Lock", "Two switches in the TV's Settings let the lock cover the screen.",
                    if (setup) "Step 1 of 5" else null,
                )
            }
            TvButton(
                if (a11y) (if (setup) "Continue" else "Done") else "Skip for now", next,
                icon = Icons.AutoMirrored.Rounded.ArrowForward, primary = a11y, focusRequester = cont,
            )
        }
        Spacer(Modifier.height(24.dp))
        PermRow(
            Icons.Rounded.Accessibility, "Accessibility", "Required",
            "Settings → Accessibility → TV Lock → turn it on. This is what puts the PIN screen over the home screen and apps.",
            a11y, if (noA11yPage) "This TV has no Accessibility page. Use the adb commands below." else null, first,
        ) { noA11yPage = !Perms.openAccessibility(ctx) }
        Spacer(Modifier.height(14.dp))
        PermRow(
            Icons.Rounded.Layers, "Display over other apps", "Recommended",
            "Lets TV Lock show the lock right as the TV starts, even before Accessibility is ready.",
            overlay, if (noOverlayPage) "This TV hides that page. Use the adb command below, or skip it." else null, null,
        ) { noOverlayPage = !Perms.openOverlay(ctx) }
        Spacer(Modifier.height(22.dp))
        Text("Switch greyed out or missing?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Android 13 and newer block Accessibility for apps installed from a file. Open App info, press the ⋮ menu " +
                "(or scroll down) and pick \"Allow restricted settings\", then try again.",
            style = MaterialTheme.typography.bodyMedium, color = C.dim,
        )
        Spacer(Modifier.height(12.dp))
        TvButton("Open App info", { Perms.openAppInfo(ctx) }, icon = Icons.Rounded.Info)
        Spacer(Modifier.height(18.dp))
        Text("No luck? From a computer on the same network:", style = MaterialTheme.typography.bodyMedium, color = C.dim)
        Spacer(Modifier.height(8.dp))
        Code("${Perms.ADB_RESTRICTED}\n${Perms.ADB_A11Y}\n${Perms.ADB_OVERLAY}")
        Spacer(Modifier.height(6.dp))
        Text(
            "The first Accessibility command replaces any other services on the list.",
            style = MaterialTheme.typography.bodyMedium, color = C.dim.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun PermRow(
    icon: ImageVector, name: String, tag: String, desc: String, on: Boolean, problem: String?,
    fr: FocusRequester?, open: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(icon, 52.dp, if (on) C.accent else androidx.compose.ui.graphics.SolidColor(C.dim.copy(alpha = 0.5f)))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Chip(if (on) "Allowed" else tag, if (on) C.good else if (tag == "Required") C.warn else C.dim)
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = C.dim)
            if (problem != null) {
                Spacer(Modifier.height(4.dp))
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = C.error)
            }
        }
        Spacer(Modifier.width(18.dp))
        if (on) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(34.dp), tint = C.good)
        else TvButton("Open settings", open, primary = true, focusRequester = fr)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Code(text: String) {
    Text(
        text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = C.latte,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x66000000)).padding(16.dp),
    )
}

// ---------------------------------------------------------------- what to lock

@Composable
private fun Mode(prefs: Prefs, setup: Boolean, next: () -> Unit) {
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    val current = when {
        prefs.bootLock && prefs.appLock -> 2
        prefs.appLock -> 1
        else -> 0
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("What should TV Lock lock?", "You can change this later.", if (setup) "Step 2 of 5" else null)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val opts = listOf(
                Triple(Icons.Rounded.PowerSettingsNew, "When the TV starts", "The PIN screen instead of the home screen when the TV turns on or wakes up."),
                Triple(Icons.Rounded.Apps, "Specific apps", "Only the apps you choose ask for the PIN."),
                Triple(Icons.Rounded.Shield, "Both", "The TV start and the apps you choose."),
            )
            opts.forEachIndexed { i, (icon, title, desc) ->
                TvSurface(
                    {
                        prefs.bootLock = i != 1
                        prefs.appLock = i != 0
                        next()
                    },
                    Modifier.weight(1f).height(250.dp), RoundedCornerShape(24.dp),
                    selected = !setup && i == current, scale = 1.05f,
                    focusRequester = if (i == (if (setup) 0 else current)) fr else null,
                ) { focused ->
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Badge(icon, 56.dp)
                        Spacer(Modifier.weight(1f))
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(desc, style = MaterialTheme.typography.bodyMedium, color = if (focused) C.focusText.copy(alpha = .7f) else C.dim)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- app picker

@Composable
private fun AppPicker(prefs: Prefs, setup: Boolean, next: () -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<Pair<AppEntry, ImageBitmap?>>?>(null) {
        value = withContext(Dispatchers.IO) {
            Apps.list(ctx).map { a ->
                a to a.art?.let { d ->
                    runCatching { (if (a.wide) d.toBitmap(320, 180) else d.toBitmap(128, 128)).asImageBitmap() }.getOrNull()
                }
            }
        }
    }
    var chosen by remember { mutableStateOf(prefs.lockedApps) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr, apps != null)

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                ScreenTitle(
                    "Choose apps to lock",
                    "Tip: lock Settings and the Play Store too, so nobody can switch TV Lock off.",
                    if (setup) "Step 3 of 5" else null,
                )
            }
            TvButton(
                if (chosen.isEmpty()) "Skip" else "Lock ${chosen.size} app${if (chosen.size == 1) "" else "s"}",
                { prefs.lockedApps = chosen; next() },
                icon = Icons.AutoMirrored.Rounded.ArrowForward, primary = chosen.isNotEmpty(),
            )
        }
        Spacer(Modifier.height(20.dp))
        val list = apps
        if (list == null) {
            Text("Finding apps…", color = C.dim)
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            itemsIndexed(list, key = { _, it -> it.first.pkg }) { i, (app, art) ->
                val on = app.pkg in chosen
                TvSurface(
                    { chosen = if (on) chosen - app.pkg else chosen + app.pkg },
                    Modifier.fillMaxWidth().aspectRatio(1.25f), RoundedCornerShape(16.dp),
                    selected = on, scale = 1.08f, focusRequester = if (i == 0) fr else null,
                ) { focused ->
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxWidth().weight(1f).background(Color(0x22000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (art != null) {
                                if (app.wide) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Image(art, null, Modifier.size(48.dp))
                            } else Icon(Icons.Rounded.Android, null, Modifier.size(40.dp))
                            if (on) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(C.cream),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = C.focusText) }
                            }
                        }
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (app.suggested && !on) Icon(Icons.Rounded.Star, "Suggested", Modifier.size(14.dp),
                                tint = if (focused) C.focusText else C.caramel)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- PIN kind + entry

@Composable
private fun Kind(setup: Boolean, pick: (SecretKind, Int) -> Unit) {
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Choose your lock", "A 4-digit PIN is the default and the quickest with a remote.", if (setup) "Step 4 of 5" else null)
        Spacer(Modifier.height(26.dp))
        val opts = listOf(
            listOf(Icons.Rounded.Pin, "4-digit PIN", "Default", SecretKind.PIN, 4),
            listOf(Icons.Rounded.Dialpad, "6-digit PIN", "", SecretKind.PIN, 6),
            listOf(Icons.Rounded.MoreHoriz, "PIN of any length", "4 to 16 digits", SecretKind.PIN_ANY, 0),
            listOf(Icons.Rounded.Password, "Password", "Letters, numbers and symbols", SecretKind.PASSWORD, 0),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            opts.forEachIndexed { i, o ->
                TvSurface(
                    { pick(o[3] as SecretKind, o[4] as Int) },
                    Modifier.weight(1f).height(200.dp), RoundedCornerShape(22.dp), scale = 1.06f,
                    focusRequester = if (i == 0) fr else null,
                ) { focused ->
                    Column(Modifier.fillMaxSize().padding(22.dp)) {
                        Badge(o[0] as ImageVector, 50.dp)
                        Spacer(Modifier.weight(1f))
                        Text(o[1] as String, style = MaterialTheme.typography.titleLarge)
                        val d = o[2] as String
                        if (d.isNotEmpty()) Text(d, style = MaterialTheme.typography.bodyMedium,
                            color = if (focused) C.focusText.copy(alpha = .7f) else if (i == 0) C.caramel else C.dim)
                    }
                }
            }
        }
    }
}

/** Entry screen used while creating / confirming a PIN or password. */
@Composable
private fun PadScreen(
    step: String?, title: String, sub: String, kind: SecretKind, length: Int, key: String,
    error: Boolean = false, onDone: (String) -> Unit,
) {
    androidx.compose.runtime.key(key) {
        val entry = remember { Entry() }
        var msg by remember { mutableStateOf<String?>(null) }
        val first = remember { FocusRequester() }
        InitialFocus(first)
        fun submit() {
            val s = entry.text
            val ok = if (kind == SecretKind.PIN) s.length == length else s.length >= MIN_LEN
            if (!ok) {
                msg = if (kind == SecretKind.PIN) "Enter all $length digits" else "Use at least $MIN_LEN characters"
                entry.shakes++
                return
            }
            onDone(s)
        }
        LaunchedEffect(entry.text) {
            if (entry.text.isNotEmpty()) msg = null
            if (kind == SecretKind.PIN && entry.text.length == length) {
                kotlinx.coroutines.delay(140)
                submit()
            }
        }
        Row(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent.keyCode
                if (k == AKey.KEYCODE_BACK) return@onPreviewKeyEvent false
                typeKey(e, kind, entry, ::submit)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 32.dp)) {
                ScreenTitle(title, null, step)
                Spacer(Modifier.height(8.dp))
                Text(sub, style = MaterialTheme.typography.bodyLarge, color = if (error) C.error else C.dim)
                Spacer(Modifier.height(30.dp))
                EntryDisplay(kind, length, entry, msg != null)
                Spacer(Modifier.height(16.dp))
                Text(msg ?: "", style = MaterialTheme.typography.bodyMedium, color = C.error)
            }
            Box(Modifier.width(if (kind == SecretKind.PASSWORD) 470.dp else 300.dp), contentAlignment = Alignment.Center) {
                EntryPad(kind, entry, ::submit, first)
            }
        }
    }
}

// ---------------------------------------------------------------- test

@Composable
private fun TestScreen(prefs: Prefs, setup: Boolean, done: () -> Unit) {
    val ctx = LocalContext.current
    val result by LockState.testResult.collectAsState()
    val running by LockState.serviceRunning.collectAsState()
    val fr = remember { FocusRequester() }
    val finish = remember { FocusRequester() }
    LaunchedEffect(Unit) { LockState.testResult.value = null }
    InitialFocus(if (result == true) finish else fr, result)
    val noun = nounFor(prefs.secretKind)
    val firstApp = prefs.lockedApps.firstOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                ScreenTitle(
                    if (setup) "You're all set. Try it out" else "Test the lock",
                    "Make sure your $noun works before you rely on it.",
                    if (setup) "Step 5 of 5" else null,
                )
            }
            TvButton(if (setup) "Finish" else "Done", done, icon = Icons.Rounded.Check, primary = result == true, focusRequester = finish)
        }
        Spacer(Modifier.height(26.dp))
        if (!running) {
            Text(
                "Accessibility for TV Lock is off, so the test uses the backup lock screen and the real lock won't run yet.",
                color = C.warn, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TestCard(
                Icons.Rounded.Lock, "Try the lock screen",
                when (result) {
                    true -> "It works: your $noun unlocked it."
                    false -> "Test stopped. Try it again."
                    null -> "Opens the real lock. Enter your $noun, or press Back to stop."
                },
                result, Modifier.weight(1f), fr,
            ) {
                LockState.testResult.value = null
                LockState.grace(1)
                LockState.show(ctx, Lock.Test)
            }
            if (prefs.appLock && firstApp != null) {
                val label = remember(firstApp) { Apps.label(ctx, firstApp) }
                TestCard(
                    Icons.Rounded.Apps, "Open $label", "It's locked, so the PIN screen should come up first.",
                    null, Modifier.weight(1f), null,
                ) {
                    LockState.graceUntil = 0
                    LockState.unlocked.remove(firstApp)
                    Apps.launch(ctx, firstApp)
                }
            }
        }
        if (prefs.bootLock) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(22.dp), tint = C.latte)
                Spacer(Modifier.width(10.dp))
                Text(
                    "To see the TV start lock, turn the TV off and on" +
                        (if (prefs.lockOnWake) " (sleep and wake counts too)." else " (a full restart)."),
                    style = MaterialTheme.typography.bodyMedium, color = C.dim,
                )
            }
        }
    }
}

@Composable
private fun TestCard(
    icon: ImageVector, title: String, desc: String, ok: Boolean?, modifier: Modifier,
    fr: FocusRequester?, onClick: () -> Unit,
) {
    TvSurface(onClick, modifier.height(190.dp), RoundedCornerShape(22.dp), scale = 1.05f, focusRequester = fr) { focused ->
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(icon, 48.dp)
                Spacer(Modifier.weight(1f))
                if (ok == true) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(32.dp), tint = if (focused) C.focusText else C.good)
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                desc, style = MaterialTheme.typography.bodyMedium,
                color = if (focused) C.focusText.copy(alpha = .7f) else if (ok == true) C.good else C.dim,
            )
        }
    }
}

// ---------------------------------------------------------------- dashboard

@Composable
private fun Home(prefs: Prefs, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val running by LockState.serviceRunning.collectAsState()
    val a11y = remember(tick, running) { Perms.accessibilityOn(ctx) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    val summary = remember(tick, enabled) {
        val parts = buildList {
            if (prefs.bootLock) add("when the TV starts")
            if (prefs.appLock) add("${prefs.lockedApps.size} app${if (prefs.lockedApps.size == 1) "" else "s"}")
        }
        if (!enabled) "Off: nothing is locked" else "Locks " + parts.joinToString(" and ")
    }
    val noun = nounFor(prefs.secretKind)
    val kindText = when (prefs.secretKind) {
        SecretKind.PIN -> "${prefs.pinLength}-digit PIN"
        SecretKind.PIN_ANY -> "PIN, any length"
        SecretKind.PASSWORD -> "Password"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(Icons.Rounded.Lock, 64.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("TV Lock", style = MaterialTheme.typography.displaySmall)
                Text(summary, style = MaterialTheme.typography.bodyLarge, color = if (enabled) C.latte else C.dim)
            }
            TvToggle(
                if (enabled) "Lock is on" else "Lock is off", "Press OK to switch",
                enabled, { enabled = it; prefs.enabled = it; if (it) prefs.markBootUnlocked() else LockState.unlocked.clear() },
                Modifier.width(280.dp), fr,
            )
        }
        if (!a11y) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.warn.copy(alpha = 0.12f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Warning, null, tint = C.warn)
                Spacer(Modifier.width(12.dp))
                Text(
                    "The lock isn't running: Accessibility for TV Lock is off.",
                    Modifier.weight(1f), color = C.warn, style = MaterialTheme.typography.titleMedium,
                )
                TvButton("Fix it", { open(Screen.PERMISSIONS) }, primary = true)
            }
        }
        Spacer(Modifier.height(26.dp))
        val cards = buildList {
            add(Triple(Icons.Rounded.Tune, "What's locked", summary.removePrefix("Locks ").replaceFirstChar { it.uppercase() }) to Screen.MODE)
            if (prefs.appLock) add(Triple(Icons.Rounded.Apps, "Locked apps", "${prefs.lockedApps.size} chosen") to Screen.APPS)
            add(Triple(Icons.Rounded.Pin, "Change $noun", kindText) to Screen.KIND)
            add(Triple(Icons.Rounded.Science, "Test the lock", "Try it safely") to Screen.TEST)
            add(Triple(Icons.Rounded.Settings, "Options", "Sleep, relock") to Screen.OPTIONS)
            add(Triple(Icons.Rounded.Accessibility, "Permissions", if (a11y) "All good" else "Needs attention") to Screen.PERMISSIONS)
        }
        cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { (c, s) ->
                    TvSurface({ open(s) }, Modifier.weight(1f).height(132.dp), RoundedCornerShape(22.dp), scale = 1.05f) { focused ->
                        Row(Modifier.fillMaxSize().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(c.first, 50.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(c.second, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    c.third, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    color = if (focused) C.focusText.copy(alpha = .7f) else C.dim,
                                )
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun Options(prefs: Prefs) {
    val ctx = LocalContext.current
    var wake by remember { mutableStateOf(prefs.lockOnWake) }
    var home by remember { mutableStateOf(prefs.relockOnHome) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Gap) {
        ScreenTitle("Options")
        Spacer(Modifier.height(10.dp))
        TvToggle(
            "Lock when the TV wakes up",
            "Most TVs sleep instead of turning off, so this makes the start lock show every time you turn it on.",
            wake, { wake = it; prefs.lockOnWake = it }, Modifier.fillMaxWidth(), fr,
        )
        TvToggle(
            "Relock apps when you go home",
            if (home) "A locked app asks again every time you open it from the home screen."
            else "Once unlocked, apps stay open until the TV sleeps.",
            home, { home = it; prefs.relockOnHome = it }, Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "TV Lock ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""}",
            style = MaterialTheme.typography.bodyMedium, color = C.dim,
        )
    }
}

