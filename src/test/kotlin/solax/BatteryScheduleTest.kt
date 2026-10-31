package ilios.solax

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryScheduleTest {
    private val sofia = ZoneId.of("Europe/Sofia") // winter: the night zone is 22:00-06:00, UTC+2
    private fun slotAt(day: Int, time: String) = Instant.parse("2026-12-0${day}T$time:00Z")

    @Test
    fun theBatteryIsReadHourlyByDayAndEvery30MinutesAtNight() {
        assertEquals(slotAt(1, "10:00"), batterySlot(slotAt(1, "10:47"), sofia))
        assertEquals(slotAt(1, "20:30"), batterySlot(slotAt(1, "20:47"), sofia)) // 22:47 local
        assertEquals(slotAt(1, "20:00"), batterySlot(slotAt(1, "20:29"), sofia))
    }

    @Test
    fun itIsReadEvery5MinutesInTheHalfHourBeforeTheLatestStartAndTheEnd() {
        // the latest start is 00:00 local, the charge ends at 05:00 local
        val planned = listOf(slotAt(1, "22:00").toEpochMilli(), slotAt(2, "03:00").toEpochMilli())

        fun slot(day: Int, time: String) = batterySlot(slotAt(day, time), sofia, planned)
        assertEquals(slotAt(1, "21:30"), slot(1, "21:34")) // the half hour before the start
        assertEquals(slotAt(1, "21:55"), slot(1, "21:59"))
        assertEquals(slotAt(1, "22:00"), slot(1, "22:00")) // the start itself still counts
        assertEquals(slotAt(1, "21:00"), slot(1, "21:29")) // before the window: the normal slot
        assertEquals(slotAt(1, "22:30"), slot(1, "22:41")) // charging: every 30 minutes again
        assertEquals(slotAt(2, "02:55"), slot(2, "02:58")) // the half hour before the end
        assertEquals(slotAt(2, "03:30"), slot(2, "03:41")) // after the charge: back to normal
    }
}
