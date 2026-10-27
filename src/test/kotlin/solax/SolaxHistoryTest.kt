package ilios.solax

import ilios.db.BatteryDays
import ilios.db.Solax
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The battery history of past days. */
class SolaxHistoryTest : SolaxFixture() {
    @Test
    fun aFullBatteryAmongSeveralDoesNotCountAsFull() = runBlocking {
        val (loc, svc, _) = connected()
        val yesterday = today.minusDays(1)
        socPoints = listOf(Triple(at(yesterday, "12:00"), 100.0, 80.0)) // averages to 90 %, not 100 %
        repeat(daysToFetch() + 1) { svc.batteryHistoryIfDue(loc, noon) }
        assertEquals(90.0, SolaxReadings.batteryDays(loc.id).getValue(yesterday))
    }

    @Test
    fun aDayWithoutHistoryIsNotAskedForAgain() = runBlocking {
        val (loc, svc, seen) = connected()
        socPoints = emptyList()
        repeat(daysToFetch() + 2) { svc.batteryHistoryIfDue(loc, noon) }
        val calls = historyCalls(seen).size
        repeat(3) { svc.batteryHistoryIfDue(loc, noon.plusSeconds(3600L * it)) }
        assertEquals(calls, historyCalls(seen).size)
        assertTrue(SolaxReadings.batteryDays(loc.id).isEmpty())
    }

    @Test
    fun aFailedHistoryRequestIsRetriedAtMostHourly() = runBlocking {
        val (loc, svc, seen) = connected()
        historyBroken = true
        svc.batteryHistoryIfDue(loc, noon)
        val first = historyCalls(seen).size
        assertTrue(first >= 1)
        assertTrue(SolaxReadings.batteryDays(loc.id).isEmpty())

        svc.batteryHistoryIfDue(loc, noon.plusSeconds(30 * 60))
        assertEquals(first, historyCalls(seen).size, "backing off")

        historyBroken = false
        socPoints = listOf(Triple(at(today.minusDays(1), "12:00"), 100.0, 100.0))
        svc.batteryHistoryIfDue(loc, noon.plusSeconds(61 * 60))
        assertTrue(historyCalls(seen).size > first)
        assertEquals(null, row(loc)[Solax.historyTryAt], "success clears the back-off")
    }

    @Test
    fun noBatteryMeansNoHistoryRequests() = runBlocking {
        batteryDevicesReply = """{"code":10000,"result":{"records":[]}}"""
        val loc = place()
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") ->
                    """{"code":10000,"result":{"records":[{"plantId":"P1","plantName":"Home","pvCapacity":5.0,"batteryCapacity":0.0}]}}"""
                else -> other(req)
            }
        }
        svc.sync(loc, noon)
        svc.batteryHistoryIfDue(loc, noon)
        assertTrue(historyCalls(seen).isEmpty())
    }

    @Test
    fun theDayThatEndsSummerTimeNeedsThreeWindowsOfAtMostTwelveHours() = runBlocking {
        val (loc, svc, seen) = connected("Europe/Sofia")
        // 25 October 2026 has 25 hours in Sofia (clocks go back); make it the next day to fetch
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.batteryDaysThrough] = "2026-10-24" } }

        svc.batteryHistoryIfDue(loc, Instant.parse("2026-10-27T12:00:00Z"))

        val windows = historyCalls(seen).map {
            it.url.parameters["startTime"]!!.toLong() to
                it.url.parameters["endTime"]!!.toLong()
        }
        assertEquals(3, windows.size)
        assertTrue(windows.all { (a, b) -> b - a <= 12L * 3600 * 1000 })
        assertEquals(25L * 3600 * 1000, windows.sumOf { it.second - it.first }, "the whole 25-hour day is covered")
        assertEquals("2026-10-25", row(loc)[Solax.batteryDaysThrough])
    }

    @Test
    fun choosingAnotherPlantForgetsTheBatteryHistoryProgress() = runBlocking {
        val (loc, svc, _) = twoPlantService().let { (s, q) -> Triple(place(), s, q) }
        svc.sync(loc, noon)
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.batteryDaysThrough] = "2026-10-14" } }
        SolaxConnection.configure(loc.id, "client-1", "", "P2")
        assertEquals(null, row(loc)[Solax.batteryDaysThrough])
    }

    @Test
    fun theReportCarriesThePeakChargeOfPastDays() = withPlace(solar = true) { c, id ->
        transaction {
            Solax.insert {
                it[locationId] = id
                it[clientId] = "c"
                it[clientSecret] = "s"
                it[pvKwp] = 5.0
                it[batteryDeviceKwh] = 10.0
                it[dueAt] = Long.MAX_VALUE
            }
            BatteryDays.insert {
                it[locationId] = id
                it[day] = today.minusDays(1).toString()
                it[maxSoc] = 100.0
            }
        }
        val days = (report(c)["days"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        val yesterday = days.single { it["date"]!!.jsonPrimitive.content == today.minusDays(1).toString() }
        assertEquals("100.0", yesterday["maxSoc"]!!.jsonPrimitive.content)
    }
}
