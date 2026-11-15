package dev.alexaero.tvlock.ui

import android.view.KeyEvent as AKey
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alexaero.tvlock.SecretKind
import kotlin.math.roundToInt

const val MIN_LEN = 4
fun maxLen(kind: SecretKind) = if (kind == SecretKind.PASSWORD) 32 else 16

/** Entry text + a shake trigger, shared by the lock screen and the create-PIN steps. */
class Entry {
    var text by mutableStateOf("")
    var shakes by mutableIntStateOf(0)
    fun fail() { text = ""; shakes++ }
}

/**
 * Digits/letters from the remote's number buttons or a real keyboard. Returns true when used.
 * Arrows, OK and Back are left to focus navigation and the screen.
 */
fun typeKey(e: KeyEvent, kind: SecretKind, entry: Entry, submit: () -> Unit): Boolean {
    val k = e.nativeKeyEvent
    if (e.type != KeyEventType.KeyDown) {
        return k.keyCode in AKey.KEYCODE_0..AKey.KEYCODE_9 || k.keyCode == AKey.KEYCODE_DEL
    }
    when (k.keyCode) {
        in AKey.KEYCODE_0..AKey.KEYCODE_9, in AKey.KEYCODE_NUMPAD_0..AKey.KEYCODE_NUMPAD_9 -> {
            if (kind == SecretKind.PASSWORD && k.keyCode <= AKey.KEYCODE_9 && k.unicodeChar != 0 &&
                !k.unicodeChar.toChar().isDigit()
            ) {
                add(entry, kind, k.unicodeChar.toChar().toString())
            } else {
                val d = if (k.keyCode <= AKey.KEYCODE_9) k.keyCode - AKey.KEYCODE_0 else k.keyCode - AKey.KEYCODE_NUMPAD_0
                add(entry, kind, d.toString())
            }
            return true
        }
        AKey.KEYCODE_DEL -> { entry.text = entry.text.dropLast(1); return true }
        AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
            // A real keyboard's Enter submits; the remote's OK (DPAD_CENTER) presses the focused key.
            if (k.device?.isVirtual == false && k.device?.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                submit(); return true
            }
            return false
        }
    }
    if (kind == SecretKind.PASSWORD && k.unicodeChar != 0) {
        val ch = k.unicodeChar.toChar()
        if (!ch.isISOControl()) { add(entry, kind, ch.toString()); return true }
    }
    return false
}

fun add(entry: Entry, kind: SecretKind, s: String) {
    if (entry.text.length < maxLen(kind)) entry.text += s
}

/** Dots (PIN) or a masked field (password), shaking on a wrong answer. */
@Composable
fun EntryDisplay(kind: SecretKind, length: Int, entry: Entry, error: Boolean) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(entry.shakes) {
        if (entry.shakes == 0) return@LaunchedEffect
        for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(45))
    }
    Box(Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }) {
        if (kind == SecretKind.PASSWORD) {
            Box(
                Modifier.widthIn(min = 300.dp).height(56.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0x14F4EBD9))
                    .border(2.dp, if (error) C.error else C.cream.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (entry.text.isEmpty()) Text("Password", color = C.dim, style = MaterialTheme.typography.titleLarge)
                else Text("•".repeat(entry.text.length), fontSize = 28.sp, letterSpacing = 4.sp, maxLines = 1)
            }
        } else {
            val count = if (kind == SecretKind.PIN) length else maxOf(MIN_LEN, entry.text.length)
            Row(horizontalArrangement = Arrangement.spacedBy(if (count > 8) 10.dp else 16.dp)) {
                repeat(count) { i ->
                    val filled = i < entry.text.length
                    val s by animateFloatAsState(if (filled) 1f else 0.82f, label = "dot")
                    Box(
                        Modifier.size(if (count > 8) 16.dp else 22.dp).graphicsLayer { scaleX = s; scaleY = s }
                            .clip(CircleShape)
                            .background(if (filled) (if (error) C.error else C.cream) else Color.Transparent)
                            .border(2.dp, if (error) C.error else C.cream.copy(alpha = 0.5f), CircleShape),
                    )
                }
            }
        }
    }
}

