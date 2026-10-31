package ilios.report

import ilios.PERCENT
import kotlinx.serialization.Serializable

private const val NOON = 12
private const val SUMMER_NIGHT_FROM = 23
private const val SUMMER_NIGHT_TO = 7
private const val WINTER_NIGHT_FROM = 22
private const val WINTER_NIGHT_TO = 6

/**
 * The cheap night tariff of a date as local hours (from, to): 22:00-06:00 in winter time, 23:00-07:00 in summer time.
 */
fun nightZone(date: java.time.LocalDate, zone: java.time.ZoneId): Pair<Int, Int> =
    if (zone.rules.isDaylightSavings(date.atTime(NOON, 0).atZone(zone).toInstant())) {
        SUMMER_NIGHT_FROM to SUMMER_NIGHT_TO
    } else {
        WINTER_NIGHT_FROM to WINTER_NIGHT_TO
    }

/**
 * What to put into the battery at night so that the next day needs no daytime grid energy. [dayStartKwh] covers the
 * hours from the start of the
 * day zone until the sun ([sunAt]) can power the whole house; [solarKwh] covers the later hours the sun cannot make
 * up for, until the night zone
 * starts. [totalKwh] is their sum (may exceed the battery: then the target is a full battery); [targetSoc] is the
 * charge (%) to reach.
 */
@Serializable
class Compensation(
    val dayStartKwh: Double,
    val solarKwh: Double,
    val totalKwh: Double,
    val sunAt: String?,
    val targetSoc: Double,
)

/**
 * Walks the day zone ([dayStart] to [nightStart], in 5-minute slots) with the battery at its reserve: the running
 * balance of panel power minus
 * consumption goes down while the sun is short, and its lowest point is the energy the battery needs in advance (the
 * surplus in between is
 * used up by the later shortfall). The battery's size is ignored while walking so a total above the capacity can be
 * told.
 */
fun compensation(
    pattern: DoubleArray,
    pvW: (Int) -> Double,
    dayStart: Int,
    nightStart: Int,
    batteryKwh: Double,
    reservedPct: Int,
): Compensation {
    val h = 5.0 / 60 / 1000
    var bal = 0.0
    var low = 0.0
    for (i in dayStart until nightStart) {
        bal += (pvW(i) - pattern[i]) * h
        low = minOf(low, bal)
    }
    val total = -low
    val sun = (dayStart until nightStart).firstOrNull { i ->
        (0..2).all { k ->
            i + k < nightStart &&
                pvW(i + k) >= pattern[i + k]
        }
    }
    val first = if (sun == null) total else (dayStart until sun).sumOf { maxOf(0.0, pattern[it] - pvW(it)) } * h
    val usable = batteryKwh * (100 - reservedPct) / 100
    val soc = if (batteryKwh > 0 &&
        total > 0
    ) {
        minOf(100.0, reservedPct + minOf(total, usable) / batteryKwh * 100)
    } else {
        reservedPct.toDouble()
    }
    val d1 = minOf(first, total)
    return Compensation(r2(d1), r2(maxOf(0.0, total - d1)), r2(total), sun?.let(::slotName), r1(soc))
}

/**
 * The charge (%) the night plan aims for: the compensation's level plus what the house is expected to use from the
 * battery during the
 * [gapSlots] before the day zone starts at slot [dayStart] (the charge ends that much earlier); a full battery when
 * the total exceeds it.
 */
fun nightTargetSoc(c: Compensation, pattern: DoubleArray, dayStart: Int, gapSlots: Int, batteryKwh: Double): Double {
    val gapKwh = (dayStart - gapSlots until dayStart).sumOf { pattern[it] } * 5 / 60 / 1000
    return if (c.totalKwh >
        0
    ) {
        minOf(PERCENT.toDouble(), r1(c.targetSoc + gapKwh / batteryKwh * PERCENT))
    } else {
        c.targetSoc
    }
}

/** The stored plan for one night: the compensation energy calculated while the battery sat at its minimum. */
@Serializable
class NightPlan(
    val day: String,
    val foundAt: Long,
    val checkedAt: Long,
    val dayStartKwh: Double,
    val solarKwh: Double,
    val totalKwh: Double,
    val targetSoc: Double,
    val sunAt: String?,
    val stopBy: String,
    val socNow: Double?,
    val reached: Boolean,
    val chargeUntil: Long? = null,
    val control: String? = null,
    val startBy: Long? = null,
)
