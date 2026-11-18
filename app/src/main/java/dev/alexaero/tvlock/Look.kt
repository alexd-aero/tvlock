package dev.alexaero.tvlock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.io.File

/** A whole-UI colour theme for the TV app (lock screen included). */
data class Palette(
    val id: String,
    val name: String,
    val bg0: Color,
    val bg1: Color,
    /** Main accent: focused controls, primary buttons. */
    val accent: Color,
    val accent2: Color,
    /** Labels like "STEP 1 OF 6", glows. */
    val accent3: Color,
    val text: Color,
    val dim: Color,
    /** Text on the accent colour. */
    val ink: Color,
)

/** Theme + custom background of the TV app. Snapshot state, so the UI recolours the moment it changes. */
object Look {
    val palettes = listOf(
        Palette("cream", "Cream", Color(0xFF0D0C0A), Color(0xFF1A1714), Color(0xFFF4EBD9), Color(0xFFDCC7A6), Color(0xFFC89B6D),
            Color(0xFFF4EBD9), Color(0xFFA89F8F), Color(0xFF1B1712)),
        Palette("midnight", "Midnight", Color(0xFF070B16), Color(0xFF111A2E), Color(0xFFDCE8FF), Color(0xFFA9C2F0), Color(0xFF7FA3E8),
            Color(0xFFEAF1FF), Color(0xFF8E9AB5), Color(0xFF0B1426)),
        Palette("forest", "Forest", Color(0xFF07100C), Color(0xFF102119), Color(0xFFDDF2E3), Color(0xFFA9D6B5), Color(0xFF6FBF8A),
            Color(0xFFE8F5EC), Color(0xFF8FA899), Color(0xFF0B1A12)),
        Palette("rose", "Rose", Color(0xFF130A0E), Color(0xFF24131A), Color(0xFFFBE1E8), Color(0xFFEDB6C5), Color(0xFFD9849C),
            Color(0xFFFBEDF1), Color(0xFFAD949B), Color(0xFF26121A)),
        Palette("sunset", "Sunset", Color(0xFF120A06), Color(0xFF26150C), Color(0xFFFFE2C7), Color(0xFFF6B98A), Color(0xFFE8895A),
            Color(0xFFFFEEDF), Color(0xFFB39A88), Color(0xFF26140A)),
        Palette("lilac", "Lilac", Color(0xFF0E0A15), Color(0xFF1C1529), Color(0xFFEBE1FF), Color(0xFFC9B6F2), Color(0xFFA287E0),
            Color(0xFFF2EDFF), Color(0xFF9F96B3), Color(0xFF191127)),
        Palette("mono", "Mono", Color(0xFF000000), Color(0xFF111111), Color(0xFFFFFFFF), Color(0xFFD0D0D0), Color(0xFFA0A0A0),
            Color(0xFFFFFFFF), Color(0xFF9A9A9A), Color(0xFF000000)),
    )

    var palette by mutableStateOf(palettes[0])
        private set

    /** "", "image", "gif" or "video". */
    var bgType by mutableStateOf("")
        private set
    /** Changes when a new background is uploaded (so it reloads). */
    var bgVersion by mutableStateOf(0L)
        private set
    /** How much the background is darkened behind the UI, 0–90 %. */
    var dim by mutableStateOf(55)
        private set

    /** How the background fills the screen: fill (crop), fit or stretch; turned 0/90/180/270°; mirrored. */
    var fit by mutableStateOf("fill")
        private set
    var rotate by mutableStateOf(0)
        private set
    var mirror by mutableStateOf(false)
        private set

    fun setPlacement(ctx: Context, fit: String? = null, rotate: Int? = null, mirror: Boolean? = null) {
        val p = Prefs(ctx)
        if (fit != null && fit in listOf("fill", "fit", "stretch")) { p.bgFit = fit; this.fit = fit }
        if (rotate != null) { val r = ((rotate % 360) + 360) % 360 / 90 * 90; p.bgRotate = r; this.rotate = r }
        if (mirror != null) { p.bgMirror = mirror; this.mirror = mirror }
        Live.bump()
    }

    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = Prefs(ctx)
        palette = palettes.firstOrNull { it.id == p.theme } ?: palettes[0]
        bgType = p.bgType.takeIf { bgFile(ctx).exists() } ?: ""
        bgVersion = p.bgVersion
        dim = p.bgDim
        fit = p.bgFit
        rotate = p.bgRotate
        mirror = p.bgMirror
    }

    fun setTheme(ctx: Context, id: String) {
        val pal = palettes.firstOrNull { it.id == id } ?: return
        Prefs(ctx).theme = pal.id
        palette = pal
        Live.bump()
    }

    fun setDim(ctx: Context, d: Int) {
        val v = d.coerceIn(0, 90)
        Prefs(ctx).bgDim = v
        dim = v
        Live.bump()
    }

    fun bgFile(ctx: Context) = File(File(ctx.filesDir, "look").apply { mkdirs() }, "background")

    /** A new background was written to [bgFile]. */
    fun setBackground(ctx: Context, type: String) {
        val p = Prefs(ctx)
        p.bgType = type
        p.bgVersion = System.currentTimeMillis()
        bgType = type
        bgVersion = p.bgVersion
        Live.bump()
    }

    fun removeBackground(ctx: Context) {
        bgFile(ctx).delete()
        Prefs(ctx).bgType = ""
        bgType = ""
        Live.bump()
    }

    /** What a file is, from its first bytes: image / gif / video, or null. */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = b.indices.all { i + it < head.size && (head[i + it].toInt() and 0xff) == b[it] }
        return when {
            at(0, 0x47, 0x49, 0x46) -> "gif"
            at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 0x50, 0x4E, 0x47) -> "image"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image" // WebP
            at(4, 0x66, 0x74, 0x79, 0x70) -> if (at(8, 0x61, 0x76, 0x69, 0x66) || at(8, 0x68, 0x65, 0x69, 0x63)) "image" else "video" // avif/heic vs mp4/mov
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video" // webm/mkv
            else -> null
        }
    }

    /** image / gif / video from an upload's Content-Type (null if unsupported). */
    fun typeOf(mime: String?): String? {
        val m = mime?.lowercase() ?: return null
        return when {
            m.startsWith("image/gif") -> "gif"
            m.startsWith("image/") -> "image"
            m.startsWith("video/") -> "video"
            else -> null
        }
    }
}
