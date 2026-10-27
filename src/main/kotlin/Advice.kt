package ilios

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
fun nightCharge(
    batteryKwh: Double, soc: Double?, tomorrowKwh: Double?, dayPrice: Double = PRICE_DAY, nightPrice: Double = PRICE_NIGHT, homeKwh: Double = 0.0,
): NightCharge? {
    if (batteryKwh <= 0 || soc == null || tomorrowKwh == null) return null
    val room = batteryKwh * (1 - soc.coerceIn(0.0, 100.0) / 100)
    val sunKwh = (tomorrowKwh - homeKwh).coerceAtLeast(0.0) // what is left for the battery after the home (heat pump...) has used its share
    val kwh = (minOf(room, batteryKwh - sunKwh)).coerceAtLeast(0.0)
    val home = if (homeKwh > 0.05) " след ≈ ${bg(r1(homeKwh))} kWh консумация на къщата през деня" else ""
    if (kwh < 0.5) return NightCharge(false, 0.0, 0.0, 0.0, dayPrice, nightPrice,
        if (room < 0.5) "Батерията е почти пълна – не е нужно нощно зареждане." else "Утре слънцето (≈ ${bg(r1(tomorrowKwh))} kWh)$home ще напълни батерията – не зареждайте от мрежата.")
    val cost = kwh * nightPrice; val saving = kwh * (dayPrice - nightPrice)
    return NightCharge(true, r1(kwh), round(saving * 100) / 100, round(cost * 100) / 100, dayPrice, nightPrice,
        "Заредете батерията от мрежата през нощта с около ${bg(r1(kwh))} kWh: утре слънцето (≈ ${bg(r1(tomorrowKwh))} kWh)$home няма да я напълни. " +
            "Нощната цена е ${bg2(nightPrice)} €/kWh срещу ${bg2(dayPrice)} € през деня – ≈ ${bg2(cost)} € разход и ≈ ${bg2(saving)} € икономия.")
}

/** One day of the three-week window (previous, current and next week). kwh is the estimated solar yield; produced is SolaX's measured yield when connected. */
@Serializable
class DayPoint(
    val date: String, val tMax: Double?, val tMin: Double?, val rainMm: Double, val kwh: Double,
    val produced: Double? = null, val ac: Double? = null, // ac: inverter AC output of that day, for comparison with the SolaX app
    val maxSoc: Double? = null, // highest battery charge (%) a past day reached, from SolaX's battery history
    val loadKwh: Double? = null, // past days: the home's measured total consumption
    val loadEst: Double? = null, // today and later: estimated total consumption from the temperature and the sun, learnt from past days
    val homeKwh: Double? = null, // today and later: expected daytime consumption of the house, which the sun has to cover before the battery gets any
    val plan: NightPlan? = null, // the night-charge plan worked out for this day, when the battery was found at its minimum
    val badCloud: Double? = null, // past days: the impossible production (kWh) SolaX returned, ignored in favour of the estimate
)

/** One step of today's forecast at the finest resolution Open-Meteo offers. event: thunder | snow | rain | wind | cloud */
@Serializable
class IntraPoint(val t: String, val temp: Double?, val rainMm: Double, val event: String?, val radWm2: Double? = null)

@Serializable
class Intraday(val now: String, val stepMin: Int, val points: List<IntraPoint>, val prev: List<IntraPoint> = emptyList(), val next: List<IntraPoint> = emptyList())

/** One 5-minute step of today's battery charge (%) and panel power (W) from SolaX; t is local HH:mm. */
@Serializable
class TodayPoint(val t: String, val soc: Double?, val pvW: Double?, val batW: Double? = null, val gridW: Double? = null)

/** The battery's expected discharge power (W) at a time of day (HH:mm). */
@Serializable
class DischargePoint(val t: String, val w: Double)

/** One stored 5-minute step of a finished day: battery discharge, home consumption and power drawn from the grid, in W (null = not reported). */
class LoadPoint(val t: String, val dischargeW: Double, val loadW: Double?, val gridW: Double?)

