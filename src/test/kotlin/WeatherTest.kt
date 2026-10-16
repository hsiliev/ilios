package ilios

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open-Meteo calls against a fake server: what is asked for, and what is served from the database instead. */
class WeatherTest {
    private val today = LocalDate.now(ZoneId.of("UTC"))

    private fun service(handler: (HttpRequestData) -> String): Pair<WeatherService, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            respond(handler(req), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return WeatherService(client, "https://forecast.test", "https://geo.test") to seen
    }

    /** Daily values for the requested days, or a few 15-minute steps when `minutely_15` is asked for. */
    private fun forecast(req: HttpRequestData): String {
        val p = req.url.parameters
        if (p["minutely_15"] != null) {
            return """{"timezone":"UTC","minutely_15":{"time":["${today}T00:00","${today}T00:15"],"temperature_2m":[10.0,10.5],
                "precipitation":[0.0,0.0],"weather_code":[0,0],"cloud_cover":[10,10],"wind_speed_10m":[5,5],"wind_gusts_10m":[8,8]}}"""
        }
        val past = p["past_days"]?.toLong() ?: 0L
        val days = (-past until p["forecast_days"]!!.toLong()).map { today.plusDays(it) }
        fun list(v: String) = days.joinToString(",") { v }
        return """{"timezone":"UTC","daily":{"time":[${days.joinToString(",") { "\"$it\"" }}],
            "temperature_2m_max":[${list("20.0")}],"temperature_2m_min":[${list("10.0")}],
            "precipitation_sum":[${list("1.0")}],"shortwave_radiation_sum":[${list("12.0")}]}}"""
    }

    private fun place(): Loc {
        initDb(tempDbPath())
        val id = transaction {
            val user = Users.insert { it[email] = "a@example.com"; it[passHash] = "x" }[Users.id]
            Locations.insert { it[userId] = user; it[name] = "Sofia"; it[lat] = 42.7; it[lon] = 23.3; it[tz] = "UTC" }[Locations.id]
        }
        return locOf(transaction { Locations.selectAll().where { Locations.id eq id }.single() })
    }

    private fun daily(seen: List<HttpRequestData>) = seen.filter { it.url.parameters["minutely_15"] == null }

    @Test
    fun pastDaysAreRequestedOnlyWhenTheyAreMissing() = runBlocking {
        val loc = place()
        val (svc, seen) = service(::forecast)

        svc.refresh(loc)
        val first = daily(seen).single().url.parameters["past_days"]?.toInt()
        assertTrue(first != null && first in 7..14, "a new place needs last week's days, asked for $first")
        val stored = svc.days(loc).map { it.date }
        assertTrue(today.minusDays(7) in stored || today.minusDays(first!!.toLong()) in stored)
        assertTrue(today.plusDays(13) in stored)

        svc.refresh(loc)
        assertNull(daily(seen)[1].url.parameters["past_days"], "everything past is stored already, so only today and the future are asked for")

        // a gap appears (say, the app was off): exactly the missing days are asked for again
        transaction { Daily.deleteWhere { (Daily.locationId eq loc.id) and (Daily.day eq today.minusDays(1).toString()) } }
        svc.refresh(loc)
        assertEquals("1", daily(seen)[2].url.parameters["past_days"])
        assertTrue(today.minusDays(1) in svc.days(loc).map { it.date })
    }

    @Test
    fun storedPastDaysAreNeverReplacedByLaterRefreshes() = runBlocking {
        val loc = place()
        val (svc, _) = service(::forecast)
        svc.refresh(loc)
        val old = today.minusDays(2)
        transaction { Daily.update({ (Daily.locationId eq loc.id) and (Daily.day eq old.toString()) }) { it[Daily.tMax] = 99.0 } }

        svc.refresh(loc)

        assertEquals(99.0, svc.days(loc).single { it.date == old }.tMax)
    }

    private val hits = """{"results":[{"name":"София","latitude":42.7,"longitude":23.3,"country":"България","admin1":"София-град","timezone":"Europe/Sofia"}]}"""

    @Test
    fun aRepeatedPlaceSearchIsAnsweredFromTheDatabase() = runBlocking {
        place()
        val (svc, seen) = service { hits }
        val now = Instant.parse("2026-10-16T10:00:00Z")

        assertEquals("София", svc.geocode("София", now).single().name)
        assertEquals("София", svc.geocode("  СОФИЯ ", now.plusSeconds(3600)).single().name)
        assertEquals(1, seen.size, "the second search (same words, other spacing and case) needed no request")
    }

    @Test
    fun aCachedSearchExpiresAfterAMonthAndEmptyAnswersAreCachedToo() = runBlocking {
        place()
        var reply = """{"results":[]}"""
        val (svc, seen) = service { reply }
        val now = Instant.parse("2026-10-16T10:00:00Z")

        assertTrue(svc.geocode("Xyzzy", now).isEmpty())
        assertTrue(svc.geocode("xyzzy", now.plusSeconds(60)).isEmpty())
        assertEquals(1, seen.size, "an empty answer is cached as well")

        reply = hits
        assertTrue(svc.geocode("xyzzy", now.plusSeconds(31L * 24 * 3600)).isNotEmpty())
        assertEquals(2, seen.size, "after a month the search is made again")
    }
}
