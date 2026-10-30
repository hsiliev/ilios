package ilios.solax

import ilios.db.BatteryDays
import ilios.db.DischargeDays
import ilios.db.Produced
import ilios.db.SolarToday
import ilios.report.ChargePoint
import ilios.report.DischargePoint
import ilios.report.LoadPoint
import ilios.report.TodayPoint
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate

/**
 * What SolaX measured, as stored for the report: production, consumption, battery history and today's 5-minute
 * points.
 */
object SolaxReadings {
    fun produced(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId and (Produced.bad eq false) }.associate {
            LocalDate.parse(it[Produced.day]) to
                it[Produced.kwh]
        }
    }

    /**
     * Days whose SolaX production was impossible for the panels (kWh as returned), shown on the chart as bad cloud
     * data.
     */
    fun badProduced(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId and (Produced.bad eq true) }.associate {
            LocalDate.parse(it[Produced.day]) to
                it[Produced.kwh]
        }
    }

    /**
     * Inverter AC output per day (the SolaX app's "Inverter Output"); unlike [produced] it includes battery discharge
     * and excludes PV energy stored in the battery.
     */
    fun producedAc(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId }
            .mapNotNull { r -> r[Produced.acKwh]?.let { LocalDate.parse(r[Produced.day]) to it } }
            .toMap()
    }

    /** The home's total consumption per day (heat pump included), as SolaX reports it. */
    fun consumption(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId }
            .mapNotNull { r -> r[Produced.loadKwh]?.let { LocalDate.parse(r[Produced.day]) to it } }
            .toMap()
    }

    /**
     * The stored consumption and grid power (W) per 5-minute step of one finished day (last week and this week are
     * kept).
     */
    fun loadDay(locationId: Long, day: LocalDate): List<LoadPoint> = transaction {
        DischargeDays.selectAll().where {
            DischargeDays.locationId eq locationId and
                (DischargeDays.day eq day.toString())
        }
            .orderBy(DischargeDays.t)
            .map {
                LoadPoint(
                    it[DischargeDays.t],
                    it[DischargeDays.dischargeW],
                    it[DischargeDays.loadW],
                    it[DischargeDays.gridW],
                )
            }
    }

    /** Mean battery discharge power (W) per 5-minute step of the day over the cached past days. */
    fun dischargeProfile(locationId: Long, today: LocalDate): List<DischargePoint> = transaction {
        val from = today.minusDays(DISCHARGE_DAYS.toLong()).toString()
        DischargeDays.selectAll().where {
            DischargeDays.locationId eq locationId and
                (DischargeDays.day greaterEq from) and
                (DischargeDays.day less today.toString())
        }
            .map { it[DischargeDays.t] to it[DischargeDays.dischargeW] }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (t, w) -> DischargePoint(t, w.average()) }
    }

    /**
     * The consumption (W) per 5-minute slot of each of the last [DISCHARGE_DAYS] finished days that have consumption
     * data, oldest first.
     */
    fun loadDays(locationId: Long, today: LocalDate): List<Map<String, Double>> = transaction {
        val from = today.minusDays(DISCHARGE_DAYS.toLong()).toString()
        DischargeDays.selectAll().where {
            DischargeDays.locationId eq locationId and
                (DischargeDays.day greaterEq from) and
                (DischargeDays.day less today.toString()) and
                DischargeDays.loadW.isNotNull()
        }
            .groupBy({
                it[DischargeDays.day]
            }, { it[DischargeDays.t] to it[DischargeDays.loadW]!! })
            .toSortedMap()
            .values
            .map { it.toMap() }
    }

    /** The stored 5-minute battery charge and charging power of the past days, for measuring the efficiency. */
    fun chargePoints(locationId: Long): List<ChargePoint> = transaction {
        DischargeDays.selectAll().where { DischargeDays.locationId eq locationId and DischargeDays.soc.isNotNull() }
            .map {
                ChargePoint(
                    LocalDate.parse(it[DischargeDays.day]),
                    it[DischargeDays.t],
                    it[DischargeDays.soc],
                    it[DischargeDays.chargeW],
                )
            }
    }

    /** The highest battery charge (%) each past day reached, where SolaX's history was fetched. */
    fun batteryDays(locationId: Long): Map<LocalDate, Double> = transaction {
        BatteryDays.selectAll().where { BatteryDays.locationId eq locationId }.associate {
            LocalDate.parse(it[BatteryDays.day]) to
                it[BatteryDays.maxSoc]
        }
    }

    /** Today's cached 5-minute battery charge and panel power. */
    fun today(locationId: Long, day: LocalDate): List<TodayPoint> = transaction {
        SolarToday.selectAll().where { SolarToday.locationId eq locationId and (SolarToday.day eq day.toString()) }
            .orderBy(SolarToday.t)
            .map {
                TodayPoint(
                    it[SolarToday.t],
                    it[SolarToday.soc],
                    it[SolarToday.pvW],
                    it[SolarToday.batW],
                    it[SolarToday.gridW],
                )
            }
    }
}
