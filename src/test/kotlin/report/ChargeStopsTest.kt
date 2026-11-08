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
    fun theChargeStopsWhenLoadAndChargeDoNotFitTheTotal() {
        // 6 kW + 2 kW is over 7 kW: nothing is charged in the last 6 slots (5 kW + 2 kW still fits)
        val busy = DoubleArray(144) { if (it < 6) 6000.0 else 5000.0 }
        val stops = ChargeStops(List(4) { busy })
        assertEquals(18.0, stops.slots(2.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun theMedianNightCountsSoOneOddNightDoesNot() {
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

    @Test
    fun withAForecastTheNightsNearestInTemperatureCount() {
        val busy = DoubleArray(144) { if (it < 12) 7000.0 else 0.0 } // the last hour charges nothing
        val quiet = night(0.0)
        // cold nights are busy, mild ones quiet; the temperatures are of the nights in order
        val nights = listOf(busy, busy, busy, quiet, quiet, quiet)
        val temps = listOf(-5.0, -4.0, -3.0, 8.0, 9.0, 10.0)
        assertEquals(24.0, ChargeStops(nights, temps, -4.0).slots(2.0, 2000.0, 1.0), 1e-9)
        assertEquals(12.0, ChargeStops(nights, temps, 9.0).slots(2.0, 2000.0, 1.0), 1e-9)
        // no forecast: the median of all (the upper middle, a busy one)
        assertEquals(24.0, ChargeStops(nights, temps, null).slots(2.0, 2000.0, 1.0), 1e-9)
        // too few nights with a temperature: all nights count
        val few = ChargeStops(nights, listOf(-5.0, null, null, null, null, null), 9.0)
        assertEquals(24.0, few.slots(2.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun aColderForecastAddsSlotsPerDegreeAndAWarmerOneTakesThemAway() {
        val busy = DoubleArray(144) { if (it < 12) 7000.0 else 0.0 } // 24 slots for 2 kWh at 2 kW
        val nights = List(3) { busy }
        val temps = listOf(-5.0, -4.0, -3.0) // mean -4
        // as cold as the nights: no change
        assertEquals(24.0, ChargeStops(nights, temps, -4.0).slots(2.0, 2000.0, 1.0), 1e-9)
        // 5 degrees colder than the nights: 2 slots per degree more
        assertEquals(24.0 + 5 * STOP_SLOTS_PER_DEGREE, ChargeStops(nights, temps, -9.0).slots(2.0, 2000.0, 1.0), 1e-9)
        // 3 degrees warmer: that many fewer
        assertEquals(24.0 - 3 * STOP_SLOTS_PER_DEGREE, ChargeStops(nights, temps, -1.0).slots(2.0, 2000.0, 1.0), 1e-9)
        // much warmer: never less than the plain time
        assertEquals(12.0, ChargeStops(nights, temps, 20.0).slots(2.0, 2000.0, 1.0), 1e-9)
        // no temperatures: no adjustment
        assertEquals(24.0, ChargeStops(nights, emptyList(), -9.0).slots(2.0, 2000.0, 1.0), 1e-9)
    }
}
