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

/** Extra charging time (a share) for the losses of charging, so the battery really reaches the planned level. */
private const val CHARGE_MARGIN = 1.1

/** Plans older than this many days are dropped. */
private const val KEEP_PLAN_DAYS = 14L

fun nightPlans(locationId: Long): Map<LocalDate, NightPlan> = transaction {
    NightPlans.selectAll().where { NightPlans.locationId eq locationId }.associate {
        LocalDate.parse(it[NightPlans.day]) to NightPlan(
            it[NightPlans.day], it[NightPlans.foundAt], it[NightPlans.checkedAt], it[NightPlans.dayStartKwh], it[NightPlans.solarKwh], it[NightPlans.totalKwh],
            it[NightPlans.targetSoc], it[NightPlans.sunAt], it[NightPlans.stopBy], it[NightPlans.socNow], it[NightPlans.reached],
            it[NightPlans.chargeUntil], it[NightPlans.control],
        )
    }
}

/**
 * During the night zone the battery is read every 30 minutes (see [SolaxService.readBatteryIfDue]). When it is found at its reserved minimum, the
 * compensation energy for the coming day zone is worked out ([compensation]) and kept in `night_plans`; the plan is refreshed at each later check
 * until the day zone starts. With management enabled the inverter is also told to charge (see [controlInverter]); the plan is shown (callouts, recommendations, the lightning on the weekly chart).
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
     * Remote-mode charging (SolaX Push Power at the place's max charging power) for the night plan; never throws (except cancellation).
     * Charging runs on a timer that the inverter honours by itself: the energy still missing divided by the power, plus a margin, and never
     * past the day zone start ([stopHour]). Each fresh battery reading sends the command again with the time recomputed (a new command
     * replaces a running one), and as soon as the plan is reached, or management is switched off, a running charge is ended. Returns the
     * time until which the inverter charges (null when it does not) and a status text.
     */
    internal suspend fun controlInverter(loc: Loc, existing: NightPlan?, plant: SolaxPlant, targetSoc: Double, reached: Boolean, local: ZonedDateTime, target: LocalDate, stopHour: Int): Pair<Long?, String?> {
        val now = local.toInstant().toEpochMilli()
        val running = existing?.chargeUntil?.takeIf { it > now }
        val keep = running to existing?.control
        val level = minOf(100, Math.ceil(targetSoc).toInt())
        val stopReason = when {
            !loc.allowControl -> "Управлението е изключено – зареждането е прекратено"
            reached -> "Планираното ниво е достигнато"
            level <= loc.reservedPct -> "Няма нужда от зареждане"
            else -> null
        }
        if (stopReason != null) {
            if (running == null) return keep
            return try { solax.stopCharge(loc); null to stopReason } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(loc, e, running, existing?.control) }
        }
        val end = target.atTime(stopHour, 0).atZone(local.zone).toInstant().toEpochMilli()
        val missingKwh = (targetSoc - (plant.soc ?: return keep)) / 100 * (plant.batteryKwh ?: return keep)
        val seconds = minOf((end - now) / 1000, (missingKwh / (loc.maxChargeW / 1000.0) * 3600 * CHARGE_MARGIN).toLong() + 60)
        if (seconds < 120) return keep // nothing worthwhile left before the day zone
        return try {
            solax.startCharge(loc, seconds.toInt())
            val at = local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            (now + seconds * 1000) to "Зареждане до $level% при най-много ${loc.maxChargeW} W, зададено в $at за ${(seconds + 30) / 60} мин"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(loc, e, running, existing?.control)
        }
    }

    private fun failed(loc: Loc, e: Exception, until: Long?, control: String?): Pair<Long?, String?> =
        if (e is SolaxError) until to e.message?.take(200)
        else { System.err.println("Controlling the inverter failed for place ${loc.id}: ${e::class.simpleName}"); until to "Инверторът не може да се управлява – ще опитам отново по-късно" }

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
        val (chargeUntil, control) = controlInverter(loc, existing, plant, c.targetSoc, reached, local, target, tTo)
        transaction {
            NightPlans.deleteWhere { (NightPlans.locationId eq loc.id) and (NightPlans.day eq target.toString()) }
            NightPlans.insert {
                it[locationId] = loc.id; it[day] = target.toString(); it[foundAt] = existing?.foundAt ?: at; it[checkedAt] = at
                it[dayStartKwh] = c.dayStartKwh; it[solarKwh] = c.solarKwh; it[totalKwh] = c.totalKwh; it[targetSoc] = c.targetSoc
                it[sunAt] = c.sunAt; it[stopBy] = "%02d:00".format(tTo); it[socNow] = soc; it[NightPlans.reached] = reached
                it[NightPlans.chargeUntil] = chargeUntil; it[NightPlans.control] = control
            }
            NightPlans.deleteWhere { (NightPlans.locationId eq loc.id) and (NightPlans.day less today.minusDays(KEEP_PLAN_DAYS).toString()) }
        }
    }
}
