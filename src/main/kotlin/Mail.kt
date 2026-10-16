package ilios

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/** SMTP port from MAIL_PORT (default 587, as in Hermes); invalid values fail at start-up. */
fun mailPortFrom(value: String?): Int {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return 587
    return v.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("MAIL_PORT must be a number between 1 and 65535, got '$v'")
}

/**
 * SMTP (Gmail by default), configured by MAIL_USERNAME (sender address) and MAIL_PASSWORD (e.g. a Google app password);
 * MAIL_HOST (default smtp.gmail.com) and MAIL_PORT (default 587) are optional.
 */
class Mailer(
    private val user: String = System.getenv("MAIL_USERNAME")?.trim().orEmpty(),
    private val password: String = System.getenv("MAIL_PASSWORD")?.trim().orEmpty(),
    private val host: String = System.getenv("MAIL_HOST")?.trim().orEmpty().ifEmpty { "smtp.gmail.com" },
    private val port: Int = mailPortFrom(System.getenv("MAIL_PORT")),
) {

    /** Without both settings mail is disabled and reset links go to the server log. */
    val enabled = user.isNotEmpty() && password.isNotEmpty()

    private val session: Session? = if (!enabled) null else {
        val props = Properties().apply {
            put("mail.smtp.host", host); put("mail.smtp.port", port.toString())
            put("mail.smtp.connectiontimeout", "10000"); put("mail.smtp.timeout", "10000"); put("mail.smtp.writetimeout", "10000")
            put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.checkserveridentity", "true"); put("mail.smtp.auth", "true")
        }
        Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
        })
    }

    suspend fun send(to: String, subject: String, body: String) {
        val s = session ?: error("SMTP not configured")
        withContext(Dispatchers.IO) {
            val m = MimeMessage(s).apply {
                setFrom(InternetAddress(user))
                setRecipient(Message.RecipientType.TO, InternetAddress(to))
                setSubject(subject, "UTF-8"); setText(body, "UTF-8")
            }
            m.saveChanges(); Transport.send(m, m.allRecipients)
        }
    }
}
