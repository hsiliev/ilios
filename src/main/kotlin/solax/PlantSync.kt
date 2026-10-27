package ilios.solax

import ilios.HOURS_PER_DAY
import ilios.db.Loc
import ilios.db.Produced
import ilios.db.Solax
import ilios.historyStart
import ilios.report.bg
import ilios.solax.api.PlantRec
import ilios.solax.api.RETRY_AFTER_MS
import ilios.solax.api.SolaxError
import ilios.solax.api.SolaxSession
import ilios.solax.api.StatRow
import ilios.solax.api.plantsJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The plant a place is connected to, as read from SolaX or from what is stored; [fromCache] when no call was needed,
 * [wiped] when the stored history was dropped.
 */
private class PlantRecord(
    val plantId: String,
    val plantName: String?,
    val pvKwp: Double?,
    val type: Int,
    val deviceKwh: Double?,
    val diagDevices: String?,
    val fromCache: Boolean,
    val wiped: Boolean = false,
)

/** The days whose production still has to be fetched; empty when [start] is after [end]. */
private class Period(val start: LocalDate, val end: LocalDate) {
    val hasDays get() = start <= end
}

private class DayProduction(val date: LocalDate, val kwh: Double, val acKwh: Double?, val loadKwh: Double?)

/**
 * The production sync of one place: the plant record (name, PV power, battery capacity) and the energy of the days
 * not fetched yet.
 */
internal object PlantSync {
    private const val DIAG_LIMIT = 1500
    private const val STATUS_LIMIT = 400

    /**
     * Runs one sync and returns the text for the status line. Throws [SolaxError]; the caller turns failures into
     * status texts.
     */
    suspend fun run(loc: Loc, row: ResultRow, session: SolaxSession, now: Instant): String {
        val record = cachedRecord(row, now) ?: readRecord(loc, row, session)
        val period = periodToFetch(loc, row, now, record.wiped)
        val (type, fresh) = readProduction(session, record, period)
        store(loc.id, record, type, period, fresh, now)
        val through = if (period.hasDays) {
            period.end
        } else {
            row[Solax.producedThrough]?.let(LocalDate::parse)
                ?: period.end
        }
        return summary(record, through)
    }

    /** Sets the status text of a finished sync and when the next one may run. */
    fun finish(locationId: Long, status: String, dueAt: Long) = SolaxRepository.update(locationId) {
        it[Solax.status] = status.take(STATUS_LIMIT)
        it[Solax.dueAt] = dueAt
    }

    /**
     * The plant record is read when the place is first connected and then about weekly: it hardly ever changes and
     * every call counts against
     * SolaX's quota. Null when it has to be read now.
     */
    private fun cachedRecord(row: ResultRow, now: Instant): PlantRecord? {
        val plantId = row[Solax.plantId] ?: return null
        val type = row[Solax.businessType] ?: return null
        val hasCapacity = row[Solax.batteryDeviceKwh] ?: 0.0 > 0
        val maxAge = if (hasCapacity) PLANT_REFRESH_MS else RETRY_AFTER_MS // no capacity yet: look again hourly
        val fresh = row[Solax.plants] != null &&
            row[Solax.pvKwp] ?: 0.0 > 0 &&
            (row[Solax.batteryDeviceKwh] != null || row[Solax.diagDevices] != null) &&
            row[Solax.plantReadAt] ?: 0L > now.toEpochMilli() - maxAge
        if (!fresh) return null
        return PlantRecord(
            plantId,
            row[Solax.plantName],
            row[Solax.pvKwp],
            type,
            row[Solax.batteryDeviceKwh],
            row[Solax.diagDevices],
            fromCache = true,
        )
    }

    private suspend fun readRecord(loc: Loc, row: ResultRow, session: SolaxSession): PlantRecord {
        val (type, all) = session.plants(row[Solax.businessType])
        // the list is saved first so the dropdown works even if the selected plant turns out to be missing
        val options = all.map { PlantOption(it.plantId.content, it.plantName ?: it.plantId.content) }
        SolaxRepository.update(loc.id) {
            it[Solax.plants] = plantsJson.encodeToString(ListSerializer(PlantOption.serializer()), options)
            it[Solax.businessType] = type
        }
        val (chosen, wiped) = choosePlant(loc.id, all, row[Solax.plantId], listWasLoaded = row[Solax.plants] != null)
        val plantId = chosen.plantId.content
        val devices = batteryCapacity(session, plantId, type)
        val diag = if (devices?.first == null) devices?.second ?: "заявката не успя" else null
        return PlantRecord(
            plantId,
            chosen.plantName,
            chosen.pvCapacity,
            type,
            devices?.first,
            diag,
            fromCache = false,
            wiped = wiped,
        )
    }

