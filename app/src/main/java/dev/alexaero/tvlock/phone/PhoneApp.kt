package dev.alexaero.tvlock.phone

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.alexaero.tvlock.ui.Backdrop
import dev.alexaero.tvlock.ui.Badge
import dev.alexaero.tvlock.ui.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private sealed interface P {
    data object Welcome : P
    data object Scan : P
    data class Tv(val tv: FoundTv) : P
    data class Linking(val host: String, val code: String) : P
    data class Auto(val tv: FoundTv) : P
    data object Mode : P
    data object Apps : P
    data object Pin : P
    data object Done : P
    data object Remote : P
    /** Joined a TV that's already set up: who is using this phone? */
    data object Who : P
    /** Create a profile for this phone's person (TV already set up). */
    data object NewMe : P
    /** "How do you want to appear?" for an existing profile. */
    data class Me(val profileId: String) : P
}

/** The phone side of TV Lock: finds the TV, sets it up, then becomes the remote. */
@Composable
fun PhoneApp(startLink: Uri?) {
    val ctx = LocalContext.current
    val store = remember { PhoneStore(ctx) }
    val scanner = remember { Scanner(ctx) }
    val stack = remember { mutableStateListOf<P>(if (store.hasTv) P.Remote else P.Welcome) }
    fun go(p: P) = stack.add(p)
    fun reset(p: P) { stack.clear(); stack.add(p) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else (ctx as? android.app.Activity)?.finish() }
    BackHandler(stack.size > 1) { back() }

    // tvlock://setup?host=…&code=… from the TV's download page.
    val incoming by PhoneLinks.incoming.collectAsState()
    LaunchedEffect(startLink, incoming) {
        val u = incoming ?: startLink ?: return@LaunchedEffect
        PhoneLinks.incoming.value = null
        val host = u.getQueryParameter("host")
        if (u.host == "setup" && !host.isNullOrBlank()) reset(P.Linking(host, u.getQueryParameter("code").orEmpty()))
    }
    val api = remember(stack.lastOrNull()) { store.host?.let { TvApi(it, store.token) } }

    when (val s = stack.last()) {
        P.Welcome -> Welcome { go(P.Scan) }
        P.Scan -> ScanScreen(scanner, ::back) { go(P.Tv(it)) }
        is P.Tv -> TvScreen(s.tv, ::back, auto = { go(P.Auto(s.tv)) }) { token, name, profileId ->
            store.save(s.tv.host, token, name)
            reset(when {
                profileId != null -> P.Me(profileId)
                s.tv.setupDone == true -> P.Who
                else -> P.Mode
            })
        }
        is P.Linking -> Linking(s, store, onFail = { reset(P.Welcome) }) { setupDone ->
            reset(if (setupDone) P.Who else P.Mode)
        }
        is P.Auto -> AutoScreen(s.tv, store, ::back) { setupDone -> reset(if (setupDone) P.Who else P.Mode) }
        P.Who -> api?.let { WhoScreen(it, me = { id -> reset(P.Me(id)) }, create = { go(P.NewMe) }) { reset(P.Remote) } }
        P.NewMe -> api?.let { PinScreen(it, ::back, setupTv = false) { reset(P.Remote) } }
        is P.Me -> api?.let { MeScreen(it, s.profileId) { reset(P.Remote) } }
        P.Mode -> api?.let { ModeScreen(it, auto = { tv -> go(P.Auto(tv)) }) { appLock -> go(if (appLock) P.Apps else P.Pin) } }
        P.Apps -> api?.let { AppsScreen(it, ::back) { go(P.Pin) } }
        P.Pin -> api?.let { PinScreen(it, ::back, setupTv = true) { reset(P.Done) } }
        P.Done -> DoneScreen(store.name) { reset(P.Remote) }
        P.Remote -> RemoteScreen(store, forget = { store.clear(); reset(P.Welcome) }, find = { go(P.Scan) })
    }
}

