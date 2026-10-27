package ilios.solax

import ilios.db.Locations
import ilios.db.Solax
import ilios.db.Users
import ilios.db.initDb
import ilios.tempDbPath
import ilios.web.userId
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The battery devices' own capacity. */
class SolaxCapacityTest : SolaxFixture() {
    @Test
    fun commercialBatteryCapacitiesAreAlreadyInKwh() = runBlocking {
        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"215"}]}}"""
        val loc = place()
        val (svc, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith(
                    "/page_device_info",
                ) &&
                    req.url.parameters["deviceType"] == "2" -> batteryDevicesReply
                else -> other(req)
            }
        }
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.businessType] = 4 } }
        svc.sync(loc, noon)
        assertEquals(215.0, SolaxRepository.plant(loc.id)!!.deviceKwh)
    }

    @Test
    fun aFailingDeviceListDoesNotBreakTheSync() = runBlocking {
        val loc = place()
        val (svc, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith(
                    "/page_device_info",
                ) &&
                    req.url.parameters["deviceType"] == "2" -> """{"code":10200,"message":"boom"}"""
                else -> other(req)
            }
        }
        svc.sync(loc, noon)
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
        assertEquals(null, SolaxRepository.plant(loc.id)!!.deviceKwh)
        assertEquals(
            null,
            SolaxRepository.plant(loc.id)!!.batteryKwh,
            "does not fall back to other ways to fetch battery capacity",
        )
    }

    @Test
    fun batteryHistoryRecordsDailyMaxSoc() = runBlocking {
        val (loc, svc, _) = connected()
        val yesterday = today.minusDays(1)
        socPoints =
            listOf(
                Triple(at(yesterday, "09:00"), 10.0, 10.0),
                Triple(at(yesterday, "12:00"), 60.0, 60.0),
                Triple(at(yesterday, "15:00"), 100.0, 100.0),
            )
        repeat(daysToFetch() + 1) { svc.batteryHistoryIfDue(loc, noon) }

        assertEquals(100.0, SolaxReadings.batteryDays(loc.id).getValue(yesterday))
    }

    @Test
    fun aBatteryOnlyKnownFromItsDevicesIsStillRead() = runBlocking {
        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"12000"}]}}"""
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
        svc.readBatteryIfDue(loc, Instant.parse("2026-10-16T19:40:00Z"))

        assertEquals(
            12.0,
            SolaxRepository.plant(loc.id)!!.batteryKwh,
            "the record says 0 but the devices list a battery",
        )
        assertTrue(paths(seen, "/realtime_data").isNotEmpty(), "so its charge is read")
    }

    @Test
    fun placesConnectedBeforeTheDeviceCapacityExistedReadItOnceAfterTheUpgrade() {
        val path = tempDbPath()
        initDb(path)
        fun readAt() = transaction { Solax.selectAll().single()[Solax.plantReadAt] }
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
                it[plantReadAt] = 1_700_000_000_000
            }
            exec("ALTER TABLE solax DROP COLUMN battery_device_kwh") // the schema of the previous version
        }

        initDb(path)
        assertEquals(null, readAt(), "the plant record is read again at the next sync")

        transaction { Solax.update({ Solax.clientId eq "c" }) { it[plantReadAt] = 1_700_000_000_000 } }
        initDb(path)
        assertEquals(1_700_000_000_000, readAt(), "later starts leave it alone")
    }

    @Test
    fun theRawAnswerIsKeptWhenSolaxGivesNoDeviceCapacityAndDroppedWhenItDoes() = runBlocking {
        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","batteryType":"T58"}]}}"""
        val (loc, svc, _) = connected()
        assertContains(row(loc)[Solax.diagDevices]!!, "batteryType", message = "the real field names can be inspected")
        assertEquals(null, SolaxRepository.plant(loc.id)!!.deviceKwh)

        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"6000"}]}}"""
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.plantReadAt] = null } }
        svc.sync(loc, noon)
        assertEquals(null, row(loc)[Solax.diagDevices])
        assertEquals(6.0, SolaxRepository.plant(loc.id)!!.deviceKwh)
    }
}
