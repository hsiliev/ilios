package ilios

import kotlinx.serialization.Serializable
import kotlin.math.round

/** The battery charge (%) reserved by default, and the range a place may set: below it the house is served from the grid. */
const val DEFAULT_RESERVE = 0
const val MAX_RESERVE = 50

/** One 5-minute step of the estimate for the rest of today; t is local HH:mm. All powers in W. */
@Serializable
class EstimatePoint(val t: String, val pvW: Double, val loadW: Double, val batW: Double, val gridW: Double, val soc: Double)

/**
 * The rest of today in 5-minute steps: the expected consumption, panel power, battery discharge, grid import and battery charge (%).
 * peakSoc is the highest charge the day is expected to reach (from now), importKwh what the grid has to supply; chargeNeeded when the
 * sun is not expected to fill the battery or the battery runs down to its floor.
 */
@Serializable
class Estimate(val points: List<EstimatePoint>, val peakSoc: Double, val importKwh: Double, val chargeNeeded: Boolean)

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
 * value holds for its three slots).
 */
fun estimateDay(nowSlot: Int, soc: Double, batteryKwh: Double, pattern: DoubleArray, reservedPct: Int = DEFAULT_RESERVE, pvW: (Int) -> Double): Estimate? {
    if (batteryKwh <= 0 || nowSlot !in 0..287) return null
    var e = soc / 100 * batteryKwh; val floor = reservedPct / 100.0 * batteryKwh
    var peak = soc; var imp = 0.0; var hitFloor = false
    val pts = (nowSlot..287).map { i ->
        val load = pattern[i]; val pv = pvW(i); val h = 5.0 / 60 / 1000 // W over a slot -> kWh
        val net = (pv - load) * h
        var bat = 0.0; var grid = 0.0
        if (net >= 0) e = minOf(batteryKwh, e + net)
        else {
            val take = minOf(-net, maxOf(0.0, e - floor)); e -= take
            bat = take / h; grid = (-net - take) / h
            if (grid > 0) hitFloor = true
        }
        imp += grid * h
        val s = e / batteryKwh * 100; peak = maxOf(peak, s)
        EstimatePoint(slotName(i), round(pv), round(load), round(bat), round(grid), round(s * 10) / 10)
    }
    return Estimate(pts, round(peak * 10) / 10, round(imp * 100) / 100, peak < 99 || hitFloor)
}
