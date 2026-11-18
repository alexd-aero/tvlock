package dev.alexaero.tvlock.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.alexaero.tvlock.Look

/** The custom background: a photo, an animated GIF, or a looping silent video, filling the screen. */
@Composable
fun BackgroundMedia(type: String, version: Long) {
    // Turned sideways: lay it out in a box with width and height swapped, then rotate that box.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = Look.rotate == 90 || Look.rotate == 270
        Box(
            Modifier.requiredSize(if (side) maxHeight else maxWidth, if (side) maxWidth else maxHeight)
                .graphicsLayer { rotationZ = Look.rotate.toFloat(); scaleX = if (Look.mirror) -1f else 1f },
        ) { Media(type, version) }
    }
}

private fun scaleFor(fit: String) = when (fit) { "fit" -> ContentScale.Fit; "stretch" -> ContentScale.FillBounds; else -> ContentScale.Crop }

@Composable
private fun Media(type: String, version: Long) {
    val ctx = LocalContext.current
    val file = remember(version) { Look.bgFile(ctx) }
    when (type) {
        "video" -> VideoBackground(file.path, version)
        "gif" -> if (Build.VERSION.SDK_INT >= 28) {
            AndroidView(
                factory = { c ->
                    ImageView(c).apply {
                        scaleType = when (Look.fit) { "fit" -> ImageView.ScaleType.FIT_CENTER; "stretch" -> ImageView.ScaleType.FIT_XY; else -> ImageView.ScaleType.CENTER_CROP }
                        runCatching {
                            val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file))
                            setImageDrawable(d)
                            (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
                        }
                    }
                },
                update = { it.scaleType = when (Look.fit) { "fit" -> ImageView.ScaleType.FIT_CENTER; "stretch" -> ImageView.ScaleType.FIT_XY; else -> ImageView.ScaleType.CENTER_CROP } },
                modifier = Modifier.fillMaxSize(),
            )
        } else StillBackground(file.path, version)
        else -> StillBackground(file.path, version)
    }
}

@Composable
private fun StillBackground(path: String, version: Long) {
    // Down-sampled to about screen size: TV boxes don't have memory for 50 MP photos.
    val bmp = remember(path, version) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = scaleFor(Look.fit))
}

@Composable
private fun VideoBackground(path: String, version: Long) {
    val player = remember(path, version) { MediaPlayer() }
    DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    AndroidView(
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.setSurface(Surface(st))
                            player.setDataSource(path)
                            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            player.setOnVideoSizeChangedListener { _, vw, vh -> crop(this@apply, vw, vh) }
                            player.setOnPreparedListener { it.start() }
                            player.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) =
                        crop(this@apply, runCatching { player.videoWidth }.getOrDefault(0), runCatching { player.videoHeight }.getOrDefault(0))
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = { v -> crop(v, runCatching { player.videoWidth }.getOrDefault(0), runCatching { player.videoHeight }.getOrDefault(0)) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Fill (crop), fit or stretch the video in the view. */
private fun crop(v: TextureView, vw: Int, vh: Int) {
    if (vw <= 0 || vh <= 0 || v.width <= 0 || v.height <= 0) return
    if (Look.fit == "stretch") { v.setTransform(Matrix()); return }
    val sx = v.width.toFloat() / vw
    val sy = v.height.toFloat() / vh
    val s = if (Look.fit == "fit") minOf(sx, sy) else maxOf(sx, sy)
    val m = Matrix()
    m.setScale(vw * s / v.width, vh * s / v.height, v.width / 2f, v.height / 2f)
    v.setTransform(m)
}
