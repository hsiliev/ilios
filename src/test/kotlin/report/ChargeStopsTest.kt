package ilios.report

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargeStopsTest {
    // 2 kW at 100 %: 2 kWh per hour = 12 slots; a night of constant load
    private fun night(load: Double, slots: Int = 144) = DoubleArray(slots) { load }

    @Test
    fun withoutEnoughNightsItIsTheEnergyOverThePower() {
        assertEquals(12.0, ChargeStops.NONE.slots(2.0, 2000.0, 1.0), 1e-9)
        val two = ChargeStops(listOf(night(6500.0), night(6500.0)))
        assertEquals(12.0, two.slots(2.0, 2000.0, 1.0), 1e-9)
        assertEquals(0.0, ChargeStops(List(3) { night(0.0) }).slots(0.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun aQuietNightIsNotLongerThanThePlainTime() {
        val quiet = ChargeStops(List(4) { night(500.0) })
        assertEquals(12.0, quiet.slots(2.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun theChargeGivesWayWhenTheLoadPassesTheGridLimit() {
        // only 500 W of the 7 kW is left for the charge in the last 6 slots: 6 x 500 W = 0.25 kWh, then 2 kW
        val busy = DoubleArray(144) { if (it < 6) 6500.0 else 0.0 }
        val stops = ChargeStops(List(4) { busy })
        // 2 kWh: 0.25 in the last 6 slots, the other 1.75 kWh at 2 kW = 10.5 slots
        assertEquals(16.5, stops.slots(2.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun theSecondLongestNightCountsSoOneOddNightDoesNot() {
        val busy = DoubleArray(144) { if (it < 12) 7000.0 else 0.0 } // nothing charges in the last hour
        val odd = ChargeStops(listOf(night(0.0), night(0.0), night(0.0), busy))
        assertEquals(12.0, odd.slots(2.0, 2000.0, 1.0), 1e-9)
        val twice = ChargeStops(listOf(night(0.0), night(0.0), busy, busy))
        assertEquals(24.0, twice.slots(2.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun theEfficiencyAppliesToTheStoredEnergy() {
        val stops = ChargeStops(List(3) { night(0.0) })
        assertEquals(12.0 / 0.75, stops.slots(2.0, 2000.0, 0.75), 1e-9)
    }

    @Test
    fun nightsEndAtTheGivenSlotAndTakeTheEveningFromTheDayBefore() {
        // every evening the load is 7 kW from 23:00 to midnight, the rest of the time nothing
        val day = (0 until 288).associate { slotName(it) to if (it >= slotOf("23:00")) 7000.0 else 0.0 }
        val first = LocalDate.parse("2026-10-27")
        val history = (0L until 5L).associate { first.plusDays(it) to day } // the oldest has no day before it
        val stops = chargeStops(history, slotOf("06:00"))
        // 20 kWh at 2 kW = 120 slots; the busy hour (12 slots) lies 72-83 slots before 06:00 and charges nothing
        assertEquals(132.0, stops.slots(20.0, 2000.0, 1.0), 1e-9)
        // a charge that ends before the busy hour is not affected
        assertEquals(12.0, stops.slots(2.0, 2000.0, 1.0), 1e-9)
    }
}
