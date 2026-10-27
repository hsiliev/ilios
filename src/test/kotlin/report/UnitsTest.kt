package ilios.report

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitsTest {
    private val sofia = ZoneId.of("Europe/Sofia")

    @Test
    fun nightZoneFollowsSummerTime() {
        assertEquals(22 to 6, nightZone(LocalDate.of(2026, 12, 1), sofia))
        assertEquals(23 to 7, nightZone(LocalDate.of(2026, 7, 1), sofia))
        // summer time ended on 25 October 2026
        assertEquals(22 to 6, nightZone(LocalDate.of(2026, 10, 27), sofia))
    }

    @Test
    fun roundingKeepsOneDecimalOrCents() {
        assertEquals(1.3, r1(1.26))
        assertEquals(1.26, r2(1.2649))
    }

    @Test
    fun loadEstimateFallsBackToTheMeanWithTooFewSamples() {
        val few = List(4) { Triple<Double?, Double, Double>(10.0 + it, 5.0, 100.0 + it) }
        assertEquals(101.5, estimateLoad(few, 12.0, 5.0))
        assertNull(estimateLoad(emptyList(), 12.0, 5.0))
    }

    @Test
    fun loadEstimateFollowsTheTemperature() {
        // consumption = 500 - 20 x temperature + 10 x sun
        val samples = List(10) {
            val temp = it.toDouble()
            val sun = (it * it % 7).toDouble()
            Triple<Double?, Double, Double>(temp, sun, 500 - 20 * temp + 10 * sun)
        }
        val est = assertNotNull(estimateLoad(samples, 4.0, 3.0))
        assertTrue(kotlin.math.abs(est - (500 - 80 + 30)) < 1.0, "was $est")
    }
}
