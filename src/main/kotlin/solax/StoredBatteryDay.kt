package ilios.solax

import ilios.db.DischargeDays
import ilios.report.TodayPoint
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate

/**
 * A finished day from the stored 5-minute steps as battery points, for the battery chart's slider. The panel
 * power is not stored: it follows from consumption = panels - battery charging + grid import.
 */
fun storedBatteryDay(locationId: Long, day: LocalDate): List<TodayPoint> = transaction {
    DischargeDays.selectAll().where {
        DischargeDays.locationId eq locationId and (DischargeDays.day eq day.toString())
    }
        .orderBy(DischargeDays.t)
        .map {
            val grid = it[DischargeDays.gridW]
            val load = it[DischargeDays.loadW]
            val battery = (it[DischargeDays.chargeW] ?: 0.0) - it[DischargeDays.dischargeW]
            val pv = if (load != null && grid != null) maxOf(0.0, load + battery - grid) else null
            TodayPoint(it[DischargeDays.t], it[DischargeDays.soc], pv, gridW = grid)
        }
}