// ------------------------------------------------------------------------------------------ parts

@Composable
private fun Page(
    step: String? = null,
    title: String,
    sub: String? = null,
    onBack: (() -> Unit)? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Backdrop {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(10.dp))
                if (onBack != null) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(C.card).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = C.text) }
                    Spacer(Modifier.height(10.dp))
                } else Spacer(Modifier.height(20.dp))
                if (step != null) Text(step.uppercase(), color = C.caramel, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(6.dp))
                Text(title, color = C.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp, letterSpacing = (-0.6).sp)
                if (sub != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(sub, color = C.dim, fontSize = 16.sp, lineHeight = 22.sp)
                }
                Spacer(Modifier.height(18.dp))
                content()
                Spacer(Modifier.height(24.dp))
            }
            if (bottom != null) Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), content = bottom)
        }
    }
}

@Composable
private fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    Box(
        modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = if (enabled) 1f else 0.5f }
            .clip(RoundedCornerShape(28.dp))
            .background(if (primary) C.cream else C.card)
            .border(1.dp, if (primary) Color.Transparent else C.cardLine, RoundedCornerShape(28.dp))
            .clickable(src, null, enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (primary) C.focusText else C.text) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = LocalContentColor.current)
                else if (icon != null) Icon(icon, null, Modifier.size(22.dp))
                if (busy || icon != null) Spacer(Modifier.width(10.dp))
                Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PCard(modifier: Modifier = Modifier, highlight: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 7.dp).clip(RoundedCornerShape(24.dp))
            .background(if (highlight) C.cream.copy(alpha = 0.12f) else C.card)
            .border(1.dp, if (highlight) C.latte.copy(alpha = 0.5f) else C.cardLine, RoundedCornerShape(24.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun Tag(text: String, color: Color = C.caramel) {
    Text(
        text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, keyboard: KeyboardType, secret: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x40000000))
            .border(1.dp, C.cardLine, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = C.dim, fontSize = 17.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = C.text, fontSize = 18.sp, letterSpacing = if (secret) 4.sp else 0.sp),
            cursorBrush = SolidColor(C.caramel),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Note(icon: ImageVector, text: String, color: Color = C.warn) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp).clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.10f)).padding(16.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = C.dim, fontSize = 14.sp, lineHeight = 20.sp)
    }
}


// ------------------------------------------------------------------------------------------ screens

