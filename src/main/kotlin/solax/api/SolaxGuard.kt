package ilios.solax.api

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Runs [block] so that nothing escapes except cancellation: a [SolaxError] goes to [onSolaxError] (its message is
 * safe to show), any other
 * failure is logged by class name only (never the message, which could hold credentials) and goes to [onFailure].
 */
internal suspend fun attempt(
    what: String,
    locationId: Long,
    onSolaxError: (SolaxError) -> Unit = {},
    onFailure: () -> Unit = {},
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SolaxError) {
        log.warn { "SolaX $what failed for place $locationId: ${e.message}" }
        onSolaxError(e)
    } catch (e: Exception) {
        log.warn { "SolaX $what failed for place $locationId: ${e::class.simpleName}" }
        onFailure()
    }
}

/** Like [attempt] for a step whose failure only costs some optional data: it is logged and the caller goes on. */
internal suspend fun optionally(what: String, locationId: Long, block: suspend () -> Unit) =
    attempt(what, locationId, block = block)
