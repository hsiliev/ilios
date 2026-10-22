package ilios

import kotlinx.serialization.Serializable
import kotlin.math.round

/** The battery charge (%) reserved by default, and the range a place may set: below it the house is served from the grid. */
const val DEFAULT_RESERVE = 0
const val MAX_RESERVE = 50
const val DEFAULT_MAX_CHARGE_W = 2000
const val MIN_MAX_CHARGE_W = 500
const val MAX_MAX_CHARGE_W = 6000

/** One 5-minute step of the estimate for the rest of today; t is local HH:mm. All powers in W. */
@Serializable
class EstimatePoint(val t: String, val pvW: Double, val loadW: Double, val batW: Double, val gridW: Double, val soc: Double, val chargeW: Double = 0.0)

/**
 * The rest of today in 5-minute steps: the expected consumption, panel power, battery discharge, grid import and battery charge (%).
 * peakSoc is the highest charge the day is expected to reach (from now), importKwh what the grid has to supply; chargeNeeded when the
 * sun is not expected to fill the battery or the battery runs down to its floor.
 */
@Serializable
class Estimate(val points: List<EstimatePoint>, val peakSoc: Double, val importKwh: Double, val chargeNeeded: Boolean)

/**
 * The grid charging the night plan intends to do (only when the place allows managing the inverter): from [fromSlot] to [toSlot] (slots of
 * the estimate, 288 and up are tomorrow) the battery takes [powerW] from the grid until it holds [targetSoc] %. While it runs, the house is
 * served from the grid too (verified on the inverter: the grid supplies the charge plus the load) and the battery is not discharged.
 */
class PlannedCharge(val fromSlot: Int, val toSlot: Int, val powerW: Double, val targetSoc: Double)

/**
 * The slot at which the planned charge starts, as the inverter control does: as late as possible so that it still reaches [targetSoc] before
 * [endSlot] (the missing energy ÷ power × [margin]), or at once ([nowSlot]) when a charge is already running. Null when nothing is missing.
 * [plain] is the estimate without the charge, running at least to [endSlot].
 */
fun plannedChargeStart(plain: Estimate, nowSlot: Int, endSlot: Int, batteryKwh: Double, targetSoc: Double, powerW: Double, margin: Double, running: Boolean): Int? {
    if (endSlot <= nowSlot || batteryKwh <= 0 || powerW <= 0) return null
    for (s in nowSlot until endSlot) {
        val soc = plain.points.getOrNull(s - nowSlot)?.soc ?: continue
        val missingKwh = (targetSoc - soc) / 100 * batteryKwh
        if (missingKwh <= 0) continue // still above the target: the battery has to drain first
        val slots = missingKwh / (powerW / 1000) * margin * 12 // hours -> 5-minute slots
        if (running || s + slots >= endSlot) return s
    }
    return null
}

fun slotOf(t: String): Int { val (h, m) = t.split(':'); return (h.toInt() * 60 + m.toInt()) / 5 }
fun slotName(i: Int) = "%02d:%02d".format(i * 5 / 60, i * 5 % 60)

/**
 * Consumption pattern (W per 5-minute slot, 288 values) as the mean of the previous days' measured consumption at that time, scaled by
 * [weatherScale] (today's expected total over the mean total of those days, so a colder or duller day lifts the whole pattern).
 * Slots without data take the mean of the others. Null without any measured consumption.
 */
fun loadPattern(history: List<Map<String, Double>>, weatherScale: Double): DoubleArray? {
    val sums = DoubleArray(288); val counts = IntArray(288)
    for (day in history) for ((t, w) in day) { val i = slotOf(t); if (i in 0..287) { sums[i] += w; counts[i]++ } }
    val have = counts.indices.filter { counts[it] > 0 }
    if (have.isEmpty()) return null
    val mean = have.sumOf { sums[it] / counts[it] }.div(have.size)
    return DoubleArray(288) { (if (counts[it] > 0) sums[it] / counts[it] else mean) * weatherScale }
}

/**
 * Simulates the battery from [nowSlot] to midnight: the sun serves the house first, the surplus charges the battery (up to 100 %), a
 * shortfall is taken from the battery down to its floor and then from the grid. [pvW] is the panel power forecast per slot (a 15-minute
 * value holds for its three slots). With [untilSlot] past 288 the simulation goes on into the next day (slots 288 and up, named by the time
 * of day, so the times wrap); the summary figures still cover today only. A [charge] adds the planned grid charging.
 */
