package ilios.solax

import ilios.db.Daily
import ilios.db.Loc
import ilios.db.Locations
import ilios.db.Solax
import ilios.db.Users
import ilios.db.initDb
import ilios.db.locOf
import ilios.module
import ilios.solax.api.OK
import ilios.tempDbPath
import ilios.weather.Day
import ilios.web.userId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue

/**
 * The fake SolaX server and helpers shared by the SolaX sync tests (Ktor mock engine: no network, no real
 * credentials).
 */
@Suppress("ClassOrdering") // each canned reply sits next to the helper that uses it
abstract class SolaxFixture {
    protected val today = LocalDate.now(ZoneId.of("UTC"))

    // fixed time of day, so tests do not depend on when they run
    protected val noon = today.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC)

    protected fun service(handler: (HttpRequestData) -> String): Pair<SolaxService, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            respond(
                handler(req),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return SolaxService(client, "https://solax.test") to seen
    }

    /** A solar place with SolaX credentials saved but not yet synced. */
    protected fun place(tz: String = "UTC"): Loc {
        initDb(tempDbPath())
        val locId = transaction {
            val user = Users.insert {
                it[email] = "a@example.com"
                it[passHash] = "x"
            }[Users.id]
            Locations.insert {
                it[userId] = user
                it[name] = "Sofia"
                it[lat] = 42.7
                it[lon] = 23.3
                it[Locations.tz] = tz
                it[solar] = true
            }[Locations.id]
        }
        val svc = service { "" }.first
        assertTrue(SolaxConnection.configure(locId, "client-1", "very-secret", ""))
        return locOf(transaction { Locations.selectAll().where { Locations.id eq locId }.single() })
    }

    protected fun status(loc: Loc) = transaction {
        Solax.selectAll().where { Solax.locationId eq loc.id }.single()[Solax.status]
    }
    protected fun row(loc: Loc) = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.single() }

    protected val token = """{"code":0,"result":{"access_token":"tok-1","expires_in":2591999}}"""
    protected val onePlant =
        """{"code":10000,"result":{"records":[{"plantId":"P1","plantName":"Home","pvCapacity":5.0,"batteryCapacity":10.0}]}}"""

    /** Battery history served by the fake server: (moment, charge of battery B1, charge of battery B2). */
    protected var socPoints = listOf<Triple<Instant, Double, Double>>()
    protected var historyBroken = false
    protected val utcStamp = java.time.format.DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss",
    ).withZone(ZoneId.of("UTC"))

    protected fun historyReply(req: HttpRequestData): String {
        if (historyBroken) return """{"code":10200,"message":"boom"}"""
        val from = Instant.ofEpochMilli(req.url.parameters["startTime"]!!.toLong())
        val to = Instant.ofEpochMilli(req.url.parameters["endTime"]!!.toLong())
        val points = socPoints.filter { it.first >= from && it.first < to }.flatMap { (moment, a, b) ->
            listOf("B1" to a, "B2" to b).map { (sn, v) ->
                """{"deviceSn":"$sn","plantLocalTime":"${utcStamp.format(
                    moment,
                )}","batterySOC":$v,"batteryRemainings":${v / 100 * usablePerBattery}}"""
            }
        }
        return """{"code":10000,"result":[${points.joinToString(",")}]}"""
    }

    /** Each of the two fake batteries stores 5 kWh at 100 %, so the system's rated capacity is 10 kWh. */
    protected val usablePerBattery = 5.0
    protected var batteryDevicesReply =
        """{"code":10000,"result":{"records":[{"deviceSn":"B1","ratedCapacity":"5000"},{"deviceSn":"B2","ratedCapacity":"5000"}]}}"""
    protected val devices = """{"code":10000,"result":{"records":[{"deviceSn":"INV1"}]}}"""
    protected val batteries = """
        {"code":10000,"result":[
        {"deviceSn":"B1","batterySOC":70.0,"batteryRemainings":7.0,"chargeDischargePower":1500.0},
        {"deviceSn":"B2","batterySOC":80.0,"batteryRemainings":8.0,"chargeDischargePower":500.0}]}
    """.trimIndent()

    /** Everything after authentication and the plant lookup: devices, battery data, statistics. */
    protected fun other(req: HttpRequestData) = when {
        req.url.encodedPath.endsWith("/page_device_info") ->
            if (req.url.parameters["deviceType"] == "2") {
                if (req.url.parameters["plantId"] == "P2") {
                    """{"code":10000,"result":{"records":[]}}"""
                } else {
                    batteryDevicesReply
                }
            } else {
                devices
            }
        req.url.encodedPath.endsWith("/realtime_data") -> batteries
        req.url.encodedPath.endsWith("/history_data") -> historyReply(req)
        else -> stats(req)
    }

    protected fun paths(seen: List<HttpRequestData>, suffix: String) = seen.filter {
        it.url.encodedPath.endsWith(suffix)
    }

    /** Statistics for the requested month: 3.5 kWh on each of the last 20 days of it. */
    protected fun stats(req: HttpRequestData): String {
        val month = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["date"]!!.jsonPrimitive.content
        val rows = (0L..20L).map { today.minusDays(it) }.filter { it.toString().startsWith(month) }
            .joinToString(",") { """{"date":"$it","pvGeneration":3.5,"inverterACOutputEnergy":3.0}""" }
        return """{"code":10000,"result":{"plantEnergyStatDataList":[$rows]}}"""
    }

    protected val twoPlants = """
        {"code":10000,"result":{"records":[
        {"plantId":"P1","plantName":"Home","pvCapacity":5.0,"batteryCapacity":10.0},
        {"plantId":"P2","plantName":"Garage","pvCapacity":3.0,"batteryCapacity":0.0}]}}
    """.trimIndent()

    protected fun twoPlantService() = service { req ->
        when {
            req.url.encodedPath.endsWith("/oauth/token") -> token
            req.url.encodedPath.endsWith("/page_plant_info") -> twoPlants
            else -> other(req)
        }
    }

    protected fun connected(tz: String = "UTC"): Triple<Loc, SolaxService, MutableList<HttpRequestData>> {
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

    @Suppress("FunctionNameMinLength")
    protected fun at(utc: String) = Instant.parse(utc)

    protected fun statCalls(seen: List<HttpRequestData>) = paths(seen, "/get_stat_data").size

    protected fun historyCalls(seen: List<HttpRequestData>) = paths(seen, "/history_data")

    @Suppress("FunctionNameMinLength")
    protected fun at(day: LocalDate, time: String) = Instant.parse("${day}T$time:00Z")
    protected fun daysToFetch(): Int { // from last week's Monday to yesterday, as the app counts them
        val monday = today.with(
            java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY),
        ).minusDays(7)
        return (today.toEpochDay() - 1 - monday.toEpochDay() + 1).toInt()
    }

    protected fun batteryDeviceCalls(seen: List<HttpRequestData>) = paths(seen, "/page_device_info").filter {
        it.url.parameters["deviceType"] ==
            "2"
    }

    protected fun report(c: HttpClient) = runBlocking {
        Json.parseToJsonElement(c.get("/api/report").bodyAsText()).let {
            (it as kotlinx.serialization.json.JsonArray).single().jsonObject
        }
    }

    /** A signed-in user with one place that already has fresh weather rows, so the report needs no network. */
    protected fun withPlace(solar: Boolean, block: suspend (HttpClient, Long) -> Unit) {
        initDb(tempDbPath())
        testApplication {
            application { module() }
            val c = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
            c.post("/api/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@example.com","password":"password1"}""")
            }
            val uid = transaction { Users.selectAll().single()[Users.id] }
            val id = transaction {
                Locations.insert {
                    it[userId] = uid
                    it[name] = "Sofia"
                    it[lat] = 42.7
                    it[lon] = 23.3
                    it[tz] = "UTC"
                    it[Locations.solar] = solar
                }[Locations.id]
            }
            transaction {
                for (offset in -14L..13L) {
                    Daily.insert {
                        it[locationId] = id
                        it[day] = today.plusDays(offset).toString()
                        it[tMax] = 20.0
                        it[tMin] = 10.0
                        it[rainMm] = 0.0
                        it[radiationMj] = 18.0
                        it[fetchedAt] = System.currentTimeMillis()
                    }
                }
            }
            block(c, id)
        }
    }

    protected fun day(offset: Long, radiationMj: Double = 18.0) =
        Day(today.plusDays(offset), 20.0, 10.0, 0.0, radiationMj)
}
