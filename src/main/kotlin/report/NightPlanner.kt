package ilios.report

import ilios.SLOTS_PER_DAY
import ilios.SLOTS_PER_HOUR
import ilios.db.Loc
import ilios.db.Locations
import ilios.db.NightPlans
import ilios.db.locOf
import ilios.solax.SolaxPlant
import ilios.solax.SolaxReadings
import ilios.solax.SolaxRepository
import ilios.solax.SolaxService
import ilios.weather.WeatherService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

/** Plans older than this many days are dropped. */
private const val KEEP_PLAN_DAYS = 14L
private const val FRESH_READING_MS = 45 * 60_000L // only a reading this young counts
internal const val SLOT_MS = 300_000L

fun nightPlans(locationId: Long): Map<LocalDate, NightPlan> = transaction {
    NightPlans.selectAll().where { NightPlans.locationId eq locationId }.associate {
        LocalDate.parse(it[NightPlans.day]) to NightPlan(
            it[NightPlans.day], it[NightPlans.foundAt], it[NightPlans.checkedAt], it[NightPlans.dayStartKwh],
            it[NightPlans.solarKwh], it[NightPlans.totalKwh],
            it[NightPlans.targetSoc], it[NightPlans.sunAt], it[NightPlans.stopBy], it[NightPlans.socNow],
            it[NightPlans.reached],
            it[NightPlans.chargeUntil], it[NightPlans.control],
        )
    }
}

/** What the planner needs from the plant's last battery reading. */
private class Reading(val kwp: Double, val capacityKwh: Double, val soc: Double, val readAt: Long) {
    companion object {
        @Suppress("FunctionNameMinLength")
        fun of(plant: SolaxPlant): Reading? {
            val kwp = plant.kwp ?: return null
            val capacity = plant.batteryKwh?.takeIf { it > 0 } ?: return null
            return Reading(kwp, capacity, plant.soc ?: return null, plant.readAt ?: return null)
        }
    }
}

/** The consumption pattern and the panel forecast (W per 5-minute slot) of the day to plan. */
private class Forecast(val pattern: DoubleArray, val pv: DoubleArray)

/**
 * During the night zone the battery is read every 30 minutes (see [SolaxService.readBatteryIfDue]). When it is found
 * at its reserved minimum, the
 * compensation energy for the coming day zone is worked out ([compensation]) and kept in `night_plans`; the plan is
 * refreshed at each later check
 * until the day zone starts. With management enabled the inverter is also told to charge (see [InverterControl]);
 * the plan is shown (callouts, recommendations, the lightning on the weekly chart).
 */
class NightPlanner(private val weather: WeatherService, solax: SolaxService) {
    private val inverter = InverterControl(solax)

