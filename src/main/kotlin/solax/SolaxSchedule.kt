package ilios.solax

import ilios.SECONDS_PER_MINUTE
import ilios.report.nightZone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes

internal const val BATTERY_NIGHT_MINUTES = 30
internal const val BATTERY_FAST_MINUTES = 5 // the finest the cloud updates
internal const val FAST_WINDOW_MS = 30 * 60_000L // fast reading this long before the charge's latest start and end

// Timing rules of the SolaX sync: back-offs after failures and when each kind of data is due.
internal const val VIEWED_WITHIN_MS = 3L * 60 * 1000 // the dashboard polls every minute while open
internal const val TODAY_EVERY_MS = 9L * 60 * 1000 // today's 5-minute history is refreshed on each 10-minute loop pass

/**
 * A day's production is final only after the cloud has processed the night, so yesterday is fetched from this local
 * time.
 */
internal val PRODUCTION_FINAL_FROM = LocalTime.of(2, 0)
internal const val PLANT_REFRESH_MS = 7L * 24 * 60 * 60 * 1000 // panel power and battery capacity rarely change
internal val CHECK_EVERY = 10.minutes
internal val BATTERY_CHECK_EVERY = 5.minutes

/** The newest day whose production is final at [local] time: yesterday from 02:00, otherwise the day before. */
fun lastProductionDay(local: ZonedDateTime): LocalDate = local.toLocalDate().minusDays(
    if (local.toLocalTime() >=
        PRODUCTION_FINAL_FROM
    ) {
        1
    } else {
        2
    },
)

/** The start of the current hour; a battery reading older than this is due, so the charge is read hourly. */
fun lastBatterySlot(now: Instant): Instant = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)

/**
 * The battery is read hourly, every 30 minutes while the night zone lasts (the night plan checks it then), and every 5
 * minutes in the [FAST_WINDOW_MS] before each of [plannedTimes] (when the night charge can start at the latest and when
 * it ends), so that the start and the end are noticed soon.
 */
fun batterySlot(now: Instant, zone: ZoneId, plannedTimes: List<Long> = emptyList()): Instant {
    val local = now.atZone(zone)
    val (from, to) = nightZone(local.toLocalDate(), zone)
    val fast = plannedTimes.any { now.toEpochMilli() in it - FAST_WINDOW_MS..it }
    return if (fast) {
        now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(local.minute % BATTERY_FAST_MINUTES * SECONDS_PER_MINUTE)
    } else if (local.hour >= from ||
        local.hour < to
    ) {
        now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(local.minute % BATTERY_NIGHT_MINUTES * SECONDS_PER_MINUTE)
    } else {
        lastBatterySlot(now)
    }
}

/** Days of battery history behind the discharge estimate (the profile is the mean of these). */
internal const val DISCHARGE_DAYS = 7

/** Days of 15-minute history fetched per pass: a backlog is worked off gradually to stay under the call allowance. */
internal const val HISTORY_DAYS_PER_PASS = 3
