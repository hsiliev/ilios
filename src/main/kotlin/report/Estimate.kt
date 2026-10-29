package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.PERCENT
import ilios.SLOTS_PER_DAY
import ilios.SLOT_MINUTES
import ilios.db.DEFAULT_RESERVE
import kotlinx.serialization.Serializable
import kotlin.math.round

private const val FULL_SOC = 99 // a charge at or above this counts as full

/** One 5-minute step of the estimate for the rest of today; t is local HH:mm. All powers in W. */
@Serializable
class EstimatePoint(
    val t: String,
    val pvW: Double,
    val loadW: Double,
    val batW: Double,
    val gridW: Double,
    val soc: Double,
    val chargeW: Double = 0.0,
)

/**
 * The rest of today in 5-minute steps: the expected consumption, panel power, battery discharge, grid import and
 * battery charge (%).
 * peakSoc is the highest charge the day is expected to reach (from now), importKwh what the grid has to supply;
 * chargeNeeded when the
 * sun is not expected to fill the battery or the battery runs down to its floor.
 */
@Serializable
class Estimate(val points: List<EstimatePoint>, val peakSoc: Double, val importKwh: Double, val chargeNeeded: Boolean)

/**
 * The grid charging the night plan intends to do (only when the place allows managing the inverter): from [fromSlot]
 * to [toSlot] (slots of
 * the estimate, 288 and up are tomorrow) the battery takes [powerW] from the grid until it holds [targetSoc] %.
 * While it runs, the house is
 * served from the grid too (verified on the inverter: the grid supplies the charge plus the load) and the battery is
 * not discharged.
 */
class PlannedCharge(
    val fromSlot: Int,
    val toSlot: Int,
    val powerW: Double,
    val targetSoc: Double,
    val efficiency: Double = 1.0,
) {
    fun runsAt(slot: Int) = slot in fromSlot until toSlot

    /** The energy the battery of [capacityKwh] holds at the target charge. */
    fun targetKwh(capacityKwh: Double) = targetSoc / PERCENT * capacityKwh
}

/**
 * The slot at which the planned charge starts, as the inverter control does: as late as possible so that it still
 * reaches [targetSoc] before
 * [endSlot] (the missing energy ÷ (power × [efficiency])), or at once ([nowSlot]) when a charge is already running.
 * Null when nothing is missing.
 * [plain] is the estimate without the charge, running at least to [endSlot].
 */
fun plannedChargeStart(
    plain: Estimate,
    nowSlot: Int,
    endSlot: Int,
    batteryKwh: Double,
    targetSoc: Double,
    powerW: Double,
    efficiency: Double,
    running: Boolean,
): Int? {
    if (endSlot <= nowSlot || batteryKwh <= 0 || powerW <= 0) return null
    return (nowSlot until endSlot).firstOrNull { s ->
        val soc = plain.points.getOrNull(s - nowSlot)?.soc ?: return@firstOrNull false
        val missingKwh = (targetSoc - soc) / 100 * batteryKwh
        val slots = missingKwh / (powerW / 1000 * efficiency) * 12 // hours -> 5-minute slots
        // missingKwh <= 0: still above the target, the battery has to drain first
        missingKwh > 0 && (running || s + slots >= endSlot)
    }
}

fun slotOf(t: String): Int {
    val (h, m) = t.split(':')
    return (h.toInt() * MINUTES_PER_HOUR + m.toInt()) / SLOT_MINUTES
}
fun slotName(i: Int) = "%02d:%02d".format(i * SLOT_MINUTES / MINUTES_PER_HOUR, i * SLOT_MINUTES % MINUTES_PER_HOUR)

/**
 * Consumption pattern (W per 5-minute slot, 288 values) as the mean of the previous days' measured consumption at
 * that time, scaled by
 * [weatherScale] (today's expected total over the mean total of those days, so a colder or duller day lifts the
 * whole pattern).
 * Slots without data take the mean of the others. Null without any measured consumption.
 */
fun loadPattern(history: List<Map<String, Double>>, weatherScale: Double): DoubleArray? {
    val sums = DoubleArray(SLOTS_PER_DAY)
    val counts = IntArray(288)
    for (day in history) {
        for ((t, w) in day) {
            val i = slotOf(t)
            if (i in 0 until SLOTS_PER_DAY) {
                sums[i] += w
                counts[i]++
            }
        }
    }
    val have = counts.indices.filter { counts[it] > 0 }
    if (have.isEmpty()) return null
    val mean = have.sumOf { sums[it] / counts[it] }.div(have.size)
    return DoubleArray(SLOTS_PER_DAY) { (if (counts[it] > 0) sums[it] / counts[it] else mean) * weatherScale }
}

