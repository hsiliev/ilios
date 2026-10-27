package ilios.report

import ilios.weather.Intraday
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
class Advice(val water: String, val text: String) // water: "yes" | "light" | "no"

@Serializable
class Watering(
    val rainSoFarMm: Double,
    val rainExpectedMm: Double,
    val plants: Advice,
    val grass: Advice,
    val trees: Advice,
)

/** batteryKwh is 0 when the plant has no battery (or SolaX does not report one). */
@Serializable
class Battery(
    val batteryKwh: Double,
    val soc: Double? = null,
    val remainingKwh: Double? = null,
    val powerW: Double? = null,
    val readAt: Long? = null, // charge state at the last SolaX sync; power > 0 = charging
)

/**
 * Night charging advice: charge = grid charging is worth it; kwh = energy to take from the grid; savingEur = what it
 * saves vs. buying that energy at the day price.
 */
@Serializable
class NightCharge(
    val charge: Boolean,
    val kwh: Double,
    val savingEur: Double,
    val costEur: Double,
    val dayPrice: Double,
    val nightPrice: Double,
    val text: String,
)

/**
 * Today's lost saving: kwh = energy the battery lacked at the start of the day and the sun did not make up for;
 * lostEur = that energy at day price minus night price.
 */
@Serializable
class MissedCharge(val kwh: Double, val lostEur: Double, val text: String)

/**
 * One day of the three-week window (previous, current and next week). kwh is the estimated solar yield; produced is
 * SolaX's measured yield when connected.
 */
@Serializable
class DayPoint(
    val date: String,
    val tMax: Double?,
    val tMin: Double?,
    val rainMm: Double,
    val kwh: Double,
    val produced: Double? = null,
    val ac: Double? = null, // ac: inverter AC output of that day, for comparison with the SolaX app
    val maxSoc: Double? = null, // highest battery charge (%) a past day reached, from SolaX's battery history
    val loadKwh: Double? = null, // past days: the home's measured total consumption
    // today and later: estimated total consumption from the temperature and the sun, learnt from past days
    val loadEst: Double? = null,
    // today and later: expected daytime consumption of the house, which the sun has to cover before the battery gets
    // any
    val homeKwh: Double? = null,
    // the night-charge plan worked out for this day, when the battery was found at its minimum
    val plan: NightPlan? = null,
    // past days: the impossible production (kWh) SolaX returned, ignored in favour of the estimate
    val badCloud: Double? = null,
)

/** One 5-minute step of today's battery charge (%) and panel power (W) from SolaX; t is local HH:mm. */
@Serializable
class TodayPoint(val t: String, val soc: Double?, val pvW: Double?, val batW: Double? = null, val gridW: Double? = null)

/** The battery's expected discharge power (W) at a time of day (HH:mm). */
@Serializable
class DischargePoint(val t: String, val w: Double)

/**
 * One stored 5-minute step of a finished day: battery discharge, home consumption and power drawn from the grid, in W
 * (null = not reported).
 */
class LoadPoint(val t: String, val dischargeW: Double, val loadW: Double?, val gridW: Double?)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class Report(
    val id: Long,
    val name: String,
    val solar: Boolean = false,
    val today: String = "",
    val days: List<DayPoint> = emptyList(),
    val watering: Watering? = null,
    val battery: Battery? = null,
    val intraday: Intraday? = null,
    val error: String? = null,
    val country: String? = null,
    val batteryToday: List<TodayPoint> = emptyList(),
    val pvForecast: List<TodayPoint> = emptyList(),
    val batteryPrev: List<TodayPoint> = emptyList(),
    // yesterday's charge and tomorrow's panel forecast, for the 24-hour window
    val pvForecastNext: List<TodayPoint> = emptyList(),
    val night: NightCharge? = null,
    val missed: MissedCharge? = null,
    val dischargeProfile: List<DischargePoint> = emptyList(),
    @EncodeDefault val nightFrom: Int = 22,
    @EncodeDefault val nightTo: Int = 6, // the cheap night tariff today, local hours
    // the rest of today in 5-minute steps, from the previous days' consumption pattern, the weather and the solar
    // forecast
    val estimate: Estimate? = null,
)