@Serializable
class Report(
    val id: Long, val name: String, val solar: Boolean = false, val today: String = "", val days: List<DayPoint> = emptyList(),
    val watering: Watering? = null, val battery: Battery? = null, val intraday: Intraday? = null, val error: String? = null, val country: String? = null,
    val batteryToday: List<TodayPoint> = emptyList(), val pvForecast: List<TodayPoint> = emptyList(),
    val batteryPrev: List<TodayPoint> = emptyList(), val pvForecastNext: List<TodayPoint> = emptyList(), // yesterday's charge and tomorrow's panel forecast, for the 24-hour window
    val night: NightCharge? = null, val missed: MissedCharge? = null, val dischargeProfile: List<DischargePoint> = emptyList(),
    val nightFrom: Int = 22, val nightTo: Int = 6, // the cheap night tariff today, local hours
    val estimate: Estimate? = null, // the rest of today in 5-minute steps, from the previous days' consumption pattern, the weather and the solar forecast
)

/** Share of a day's consumption that falls while the sun is up (the rest is evening and night, when the battery serves it anyway). */
const val DAYTIME_LOAD_SHARE = 0.6

/** Expected daytime consumption: the mean of the last 7 finished days' measured consumption (the heat pump included) times [DAYTIME_LOAD_SHARE]. */
fun homeDaytimeKwh(load: Map<LocalDate, Double>, today: LocalDate): Double? =
    (1L..7L).mapNotNull { load[today.minusDays(it)] }.takeIf { it.isNotEmpty() }?.average()?.times(DAYTIME_LOAD_SHARE)

/**
 * The consumption pattern (W per 5-minute slot) expected on [date]: the previous days' pattern scaled by that day's temperature- and sun-based
 * total over the mean total of the pattern days. Null without measured consumption.
 */
fun loadPatternFor(
    days: List<Day>, date: LocalDate, today: LocalDate, produced: Map<LocalDate, Double>, load: Map<LocalDate, Double>,
    loadHistory: List<Map<String, Double>>, kwp: Double, ratio: Double,
): DoubleArray? {
    val samples = days.filter { it.date < today && load[it.date] != null }
        .map { Triple(meanTemp(it.tMax, it.tMin), produced[it.date] ?: solarKwh(it, kwp, ratio), load.getValue(it.date)) }
    val d = days.find { it.date == date }
    val est = d?.let { estimateLoad(samples, meanTemp(it.tMax, it.tMin), produced[date] ?: solarKwh(it, kwp, ratio)) }
    val past = loadHistory.map { it.values.sum() * 5.0 / 60 / 1000 }.average().takeUnless { it.isNaN() } ?: return null // mean total kWh of the pattern days
    return loadPattern(loadHistory, if (est != null && past > 0) (est / past).coerceIn(0.5, 2.0) else 1.0)
}

private fun meanTemp(tMax: Double?, tMin: Double?): Double? = listOfNotNull(tMax, tMin).takeIf { it.isNotEmpty() }?.average()

/**
 * Expected total consumption of a day: least squares of past days' consumption on mean temperature and solar kWh (heating rises when
 * it is cold, and a sunny day warms the house). Falls back to the mean with too few days or a degenerate fit; kept within 0.3x..3x of the mean.
 */
fun estimateLoad(samples: List<Triple<Double?, Double, Double>>, temp: Double?, sun: Double): Double? {
    val s = samples.filter { it.first != null }.map { Triple(it.first!!, it.second, it.third) }
    if (samples.isEmpty()) return null
    val mean = samples.map { it.third }.average()
    if (temp == null || s.size < 5) return mean
    val x = s.map { doubleArrayOf(1.0, it.first, it.second) }; val y = s.map { it.third }
    val a = Array(3) { i -> DoubleArray(4) { j -> if (j < 3) x.sumOf { r -> r[i] * r[j] } else x.indices.sumOf { k -> x[k][i] * y[k] } } }
    for (i in 0 until 3) { // gaussian elimination with partial pivoting
        val p = (i until 3).maxBy { kotlin.math.abs(a[it][i]) }
        if (kotlin.math.abs(a[p][i]) < 1e-9) return mean
        a[i] = a[p].also { a[p] = a[i] }
        for (r in 0 until 3) if (r != i) { val f = a[r][i] / a[i][i]; for (c in i..3) a[r][c] -= f * a[i][c] }
    }
    val est = (0 until 3).map { a[it][3] / a[it][it] }.let { it[0] + it[1] * temp + it[2] * sun }
    return est.coerceIn(mean * 0.3, mean * 3)
}

private fun r1(x: Double) = round(x * 10) / 10

/** One decimal with the Bulgarian decimal comma, for display texts. */
private fun bg(x: Double) = r1(x).toString().replace('.', ',')
private fun bg2(x: Double) = "%.2f".format(java.util.Locale.ROOT, x).replace('.', ',')

