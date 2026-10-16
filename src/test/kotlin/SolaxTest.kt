package ilios

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SolaX sync against a fake SolaX server (Ktor mock engine): no network, no real credentials. */
class SolaxTest {
    private val today = LocalDate.now(ZoneId.of("UTC"))
    private val noon = today.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC) // fixed time of day, so tests do not depend on when they run

    private fun service(handler: (HttpRequestData) -> String): Pair<SolaxService, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            respond(handler(req), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return SolaxService(client, "https://solax.test") to seen
    }

    /** A solar place with SolaX credentials saved but not yet synced. */
    private fun place(tz: String = "UTC"): Loc {
        initDb(tempDbPath())
        val locId = transaction {
            val user = Users.insert { it[email] = "a@example.com"; it[passHash] = "x" }[Users.id]
            Locations.insert {
                it[userId] = user; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[Locations.tz] = tz; it[solar] = true
            }[Locations.id]
        }
        val svc = service { "" }.first
        assertTrue(svc.configure(locId, "client-1", "very-secret", ""))
        return locOf(transaction { Locations.selectAll().where { Locations.id eq locId }.single() })
    }

    private fun status(loc: Loc) = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.single()[Solax.status] }
    private fun row(loc: Loc) = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.single() }

    private val token = """{"code":0,"result":{"access_token":"tok-1","expires_in":2591999}}"""
    private val onePlant = """{"code":10000,"result":{"records":[{"plantId":"P1","plantName":"Home","pvCapacity":5.0,"batteryCapacity":10.0}]}}"""

    /** Battery history served by the fake server: (moment, charge of battery B1, charge of battery B2). */
    private var socPoints = listOf<Triple<Instant, Double, Double>>()
    private var historyBroken = false
    private val utcStamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"))

    private fun historyReply(req: HttpRequestData): String {
        if (historyBroken) return """{"code":10200,"message":"boom"}"""
        val from = Instant.ofEpochMilli(req.url.parameters["startTime"]!!.toLong())
        val to = Instant.ofEpochMilli(req.url.parameters["endTime"]!!.toLong())
        val points = socPoints.filter { it.first >= from && it.first < to }.flatMap { (moment, a, b) ->
            listOf("B1" to a, "B2" to b).map { (sn, v) ->
                """{"deviceSn":"$sn","plantLocalTime":"${utcStamp.format(moment)}","batterySOC":$v,"batteryRemainings":${v / 100 * USABLE_PER_BATTERY}}"""
            }
        }
        return """{"code":10000,"result":[${points.joinToString(",")}]}"""
    }

    /** Each of the two fake batteries stores 5 kWh at 100 %, so the system's rated capacity is 10 kWh. */
    private val USABLE_PER_BATTERY = 5.0
    private var batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"5000"},{"deviceSn":"B2","ratedCapacity":"5000"}]}}"""
    private val devices = """{"code":10000,"result":{"records":[{"deviceSn":"INV1"}]}}"""
    private val batteries = """{"code":10000,"result":[
        {"deviceSn":"B1","batterySOC":70.0,"batteryRemainings":7.0,"chargeDischargePower":1500.0},
        {"deviceSn":"B2","batterySOC":80.0,"batteryRemainings":8.0,"chargeDischargePower":500.0}]}"""

    /** Everything after authentication and the plant lookup: devices, battery data, statistics. */
    private fun other(req: HttpRequestData) = when {
        req.url.encodedPath.endsWith("/page_device_info") ->
            if (req.url.parameters["deviceType"] == "2") {
                if (req.url.parameters["plantId"] == "P2") """{"code":10000,"result":{"records":[]}}"""
                else batteryDevicesReply
            } else devices
        req.url.encodedPath.endsWith("/realtime_data") -> batteries
        req.url.encodedPath.endsWith("/history_data") -> historyReply(req)
        else -> stats(req)
    }

    private fun paths(seen: List<HttpRequestData>, suffix: String) = seen.filter { it.url.encodedPath.endsWith(suffix) }

    /** Statistics for the requested month: 3.5 kWh on each of the last 20 days of it. */
    private fun stats(req: HttpRequestData): String {
        val month = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["date"]!!.jsonPrimitive.content
        val rows = (0L..20L).map { today.minusDays(it) }.filter { it.toString().startsWith(month) }
            .joinToString(",") { """{"date":"$it","pvGeneration":3.5,"inverterACOutputEnergy":3.0}""" }
        return """{"code":10000,"result":{"plantEnergyStatDataList":[$rows]}}"""
    }

    @Test
    fun syncStoresProducedEnergyAndDiscoversTheSinglePlant() = runBlocking {
        val loc = place()
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                else -> other(req)
            }
        }

        svc.sync(loc, noon)

        val produced = svc.produced(loc.id)
        assertTrue(produced.size >= 7, "expected the days from last week's Monday to yesterday, got ${produced.size}")
        assertEquals(3.5, produced.getValue(today.minusDays(1)))
        assertFalse(produced.containsKey(today), "today is not final yet, so it is not fetched")
        // the inverter's AC output is kept next to the PV generation: the SolaX app's "Inverter Output" is that figure
        assertEquals(3.0, svc.producedAc(loc.id).getValue(today.minusDays(1)))
        assertContains(status(loc), "произведена енергия до")
        assertEquals(today.minusDays(1).toString(), row(loc)[Solax.producedThrough])
        assertEquals("P1", row(loc)[Solax.plantId])
        assertEquals(1, row(loc)[Solax.businessType])
        // installed PV power and battery capacity come from the plant record and devices, not from settings
        assertEquals(5.0, row(loc)[Solax.pvKwp])
        assertEquals(10.0, row(loc)[Solax.batteryDeviceKwh])
        assertEquals(5.0, svc.plant(loc.id)!!.kwp)
        assertEquals(10.0, svc.plant(loc.id)!!.batteryKwh)
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
        assertContains(status(loc), "5,0 kWp")
        assertContains(status(loc), "10,0 kWh")
        assertFalse(status(loc).contains("заряд"))
        assertFalse(status(loc).contains("very-secret"))
        // the bearer token is sent exactly as SolaX documents it
        assertEquals("bearer tok-1", seen.last { it.url.encodedPath.endsWith("get_stat_data") }.headers[HttpHeaders.Authorization])
        assertTrue(paths(seen, "/realtime_data").isEmpty(), "the 3-hourly production sync must not read the battery")
        assertEquals(0L, row(loc)[Solax.dueAt], "no back-off after a success; the next fetch is due when a new day is final")
    }

    private val twoPlants = """{"code":10000,"result":{"records":[
        {"plantId":"P1","plantName":"Home","pvCapacity":5.0,"batteryCapacity":10.0},
        {"plantId":"P2","plantName":"Garage","pvCapacity":3.0,"batteryCapacity":0.0}]}}"""

    private fun twoPlantService() = service { req ->
        when {
            req.url.encodedPath.endsWith("/oauth/token") -> token
            req.url.encodedPath.endsWith("/page_plant_info") -> twoPlants
            else -> other(req)
        }
    }

    @Test
    fun severalPlantsAreOfferedAndTheFirstOneIsSelected() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()

        svc.sync(loc, noon)

        val info = svc.infos(listOf(loc.id)).getValue(loc.id)
        assertEquals(listOf("P1", "P2"), info.plants.map { it.id })
        assertEquals(listOf("Home", "Garage"), info.plants.map { it.name })
        assertEquals("P1", info.plantId, "the first plant is the default")
        assertEquals(5.0, svc.plant(loc.id)!!.kwp)
        assertTrue(svc.produced(loc.id).isNotEmpty())
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
    }

    @Test
    fun choosingAnotherPlantReadsThatPlantsData() = runBlocking {
        val loc = place()
        val (svc, seen) = twoPlantService()
        svc.sync(loc, noon)

        assertTrue(svc.configure(loc.id, "client-1", "", "P2"), "a changed selection needs a sync")
        assertEquals(null, svc.plant(loc.id)!!.kwp, "the first plant's data is forgotten")
        svc.sync(loc, noon)

        assertEquals("P2", svc.infos(listOf(loc.id)).getValue(loc.id).plantId)
        assertEquals(3.0, svc.plant(loc.id)!!.kwp)
        assertEquals(null, svc.plant(loc.id)!!.batteryKwh) // 0 kWh = no battery
        assertContains(status(loc), "Garage")
        svc.readBatteryIfDue(loc, Instant.parse("2026-10-16T19:40:00Z"))
        assertTrue(paths(seen, "/realtime_data").isEmpty(), "the chosen plant has no battery, so no charge request")
        assertEquals(2, svc.infos(listOf(loc.id)).getValue(loc.id).plants.size, "the list is kept")

        assertFalse(svc.configure(loc.id, "client-1", "", "P2"), "saving the same selection changes nothing")
    }

    @Test
    fun aPlantThatIsNotInTheListIsRejected() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()
        svc.sync(loc, noon)

        assertFailsWith<HttpError> { svc.configure(loc.id, "client-1", "", "P99") }
        assertEquals("P1", svc.infos(listOf(loc.id)).getValue(loc.id).plantId)
    }

    @Test
    fun newCredentialsKeepTheSelectedPlantWhenTheNewAccountStillHasIt() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()
        svc.sync(loc, noon)
        svc.configure(loc.id, "client-1", "", "P2")
        svc.sync(loc, noon)

        assertTrue(svc.configure(loc.id, "client-2", "another-secret", ""))
        val during = svc.infos(listOf(loc.id)).getValue(loc.id)
        assertEquals("P2", during.plantId, "the selection is kept until the new account has been read")
        assertTrue(during.plants.isEmpty(), "the old account's list is gone until it is read again")
        svc.sync(loc, noon)

        assertEquals("P2", svc.infos(listOf(loc.id)).getValue(loc.id).plantId)
        assertEquals(2, svc.infos(listOf(loc.id)).getValue(loc.id).plants.size)
    }

    @Test
    fun newCredentialsForADifferentAccountStartOverWithItsFirstPlantAndDropTheOldData() = runBlocking {
        val loc = place()
        val (first, _) = twoPlantService()
        first.sync(loc, noon)
        assertTrue(first.produced(loc.id).isNotEmpty())
        transaction { BatteryDays.insert { it[locationId] = loc.id; it[day] = today.minusDays(2).toString(); it[maxSoc] = 100.0 } }

        assertTrue(first.configure(loc.id, "client-2", "other-account", ""))
        val (second, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") ->
                    """{"code":10000,"result":{"records":[{"plantId":"X9","plantName":"Other","pvCapacity":2.0,"batteryCapacity":0.0}]}}"""
                else -> other(req)
            }
        }
        second.sync(loc, noon)

        assertEquals("X9", second.infos(listOf(loc.id)).getValue(loc.id).plantId)
        assertEquals(2.0, second.plant(loc.id)!!.kwp)
        assertTrue(second.batteryDays(loc.id).isEmpty(), "the old plant's battery history is dropped")
        assertTrue(second.produced(loc.id).keys.all { it >= today.minusDays(14) }, "only the new plant's production is stored")
        assertEquals(today.minusDays(1).toString(), row(loc)[Solax.producedThrough], "and it was fetched afresh")
    }

    @Test
    fun aSelectedPlantThatDisappearedGivesAClearStatusAndKeepsTheList() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()
        svc.sync(loc, noon)
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.plantId] = "GONE"; it[Solax.plantReadAt] = null } }

        svc.sync(loc, noon)

        assertContains(status(loc), "GONE")
        assertEquals(2, svc.infos(listOf(loc.id)).getValue(loc.id).plants.size, "the dropdown still works")
    }

    @Test
    fun theAccountsPlantsAreReadPageByPage() = runBlocking {
        val loc = place()
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") ->
                    if (req.url.parameters["pageNo"] == "2") """{"code":10000,"result":{"pages":2,"records":[{"plantId":"P11","plantName":"Eleventh"}]}}"""
                    else """{"code":10000,"result":{"pages":2,"records":[{"plantId":"P1","plantName":"First","pvCapacity":5.0,"batteryCapacity":10.0}]}}"""
                else -> other(req)
            }
        }

        svc.sync(loc, noon)

        assertEquals(listOf("P1", "P11"), svc.infos(listOf(loc.id)).getValue(loc.id).plants.map { it.id })
        assertEquals(2, paths(seen, "/page_plant_info").size)
    }

    @Test
    fun noPlantsInTheAccountGivesAClearStatus() = runBlocking {
        val loc = place()
        val (svc, _) = service { req ->
            if (req.url.encodedPath.endsWith("/oauth/token")) token else """{"code":10000,"result":{"records":[]}}"""
        }

        svc.sync(loc, noon)

        assertContains(status(loc), "не са намерени централи")
    }

    @Test
    fun aRejectedTokenIsRenewedOnce() = runBlocking {
        val loc = place()
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.token] = "stale"; it[Solax.tokenExpires] = System.currentTimeMillis() + 100000 } }
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.headers[HttpHeaders.Authorization] == "bearer stale" -> """{"code":10402,"message":"token invalid"}"""
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                else -> other(req)
            }
        }

        svc.sync(loc, noon)

        assertTrue(status(loc).startsWith("Свързано"), status(loc))
        assertEquals(1, seen.count { it.url.encodedPath.endsWith("/oauth/token") })
        assertEquals("tok-1", row(loc)[Solax.token])
    }

    @Test
    fun badCredentialsAndQuotaGiveAReadableStatusAndBackOffAccordingToTheirCause() = runBlocking {
        val loc = place()
        val (denied, _) = service { """{"code":10401,"message":"bad"}""" }
        denied.sync(loc, noon)
        assertContains(status(loc), "Client ID")
        assertFalse(status(loc).contains("very-secret"))
        assertEquals(noon.toEpochMilli() + 12 * 3_600_000L, row(loc)[Solax.dueAt], "wrong credentials do not fix themselves: wait 12 hours")

        val (exhausted, _) = service { """{"code":10405,"message":"quota"}""" }
        exhausted.sync(loc, noon)
        assertContains(status(loc), "лимит", ignoreCase = true)
        assertEquals(noon.toEpochMilli() + 6 * 3_600_000L, row(loc)[Solax.dueAt], "an exhausted allowance: wait 6 hours")

        val (rate, _) = service { """{"code":10406,"message":"too fast"}""" }
        rate.sync(loc, noon)
        assertEquals(noon.toEpochMilli() + 3_600_000L, row(loc)[Solax.dueAt], "a rate limit passes soon: wait an hour")
    }

    @Test
    fun disconnectingRemovesTheCredentialsButKeepsTheHistory() {
        val loc = place()
        transaction { Produced.insert { it[locationId] = loc.id; it[day] = today.toString(); it[kwh] = 7.0 } }
        val (svc, _) = service { "" }

        assertEquals(false, svc.configure(loc.id, "", "", ""))

        assertEquals(0L, transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.count() })
        assertEquals(7.0, svc.produced(loc.id).getValue(today))
    }

    @Test
    fun theSecretIsNeverSentBackToTheBrowser() {
        initDb(tempDbPath())
        testApplication {
            application { module() }
            val c = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
            c.post("/api/register") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com","password":"password1"}""") }
            val uid = transaction { Users.selectAll().single()[Users.id] }
            val locId = transaction { Locations.insert { it[userId] = uid; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[tz] = "UTC" }[Locations.id] }

            // solar = false so saving does not call the real SolaX API
            val res = c.put("/api/locations/$locId") {
                contentType(ContentType.Application.Json)
                setBody("""{"solar":false,"solaxClientId":"client-1","solaxClientSecret":"very-secret"}""")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertFalse(res.bodyAsText().contains("very-secret"))

            val list = c.get("/api/locations").bodyAsText()
            assertFalse(list.contains("very-secret"))
            val first = Json.parseToJsonElement(list).let { (it as kotlinx.serialization.json.JsonArray).single().jsonObject }
            assertEquals("client-1", first["solaxClientId"]!!.jsonPrimitive.content)
            assertEquals("true", first["solaxHasSecret"]!!.jsonPrimitive.content)
        }
    }

    private fun connected(tz: String = "UTC"): Triple<Loc, SolaxService, MutableList<HttpRequestData>> {
        val loc = place(tz)
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                else -> other(req)
            }
        }
        runBlocking { svc.sync(loc, noon) } // finds the plant and its 10 kWh battery
        return Triple(loc, svc, seen)
    }

    private fun at(utc: String) = Instant.parse(utc)

    @Test
    fun theBatteryIsReadRightAfterConnectingAndThenOncePerDayAt2230SofiaTime() = runBlocking {
        val (loc, svc, seen) = connected()

        svc.readBatteryIfDue(loc, at("2026-10-16T08:00:00Z")) // 11:00 Sofia: first reading after connecting
        val first = svc.plant(loc.id)!!
        assertEquals(75.0, first.soc) // 70 % and 80 % average
        assertEquals(15.0, first.remainingKwh)
        assertEquals(2000.0, first.powerW)
        assertEquals(at("2026-10-16T08:00:00Z").toEpochMilli(), first.readAt)
        assertContains(row(loc)[Solax.batteryStatus]!!, "75%")
        val read = paths(seen, "/realtime_data").single().url.parameters
        assertEquals("INV1", read["snList"]); assertEquals("2", read["deviceType"]); assertEquals("1", read["requestSnType"])

        svc.readBatteryIfDue(loc, at("2026-10-16T12:00:00Z")) // 15:00 Sofia: nothing new is due
        svc.readBatteryIfDue(loc, at("2026-10-16T19:29:00Z")) // 22:29 Sofia: one minute early
        assertEquals(1, paths(seen, "/realtime_data").size)

        svc.readBatteryIfDue(loc, at("2026-10-16T19:31:00Z")) // 22:31 Sofia: the 22:30 slot has passed
        assertEquals(2, paths(seen, "/realtime_data").size)
        svc.readBatteryIfDue(loc, at("2026-10-16T21:00:00Z")) // already read after this slot
        assertEquals(2, paths(seen, "/realtime_data").size)

        svc.readBatteryIfDue(loc, at("2026-10-17T19:31:00Z")) // next day
        assertEquals(3, paths(seen, "/realtime_data").size)
        assertEquals(1, paths(seen, "/page_device_info").count { it.url.parameters["deviceType"] == "1" }, "the inverter serials are looked up once")
    }

    private fun statCalls(seen: List<HttpRequestData>) = paths(seen, "/get_stat_data").size

    @Test
    fun eachPastDayIsFetchedOnceAndNewDaysAreAddedInTheMorning() = runBlocking {
        val (loc, svc, seen) = connected() // backfilled up to yesterday at noon
        val before = statCalls(seen)
        // a value that a re-fetch would overwrite: proves old days are left alone
        val old = today.minusDays(3)
        transaction { Produced.update({ (Produced.locationId eq loc.id) and (Produced.day eq old.toString()) }) { it[Produced.kwh] = 99.0 } }

        svc.syncIfDue(loc, noon.plusSeconds(30 * 60)) // later the same day: nothing new
        svc.syncIfDue(loc, noon.plusSeconds(13 * 3600)) // 01:00 next day: yesterday is not final yet
        assertEquals(before, statCalls(seen))

        svc.syncIfDue(loc, noon.plusSeconds(14 * 3600)) // 02:00 next day: `today` is now a final day
        assertEquals(before + 1, statCalls(seen), "one statistics call for the new day")
        val produced = svc.produced(loc.id)
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
                req.url.encodedPath.endsWith("/get_stat_data") -> """{"code":10000,"result":{"plantEnergyStatDataList":[]}}"""
                else -> other(req)
            }
        }
        svc.sync(loc, noon)
        val calls = statCalls(seen)

        svc.syncIfDue(loc, noon.plusSeconds(3600))
        svc.syncIfDue(loc, noon.plusSeconds(6 * 3600))

        assertEquals(calls, statCalls(seen))
        assertTrue(svc.produced(loc.id).isEmpty())
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
        assertTrue(svc.produced(loc.id).isNotEmpty())
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

        assertTrue(denied.configure(loc.id, "client-1", "corrected-secret", ""))

        assertEquals(0L, row(loc)[Solax.dueAt], "the back-off ends as soon as the user changes the credentials")
    }

    @Test
    fun yesterdayBecomesFinalAt0200LocalTime() {
        fun day(time: String) = lastProductionDay(java.time.ZonedDateTime.parse("2026-10-16T${time}+03:00[Europe/Sofia]"))
        assertEquals(LocalDate.parse("2026-10-14"), day("01:59:00"))
        assertEquals(LocalDate.parse("2026-10-15"), day("02:00:00"))
        assertEquals(LocalDate.parse("2026-10-15"), day("23:59:00"))
    }

    @Test
    fun theSlotFollowsSofiaTimeAcrossTheSummerWinterChange() {
        // summer time (UTC+3): 22:30 Sofia is 19:30 UTC
        assertEquals(at("2026-10-24T19:30:00Z"), lastBatterySlot(at("2026-10-24T19:30:00Z")))
        assertEquals(at("2026-10-23T19:30:00Z"), lastBatterySlot(at("2026-10-24T19:29:00Z")))
        // winter time (UTC+2) from 25 October: 22:30 Sofia is 20:30 UTC
        assertEquals(at("2026-10-26T20:30:00Z"), lastBatterySlot(at("2026-10-26T20:45:00Z")))
        assertEquals(at("2026-10-25T20:30:00Z"), lastBatterySlot(at("2026-10-26T20:29:00Z")))
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
        assertContains(row(loc)[Solax.batteryStatus]!!, "Solax върна грешка")
        assertEquals(null, svc.plant(loc.id)!!.soc)
        assertTrue(status(loc).startsWith("Свързано"), "production status is unaffected")
        assertTrue(svc.produced(loc.id).isNotEmpty())

        svc.readBatteryIfDue(loc, at("2026-10-16T08:30:00Z")) // 30 min later: still backing off
        assertEquals(1, paths(seen, "/realtime_data").size)

        broken = false
        svc.readBatteryIfDue(loc, at("2026-10-16T09:05:00Z")) // more than an hour later
        assertEquals(2, paths(seen, "/realtime_data").size)
        assertEquals(75.0, svc.plant(loc.id)!!.soc)
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
        assertEquals(10.0, svc.plant(loc.id)!!.batteryKwh)

        // the owner enlarges the battery; after a week the app notices
        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"6000"},{"deviceSn":"B2","ratedCapacity":"6000"}]}}"""
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.plantReadAt] = System.currentTimeMillis() - 8L * 24 * 3600 * 1000 } }
        svc.sync(loc, noon)

        assertEquals(2, paths(seen, "/page_plant_info").size)
        assertEquals(12.0, svc.plant(loc.id)!!.batteryKwh)
    }

    @Test
    fun aRotatedSecretForTheSamePlantKeepsEverythingStoredAndOnlyReadsThePlantListAgain() = runBlocking {
        val (loc, svc, seen) = connected()
        val old = today.minusDays(3)
        transaction {
            Produced.update({ (Produced.locationId eq loc.id) and (Produced.day eq old.toString()) }) { it[Produced.kwh] = 99.0 }
            BatteryDays.insert { it[locationId] = loc.id; it[day] = old.toString(); it[maxSoc] = 100.0 }
            Solax.update({ Solax.locationId eq loc.id }) { it[Solax.batteryDaysThrough] = today.minusDays(1).toString() }
        }
        val stats = statCalls(seen)

        assertTrue(svc.configure(loc.id, "client-1", "rotated-secret", ""))
        assertEquals(10.0, svc.plant(loc.id)!!.batteryKwh, "the stored capacity stays visible meanwhile")
        assertEquals("P1", svc.infos(listOf(loc.id)).getValue(loc.id).plantId)
        svc.sync(loc, noon)

        assertEquals(2, paths(seen, "/page_plant_info").size, "only the plant list is read again")
        assertEquals(stats, statCalls(seen), "no production is fetched again")
        assertEquals(99.0, svc.produced(loc.id).getValue(old), "stored production is kept")
        assertEquals(100.0, svc.batteryDays(loc.id).getValue(old), "stored battery history is kept")
        assertEquals(today.minusDays(1).toString(), row(loc)[Solax.batteryDaysThrough])
    }

    @Test
    fun daysStoredBeforeTheAcColumnExistedAreFetchedAgainOnce() {
        val path = tempDbPath()
        initDb(path)
        fun through() = transaction { Solax.selectAll().single()[Solax.producedThrough] }
        transaction {
            val user = Users.insert { it[email] = "a@example.com"; it[passHash] = "x" }[Users.id]
            val loc = Locations.insert { it[userId] = user; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[tz] = "UTC"; it[solar] = true }[Locations.id]
            Solax.insert { it[locationId] = loc; it[clientId] = "c"; it[clientSecret] = "s"; it[producedThrough] = "2026-10-15" }
            exec("ALTER TABLE produced DROP COLUMN ac_kwh") // the schema of an older version
        }

        initDb(path) // first start after the upgrade
        assertEquals(null, through(), "the history is fetched again to get the AC output")

        transaction { Solax.update({ Solax.clientId eq "c" }) { it[producedThrough] = "2026-10-15" } }
        initDb(path) // later starts must not repeat it
        assertEquals("2026-10-15", through())
    }

    private fun historyCalls(seen: List<HttpRequestData>) = paths(seen, "/history_data")
    private fun at(day: LocalDate, time: String) = Instant.parse("${day}T${time}:00Z")
    private fun daysToFetch(): Int { // from last week's Monday to yesterday, as the app counts them
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusDays(7)
        return (today.toEpochDay() - 1 - monday.toEpochDay() + 1).toInt()
    }

    @Test
    fun theHighestChargeOfEachPastDayIsFetchedOneDayPerCallAndOnlyOnce() = runBlocking {
        val (loc, svc, seen) = connected()
        val yesterday = today.minusDays(1)
        socPoints = listOf(
            Triple(at(yesterday, "10:00"), 96.0, 100.0), Triple(at(yesterday, "14:00"), 100.0, 100.0), // 98 % then 100 %
            Triple(at(today.minusDays(2), "13:00"), 90.0, 84.0), // 87 % at best
        )

        svc.batteryHistoryIfDue(loc, noon)
        assertEquals(2, historyCalls(seen).size, "one day = two 12-hour requests")
        val oldest = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusDays(7)
        assertEquals(oldest.toString(), row(loc)[Solax.batteryDaysThrough], "the first call handled only the oldest day of the backlog")

        repeat(daysToFetch() + 3) { svc.batteryHistoryIfDue(loc, noon) } // work off the backlog; extra calls must do nothing
        assertEquals(2 * daysToFetch(), historyCalls(seen).size, "every day is requested exactly once")
        val days = svc.batteryDays(loc.id)
        assertEquals(100.0, days.getValue(yesterday))
        assertEquals(87.0, days.getValue(today.minusDays(2)))
        assertEquals(yesterday.toString(), row(loc)[Solax.batteryDaysThrough])

        // every request stays within the API's 12-hour limit and the two windows of a day are contiguous
        val windows = historyCalls(seen).map { it.url.parameters["startTime"]!!.toLong() to it.url.parameters["endTime"]!!.toLong() }
        assertTrue(windows.all { (a, b) -> b - a <= 12L * 3600 * 1000 })
        assertEquals(windows[0].second, windows[1].first)
        val first = historyCalls(seen).first().url.parameters
        assertEquals("INV1", first["snList"]); assertEquals("2", first["deviceType"]); assertEquals("1", first["requestSnType"])

        svc.batteryHistoryIfDue(loc, noon.plusSeconds(14 * 3600)) // 02:00 next day: `today` is now final
        assertEquals(2 * daysToFetch() + 2, historyCalls(seen).size)
    }

    @Test
    fun aFullBatteryAmongSeveralDoesNotCountAsFull() = runBlocking {
        val (loc, svc, _) = connected()
        val yesterday = today.minusDays(1)
        socPoints = listOf(Triple(at(yesterday, "12:00"), 100.0, 80.0)) // averages to 90 %, not 100 %
        repeat(daysToFetch() + 1) { svc.batteryHistoryIfDue(loc, noon) }
        assertEquals(90.0, svc.batteryDays(loc.id).getValue(yesterday))
    }

    @Test
    fun aDayWithoutHistoryIsNotAskedForAgain() = runBlocking {
        val (loc, svc, seen) = connected()
        socPoints = emptyList()
        repeat(daysToFetch() + 2) { svc.batteryHistoryIfDue(loc, noon) }
        val calls = historyCalls(seen).size
        repeat(3) { svc.batteryHistoryIfDue(loc, noon.plusSeconds(3600L * it)) }
        assertEquals(calls, historyCalls(seen).size)
        assertTrue(svc.batteryDays(loc.id).isEmpty())
    }

    @Test
    fun aFailedHistoryRequestIsRetriedAtMostHourly() = runBlocking {
        val (loc, svc, seen) = connected()
        historyBroken = true
        svc.batteryHistoryIfDue(loc, noon)
        val first = historyCalls(seen).size
        assertTrue(first >= 1)
        assertTrue(svc.batteryDays(loc.id).isEmpty())

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

        val windows = historyCalls(seen).map { it.url.parameters["startTime"]!!.toLong() to it.url.parameters["endTime"]!!.toLong() }
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
        svc.configure(loc.id, "client-1", "", "P2")
        assertEquals(null, row(loc)[Solax.batteryDaysThrough])
    }

    @Test
    fun theReportCarriesThePeakChargeOfPastDays() = withPlace(solar = true) { c, id ->
        transaction {
            Solax.insert { it[locationId] = id; it[clientId] = "c"; it[clientSecret] = "s"; it[pvKwp] = 5.0; it[batteryDeviceKwh] = 10.0; it[dueAt] = Long.MAX_VALUE }
            BatteryDays.insert { it[locationId] = id; it[day] = today.minusDays(1).toString(); it[maxSoc] = 100.0 }
        }
        val days = (report(c)["days"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        val yesterday = days.single { it["date"]!!.jsonPrimitive.content == today.minusDays(1).toString() }
        assertEquals("100.0", yesterday["maxSoc"]!!.jsonPrimitive.content)
    }

    private fun batteryDeviceCalls(seen: List<HttpRequestData>) = paths(seen, "/page_device_info").filter { it.url.parameters["deviceType"] == "2" }

    @Test
    fun theBatteryDevicesOwnCapacityIsReadWithThePlantRecordAndConvertedFromWatthours() = runBlocking {
        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"7500"},{"deviceSn":"B2","ratedCapacity":7500}]}}"""
        val (loc, svc, seen) = connected()

        val p = svc.plant(loc.id)!!
        assertEquals(15.0, p.deviceKwh, "two batteries of 7500 Wh")
        assertEquals(15.0, p.batteryKwh, "the devices' ratedCapacity figure is used for battery capacity")
        assertEquals(1, batteryDeviceCalls(seen).size)
        assertEquals("1", batteryDeviceCalls(seen).single().url.parameters["businessType"])
        val info = svc.infos(listOf(loc.id)).getValue(loc.id).batteryInfo
        assertContains(info, "15,0 kWh")
        assertContains(status(loc), "батерия 15,0 kWh", message = "status summary uses the device hardware rated capacity")

        svc.sync(loc, noon)
        assertEquals(1, batteryDeviceCalls(seen).size, "the device list is read with the weekly plant record, not on every sync")
    }

    @Test
    fun commercialBatteryCapacitiesAreAlreadyInKwh() = runBlocking {
        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"215"}]}}"""
        val loc = place()
        val (svc, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith("/page_device_info") && req.url.parameters["deviceType"] == "2" -> batteryDevicesReply
                else -> other(req)
            }
        }
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.businessType] = 4 } }
        svc.sync(loc, noon)
        assertEquals(215.0, svc.plant(loc.id)!!.deviceKwh)
    }

    @Test
    fun aFailingDeviceListDoesNotBreakTheSync() = runBlocking {
        val loc = place()
        val (svc, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith("/page_device_info") && req.url.parameters["deviceType"] == "2" -> """{"code":10200,"message":"boom"}"""
                else -> other(req)
            }
        }
        svc.sync(loc, noon)
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
        assertEquals(null, svc.plant(loc.id)!!.deviceKwh)
        assertEquals(null, svc.plant(loc.id)!!.batteryKwh, "does not fall back to other ways to fetch battery capacity")
    }

    @Test
    fun batteryHistoryRecordsDailyMaxSoc() = runBlocking {
        val (loc, svc, _) = connected()
        val yesterday = today.minusDays(1)
        socPoints = listOf(Triple(at(yesterday, "09:00"), 10.0, 10.0), Triple(at(yesterday, "12:00"), 60.0, 60.0), Triple(at(yesterday, "15:00"), 100.0, 100.0))
        repeat(daysToFetch() + 1) { svc.batteryHistoryIfDue(loc, noon) }

        assertEquals(100.0, svc.batteryDays(loc.id).getValue(yesterday))
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

        assertEquals(12.0, svc.plant(loc.id)!!.batteryKwh, "the record says 0 but the devices list a battery")
        assertTrue(paths(seen, "/realtime_data").isNotEmpty(), "so its charge is read")
    }

    @Test
    fun placesConnectedBeforeTheDeviceCapacityExistedReadItOnceAfterTheUpgrade() {
        val path = tempDbPath()
        initDb(path)
        fun readAt() = transaction { Solax.selectAll().single()[Solax.plantReadAt] }
        transaction {
            val user = Users.insert { it[email] = "a@example.com"; it[passHash] = "x" }[Users.id]
            val loc = Locations.insert { it[userId] = user; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[tz] = "UTC"; it[solar] = true }[Locations.id]
            Solax.insert { it[locationId] = loc; it[clientId] = "c"; it[clientSecret] = "s"; it[plantReadAt] = 1_700_000_000_000 }
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
        assertEquals(null, svc.plant(loc.id)!!.deviceKwh)

        batteryDevicesReply = """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"6000"}]}}"""
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.plantReadAt] = null } }
        svc.sync(loc, noon)
        assertEquals(null, row(loc)[Solax.diagDevices])
        assertEquals(6.0, svc.plant(loc.id)!!.deviceKwh)
    }

    private fun report(c: HttpClient) = runBlocking {
        Json.parseToJsonElement(c.get("/api/report").bodyAsText()).let { (it as kotlinx.serialization.json.JsonArray).single().jsonObject }
    }

    /** A signed-in user with one place that already has fresh weather rows, so the report needs no network. */
    private fun withPlace(solar: Boolean, block: suspend (HttpClient, Long) -> Unit) {
        initDb(tempDbPath())
        testApplication {
            application { module() }
            val c = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
            c.post("/api/register") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com","password":"password1"}""") }
            val uid = transaction { Users.selectAll().single()[Users.id] }
            val id = transaction { Locations.insert { it[userId] = uid; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[tz] = "UTC"; it[Locations.solar] = solar }[Locations.id] }
            transaction {
                for (offset in -14L..13L) Daily.insert {
                    it[locationId] = id; it[day] = today.plusDays(offset).toString()
                    it[tMax] = 20.0; it[tMin] = 10.0; it[rainMm] = 0.0; it[radiationMj] = 18.0; it[fetchedAt] = System.currentTimeMillis()
                }
            }
            block(c, id)
        }
    }

    @Test
    fun reportUsesPvPowerAndBatteryFromSolax() = withPlace(solar = true) { c, id ->
        transaction {
            Solax.insert {
                it[locationId] = id; it[clientId] = "c"; it[clientSecret] = "s"; it[pvKwp] = 5.0; it[batteryDeviceKwh] = 10.0
                it[soc] = 62.0; it[batteryRemainingKwh] = 6.2; it[batteryPowerW] = -300.0; it[batteryReadAt] = 1_700_000_000_000; it[dueAt] = Long.MAX_VALUE
            }
        }
        val r = report(c)
        val battery = r["battery"]!!.jsonObject
        assertEquals("10.0", battery["batteryKwh"]!!.jsonPrimitive.content)
        assertEquals("62.0", battery["soc"]!!.jsonPrimitive.content)
        assertEquals("-300.0", battery["powerW"]!!.jsonPrimitive.content)
        // 5 kWp x 18 MJ/m2 / 3.6 x 0.75 = 18.75 kWh estimated per day
        assertEquals(18.8, r["days"]!!.let { (it as kotlinx.serialization.json.JsonArray)[10].jsonObject["kwh"]!!.jsonPrimitive.content.toDouble() })
    }

    private fun day(offset: Long, radiationMj: Double = 18.0) = Day(today.plusDays(offset), 20.0, 10.0, 0.0, radiationMj)

    @Test
    fun theEfficiencyIsCalibratedFromMeasuredProduction() {
        // 5 kWp x 18 MJ/m2 / 3.6 = 25 kWh ideal per day; 15 kWh measured means the system delivers 60 %
        val days = (-6L..6L).map { day(it) }
        val produced = (-5L..-1L).associate { today.plusDays(it) to 15.0 }
        assertEquals(0.6, calibratedRatio(days, produced, 5.0, today), 1e-9)
    }

    @Test
    fun theEfficiencyFallsBackToTheDefaultWithoutEnoughMeasuredDays() {
        val days = (-6L..6L).map { day(it) }
        assertEquals(DEFAULT_RATIO, calibratedRatio(days, emptyMap(), 5.0, today))
        assertEquals(DEFAULT_RATIO, calibratedRatio(days, mapOf(today.minusDays(1) to 15.0, today.minusDays(2) to 15.0), 5.0, today), "only two days")
        assertEquals(DEFAULT_RATIO, calibratedRatio(days, (-5L..-1L).associate { today.plusDays(it) to 15.0 }, 0.0, today), "no panel power known")
    }

    @Test
    fun theEfficiencyIgnoresTodayOutagesAndAbsurdValues() {
        val days = (-6L..6L).map { day(it) }
        val good = (-5L..-3L).associate { today.plusDays(it) to 15.0 }
        // today is incomplete and a zero day is an outage: neither may drag the result down
        val noisy = good + mapOf(today to 1.0, today.minusDays(1) to 0.0)
        assertEquals(0.6, calibratedRatio(days, noisy, 5.0, today), 1e-9)
        // implausibly high or low results are clamped
        assertEquals(1.0, calibratedRatio(days, (-5L..-1L).associate { today.plusDays(it) to 500.0 }, 5.0, today))
        assertEquals(0.3, calibratedRatio(days, (-5L..-1L).associate { today.plusDays(it) to 1.0 }, 5.0, today))
    }

    @Test
    fun theForecastUsesTheCalibratedEfficiency() = withPlace(solar = true) { c, id ->
        transaction {
            Solax.insert { it[locationId] = id; it[clientId] = "c"; it[clientSecret] = "s"; it[pvKwp] = 5.0; it[batteryDeviceKwh] = 10.0; it[dueAt] = Long.MAX_VALUE }
            for (back in 1L..5L) Produced.insert { it[locationId] = id; it[day] = today.minusDays(back).toString(); it[kwh] = 15.0; it[acKwh] = 13.0 }
        }
        val days = (report(c)["days"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        val future = days.last()
        val yesterday = days.single { it["date"]!!.jsonPrimitive.content == today.minusDays(1).toString() }
        assertEquals("15.0", yesterday["produced"]!!.jsonPrimitive.content)
        assertEquals("13.0", yesterday["ac"]!!.jsonPrimitive.content, "the AC output reaches the report for the tooltip")
        // 5 kWp x 18 MJ/m2 / 3.6 x 0.6 (calibrated) = 15.0 kWh, instead of 18.8 kWh with the default 0.75
        assertEquals(15.0, future["kwh"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun aSolarPlaceNotYetConnectedHasNoBatteryOrEstimate() = withPlace(solar = true) { c, _ ->
        val r = report(c)
        assertEquals("true", r["solar"]!!.jsonPrimitive.content)
        assertEquals(null, r["battery"])
    }

    @Test
    fun aPlaceWithoutSolarHasNoBattery() = withPlace(solar = false) { c, _ ->
        assertEquals(null, report(c)["battery"])
    }
}
