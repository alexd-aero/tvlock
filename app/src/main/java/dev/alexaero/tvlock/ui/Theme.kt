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
import dev.alexaero.tvlock.Look

/** Colours of the current theme ([Look.palette]); reading them in a composable recolours it when the theme changes. */
object C {
    private val p get() = Look.palette
    val bg0 get() = p.bg0
    val bg1 get() = p.bg1
    val card get() = p.text.copy(alpha = 0.07f)
    val cardLine get() = p.text.copy(alpha = 0.12f)
    val cream get() = p.accent
    val latte get() = p.accent2
    val caramel get() = p.accent3
    val text get() = p.text
    val dim get() = p.dim
    val focusText get() = p.ink
    val error = Color(0xFFFF8F7E)
    val good = Color(0xFFA6D9A9)
    val warn = Color(0xFFF2C879)
    val accent get() = Brush.linearGradient(listOf(cream, latte))
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

/** The backdrop of every screen: the theme's gradient and glows, or the custom background (dimmed). */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.bg0, C.bg1)))) {
        if (Look.bgType.isNotEmpty()) {
            BackgroundMedia(Look.bgType, Look.bgVersion)
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(
                    Color.Black.copy(alpha = (Look.dim / 100f * 0.85f).coerceIn(0f, 1f)),
                    Color.Black.copy(alpha = (Look.dim / 100f).coerceIn(0f, 1f)),
                )),
            ))
        } else {
            val glow1 = C.caramel
            val glow2 = C.latte
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(glow1.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.05f), radius = size.width * 0.45f,
                    ),
                    radius = size.width * 0.45f, center = Offset(size.width * 0.12f, size.height * 0.05f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(glow2.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 1.0f), radius = size.width * 0.4f,
                    ),
                    radius = size.width * 0.4f, center = Offset(size.width * 0.95f, size.height * 1.0f),
                )
            }
        }
        content()
    }
}
