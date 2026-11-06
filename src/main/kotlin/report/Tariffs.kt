package ilios.report

import ilios.MILLIS
import ilios.PERCENT
import ilios.SLOTS_PER_HOUR
import ilios.db.DEFAULT_SUPPLIER

private const val MIN_NIGHT_KWH = 0.5 // less than this is not worth charging for
private const val MIN_MISSED_KWH = 0.1

/**
 * Regulated household tariffs (day, night) in EUR/kWh with network services and 20% VAT, valid 1 July 2026 - 30 June
 * 2027
 * (KEWR decision Ц-13). Update every July. Keys are the suppliers a place can choose in its settings.
 */
val TARIFFS: Map<String, Pair<Double, Double>> = linkedMapOf(
    "electrohold" to (0.15425 to 0.09192),
    "evn" to (0.15458 to 0.09226),
    "energopro" to (0.15512 to 0.09280),
)
val PRICE_DAY = TARIFFS.getValue(DEFAULT_SUPPLIER).first
val PRICE_NIGHT = TARIFFS.getValue(DEFAULT_SUPPLIER).second

/**
 * Whether to charge the battery from the grid tonight at the cheap tariff. The room in the battery is what
 * tomorrow's sun is not
 * expected to fill; that energy bought at night instead of during the day is the saving. Null when the charge is
 * unknown. Of the grid energy bought only [efficiency] reaches the battery, so the cost is that of kwh ÷ [efficiency]
 * and the saving is the day price of kwh minus that cost.
 */
fun nightCharge(
    batteryKwh: Double,
    soc: Double?,
    tomorrowKwh: Double?,
    dayPrice: Double = PRICE_DAY,
    nightPrice: Double = PRICE_NIGHT,
    homeKwh: Double = 0.0,
    efficiency: Double = 1.0,
): NightCharge? {
    if (batteryKwh <= 0 || soc == null || tomorrowKwh == null) return null
    val room = batteryKwh * (1 - soc.coerceIn(0.0, 100.0) / 100)
    // what is left for the battery after the home (heat pump...) has used its share
    val sunKwh = (tomorrowKwh - homeKwh).coerceAtLeast(0.0)
    val kwh = minOf(room, batteryKwh - sunKwh).coerceAtLeast(0.0)
    val home = if (homeKwh > 0.05) " след ≈ ${bg(r1(homeKwh))} kWh консумация на къщата през деня" else ""
    if (kwh < MIN_NIGHT_KWH) {
        return NightCharge(
            false,
            0.0,
            0.0,
            0.0,
            dayPrice,
            nightPrice,
            efficiency,
            if (room < MIN_NIGHT_KWH
            ) {
                "Батерията е почти пълна – не е нужно нощно зареждане."
            } else {
                "Утре слънцето (≈ ${bg(r1(tomorrowKwh))} kWh)$home ще напълни батерията – не зареждайте от мрежата."
            },
        )
    }
    val cost = kwh / efficiency * nightPrice
    val saving = kwh * dayPrice - cost
    return NightCharge(
        true,
        r1(kwh),
        r2(saving),
        r2(cost),
        dayPrice,
        nightPrice,
        efficiency,
        "Заредете батерията от мрежата през нощта с около ${bg(
            r1(kwh),
        )} kWh: утре слънцето (≈ ${bg(r1(tomorrowKwh))} kWh)$home няма да я напълни. " +
            "Нощната цена е ${bg2(
                nightPrice,
            )} €/kWh срещу ${bg2(dayPrice)} € през деня – ≈ ${bg2(cost)} € разход и ≈ ${bg2(saving)} € икономия.",
    )
}

/**
 * What not charging the battery at night cost today. Only the day zone from its start counts: while the battery is
 * down at its reserved
 * level (+1%) the house buys from the grid at day price what a night charge would have bought at night price, but
 * never more than the room the battery had when the day zone began (a full charge could not have stored more). A
 * battery that never ran
 * empty lost nothing. The night charge that would have supplied that energy costs kwh ÷ [efficiency] at night price.
 * Null until the day zone has started.
 */
fun missedCharge(
    batteryKwh: Double,
    pts: List<TodayPoint>,
    reservedPct: Int,
    dayFromHour: Int,
    dayPrice: Double = PRICE_DAY,
    nightPrice: Double = PRICE_NIGHT,
    efficiency: Double = 1.0,
): MissedCharge? {
    val from = "%02d:00".format(dayFromHour)
    val day = pts.filter { it.soc != null && it.t >= from }
    if (batteryKwh <= 0 || day.isEmpty()) return null
    val (imported, kwh) = coverableImport(day, batteryKwh, reservedPct)
    if (imported <
        MIN_MISSED_KWH
    ) {
        return MissedCharge(0.0, 0.0, "Батерията не е свършила през деня – няма спестявания.")
    }
    if (kwh < MIN_MISSED_KWH) {
        return MissedCharge(
            0.0,
            0.0,
            "Батерията е стигнала резерва, но по-пълна нощна батерия нямаше да помогне – слънцето я е заредило " +
                "догоре и излишъкът би се загубил.",
        )
    }
    val lost = kwh * dayPrice - kwh / efficiency * nightPrice
    return MissedCharge(
        r1(kwh),
        r2(lost),
        "Батерията е стигнала резерва (${bg(reservedPct.toDouble())}%) и къщата е взела ≈ ${bg(imported)} kWh от " +
            "мрежата по дневна цена. По-пълна нощна батерия е можела да поеме ≈ ${bg(kwh)} kWh от тях – " +
            "пропусната икономия ≈ ${bg2(lost)} € (нощна цена ${bg2(nightPrice)} €/kWh).",
    )
}

/**
 * Replays the day zone with a battery that began it fuller by its whole room (what a night charge to 100 % would
 * have added). That extra energy covers the grid import of the steps where the battery really sat at its reserved
 * level (+1%) and is used up doing so; whenever the sun charges the real battery so far that the fuller one would
 * overflow, the excess is lost. Returns the grid energy drawn while empty and the part of it the extra energy would
 * have covered (kWh).
 */
private fun coverableImport(day: List<TodayPoint>, batteryKwh: Double, reservedPct: Int): Pair<Double, Double> {
    var extra = batteryKwh * (PERCENT - day.first().soc!!).coerceAtLeast(0.0) / PERCENT
    var imported = 0.0
    var covered = 0.0
    for (p in day) {
        val soc = p.soc!!
        extra = minOf(extra, batteryKwh * (PERCENT - soc).coerceAtLeast(0.0) / PERCENT)
        if (soc > reservedPct + 1.0) continue
        val grid = (p.gridW ?: 0.0).coerceAtLeast(0.0) / (SLOTS_PER_HOUR * MILLIS) // 5-minute steps
        val used = minOf(extra, grid)
        imported += grid
        covered += used
        extra -= used
    }
    return imported to covered
}