    suspend fun loop() {
        while (true) {
            try {
                for (loc in transaction { Locations.selectAll().where { Locations.solar eq true }.map(::locOf) }) {
                    try {
                        check(loc)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        System.err.println("Night plan failed for place ${loc.id}: ${e::class.simpleName}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Night plan loop error: ${e::class.simpleName}")
            }
            delay(10.minutes)
        }
    }

    /**
     * Plans the coming day zone when the place's last battery reading is fresh, new and at the reserved minimum (or a
     * plan already exists).
     */
    suspend fun check(loc: Loc, now: Instant = Instant.now()) {
        val plant = SolaxRepository.plant(loc.id) ?: return
        val reading = Reading.of(plant) ?: return
        val zone = ZoneId.of(loc.tz)
        val local = now.atZone(zone)
        val target = dayToPlan(local, zone) ?: return // the day zone: nothing to plan
        val existing = nightPlans(loc.id)[target]
        if (!isWorthPlanning(loc, existing, reading, now)) return
        val forecast = forecastFor(loc, reading.kwp, target, local.toLocalDate()) ?: return
        val (tFrom, tTo) = nightZone(target, zone)
        val c =
            compensation(forecast.pattern, {
                forecast.pv[it]
            }, tTo * SLOTS_PER_HOUR, tFrom * 12, reading.capacityKwh, loc.reservedPct)
        // The charge ends shortly before the day zone: add what the house is expected to use from the battery in
        // between.
        val targetSoc =
            nightTargetSoc(c, forecast.pattern, tTo * 12, (CHARGE_END_BEFORE_MS / SLOT_MS).toInt(), reading.capacityKwh)
        val reached = c.totalKwh <= 0 || reading.soc >= targetSoc
        val (chargeUntil, control) = inverter.control(loc, existing, plant, targetSoc, reached, local, target, tTo)
        val at = now.toEpochMilli()
        val plan = NightPlan(
            target.toString(), existing?.foundAt ?: at, at, c.dayStartKwh, c.solarKwh, c.totalKwh, targetSoc, c.sunAt,
            "%02d:00".format(
                tTo,
            ),
            reading.soc, reached,
            chargeUntil, control,
        )
        store(loc.id, plan, local.toLocalDate())
    }

    /**
     * The day whose coming day zone is planned: tomorrow in the evening, today before the day zone; null during the
     * day zone.
     */
    private fun dayToPlan(local: ZonedDateTime, zone: ZoneId): LocalDate? {
        val (from, to) = nightZone(local.toLocalDate(), zone)
        val evening = local.hour >= from
        return when {
            evening -> local.toLocalDate().plusDays(1)
            local.hour < to -> local.toLocalDate()
            else -> null
        }
    }

    private fun isWorthPlanning(loc: Loc, existing: NightPlan?, reading: Reading, now: Instant): Boolean {
        val used = existing != null && existing.checkedAt >= reading.readAt // this reading was already used
        val fresh = reading.readAt >= now.toEpochMilli() - FRESH_READING_MS
        val atMinimum = existing != null || reading.soc <= loc.reservedPct + 1 // not at the minimum (yet)
        return !used && fresh && atMinimum
    }

    private fun forecastFor(loc: Loc, kwp: Double, target: LocalDate, today: LocalDate): Forecast? {
        val days = weather.days(loc)
        val produced = SolaxReadings.produced(loc.id)
        val ratio = calibratedRatio(days, produced, kwp, today)
        val pattern =
            loadPatternFor(
                days,
                target,
                today,
                produced,
                SolaxReadings.consumption(loc.id),
                SolaxReadings.loadDays(loc.id, today),
                kwp,
                ratio,
            )
                ?: return null
        val rad =
            weather.intraday(loc, target)?.points?.filter { it.radWm2 != null }?.takeIf { it.isNotEmpty() }
                ?: return null
        return Forecast(
            pattern,
            DoubleArray(SLOTS_PER_DAY) { i ->
                rad.lastOrNull { slotOf(it.t) <= i }?.radWm2?.let {
                    kwp * it *
                        ratio
                }
                    ?: 0.0
            },
        )
    }

    private fun store(locationId: Long, plan: NightPlan, today: LocalDate) {
        transaction {
            NightPlans.deleteWhere { NightPlans.locationId eq locationId and (NightPlans.day eq plan.day) }
            NightPlans.insert {
                it[NightPlans.locationId] = locationId
                it[day] = plan.day
                it[foundAt] = plan.foundAt
                it[checkedAt] = plan.checkedAt
                it[dayStartKwh] = plan.dayStartKwh
                it[solarKwh] = plan.solarKwh
                it[totalKwh] = plan.totalKwh
                it[targetSoc] = plan.targetSoc
                it[sunAt] = plan.sunAt
                it[stopBy] = plan.stopBy
                it[socNow] = plan.socNow
                it[reached] = plan.reached
                it[chargeUntil] = plan.chargeUntil
                it[control] = plan.control
            }
            NightPlans.deleteWhere {
                NightPlans.locationId eq locationId and
                    (NightPlans.day less today.minusDays(KEEP_PLAN_DAYS).toString())
            }
        }
    }
}
