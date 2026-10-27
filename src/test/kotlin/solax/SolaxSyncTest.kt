package ilios.solax

import ilios.db.BatteryDays
import ilios.db.Locations
import ilios.db.Produced
import ilios.db.Solax
import ilios.db.Users
import ilios.db.initDb
import ilios.tempDbPath
import ilios.web.userId
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production sync, back-offs and the battery reading schedule. */
class SolaxSyncTest : SolaxFixture() {
    @Test
    fun theBatteryIsReadRightAfterConnectingAndThenHourly() = runBlocking {
        val (loc, svc, seen) = connected()

        svc.readBatteryIfDue(loc, at("2026-10-16T08:00:00Z")) // 11:00 Sofia: first reading after connecting
        val first = SolaxRepository.plant(loc.id)!!
        assertEquals(75.0, first.soc) // 70 % and 80 % average
        assertEquals(15.0, first.remainingKwh)
        assertEquals(2000.0, first.powerW)
        assertEquals(at("2026-10-16T08:00:00Z").toEpochMilli(), first.readAt)
        assertContains(row(loc)[Solax.batteryStatus]!!, "75%")
        val read = paths(seen, "/realtime_data").single().url.parameters
        assertEquals("INV1", read["snList"])
        assertEquals("2", read["deviceType"])
        assertEquals("1", read["requestSnType"])

        svc.readBatteryIfDue(loc, at("2026-10-16T08:50:00Z")) // same hour: nothing new is due
        assertEquals(1, paths(seen, "/realtime_data").size)

        svc.readBatteryIfDue(loc, at("2026-10-16T09:01:00Z")) // the next hour has begun
        assertEquals(2, paths(seen, "/realtime_data").size)
        svc.readBatteryIfDue(loc, at("2026-10-16T09:40:00Z")) // already read this hour
        assertEquals(2, paths(seen, "/realtime_data").size)

        svc.readBatteryIfDue(loc, at("2026-10-17T19:31:00Z")) // much later
        assertEquals(3, paths(seen, "/realtime_data").size)
        assertEquals(
            1,
            paths(seen, "/page_device_info").count {
                it.url.parameters["deviceType"] == "1"
            },
            "the inverter serials are looked up once",
        )
    }

    @Test
    fun eachPastDayIsFetchedOnceAndNewDaysAreAddedInTheMorning() = runBlocking {
        val (loc, svc, seen) = connected() // backfilled up to yesterday at noon
        val before = statCalls(seen)
        // a value that a re-fetch would overwrite: proves old days are left alone
        val old = today.minusDays(3)
        transaction {
            Produced.update({ Produced.locationId eq loc.id and (Produced.day eq old.toString()) }) {
                it[Produced.kwh] =
                    99.0
            }
        }

        svc.syncIfDue(loc, noon.plusSeconds(30 * 60)) // later the same day: nothing new
        svc.syncIfDue(loc, noon.plusSeconds(13 * 3600)) // 01:00 next day: yesterday is not final yet
        assertEquals(before, statCalls(seen))

        svc.syncIfDue(loc, noon.plusSeconds(14 * 3600)) // 02:00 next day: `today` is now a final day
        assertEquals(before + 1, statCalls(seen), "one statistics call for the new day")
        val produced = SolaxReadings.produced(loc.id)
        assertEquals(3.5, produced.getValue(today))
        assertEquals(99.0, produced.getValue(old), "already fetched days are not fetched again")
        assertEquals(today.toString(), row(loc)[Solax.producedThrough])

        svc.syncIfDue(loc, noon.plusSeconds(15 * 3600))
        assertEquals(before + 1, statCalls(seen))
    }

