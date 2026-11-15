package dev.alexaero.tvlock

/** HDMI-CEC state of this box, read from `dumpsys hdmi_control`. */
object Hdmi {
    private val active = Regex("""isActiveSource\(\):\s*(true|false)""")

    /** Is the TV showing this box's input right now? null if unknown (no permission, no CEC). */
    fun isActiveSource(): Boolean? = runCatching {
        val p = ProcessBuilder("dumpsys", "hdmi_control").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        active.find(text)?.groupValues?.get(1)?.toBooleanStrict()
    }.getOrNull()
}
