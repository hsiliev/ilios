package ilios.report

import ilios.PERCENT
import ilios.SLOTS_PER_DAY
import ilios.SLOTS_PER_HOUR
import ilios.WEEK_DAYS
import ilios.db.DEFAULT_SUPPLIER
import ilios.db.Loc
import ilios.mondayOf
import ilios.solax.SolaxPlant
import ilios.weather.Day
import ilios.weather.IntraPoint
import ilios.weather.Intraday
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.round

private const val HALF_DAY_SLOTS = 12 * SLOTS_PER_HOUR

/** What the estimate of the rest of today is worked out from (the pieces of [buildReport] it shares). */
private class EstimateInput(
    val loc: Loc,
    val days: List<Day>,
    val today: LocalDate,
    val intraday: Intraday?,
    val battery: Battery,
    val batteryToday: List<TodayPoint>,
    val produced: Map<LocalDate, Double>,
    val load: Map<LocalDate, Double>,
    val loadHistory: List<Map<String, Double>>,
    val plans: Map<LocalDate, NightPlan>,
    val kwp: Double,
    val ratio: Double,
    val pvForecast: List<TodayPoint>,
    val pvForecastNext: List<TodayPoint>,
)

/**
 * Everything the dashboard shows for one place, worked out from the stored data only (no network). [plant] and the
 * battery/production maps
 * come from SolaX and are empty for places without solar.
 */
fun buildReport(
    loc: Loc,
    days: List<Day>,
    today: LocalDate,
    intraday: Intraday?,
    produced: Map<LocalDate, Double> = emptyMap(),
    plant: SolaxPlant? = null,
    ac: Map<LocalDate, Double> = emptyMap(),
    maxSoc: Map<LocalDate, Double> = emptyMap(),
    batteryToday: List<TodayPoint> = emptyList(),
    batteryPrev: List<TodayPoint> = emptyList(),
    load: Map<LocalDate, Double> = emptyMap(),
    dischargeProfile: List<DischargePoint> = emptyList(),
    loadHistory: List<Map<String, Double>> = emptyList(),
    plans: Map<LocalDate, NightPlan> = emptyMap(),
    badCloud: Map<LocalDate, Double> = emptyMap(),
): Report {
    val kwp = plant?.kwp ?: 0.0 // installed PV power comes from SolaX; without it there is no estimate
    val ratio = calibratedRatio(days, produced, kwp, today)
    val homeDay = homeDaytimeKwh(load, today)
    val points = weekPoints(days, today, kwp, ratio, produced, ac, maxSoc, load, homeDay, plans, badCloud)
    val battery = if (loc.solar && kwp > 0) batteryOf(plant, batteryToday, today, ZoneId.of(loc.tz)) else null
    val hasBattery = battery != null
    val pvForecast = if (battery != null) pvForecastOf(intraday?.points.orEmpty(), kwp, ratio) else emptyList()
    val pvForecastNext = if (battery != null) pvForecastOf(intraday?.next.orEmpty(), kwp, ratio) else emptyList()
    val estimate = battery?.let {
        estimateRestOfToday(
            EstimateInput(
                loc, days, today, intraday, it, batteryToday, produced, load, loadHistory, plans, kwp, ratio,
                pvForecast, pvForecastNext,
            ),
        )
    }
    val (nightFrom, nightTo) = nightZone(today, ZoneId.of(loc.tz))
    val (dayPrice, nightPrice) = TARIFFS[loc.supplier] ?: TARIFFS.getValue(DEFAULT_SUPPLIER)
    return Report(
        loc.id, loc.name, loc.solar, today.toString(), points,
        wateringFor(
            days,
            today,
        ),
        battery, intraday, country = loc.country, estimate = estimate,
        nightFrom = nightFrom, nightTo = nightTo,
        batteryToday = if (hasBattery) batteryToday else emptyList(),
        dischargeProfile = if (hasBattery) dischargeProfile else emptyList(),
        // expected panel power of the 15-minute steps: installed kWp x radiation (W/m² over 1000) x the calibrated
        // yield ratio
        pvForecast = pvForecast, pvForecastNext = pvForecastNext,
        batteryPrev = if (hasBattery) batteryPrev else emptyList(),
        night = battery?.let {
            nightCharge(
                it.batteryKwh,
                it.soc,
                points.find { p ->
                    p.date == today.plusDays(1).toString()
                }?.kwh,
                dayPrice,
                nightPrice,
                homeDay ?: 0.0,
            )
        },
        missed = battery?.let {
            missedCharge(it.batteryKwh, batteryToday, loc.reservedPct, nightTo, dayPrice, nightPrice)
        },
    )
}

