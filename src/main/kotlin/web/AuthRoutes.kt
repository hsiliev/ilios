package ilios.web

import ilios.BASE_URL
import ilios.HttpError
import ilios.auth.AuthService
import ilios.auth.Mailer
import ilios.badRequest
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private const val MIN_PASSWORD = 8
private const val MAX_PASSWORD = 200

private val EMAIL_RE = Regex("""^[^@\s]{1,64}@[^@\s]+\.[^@\s]+$""")

private fun resetMessage(link: String) = """
    Задайте нова парола (връзката е валидна 1 час):

    $link

    Ако не сте поискали това, игнорирайте писмото.
""".trimIndent()

private fun checkPassword(password: String) {
    if (password.length !in MIN_PASSWORD..MAX_PASSWORD) {
        badRequest("Паролата трябва да е между $MIN_PASSWORD и $MAX_PASSWORD символа")
    }
}

/** Registration, login, logout, password reset and the signed-in user. */
fun Route.authRoutes(auth: AuthService, mailer: Mailer) {
    post("/register") {
        val c = call.receive<Credentials>()
        val email = c.email.trim().lowercase()
        if (!EMAIL_RE.matches(email)) badRequest("Невалиден имейл")
        checkPassword(c.password)
        auth.register(email, c.password) ?: badRequest("Този имейл вече е регистриран")
        call.startSession(auth.login(email, c.password)!!)
        call.respond(HttpStatusCode.Created, Msg("Регистрацията е успешна"))
    }
    post("/login") {
        val c = call.receive<Credentials>()
        val token = auth.login(c.email.trim().lowercase(), c.password)
            ?: throw HttpError(HttpStatusCode.Unauthorized, "Грешен имейл или парола")
        call.startSession(token)
        call.respond(Msg("OK"))
    }
    post("/logout") {
        call.request.cookies[COOKIE]?.let(auth::logout)
        call.response.cookies.append(Cookie(COOKIE, "", path = "/", maxAge = 0))
        call.respond(Msg("OK"))
    }
    post("/forgot") {
        val email = call.receive<EmailReq>().email.trim().lowercase()
        auth.createReset(email)?.let { token ->
            val link = "$BASE_URL/#reset=$token"
            if (mailer.enabled) {
                // never reveal failures to the requester (would leak whether the address exists)
                runCatching {
                    mailer.send(
                        email,
                        "Слънце – възстановяване на парола",
                        resetMessage(link),
                    )
                }
                    .onFailure { call.application.environment.log.error("Reset mail failed: ${it.message}") }
            } else {
                println("PASSWORD RESET for $email: $link (SMTP not configured)")
            }
        }
        call.respond(Msg("Ако адресът е регистриран, изпратихме връзка за възстановяване на паролата."))
    }
    post("/reset") {
        val r = call.receive<ResetReq>()
        checkPassword(r.password)
        if (!auth.reset(r.token, r.password)) badRequest("Връзката за възстановяване е невалидна или е изтекла")
        call.respond(Msg("Паролата е сменена"))
    }
    get("/me") {
        call.respond(Me(auth.email(call.userId(auth))))
    }
}
