package ilios

import ch.qos.logback.classic.Level
import kotlin.test.Test
import kotlin.test.assertEquals

class SyslogPriorityTest {
    @Test
    fun `each level gets its systemd priority and nothing is added outside systemd`() {
        assertEquals("<3>", syslogPrefix(Level.ERROR_INT, true))
        assertEquals("<4>", syslogPrefix(Level.WARN_INT, true))
        assertEquals("<6>", syslogPrefix(Level.INFO_INT, true))
        assertEquals("<7>", syslogPrefix(Level.DEBUG_INT, true))
        assertEquals("<7>", syslogPrefix(Level.TRACE_INT, true))
        assertEquals("", syslogPrefix(Level.ERROR_INT, false))
    }
}
