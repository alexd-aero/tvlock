package dev.alexaero.tvlock

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** Lines from the Box R's own `dumpsys hdmi_control` (11-17). */
class HdmiTest {
    private val log = """
    [S] time=2026-11-17 21:40:16 message=<Give Device Power Status> 40:8F (ACK)
    [R] time=2026-11-17 21:40:17 message=<Report Power Status> 04:90:00
    [S] time=2026-11-17 21:50:21 message=<User Control Pressed> 45:44 <Keycode type = Volume down> (ACK)
    [R] time=2026-11-17 22:34:12 message=<Standby> 0F:36
    [R] time=2026-11-17 22:34:13 message=<Standby> 5F:36
    [S] time=2026-11-17 22:40:00 message=<Standby> 40:36 (ACK)
    """
    private fun t(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)!!.time

    @Test fun tvSaysOnThenOff() {
        assertEquals("on", Hdmi.tvSaid(Hdmi.parseMessages(log, t("2026-11-17 21:40:10")).take(2)))
        assertEquals("off", Hdmi.tvSaid(Hdmi.parseMessages(log, t("2026-11-17 22:34:00"))))
    }

    @Test fun onlyRecentMessages() {
        val m = Hdmi.parseMessages(log, t("2026-11-17 22:39:59"))
        assertEquals(1, m.size)
        assertEquals("Standby", m[0].name); assertEquals(true, m[0].sent); assertEquals(true, m[0].acked)
        assertEquals(null, Hdmi.tvSaid(m)) // our own Standby isn't the TV talking
    }

    @Test fun soundbarIsNotTheTv() {
        val m = Hdmi.parseMessages("    [R] time=2026-11-17 22:34:13 message=<Standby> 5F:36\n", 0)
        assertEquals(null, Hdmi.tvSaid(m))
    }
}
