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
}