    /**
     * The batteries' own rated capacity (from the device list); null when the request fails, which must not stop the
     * production sync.
     */
    private suspend fun batteryCapacity(session: SolaxSession, plantId: String, type: Int): Pair<Double?, String>? =
        try {
            session.batteryCapacity(plantId, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("SolaX device list failed: ${e::class.simpleName}")
            null
        }

    /** The selected plant, and whether the stored history had to be wiped because the account no longer has it. */
    private fun choosePlant(
        locationId: Long,
        all: List<PlantRec>,
        known: String?,
        listWasLoaded: Boolean,
    ): Pair<PlantRec, Boolean> {
        val match = known?.let { k -> all.firstOrNull { it.plantId.content == k } }
        return when {
            // same plant as before: everything stored for it stays valid
            match != null -> {
                match to false
            }
            // the first plant is the default
            known == null -> {
                all.first() to false
            }
            // new credentials led to an account without the old plant: start over with its first plant
            !listWasLoaded -> {
                SolaxConnection.wipePlantHistory(locationId)
                all.first() to true
            }
            else -> {
                throw SolaxError("Централа $known не е намерена в акаунта в SolaX – изберете друга от списъка")
            }
        }
    }

    /**
     * Production of a past day never changes, so each day is fetched once: from the day after the last fetched one
     * (or the start of the charts' window on a new connection) up to the last final day.
     */
    private fun periodToFetch(loc: Loc, row: ResultRow, now: Instant, wiped: Boolean): Period {
        val local = now.atZone(java.time.ZoneId.of(loc.tz))
        val windowStart = historyStart(local.toLocalDate())
        val fetchedThrough = if (wiped) null else row[Solax.producedThrough]?.let(LocalDate::parse)
        return Period(maxOf(windowStart, fetchedThrough?.plusDays(1) ?: windowStart), lastProductionDay(local))
    }

    /**
     * One statistics call per month involved. Returns the business type SolaX answered for and the days it has
     * production for.
     */
    private suspend fun readProduction(
        session: SolaxSession,
        record: PlantRecord,
        period: Period,
    ): Pair<Int, List<DayProduction>> {
        if (!period.hasDays) return record.type to emptyList()
        var type = record.type
        val rows = ArrayList<StatRow>()
        for (month in generateSequence(YearMonth.from(period.start)) { it.plusMonths(1) }.takeWhile {
            it <=
                YearMonth.from(period.end)
        }) {
            val (usedType, list) = session.statistics(record.plantId, type, month.toString())
            type = usedType
            rows += list
        }
        val fresh = rows.mapNotNull { r ->
            r.pvGeneration?.let {
                DayProduction(LocalDate.parse(r.date), it, r.inverterACOutputEnergy, r.loadConsumption)
            }
        }
        return type to fresh.filter { it.date in period.start..period.end }
    }

    private fun store(
        locationId: Long,
        record: PlantRecord,
        type: Int,
        period: Period,
        fresh: List<DayProduction>,
        now: Instant,
    ) {
        val pvKwp = record.pvKwp
        transaction {
            if (period.hasDays) {
                Produced.deleteWhere {
                    Produced.locationId eq locationId and (Produced.day greaterEq period.start.toString()) and
                        (Produced.day lessEq period.end.toString())
                }
                Produced.batchInsert(fresh) { d ->
                    this[Produced.locationId] = locationId
                    this[Produced.day] = d.date.toString()
                    this[Produced.kwh] = d.kwh
                    this[Produced.acKwh] = d.acKwh
                    this[Produced.loadKwh] = d.loadKwh
                    // more than the panels can make in a day
                    this[Produced.bad] = pvKwp != null && pvKwp > 0 && d.kwh > pvKwp * HOURS_PER_DAY
                }
            }
            Solax.update({ Solax.locationId eq locationId }) {
                it[Solax.plantId] = record.plantId
                it[Solax.plantName] = record.plantName
                it[Solax.businessType] = type
                if (!record.fromCache) {
                    it[Solax.pvKwp] = pvKwp
                    it[Solax.batteryDeviceKwh] = record.deviceKwh
                    it[Solax.diagDevices] = record.diagDevices?.take(DIAG_LIMIT)
                    it[Solax.plantReadAt] = now.toEpochMilli()
                }
                if (period.hasDays) it[Solax.producedThrough] = period.end.toString()
            }
        }
    }

    private fun summary(record: PlantRecord, through: LocalDate): String {
        val bg = Locale.forLanguageTag("bg")
        val specs = listOfNotNull(
            record.pvKwp?.takeIf { it > 0 }?.let { "панели ${"%.1f".format(bg, it)} kWp" },
            record.deviceKwh?.takeIf { it > 0 }?.let { "батерия ${"%.1f".format(bg, it)} kWh" },
        )
        val specText = if (specs.isEmpty()) "" else " (${specs.joinToString(", ")})"
        return "централа „${record.plantName ?: record.plantId}“$specText, произведена енергия до ${"%02d.%02d".format(
            through.dayOfMonth,
            through.monthValue,
        )}"
    }
}
