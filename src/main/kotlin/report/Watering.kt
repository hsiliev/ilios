package ilios.report

import ilios.WEEK_DAYS
import ilios.mondayOf
import ilios.weather.Day
import java.time.LocalDate

private const val LIGHT_SHARE = 0.3 // a deficit below this share of the need only calls for light watering

/** Weekly water need (mm) and how much of the last week's rain still counts (deep-rooted trees keep more). */
private class Crop(val needMm: Double, val carry: Double)

private val PLANTS = Crop(20.0, 0.5)
private val GRASS = Crop(25.0, 0.5)
private val TREES = Crop(15.0, 1.0)

/** The days of this list that fall between [from] and [to], both included. */
internal fun List<Day>.between(from: LocalDate, to: LocalDate) = filter { it.date in from..to }

/**
 * Watering advice for plants, grass and trees: the rain of the last 7 days (partly) plus the rain expected in the
 * next 7 against a weekly
 * need that grows when the coming week is hot and shrinks when it is cool. Also the rain so far and still expected
 * over the three-week window.
 */
fun wateringFor(days: List<Day>, today: LocalDate): Watering {
    val monday = mondayOf(today)
    val recent = days.between(today.minusDays(6), today).sumOf { it.rainMm }
    val ahead = days.between(today.plusDays(1), today.plusDays(7))
    val aheadRain = ahead.sumOf { it.rainMm }
    val heat = ahead.mapNotNull { it.tMax }.average().takeUnless { it.isNaN() } ?: 20.0
    val heatFactor = when {
        heat > 30 -> 1.3
        heat < 15 -> 0.6
        else -> 1.0
    }

    fun advise(crop: Crop): Advice {
        val need = crop.needMm * heatFactor
        val deficit = need - (crop.carry * recent + aheadRain)
        return when {
            deficit <= 0 -> Advice(
                "no",
                "Не е нужно поливане: през последните 7 дни са паднали ${bg(
                    recent,
                )} мм, а през следващите 7 се очакват ${bg(aheadRain)} мм.",
            )
            deficit < LIGHT_SHARE * need -> Advice("light", "Леко поливане по желание (около ${bg(deficit)} мм).")
            else -> Advice(
                "yes",
                "Поливайте сега: за следващата седмица липсват около ${bg(deficit)} мм (нужни са ${bg(need)} мм).",
            )
        }
    }

    return Watering(
        rainSoFarMm = r1(days.between(monday.minusDays(WEEK_DAYS), today).sumOf { it.rainMm }),
        rainExpectedMm = r1(days.between(today.plusDays(1), monday.plusDays(WEEK_DAYS * 2 - 1)).sumOf { it.rainMm }),
        plants = advise(PLANTS),
        grass = advise(GRASS),
        trees = advise(TREES),
    )
}
