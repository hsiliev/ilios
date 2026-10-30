package ilios.solax

import ilios.db.DischargeDays
import ilios.db.Loc
import ilios.db.Locations
import ilios.historyStart
import ilios.report.measuredChargeEffPct
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
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * The 5-minute history of the finished days since the Monday of last week: the battery's discharge power, the home's
 * consumption and the
 * power drawn from the grid (battery and inverter history). The discharge feeds the estimate of the rest of today.
 * Missing days are fetched
 * [HISTORY_DAYS_PER_PASS] per pass (four requests per day), failures are retried at most hourly.
 */
internal class DischargeHistory(private val client: SolaxClient) {
    private val tryAt = ConcurrentHashMap<Long, Long>()

    /** Fetches the days still missing. Never throws (except cancellation). */
    suspend fun cacheIfDue(loc: Loc, now: Instant) {
        val link = client.link(loc)?.takeIf { it.hasBattery } ?: return
        if (tryAt[loc.id] ?: 0L > now.toEpochMilli()) return
        val missing = missingDays(loc.id, now.atZone(link.zone).toLocalDate())
        if (missing.isEmpty()) return
        tryAt[loc.id] = now.toEpochMilli() + RETRY_AFTER_MS
        attempt(
            "discharge history",
            loc.id,
            onSolaxError = { e ->
                System.err.println("SolaX discharge history failed for place ${loc.id}: ${e.message}")
                tryAt[loc.id] = now.toEpochMilli() + e.retryMs
            },
        ) {
            val sns = link.inverterSerials()
            if (sns.isEmpty()) return@attempt
            val done = missing.all { cacheDay(link, sns, it) }
            measureChargeEfficiency(link)
            if (done) tryAt.remove(loc.id) // else each day once; afterwards one new day a day
        }
    }

    /** The finished days of the window that are not stored yet (older stored days are dropped). */
    private fun missingDays(locationId: Long, today: LocalDate): List<LocalDate> {
        val first = historyStart(today)
        val have = transaction {
            DischargeDays.deleteWhere {
                DischargeDays.locationId eq locationId and
                    (DischargeDays.day less first.toString())
            }
            DischargeDays.selectAll().where {
                DischargeDays.locationId eq locationId
            }.map { it[DischargeDays.day] }.toSet()
        }
        return generateSequence(first) {
            it.plusDays(1)
        }.takeWhile { it < today }.filter { it.toString() !in have }.take(HISTORY_DAYS_PER_PASS).toList()
    }

    /** Stores one day. False when SolaX has no battery data for it (yet): the pass stops and tries again later. */
    private suspend fun cacheDay(link: SolaxLink, sns: List<String>, day: LocalDate): Boolean {
        val from = day.atStartOfDay(link.zone).toInstant()
        val to = day.plusDays(1).atStartOfDay(link.zone).toInstant()
        val battery = HashMap<LocalDateTime, Double>() // charging power, several batteries add up (positive = charging)
        val soc = HashMap<LocalDateTime, MutableList<Double>>() // several batteries are averaged
        for ((m, p) in link.historyOfDay(sns, 2, day, from, to)) {
            p.number("chargeDischargePower")?.let {
                battery.merge(m, it, Double::plus)
            }
            p.number("batterySOC")?.let { soc.getOrPut(m) { ArrayList() } += it }
        }
        if (battery.isEmpty()) return false
        val pv = HashMap<LocalDateTime, Double>()
        val grid = HashMap<LocalDateTime, Double>() // several inverters add up
        // consumption and grid power are optional: the discharge is still worth keeping
        optionally("inverter history (consumption)", link.loc.id) {
            for ((m, p) in link.historyOfDay(sns, 1, day, from, to)) {
                pvPowerOf(p)?.let { pv.merge(m, it, Double::plus) }
                gridPowerOf(p)?.let { grid.merge(m, it, Double::plus) }
            }
        }
        val socSlots = soc.entries.groupBy({ it.key.slot5() }, { it.value.average() })
        store(link.loc.id, day, battery, grid, socSlots, consumptionOf(battery, pv, grid))
        return true
    }

    /** Measures the charging efficiency on the stored nights; an earlier figure stays when they have no charging. */
    private fun measureChargeEfficiency(link: SolaxLink) {
        val capacity = SolaxRepository.plant(link.loc.id)?.batteryKwh?.takeIf { it > 0 } ?: return
        val pct = measuredChargeEffPct(SolaxReadings.chargePoints(link.loc.id), capacity, link.zone) ?: return
        transaction { Locations.update({ Locations.id eq link.loc.id }) { it[chargeEffMeasured] = pct } }
    }

    @Suppress("LongParameterList")
    private fun store(
        locationId: Long,
        day: LocalDate,
        battery: Map<LocalDateTime, Double>,
        grid: Map<LocalDateTime, Double>,
        soc: Map<String, List<Double>>,
        loads: Map<String, Double>,
    ) {
        val slots = battery.entries.groupBy({ it.key.slot5() }, { it.value }).toSortedMap()
        val gridSlots = grid.entries.groupBy({ it.key.slot5() }, { it.value })
        transaction {
            DischargeDays.deleteWhere {
                DischargeDays.locationId eq locationId and
                    (DischargeDays.day eq day.toString())
            }
            DischargeDays.batchInsert(slots.entries) { (t, w) ->
                this[DischargeDays.locationId] = locationId
                this[DischargeDays.day] = day.toString()
                this[DischargeDays.t] = t
                this[DischargeDays.dischargeW] = w.map { maxOf(0.0, -it) }.average()
                this[DischargeDays.chargeW] = w.map { maxOf(0.0, it) }.average()
                this[DischargeDays.soc] = soc[t]?.average()
                this[DischargeDays.loadW] = loads[t]
                this[DischargeDays.gridW] = gridSlots[t]?.average()
            }
        }
    }
}

/**
 * SolaX reports no consumption of its own: the house uses what the panels make, what the battery gives and what the
 * grid supplies, so
 * consumption = panel power - battery charging power + grid import, per 5-minute slot, for the moments that have
 * grid data.
 */
internal fun consumptionOf(
    battery: Map<LocalDateTime, Double>,
    pv: Map<LocalDateTime, Double>,
    grid: Map<LocalDateTime, Double>,
): Map<String, Double> = grid.entries.mapNotNull { (m, g) ->
    battery[m]?.let { b ->
        m.slot5() to
            maxOf(0.0, (pv[m] ?: 0.0) - b + g)
    }
}.toMap()
