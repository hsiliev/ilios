package ilios

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Inverter control (remote-mode charging) against a fake SolaX server whose answers copy the real API's shapes. */
class ControlTest {
    private val zone = ZoneId.of("Europe/Sofia")
    private val token = """{"code":0,"result":{"access_token":"tok-1","expires_in":2591999}}"""

    private fun service(handler: (HttpRequestData) -> String): Pair<SolaxService, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            respond(handler(req), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return SolaxService(client, "https://solax.test") to seen
    }

    /** A connected place with management enabled, a known plant and one inverter. */
    private fun place(maxChargeW: Int = DEFAULT_MAX_CHARGE_W, reservedPct: Int = 10): Loc {
        initDb(tempDbPath())
        val id = transaction {
            val user = Users.insert { it[email] = "a@example.com"; it[passHash] = "x" }[Users.id]
            Locations.insert {
                it[userId] = user; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[tz] = zone.id; it[solar] = true
                it[allowControl] = true; it[Locations.maxChargeW] = maxChargeW; it[Locations.reservedPct] = reservedPct
            }[Locations.id]
        }
        assertTrue(service { "" }.first.configure(id, "client-1", "very-secret", ""))
        transaction { Solax.update({ Solax.locationId eq id }) { it[plantId] = "P1"; it[businessType] = 1; it[inverterSns] = "SN1" } }
        return locOf(transaction { Locations.selectAll().where { Locations.id eq id }.single() })
    }

