package ilios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstimateTest {
    @Test
    fun patternIsTheMeanOfTheDaysScaledByTheWeather() {
        val p = loadPattern(listOf(mapOf("08:00" to 400.0), mapOf("08:00" to 600.0)), 2.0)!!
        assertEquals(1000.0, p[slotOf("08:00")], 1e-9)
        assertEquals(1000.0, p[slotOf("12:00")], 1e-9) // slots without data take the mean
        assertNull(loadPattern(emptyList(), 1.0))
    }

    @Test
    fun sunSurplusFillsTheBatteryAndNightDrawsItDown() {
        val pattern = DoubleArray(288) { 500.0 }
        val sunny = estimateDay(slotOf("06:00"), 50.0, 10.0, pattern) { if (it in slotOf("09:00")..slotOf("15:00")) 5000.0 else 0.0 }!!
        assertEquals(100.0, sunny.peakSoc, 1e-9)
        assertTrue(sunny.points.last().soc < 100.0) // the evening load takes some of it back
        val dull = estimateDay(slotOf("06:00"), 30.0, 10.0, pattern) { 0.0 }!!
        assertTrue(dull.chargeNeeded)
        assertTrue(dull.importKwh > 0) // the battery reaches its floor and the grid takes over
        assertEquals(DEFAULT_RESERVE.toDouble(), dull.points.last().soc, 1e-9)
    }

    @Test
    fun noChargeIsNeededWhenTheSunFillsTheBatteryAndNothingRunsOut() {
        val e = estimateDay(slotOf("06:00"), 90.0, 10.0, DoubleArray(288) { 100.0 }) { if (it in slotOf("10:00")..slotOf("14:00")) 4000.0 else 0.0 }!!
        assertFalse(e.chargeNeeded)
        assertNotNull(e.points.firstOrNull())
    }

    @Test
    fun compensationAddsTheDayStartEnergyAndTheLaterShortfall() {
        // 1 kW consumption all day; the sun makes 3 kW from 10:00 to 12:00 (+2 kW surplus), nothing 12:00-15:00 ... then nothing again
        val load = DoubleArray(288) { 1000.0 }
        val pv = { i: Int -> if (i in slotOf("10:00")..<slotOf("12:00")) 3000.0 else 0.0 }
        val c = compensation(load, pv, slotOf("06:00"), slotOf("22:00"), 20.0, 0)
        assertEquals("10:00", c.sunAt)
        assertEquals(4.0, c.dayStartKwh, 1e-6) // 06:00-10:00 at 1 kW
        // total: 16 h x 1 kW - 2 h x 3 kW = 10 kWh; the sun surplus (4 kWh) is used up later, so 6 kWh more than the day start
        assertEquals(10.0, c.totalKwh, 1e-6)
        assertEquals(6.0, c.solarKwh, 1e-6)
        assertEquals(50.0, c.targetSoc, 1e-6)
    }

    @Test
    fun compensationAboveTheCapacityTargetsAFullBattery() {
        val c = compensation(DoubleArray(288) { 1000.0 }, { 0.0 }, slotOf("06:00"), slotOf("22:00"), 10.0, 10)
        assertEquals(16.0, c.totalKwh, 1e-6)
        assertEquals(100.0, c.targetSoc, 1e-6)
        assertNull(c.sunAt)
    }

    @Test
    fun nightZoneShiftsWithSummerTime() {
        val sofia = java.time.ZoneId.of("Europe/Sofia")
        assertEquals(23 to 7, nightZone(java.time.LocalDate.of(2026, 7, 1), sofia))
        assertEquals(22 to 6, nightZone(java.time.LocalDate.of(2026, 12, 1), sofia))
    }

    @Test
    fun theEstimatedChargeNeverGoesBelowTheReservedBattery() {
        val e = estimateDay(slotOf("20:00"), 10.0, 10.0, DoubleArray(288) { 800.0 }, 10) { 0.0 }!!
        assertTrue(e.points.all { it.soc >= 10.0 })
        assertEquals(10.0, e.points.last().soc, 1e-9)
        assertTrue(e.importKwh > 0) // the whole load comes from the grid
    }

    @Test
    fun theEstimateCanRunPastMidnightWithoutChangingTheSummaryOfToday() {
        val pattern = DoubleArray(576) { 100.0 }
        val today = estimateDay(slotOf("22:00"), 50.0, 10.0, pattern) { 0.0 }!!
        val ahead = estimateDay(slotOf("22:00"), 50.0, 10.0, pattern, untilSlot = slotOf("22:00") + 144) { 0.0 }!!

        assertEquals(144, ahead.points.size)
        assertEquals("00:00", ahead.points[24].t)
        assertEquals(today.peakSoc, ahead.peakSoc)
        assertEquals(today.importKwh, ahead.importKwh)
        assertTrue(ahead.points.last().soc < today.points.last().soc, "the night after midnight keeps draining the battery")
    }

    @Test
    fun thePlannedChargeIsDrawnFromTheGridAndRaisesTheBatteryUntilItsTarget() {
        val pattern = DoubleArray(576) { 500.0 }
        val now = slotOf("22:00"); val end = 288 + slotOf("06:55")
        val plain = estimateDay(now, 0.0, 10.0, pattern, 0, end) { 0.0 }!!
        val start = plannedChargeStart(plain, now, end, 10.0, 60.0, 2000.0, 1.1, false)!!
        assertTrue(start > now && start < end, "waits until it has to start: $start")
        assertEquals(end.toDouble(), start + 6.0 / 2.0 * 1.1 * 12, 1.0) // 6 kWh at 2 kW, with the margin, ends at the end slot

        val e = estimateDay(now, 0.0, 10.0, pattern, 0, end, PlannedCharge(start, end, 2000.0, 60.0)) { 0.0 }!!
        val charging = e.points.filter { it.chargeW > 0 }
        assertTrue(charging.isNotEmpty() && charging.all { it.gridW >= it.chargeW && it.batW == 0.0 })
        assertEquals(60.0, e.points.last().soc, 0.5)
        assertTrue(e.points.all { it.soc <= 60.0 + 1e-9 }, "stops at the target")
    }
}

