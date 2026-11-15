package dev.alexaero.tvlock.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The building block of every remote-friendly control: focusable with the D-pad, grows and
 * turns white when focused, OK/Enter clicks it.
 */
@Composable
fun TvSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    color: Color = C.card,
    focusColor: Color = C.cream,
    contentColor: Color = C.text,
    selected: Boolean = false,
    scale: Float = 1.06f,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) scale else 1f, label = "scale")
    val border = when {
        focused -> SolidColor(Color(0xFFFFF8EA))
        selected -> C.accent
        else -> SolidColor(C.cardLine)
    }
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .shadow(if (focused) 18.dp else 0.dp, shape, ambientColor = C.caramel, spotColor = C.caramel)
            .clip(shape)
            .background(if (focused) focusColor else if (selected) C.cream.copy(alpha = 0.14f) else color)
            .border(if (focused || selected) 2.dp else 1.dp, border, shape)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .clickable(src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) C.focusText else contentColor) {
            content(focused)
        }
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    TvSurface(
        onClick, modifier.height(52.dp), RoundedCornerShape(26.dp),
        color = if (primary) C.cream else C.card, focusColor = if (primary) Color(0xFFFFF8EA) else C.cream,
        contentColor = if (primary) C.focusText else C.text, focusRequester = focusRequester,
    ) {
        Row(Modifier.padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A focusable row with a title, description and an on/off pill. */
@Composable
fun TvToggle(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    TvSurface({ onChange(!checked) }, modifier, scale = 1.03f, focusRequester = focusRequester) { focused ->
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = if (focused) C.focusText.copy(alpha = .7f) else C.dim)
            }
            Spacer(Modifier.width(16.dp))
            Pill(checked)
        }
    }
}

@Composable
fun Pill(on: Boolean) {
    Box(
        Modifier.size(52.dp, 30.dp).clip(CircleShape)
            .background(if (on) C.accent else SolidColor(Color(0x33A89F8F))),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(if (on) C.focusText else C.cream))
    }
}

/** Small round icon badge with the accent gradient. */
@Composable
fun Badge(icon: ImageVector, size: Dp = 56.dp, brush: Brush = C.accent) {
    Box(Modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = C.focusText)
    }
}

/** Move D-pad focus to [fr] once the screen is up (retries while the window gets focus). */
@Composable
fun InitialFocus(fr: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        repeat(20) {
            delay(if (it == 0) 30 else 100)
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
fun ScreenTitle(title: String, sub: String? = null, step: String? = null) {
    Column {
        if (step != null) {
            Text(step.uppercase(), style = MaterialTheme.typography.labelLarge, color = C.caramel)
            Spacer(Modifier.height(6.dp))
        }
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (sub != null) {
            Spacer(Modifier.height(8.dp))
            Text(sub, style = MaterialTheme.typography.bodyLarge, color = C.dim)
        }
    }
}

val Gap = Arrangement.spacedBy(14.dp)
