package ilios.solax

import ilios.db.Produced
import ilios.db.Solax
import ilios.report.DEFAULT_RATIO
import ilios.report.calibratedRatio
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** What the report shows from the SolaX data. */
class SolaxReportTest : SolaxFixture() {
    @Test
    fun theBatteryDevicesOwnCapacityIsReadWithThePlantRecordAndConvertedFromWatthours() = runBlocking {
        batteryDevicesReply =
            """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"7500"},{"deviceSn":"B2","ratedCapacity":7500}]}}"""
        val (loc, svc, seen) = connected()

        val p = SolaxRepository.plant(loc.id)!!
        assertEquals(15.0, p.deviceKwh, "two batteries of 7500 Wh")
        assertEquals(15.0, p.batteryKwh, "the devices' ratedCapacity figure is used for battery capacity")
        assertEquals(1, batteryDeviceCalls(seen).size)
        assertEquals("1", batteryDeviceCalls(seen).single().url.parameters["businessType"])
        val info = SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).batteryInfo
        assertContains(info, "15,0 kWh")
        assertContains(
            status(loc),
            "батерия 15,0 kWh",
            message = "status summary uses the device hardware rated capacity",
        )

        svc.sync(loc, noon)
        assertEquals(
            1,
            batteryDeviceCalls(seen).size,
            "the device list is read with the weekly plant record, not on every sync",
        )
    }

    @Test
    fun reportUsesPvPowerAndBatteryFromSolax() = withPlace(solar = true) { c, id ->
        transaction {
            Solax.insert {
                it[locationId] = id
                it[clientId] = "c"
                it[clientSecret] = "s"
                it[pvKwp] = 5.0
                it[batteryDeviceKwh] = 10.0
                it[soc] = 62.0
                it[batteryRemainingKwh] = 6.2
                it[batteryPowerW] = -300.0
                it[batteryReadAt] = 1_700_000_000_000
                it[dueAt] = Long.MAX_VALUE
            }
        }
        val r = report(c)
        val battery = r["battery"]!!.jsonObject
        assertEquals("10.0", battery["batteryKwh"]!!.jsonPrimitive.content)
        assertEquals("62.0", battery["soc"]!!.jsonPrimitive.content)
        assertEquals("-300.0", battery["powerW"]!!.jsonPrimitive.content)
        // 5 kWp x 18 MJ/m2 / 3.6 x 0.75 = 18.75 kWh estimated per day
        assertEquals(
            18.8,
            r["days"]!!.let {
                (it as kotlinx.serialization.json.JsonArray)[10].jsonObject["kwh"]!!.jsonPrimitive.content.toDouble()
            },
        )
    }

    @Test
    fun theEfficiencyIsCalibratedFromMeasuredProduction() {
        // 5 kWp x 18 MJ/m2 / 3.6 = 25 kWh ideal per day; 15 kWh measured means the system delivers 60 %
        val days = (-6L..6L).map { day(it) }
        val produced = (-5L..-1L).associate { today.plusDays(it) to 15.0 }
        assertEquals(0.6, calibratedRatio(days, produced, 5.0, today), 1e-9)
    }

    @Test
    fun theEfficiencyFallsBackToTheDefaultWithoutEnoughMeasuredDays() {
        val days = (-6L..6L).map { day(it) }
        assertEquals(DEFAULT_RATIO, calibratedRatio(days, emptyMap(), 5.0, today))
        assertEquals(
            DEFAULT_RATIO,
            calibratedRatio(days, mapOf(today.minusDays(1) to 15.0, today.minusDays(2) to 15.0), 5.0, today),
            "only two days",
        )
        assertEquals(
            DEFAULT_RATIO,
            calibratedRatio(
                days,
                (-5L..-1L).associate {
                    today.plusDays(it) to 15.0
                },
                0.0,
                today,
            ),
            "no panel power known",
        )
    }

    @Test
    fun theEfficiencyIgnoresTodayOutagesAndAbsurdValues() {
        val days = (-6L..6L).map { day(it) }
        val good = (-5L..-3L).associate { today.plusDays(it) to 15.0 }
        // today is incomplete and a zero day is an outage: neither may drag the result down
        val noisy = good + mapOf(today to 1.0, today.minusDays(1) to 0.0)
        assertEquals(0.6, calibratedRatio(days, noisy, 5.0, today), 1e-9)
        // implausibly high or low results are clamped
        assertEquals(1.0, calibratedRatio(days, (-5L..-1L).associate { today.plusDays(it) to 500.0 }, 5.0, today))
        assertEquals(0.3, calibratedRatio(days, (-5L..-1L).associate { today.plusDays(it) to 1.0 }, 5.0, today))
    }

    @Test
    fun theForecastUsesTheCalibratedEfficiency() = withPlace(solar = true) { c, id ->
        transaction {
            Solax.insert {
                it[locationId] = id
                it[clientId] = "c"
                it[clientSecret] = "s"
                it[pvKwp] = 5.0
                it[batteryDeviceKwh] = 10.0
                it[dueAt] = Long.MAX_VALUE
            }
            for (back in 1L..5L) {
                Produced.insert {
                    it[locationId] = id
                    it[day] = today.minusDays(back).toString()
                    it[kwh] = 15.0
                    it[acKwh] = 13.0
                }
            }
        }
        val days = (report(c)["days"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        val future = days.last()
        val yesterday = days.single { it["date"]!!.jsonPrimitive.content == today.minusDays(1).toString() }
        assertEquals("15.0", yesterday["produced"]!!.jsonPrimitive.content)
        assertEquals(
            "13.0",
            yesterday["ac"]!!.jsonPrimitive.content,
            "the AC output reaches the report for the tooltip",
        )
        // 5 kWp x 18 MJ/m2 / 3.6 x 0.6 (calibrated) = 15.0 kWh, instead of 18.8 kWh with the default 0.75
        assertEquals(15.0, future["kwh"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun aSolarPlaceNotYetConnectedHasNoBatteryOrEstimate() = withPlace(solar = true) { c, _ ->
        val r = report(c)
        assertEquals("true", r["solar"]!!.jsonPrimitive.content)
        assertEquals(null, r["battery"])
    }

    @Test
    fun aPlaceWithoutSolarHasNoBattery() = withPlace(solar = false) { c, _ ->
        assertEquals(null, report(c)["battery"])
    }
}