/**
 * The three-week window (previous, current and next week, Monday to Sunday) with solar yield, production and
 * consumption per day.
 */
private fun weekPoints(
    days: List<Day>,
    today: LocalDate,
    kwp: Double,
    ratio: Double,
    produced: Map<LocalDate, Double>,
    ac: Map<LocalDate, Double>,
    maxSoc: Map<LocalDate, Double>,
    load: Map<LocalDate, Double>,
    homeDay: Double?,
    plans: Map<LocalDate, NightPlan>,
    badCloud: Map<LocalDate, Double>,
): List<DayPoint> {
    val monday = mondayOf(today)
    val samples = days.filter { it.date < today && load[it.date] != null }
        .map {
            Triple(meanTemp(it.tMax, it.tMin), produced[it.date] ?: solarKwh(it, kwp, ratio), load.getValue(it.date))
        }
    return days.between(monday.minusDays(WEEK_DAYS), monday.plusDays(WEEK_DAYS * 2 - 1)).map { d ->
        val past = d.date < today
        val sun = produced[d.date] ?: r1(solarKwh(d, kwp, ratio))
        DayPoint(
            d.date.toString(), d.tMax, d.tMin,
            r1(
                d.rainMm,
            ),
            r1(
                solarKwh(d, kwp, ratio),
            ),
            produced[d.date]?.let(::r1), ac[d.date]?.let(::r1), maxSoc[d.date]?.let(::r1),
            loadKwh = if (past) load[d.date]?.let(::r1) else null,
            loadEst = if (past) null else estimateLoad(samples, meanTemp(d.tMax, d.tMin), sun)?.let(::r1),
            homeKwh = if (past) null else homeDay?.let(::r1),
            plan = plans[d.date], badCloud = badCloud[d.date]?.let(::r1),
        )
    }
}

/**
 * The battery as the dashboard shows it. The 5-minute history of today is usually newer than the hourly reading kept
 * with the plant; when it
 * is, its last point is used so the chip and the chart show the same value.
 */
private fun batteryOf(plant: SolaxPlant?, batteryToday: List<TodayPoint>, today: LocalDate, zone: ZoneId): Battery {
    val kwh = plant?.batteryKwh ?: 0.0
    val last = batteryToday.lastOrNull { it.soc != null }
    val soc = last?.soc
    val lastAt = last?.let { today.atTime(LocalTime.parse(it.t)).atZone(zone).toInstant().toEpochMilli() }
    return if (soc != null && lastAt != null && lastAt > plant?.readAt ?: 0L) {
        Battery(kwh, soc, if (kwh > 0) kwh * soc / PERCENT else null, last.batW ?: plant?.powerW, lastAt)
    } else {
        Battery(kwh, plant?.soc, plant?.remainingKwh, plant?.powerW, plant?.readAt)
    }
}

/** Expected panel power (W) of each 15-minute step: kWp x radiation (W/m² over 1000) x the calibrated yield ratio. */
private fun pvForecastOf(steps: List<IntraPoint>, kwp: Double, ratio: Double): List<TodayPoint> = steps.filter {
    it.radWm2 !=
        null
}.map { TodayPoint(it.t, null, round(kwp * it.radWm2!! * ratio)) }

/**
 * The rest of today (and the 12 hours ahead), 5 minutes at a time: the pattern of the previous days' consumption,
 * lifted or lowered by the weather.
 */