/**
 * What not charging the battery at night cost today. Only the day zone from its start counts: while the battery is down at its reserved
 * level (+1%) the house buys from the grid at day price what a night charge would have bought at night price. A battery that never ran
 * empty lost nothing. Null until the day zone has started.
 */
fun missedCharge(
    batteryKwh: Double, pts: List<TodayPoint>, reservedPct: Int, dayFromHour: Int, dayPrice: Double = PRICE_DAY, nightPrice: Double = PRICE_NIGHT,
): MissedCharge? {
    val from = "%02d:00".format(dayFromHour)
    val day = pts.filter { it.soc != null && it.t >= from }
    if (batteryKwh <= 0 || day.isEmpty()) return null
    val empty = day.filter { it.soc!! <= reservedPct + 1.0 }
    val kwh = empty.sumOf { (it.gridW ?: 0.0).coerceAtLeast(0.0) } / 12000 // 5-minute steps
    if (kwh < 0.1) return MissedCharge(0.0, 0.0, "Батерията не е свършила през деня – няма спестявания.")
    val lost = kwh * (dayPrice - nightPrice)
    return MissedCharge(r1(kwh), round(lost * 100) / 100,
        "Батерията е стигнала резерва (${bg(reservedPct.toDouble())}%) и къщата е взела ≈ ${bg(kwh)} kWh от мрежата по дневна цена вместо по ${bg2(nightPrice)} €/kWh – пропусната икономия ≈ ${bg2(lost)} €.")
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
    maxSoc: Map<LocalDate, Double> = emptyMap(), batteryToday: List<TodayPoint> = emptyList(), batteryPrev: List<TodayPoint> = emptyList(), load: Map<LocalDate, Double> = emptyMap(),
    dischargeProfile: List<DischargePoint> = emptyList(), loadHistory: List<Map<String, Double>> = emptyList(),
    plans: Map<LocalDate, NightPlan> = emptyMap(), badCloud: Map<LocalDate, Double> = emptyMap(),
): Report {
    val kwp = plant?.kwp ?: 0.0 // installed PV power comes from SolaX; without it there is no estimate
    val ratio = calibratedRatio(days, produced, kwp, today)
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun range(from: LocalDate, to: LocalDate) = days.filter { it.date in from..to }
    val homeDay = homeDaytimeKwh(load, today)
    val samples = days.filter { it.date < today && load[it.date] != null }
        .map { Triple(meanTemp(it.tMax, it.tMin), produced[it.date] ?: solarKwh(it, kwp, ratio), load.getValue(it.date)) }
    val points = range(monday.minusDays(7), monday.plusDays(13)).map {
        val sun = produced[it.date] ?: r1(solarKwh(it, kwp, ratio))
        DayPoint(it.date.toString(), it.tMax, it.tMin, r1(it.rainMm), r1(solarKwh(it, kwp, ratio)), produced[it.date]?.let(::r1), ac[it.date]?.let(::r1), maxSoc[it.date]?.let(::r1),
            if (it.date < today) load[it.date]?.let(::r1) else null,
            if (it.date >= today) estimateLoad(samples, meanTemp(it.tMax, it.tMin), sun)?.let(::r1) else null,
            if (it.date >= today) homeDay?.let(::r1) else null, plans[it.date], badCloud[it.date]?.let(::r1))
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

    val battery = if (loc.solar && kwp > 0) {
        val last = batteryToday.lastOrNull { it.soc != null }
        val lastAt = last?.let { today.atTime(java.time.LocalTime.parse(it.t)).atZone(ZoneId.of(loc.tz)).toInstant().toEpochMilli() }
        if (last != null && lastAt != null && lastAt > (plant?.readAt ?: 0L)) { // the chart's 5-minute history is newer than the hourly reading: show the same value
            val kwh = plant?.batteryKwh ?: 0.0
            Battery(kwh, last.soc, if (kwh > 0) kwh * last.soc!! / 100 else null, last.batW ?: plant?.powerW, lastAt)
        } else Battery(plant?.batteryKwh ?: 0.0, plant?.soc, plant?.remainingKwh, plant?.powerW, plant?.readAt)
    } else null
    val pvForecast = if (battery != null && kwp > 0) intraday?.points.orEmpty().filter { it.radWm2 != null }
        .map { TodayPoint(it.t, null, round(kwp * it.radWm2!! * ratio)) } else emptyList()
    val pvForecastNext = if (battery != null && kwp > 0) intraday?.next.orEmpty().filter { it.radWm2 != null }
        .map { TodayPoint(it.t, null, round(kwp * it.radWm2!! * ratio)) } else emptyList()
    // the rest of today, 5 minutes at a time: the pattern of the previous days' consumption, lifted or lowered by today's weather
    val estimate = run {
        val b = battery ?: return@run null; val now = intraday?.now ?: return@run null
        val soc = batteryToday.lastOrNull { it.soc != null }?.soc ?: b.soc ?: return@run null
        val pattern = loadPatternFor(days, today, today, produced, load, loadHistory, kwp, ratio) ?: return@run null
        // the next 12 hours, which run into tomorrow: its consumption pattern (weather) and panel forecast follow today's
        val tomorrow = loadPatternFor(days, today.plusDays(1), today, produced, load, loadHistory, kwp, ratio) ?: pattern
        val pv = DoubleArray(576) { i -> (if (i < 288) pvForecast else pvForecastNext).lastOrNull { slotOf(it.t) <= i % 288 }?.pvW ?: 0.0 }
        val from = slotOf(now); val zone = ZoneId.of(loc.tz); val patterns = pattern + tomorrow
        // the charge the night plan intends to do (management on): for the coming day zone, which is tomorrow's from the day zone's start on
        val evening = from >= nightZone(today, zone).second * 12 // the day zone has started: the next one to plan for is tomorrow's
        val planDay = if (evening) today.plusDays(1) else today
        val stored = plans[planDay]
        val (zFrom, zTo) = nightZone(planDay, zone)
        val gap = (CHARGE_END_BEFORE_MS / 300_000).toInt()
        val end = zTo * 12 - gap + if (evening) 288 else 0
        val plain = if (loc.allowControl) estimateDay(from, soc, b.batteryKwh, patterns, loc.reservedPct, end) { pv[it] } else null
        // the stored plan; without one (the planner acts once the battery is at its minimum) the plan the planner would make when the
        // estimate says the battery gets there before the day zone
        val target = if (plain == null) null else if (stored != null) stored.takeIf { !it.reached && it.totalKwh > 0 }?.targetSoc
            else if (plain.points.none { it.soc <= loc.reservedPct + 1 }) null
            else {
                val offset = if (evening) 288 else 0
                val c = compensation(patterns.copyOfRange(offset, offset + 288), { pv[offset + it] }, zTo * 12, zFrom * 12, b.batteryKwh, loc.reservedPct)
                if (c.totalKwh > 0) nightTargetSoc(c, patterns.copyOfRange(offset, offset + 288), zTo * 12, gap, b.batteryKwh) else null
            }
        val charge = if (plain == null || target == null || target <= loc.reservedPct) null else {
            val running = (stored?.chargeUntil ?: 0L) > System.currentTimeMillis()
            plannedChargeStart(plain, from, end, b.batteryKwh, target, loc.maxChargeW.toDouble(), CHARGE_MARGIN, running)?.let { s -> PlannedCharge(s, end, loc.maxChargeW.toDouble(), target) }
        }
        estimateDay(from, soc, b.batteryKwh, patterns, loc.reservedPct, from + 144, charge) { pv[it] }
    }
    return Report(loc.id, loc.name, loc.solar, today.toString(), points, watering, battery, intraday, country = loc.country, estimate = estimate, nightFrom = nightZone(today, ZoneId.of(loc.tz)).first, nightTo = nightZone(today, ZoneId.of(loc.tz)).second,
        batteryToday = if (battery != null) batteryToday else emptyList(),
        dischargeProfile = if (battery != null) dischargeProfile else emptyList(),
        // expected panel power of today's 15-minute steps: installed kWp x radiation (W/m² over 1000) x the calibrated yield ratio
        pvForecast = pvForecast, pvForecastNext = pvForecastNext,
        batteryPrev = if (battery != null) batteryPrev else emptyList(),
        night = if (battery != null) {
                    val (dayPrice, nightPrice) = TARIFFS[loc.supplier] ?: TARIFFS.getValue(DEFAULT_SUPPLIER)
                    nightCharge(battery.batteryKwh, battery.soc, points.find { it.date == today.plusDays(1).toString() }?.kwh, dayPrice, nightPrice, homeDay ?: 0.0)
                } else null,
        missed = if (battery != null) {
                    val (dayPrice, nightPrice) = TARIFFS[loc.supplier] ?: TARIFFS.getValue(DEFAULT_SUPPLIER)
                    missedCharge(battery.batteryKwh, batteryToday, loc.reservedPct, nightZone(today, ZoneId.of(loc.tz)).second, dayPrice, nightPrice)
                } else null)
}
