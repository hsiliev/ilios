package ilios.solax

import ilios.db.BatteryDays
import ilios.db.Loc
import ilios.db.Solax
import ilios.historyStart
import ilios.solax.api.BatteryDayResult
import ilios.solax.api.RETRY_AFTER_MS
import ilios.solax.api.attempt
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate

/**
 * Finds out, one past day at a time, how full the battery got: the day's battery history (SolaX allows at most 12
 * hours per request, so two
 * requests) gives the highest charge. Each day is fetched once; a backlog (a new connection, or the app being off)
 * is worked off one day per
 * call so the burst stays small, and failures are retried at most hourly.
 */
internal class BatteryDayHistory(private val client: SolaxClient) {
    /** Fetches the next missing day, if any. Never throws (except cancellation). */
    suspend fun cacheIfDue(loc: Loc, now: Instant) {
        val link = client.link(loc)?.takeIf { it.hasBattery } ?: return // no plant yet, or no battery
        val tried = link.row[Solax.historyTryAt]
        if (tried != null && tried > now.toEpochMilli()) return // tried = the earliest time of the next attempt
        val local = now.atZone(link.zone)
        val first = historyStart(local.toLocalDate())
        val day = maxOf(first, link.row[Solax.batteryDaysThrough]?.let(LocalDate::parse)?.plusDays(1) ?: first)
        if (day > lastProductionDay(local)) return
        SolaxRepository.update(loc.id) { it[Solax.historyTryAt] = now.toEpochMilli() + RETRY_AFTER_MS }
        attempt(
            "battery history",
            loc.id,
            onSolaxError = { e ->
                System.err.println("SolaX battery history failed for place ${loc.id}: ${e.message}")
                SolaxRepository.update(loc.id) { it[Solax.historyTryAt] = now.toEpochMilli() + e.retryMs }
            },
        ) {
            val sns = link.inverterSerials()
            store(loc.id, day, if (sns.isEmpty()) null else link.session.batteryDay(sns, link.type, day, link.zone))
        }
    }

    private fun store(locationId: Long, day: LocalDate, result: BatteryDayResult?) {
        transaction {
            BatteryDays.deleteWhere { BatteryDays.locationId eq locationId and (BatteryDays.day eq day.toString()) }
            if (result != null) {
                BatteryDays.insert {
                    it[BatteryDays.locationId] = locationId
                    it[BatteryDays.day] = day.toString()
                    it[BatteryDays.maxSoc] = result.maxSoc
                }
            }
            Solax.update({ Solax.locationId eq locationId }) {
                it[Solax.batteryDaysThrough] = day.toString()
                it[Solax.historyTryAt] = null
            }
        }
    }
}
