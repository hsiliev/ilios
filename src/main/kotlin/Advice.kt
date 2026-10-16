package ilios

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.round

@Serializable
class Advice(val water: String, val text: String) // water: "yes" | "light" | "no"

@Serializable
class Watering(val rainSoFarMm: Double, val rainExpectedMm: Double, val plants: Advice, val grass: Advice, val trees: Advice)

/** batteryKwh is 0 when the plant has no battery (or SolaX does not report one). */
@Serializable
class Battery(
    val batteryKwh: Double, val soc: Double? = null, val remainingKwh: Double? = null,
    val powerW: Double? = null, val readAt: Long? = null, // charge state at the last SolaX sync; power > 0 = charging
)

/** One day of the three-week window (previous, current and next week). kwh is the estimated solar yield; produced is SolaX's measured yield when connected. */
@Serializable
class DayPoint(
    val date: String, val tMax: Double?, val tMin: Double?, val rainMm: Double, val kwh: Double,
    val produced: Double? = null, val ac: Double? = null, // ac: inverter AC output of that day, for comparison with the SolaX app
    val maxSoc: Double? = null, // highest battery charge (%) a past day reached, from SolaX's battery history
)

/** One step of today's forecast at the finest resolution Open-Meteo offers. event: thunder | snow | rain | wind | cloud */
@Serializable
class IntraPoint(val t: String, val temp: Double?, val rainMm: Double, val event: String?)

@Serializable
class Intraday(val now: String, val stepMin: Int, val points: List<IntraPoint>)

@Serializable
class Report(
    val id: Long, val name: String, val solar: Boolean = false, val today: String = "", val days: List<DayPoint> = emptyList(),
    val watering: Watering? = null, val battery: Battery? = null, val intraday: Intraday? = null, val error: String? = null,
)

private fun r1(x: Double) = round(x * 10) / 10

/** One decimal with the Bulgarian decimal comma, for display texts. */
private fun bg(x: Double) = r1(x).toString().replace('.', ',')

/** Weekly water need (mm) and how much of the last week's rain still counts (deep-rooted trees keep more). */
private class Crop(val needMm: Double, val carry: Double)

private val crops = mapOf("plants" to Crop(20.0, 0.5), "grass" to Crop(25.0, 0.5), "trees" to Crop(15.0, 1.0))

/** Yield assumed until enough measured days exist: the share of the ideal radiation-based yield a system typically delivers. */
const val DEFAULT_RATIO = 0.75
private const val MIN_CALIBRATION_DAYS = 3

/**
 * The share of the ideal yield (`kWp × radiation`) the system really delivered, from past days with measured production.
 * Falls back to [DEFAULT_RATIO] with fewer than three usable days, and is kept within 0.3–1.0 so that one odd period cannot
 * produce an absurd forecast. Days with no production or no radiation are ignored (outage, missing data).
 */
fun calibratedRatio(days: List<Day>, produced: Map<LocalDate, Double>, kwp: Double, today: LocalDate): Double {
    if (kwp <= 0) return DEFAULT_RATIO
    var real = 0.0; var ideal = 0.0; var n = 0
    for (d in days) {
        val p = produced[d.date] ?: continue
        if (d.date >= today || p <= 0 || d.radiationMj <= 0) continue
        real += p; ideal += kwp * d.radiationMj / 3.6; n++
    }
    return if (n >= MIN_CALIBRATION_DAYS && ideal > 0) (real / ideal).coerceIn(0.3, 1.0) else DEFAULT_RATIO
}

fun solarKwh(d: Day, kwp: Double, ratio: Double) = kwp * (d.radiationMj / 3.6) * ratio // MJ/m² -> kWh/m² = peak sun hours

fun buildReport(
    loc: Loc, days: List<Day>, today: LocalDate, intraday: Intraday?,
    produced: Map<LocalDate, Double> = emptyMap(), plant: SolaxPlant? = null, ac: Map<LocalDate, Double> = emptyMap(),
    maxSoc: Map<LocalDate, Double> = emptyMap(),
): Report {
    val kwp = plant?.kwp ?: 0.0 // installed PV power comes from SolaX; without it there is no estimate
    val ratio = calibratedRatio(days, produced, kwp, today)
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun range(from: LocalDate, to: LocalDate) = days.filter { it.date in from..to }
    val points = range(monday.minusDays(7), monday.plusDays(13)).map {
        DayPoint(it.date.toString(), it.tMax, it.tMin, r1(it.rainMm), r1(solarKwh(it, kwp, ratio)), produced[it.date]?.let(::r1), ac[it.date]?.let(::r1), maxSoc[it.date]?.let(::r1))
    }

    // Rain: accumulated from the start of last week through today vs. expected from tomorrow to the end of next week.
    val soFar = range(monday.minusDays(7), today).sumOf { it.rainMm }
    val expected = range(today.plusDays(1), monday.plusDays(13)).sumOf { it.rainMm }
    val recent = range(today.minusDays(6), today).sumOf { it.rainMm }
    val ahead = range(today.plusDays(1), today.plusDays(7))
    val aheadRain = ahead.sumOf { it.rainMm }
    val heat = ahead.mapNotNull { it.tMax }.average().takeUnless { it.isNaN() } ?: 20.0
    val heatFactor = if (heat > 30) 1.3 else if (heat < 15) 0.6 else 1.0

    fun advise(c: Crop): Advice {
        val need = c.needMm * heatFactor
        val deficit = need - (c.carry * recent + aheadRain)
        return when {
            deficit <= 0 -> Advice("no", "Не е нужно поливане: през последните 7 дни са паднали ${bg(recent)} мм, а през следващите 7 се очакват ${bg(aheadRain)} мм.")
            deficit < 0.3 * need -> Advice("light", "Леко поливане по желание (около ${bg(deficit)} мм).")
            else -> Advice("yes", "Поливайте сега: за следващата седмица липсват около ${bg(deficit)} мм (нужни са ${bg(need)} мм).")
        }
    }
    val watering = Watering(r1(soFar), r1(expected), advise(crops.getValue("plants")), advise(crops.getValue("grass")), advise(crops.getValue("trees")))

    val battery = if (loc.solar && kwp > 0) Battery(plant?.batteryKwh ?: 0.0, plant?.soc, plant?.remainingKwh, plant?.powerW, plant?.readAt) else null
    return Report(loc.id, loc.name, loc.solar, today.toString(), points, watering, battery, intraday)
}
