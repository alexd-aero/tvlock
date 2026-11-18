package dev.alexaero.tvlock

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/** HDMI-CEC state of this box, read from `dumpsys hdmi_control`. */
object Hdmi {
    private val active = Regex("""isActiveSource\(\):\s*(true|false)""")
    private val msg = Regex("""\[([SR])\] time=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) message=<([^>]+)> ([0-9A-Fa-f:]+)(.*)""")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Last known: is the TV showing this box? Updated by the lock service while the screen is on. */
    @Volatile var lastActive: Boolean? = null

    private fun dump(ctx: Context?): String? {
        val direct = runCatching {
            val p = ProcessBuilder("dumpsys", "hdmi_control").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text.takeIf { it.contains("isActiveSource") || it.contains("message=") }
        }.getOrNull()
        return direct ?: ctx?.let { SelfAdb.shell(it, "dumpsys hdmi_control") }
    }

    /** Is the TV showing this box's input right now? null if unknown (no permission, no CEC). */
    fun isActiveSource(ctx: Context? = null): Boolean? =
        dump(ctx)?.let { active.find(it)?.groupValues?.get(1)?.toBooleanStrict() }.also { if (it != null) lastActive = it }

    data class Msg(val sent: Boolean, val at: Long, val name: String, val bytes: String, val acked: Boolean)

    /** HDMI-CEC messages since [sinceMs] (the box's own message log). */
    fun messagesSince(ctx: Context, sinceMs: Long): List<Msg> = dump(ctx)?.let { parseMessages(it, sinceMs) } ?: emptyList()

    fun parseMessages(text: String, sinceMs: Long): List<Msg> {
        return msg.findAll(text).mapNotNull { m ->
            val at = runCatching { fmt.parse(m.groupValues[2])!!.time }.getOrNull() ?: return@mapNotNull null
            if (at < sinceMs - 1500) return@mapNotNull null
            Msg(m.groupValues[1] == "S", at, m.groupValues[3], m.groupValues[4].uppercase(), m.groupValues[5].contains("(ACK)"))
        }.toList()
    }

    /** From the log: did the TV say it's in standby (or on)? "off", "on" or null. */
    fun tvSaid(msgs: List<Msg>): String? {
        // The TV is logical address 0: messages from it start with "0".
        for (m in msgs.reversed()) {
            if (m.sent || !m.bytes.startsWith("0")) continue
            if (m.name == "Standby") return "off"
            if (m.name == "Report Power Status") return when (m.bytes.substringAfterLast(":")) { "00", "03" -> "on"; "01", "02" -> "off"; else -> null }
            if (m.name == "Active Source" || m.name == "Set Stream Path" || m.name == "Routing Change") return "on"
        }
        return null
    }
}
