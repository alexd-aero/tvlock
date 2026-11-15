package dev.alexaero.tvlock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alexaero.tvlock.Profile
import dev.alexaero.tvlock.Profiles

fun avatarBrush(color: Int): Brush {
    val (a, b) = Profiles.palette[color.coerceIn(0, Profiles.palette.lastIndex)]
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

/** A profile's photo, or their initial on their colour. */
@Composable
fun Avatar(p: Profile, size: Dp, modifier: Modifier = Modifier, glow: Boolean = false) =
    AvatarOf(p.id, p.initial, p.color, p.photo, size, modifier, glow)

@Composable
fun AvatarOf(id: String?, initial: String, color: Int, photo: Long, size: Dp, modifier: Modifier = Modifier, glow: Boolean = false) {
    val ctx = LocalContext.current
    val img = remember(id, photo) {
        if (id == null || photo <= 0) null
        else runCatching { BitmapFactory.decodeFile(Profiles.photoFile(ctx, id).path)?.asImageBitmap() }.getOrNull()
    }
    Box(
        modifier.size(size)
            .let { if (glow) it.shadow(size * 0.22f, CircleShape, ambientColor = C.caramel, spotColor = C.caramel) else it }
            .clip(CircleShape)
            .background(avatarBrush(color))
            .let { if (glow) it.border(3.dp, C.cream.copy(alpha = 0.55f), CircleShape) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initial, color = C.focusText, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}
