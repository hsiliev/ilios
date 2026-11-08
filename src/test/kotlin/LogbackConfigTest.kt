package ilios

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.Status
import org.slf4j.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The shipped logback.xml, loaded into a fresh context the way the app loads it at start-up. */
class LogbackConfigTest {
    private fun configured(): LoggerContext {
        val context = LoggerContext()
        val config = assertNotNull(javaClass.getResource("/logback.xml"), "logback.xml is on the classpath")
        JoranConfigurator().also { it.context = context }.doConfigure(config)
        return context
    }

    private fun lineOf(context: LoggerContext, level: Level, message: String): String {
        val appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR") as ConsoleAppender<LoggingEvent>
        val encoder = appender.encoder as PatternLayoutEncoder
        val event = LoggingEvent(Logger::class.java.name, context.getLogger("ilios.Test"), level, message, null, null)
        return encoder.layout.doLayout(event)
    }

    @Test
    fun `the configuration loads without warnings or errors`() {
        val problems = configured().statusManager.copyOfStatusList.filter { it.level >= Status.WARN }
        assertTrue(problems.isEmpty(), "logback status: ${problems.map { it.message }}")
    }

    @Test
    fun `a line has the thread, the level, the logger and the message`() {
        val line = lineOf(configured(), Level.WARN, "hello")
        assertTrue(Regex("""\[.+] WARN  ilios\.Test - hello\n""").matches(line), line)
    }

    @Test
    fun `under systemd the line starts with the priority and not under a terminal`() {
        val context = configured()
        val converter = SyslogPriority()
        val warn = LoggingEvent(Logger::class.java.name, context.getLogger("ilios.Test"), Level.WARN, "x", null, null)
        converter.underSystemd = true
        assertEquals("<4>", converter.convert(warn))
        converter.underSystemd = false
        assertEquals("", converter.convert(warn))
    }

    @Test
    fun `Exposed only reports warnings and errors`() {
        val context = configured()
        assertEquals(Level.WARN, context.getLogger("Exposed").level)
        assertEquals(Level.INFO, context.getLogger(Logger.ROOT_LOGGER_NAME).level)
    }
}
