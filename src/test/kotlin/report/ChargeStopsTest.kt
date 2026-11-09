package ilios.report

import ilios.solax.LoadSlot
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeStopsTest {
    // 2 kW at 100 %: 2 kWh per hour = 12 slots; the charge pauses in a slot whose load is above 5 kW
    private val limit = 5000.0

    private fun night(load: Double) = DoubleArray(144) { load }

    // the last hour is above the limit (12 slots of pause), then the night is quiet
    private fun busy() = DoubleArray(144) { if (it < 12) 7000.0 else 0.0 }

    private fun stops(nights: List<DoubleArray>, temps: List<Double?> = emptyList(), expected: Double? = null) =
        ChargeStops(nights, limit, temps, expected)

    private fun slots(s: ChargeStops) = s.slots(2.0, 2000.0, 1.0)

    @Test
    fun withoutNightsOrALimitItIsTheEnergyOverThePower() {
        assertEquals(12.0, slots(ChargeStops.NONE), 1e-9)
        assertEquals(12.0, slots(ChargeStops(listOf(busy()), null)), 1e-9) // no limit known: no stops assumed
        assertEquals(0.0, stops(listOf(busy())).slots(0.0, 2000.0, 1.0), 1e-9)
    }

    @Test
    fun aQuietNightIsNotLongerThanThePlainTime() {
        assertEquals(12.0, slots(stops(List(4) { night(500.0) })), 1e-9)
    }

    @Test
    fun theChargePausesInEverySlotWhoseLoadIsAboveTheLimit() {
        // 6 kW is above the 5 kW limit in the last 6 slots (nothing charges there), 4.9 kW is not
        val night = DoubleArray(144) { if (it < 6) 6000.0 else 4900.0 }
        assertEquals(18.0, slots(stops(listOf(night, night, night))), 1e-9)
    }

    @Test
    fun theEfficiencyAppliesToTheStoredEnergy() {
        assertEquals(12.0 / 0.75, stops(listOf(night(0.0))).slots(2.0, 2000.0, 0.75), 1e-9)
    }

    @Test
    fun withoutAForecastTheMedianNightCounts() {
        assertEquals(24.0, slots(stops(listOf(night(0.0), busy(), busy()))), 1e-9)
        assertEquals(12.0, slots(stops(listOf(night(0.0), night(0.0), busy()))), 1e-9)
    }

    @Test
    fun withAForecastTheClosestNightByTemperatureIsReplayed() {
        // cold nights were busy, mild ones quiet
        val nights = listOf(busy(), busy(), busy(), night(0.0), night(0.0), night(0.0))
        val temps = listOf(-5.0, -4.0, -3.0, 8.0, 9.0, 10.0)
        assertEquals(24.0, slots(stops(nights, temps, -4.0)), 1e-9)
        assertEquals(12.0, slots(stops(nights, temps, 9.0)), 1e-9)
        assertEquals(24.0, slots(stops(nights, temps, null)), 1e-9) // no forecast: the median night
    }

    @Test
    fun aTieInTemperatureTakesTheMostRecentNight() {
        val temps = listOf(5.0, 5.0, 20.0)
        assertEquals(12.0, slots(stops(listOf(busy(), night(0.0), night(0.0)), temps, 5.0)), 1e-9)
        assertEquals(24.0, slots(stops(listOf(night(0.0), busy(), night(0.0)), temps, 5.0)), 1e-9)
    }

    @Test
    fun nightsWithoutATemperatureAreNotPickedByIt() {
        val nights = listOf(busy(), busy(), night(0.0))
        val temps = listOf(null, null, 5.0) // only the last one has a temperature
        assertEquals(12.0, slots(stops(nights, temps, 5.0)), 1e-9)
        assertEquals(24.0, slots(stops(nights, temps, null)), 1e-9)
    }

    @Test
    fun aColderForecastAddsSlotsPerDegreeAndAWarmerOneTakesThemAway() {
        val nights = List(3) { busy() }
        val temps = listOf(-5.0, -4.0, -3.0)
        assertEquals(24.0, slots(stops(nights, temps, -4.0)), 1e-9) // as cold as the closest night
        assertEquals(24.0 + 4 * STOP_SLOTS_PER_DEGREE, slots(stops(nights, temps, -9.0)), 1e-9)
        assertEquals(24.0 - 2 * STOP_SLOTS_PER_DEGREE, slots(stops(nights, temps, -1.0)), 1e-9)
        assertEquals(12.0, slots(stops(nights, temps, 20.0)), 1e-9) // never less than the plain time
    }

    @Test
    fun theCutSlotsAreThoseOfTheNightTheTimeIsWorkedOutFrom() {
        val nights = listOf(busy(), night(0.0), night(0.0))
        val temps = listOf(-3.0, 8.0, 9.0)
        val cut = stops(nights, temps, -3.0).cutSlots(2.0, 2000.0, 1.0)
        assertEquals(12, cut.count { it })
        assertTrue(cut.take(12).all { it })
        assertTrue(stops(nights, temps, 9.0).cutSlots(2.0, 2000.0, 1.0).none { it })
        assertEquals(0, ChargeStops.NONE.cutSlots(2.0, 2000.0, 1.0).size) // no history: no pauses
        assertEquals(0, stops(nights, temps, -3.0).cutSlots(0.0, 2000.0, 1.0).size) // nothing to charge
    }

    // ---- the limit worked out from the stored nights

    private fun slot(load: Double, charge: Double) = LoadSlot(load, charge)

    /** A night as stored: the slots charging at 1.9 kW with the given loads, and some that did not charge. */
    private fun night(charging: List<Double>, notCharging: List<Double> = emptyList()): List<LoadSlot?> =
        charging.map { slot(it, 1900.0) } + notCharging.map { slot(it, 0.0) }

    @Test
    fun theLimitIsTheHighestLoadAtWhichTheChargeWentOn() {
        val nights = List(3) { night(charging = listOf(500.0, 1000.0, 4900.0), notCharging = listOf(6000.0)) }
        assertEquals(4900.0, maxChargingLoad(nights, 1000.0)!!, 1e-9)
    }

    @Test
    fun aPartialChargeCountsAsGoingOnAndNoChargeDoesNot() {
        val nights = listOf(
            listOf(slot(500.0, 1900.0), slot(5600.0, 1100.0), slot(5900.0, 400.0), slot(8000.0, 0.0)),
            night(charging = listOf(600.0)),
            night(charging = listOf(700.0)),
        )
        assertEquals(5600.0, maxChargingLoad(nights, 1000.0)!!, 1e-9)
    }

    @Test
    fun slotsWithoutDataAreIgnored() {
        val nights = List(3) { listOf(null, slot(3000.0, 1900.0), null) }
        assertEquals(3000.0, maxChargingLoad(nights, 1000.0)!!, 1e-9)
    }

    @Test
    fun nothingIsLearntFromTooFewNightsThatCharged() {
        val charged = night(charging = listOf(500.0), notCharging = listOf(6000.0))
        assertNull(maxChargingLoad(List(2) { charged }, 1000.0))
        assertNull(maxChargingLoad(List(5) { night(charging = emptyList(), notCharging = listOf(500.0)) }, 1000.0))
    }

    private fun history(slotAt: (Int) -> LoadSlot): Map<LocalDate, Map<String, LoadSlot>> {
        val day = (0 until 288).associate { slotName(it) to slotAt(it) }
        val first = LocalDate.parse("2026-10-27")
        return (0L until 5L).associate { first.plusDays(it) to day } // the oldest has no day before it
    }

    @Test
    fun nightsEndAtTheGivenSlotAndTakeTheEveningFromTheDayBefore() {
        // every evening the load is 7 kW from 23:00 to midnight and nothing charges, the rest of the time it is quiet
        // (0.5 kW) and 2 kW go into the battery: the limit is 0.5 kW, so the busy hour pauses the charge
        val history = history { if (it >= slotOf("23:00")) slot(7000.0, 0.0) else slot(500.0, 2000.0) }
        val stops = chargeStops(history, slotOf("06:00"), emptyMap(), null, 2000)
        // 20 kWh at 2 kW = 120 slots; the busy hour (12 slots) lies 72-83 slots before 06:00 and charges nothing
        assertEquals(132.0, stops.slots(20.0, 2000.0, 1.0), 1e-9)
        // a charge that ends before the busy hour is not affected
        assertEquals(12.0, slots(stops), 1e-9)
        // the same history gives the figure shown in the settings
        assertEquals(500.0, measuredChargingLoadW(history, slotOf("06:00"), 2000)!!, 1e-9)
    }

    @Test
    fun theNightsTakeTheTemperatureOfTheirDay() {
        val first = LocalDate.parse("2026-10-27")
        fun day(busyFrom: String?) = (0 until 288).associate {
            val busy = busyFrom != null && it in slotOf(busyFrom) until slotOf("05:25")
            slotName(it) to if (busy) slot(7000.0, 0.0) else slot(500.0, 2000.0)
        }
        // the third day was busy for 25 minutes just before the end and was cold, the others were not
        val history = mapOf(
            first to day(null),
            first.plusDays(1) to day(null),
            first.plusDays(2) to day("05:00"),
            first.plusDays(3) to day(null),
        )
        val temps = mapOf(first.plusDays(1) to 8.0, first.plusDays(2) to -3.0, first.plusDays(3) to 9.0)
        assertEquals(17.0, slots(chargeStops(history, slotOf("06:00"), temps, -3.0, 2000)), 1e-9)
        assertEquals(12.0, slots(chargeStops(history, slotOf("06:00"), temps, 9.0, 2000)), 1e-9)
    }
}
