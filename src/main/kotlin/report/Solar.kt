package ilios.report

import ilios.weather.Day
import java.time.LocalDate

private const val MIN_RATIO = 0.3
private const val MJ_PER_KWH = 3.6

/**
 * Yield assumed until enough measured days exist: the share of the ideal radiation-based yield a system typically
 * delivers.
 */
const val DEFAULT_RATIO = 0.75
private const val MIN_CALIBRATION_DAYS = 3

/**
 * The share of the ideal yield (`kWp × radiation`) the system really delivered, from past days with measured
 * production.
 * Falls back to [DEFAULT_RATIO] with fewer than three usable days, and is kept within 0.3–1.0 so that one odd period
 * cannot
 * produce an absurd forecast. Days with no production or no radiation are ignored (outage, missing data).
 */
fun calibratedRatio(days: List<Day>, produced: Map<LocalDate, Double>, kwp: Double, today: LocalDate): Double {
    if (kwp <= 0) return DEFAULT_RATIO
    val usable = days.filter { it.date < today && it.radiationMj > 0 && produced[it.date] ?: 0.0 > 0 }
    val real = usable.sumOf { produced.getValue(it.date) }
    val ideal = usable.sumOf { kwp * it.radiationMj / 3.6 }
    return if (usable.size >= MIN_CALIBRATION_DAYS &&
        ideal > 0
    ) {
        (real / ideal).coerceIn(MIN_RATIO, 1.0)
    } else {
        DEFAULT_RATIO
    }
}

fun solarKwh(d: Day, kwp: Double, ratio: Double) =
    kwp * (d.radiationMj / MJ_PER_KWH) * ratio // MJ/m² -> kWh/m² = peak sun hours
