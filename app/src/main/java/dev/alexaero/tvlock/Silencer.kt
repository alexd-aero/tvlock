package dev.alexaero.tvlock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Takes audio focus while the lock is up, so the app (or the home screen's trailers)
 * playing behind the lock pauses instead of playing sound.
 */
class Silencer(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    private var req: AudioFocusRequest? = null
    private var held = false

    fun take() {
        if (held) return
        held = true
        if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            req = r
            am.requestAudioFocus(r)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    fun release() {
        if (!held) return
        held = false
        if (Build.VERSION.SDK_INT >= 26) req?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(listener)
        req = null
    }
}
