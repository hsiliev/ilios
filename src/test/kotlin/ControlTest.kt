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

/** Inverter control (time-of-use charging rules) against a fake SolaX server whose answers copy the real API's shapes. */
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

    private fun added(id: String) = """{"code":10000,"result":{"optDeviceList":[{"deviceSn":"SN1","message":null,"ruleId":$id,"status":1}]},"requestId":"1"}"""

    @Test fun `a same-day window becomes one full-day rule with a capped charge segment`() = runBlocking<Unit> {
        val loc = place(maxChargeW = 2000)
        val (svc, seen) = service { if ("oauth/token" in it.url.toString()) token else added("2112806688194363393") }
        val made = svc.programCharge(loc, at("2026-12-01", "01:10"), at("2026-12-01", "06:00"), 80)
        assertEquals(listOf("SN1/2112806688194363393"), made) // the long id keeps every digit

        val body = bodyOf(seen.single { path(it).endsWith("add_tou_set") })
        assertEquals("12-01", body["startDate"]!!.jsonPrimitive.content); assertEquals("12-01", body["endDate"]!!.jsonPrimitive.content)
        assertEquals("SN1", body["snList"]!!.jsonArray.single().jsonPrimitive.content)
        val segs = body["runModeInfoList"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf(1, 4, 1), segs.map { it["runMode"]!!.jsonPrimitive.int })
        assertEquals(listOf("00:00" to "01:10", "01:10" to "06:00", "06:00" to "23:59"), segs.map { it["startTime"]!!.jsonPrimitive.content to it["endTime"]!!.jsonPrimitive.content })
        val charge = segs[1]
        assertEquals(2000, charge["chargePower"]!!.jsonPrimitive.int); assertEquals(80, charge["targetSoc"]!!.jsonPrimitive.int)
        assertEquals(1, charge["chargeGridEnableFlag"]!!.jsonPrimitive.int)
        assertEquals(10, segs[0]["minSoc"]!!.jsonPrimitive.int); assertEquals(10, segs[2]["minSoc"]!!.jsonPrimitive.int) // self-use segments carry the reserve
        assertEquals("bearer tok-1", seen.first { path(it).endsWith("add_tou_set") }.headers["Authorization"])
    }

    @Test fun `a window across midnight is split into one rule per date`() = runBlocking<Unit> {
        val loc = place(maxChargeW = 1500)
        var n = 0
        val (svc, seen) = service { if ("oauth/token" in it.url.toString()) token else added("${++n}00") }
        val made = svc.programCharge(loc, at("2026-12-01", "22:30"), at("2026-12-02", "06:00"), 70)
        assertEquals(listOf("SN1/100", "SN1/200"), made)

        val rules = seen.filter { path(it).endsWith("add_tou_set") }.map(::bodyOf)
        assertEquals(listOf("12-01", "12-02"), rules.map { it["startDate"]!!.jsonPrimitive.content })
        fun times(b: JsonObject) = b["runModeInfoList"]!!.jsonArray.map { (it as JsonObject)["startTime"]!!.jsonPrimitive.content to it["endTime"]!!.jsonPrimitive.content }
        assertEquals(listOf("00:00" to "22:30", "22:30" to "23:59"), times(rules[0])) // the first day's charge runs to the end of the day
        assertEquals(listOf("00:00" to "06:00", "06:00" to "23:59"), times(rules[1])) // the next day's starts at midnight
        assertEquals(listOf(4, 1), rules[1]["runModeInfoList"]!!.jsonArray.map { (it as JsonObject)["runMode"]!!.jsonPrimitive.int })
    }

    @Test fun `a failure on the second date removes the first rule again`() = runBlocking<Unit> {
        val loc = place()
        var adds = 0
        val (svc, seen) = service {
            when {
                "oauth/token" in it.url.toString() -> token
                it.url.encodedPath.endsWith("add_tou_set") -> if (++adds == 1) added("111") else """{"code":10200,"message":"FIELD_ERROR: nope"}"""
                else -> """{"code":10000,"result":null}"""
            }
        }
        assertFailsWith<SolaxError> { svc.programCharge(loc, at("2026-12-01", "22:30"), at("2026-12-02", "06:00"), 70) }
        val del = bodyOf(seen.single { path(it).endsWith("delete_tou_set") })
        assertEquals("SN1", del["deviceSn"]!!.jsonPrimitive.content); assertEquals("111", del["ruleId"]!!.jsonPrimitive.content)
    }

    @Test fun `a rejected command is reported and a missing rule id counts as a failure`() = runBlocking<Unit> {
        val loc = place()
        val (denied, _) = service { if ("oauth/token" in it.url.toString()) token else """{"code":10403,"message":"no"}""" }
        assertFailsWith<SolaxError> { denied.programCharge(loc, at("2026-12-01", "01:00"), at("2026-12-01", "06:00"), 80) }
        val (empty, _) = service { if ("oauth/token" in it.url.toString()) token else """{"code":10000,"result":{"optDeviceList":[]}}""" }
        assertFailsWith<SolaxError> { empty.programCharge(loc, at("2026-12-01", "01:00"), at("2026-12-01", "06:00"), 80) }
    }

    @Test fun `removing rules deletes each one by serial and id`() = runBlocking<Unit> {
        val loc = place()
        val (svc, seen) = service { if ("oauth/token" in it.url.toString()) token else """{"code":10000,"result":null}""" }
        svc.removeChargeRules(loc, listOf("SN1/100", "SN1/200"))
        val ids = seen.filter { path(it).endsWith("delete_tou_set") }.map { bodyOf(it)["ruleId"]!!.jsonPrimitive.content }
        assertEquals(listOf("100", "200"), ids)
    }
}
