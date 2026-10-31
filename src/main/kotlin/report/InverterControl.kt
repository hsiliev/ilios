package ilios.report

import ilios.MILLIS
import ilios.MINUTES_PER_HOUR
import ilios.db.Loc
import ilios.solax.BATTERY_NIGHT_MINUTES
import ilios.solax.SolaxPlant
import ilios.solax.SolaxService
import ilios.solax.api.SolaxError
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private const val HALF_MINUTE = 30

internal const val CHARGE_END_BEFORE_MS = 5 * 60_000L // the charge should end this long before the day zone starts

/** The planner decides at the battery readings, so a charge that is not started now waits this long. */
internal const val NEXT_READING_MS = BATTERY_NIGHT_MINUTES * 60_000L
private const val MIN_CHARGE_SECONDS = 120 // nothing worthwhile is left before the day zone when less than this remains
private const val TIMER_EXTRA_SECONDS = 60L
private const val STATUS_LIMIT = 200

/** The time until which the inverter charges (null when it does not) and a status text. */
internal typealias ChargeState = Pair<Long?, String?>

/**
 * Remote-mode charging (SolaX Push Power at the place's max charging power) for the night plan; never throws (except
 * cancellation).
 * Charging runs on a timer that the inverter honours by itself: the energy still missing divided by the power that
 * reaches the battery (the place's charging efficiency), and never
 * past 5 minutes before the day zone start; a charge is not started before it has to be, so that it ends then (the
 * last battery reading from which it still ends in time starts it, because the next reading would be too late). Each
 * fresh battery reading
 * sends the command again with the time recomputed (a new command replaces a running one), and as soon as the plan
 * is reached, or
 * management is switched off, a running charge is ended.
 */
internal class InverterControl(private val solax: SolaxService) {
    /**
     * Decides what the inverter should do for the plan ([targetSoc], [reached]) of day [target] whose day zone starts
     * at [stopHour]; see the class comment.
     */
    suspend fun control(
        loc: Loc,
        existing: NightPlan?,
        plant: SolaxPlant,
        targetSoc: Double,
        reached: Boolean,
        local: ZonedDateTime,
        target: LocalDate,
        stopHour: Int,
        stops: ChargeStops = ChargeStops.NONE,
    ): ChargeState {
        val now = local.toInstant().toEpochMilli()
        val running = existing?.chargeUntil?.takeIf { it > now }
        val keep: ChargeState = running to existing?.control
        val level = minOf(100, ceil(targetSoc).toInt())
        val stopReason = stopReason(loc, reached, level)
        if (stopReason != null) return if (running == null) keep else stop(loc, running, stopReason)
        val end = chargeEnd(target, stopHour, local.zone)
        val seconds = timerSeconds(loc, plant, targetSoc, now, end, running != null, stops) ?: return keep
        return start(loc, running, level, seconds, local, now)
    }

    /** Why a running charge has to end, or null when charging is wanted. */
    private fun stopReason(loc: Loc, reached: Boolean, level: Int): String? = when {
        !loc.allowControl -> "Управлението е изключено – зареждането е прекратено"
        reached -> "Планираното ниво е достигнато"
        level <= loc.reservedPct -> "Няма нужда от зареждане"
        else -> null
    }

    private suspend fun stop(loc: Loc, running: Long, reason: String): ChargeState = try {
        solax.stopCharge(loc)
        null to reason
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(loc, e, running)
    }

    /**
     * The timer of the charge in seconds, or null when none should be sent now: no reading, nothing worthwhile left
     * before the day zone, or
     * the charge does not have to start yet. Charging early would be drained by the house again, so it waits as long
     * as the next reading (30 minutes at night) is still in time and starts at the last reading before it has to; a
     * reading that comes after that point starts a little late and the timer is capped at the end.
     */
    private fun timerSeconds(
        loc: Loc,
        plant: SolaxPlant,
        targetSoc: Double,
        now: Long,
        end: Long,
        running: Boolean,
        stops: ChargeStops,
    ): Long? {
        val need = needSeconds(loc, plant, targetSoc, stops) ?: return null
        val seconds = minOf((end - now) / 1000, need)
        val tooEarly = !running && now + NEXT_READING_MS <= end - need * 1000 // the next reading is still in time
        return seconds.takeUnless { it < MIN_CHARGE_SECONDS || tooEarly }
    }

    /** The seconds the charge needs from the plant's last reading to [targetSoc], or null without a reading. */
    private fun needSeconds(loc: Loc, plant: SolaxPlant, targetSoc: Double, stops: ChargeStops): Long? {
        val soc = plant.soc ?: return null
        val capacityKwh = plant.batteryKwh ?: return null
        val missingKwh = (targetSoc - soc) / 100 * capacityKwh
        val slots = stops.slots(missingKwh, loc.maxChargeW.toDouble(), loc.chargeEfficiency)
        return (slots * SLOT_MS / MILLIS).toLong() + TIMER_EXTRA_SECONDS
    }

    /** The latest moment (epoch ms) the charge can start and still end at [end], or null without a reading. */
    fun latestStart(loc: Loc, plant: SolaxPlant, targetSoc: Double, end: Long, stops: ChargeStops): Long? =
        needSeconds(loc, plant, targetSoc, stops)?.let { end - it * MILLIS }

    private suspend fun start(
        loc: Loc,
        running: Long?,
        level: Int,
        seconds: Long,
        local: ZonedDateTime,
        now: Long,
    ): ChargeState = try {
        solax.startCharge(loc, seconds.toInt())
        val at = local.format(DateTimeFormatter.ofPattern("HH:mm"))
        now + seconds * MILLIS to
            "Зареждане до $level% при най-много ${loc.maxChargeW} W, зададено в $at за ${(seconds + HALF_MINUTE) / MINUTES_PER_HOUR} мин"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(loc, e, running)
    }

    private fun failed(loc: Loc, e: Exception, until: Long?): ChargeState {
        if (e is SolaxError) return until to e.message?.take(STATUS_LIMIT)
        System.err.println("Controlling the inverter failed for place ${loc.id}: ${e::class.simpleName}")
        return until to "Инверторът не може да се управлява – ще опитам отново по-късно"
    }
}

/** When the charge of the night plan for [target] has to end: shortly before the day zone starts at [stopHour]. */
internal fun chargeEnd(target: LocalDate, stopHour: Int, zone: ZoneId): Long =
    target.atTime(stopHour, 0).atZone(zone).toInstant().toEpochMilli() - CHARGE_END_BEFORE_MS