fun estimateDay(nowSlot: Int, soc: Double, batteryKwh: Double, pattern: DoubleArray, reservedPct: Int = DEFAULT_RESERVE, untilSlot: Int = 288, charge: PlannedCharge? = null, pvW: (Int) -> Double): Estimate? {
    if (batteryKwh <= 0 || nowSlot !in 0..287) return null
    var e = soc / 100 * batteryKwh; val floor = reservedPct / 100.0 * batteryKwh
    var peak = soc; var imp = 0.0; var hitFloor = false
    val pts = (nowSlot until maxOf(288, untilSlot)).map { i ->
        val load = pattern[i]; val pv = pvW(i); val h = 5.0 / 60 / 1000 // W over a slot -> kWh
        val net = (pv - load) * h
        var bat = 0.0; var grid = 0.0; var chargeW = 0.0
        val target = charge?.let { it.targetSoc / 100 * batteryKwh }
        if (charge != null && target != null && i >= charge.fromSlot && i < charge.toSlot && e < target) {
            val add = minOf(charge.powerW * h, target - e); e += add; chargeW = add / h
            grid = maxOf(0.0, -net / h) + chargeW // the house is served from the grid while the battery charges
        } else if (net >= 0) e = minOf(batteryKwh, e + net)
        else {
            val take = minOf(-net, maxOf(0.0, e - floor)); e -= take
            bat = take / h; grid = (-net - take) / h
            if (grid > 0 && i < 288) hitFloor = true
        }
        if (i < 288) imp += grid * h
        val s = e / batteryKwh * 100; if (i < 288) peak = maxOf(peak, s)
        EstimatePoint(slotName(i % 288), round(pv), round(load), round(bat), round(grid), round(s * 10) / 10, round(chargeW))
    }
    return Estimate(pts, round(peak * 10) / 10, round(imp * 100) / 100, peak < 99 || hitFloor)
}

/** The cheap night tariff of a date as local hours (from, to): 22:00-06:00 in winter time, 23:00-07:00 in summer time. */
fun nightZone(date: java.time.LocalDate, zone: java.time.ZoneId): Pair<Int, Int> =
    if (zone.rules.isDaylightSavings(date.atTime(12, 0).atZone(zone).toInstant())) 23 to 7 else 22 to 6

/**
 * What to put into the battery at night so that the next day needs no daytime grid energy. [dayStartKwh] covers the hours from the start of the
 * day zone until the sun ([sunAt]) can power the whole house; [solarKwh] covers the later hours the sun cannot make up for, until the night zone
 * starts. [totalKwh] is their sum (may exceed the battery: then the target is a full battery); [targetSoc] is the charge (%) to reach.
 */
@Serializable
class Compensation(val dayStartKwh: Double, val solarKwh: Double, val totalKwh: Double, val sunAt: String?, val targetSoc: Double)

/**
 * Walks the day zone ([dayStart] to [nightStart], in 5-minute slots) with the battery at its reserve: the running balance of panel power minus
 * consumption goes down while the sun is short, and its lowest point is the energy the battery needs in advance (the surplus in between is
 * used up by the later shortfall). The battery's size is ignored while walking so a total above the capacity can be told.
 */
fun compensation(pattern: DoubleArray, pvW: (Int) -> Double, dayStart: Int, nightStart: Int, batteryKwh: Double, reservedPct: Int): Compensation {
    val h = 5.0 / 60 / 1000
    var bal = 0.0; var low = 0.0
    for (i in dayStart until nightStart) { bal += (pvW(i) - pattern[i]) * h; low = minOf(low, bal) }
    val total = -low
    val sun = (dayStart until nightStart).firstOrNull { i -> (0..2).all { k -> i + k < nightStart && pvW(i + k) >= pattern[i + k] } }
    val first = if (sun == null) total else (dayStart until sun).sumOf { maxOf(0.0, pattern[it] - pvW(it)) } * h
    val usable = batteryKwh * (100 - reservedPct) / 100
    val soc = if (batteryKwh > 0 && total > 0) minOf(100.0, reservedPct + minOf(total, usable) / batteryKwh * 100) else reservedPct.toDouble()
    fun r2(x: Double) = round(x * 100) / 100
    val d1 = minOf(first, total)
    return Compensation(r2(d1), r2(maxOf(0.0, total - d1)), r2(total), sun?.let(::slotName), round(soc * 10) / 10)
}

/**
 * The charge (%) the night plan aims for: the compensation's level plus what the house is expected to use from the battery during the
 * [gapSlots] before the day zone starts at slot [dayStart] (the charge ends that much earlier); a full battery when the total exceeds it.
 */
fun nightTargetSoc(c: Compensation, pattern: DoubleArray, dayStart: Int, gapSlots: Int, batteryKwh: Double): Double {
    val gapKwh = (dayStart - gapSlots until dayStart).sumOf { pattern[it] } * 5 / 60 / 1000
    return if (c.totalKwh > 0) minOf(100.0, round((c.targetSoc + gapKwh / batteryKwh * 100) * 10) / 10) else c.targetSoc
}

/** The stored plan for one night: the compensation energy calculated while the battery sat at its minimum. */
@Serializable
class NightPlan(
    val day: String, val foundAt: Long, val checkedAt: Long, val dayStartKwh: Double, val solarKwh: Double, val totalKwh: Double,
    val targetSoc: Double, val sunAt: String?, val stopBy: String, val socNow: Double?, val reached: Boolean,
    val chargeUntil: Long? = null, val control: String? = null,
)