private fun estimateRestOfToday(i: EstimateInput): Estimate? {
    val loc = i.loc
    val today = i.today
    val now = i.intraday?.now ?: return null
    val soc = i.batteryToday.lastOrNull { it.soc != null }?.soc ?: i.battery.soc ?: return null
    val pattern = loadPatternFor(i.days, today, today, i.produced, i.load, i.loadHistory, i.kwp, i.ratio) ?: return null
    // the next 12 hours, which run into tomorrow: its consumption pattern (weather) and panel forecast follow today's
    val tomorrow =
        loadPatternFor(i.days, today.plusDays(1), today, i.produced, i.load, i.loadHistory, i.kwp, i.ratio) ?: pattern
    val pv = DoubleArray(576) { slot ->
        (
            if (slot <
                288
            ) {
                i.pvForecast
            } else {
                i.pvForecastNext
            }
            ).lastOrNull { slotOf(it.t) <= slot % 288 }?.pvW
            ?: 0.0
    }
    val from = slotOf(now)
    val patterns = pattern + tomorrow
    val batteryKwh = i.battery.batteryKwh
    val charge = plannedChargeOf(i, from, patterns, pv, soc)
    return estimateDay(from, soc, batteryKwh, patterns, loc.reservedPct, from + HALF_DAY_SLOTS, charge) { pv[it] }
}

/**
 * The night plan the estimate looks at: the coming day zone, which is tomorrow's from the day zone's start on (slots
 * 288 and up are tomorrow).
 */
private class PlanWindow(val planDay: LocalDate, val zoneFrom: Int, val zoneTo: Int, val offset: Int, val gap: Int) {
    /** The slot at which the charge should end: shortly before the day zone starts. */
    val end get() = zoneTo * 12 - gap + offset

    companion object {
        @Suppress("FunctionNameMinLength")
        fun of(today: LocalDate, zone: ZoneId, fromSlot: Int): PlanWindow {
            // the day zone has started: the next one to plan for is tomorrow's
            val evening = fromSlot >= nightZone(today, zone).second * 12
            val planDay = if (evening) today.plusDays(1) else today
            val (zFrom, zTo) = nightZone(planDay, zone)
            return PlanWindow(
                planDay,
                zFrom,
                zTo,
                if (evening) SLOTS_PER_DAY else 0,
                (CHARGE_END_BEFORE_MS / SLOT_MS).toInt(),
            )
        }
    }
}

/**
 * The grid charging the night plan intends to do (management on): for the coming day zone, which is tomorrow's from
 * the day zone's start on.
 * The stored plan is used; without one (the planner acts once the battery is at its minimum) the plan the planner
 * would make when the plain
 * estimate says the battery gets there before the day zone. Null when nothing is planned.
 */
private fun plannedChargeOf(
    i: EstimateInput,
    from: Int,
    patterns: DoubleArray,
    pv: DoubleArray,
    soc: Double,
): PlannedCharge? {
    val loc = i.loc
    if (!loc.allowControl) return null
    val w = PlanWindow.of(i.today, ZoneId.of(loc.tz), from)
    val stored = i.plans[w.planDay]
    val plain = estimateDay(from, soc, i.battery.batteryKwh, patterns, loc.reservedPct, w.end) { pv[it] } ?: return null
    val target =
        (
            if (stored !=
                null
            ) {
                storedTarget(stored)
            } else {
                projectedTarget(i, w, plain, patterns, pv)
            }
            )?.takeIf { it > loc.reservedPct }
            ?: return null
    val running = stored?.chargeUntil ?: 0L > System.currentTimeMillis()
    val start =
        plannedChargeStart(
            plain,
            from,
            w.end,
            i.battery.batteryKwh,
            target,
            loc.maxChargeW.toDouble(),
            CHARGE_MARGIN,
            running,
        )
            ?: return null
    return PlannedCharge(start, w.end, loc.maxChargeW.toDouble(), target)
}

private fun storedTarget(stored: NightPlan): Double? = stored.takeIf { !it.reached && it.totalKwh > 0 }?.targetSoc

/**
 * The target the planner would set: when the plain estimate drops to the reserved level before the day zone, from
 * that day's compensation.
 */
private fun projectedTarget(
    i: EstimateInput,
    w: PlanWindow,
    plain: Estimate,
    patterns: DoubleArray,
    pv: DoubleArray,
): Double? {
    val reserved = i.loc.reservedPct
    if (plain.points.none { it.soc <= reserved + 1 }) return null
    val pattern = patterns.copyOfRange(w.offset, w.offset + 288)
    val c =
        compensation(pattern, {
            pv[w.offset + it]
        }, w.zoneTo * SLOTS_PER_HOUR, w.zoneFrom * 12, i.battery.batteryKwh, reserved)
    return if (c.totalKwh >
        0
    ) {
        nightTargetSoc(c, pattern, w.zoneTo * SLOTS_PER_HOUR, w.gap, i.battery.batteryKwh)
    } else {
        null
    }
}
