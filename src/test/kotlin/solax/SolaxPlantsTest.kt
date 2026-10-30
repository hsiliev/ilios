package ilios.solax

import ilios.HttpError
import ilios.db.BatteryDays
import ilios.db.Locations
import ilios.db.Produced
import ilios.db.Solax
import ilios.db.Users
import ilios.db.initDb
import ilios.module
import ilios.solax.api.OK
import ilios.tempDbPath
import ilios.web.userId
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Connecting, choosing plants and credentials. */
class SolaxPlantsTest : SolaxFixture() {
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

        val produced = SolaxReadings.produced(loc.id)
        assertTrue(produced.size >= 7, "expected the days from last week's Monday to yesterday, got ${produced.size}")
        assertEquals(3.5, produced.getValue(today.minusDays(1)))
        assertFalse(produced.containsKey(today), "today is not final yet, so it is not fetched")
        // the inverter's AC output is kept next to the PV generation: the SolaX app's "Inverter Output" is that figure
        assertEquals(3.0, SolaxReadings.producedAc(loc.id).getValue(today.minusDays(1)))
        assertContains(status(loc), "централа")
        assertEquals(today.minusDays(1).toString(), row(loc)[Solax.producedThrough])
        assertEquals("P1", row(loc)[Solax.plantId])
        assertEquals(1, row(loc)[Solax.businessType])
        // installed PV power and battery capacity come from the plant record and devices, not from settings
        assertEquals(5.0, row(loc)[Solax.pvKwp])
        assertEquals(10.0, row(loc)[Solax.batteryDeviceKwh])
        assertEquals(5.0, SolaxRepository.plant(loc.id)!!.kwp)
        assertEquals(10.0, SolaxRepository.plant(loc.id)!!.batteryKwh)
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
        assertContains(status(loc), "5,0 kWp")
        assertContains(status(loc), "10,0 kWh")
        assertFalse(status(loc).contains("заряд"))
        assertFalse(status(loc).contains("very-secret"))
        // the bearer token is sent exactly as SolaX documents it
        assertEquals(
            "bearer tok-1",
            seen.last {
                it.url.encodedPath.endsWith("get_stat_data")
            }.headers[HttpHeaders.Authorization],
        )
        assertTrue(paths(seen, "/realtime_data").isEmpty(), "the 3-hourly production sync must not read the battery")
        assertEquals(
            0L,
            row(loc)[Solax.dueAt],
            "no back-off after a success; the next fetch is due when a new day is final",
        )
    }

    @Test
    fun severalPlantsAreOfferedAndTheFirstOneIsSelected() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()

        svc.sync(loc, noon)

        val info = SolaxRepository.infos(listOf(loc.id)).getValue(loc.id)
        assertEquals(listOf("P1", "P2"), info.plants.map { it.id })
        assertEquals(listOf("Home", "Garage"), info.plants.map { it.name })
        assertEquals("P1", info.plantId, "the first plant is the default")
        assertEquals(5.0, SolaxRepository.plant(loc.id)!!.kwp)
        assertTrue(SolaxReadings.produced(loc.id).isNotEmpty())
        assertTrue(status(loc).startsWith("Свързано"), status(loc))
    }

    @Test
    fun choosingAnotherPlantReadsThatPlantsData() = runBlocking {
        val loc = place()
        val (svc, seen) = twoPlantService()
        svc.sync(loc, noon)

        assertTrue(SolaxConnection.configure(loc.id, "client-1", "", "P2"), "a changed selection needs a sync")
        assertEquals(null, SolaxRepository.plant(loc.id)!!.kwp, "the first plant's data is forgotten")
        svc.sync(loc, noon)

        assertEquals("P2", SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plantId)
        assertEquals(3.0, SolaxRepository.plant(loc.id)!!.kwp)
        assertEquals(null, SolaxRepository.plant(loc.id)!!.batteryKwh) // 0 kWh = no battery
        assertContains(status(loc), "Garage")
        svc.readBatteryIfDue(loc, Instant.parse("2026-10-16T19:40:00Z"))
        assertTrue(paths(seen, "/realtime_data").isEmpty(), "the chosen plant has no battery, so no charge request")
        assertEquals(2, SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plants.size, "the list is kept")

        assertFalse(
            SolaxConnection.configure(loc.id, "client-1", "", "P2"),
            "saving the same selection changes nothing",
        )
    }

    @Test
    fun aPlantThatIsNotInTheListIsRejected() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()
        svc.sync(loc, noon)

        assertFailsWith<HttpError> { SolaxConnection.configure(loc.id, "client-1", "", "P99") }
        assertEquals("P1", SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plantId)
    }

    @Test
    fun newCredentialsKeepTheSelectedPlantWhenTheNewAccountStillHasIt() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()
        svc.sync(loc, noon)
        SolaxConnection.configure(loc.id, "client-1", "", "P2")
        svc.sync(loc, noon)

        assertTrue(SolaxConnection.configure(loc.id, "client-2", "another-secret", ""))
        val during = SolaxRepository.infos(listOf(loc.id)).getValue(loc.id)
        assertEquals("P2", during.plantId, "the selection is kept until the new account has been read")
        assertTrue(during.plants.isEmpty(), "the old account's list is gone until it is read again")
        svc.sync(loc, noon)

        assertEquals("P2", SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plantId)
        assertEquals(2, SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plants.size)
    }

    @Test
    fun newCredentialsForADifferentAccountStartOverWithItsFirstPlantAndDropTheOldData() = runBlocking {
        val loc = place()
        val (first, _) = twoPlantService()
        first.sync(loc, noon)
        assertTrue(SolaxReadings.produced(loc.id).isNotEmpty())
        transaction {
            BatteryDays.insert {
                it[locationId] = loc.id
                it[day] = today.minusDays(2).toString()
                it[maxSoc] = 100.0
            }
        }

        assertTrue(SolaxConnection.configure(loc.id, "client-2", "other-account", ""))
        val (second, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") ->
                    """{"code":10000,"result":{"records":[{"plantId":"X9","plantName":"Other","pvCapacity":2.0,"batteryCapacity":0.0}]}}"""
                else -> other(req)
            }
        }
        second.sync(loc, noon)

        assertEquals("X9", SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plantId)
        assertEquals(2.0, SolaxRepository.plant(loc.id)!!.kwp)
        assertTrue(SolaxReadings.batteryDays(loc.id).isEmpty(), "the old plant's battery history is dropped")
        assertTrue(
            SolaxReadings.produced(loc.id).keys.all {
                it >= today.minusDays(14)
            },
            "only the new plant's production is stored",
        )
        assertEquals(today.minusDays(1).toString(), row(loc)[Solax.producedThrough], "and it was fetched afresh")
    }

    @Test
    fun aSelectedPlantThatDisappearedGivesAClearStatusAndKeepsTheList() = runBlocking {
        val loc = place()
        val (svc, _) = twoPlantService()
        svc.sync(loc, noon)
        transaction {
            Solax.update({ Solax.locationId eq loc.id }) {
                it[Solax.plantId] = "GONE"
                it[Solax.plantReadAt] = null
            }
        }

        svc.sync(loc, noon)

        assertContains(status(loc), "GONE")
        assertEquals(2, SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plants.size, "the dropdown still works")
    }

    @Test
    fun theAccountsPlantsAreReadPageByPage() = runBlocking {
        val loc = place()
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") ->
                    if (req.url.parameters["pageNo"] == "2") {
                        """{"code":10000,"result":{"pages":2,"records":[{"plantId":"P11","plantName":"Eleventh"}]}}"""
                    } else {
                        """{"code":10000,"result":{"pages":2,"records":[{"plantId":"P1","plantName":"First","pvCapacity":5.0,"batteryCapacity":10.0}]}}"""
                    }
                else -> other(req)
            }
        }

        svc.sync(loc, noon)

        assertEquals(listOf("P1", "P11"), SolaxRepository.infos(listOf(loc.id)).getValue(loc.id).plants.map { it.id })
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
        transaction {
            Solax.update({ Solax.locationId eq loc.id }) {
                it[Solax.token] = "stale"
                it[Solax.tokenExpires] = System.currentTimeMillis() + 100_000
            }
        }
        val (svc, seen) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.headers[HttpHeaders.Authorization] == "bearer stale" ->
                    """{"code":10402,"message":"token invalid"}"""
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
        assertEquals(
            noon.toEpochMilli() + 12 * 3_600_000L,
            row(loc)[Solax.dueAt],
            "wrong credentials do not fix themselves: wait 12 hours",
        )

        val (exhausted, _) = service { """{"code":10405,"message":"quota"}""" }
        exhausted.sync(loc, noon)
        assertContains(status(loc), "лимит", ignoreCase = true)
        assertEquals(
            noon.toEpochMilli() + 6 * 3_600_000L,
            row(loc)[Solax.dueAt],
            "an exhausted allowance: wait 6 hours",
        )

        val (rate, _) = service { """{"code":10406,"message":"too fast"}""" }
        rate.sync(loc, noon)
        assertEquals(noon.toEpochMilli() + 3_600_000L, row(loc)[Solax.dueAt], "a rate limit passes soon: wait an hour")
    }

    @Test
    fun aDayAboveWhatThePanelsCanMakeIsBadCloudDataAndIsNotUsed() = runBlocking {
        val loc = place()
        val d = today.minusDays(1)
        val (svc, _) = service { req ->
            when {
                req.url.encodedPath.endsWith("/oauth/token") -> token
                req.url.encodedPath.endsWith("/page_plant_info") -> onePlant
                req.url.encodedPath.endsWith("/get_stat_data") ->
                    """{"code":10000,"result":{"plantEnergyStatDataList":[{"date":"$d","pvGeneration":4383.2,"inverterACOutputEnergy":13.4}]}}"""
                else -> other(req)
            }
        }

        svc.sync(loc, noon)

        assertFalse(
            SolaxReadings.produced(loc.id).containsKey(d),
            "5 kWp cannot make 4383 kWh: the estimate is used instead",
        )
        assertEquals(4383.2, SolaxReadings.badProduced(loc.id).getValue(d))
        assertEquals(13.4, SolaxReadings.producedAc(loc.id).getValue(d), "the other figures of that day are kept")
    }

    @Test
    fun disconnectingRemovesTheCredentialsButKeepsTheHistory() {
        val loc = place()
        transaction {
            Produced.insert {
                it[locationId] = loc.id
                it[day] = today.toString()
                it[kwh] = 7.0
            }
        }
        val (svc, _) = service { "" }

        assertEquals(false, SolaxConnection.configure(loc.id, "", "", ""))

        assertEquals(0L, transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.count() })
        assertEquals(7.0, SolaxReadings.produced(loc.id).getValue(today))
    }

    @Test
    fun theSecretIsNeverSentBackToTheBrowser() {
        initDb(tempDbPath())
        testApplication {
            application { module() }
            val c = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
            c.post("/api/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@example.com","password":"password1"}""")
            }
            val uid = transaction { Users.selectAll().single()[Users.id] }
            val locId = transaction {
                Locations.insert {
                    it[userId] = uid
                    it[name] = "Sofia"
                    it[lat] = 42.7
                    it[lon] = 23.3
                    it[tz] = "UTC"
                }[Locations.id]
            }

            // solar = false so saving does not call the real SolaX API
            val res = c.put("/api/locations/$locId") {
                contentType(ContentType.Application.Json)
                setBody("""{"solar":false,"solaxClientId":"client-1","solaxClientSecret":"very-secret"}""")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertFalse(res.bodyAsText().contains("very-secret"))

            val list = c.get("/api/locations").bodyAsText()
            assertFalse(list.contains("very-secret"))
            val first = Json.parseToJsonElement(list).let {
                (it as kotlinx.serialization.json.JsonArray).single().jsonObject
            }
            assertEquals("client-1", first["solaxClientId"]!!.jsonPrimitive.content)
            assertEquals("true", first["solaxHasSecret"]!!.jsonPrimitive.content)
        }
    }
}
