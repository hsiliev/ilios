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

/** Night charging advice: charge = grid charging is worth it; kwh = energy to take from the grid; savingEur = what it saves vs. buying that energy at the day price. */
@Serializable
class NightCharge(val charge: Boolean, val kwh: Double, val savingEur: Double, val costEur: Double, val dayPrice: Double, val nightPrice: Double, val text: String)

/** Today's lost saving: kwh = energy the battery lacked at the start of the day and the sun did not make up for; lostEur = that energy at day price minus night price. */
@Serializable
class MissedCharge(val kwh: Double, val lostEur: Double, val text: String)

/**
 * Regulated household tariffs (day, night) in EUR/kWh with network services and 20% VAT, valid 1 July 2026 - 30 June 2027
 * (KEWR decision Ц-13). Update every July. Keys are the suppliers a place can choose in its settings.
 */
val TARIFFS: Map<String, Pair<Double, Double>> = linkedMapOf(
    "electrohold" to (0.15425 to 0.09192),
    "evn" to (0.15458 to 0.09226),
    "energopro" to (0.15512 to 0.09280),
)
const val DEFAULT_SUPPLIER = "electrohold"
val PRICE_DAY = TARIFFS.getValue(DEFAULT_SUPPLIER).first
val PRICE_NIGHT = TARIFFS.getValue(DEFAULT_SUPPLIER).second

/**
 * Whether to charge the battery from the grid tonight at the cheap tariff. The room in the battery is what tomorrow's sun is not
 * expected to fill; that energy bought at night instead of during the day is the saving. Null when the charge is unknown.
 */
fun nightCharge(batteryKwh: Double, soc: Double?, tomorrowKwh: Double?, dayPrice: Double = PRICE_DAY, nightPrice: Double = PRICE_NIGHT): NightCharge? {
    if (batteryKwh <= 0 || soc == null || tomorrowKwh == null) return null
    val room = batteryKwh * (1 - soc.coerceIn(0.0, 100.0) / 100)
    val kwh = (minOf(room, batteryKwh - tomorrowKwh)).coerceAtLeast(0.0)
    if (kwh < 0.5) return NightCharge(false, 0.0, 0.0, 0.0, dayPrice, nightPrice,
        if (room < 0.5) "Батерията е почти пълна – не е нужно нощно зареждане." else "Утре слънцето (≈ ${bg(r1(tomorrowKwh))} kWh) ще напълни батерията – не зареждайте от мрежата.")
    val cost = kwh * nightPrice; val saving = kwh * (dayPrice - nightPrice)
    return NightCharge(true, r1(kwh), round(saving * 100) / 100, round(cost * 100) / 100, dayPrice, nightPrice,
        "Заредете батерията от мрежата през нощта с около ${bg(r1(kwh))} kWh: утре слънцето (≈ ${bg(r1(tomorrowKwh))} kWh) няма да я напълни. " +
            "Нощната цена е ${bg2(nightPrice)} €/kWh срещу ${bg2(dayPrice)} € през деня – ≈ ${bg2(cost)} € разход и ≈ ${bg2(saving)} € икономия.")
}

/** One day of the three-week window (previous, current and next week). kwh is the estimated solar yield; produced is SolaX's measured yield when connected. */
@Serializable
class DayPoint(
    val date: String, val tMax: Double?, val tMin: Double?, val rainMm: Double, val kwh: Double,
    val produced: Double? = null, val ac: Double? = null, // ac: inverter AC output of that day, for comparison with the SolaX app
    val maxSoc: Double? = null, // highest battery charge (%) a past day reached, from SolaX's battery history
)

/** One step of today's forecast at the finest resolution Open-Meteo offers. event: thunder | snow | rain | wind | cloud */
@Serializable
class IntraPoint(val t: String, val temp: Double?, val rainMm: Double, val event: String?, val radWm2: Double? = null)

@Serializable
class Intraday(val now: String, val stepMin: Int, val points: List<IntraPoint>)

/** One 5-minute step of today's battery charge (%) and panel power (W) from SolaX; t is local HH:mm. */
@Serializable
class TodayPoint(val t: String, val soc: Double?, val pvW: Double?, val batW: Double? = null)

@Serializable
class Report(
    val id: Long, val name: String, val solar: Boolean = false, val today: String = "", val days: List<DayPoint> = emptyList(),
    val watering: Watering? = null, val battery: Battery? = null, val intraday: Intraday? = null, val error: String? = null, val country: String? = null,
    val batteryToday: List<TodayPoint> = emptyList(), val pvForecast: List<TodayPoint> = emptyList(),
    val night: NightCharge? = null, val missed: MissedCharge? = null,
)

private fun r1(x: Double) = round(x * 10) / 10

/** One decimal with the Bulgarian decimal comma, for display texts. */
private fun bg(x: Double) = r1(x).toString().replace('.', ',')
private fun bg2(x: Double) = "%.2f".format(java.util.Locale.ROOT, x).replace('.', ',')

/**
 * What not charging the battery at night cost today. The charge at the first reading of the day shows what was missing; if the
 * sun did not bring the battery near full later (max charge below 95%), that missing energy had to come from the grid at day price.
 */
fun missedCharge(batteryKwh: Double, pts: List<TodayPoint>, dayPrice: Double = PRICE_DAY, nightPrice: Double = PRICE_NIGHT): MissedCharge? {
    val socs = pts.mapNotNull { it.soc }
    if (batteryKwh <= 0 || socs.isEmpty()) return null
    val start = socs.first().coerceIn(0.0, 100.0); val peak = socs.max()
    val room = batteryKwh * (1 - start / 100)
    if (room < 0.5 || peak >= 95) return MissedCharge(0.0, 0.0, "Батерията е започнала деня с ${bg(start)}% и слънцето я е напълнило – няма пропусната икономия.")
    val lost = room * (dayPrice - nightPrice)
    return MissedCharge(r1(room), round(lost * 100) / 100,
        "Батерията е започнала деня с ${bg(start)}% и не е стигнала 100% (макс. ${bg(peak)}%). Липсващите ≈ ${bg(room)} kWh не са заредени през нощта по ${bg2(nightPrice)} €/kWh – пропусната икономия ≈ ${bg2(lost)} €.")
}

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
    maxSoc: Map<LocalDate, Double> = emptyMap(), batteryToday: List<TodayPoint> = emptyList(),
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
    return Report(loc.id, loc.name, loc.solar, today.toString(), points, watering, battery, intraday, country = loc.country,
        batteryToday = if (battery != null) batteryToday else emptyList(),
        // expected panel power of today's 15-minute steps: installed kWp x radiation (W/m² over 1000) x the calibrated yield ratio
        pvForecast = if (battery != null && kwp > 0) intraday?.points.orEmpty().filter { it.radWm2 != null }
            .map { TodayPoint(it.t, null, round(kwp * it.radWm2!! * ratio)) } else emptyList(),
        night = if (battery != null) {
                    val (dayPrice, nightPrice) = TARIFFS[loc.supplier] ?: TARIFFS.getValue(DEFAULT_SUPPLIER)
                    nightCharge(battery.batteryKwh, battery.soc, points.find { it.date == today.plusDays(1).toString() }?.kwh, dayPrice, nightPrice)
                } else null,
        missed = if (battery != null) {
                    val (dayPrice, nightPrice) = TARIFFS[loc.supplier] ?: TARIFFS.getValue(DEFAULT_SUPPLIER)
                    missedCharge(battery.batteryKwh, batteryToday, dayPrice, nightPrice)
                } else null)
}
