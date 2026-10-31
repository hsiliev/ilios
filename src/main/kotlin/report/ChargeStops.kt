package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.SLOTS_PER_DAY
import ilios.SLOT_MINUTES
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * A parameter of the stop model, not a limit of the inverter or the grid (the grid carried 10 kW on days without
 * charging). On the two charge nights seen (2026-10-30 and 31) the charge fell when the house load passed about 5 kW,
 * and load plus charge never passed 6.96 kW; the model keeps the charge at power or at this total minus the load,
 * whichever is less. What makes the charge give way is not known.
 */
internal const val CHARGE_MODEL_TOTAL_W = 7000.0

private const val NIGHT_SLOTS = 12 * MINUTES_PER_HOUR / SLOT_MINUTES // how far back a past night is looked at
private const val MIN_NIGHTS = 3 // fewer nights say too little about the stops
private const val SLOT_HOURS = SLOT_MINUTES.toDouble() / MINUTES_PER_HOUR

/**
 * How long the charge takes when the house load interrupts it, learnt from the past nights: [nights] hold the
 * consumption (W) of the slots before the charge's end, the slot just before it first, one array per night.
 * Each night is replayed backwards from the end with the charge cut as the model says ([CHARGE_MODEL_TOTAL_W]); the
 * second longest time counts, so one unusual night does not set the start but the recurring peaks do.
 */
class ChargeStops(private val nights: List<DoubleArray>) {
    /** The 5-minute slots before the end that the charge needs to store [missingKwh]. */
    fun slots(missingKwh: Double, powerW: Double, efficiency: Double): Double {
        val plain = missingKwh / (powerW / MILLIS * efficiency) / SLOT_HOURS
        if (missingKwh <= 0 || nights.size < MIN_NIGHTS) return plain
        val longest = nights.map { slotsOn(it, missingKwh, powerW, efficiency) }.sortedDescending()
        return max(plain, longest[1])
    }

    private fun slotsOn(night: DoubleArray, missingKwh: Double, powerW: Double, efficiency: Double): Double {
        var left = missingKwh
        for ((k, load) in night.withIndex()) {
            val kwh = min(powerW, max(0.0, CHARGE_MODEL_TOTAL_W - load)) * efficiency * SLOT_HOURS / MILLIS
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
 * with the day before them, whose evening is part of the night.
 */
fun chargeStops(loadByDay: Map<LocalDate, Map<String, Double>>, endSlot: Int): ChargeStops = ChargeStops(
    loadByDay.mapNotNull { (day, own) ->
        val before = loadByDay[day.minusDays(1)] ?: return@mapNotNull null
        DoubleArray(NIGHT_SLOTS) { k ->
            val s = endSlot - 1 - k
            (if (s >= 0) own[slotName(s)] else before[slotName(s + SLOTS_PER_DAY)]) ?: 0.0
        }
    },
)