class ProjectedChargeTest {
    private val today = java.time.LocalDate.of(2026, 10, 22)
    private fun report(allow: Boolean): Report {
        val days = (-8L..3L).map { Day(today.plusDays(it), 15.0, 8.0, 0.0, 10.0) }
        val steps = (0 until 96).map { IntraPoint("%02d:%02d".format(it / 4, it % 4 * 15), 10.0, 0.0, null, if (it in 36..68) 400.0 else 0.0) }
        val history = (1..3).map { (0 until 288).associate { i -> slotName(i) to 400.0 } }
        val load = (1L..8L).associate { today.minusDays(it) to 9.6 }
        return buildReport(
            Loc(1, "p", 42.0, 23.0, "Europe/Sofia", true, reservedPct = 10, allowControl = allow, maxChargeW = 2000), days, today,
            Intraday("22:58", 15, steps, steps, steps), plant = SolaxPlant(9.0, 10.0, 30.0), load = load, loadHistory = history,
        )
    }

    @Test
    fun theEstimateShowsTheClimbOfTheIntendedNightChargeBeforeThePlanExists() {
        val on = report(true).estimate!!
        assertTrue(on.points.any { it.chargeW > 0 }, "a charge is projected")
        val floorAt = on.points.indexOfFirst { it.soc <= 11.0 }
        assertTrue(floorAt >= 0 && on.points.drop(floorAt).any { it.soc > 30.0 }, "the battery rises again after reaching its minimum")
        assertTrue(report(false).estimate!!.points.none { it.chargeW > 0 }, "no charge without management")
    }
}
