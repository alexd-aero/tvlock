package dev.alexaero.tvlock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object C {
    // Warm near-black backdrop, cream foreground.
    val bg0 = Color(0xFF0D0C0A)
    val bg1 = Color(0xFF1A1714)
    val card = Color(0x12F4EBD9)
    val cardLine = Color(0x1FF4EBD9)
    val cream = Color(0xFFF4EBD9)
    val latte = Color(0xFFDCC7A6)
    val caramel = Color(0xFFC89B6D)
    val text = Color(0xFFF4EBD9)
    val dim = Color(0xFFA89F8F)
    val focusText = Color(0xFF1B1712)
    val error = Color(0xFFFF8F7E)
    val good = Color(0xFFA6D9A9)
    val warn = Color(0xFFF2C879)
    val accent = Brush.linearGradient(listOf(cream, latte))
}

private val type = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun TvLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.cream, secondary = C.caramel, background = C.bg0, surface = C.bg1,
            onBackground = C.text, onSurface = C.text, error = C.error,
        ),
        typography = type,
    ) {
        CompositionLocalProvider(LocalContentColor provides C.text, content = content)
    }
}

/** The dark backdrop with two soft colour glows used on every screen. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.bg0, C.bg1)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(C.caramel.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.05f), radius = size.width * 0.45f,
                ),
                radius = size.width * 0.45f, center = Offset(size.width * 0.12f, size.height * 0.05f),
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(C.latte.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 1.0f), radius = size.width * 0.4f,
                ),
                radius = size.width * 0.4f, center = Offset(size.width * 0.95f, size.height * 1.0f),
            )
        }
        content()
    }
}
