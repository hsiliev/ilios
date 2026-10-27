package ilios.web

import ilios.HTTPS
import ilios.HttpError
import ilios.auth.AuthService
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

internal const val COOKIE = "sid"
private const val SESSION_COOKIE_SECONDS = 14 * 24 * 3600

/** The id of the signed-in user; answers 401 without a valid session cookie. */
internal fun ApplicationCall.userId(auth: AuthService): Long = request.cookies[COOKIE]?.let(auth::userFor)
    ?: throw HttpError(HttpStatusCode.Unauthorized, "Не сте влезли в профила си")

internal fun ApplicationCall.startSession(token: String) = response.cookies.append(
    Cookie(
        COOKIE,
        token,
        httpOnly = true,
        secure = HTTPS,
        path = "/",
        maxAge = SESSION_COOKIE_SECONDS,
        extensions = mapOf(
            "SameSite" to "Lax",
        ),
    ),
)

/** The `{id}` path parameter, or 400. */
internal fun ApplicationCall.idParameter(): Long =
    parameters["id"]?.toLongOrNull() ?: ilios.badRequest("Невалиден идентификатор")
