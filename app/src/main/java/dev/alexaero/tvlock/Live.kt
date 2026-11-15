package dev.alexaero.tvlock

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live updates for phones: a Server-Sent Events stream that sends the full state the moment anything
 * changes (lock, unlock, settings, profiles, phones, what's on screen), instead of phones polling.
 */
object Live {
    /** Bumped for changes that have no flow of their own (settings, power, what's on screen). */
    val tick = MutableStateFlow(0L)
    fun bump() { tick.value++ }

    @OptIn(FlowPreview::class)
    fun stream(ctx: Context, deviceId: String): InputStream {
        val q = LinkedBlockingQueue<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            combine(
                LockState.current, LockState.greeting, LockState.gate, LockState.settingsOpen,
                combine(Profiles.changed, Remote.devicesChanged, tick, Updater.state) { a, b, c, d -> listOf(a, b, c, d) },
            ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                .debounce(40)
                .collect {
                    val me = Devices.list(Prefs(ctx)).firstOrNull { it.id == deviceId }
                    if (me == null) { q.put(END); return@collect }
                    q.put("data: ${Control.state(ctx, me)}\n\n".toByteArray())
                }
        }
        return object : InputStream() {
            private var buf = ByteArray(0)
            private var pos = 0
            private var done = false

            private fun fill(): Boolean {
                if (done) return false
                while (pos >= buf.size) {
                    // A comment every 15 s keeps the connection open through Wi-Fi power saving.
                    val next = q.poll(15, TimeUnit.SECONDS) ?: ": keep-alive\n\n".toByteArray()
                    if (next === END) { done = true; return false }
                    buf = next; pos = 0
                }
                return true
            }

            override fun read(): Int = if (fill()) buf[pos++].toInt() and 0xff else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!fill()) return -1
                val n = minOf(len, buf.size - pos)
                System.arraycopy(buf, pos, b, off, n)
                pos += n
                return n
            }

            override fun available() = buf.size - pos

            override fun close() {
                done = true
                scope.cancel()
            }
        }
    }

    private val END = ByteArray(0)
}
