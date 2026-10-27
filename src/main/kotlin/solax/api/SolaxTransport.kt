package ilios.solax.api

import ilios.db.Solax
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val MAX_ERROR_TEXT = 120

/** A decoded SolaX answer with the raw text it came from. */
internal class Answer<T : SolaxReply>(val reply: T, val text: String)

private const val TOKEN_SAFETY_SECONDS = 3600L

/**
 * The calls to SolaX Cloud for one place: holds the access token and its renewal, and checks the result code of every
 * answer.
 */
internal class SolaxTransport(
    val http: HttpClient,
    val baseUrl: String,
    private val locationId: Long,
    private val row: ResultRow,
) {
    private var token: String? = row[Solax.token]?.takeIf { row[Solax.tokenExpires] > System.currentTimeMillis() }

    private suspend fun newToken(): String {
        val r = http.submitForm(
            "$baseUrl/openapi/auth/oauth/token",
            parameters {
                append("client_id", row[Solax.clientId])
                append("client_secret", row[Solax.clientSecret])
                append("grant_type", "client_credentials")
            },
        ).body<TokenResp>()
        val result = r.result
        if (r.code != 0 ||
            result == null
        ) {
            throw if (r.code in
                QUOTA
            ) {
                quotaError(r.code)
            } else {
                SolaxError("SolaX отказа достъпа – проверете Client ID и Secret", AUTH_RETRY_MS)
            }
        }
        val expires = System.currentTimeMillis() + maxOf(0L, result.expiresIn - TOKEN_SAFETY_SECONDS) * 1000
        transaction {
            Solax.update({ Solax.locationId eq locationId }) {
                it[Solax.token] = result.accessToken
                it[Solax.tokenExpires] = expires
            }
        }
        token = result.accessToken
        return result.accessToken
    }

    /** Runs an authenticated call; if SolaX rejects the cached token, gets a fresh one and retries once. */
    suspend fun <T> authed(call: suspend (String) -> T): T = try {
        call(token ?: newToken())
    } catch (expected: TokenRejected) { // the cached token is stale
        try {
            call(newToken())
        } catch (again: TokenRejected) {
            throw SolaxError("SolaX отхвърли достъпа – проверете Client ID и Secret", AUTH_RETRY_MS, cause = again)
        }
    }

    /** GET `openapi/v2/[path]` with [params]; the answer's result code is checked and the body decoded. */
    suspend inline fun <reified T : SolaxReply> get(path: String, vararg params: Pair<String, Any>): Answer<T> =
        authed { bearer ->
            val text = http.get("$baseUrl/openapi/v2/$path") {
                header("Authorization", "bearer $bearer")
                params.forEach { (name, value) -> parameter(name, value) }
            }.bodyAsText()
            Answer(decode<T>(text), text)
        }

    /** POST [body] as JSON to `openapi/v2/[path]`; the answer's result code is checked and the body decoded. */
    suspend inline fun <reified T : SolaxReply> post(path: String, body: JsonObject): Answer<T> = authed { bearer ->
        val text = http.post("$baseUrl/openapi/v2/$path") {
            header("Authorization", "bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyAsText()
        Answer(decode<T>(text), text)
    }

    /** Decodes an answer and throws for a result code other than [OK] (a rejected token is retried by [authed]). */
    inline fun <reified T : SolaxReply> decode(text: String): T =
        plantsJson.decodeFromString<T>(text).also { check(it) }

    fun check(reply: SolaxReply) {
        errorFor(reply.code, reply.message)?.let { throw it }
    }

    private fun errorFor(code: Int, message: String?): Exception? = when {
        code == OK -> null
        code == TOKEN_REJECTED -> TokenRejected()
        code in QUOTA -> quotaError(code)
        code in NO_ACCESS -> SolaxError("Нямате достъп до тази централа в SolaX (код $code)", AUTH_RETRY_MS)
        else -> SolaxError("SolaX върна грешка $code" + (message?.let { ": ${it.take(MAX_ERROR_TEXT)}" } ?: ""))
    }
}
