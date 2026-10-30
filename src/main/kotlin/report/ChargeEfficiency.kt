package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.PERCENT
import ilios.SLOTS_PER_HOUR
import ilios.SLOT_MINUTES
import ilios.db.MAX_CHARGE_EFF_PCT
import ilios.db.MIN_CHARGE_EFF_PCT
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private const val MIN_CHARGING_W = 300.0 // below this the battery is not really being charged
private const val FULL_SOC = 99.0 // near full the inverter tapers and the charge figure jumps
private const val MIN_CHARGED_KWH = 1.0 // less charging than this is too little to measure
private const val SLOT_KWH_PER_W = SLOT_MINUTES.toDouble() / MINUTES_PER_HOUR / MILLIS

/** One stored 5-minute step of a past day: battery charge (%) and charging power (W, 0 while discharging). */
class ChargePoint(val day: LocalDate, val t: String, val soc: Double?, val chargeW: Double?)

/**
 * The share (%) of the charging power that ended up stored, measured on the night-zone (grid) charging in [points]:
 * the charge gained × [capacityKwh] ÷ the energy that went in, over pairs of consecutive steps that both charge. Null
 * when there was too little charging to tell.
 */
fun measuredChargeEffPct(points: List<ChargePoint>, capacityKwh: Double, zone: ZoneId): Int? {
    var stored = 0.0
    var charged = 0.0
    for ((day, steps) in points.groupBy { it.day }) {
        val (from, to) = nightZone(day, zone)
        for ((a, b) in steps.sortedBy { it.t }.zipWithNext()) {
            val slot = slotOf(a.t)
            val night = slot >= from * SLOTS_PER_HOUR || slot < to * SLOTS_PER_HOUR
            val (gain, w) = (if (night && slotOf(b.t) == slot + 1) charging(a, b) else null) ?: continue
            stored += gain / PERCENT * capacityKwh
            charged += w * SLOT_KWH_PER_W
        }
    }
    if (charged < MIN_CHARGED_KWH) return null
    return (stored / charged * PERCENT).roundToInt().coerceIn(MIN_CHARGE_EFF_PCT, MAX_CHARGE_EFF_PCT)
}

/** The charge gained (%) and the mean charging power (W) between two steps that both charge, below full. */
private fun charging(a: ChargePoint, b: ChargePoint): Pair<Double, Double>? {
    val socA = a.soc ?: return null
    val socB = b.soc ?: return null
    val wA = a.chargeW ?: return null
    val wB = b.chargeW ?: return null
    return if (minOf(wA, wB) < MIN_CHARGING_W || socB >= FULL_SOC) null else socB - socA to (wA + wB) / 2
}
