package ilios.solax.api

import kotlinx.serialization.json.Json
import java.net.URI

// SolaX Cloud developer API (https://developer.solaxcloud.com): the API has call-count and rate limits, so there is
// no polling.
// Produced energy of a past day never changes, so each day is fetched once (on connecting, then the new day every
// morning);
// the battery charge is read hourly and cached in the database. Everything is served from the database.

internal const val OK = 10_000
internal const val RATE_LIMITED = 10_405
internal const val TOKEN_REJECTED = 10_402
internal val QUOTA = mapOf(
    RATE_LIMITED to "Лимитът на заявките към SolaX е изчерпан – ще опитам отново по-късно",
    10_406 to "Достигнат е лимитът на честота на заявките към SolaX – ще опитам отново по-късно",
)
internal val NO_ACCESS = setOf(10_403, 10_500, 10_505, 10_506)
internal fun quotaError(code: Int) = SolaxError(
    QUOTA.getValue(code),
    if (code == RATE_LIMITED) {
        QUOTA_RETRY_MS
    } else {
        RETRY_AFTER_MS
    },
)

/** A failure with a message that is safe to show the user (never contains credentials). */
class SolaxError(message: String, val retryMs: Long = RETRY_AFTER_MS, cause: Throwable? = null) :
    Exception(message, cause)
internal class TokenRejected : Exception()

/**
 * SolaX API host of the account's region (see "My Account" in the developer portal); EU by default, override with
 * SOLAX_URL.
 */
fun solaxBaseUrl(value: String? = null): String {
    val v = value?.trim().orEmpty().ifEmpty { "https://openapi-eu.solaxcloud.com" }
    val uri = runCatching { URI(v) }.getOrNull()
    require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
        "SOLAX_URL must be an http(s) URL, got '$v'"
    }
    return v.trimEnd('/')
}

// the call allowance is used up: it will not recover within the hour
internal const val QUOTA_RETRY_MS = 6L * 60 * 60 * 1000

// wrong credentials or no access: retrying only burns calls until the user fixes them
internal const val AUTH_RETRY_MS = 12L * 60 * 60 * 1000

internal const val RETRY_AFTER_MS = 60L * 60 * 1000 // ordinary failures: try again in an hour

internal val plantsJson = Json { ignoreUnknownKeys = true }

/** One past day of battery history: the highest charge reached. */
class BatteryDayResult(val maxSoc: Double, val raw: String? = null)
