package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.SLOTS_PER_DAY
import ilios.SLOT_MINUTES
import ilios.solax.LoadSlot
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

private const val NIGHT_SLOTS = 12 * MINUTES_PER_HOUR / SLOT_MINUTES // how far back a past night is looked at
private const val MIN_NIGHTS = 3 // fewer charge nights say too little about when the charge gives way
private const val SLOT_HOURS = SLOT_MINUTES.toDouble() / MINUTES_PER_HOUR

/**
 * Slots of stops added per degree the forecast is colder than the night used (taken away when it is warmer): the
 * stops rise steeply with the cold (2026-11-08, 1.2 °C: about 16 slots; 11-07, 2.2 °C: 12; 3-7 slots on the 4-9 °C
 * nights) and the closest night cannot say more than it had. A parameter between the fit over all nights (1.25
 * slots per degree) and over the nights up to 5 °C (3.3).
 */
internal const val STOP_SLOTS_PER_DEGREE = 2.0

/**
 * How long the charge takes when the house load interrupts it. [limitW] is the highest house load at which the charge
 * still went on in past nights (see [maxChargingLoad]; null when it is not known: no stops are assumed): in a slot
 * whose load is above it the charge pauses. [nights] hold the consumption (W) of the slots before the charge's end,
 * the slot just before it first, one array per night. The stops come from the house's heavy loads, which follow the
 * cold: with the coming night's forecast temperature ([expectedTemp]) and each night's own ([temps], same order)
 * the night **closest in temperature** is replayed backwards from the end (the latest on a tie), and
 * [STOP_SLOTS_PER_DEGREE] slots are added per degree it was colder than the forecast (taken away when warmer, never
 * below the plain time). Without a forecast or temperatures the median night of all counts.
 */
class ChargeStops(
    private val nights: List<DoubleArray>,
    private val limitW: Double?,
    private val temps: List<Double?> = emptyList(),
    private val expectedTemp: Double? = null,
) {
    /** The 5-minute slots before the end that the charge needs to store [missingKwh]. */
    fun slots(missingKwh: Double, powerW: Double, efficiency: Double): Double {
        val plain = missingKwh / (powerW / MILLIS * efficiency) / SLOT_HOURS
        val pick = pick(missingKwh, powerW, efficiency) ?: return plain
        return max(plain, slotsOn(pick.night, pick.limit, missingKwh, powerW, efficiency) + pick.extraSlots)
    }

    /**
     * In which of the slots before the charge's end (the slot just before it first) the charge pauses, as in the night
     * the time is worked out from; empty without history. The chart draws these pauses.
     */
    fun cutSlots(missingKwh: Double, powerW: Double, efficiency: Double): BooleanArray =
        pick(missingKwh, powerW, efficiency)?.let { p -> BooleanArray(p.night.size) { p.night[it] > p.limit } }
            ?: BooleanArray(0)

    /** The night that counts and the slots added for its temperature; null when there is nothing to replay. */
    private fun pick(missingKwh: Double, powerW: Double, efficiency: Double): Pick? {
        val limit = limitW ?: return null
        if (missingKwh <= 0 || nights.isEmpty()) return null
        val closest = closest()
        if (closest != null) return Pick(nights[closest.first], limit, STOP_SLOTS_PER_DEGREE * closest.second)
        val byTime = nights.sortedBy { slotsOn(it, limit, missingKwh, powerW, efficiency) }
        return Pick(byTime[byTime.size / 2], limit, 0.0)
    }

    private class Pick(val night: DoubleArray, val limit: Double, val extraSlots: Double)

    /** The index of the night closest in temperature to the forecast and how much colder it was; null if unknown. */
    private fun closest(): Pair<Int, Double>? {
        val expected = expectedTemp ?: return null
        return nights.indices.reversed().mapNotNull { i ->
            temps.getOrNull(i)?.let { i to it - expected }
        }.minByOrNull { abs(it.second) }
    }

    private fun slotsOn(
        night: DoubleArray,
        limit: Double,
        missingKwh: Double,
        powerW: Double,
        efficiency: Double,
    ): Double {
        var left = missingKwh
        val full = powerW * efficiency * SLOT_HOURS / MILLIS
        for ((k, load) in night.withIndex()) {
            val kwh = if (load > limit) 0.0 else full
            if (kwh >= left) return k + left / kwh
            left -= kwh
        }
        return night.size + left / full // beyond the data: no stops
    }

    companion object {
        /** No history: the plain energy ÷ power. */
        val NONE = ChargeStops(emptyList(), null)
    }
}

/**
 * The highest house load (W) at which the night charge still went on, from the past nights [nights] (the slots before
 * the charge's end, the slot just before it first): a slot charging at [chargingW] or more is one where it went on.
 * Above this load the charge pauses (it is zero above about 5.7 kW on the owner's inverter, where the single-phase
 * heat pump fills the phase). Null with fewer than [MIN_NIGHTS] nights that charged.
 */
internal fun maxChargingLoad(nights: List<List<LoadSlot?>>, chargingW: Double): Double? {
    val charged = nights.map { night -> night.filterNotNull().filter { it.chargeW >= chargingW } }
        .filter { it.isNotEmpty() }
    if (charged.size < MIN_NIGHTS) return null
    return charged.flatten().maxOf { it.loadW }
}

/** The finished days that have a day before them, and their nights as slots (see [chargeStops]). */
private fun nightsOf(
    history: Map<LocalDate, Map<String, LoadSlot>>,
    endSlot: Int,
): Pair<List<LocalDate>, List<List<LoadSlot?>>> {
    val days = history.keys.filter { history.containsKey(it.minusDays(1)) }
    return days to days.map { day ->
        val own = history.getValue(day)
        val before = history.getValue(day.minusDays(1))
        List(NIGHT_SLOTS) { k ->
            val s = endSlot - 1 - k
            if (s >= 0) own[slotName(s)] else before[slotName(s + SLOTS_PER_DAY)]
        }
    }
}

/**
 * The past nights that end at slot [endSlot] (of the day) for [ChargeStops]: the finished days of [history] with the
 * day before them, whose evening is part of the night. [minTemps] is each day's lowest temperature (the night's cold
 * is the morning's) and [expected] the coming night's. The load limit is worked out from the same nights (a charging
 * slot is one at half of [maxChargeW] or more).
 */
fun chargeStops(
    history: Map<LocalDate, Map<String, LoadSlot>>,
    endSlot: Int,
    minTemps: Map<LocalDate, Double>,
    expected: Double?,
    maxChargeW: Int,
): ChargeStops {
    val (days, slots) = nightsOf(history, endSlot)
    val nights = slots.map { night -> DoubleArray(night.size) { night[it]?.loadW ?: 0.0 } }
    return ChargeStops(nights, maxChargingLoad(slots, maxChargeW / 2.0), days.map { minTemps[it] }, expected)
}

/** The load limit (W) as worked out from [history], for the settings; null until known. */
fun measuredChargingLoadW(history: Map<LocalDate, Map<String, LoadSlot>>, endSlot: Int, maxChargeW: Int): Double? =
    maxChargingLoad(nightsOf(history, endSlot).second, maxChargeW / 2.0)