    private fun bodyOf(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
    private fun path(r: HttpRequestData) = r.url.encodedPath
    private fun at(date: String, time: String) = ZonedDateTime.parse("${date}T$time:00", java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(zone))

    private val ok = """{"code":10000,"message":"success","result":{"SN1":{"status":3}},"requestId":"1"}"""
    private fun answer(r: HttpRequestData, reply: String = ok) = if ("oauth/token" in r.url.toString()) token else reply
    private fun commands(seen: List<HttpRequestData>) = seen.filter { "inverter_vpp_mode" in path(it) }

    @Test fun `charging is Push Power with a negative power, a timer and exit afterwards`() = runBlocking<Unit> {
        val loc = place(maxChargeW = 2000)
        val (svc, seen) = service { answer(it) }
        svc.startCharge(loc, 5400)
        val cmd = commands(seen).single()
        assertTrue(path(cmd).endsWith("/inverter_vpp_mode/push_power/positive_or_negative_mode"))
        val body = bodyOf(cmd)
        assertEquals(-2000, body["batteryPower"]!!.jsonPrimitive.int) // negative = charge
        assertEquals(5400, body["timeOfDuration"]!!.jsonPrimitive.int)
        assertEquals(160, body["nextMotion"]!!.jsonPrimitive.int) // exit remote control when the time is up
        assertEquals(1, body["businessType"]!!.jsonPrimitive.int)
        assertEquals("SN1", body["snList"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("bearer tok-1", cmd.headers["Authorization"])
    }

    @Test fun `stopping sends exit_vpp_mode and a rejected command is reported`() = runBlocking<Unit> {
        val loc = place()
        val (svc, seen) = service { answer(it) }
        svc.stopCharge(loc)
        assertTrue(path(commands(seen).single()).endsWith("/inverter_vpp_mode/exit_vpp_mode"))
        val (denied, _) = service { answer(it, """{"code":10403,"message":"no"}""") }
        assertFailsWith<SolaxError> { denied.startCharge(loc, 600) }
        assertFailsWith<SolaxError> { denied.stopCharge(loc) }
    }

    // ---- the planner's decisions (NightPlanner.controlInverter) ----

    private val now = at("2026-12-01", "01:00") // the night zone; the day zone starts at 07:00
    private fun planner(svc: SolaxService) = NightPlanner(WeatherService(HttpClient(MockEngine { respond("") })), svc)
    private fun plant(soc: Double, kwh: Double = 10.0) = SolaxPlant(kwp = 5.0, batteryKwh = kwh, soc = soc)
    private fun plan(chargeUntil: Long?) = NightPlan("2026-12-01", 0, 0, 0.0, 0.0, 0.0, 60.0, null, "07:00", 20.0, false, chargeUntil, "earlier")

    @Test fun `charging time is the missing energy over the power plus a margin`() = runBlocking<Unit> {
        val loc = place(maxChargeW = 2000)
        val (svc, seen) = service { answer(it) }
        val (until, text) = planner(svc).controlInverter(loc, null, plant(20.0), 60.0, false, now, now.toLocalDate(), 7)
        // 4 kWh at 2 kW = 2 h, +10 % for losses, +60 s
        assertEquals(7980, bodyOf(commands(seen).single())["timeOfDuration"]!!.jsonPrimitive.int)
        assertEquals(now.toInstant().toEpochMilli() + 7_980_000, until)
        assertTrue(text!!.contains("60%") && text.contains("2000 W"))
    }

    @Test fun `charging never runs past the start of the day zone`() = runBlocking<Unit> {
        val loc = place(maxChargeW = 2000)
        val (svc, seen) = service { answer(it) }
        val (until, _) = planner(svc).controlInverter(loc, null, plant(10.0, 30.0), 100.0, false, now, now.toLocalDate(), 7)
        assertEquals(6 * 3600, bodyOf(commands(seen).single())["timeOfDuration"]!!.jsonPrimitive.int)
        assertEquals(at("2026-12-01", "07:00").toInstant().toEpochMilli(), until)
    }

    @Test fun `a running charge is sent again with the time recomputed`() = runBlocking<Unit> {
        val loc = place(maxChargeW = 2000)
        val (svc, seen) = service { answer(it) }
        val running = plan(now.toInstant().toEpochMilli() + 3_600_000)
        planner(svc).controlInverter(loc, running, plant(40.0), 60.0, false, now, now.toLocalDate(), 7) // 2 kWh left: 1 h + 10 % + 1 min
        assertEquals(3960 + 60, bodyOf(commands(seen).single())["timeOfDuration"]!!.jsonPrimitive.int)
    }

    @Test fun `a charge that reached the plan is ended, and nothing is sent when none runs`() = runBlocking<Unit> {
        val loc = place()
        val (svc, seen) = service { answer(it) }
        val running = plan(now.toInstant().toEpochMilli() + 600_000)
        val (until, _) = planner(svc).controlInverter(loc, running, plant(61.0), 60.0, true, now, now.toLocalDate(), 7)
        assertEquals(null, until)
        assertTrue(path(commands(seen).single()).endsWith("exit_vpp_mode"))
        seen.clear()
        val (none, text) = planner(svc).controlInverter(loc, plan(null), plant(61.0), 60.0, true, now, now.toLocalDate(), 7)
        assertEquals(null, none); assertEquals("earlier", text)
        assertTrue(commands(seen).isEmpty())
    }

    @Test fun `switching management off ends a running charge and starts none`() = runBlocking<Unit> {
        place()
        val off = Loc(1, "Sofia", 42.7, 23.3, zone.id, true, reservedPct = 10, allowControl = false)
        val (svc, seen) = service { answer(it) }
        val running = plan(now.toInstant().toEpochMilli() + 600_000)
        assertEquals(null, planner(svc).controlInverter(off, running, plant(20.0), 60.0, false, now, now.toLocalDate(), 7).first)
        assertTrue(path(commands(seen).single()).endsWith("exit_vpp_mode"))
        seen.clear()
        planner(svc).controlInverter(off, null, plant(20.0), 60.0, false, now, now.toLocalDate(), 7)
        assertTrue(commands(seen).isEmpty())
    }

    @Test fun `no charge is needed at or below the reserve, or when almost no time is left`() = runBlocking<Unit> {
        val loc = place(reservedPct = 30)
        val (svc, seen) = service { answer(it) }
        planner(svc).controlInverter(loc, null, plant(20.0), 30.0, false, now, now.toLocalDate(), 7) // the plan's level is the reserve
        planner(svc).controlInverter(loc, null, plant(59.9), 60.0, false, now, now.toLocalDate(), 7) // 0.01 kWh: under two minutes
        assertTrue(commands(seen).isEmpty())
    }

    @Test fun `a failed command is reported in the status and keeps what was running`() = runBlocking<Unit> {
        val loc = place()
        val (svc, _) = service { answer(it, """{"code":10403,"message":"no"}""") }
        val until = now.toInstant().toEpochMilli() + 600_000
        val (kept, text) = planner(svc).controlInverter(loc, plan(until), plant(20.0), 60.0, false, now, now.toLocalDate(), 7)
        assertEquals(until, kept)
        assertTrue(!text.isNullOrBlank() && text != "earlier")
    }
}
