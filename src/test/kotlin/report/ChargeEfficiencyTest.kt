package ilios.report

import ilios.db.Locations
import ilios.db.Users
import ilios.db.initDb
import ilios.tempDbPath
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargeEfficiencyTest {
    private val sofia = ZoneId.of("Europe/Sofia")
    private val day = LocalDate.of(2026, 10, 28) // night zone 22:00-06:00

    /** [n] steps from [from] (HH:mm) charging at [w] W, the charge rising by [socStep] % per step from [soc]. */
    private fun run(from: String, n: Int, w: Double, soc: Double, socStep: Double): List<ChargePoint> {
        val start = slotOf(from)
        return List(n) { i ->
            val s = start + i
            ChargePoint(day, "%02d:%02d".format(s / 12, s % 12 * 5), soc + i * socStep, w)
        }
    }

    @Test
    fun measuresTheNightChargesOnly() {
        // 2 kW for 5 minutes is 0.167 kWh; 1 % of 10 kWh is 0.1 kWh stored: 60 %
        val night = run("01:00", 19, 2000.0, 20.0, 1.0)
        val sunny = run("12:00", 12, 2000.0, 50.0, 2.0) // solar charging in the day zone does not count
        assertEquals(60, measuredChargeEffPct(night + sunny, 10.0, sofia))
    }

    @Test
    fun tooLittleChargingOrANearlyFullBatteryGivesNoFigure() {
        assertNull(measuredChargeEffPct(run("01:00", 5, 2000.0, 20.0, 1.0), 10.0, sofia), "0.67 kWh is too little")
        assertNull(measuredChargeEffPct(run("01:00", 19, 2000.0, 99.0, 0.0), 10.0, sofia), "full: not counted")
        assertNull(measuredChargeEffPct(run("01:00", 19, 100.0, 20.0, 1.0), 10.0, sofia), "a trickle is not counted")
    }

    @Test
    fun placesLeftAtTheOldDefaultSwitchToTheMeasuredValueAfterTheUpgrade() {
        val path = tempDbPath()
        initDb(path)
        transaction {
            val user = Users.insert {
                it[email] = "a@example.com"
                it[passHash] = "x"
            }[Users.id]
            for ((place, pct) in listOf("Sofia" to 70, "Varna" to 85)) {
                Locations.insert {
                    it[userId] = user
                    it[name] = place
                    it[lat] = 42.7
                    it[lon] = 23.3
                    it[chargeEffPct] = pct
                }
            }
            exec("ALTER TABLE discharge_days DROP COLUMN soc") // the schema of the previous version
        }
        initDb(path)
        val pcts = transaction { Locations.selectAll().associate { it[Locations.name] to it[Locations.chargeEffPct] } }
        assertEquals(mapOf("Sofia" to null, "Varna" to 85), pcts, "a typed-in value stays")
    }
}
