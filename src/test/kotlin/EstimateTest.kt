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
}
