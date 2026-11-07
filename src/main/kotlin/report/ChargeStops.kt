package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.SLOTS_PER_DAY
import ilios.SLOT_MINUTES
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * A parameter of the stop model, not a limit of the inverter or the grid (the grid carried 10 kW on days without
 * charging). On the charge nights seen (2026-10-30, 31 and 11-07) load plus charge never passed 6.96 kW and the charge
 * gave way when the load passed about 5 kW; on 11-07 it fell to 0 W (not to the rest of the total) for 10-15 minutes
 * each time the load was about 6 kW. The model therefore charges at full power while load plus power fit in this
 * total and not at all otherwise. What makes the charge give way is not known.
 */
internal const val CHARGE_MODEL_TOTAL_W = 7000.0

private const val NIGHT_SLOTS = 12 * MINUTES_PER_HOUR / SLOT_MINUTES // how far back a past night is looked at
private const val MIN_NIGHTS = 3 // fewer nights say too little about the stops
private const val NEAREST_NIGHTS = 3 // with a temperature forecast: the nights nearest to it count
private const val SLOT_HOURS = SLOT_MINUTES.toDouble() / MINUTES_PER_HOUR

/**
 * How long the charge takes when the house load interrupts it, learnt from the past nights: [nights] hold the
 * consumption (W) of the slots before the charge's end, the slot just before it first, one array per night.
 * Each night is replayed backwards from the end with the charge cut as the model says ([CHARGE_MODEL_TOTAL_W]); the
 * median time counts: the charge is planned for a typical night, so it ends close to the end of the night zone and
 * a worse night ends a few percent short (the timer is capped at the end) instead of the battery sitting full.
 * The stops come from the house's heavy loads, which follow the cold: with the coming night's forecast temperature
 * ([expectedTemp]) and each night's own ([temps], same order) only the [NEAREST_NIGHTS] nights nearest to it count
 * (their median); without them all nights do.
 */
class ChargeStops(
    private val nights: List<DoubleArray>,
    private val temps: List<Double?> = emptyList(),
    private val expectedTemp: Double? = null,
) {
    /** The 5-minute slots before the end that the charge needs to store [missingKwh]. */
    fun slots(missingKwh: Double, powerW: Double, efficiency: Double): Double {
        val plain = missingKwh / (powerW / MILLIS * efficiency) / SLOT_HOURS
        if (missingKwh <= 0 || nights.size < MIN_NIGHTS) return plain
        val times = similar().map { slotsOn(it, missingKwh, powerW, efficiency) }.sorted()
        return max(plain, times[times.size / 2])
    }

    private fun similar(): List<DoubleArray> {
        val expected = expectedTemp ?: return nights
        val known = nights.indices.filter { temps.getOrNull(it) != null }
        if (known.size < MIN_NIGHTS) return nights
        return known.sortedBy { abs(temps[it]!! - expected) }.take(NEAREST_NIGHTS).map { nights[it] }
    }

    private fun slotsOn(night: DoubleArray, missingKwh: Double, powerW: Double, efficiency: Double): Double {
        var left = missingKwh
        for ((k, load) in night.withIndex()) {
            val kwh = if (load + powerW > CHARGE_MODEL_TOTAL_W) 0.0 else powerW * efficiency * SLOT_HOURS / MILLIS
            if (kwh >= left) return k + left / kwh
            left -= kwh
        }
        return night.size + left / (powerW * efficiency * SLOT_HOURS / MILLIS) // beyond the data: no stops
    }

    companion object {
        /** No history: the plain energy ÷ power. */
        val NONE = ChargeStops(emptyList())
    }
}

/**
 * The past nights that end at slot [endSlot] (of the day) for [ChargeStops]: the finished days of [loadByDay]
 * with the day before them, whose evening is part of the night. [minTemps] is each day's lowest temperature (the
 * night's cold is the morning's) and [expected] the coming night's, to pick the nights that are alike.
 */
fun chargeStops(
    loadByDay: Map<LocalDate, Map<String, Double>>,
    endSlot: Int,
    minTemps: Map<LocalDate, Double> = emptyMap(),
    expected: Double? = null,
): ChargeStops {
    val days = loadByDay.keys.filter { loadByDay.containsKey(it.minusDays(1)) }
    val nights = days.map { day ->
        val own = loadByDay.getValue(day)
        val before = loadByDay.getValue(day.minusDays(1))
        DoubleArray(NIGHT_SLOTS) { k ->
            val s = endSlot - 1 - k
            (if (s >= 0) own[slotName(s)] else before[slotName(s + SLOTS_PER_DAY)]) ?: 0.0
        }
    }
    return ChargeStops(nights, days.map { minTemps[it] }, expected)
}