@Composable
private fun Welcome(find: () -> Unit) {
    Page(
        title = "TV Lock",
        sub = "Set up the lock on your TV from this phone, then unlock and control the TV any time.",
        bottom = { PButton("Find my TV", find, icon = Icons.Rounded.Search, primary = true) },
    ) {
        Spacer(Modifier.height(6.dp))
        Badge(Icons.Rounded.Lock, 84.dp)
        Spacer(Modifier.height(18.dp))
        listOf(
            Icons.Rounded.Wifi to "Finds the TV on your home network, on any Wi-Fi in the house.",
            Icons.Rounded.AutoAwesome to "Sets up every permission on the TV for you.",
            Icons.Rounded.SettingsRemote to "Unlock, remote control, apps and settings in one place.",
        ).forEach { (i, t) ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(C.cream.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(i, null, tint = C.latte, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(t, color = C.text, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Note(Icons.Rounded.Home, "Works at home only: your phone talks to the TV over your home network. Nothing is opened on your router.")
    }
}

@Composable
private fun ScanScreen(scanner: Scanner, back: () -> Unit, pick: (FoundTv) -> Unit) {
    val found by scanner.found.collectAsState()
    val running by scanner.running.collectAsState()
    val progress by scanner.progress.collectAsState()
    val status by scanner.status.collectAsState()
    var deep by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf("") }
    var manualBusy by remember { mutableStateOf(false) }
    var manualErr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (found.isEmpty()) job = scope.launch { scanner.scan(scanner.quickSubnets()) } }

    Page(
        step = "Step 1", title = if (found.isEmpty()) "Finding your TV" else "Pick your TV",
        sub = "Looking on your home network, including other Wi-Fi networks in the house.", onBack = back,
    ) {
        if (running) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.cream.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(C.accent))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                (if (deep) "Searching every network… " else "Searching… ") + status + " · ${(progress * 100).toInt()}%",
                color = C.dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        found.forEach { tv ->
            PCard(highlight = tv.tvLock, onClick = { job?.cancel(); pick(tv) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(Icons.Rounded.Tv, 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tv.title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tv.ip, color = C.dim, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (tv.tvLock) Tag("TV Lock", C.latte)
                            if (tv.adb) Tag("Auto setup ready", C.good)
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = C.dim)
                }
            }
        }
        if (!running && found.isEmpty()) {
            Note(Icons.Rounded.Info, "No TV found yet. Make sure TV Lock is open on the TV, or that network debugging is on.", C.dim)
        }
        Spacer(Modifier.height(8.dp))
        if (running) PButton("Stop", { job?.cancel() }, icon = Icons.Rounded.Close)
        else if (!deep) {
            PButton("Search every network", {
                deep = true
                job = scope.launch { scanner.scan(scanner.everySubnet()) }
            }, icon = Icons.Rounded.TravelExplore)
            Text(
                "Checks all home network ranges (192.168.x.x, 10.0.x.x, 172.16.x.x). Takes a few minutes.",
                color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Know the TV's address?", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("TV Lock shows it on the TV, for example 192.168.1.174.", color = C.dim, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Field(manual, { manual = it.trim(); manualErr = null }, "192.168.1.174", KeyboardType.Uri)
        manualErr?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(4.dp))
        PButton("Connect", {
            manualBusy = true
            scope.launch {
                val ip = manual.substringBefore(':')
                val tv = withContext(Dispatchers.IO) { scanner.probe(ip) }
                manualBusy = false
                if (tv != null) pick(tv) else manualErr = "Nothing answered at $ip. Is TV Lock open on the TV?"
            }
        }, enabled = manual.count { it == '.' } == 3, busy = manualBusy)
    }
}

@Composable
private fun TvScreen(tv: FoundTv, back: () -> Unit, auto: () -> Unit, paired: (String, String, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinBusy by remember { mutableStateOf(false) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    val setUp = tv.setupDone == true
    if (setUp) Page(
        step = "Join", title = tv.title,
        sub = "TV Lock is already set up on this TV, so this phone just joins it.", onBack = back,
    ) {
        PCard(highlight = true) {
            Text("Enter your PIN", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("The PIN you use on this TV. It also tells the TV who you are.", color = C.dim, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Field(pin, { pin = it.take(32); pinErr = null }, "Your PIN", KeyboardType.NumberPassword, secret = true)
            pinErr?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            PButton("Join", {
                pinBusy = true
                scope.launch {
                    try {
                        val j = withContext(Dispatchers.IO) {
                            TvApi(tv.host).post("/api/pair-pin", JSONObject().put("pin", pin).put("name", PhoneStore.phoneName(ctx)))
                        }
                        paired(j.getString("token"), tv.title, j.getJSONObject("profile").getString("id"))
                    } catch (e: Exception) {
                        pinErr = e.message ?: "Couldn't connect"
                    }
                    pinBusy = false
                }
            }, icon = Icons.Rounded.Login, primary = true, busy = pinBusy, enabled = pin.length >= 4, modifier = Modifier.padding(top = 10.dp))
        }
        PCard {
            Text("New here?", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("No PIN yet? On the TV, open TV Lock → Phone and type the 6-digit code here. Then you'll make your own PIN.",
                color = C.dim, fontSize = 15.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(12.dp))
            Field(code, { code = it.filter(Char::isDigit).take(6); err = null }, "123 456", KeyboardType.NumberPassword)
            err?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            PButton("Connect", {
                busy = true
                scope.launch {
                    try {
                        val t = withContext(Dispatchers.IO) { TvApi(tv.host).pair(code, PhoneStore.phoneName(ctx)) }
                        paired(t, tv.title, null)
                    } catch (e: Exception) {
                        err = e.message ?: "Couldn't connect"
                    }
                    busy = false
                }
            }, busy = busy, enabled = code.length == 6, modifier = Modifier.padding(top = 10.dp))
        }
        if (tv.adb && tv.service == false) {
            Note(Icons.Rounded.Warning, "TV Lock isn't allowed to run on this TV right now.", C.warn)
            PButton("Fix it automatically", auto, icon = Icons.Rounded.AutoAwesome)
        }
    } else Page(step = "Step 2", title = tv.title, sub = tv.ip, onBack = back) {
        if (tv.adb) {
            PCard(highlight = true) {
                Tag("Recommended", C.caramel)
                Spacer(Modifier.height(10.dp))
                Text("Automatic setup", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    (if (tv.tvLock) "Allows everything TV Lock needs on the TV" else "Installs TV Lock on the TV, allows everything it needs") +
                        " and connects this phone. The TV asks once: press Allow with the remote.",
                    color = C.dim, fontSize = 15.sp, lineHeight = 21.sp,
                )
                Spacer(Modifier.height(4.dp))
                PButton("Set up automatically", auto, icon = Icons.Rounded.AutoAwesome, primary = true, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (tv.tvLock) {
            PCard {
                Text("Connect with the code", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("The 6 digits on the TV: on the setup screen, or TV Lock → Phone.", color = C.dim, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Field(code, { code = it.filter(Char::isDigit).take(6); err = null }, "123 456", KeyboardType.NumberPassword)
                err?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(4.dp))
                PButton("Connect", {
                    busy = true
                    scope.launch {
                        try {
                            val t = withContext(Dispatchers.IO) { TvApi(tv.host).pair(code, PhoneStore.phoneName(ctx)) }
                            paired(t, tv.title, null)
                        } catch (e: Exception) {
                            err = e.message ?: "Couldn't connect"
                        }
                        busy = false
                    }
                }, primary = !tv.adb, busy = busy, enabled = code.length == 6, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (!tv.adb) {
            Note(
                Icons.Rounded.DeveloperMode,
                "For automatic setup, turn on network debugging on the TV: Settings → Device Preferences → About → press Build 7 times. " +
                    "Then Developer options → turn on USB debugging (and Network debugging if it's there), and search again.",
                C.dim,
            )
        }
    }
}

@Composable
private fun Linking(s: P.Linking, store: PhoneStore, onFail: () -> Unit, done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s) {
        try {
            val api = TvApi(s.host)
            val hello = withContext(Dispatchers.IO) { api.hello() } ?: throw ApiError("Can't reach the TV at ${s.host}. Is this phone on your home network?")
            val t = withContext(Dispatchers.IO) { api.pair(s.code, PhoneStore.phoneName(ctx)) }
            store.save(s.host, t, hello.optString("name").ifBlank { "your TV" })
            done(hello.optBoolean("setupDone"))
        } catch (e: Exception) {
            err = e.message
        }
    }
    Page(title = if (err == null) "Connecting to your TV…" else "Couldn't connect", sub = err ?: s.host) {
        if (err == null) CircularProgressIndicator(color = C.latte)
        else {
            Text("The code from the TV may have expired. Open TV Lock on the TV and scan the new code, or search for the TV.", color = C.dim)
            Spacer(Modifier.height(16.dp))
            PButton("Find my TV", onFail, icon = Icons.Rounded.Search, primary = true)
        }
    }
}

@Composable
private fun AutoScreen(tv: FoundTv, store: PhoneStore, back: () -> Unit, done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val setup = remember(tv) { AdbSetup(ctx, tv.ip) }
    val steps by setup.steps.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(attempt) {
        err = null
        try {
            val existing = store.token.takeIf { store.host == tv.host }
            val (host, token, name) = setup.run(PhoneStore.phoneName(ctx), existing)
            store.save(host, token, name)
            val api = TvApi(host, token)
            result = withContext(Dispatchers.IO) { runCatching { api.get("/api/hello").optBoolean("setupDone") }.getOrDefault(false) }
        } catch (e: Exception) {
            err = e.message
        }
    }
    Page(
        step = "Automatic setup", title = if (result != null) "The TV is ready" else "Setting up ${tv.title}",
        sub = if (result != null) "Everything on the TV is allowed." else "Keep this screen open. Look at the TV when it asks to allow this phone.",
        onBack = if (result == null) back else null,
        bottom = {
            when {
                result != null -> PButton("Continue", { done(result!!) }, icon = Icons.AutoMirrored.Rounded.ArrowForward, primary = true)
                err != null -> PButton("Try again", { attempt++ }, icon = Icons.Rounded.Refresh, primary = true)
            }
        },
    ) {
        steps.forEachIndexed { i, st ->
            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    when (st.st) {
                        AdbSetup.St.RUN -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = C.latte)
                        AdbSetup.St.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = C.good)
                        AdbSetup.St.SKIP -> Icon(Icons.Rounded.RemoveCircleOutline, null, tint = C.dim)
                        AdbSetup.St.FAIL -> Icon(Icons.Rounded.Error, null, tint = C.error)
                        AdbSetup.St.WAIT -> Box(Modifier.size(12.dp).clip(CircleShape).background(C.cream.copy(alpha = 0.2f)))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(st.title, color = if (st.st == AdbSetup.St.WAIT) C.dim else C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val showDetail = st.st == AdbSetup.St.RUN || (i == 0 && st.st == AdbSetup.St.WAIT)
                    if (st.note != null) Text(st.note, color = if (st.st == AdbSetup.St.FAIL) C.error else C.dim, fontSize = 14.sp)
                    else if (showDetail) Text(st.detail, color = C.dim, fontSize = 14.sp)
                }
            }
        }
        if (steps[0].st == AdbSetup.St.RUN) {
            Note(Icons.Rounded.Tv, "On the TV: “Allow debugging?” → tick “Always allow from this computer” → Allow.", C.latte)
        }
        err?.let { Note(Icons.Rounded.Error, it, C.error) }
    }
}

@Composable
private fun ModeScreen(api: TvApi, auto: (FoundTv) -> Unit, next: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var tvState by remember { mutableStateOf<JSONObject?>(null) }
    var adb by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            tvState = runCatching { api.get("/api/state") }.getOrNull()
            if (tvState?.optJSONObject("tv")?.optBoolean("service") == false) {
                adb = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(api.host.substringBefore(':'), 5555), 800) }; true }.getOrDefault(false)
            }
        }
    }
    Page(step = "Finish setup", title = "What should TV Lock lock?", sub = "You can change this any time.") {
        val tv = tvState?.optJSONObject("tv")
        if (tv != null && !tv.optBoolean("service")) {
            PCard(highlight = true) {
                Text("TV Lock isn't allowed on the TV yet", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (adb) "This phone can allow it for you." else "On the TV, press Allow here on the setup screen, or allow Accessibility for TV Lock in Settings.",
                    color = C.dim, fontSize = 14.sp,
                )
                if (adb) PButton("Allow it automatically", {
                    auto(FoundTv(api.host.substringBefore(':'), tv.optString("name"), true, true, false))
                }, icon = Icons.Rounded.AutoAwesome, primary = true, modifier = Modifier.padding(top = 12.dp))
            }
        }
        listOf(
            Triple(Icons.Rounded.PowerSettingsNew, "When the TV starts", "The PIN screen instead of the home screen when the TV turns on, wakes up or you switch back to it."),
            Triple(Icons.Rounded.Apps, "Specific apps", "Only the apps you choose ask for the PIN."),
            Triple(Icons.Rounded.Shield, "Both", "The TV start and the apps you choose."),
        ).forEachIndexed { i, (icon, title, desc) ->
            PCard(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { api.post("/api/settings", JSONObject().put("bootLock", i != 1).put("appLock", i != 0)) } }
                    next(i != 0)
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(icon, 50.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(desc, color = C.dim, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsScreen(api: TvApi, back: () -> Unit, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<JSONObject>?>(null) }
    val icons = remember { mutableStateMapOf<String, ImageBitmap>() }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        val a = withContext(Dispatchers.IO) { runCatching { api.get("/api/apps", 8000).getJSONArray("apps") }.getOrDefault(JSONArray()) }
        apps = (0 until a.length()).map { a.getJSONObject(it) }
        withContext(Dispatchers.IO) {
            apps!!.forEach { o ->
                val pkg = o.getString("pkg")
                api.bytes("/api/icon?pkg=" + Uri.encode(pkg))?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { icons[pkg] = it.asImageBitmap() } }
            }
        }
    }
    Page(
        step = "Finish setup", title = "Choose apps to lock",
        sub = "Tip: lock Settings and the Play Store too, so nobody can switch TV Lock off.", onBack = back,
        bottom = {
            PButton(if (chosen.isEmpty()) "Skip" else "Lock ${chosen.size} app${if (chosen.size == 1) "" else "s"}", {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { api.post("/api/settings", JSONObject().put("lockedApps", JSONArray(chosen.toList()))) } }
                    next()
                }
            }, icon = Icons.AutoMirrored.Rounded.ArrowForward, primary = true)
        },
    ) {
        val list = apps
        if (list == null) CircularProgressIndicator(color = C.latte)
        else LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.heightIn(max = 4000.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false,
        ) {
            items(list, key = { it.getString("pkg") }) { o ->
                val pkg = o.getString("pkg")
                val on = pkg in chosen
                Column(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) C.cream.copy(alpha = 0.14f) else C.card)
                        .border(if (on) 2.dp else 1.dp, if (on) C.latte else C.cardLine, RoundedCornerShape(20.dp))
                        .clickable { chosen = if (on) chosen - pkg else chosen + pkg },
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                        icons[pkg]?.let {
                            if (o.optBoolean("wide")) Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Image(it, null, Modifier.size(48.dp))
                        }
                        if (on) Box(
                            Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).clip(CircleShape).background(C.cream),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Lock, null, tint = C.focusText, modifier = Modifier.size(16.dp)) }
                    }
                    Text(
                        o.getString("label"), color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinScreen(api: TvApi, back: () -> Unit, setupTv: Boolean, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kinds = listOf(Triple("PIN", 4, "4 digits"), Triple("PIN", 6, "6 digits"), Triple("PIN_ANY", 0, "Any length"), Triple("PASSWORD", 0, "Password"))
    var kind by remember { mutableIntStateOf(0) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val ctx = LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { u -> photo = runCatching { squareJpeg(ctx, u) }.getOrNull() } }
    val preview = remember(photo) { photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val (k, len, _) = kinds[kind]
    val pw = k == "PASSWORD"
    Page(
        step = if (setupTv) "Finish setup" else "Your profile", title = "Create your profile",
        sub = "Each person gets their own PIN, and the TV says hello to whoever unlocks it. You can add more people later.", onBack = back,
        bottom = {
            PButton(if (setupTv) "Save and lock the TV" else "Save my profile", {
                err = when {
                    name.isBlank() -> "Add your name."
                    a != b -> "Those don't match."
                    k == "PIN" && a.length != len -> "Enter exactly $len digits."
                    a.length < 4 -> "Use at least 4 characters."
                    else -> null
                }
                if (err != null) return@PButton
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val pr = api.post("/api/profiles/add", JSONObject().put("name", name.trim()).put("pin", a).put("kind", k))
                                .getJSONObject("profile")
                            photo?.let { api.upload("/api/profiles/photo?id=" + Uri.encode(pr.getString("id")), it) }
                            api.post("/api/me/link", JSONObject().put("profile", pr.getString("id")))
                            if (setupTv) api.post("/api/setup/finish")
                        }
                        done()
                    } catch (e: Exception) {
                        err = e.message ?: "Couldn't reach the TV"
                    }
                    busy = false
                }
            }, icon = Icons.Rounded.Lock, primary = true, busy = busy)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(dev.alexaero.tvlock.ui.avatarBrush(0)).clickable { pick.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (name.isNotBlank()) Text(name.trim().first().uppercase(), color = C.focusText, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                else Icon(Icons.Rounded.AddAPhoto, "Add a photo", tint = C.focusText)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Field(name, { name = it.take(24); err = null }, "Your name", KeyboardType.Text)
                Text(if (photo == null) "Tap the circle to add a photo" else "Tap the circle to change the photo", color = C.dim, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Your PIN", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("You'll enter it on the TV with the remote. A 4-digit PIN is the quickest.", color = C.dim, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEachIndexed { i, (_, _, label) ->
                val on = i == kind
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) C.cream else C.card)
                        .clickable { kind = i; a = ""; b = ""; err = null },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) C.focusText else C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            }
        }
        Spacer(Modifier.height(18.dp))
        val kb = if (pw) KeyboardType.Password else KeyboardType.NumberPassword
        val clean = { s: String -> if (pw) s.take(32) else s.filter(Char::isDigit).take(if (k == "PIN") len else 16) }
        Field(a, { a = clean(it); err = null }, if (pw) "New password" else "New PIN", kb, secret = true)
        Spacer(Modifier.height(12.dp))
        Field(b, { b = clean(it); err = null }, "Enter it again", kb, secret = true)
        err?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun DoneScreen(tvName: String, open: () -> Unit) {
    Page(
        title = "Your TV is locked",
        sub = "TV Lock is on $tvName. Use this app any time to unlock it, control it or change the lock.",
        bottom = { PButton("Open the remote", open, icon = Icons.Rounded.SettingsRemote, primary = true) },
    ) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Badge(Icons.Rounded.Check, 120.dp) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RemoteScreen(store: PhoneStore, forget: () -> Unit, find: () -> Unit) {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // Profile photos: the web remote's file picker opens the phone's gallery.
    var pending by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> pending?.onReceiveValue(uri?.let { arrayOf(it) }); pending = null }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    // Removed from the TV? Start over instead of showing a remote that can't connect.
    LaunchedEffect(store.host, store.token) {
        val revoked = withContext(Dispatchers.IO) {
            runCatching { TvApi(store.host!!, store.token).get("/api/state"); false }
                .getOrElse { it is ApiError && it.message == "not paired" }
        }
        if (revoked) forget()
    }
    Backdrop {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            key(reload) {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#0D0C0A"))
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString + " TVLockApp/$version"
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onShowFileChooser(
                                    v: WebView, cb: android.webkit.ValueCallback<Array<Uri>>, p: FileChooserParams,
                                ): Boolean {
                                    pending?.onReceiveValue(null)
                                    pending = cb
                                    pick.launch("image/*")
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                    val u = r.url
                                    if (u.scheme == "tvlock") {
                                        if (u.host == "app" && u.path == "/forget") forget()
                                        return true
                                    }
                                    return false
                                }

                                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                                    if (r.isForMainFrame) failed = true
                                }
                            }
                            loadUrl("http://${store.host}/#t=${store.token}")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (failed) {
                Page(
                    title = "Can't reach ${store.name}",
                    sub = "Is this phone at home, on your home network? Is the TV plugged in?",
                    bottom = {
                        PButton("Try again", { failed = false; reload++ }, icon = Icons.Rounded.Refresh, primary = true)
                        Spacer(Modifier.height(10.dp))
                        PButton("Find the TV again", find, icon = Icons.Rounded.Search)
                    },
                ) {
                    Note(Icons.Rounded.Home, "TV Lock only works at home: nothing is opened on your router, so it can't be reached from outside.", C.dim)
                }
            }
        }
    }
}

/** A picked photo as a 512 px square JPEG (the TV stores it as is). */
private fun squareJpeg(ctx: android.content.Context, uri: Uri): ByteArray {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val side = minOf(src.width, src.height)
    val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val out = android.graphics.Bitmap.createScaledBitmap(sq, 512, 512, true)
    return java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}

@Composable
private fun WhoScreen(api: TvApi, me: (String) -> Unit, create: () -> Unit, skip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Page(
        step = "Almost done", title = "Who's using this phone?",
        sub = "So the TV greets you by name when you unlock it from here.",
        bottom = { PButton("Skip for now", skip) },
    ) {
        PCard(highlight = true) {
            Text("I have a PIN", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Enter the PIN you use on the TV.", color = C.dim, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Field(pin, { pin = it.take(32); err = null }, "Your PIN", KeyboardType.NumberPassword, secret = true)
            err?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            PButton("That's me", {
                busy = true
                scope.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { api.post("/api/me/link-pin", JSONObject().put("pin", pin)) }
                        me(j.getJSONObject("profile").getString("id"))
                    } catch (e: Exception) {
                        err = e.message ?: "Couldn't reach the TV"
                    }
                    busy = false
                }
            }, primary = true, busy = busy, enabled = pin.length >= 4, modifier = Modifier.padding(top = 10.dp))
        }
        PCard(onClick = create) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(Icons.Rounded.PersonAdd, 50.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("I'm new here", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Make your own profile: your name, photo and PIN.", color = C.dim, fontSize = 14.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = C.dim)
            }
        }
    }
}

/** "How do you want to appear?": name and photo for this phone's person. */
@Composable
private fun MeScreen(api: TvApi, profileId: String, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var name by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { u -> photo = runCatching { squareJpeg(ctx, u) }.getOrNull() } }
    val preview = remember(photo) { photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } } ?: current
    LaunchedEffect(profileId) {
        withContext(Dispatchers.IO) {
            val st = runCatching { api.get("/api/state") }.getOrNull() ?: return@withContext
            val a = st.getJSONArray("profiles")
            val p = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == profileId } ?: return@withContext
            profile = p
            name = p.getString("name")
            if (p.optLong("photo") > 0) {
                api.bytes("/api/profiles/photo?id=" + Uri.encode(profileId))?.let { b ->
                    current = BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                }
            }
        }
    }
    val color = profile?.optInt("color") ?: 0
    Page(
        step = "Your profile", title = if (profile != null) "Hi, ${profile!!.getString("name")}!" else "Hi!",
        sub = "How do you want to appear on the TV? This is what it shows when you unlock it.",
        bottom = {
            PButton("Save", {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (name.isNotBlank() && name.trim() != profile?.optString("name")) {
                                api.post("/api/profiles/update", JSONObject().put("id", profileId).put("name", name.trim()))
                            }
                            photo?.let { api.upload("/api/profiles/photo?id=" + Uri.encode(profileId), it) }
                        }
                        done()
                    } catch (e: Exception) {
                        err = e.message ?: "Couldn't reach the TV"
                    }
                    busy = false
                }
            }, icon = Icons.Rounded.Check, primary = true, busy = busy)
        },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(132.dp).clip(CircleShape).background(dev.alexaero.tvlock.ui.avatarBrush(color)).clickable { pick.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = C.focusText, fontSize = 54.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            if (preview == null) "Tap the circle to add a photo" else "Tap the circle to change the photo",
            color = C.dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text("Name", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Field(name, { name = it.take(24); err = null }, "Your name", KeyboardType.Text)
        err?.let { Text(it, color = C.error, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}
