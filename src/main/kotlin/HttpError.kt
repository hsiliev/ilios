package ilios

import io.ktor.http.HttpStatusCode

/** A failure that is answered with [status] and a message the user may see. */
class HttpError(val status: HttpStatusCode, message: String) : Exception(message)

/** Fails the request with 400 and a message for the user. */
fun badRequest(message: String): Nothing = throw HttpError(HttpStatusCode.BadRequest, message)
