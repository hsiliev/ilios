package ilios.solax

import ilios.db.Loc
import ilios.db.Solax
import ilios.solax.api.RETRY_AFTER_MS
import ilios.solax.api.attempt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The battery's state of charge, read through the plant's inverters and kept in the database: at most hourly, every
 * 30 minutes at night.
 */
internal class BatteryReader(private val client: SolaxClient, private val today: TodayCache) {
    private val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /**
     * Reads the battery when a reading is older than the current slot (or there is none yet, e.g. right after
     * connecting). Failures are
     * retried at most hourly. Never throws (except cancellation); the outcome goes to [Solax.batteryStatus].
     */
    suspend fun readIfDue(loc: Loc, now: Instant) {
        val link = client.link(loc) ?: return
        if (!link.hasBattery) {
            forgetBattery(link)
            return
        }
        if (!isDue(link, now)) return
        SolaxRepository.update(loc.id) { it[Solax.batteryTryAt] = now.toEpochMilli() + RETRY_AFTER_MS }
        attempt(
            "battery read",
            loc.id,
            onSolaxError = { e ->
                SolaxRepository.setBatteryStatus(loc.id, e.message)
                SolaxRepository.update(loc.id) { it[Solax.batteryTryAt] = now.toEpochMilli() + e.retryMs }
            },
            onFailure = {
                SolaxRepository.setBatteryStatus(
                    loc.id,
                    "Зарядът на батерията не може да се прочете – ще опитам отново по-късно",
                )
            },
        ) {
            val soc = read(link, now.toEpochMilli())
            SolaxRepository.setBatteryStatus(loc.id, statusText(soc, now.atZone(ZoneId.of(loc.tz)).format(stamp)))
            today.cache(loc, now)
        }
    }

    /** No battery: forget any old reading and status. */
    private fun forgetBattery(link: SolaxLink) {
        if (link.row[Solax.soc] != null || link.row[Solax.batteryStatus] != null) {
            SolaxRepository.clearBattery(link.loc.id, readAt = null)
            SolaxRepository.setBatteryStatus(link.loc.id, null)
        }
    }

    /**
     * Due when the last reading is older than the current slot and no failure back-off is running (`batteryTryAt` =
     * the earliest next attempt).
     */
    private fun isDue(link: SolaxLink, now: Instant): Boolean {
        val readAt = link.row[Solax.batteryReadAt]
        val tryAt = link.row[Solax.batteryTryAt]
        return (readAt == null || readAt < batterySlot(now, link.zone).toEpochMilli()) &&
            (tryAt == null || tryAt <= now.toEpochMilli())
    }

    private fun statusText(soc: Double?, at: String) = if (soc ==
        null
    ) {
        "SolaX не върна данни за батерията"
    } else {
        "Заряд на батерията: ${"%.0f".format(Locale.forLanguageTag("bg"), soc)}% към $at"
    }

    /**
     * Reads the charge and stores it. Several batteries are averaged for the percentage and summed for energy and
     * power. Returns the
     * percentage, or null when SolaX returned no battery data.
     */
    private suspend fun read(link: SolaxLink, at: Long): Double? {
        val sns = link.inverterSerials()
        val batteries = if (sns.isEmpty()) emptyList() else link.session.batteries(sns, link.type)
        val socs = batteries.mapNotNull { it.batterySOC }
        if (socs.isEmpty()) {
            SolaxRepository.clearBattery(link.loc.id, at)
            return null
        }
        val soc = socs.average()
        val remaining = batteries.mapNotNull { it.batteryRemainings }.takeIf { it.isNotEmpty() }?.sum()
        val power = batteries.mapNotNull { it.chargeDischargePower }.takeIf { it.isNotEmpty() }?.sum()
        SolaxRepository.update(link.loc.id) {
            it[Solax.soc] = soc
            it[Solax.batteryRemainingKwh] = remaining
            it[Solax.batteryPowerW] = power
            it[Solax.batteryReadAt] = at
        }
        return soc
    }
}
