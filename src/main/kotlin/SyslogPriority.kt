package ilios

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * The syslog priority prefix that systemd reads from the start of a line (`<3>` error, `<4>` warning, `<6>` info,
 * `<7>` debug), so that `journalctl -p warning` works; empty when [underSystemd] is false (a terminal would show it).
 */
internal fun syslogPrefix(level: Int, underSystemd: Boolean): String = when {
    !underSystemd -> ""
    level >= Level.ERROR_INT -> "<3>"
    level >= Level.WARN_INT -> "<4>"
    level >= Level.INFO_INT -> "<6>"
    else -> "<7>"
}

/** logback's `%prio`: the prefix of the event's level; systemd sets JOURNAL_STREAM when output goes to the journal. */
class SyslogPriority : ClassicConverter() {
    internal var underSystemd = System.getenv("JOURNAL_STREAM") != null

    override fun convert(event: ILoggingEvent): String = syslogPrefix(event.level.toInt(), underSystemd)
}
