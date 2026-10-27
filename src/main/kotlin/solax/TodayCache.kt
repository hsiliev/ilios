package ilios.solax

import ilios.db.Loc
import ilios.db.SolarToday
import ilios.solax.api.RETRY_AFTER_MS
import ilios.solax.api.attempt
import ilios.solax.api.gridPowerOf
import ilios.solax.api.number
import ilios.solax.api.optionally
import ilios.solax.api.pvPowerOf
import ilios.solax.api.slot5
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** One kind of measurement per 5-minute slot ("HH:mm"); several devices at the same moment are combined when stored. */
private typealias Slots = MutableMap<String, MutableList<Double>>

private fun Slots.add(slot: String, value: Double) {
    getOrPut(slot) { ArrayList() } += value
}

/** Today's battery charge, battery power, panel power and grid power in 5-minute slots. */
private class TodaySeries {
    val soc: Slots = HashMap()
    val battery: Slots = HashMap()
    val pv: Slots = HashMap()
    val grid: Slots = HashMap()

    val slots get() = (soc.keys + pv.keys + battery.keys + grid.keys).sorted()
}

/** Today's chart data: the 5-minute battery charge and panel power from SolaX's history, kept in the database. */
internal class TodayCache(private val client: SolaxClient) {
    @Volatile private var viewedAt = 0L
    private val cachedAt = ConcurrentHashMap<Long, Long>()

    /**
     * Called by the report route: the dashboard polls it every minute while open, so a recent call means somebody is
     * looking.
     */
    fun viewed(now: Instant) {
        viewedAt = now.toEpochMilli()
    }

    /**
     * Keeps today's chart close to now: refreshes the history on every loop pass (10 minutes) while the dashboard is
     * open, unless the battery
     * read just did it; a failure waits an hour.
     */
    suspend fun refreshIfDue(loc: Loc, now: Instant) {
        val link = client.link(loc)?.takeIf { it.hasBattery } ?: return
        val watched = now.toEpochMilli() - viewedAt <= VIEWED_WITHIN_MS
        val recent = now.toEpochMilli() - (cachedAt[link.loc.id] ?: 0) < TODAY_EVERY_MS
        if (watched && !recent) cache(loc, now)
    }

    /**
     * Caches today's battery charge and panel power at the finest step SolaX's history allows (5 minutes, windows of
     * at most 12 hours, so
     * up to three calls per kind). The battery and inverter histories are merged by time and replace what was
     * stored. Never throws (except
     * cancellation); a failure keeps the previous data and waits an hour.
     */
    suspend fun cache(loc: Loc, now: Instant) {
        cachedAt[loc.id] = now.toEpochMilli()
        val link = client.link(loc) ?: return
        attempt(
            "today's history",
            loc.id,
            onSolaxError = { e ->
                System.err.println("SolaX today's history failed for place ${loc.id}: ${e.message}")
                backOff(loc.id, now)
            },
            onFailure = { backOff(loc.id, now) },
        ) {
            val sns = link.inverterSerials()
            if (sns.isEmpty()) return@attempt
            val day = now.atZone(link.zone).toLocalDate()
            val series = read(link, sns, day, now)
            if (series.slots.isNotEmpty()) store(loc.id, day, series)
        }
    }

    private fun backOff(locationId: Long, now: Instant) {
        cachedAt[locationId] = now.toEpochMilli() + RETRY_AFTER_MS - TODAY_EVERY_MS
    }

    private suspend fun read(link: SolaxLink, sns: List<String>, day: LocalDate, now: Instant): TodaySeries {
        val from = day.atStartOfDay(link.zone).toInstant()
        val series = TodaySeries()
        for ((m, p) in link.historyOfDay(sns, 2, day, from, now)) {
            p.number("batterySOC")?.let { series.soc.add(m.slot5(), it) }
            p.number("chargeDischargePower")?.let { series.battery.add(m.slot5(), it) }
        }
        // the panel bars are optional: without them the charge curve is still worth keeping
        optionally("inverter history", link.loc.id) {
            for ((m, p) in link.historyOfDay(sns, 1, day, from, now)) {
                pvPowerOf(p)?.let { series.pv.add(m.slot5(), it) }
                gridPowerOf(p)?.let { series.grid.add(m.slot5(), it) }
            }
        }
        return series
    }

    private fun store(locationId: Long, day: LocalDate, series: TodaySeries) {
        transaction {
            // yesterday's points stay for the 24-hour window; today's are replaced and older ones dropped
            SolarToday.deleteWhere {
                SolarToday.locationId eq locationId and
                    (SolarToday.day eq day.toString() or (SolarToday.day less day.minusDays(1).toString()))
            }
            SolarToday.batchInsert(series.slots) { t ->
                this[SolarToday.locationId] = locationId
                this[SolarToday.day] = day.toString()
                this[SolarToday.t] = t
                this[SolarToday.soc] = series.soc[t]?.average()
                this[SolarToday.pvW] = series.pv[t]?.sum() // several inverters add up
                this[SolarToday.batW] = series.battery[t]?.sum() // several batteries add up
                this[SolarToday.gridW] = series.grid[t]?.sum()
            }
        }
    }
}
