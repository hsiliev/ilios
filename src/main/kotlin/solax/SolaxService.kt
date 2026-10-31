package ilios.solax

import ilios.db.Loc
import ilios.db.Locations
import ilios.db.Solax
import ilios.db.locOf
import ilios.solax.api.RETRY_AFTER_MS
import ilios.solax.api.SolaxError
import ilios.solax.api.SolaxSession
import ilios.solax.api.attempt
import ilios.solax.api.solaxBaseUrl
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The only code that talks to SolaX Cloud. One lock serialises the calls so that a place's token and quota are never
 * used twice at once.
 * The work itself is done by [PlantSync] (production and plant record), [BatteryReader], [TodayCache],
 * [BatteryDayHistory] and
 * [DischargeHistory]; the database side is [SolaxRepository].
 */
class SolaxService(http: HttpClient, baseUrl: String = solaxBaseUrl()) {
    private val lock = Mutex()
    private val client = SolaxClient(http, baseUrl)
    private val today = TodayCache(client)
    private val battery = BatteryReader(client, today)
    private val batteryDays = BatteryDayHistory(client)
    private val discharge = DischargeHistory(client)

    /** Opens every retry gate of one place and re-reads everything at once (the settings page's refresh button). */
    suspend fun refresh(loc: Loc) {
        SolaxRepository.update(loc.id) {
            it[Solax.plantReadAt] = null
            it[Solax.dueAt] = 0
            it[Solax.batteryTryAt] = null
            it[Solax.batteryReadAt] = null
            it[Solax.historyTryAt] = null
        }
        sync(loc)
        readBatteryIfDue(loc)
        batteryHistoryIfDue(loc)
        dischargeHistoryIfDue(loc)
    }

    /** Runs until cancelled: syncs solar places that are due. */
    suspend fun syncLoop() {
        while (true) {
            try {
                for (loc in solarPlaces()) {
                    syncIfDue(loc)
                    refreshTodayIfDue(loc)
                    batteryHistoryIfDue(loc)
                    dischargeHistoryIfDue(loc)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("SolaX loop error: ${e.message}")
            }
            delay(CHECK_EVERY)
        }
    }

    /** Runs until cancelled: reads the battery of the solar places whose reading is due (see [batterySlot]). */
    suspend fun batteryLoop() {
        while (true) {
            try {
                for (loc in solarPlaces()) readBatteryIfDue(loc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("SolaX battery loop error: ${e.message}")
            }
            delay(BATTERY_CHECK_EVERY)
        }
    }

    private fun solarPlaces() = transaction {
        (Locations innerJoin Solax).selectAll().where { Locations.solar eq true }.map(::locOf)
    }

    /**
     * Syncs production when a new final day is available (or the connection is new) and no failure back-off is
     * running.
     */
    suspend fun syncIfDue(loc: Loc, now: Instant = Instant.now()) {
        val row = SolaxRepository.row(loc.id) ?: return
        val through = row[Solax.producedThrough]?.let(LocalDate::parse)
        val newest = lastProductionDay(now.atZone(ZoneId.of(loc.tz)))
        if (row[Solax.dueAt] <= now.toEpochMilli() && (through == null || through < newest)) sync(loc, now)
    }

    /**
     * Fetches the production of the days not fetched yet (up to the last final day) and refreshes the plant record
     * when it is
     * old. Never throws (except cancellation); the outcome goes to the status text.
     */
    suspend fun sync(loc: Loc, now: Instant = Instant.now()) = lock.withLock {
        val row = SolaxRepository.row(loc.id) ?: return@withLock
        attempt(
            "request",
            loc.id,
            onSolaxError = {
                PlantSync.finish(loc.id, it.message ?: "Грешка от SolaX", now.toEpochMilli() + it.retryMs)
            },
            onFailure = {
                PlantSync.finish(
                    loc.id,
                    "Няма връзка със SolaX – ще опитам отново по-късно",
                    now.toEpochMilli() + RETRY_AFTER_MS,
                )
            },
        ) {
            val summary = PlantSync.run(loc, row, client.session(loc.id, row), now)
            PlantSync.finish(loc.id, "Свързано – $summary", 0)
        }
    }

    /**
     * Charges the battery from the grid at the place's max charging power for [seconds]: SolaX "Push Power" remote
     * mode (a negative battery
     * power charges; the panels' surplus and the house use are handled by the inverter as usual). When the time is
     * up the inverter leaves
     * remote control by itself, so the timer is the safety stop. A new command replaces a running one (power and timer). Throws [SolaxError].
     */
    suspend fun startCharge(loc: Loc, seconds: Int) =
        remote(loc) { session, sns, type -> session.pushPower(sns, type, loc.maxChargeW, seconds) }

    /** Ends a charge started by [startCharge] at once (harmless when none is running). Throws [SolaxError]. */
    suspend fun stopCharge(loc: Loc) = remote(loc) { session, sns, type -> session.exitRemote(sns, type) }

    private suspend fun remote(loc: Loc, command: suspend (SolaxSession, List<String>, Int) -> Unit) = lock.withLock {
        val link = client.link(loc) ?: throw SolaxError("Няма връзка със SolaX")
        command(link.session, link.inverterSerials(), link.type)
    }

    /**
     * Reads the battery charge hourly: when a reading is older than the start of the current hour (or there is none
     * yet, e.g. right after
     * connecting), every 30 minutes in the night zone. Failures are retried at most hourly. Never throws (except
     * cancellation); the outcome
     * goes to [Solax.batteryStatus].
     */
    suspend fun readBatteryIfDue(loc: Loc, now: Instant = Instant.now()) = lock.withLock { battery.readIfDue(loc, now) }

    /**
     * Called by the report route: the dashboard polls it every minute while open, so a recent call means somebody is
     * looking.
     */
    fun viewed(now: Instant = Instant.now()) = today.viewed(now)

    /** Keeps today's chart close to now while the dashboard is open (see [TodayCache.refreshIfDue]). */
    suspend fun refreshTodayIfDue(loc: Loc, now: Instant = Instant.now()) =
        lock.withLock { today.refreshIfDue(loc, now) }

    /** Fetches the highest battery charge of the next past day that has none yet (see [BatteryDayHistory]). */
    suspend fun batteryHistoryIfDue(loc: Loc, now: Instant = Instant.now()) =
        lock.withLock { batteryDays.cacheIfDue(loc, now) }

    /**
     * Fetches the 5-minute discharge, consumption and grid history of the days still missing (see
     * [DischargeHistory]).
     */
    suspend fun dischargeHistoryIfDue(loc: Loc, now: Instant = Instant.now()) =
        lock.withLock { discharge.cacheIfDue(loc, now) }
}
