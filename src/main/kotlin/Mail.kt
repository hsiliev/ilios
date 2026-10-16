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

/** Gmail SMTP, configured by the MAIL_USERNAME (Gmail address) and MAIL_PASSWORD (Google app password) environment variables. */
class Mailer(
    private val user: String = System.getenv("MAIL_USERNAME")?.trim().orEmpty(),
    private val password: String = System.getenv("MAIL_PASSWORD")?.trim().orEmpty(),
) {
    private val host = "smtp.gmail.com"

    /** Without both settings mail is disabled and reset links go to the server log. */
    val enabled = user.isNotEmpty() && password.isNotEmpty()

    private val session: Session? = if (!enabled) null else {
        val props = Properties().apply {
            put("mail.smtp.host", host); put("mail.smtp.port", "587")
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
