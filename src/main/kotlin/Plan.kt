package ilios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.less
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

/** Plans older than this many days are dropped. */
private const val KEEP_PLAN_DAYS = 14L

fun nightPlans(locationId: Long): Map<LocalDate, NightPlan> = transaction {
    NightPlans.selectAll().where { NightPlans.locationId eq locationId }.associate {
        LocalDate.parse(it[NightPlans.day]) to NightPlan(
            it[NightPlans.day], it[NightPlans.foundAt], it[NightPlans.checkedAt], it[NightPlans.dayStartKwh], it[NightPlans.solarKwh], it[NightPlans.totalKwh],
            it[NightPlans.targetSoc], it[NightPlans.sunAt], it[NightPlans.stopBy], it[NightPlans.socNow], it[NightPlans.reached],
            it[NightPlans.ruleId], it[NightPlans.ruleSoc], it[NightPlans.control],
        )
    }
}

/**
 * During the night zone the battery is read every 30 minutes (see [SolaxService.readBatteryIfDue]). When it is found at its reserved minimum, the
 * compensation energy for the coming day zone is worked out ([compensation]) and kept in `night_plans`; the plan is refreshed at each later check
 * until the day zone starts. With management enabled the inverter is also programmed to charge (see [programInverter]); the plan is shown (callouts, recommendations, the lightning on the weekly chart).
 */
class NightPlanner(private val weather: WeatherService, private val solax: SolaxService) {
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
     * With management enabled and charging still needed, programs the inverter to charge from now until the day zone starts, up to the planned
     * level and at the place's max charging power; an earlier rule for another level is replaced. Never throws (except cancellation); returns
     * the rule id, the level programmed and a status text.
     */
    private suspend fun programInverter(loc: Loc, existing: NightPlan?, targetSoc: Double, reached: Boolean, local: ZonedDateTime, target: LocalDate, stopHour: Int): Triple<String?, Double?, String?> {
        var ruleId = existing?.ruleId; var ruleSoc = existing?.ruleSoc
        val keep = Triple(ruleId, ruleSoc, existing?.control)
        if (!loc.allowControl) return keep
        val level = minOf(100, Math.ceil(targetSoc).toInt())
        if (reached || level <= loc.reservedPct) return keep // nothing to charge: a rule made earlier ends by itself
        if (ruleId != null && ruleSoc != null && Math.abs(ruleSoc - level) < 2) return keep
        val end = target.atTime(stopHour, 0).atZone(local.zone)
        val start = local.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusMinutes(1)
        if (!start.isBefore(end)) return keep
        return try {
            if (ruleId != null) { runCatching { solax.removeChargeRules(loc, ruleId.split(',')) }; ruleId = null; ruleSoc = null } // an expired rule may already be gone
            val id = solax.programCharge(loc, start, end, level).joinToString(",")
            Triple(id, level.toDouble(), "Зареждане до $level% при най-много ${loc.maxChargeW} W е зададено в ${"%02d:%02d".format(start.hour, start.minute)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SolaxError) {
            Triple(ruleId, ruleSoc, e.message?.take(200))
        } catch (e: Exception) {
            System.err.println("Programming the inverter failed for place ${loc.id}: ${e::class.simpleName}")
            Triple(ruleId, ruleSoc, "Инверторът не може да се програмира – ще опитам отново по-късно")
        }
    }

    suspend fun check(loc: Loc, now: Instant = Instant.now()) {
        val plant = solax.plant(loc.id) ?: return
        val kwp = plant.kwp ?: return; val cap = plant.batteryKwh?.takeIf { it > 0 } ?: return
        val soc = plant.soc ?: return; val readAt = plant.readAt ?: return
        val zone = ZoneId.of(loc.tz)
        val local = now.atZone(zone)
        val (from, to) = nightZone(local.toLocalDate(), zone)
        val evening = local.hour >= from
        if (!evening && local.hour >= to) return // the day zone: nothing to plan
        val target = if (evening) local.toLocalDate().plusDays(1) else local.toLocalDate()
        val (tFrom, tTo) = nightZone(target, zone)
        val existing = nightPlans(loc.id)[target]
        if (existing != null && existing.checkedAt >= readAt) return // this reading was already used
        if (readAt < now.toEpochMilli() - 45 * 60_000L) return // only a fresh reading counts
        if (existing == null && soc > loc.reservedPct + 1) return // not at the minimum (yet)

        val today = local.toLocalDate()
        val days = weather.days(loc)
        val produced = solax.produced(loc.id)
        val ratio = calibratedRatio(days, produced, kwp, today)
        val pattern = loadPatternFor(days, target, today, produced, solax.consumption(loc.id), solax.loadDays(loc.id, today), kwp, ratio) ?: return
        val rad = weather.intraday(loc, target)?.points?.filter { it.radWm2 != null } ?: return
        if (rad.isEmpty()) return
        val pv = DoubleArray(288) { i -> rad.lastOrNull { slotOf(it.t) <= i }?.radWm2?.let { kwp * it * ratio } ?: 0.0 }
        val c = compensation(pattern, { pv[it] }, tTo * 12, tFrom * 12, cap, loc.reservedPct)
        val at = now.toEpochMilli()
        val reached = c.totalKwh <= 0 || soc >= c.targetSoc
        val (ruleId, ruleSoc, control) = programInverter(loc, existing, c.targetSoc, reached, local, target, tTo)
        transaction {
            NightPlans.deleteWhere { (NightPlans.locationId eq loc.id) and (NightPlans.day eq target.toString()) }
            NightPlans.insert {
                it[locationId] = loc.id; it[day] = target.toString(); it[foundAt] = existing?.foundAt ?: at; it[checkedAt] = at
                it[dayStartKwh] = c.dayStartKwh; it[solarKwh] = c.solarKwh; it[totalKwh] = c.totalKwh; it[targetSoc] = c.targetSoc
                it[sunAt] = c.sunAt; it[stopBy] = "%02d:00".format(tTo); it[socNow] = soc; it[NightPlans.reached] = reached
                it[NightPlans.ruleId] = ruleId; it[NightPlans.ruleSoc] = ruleSoc; it[NightPlans.control] = control
            }
            NightPlans.deleteWhere { (NightPlans.locationId eq loc.id) and (NightPlans.day less today.minusDays(KEEP_PLAN_DAYS).toString()) }
        }
    }
}
