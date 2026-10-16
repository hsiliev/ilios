package ilios

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs the real app in-process against a throwaway H2 file: no port, no network, nothing of the real data. */
class AppTest {
    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) {
        initDb(Files.createTempDirectory("ilios-test").resolve("db").toString())
        testApplication {
            application { module() }
            block()
        }
    }

    private fun ApplicationTestBuilder.client() = createClient { install(HttpCookies) }

    private suspend fun HttpClient.json(path: String, body: String) =
        post("/api$path") { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.register(email: String) = json("/register", """{"email":"$email","password":"password1"}""")

    private fun userId(email: String) = transaction { Users.selectAll().where { Users.email eq email }.single()[Users.id] }

    /** Inserts a place with fresh weather rows directly, so nothing calls Open-Meteo. */
    private fun place(owner: Long, name: String): Long {
        val id = transaction {
            Locations.insert {
                it[userId] = owner; it[Locations.name] = name; it[lat] = 42.7; it[lon] = 23.3; it[tz] = "UTC"
            }[Locations.id]
        }
        val today = LocalDate.now(java.time.ZoneId.of("UTC"))
        transaction {
            for (offset in -14L..13L) Daily.insert {
                it[locationId] = id; it[day] = today.plusDays(offset).toString()
                it[tMax] = 20.0; it[tMin] = 10.0; it[rainMm] = 1.0; it[radiationMj] = 10.0; it[fetchedAt] = System.currentTimeMillis()
            }
            for (t in listOf("00:00", "00:15", "00:30")) Intraday15.insert {
                it[locationId] = id; it[day] = today.toString(); it[Intraday15.t] = t; it[temp] = 12.0; it[rainMm] = 0.0
            }
        }
        return id
    }

    private fun rows(placeId: Long) = transaction {
        Daily.selectAll().where { Daily.locationId eq placeId }.count() to
            Intraday15.selectAll().where { Intraday15.locationId eq placeId }.count()
    }

    @Test
    fun registerLoginAndWrongPassword() = app {
        val c = client()
        assertEquals(HttpStatusCode.Created, c.register("a@example.com").status)
        assertEquals(HttpStatusCode.OK, c.get("/api/me").status)
        assertEquals(HttpStatusCode.BadRequest, c.register("a@example.com").status) // duplicate
        val other = client()
        assertEquals(HttpStatusCode.Unauthorized, other.json("/login", """{"email":"a@example.com","password":"nope"}""").status)
        assertEquals(HttpStatusCode.OK, other.json("/login", """{"email":"a@example.com","password":"password1"}""").status)
    }

    @Test
    fun deletingAPlaceRemovesItsWeatherData() = app {
        val c = client()
        c.register("a@example.com")
        val uid = userId("a@example.com")
        val doomed = place(uid, "Doomed")
        val kept = place(uid, "Kept")
        assertEquals(28L to 3L, rows(doomed))

        assertEquals(HttpStatusCode.OK, c.delete("/api/locations/$doomed").status)

        assertEquals(0L to 0L, rows(doomed))
        assertEquals(28L to 3L, rows(kept)) // other places untouched
    }

    @Test
    fun cannotDeleteSomeoneElsesPlace() = app {
        val owner = client().also { it.register("owner@example.com") }
        val intruder = client().also { it.register("intruder@example.com") }
        val id = place(userId("owner@example.com"), "Mine")

        intruder.delete("/api/locations/$id")

        assertEquals(28L to 3L, rows(id))
        assertNotNull(transaction { Locations.selectAll().where { Locations.id eq id }.singleOrNull() })
        assertEquals(HttpStatusCode.OK, owner.get("/api/report").status)
    }

    @Test
    fun reportIsServedFromTheDatabase() = app {
        val c = client()
        c.register("a@example.com")
        place(userId("a@example.com"), "Sofia")

        val report = Json.parseToJsonElement(c.get("/api/report").bodyAsText()).jsonArray.single().jsonObject
        assertEquals(21, report["days"]!!.jsonArray.size)
        assertEquals(3, report["intraday"]!!.jsonObject["points"]!!.jsonArray.size)
    }
}