    @Test
    fun aDayWithoutDataIsNotAskedForAgain() = runBlocking {
        val loc = place()
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith(
                    "/get_stat_data",
                ) -> """{"code":10000,"result":{"plantEnergyStatDataList":[]}}"""
                else -> other(req)
            }
        }
        svc.sync(loc, noon)
        val calls = statCalls(seen)

        svc.syncIfDue(loc, noon.plusSeconds(3600))
        svc.syncIfDue(loc, noon.plusSeconds(6 * 3600))

        assertEquals(calls, statCalls(seen))
        assertTrue(SolaxReadings.produced(loc.id).isEmpty())
    }

    @Test
    fun aFailedProductionSyncIsRetriedAtMostHourly() = runBlocking {
        val loc = place()
        var broken = true
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith("/get_stat_data") && broken -> """{"code":10200,"message":"boom"}"""
                else -> other(req)
            }
        }

        svc.syncIfDue(loc, noon)
        val stats = statCalls(seen)
        assertTrue(stats >= 1)
        svc.syncIfDue(loc, noon.plusSeconds(30 * 60))
        assertEquals(stats, statCalls(seen), "backing off")

        broken = false
        svc.syncIfDue(loc, noon.plusSeconds(61 * 60))
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
        assertTrue(SolaxReadings.produced(loc.id).isNotEmpty())
    }

    @Test
    fun wrongCredentialsAreRetriedOnlyAfterTwelveHours() = runBlocking {
        val loc = place()
        var denied = true
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> if (denied) """{"code":10401}""" else token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                else -> other(req)
            }
        }

        svc.syncIfDue(loc, noon)
        val tokens = paths(seen, "/oauth/token").size
        svc.syncIfDue(loc, noon.plusSeconds(61 * 60))
        svc.syncIfDue(loc, noon.plusSeconds(11 * 3600))
        assertEquals(tokens, paths(seen, "/oauth/token").size, "no point asking again every hour")

        denied = false
        svc.syncIfDue(loc, noon.plusSeconds(12 * 3600 + 60))
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
    }

    @Test
    fun fixingTheCredentialsRetriesImmediatelyDespiteTheBackOff() = runBlocking {
        val loc = place()
        val (denied, _) = service { """{"code":10401}""" }
        denied.sync(loc, noon)
        assertTrue(row(loc)[Solax.dueAt] > noon.toEpochMilli())

        assertTrue(SolaxConnection.configure(loc.id, "client-1", "corrected-secret", ""))

        assertEquals(0L, row(loc)[Solax.dueAt], "the back-off ends as soon as the user changes the credentials")
    }

    @Test
    fun yesterdayBecomesFinalAt0200LocalTime() {
        fun day(time: String) = lastProductionDay(java.time.ZonedDateTime.parse("2026-10-16T$time+03:00[Europe/Sofia]"))
        assertEquals(LocalDate.parse("2026-10-14"), day("01:59:00"))
        assertEquals(LocalDate.parse("2026-10-15"), day("02:00:00"))
        assertEquals(LocalDate.parse("2026-10-15"), day("23:59:00"))
    }

    @Test
    fun theBatterySlotIsTheStartOfTheCurrentHour() {
        assertEquals(at("2026-10-24T19:00:00Z"), lastBatterySlot(at("2026-10-24T19:00:00Z")))
        assertEquals(at("2026-10-24T19:00:00Z"), lastBatterySlot(at("2026-10-24T19:59:59Z")))
        assertEquals(at("2026-10-24T18:00:00Z"), lastBatterySlot(at("2026-10-24T18:30:00Z")))
    }

    @Test
    fun aFailedBatteryReadSaysSoAndIsRetriedAtMostHourly() = runBlocking {
        val loc = place()
        var broken = true
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith("/realtime_data") && broken -> """{"code":10200,"message":"boom"}"""
                else -> other(req)
            }
        }
        svc.sync(loc, noon)

        svc.readBatteryIfDue(loc, at("2026-10-16T08:00:00Z"))
        assertContains(row(loc)[Solax.batteryStatus]!!, "SolaX върна грешка")
        assertEquals(null, SolaxRepository.plant(loc.id)!!.soc)
        assertTrue(status(loc).startsWith("Свързано"), "production status is unaffected")
        assertTrue(SolaxReadings.produced(loc.id).isNotEmpty())

        svc.readBatteryIfDue(loc, at("2026-10-16T08:30:00Z")) // 30 min later: still backing off
        assertEquals(1, paths(seen, "/realtime_data").size)

        broken = false
        svc.readBatteryIfDue(loc, at("2026-10-16T09:05:00Z")) // more than an hour later
        assertEquals(2, paths(seen, "/realtime_data").size)
        assertEquals(75.0, SolaxRepository.plant(loc.id)!!.soc)
    }

    @Test
    fun theCapacityIsReadOnceAndRefreshedAboutWeekly() = runBlocking {
        val loc = place()
        var plantReply = onePlant
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> plantReply
                else -> other(req)
            }
        }

        svc.sync(loc, noon)
        svc.sync(loc, noon)
        assertEquals(1, paths(seen, "/page_plant_info").size, "the second sync reuses the stored capacity")
        assertEquals(10.0, SolaxRepository.plant(loc.id)!!.batteryKwh)

        // the owner enlarges the battery; after a week the app notices
        batteryDevicesReply =
            """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"6000"},{"deviceSn":"B2","ratedCapacity":"6000"}]}}"""
        transaction {
            Solax.update({ Solax.locationId eq loc.id }) {
                it[Solax.plantReadAt] =
                    System.currentTimeMillis() - 8L * 24 * 3600 * 1000
            }
        }
        svc.sync(loc, noon)

        assertEquals(2, paths(seen, "/page_plant_info").size)
        assertEquals(12.0, SolaxRepository.plant(loc.id)!!.batteryKwh)
    }

    @Test
    fun aRotatedSecretForTheSamePlantKeepsEverythingStoredAndOnlyReadsThePlantListAgain() = runBlocking {
        val (loc, svc, seen) = connected()
        val old = today.minusDays(3)
        transaction {
            Produced.update({ Produced.locationId eq loc.id and (Produced.day eq old.toString()) }) {
                it[Produced.kwh] =
                    99.0
            }
            BatteryDays.insert {
                it[locationId] = loc.id
                it[day] = old.toString()
                it[maxSoc] = 100.0
            }
            Solax.update({
                Solax.locationId eq loc.id
            }) { it[Solax.batteryDaysThrough] = today.minusDays(1).toString() }
        }
        val stats = statCalls(seen)

        assertTrue(SolaxConnection.configure(loc.id, "client-1", "rotated-secret", ""))
        assertEquals(10.0, SolaxRepository.plant(loc.id)!!.batteryKwh, "the stored capacity stays visible meanwhile")
        assertEquals("P1", SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plantId)
        svc.sync(loc, noon)

        assertEquals(2, paths(seen, "/page_plant_info").size, "only the plant list is read again")
        assertEquals(stats, statCalls(seen), "no production is fetched again")
        assertEquals(99.0, SolaxReadings.produced(loc.id).getValue(old), "stored production is kept")
        assertEquals(100.0, SolaxReadings.batteryDays(loc.id).getValue(old), "stored battery history is kept")
        assertEquals(today.minusDays(1).toString(), row(loc)[Solax.batteryDaysThrough])
    }

    @Test
    fun daysStoredBeforeTheAcColumnExistedAreFetchedAgainOnce() {
        val path = tempDbPath()
        initDb(path)
        fun through() = transaction { Solax.selectAll().single()[Solax.producedThrough] }
        transaction {
            val user = Users.insert {
                it[email] = "a@example.com"
                it[passHash] = "x"
            }[Users.id]
            val loc = Locations.insert {
                it[userId] = user
                it[name] = "Sofia"
                it[lat] = 42.7
                it[lon] = 23.3
                it[tz] = "UTC"
                it[solar] = true
            }[Locations.id]
            Solax.insert {
                it[locationId] = loc
                it[clientId] = "c"
                it[clientSecret] = "s"
                it[producedThrough] = "2026-10-15"
            }
            exec("ALTER TABLE produced DROP COLUMN ac_kwh") // the schema of an older version
        }

        initDb(path) // first start after the upgrade
        assertEquals(null, through(), "the history is fetched again to get the AC output")

        transaction { Solax.update({ Solax.clientId eq "c" }) { it[producedThrough] = "2026-10-15" } }
        initDb(path) // later starts must not repeat it
        assertEquals("2026-10-15", through())
    }

    @Test
    fun theHighestChargeOfEachPastDayIsFetchedOneDayPerCallAndOnlyOnce() = runBlocking {
        val (loc, svc, seen) = connected()
        val yesterday = today.minusDays(1)
        socPoints = listOf(
            Triple(at(yesterday, "10:00"), 96.0, 100.0),
            Triple(at(yesterday, "14:00"), 100.0, 100.0), // 98 % then 100 %
            Triple(at(today.minusDays(2), "13:00"), 90.0, 84.0), // 87 % at best
        )

        svc.batteryHistoryIfDue(loc, noon)
        assertEquals(2, historyCalls(seen).size, "one day = two 12-hour requests")
        val oldest = today.with(
            java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY),
        ).minusDays(7)
        assertEquals(
            oldest.toString(),
            row(loc)[Solax.batteryDaysThrough],
            "the first call handled only the oldest day of the backlog",
        )

        // work off the backlog; extra calls must do nothing
        repeat(daysToFetch() + 3) { svc.batteryHistoryIfDue(loc, noon) }
        assertEquals(2 * daysToFetch(), historyCalls(seen).size, "every day is requested exactly once")
        val days = SolaxReadings.batteryDays(loc.id)
        assertEquals(100.0, days.getValue(yesterday))
        assertEquals(87.0, days.getValue(today.minusDays(2)))
        assertEquals(yesterday.toString(), row(loc)[Solax.batteryDaysThrough])

        // every request stays within the API's 12-hour limit and the two windows of a day are contiguous
        val windows = historyCalls(seen).map {
            it.url.parameters["startTime"]!!.toLong() to
                it.url.parameters["endTime"]!!.toLong()
        }
        assertTrue(windows.all { (a, b) -> b - a <= 12L * 3600 * 1000 })
        assertEquals(windows[0].second, windows[1].first)
        val first = historyCalls(seen).first().url.parameters
        assertEquals("INV1", first["snList"])
        assertEquals("2", first["deviceType"])
        assertEquals("1", first["requestSnType"])

        svc.batteryHistoryIfDue(loc, noon.plusSeconds(14 * 3600)) // 02:00 next day: `today` is now final
        assertEquals(2 * daysToFetch() + 2, historyCalls(seen).size)
    }
}
