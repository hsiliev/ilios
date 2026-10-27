package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.SLOT_MINUTES
import ilios.WEEK_DAYS
import ilios.weather.Day
import java.time.LocalDate

/**
 * Share of a day's consumption that falls while the sun is up (the rest is evening and night, when the battery serves
 * it anyway).
 */
const val DAYTIME_LOAD_SHARE = 0.6

private const val MIN_LOAD_SAMPLES = 5
private const val TERMS = 3 // constant, temperature and sunshine
private const val MIN_LOAD_SCALE = 0.3
private const val MAX_LOAD_SCALE = 3.0
private const val SINGULAR = 1e-9
private const val MIN_PATTERN_SCALE = 0.5
private const val MAX_PATTERN_SCALE = 2.0

/**
 * Expected daytime consumption: the mean of the last 7 finished days' measured consumption (the heat pump included)
 * times [DAYTIME_LOAD_SHARE].
 */
fun homeDaytimeKwh(load: Map<LocalDate, Double>, today: LocalDate): Double? = (1L..WEEK_DAYS).mapNotNull {
    load[today.minusDays(it)]
}.takeIf { it.isNotEmpty() }?.average()?.times(DAYTIME_LOAD_SHARE)

/**
 * The consumption pattern (W per 5-minute slot) expected on [date]: the previous days' pattern scaled by that day's
 * temperature- and sun-based
 * total over the mean total of the pattern days. Null without measured consumption.
 */
fun loadPatternFor(
    days: List<Day>,
    date: LocalDate,
    today: LocalDate,
    produced: Map<LocalDate, Double>,
    load: Map<LocalDate, Double>,
    loadHistory: List<Map<String, Double>>,
    kwp: Double,
    ratio: Double,
): DoubleArray? {
    val samples = days.filter { it.date < today && load[it.date] != null }
        .map {
            Triple(meanTemp(it.tMax, it.tMin), produced[it.date] ?: solarKwh(it, kwp, ratio), load.getValue(it.date))
        }
    val d = days.find { it.date == date }
    val est = d?.let { estimateLoad(samples, meanTemp(it.tMax, it.tMin), produced[date] ?: solarKwh(it, kwp, ratio)) }
    val past = loadHistory.map {
        it.values.sum() * SLOT_MINUTES.toDouble() / MINUTES_PER_HOUR / MILLIS
    }.average().takeUnless { it.isNaN() } ?: return null // mean total kWh of the pattern days
    return loadPattern(
        loadHistory,
        if (est != null &&
            past > 0
        ) {
            (est / past).coerceIn(MIN_PATTERN_SCALE, MAX_PATTERN_SCALE)
        } else {
            1.0
        },
    )
}

internal fun meanTemp(tMax: Double?, tMin: Double?): Double? = listOfNotNull(tMax, tMin).takeIf {
    it.isNotEmpty()
}?.average()

/**
 * Expected total consumption of a day: least squares of past days' consumption on mean temperature and solar kWh
 * (heating rises when
 * it is cold, and a sunny day warms the house). Falls back to the mean with too few days or a degenerate fit; kept
 * within 0.3x..3x of the mean.
 */
fun estimateLoad(samples: List<Triple<Double?, Double, Double>>, temp: Double?, sun: Double): Double? {
    val s = samples.filter { it.first != null }.map { Triple(it.first!!, it.second, it.third) }
    if (samples.isEmpty()) return null
    val mean = samples.map { it.third }.average()
    if (temp == null || s.size < MIN_LOAD_SAMPLES) return mean
    val x = s.map { doubleArrayOf(1.0, it.first, it.second) }
    val y = s.map { it.third }
    val a = Array(TERMS) { i ->
        DoubleArray(TERMS + 1) { j ->
            if (j < TERMS) x.sumOf { r -> r[i] * r[j] } else x.indices.sumOf { k -> x[k][i] * y[k] }
        }
    }
    val c = solve(a) ?: return mean
    return (c[0] + c[1] * temp + c[2] * sun).coerceIn(mean * MIN_LOAD_SCALE, mean * MAX_LOAD_SCALE)
}

/**
 * Gaussian elimination with partial pivoting of the augmented [TERMS] x ([TERMS] + 1) matrix; null when it is
 * singular.
 */
private fun solve(a: Array<DoubleArray>): DoubleArray? {
    for (i in 0 until TERMS) {
        val p = (i until TERMS).maxBy { kotlin.math.abs(a[it][i]) }
        if (kotlin.math.abs(a[p][i]) < SINGULAR) return null
        a[i] = a[p].also { a[p] = a[i] }
        for (r in 0 until TERMS) {
            if (r != i) {
                val f = a[r][i] / a[i][i]
                for (c in i..TERMS) a[r][c] -= f * a[i][c]
            }
        }
    }
    return DoubleArray(TERMS) { a[it][TERMS] / a[it][it] }
}
