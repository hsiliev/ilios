package ilios.solax

import ilios.db.Loc
import ilios.db.Solax
import ilios.solax.api.SolaxSession
import ilios.solax.api.momentOf
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Opens [SolaxSession]s and finds the connection of a place. */
internal class SolaxClient(private val http: HttpClient, private val baseUrl: String) {
    fun session(locationId: Long, row: ResultRow) = SolaxSession(http, baseUrl, locationId, row)

    /**
     * The place's connection, or null while it has none or its plant is not known yet (the production sync has to
     * find the plant first).
     */
    fun link(loc: Loc): SolaxLink? {
        val row = SolaxRepository.row(loc.id) ?: return null
        val plantId = row[Solax.plantId] ?: return null
        val type = row[Solax.businessType] ?: return null
        return SolaxLink(loc, row, session(loc.id, row), plantId, type)
    }
}

/** What a call for one place needs: its stored row, a session and the plant it is connected to. */
internal class SolaxLink(
    val loc: Loc,
    val row: ResultRow,
    val session: SolaxSession,
    val plantId: String,
    val type: Int,
) {
    val zone: ZoneId get() = ZoneId.of(loc.tz)
    val hasBattery: Boolean get() = SolaxRepository.hasBattery(row)

    /** Serial numbers of the plant's inverters, looked up once and cached. */
    suspend fun inverterSerials(): List<String> {
        val cached = row[Solax.inverterSns]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        return cached.ifEmpty {
            session.inverters(plantId, type).also { found ->
                SolaxRepository.update(loc.id) { it[Solax.inverterSns] = found.joinToString(",").ifEmpty { null } }
            }
        }
    }

    /**
     * The inverter or battery history points (see [SolaxSession.history]) that fall on [day], with their local
     * moment.
     */
    suspend fun historyOfDay(
        sns: List<String>,
        deviceType: Int,
        day: LocalDate,
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<Pair<LocalDateTime, JsonObject>> = session.history(sns, type, deviceType, from, to).mapNotNull { p ->
        momentOf(p, zone)?.takeIf { it.toLocalDate() == day }?.let { it to p }
    }
}