/** On-screen keys: a phone-style number pad, or a full keyboard for passwords. */
@Composable
fun EntryPad(kind: SecretKind, entry: Entry, onSubmit: () -> Unit, first: FocusRequester) {
    if (kind == SecretKind.PASSWORD) Keyboard(entry, onSubmit, first)
    else NumberPad(kind, entry, onSubmit, first)
}

@Composable
private fun NumberPad(kind: SecretKind, entry: Entry, onSubmit: () -> Unit, first: FocusRequester) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                r.forEach { d -> RoundKey(d, { add(entry, kind, d) }, if (d == "1") first else null) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            RoundKey(null, { entry.text = entry.text.dropLast(1) }, icon = { Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", Modifier.size(26.dp)) })
            RoundKey("0", { add(entry, kind, "0") })
            RoundKey(null, onSubmit, accent = true, icon = { Icon(Icons.Rounded.Check, "OK", Modifier.size(30.dp)) })
        }
    }
}

@Composable
private fun RoundKey(
    label: String?,
    onClick: () -> Unit,
    fr: FocusRequester? = null,
    accent: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    TvSurface(
        onClick, Modifier.size(70.dp), CircleShape,
        color = if (accent) C.cream else Color(0x14F4EBD9), focusColor = if (accent) Color(0xFFFFF8EA) else C.cream,
        contentColor = if (accent) C.focusText else C.text, scale = 1.12f, focusRequester = fr,
    ) {
        if (icon != null) icon() else Text(label!!, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class Layer { LOWER, UPPER, SYMBOLS }

@Composable
private fun Keyboard(entry: Entry, onSubmit: () -> Unit, first: FocusRequester) {
    var layer by remember { mutableStateOf(Layer.LOWER) }
    val rows = when (layer) {
        Layer.LOWER -> listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        Layer.UPPER -> listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        Layer.SYMBOLS -> listOf("1234567890", "!@#$%^&*()", "-_=+[]{};:", "'\",.<>/?\\|~")
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEachIndexed { ri, r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEachIndexed { ci, ch ->
                    KbKey(ch.toString(), Modifier.width(40.dp), if (ri == 1 && ci == 0) first else null) {
                        add(entry, SecretKind.PASSWORD, ch.toString())
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KbKey(if (layer == Layer.SYMBOLS) "ABC" else "#+=", Modifier.width(64.dp)) {
                layer = if (layer == Layer.SYMBOLS) Layer.LOWER else Layer.SYMBOLS
            }
            KbKey(null, Modifier.width(52.dp), selected = layer == Layer.UPPER, icon = {
                Icon(Icons.Rounded.ArrowUpward, "Shift", Modifier.size(20.dp))
            }) { layer = if (layer == Layer.UPPER) Layer.LOWER else Layer.UPPER }
            KbKey("space", Modifier.width(128.dp)) { add(entry, SecretKind.PASSWORD, " ") }
            KbKey(null, Modifier.width(64.dp), icon = {
                Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", Modifier.size(20.dp))
            }) { entry.text = entry.text.dropLast(1) }
            KbKey(null, Modifier.width(76.dp), accent = true, icon = {
                Icon(Icons.Rounded.Check, "OK", Modifier.size(22.dp))
            }, onClick = onSubmit)
        }
    }
}

@Composable
private fun KbKey(
    label: String?,
    modifier: Modifier,
    fr: FocusRequester? = null,
    accent: Boolean = false,
    selected: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    TvSurface(
        onClick, modifier.height(42.dp), RoundedCornerShape(10.dp),
        color = if (accent) C.cream else Color(0x14F4EBD9), focusColor = if (accent) Color(0xFFFFF8EA) else C.cream,
        contentColor = if (accent) C.focusText else C.text, selected = selected, scale = 1.14f, focusRequester = fr,
    ) {
        if (icon != null) icon() else Text(label!!, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
