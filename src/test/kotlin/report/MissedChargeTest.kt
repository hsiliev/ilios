package ilios.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MissedChargeTest {
    private fun clock(slot: Int) = "%02d:%02d".format(slot * 5 / 60, slot * 5 % 60)

    /** The day zone from 06:00: [startSoc] until 10:00, then [floorSoc] with [gridW] drawn for [empty] steps. */
    private fun day(startSoc: Double, floorSoc: Double, gridW: Double, empty: Int): List<TodayPoint> {
        val first = 6 * 12
        val steady = (0 until 4 * 12).map { TodayPoint(clock(first + it), startSoc, 0.0, gridW = 0.0) }
        val drained = (0 until empty).map { TodayPoint(clock(first + 4 * 12 + it), floorSoc, 0.0, gridW = gridW) }
        return steady + drained
    }

    @Test fun `the missed saving is capped by the room the battery had at the start of the day zone`() {
        // 89 % at 06:00 of an 11.3 kWh battery: a full night charge adds 1.24 kWh, however much the house imports later
        val pts = day(startSoc = 89.0, floorSoc = 10.0, gridW = 2000.0, empty = 60) // 10 kWh imported while empty
        val m = assertNotNull(missedCharge(11.3, pts, 10, 6, 0.25, 0.13))
        assertEquals(1.2, m.kwh) // room 1.243 kWh, not the 10 kWh imported
        assertEquals(0.15, m.lostEur) // 1.243 x (0.25 - 0.13)
    }

    @Test fun `what the house imported while the battery was empty counts when it is less than the room`() {
        val pts = day(startSoc = 20.0, floorSoc = 10.0, gridW = 1200.0, empty = 10) // 1 kWh imported while empty
        val m = assertNotNull(missedCharge(11.3, pts, 10, 6, 0.25, 0.13))
        assertEquals(1.0, m.kwh)
        assertEquals(0.12, m.lostEur) // 1 x (0.25 - 0.13)
    }

    @Test fun `a battery that never ran empty lost nothing`() {
        val pts = day(startSoc = 89.0, floorSoc = 30.0, gridW = 2000.0, empty = 60)
        val m = assertNotNull(missedCharge(11.3, pts, 10, 6, 0.25, 0.13))
        assertEquals(0.0, m.lostEur)
        assertTrue(m.text.contains("няма спестявания"))
    }

    @Test fun `energy a fuller battery would have wasted when the sun filled it anyway is not a saving`() {
        // 89 % at 06:00, the sun fills it to 100 % at 10:00, then the evening drains it and the house imports 2 kWh
        val first = 6 * 12
        val start = (0 until 4 * 12).map { TodayPoint(clock(first + it), 89.0, 0.0, gridW = 0.0) }
        val full = (0 until 12).map { TodayPoint(clock(first + 4 * 12 + it), 100.0, 3000.0, gridW = 0.0) }
        val empty = (0 until 12).map { TodayPoint(clock(first + 5 * 12 + it), 10.0, 0.0, gridW = 2000.0) }
        val m = assertNotNull(missedCharge(11.3, start + full + empty, 10, 6, 0.25, 0.13))
        assertEquals(0.0, m.lostEur)
        assertTrue(m.text.contains("слънцето"), m.text)
    }

    @Test fun `the extra energy is used up by the first imports while empty`() {
        // room 1.24 kWh; 1 kWh is imported in the morning, 1 kWh in the evening: only 1.24 kWh could have been covered
        val first = 6 * 12
        val start = (0 until 12).map { TodayPoint(clock(first + it), 89.0, 0.0, gridW = 0.0) }
        val morning = (0 until 6).map { TodayPoint(clock(first + 12 + it), 10.0, 0.0, gridW = 2000.0) }
        val evening = (0 until 6).map { TodayPoint(clock(first + 24 + it), 10.0, 0.0, gridW = 2000.0) }
        val m = assertNotNull(missedCharge(11.3, start + morning + evening, 10, 6, 0.25, 0.13))
        assertEquals(1.2, m.kwh)
    }
}