private const val SLOT_HOURS_KW = SLOT_MINUTES.toDouble() / MINUTES_PER_HOUR / MILLIS // W over a 5-minute slot -> kWh

/** What happened in one slot (W); [drained] when the house needed the grid because the battery was at its floor. */
private class SlotResult(val batW: Double, val gridW: Double, val chargeW: Double = 0.0, val drained: Boolean = false)

/**
 * The battery while it is simulated: the sun serves the house first, the surplus charges the battery, a shortfall
 * drains it down to its floor.
 */
private class BatterySim(var energyKwh: Double, val capacityKwh: Double, val floorKwh: Double) {
    val soc get() = energyKwh / capacityKwh * 100

    /**
     * One slot with [loadW] and [pvW]; a [charge] that runs now charges from the grid while the battery is under its
     * target.
     */
    fun step(loadW: Double, pvW: Double, charge: PlannedCharge?): SlotResult {
        val net = (pvW - loadW) * SLOT_HOURS_KW
        return when {
            charge != null && energyKwh < charge.targetKwh(capacityKwh) -> {
                chargeFromGrid(charge, net)
            }
            net >= 0 -> {
                energyKwh = minOf(capacityKwh, energyKwh + net)
                SlotResult(0.0, 0.0)
            }
            else -> {
                drain(-net)
            }
        }
    }

    /**
     * The house is served from the grid while the battery charges (verified on the inverter: the grid supplies the
     * charge plus the load).
     */
    private fun chargeFromGrid(charge: PlannedCharge, netKwh: Double): SlotResult {
        val add = minOf(charge.powerW * charge.efficiency * SLOT_HOURS_KW, charge.targetKwh(capacityKwh) - energyKwh)
        energyKwh += add
        val chargeW = add / charge.efficiency / SLOT_HOURS_KW // drawn from the grid
        return SlotResult(0.0, maxOf(0.0, -netKwh / SLOT_HOURS_KW) + chargeW, chargeW)
    }

    private fun drain(shortfallKwh: Double): SlotResult {
        val take = minOf(shortfallKwh, maxOf(0.0, energyKwh - floorKwh))
        energyKwh -= take
        val gridW = (shortfallKwh - take) / SLOT_HOURS_KW
        return SlotResult(take / SLOT_HOURS_KW, gridW, drained = gridW > 0)
    }
}

/**
 * Simulates the battery from [nowSlot] to midnight: the sun serves the house first, the surplus charges the battery
 * (up to 100 %), a
 * shortfall is taken from the battery down to its floor and then from the grid. [pvW] is the panel power forecast
 * per slot (a 15-minute
 * value holds for its three slots). With [untilSlot] past 288 the simulation goes on into the next day (slots 288
 * and up, named by the time
 * of day, so the times wrap); the summary figures still cover today only. A [charge] adds the planned grid charging.
 */
fun estimateDay(
    nowSlot: Int,
    soc: Double,
    batteryKwh: Double,
    pattern: DoubleArray,
    reservedPct: Int = DEFAULT_RESERVE,
    untilSlot: Int = 288,
    charge: PlannedCharge? = null,
    pvW: (Int) -> Double,
): Estimate? {
    if (batteryKwh <= 0 || nowSlot !in 0 until SLOTS_PER_DAY) return null
    val sim = BatterySim(soc / 100 * batteryKwh, batteryKwh, reservedPct / 100.0 * batteryKwh)
    var peak = soc
    var importKwh = 0.0
    var hitFloor = false
    val points = (nowSlot until maxOf(288, untilSlot)).map { i ->
        val pv = pvW(i)
        val r = sim.step(pattern[i], pv, charge?.takeIf { it.runsAt(i) })
        if (i < 288) { // the summary covers today only
            importKwh += r.gridW * SLOT_HOURS_KW
            peak = maxOf(peak, sim.soc)
            hitFloor = hitFloor || r.drained
        }
        EstimatePoint(
            slotName(i % 288),
            round(pv),
            round(pattern[i]),
            round(r.batW),
            round(r.gridW),
            r1(sim.soc),
            round(r.chargeW),
        )
    }
    return Estimate(points, r1(peak), r2(importKwh), peak < FULL_SOC || hitFloor)
}
