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

@Serializable
class Battery(val batteryKwh: Double)

/** One day of the three-week window (previous, current and next week). kwh is the estimated solar yield. */
@Serializable
class DayPoint(val date: String, val tMax: Double?, val tMin: Double?, val rainMm: Double, val kwh: Double)

/** One step of today's forecast at the finest resolution Open-Meteo offers. event: thunder | snow | rain | wind | cloud */
@Serializable
class IntraPoint(val t: String, val temp: Double?, val rainMm: Double, val event: String?)

@Serializable
class Intraday(val now: String, val stepMin: Int, val points: List<IntraPoint>)

@Serializable
class Report(
    val id: Long, val name: String, val today: String = "", val days: List<DayPoint> = emptyList(),
    val watering: Watering? = null, val battery: Battery? = null, val intraday: Intraday? = null, val error: String? = null,
)

private fun r1(x: Double) = round(x * 10) / 10

/** One decimal with the Bulgarian decimal comma, for display texts. */
private fun bg(x: Double) = r1(x).toString().replace('.', ',')

/** Weekly water need (mm) and how much of the last week's rain still counts (deep-rooted trees keep more). */
private class Crop(val needMm: Double, val carry: Double)

private val crops = mapOf("plants" to Crop(20.0, 0.5), "grass" to Crop(25.0, 0.5), "trees" to Crop(15.0, 1.0))

fun solarKwh(d: Day, kwp: Double, ratio: Double) = kwp * (d.radiationMj / 3.6) * ratio // MJ/m² -> kWh/m² = peak sun hours

fun buildReport(loc: Loc, days: List<Day>, today: LocalDate, intraday: Intraday?): Report {
    val kwp = loc.kwp
    val ratio = loc.ratio
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun range(from: LocalDate, to: LocalDate) = days.filter { it.date in from..to }
    val points = range(monday.minusDays(7), monday.plusDays(13)).map {
        DayPoint(it.date.toString(), it.tMax, it.tMin, r1(it.rainMm), r1(solarKwh(it, kwp, ratio)))
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

    return Report(loc.id, loc.name, today.toString(), points, watering, if (loc.solar) Battery(loc.batteryKwh) else null, intraday)
}
