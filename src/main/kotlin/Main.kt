package ilios

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.http.content.staticResources
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import java.time.LocalDate
import java.time.ZoneId

private val json = Json { ignoreUnknownKeys = true }

private val appModule = module {
    single { json }
    single { newHttpClient(get()) }
    single { AuthService() }
    single { Mailer() }
    single { WeatherService(get()) }
}

class HttpError(val status: HttpStatusCode, message: String) : Exception(message)

@Serializable class Credentials(val email: String = "", val password: String = "")
@Serializable class EmailReq(val email: String = "")
@Serializable class ResetReq(val token: String = "", val password: String = "")
@Serializable class Me(val email: String)
@Serializable class NewLocation(val name: String = "", val country: String? = null, val lat: Double = 0.0, val lon: Double = 0.0, val tz: String? = null)
@Serializable class PlaceSettings(val solar: Boolean, val batteryKwh: Double, val solarKwp: Double, val pvRatio: Double)
@Serializable class LocationOut(val id: Long, val name: String, val country: String?, val solar: Boolean, val batteryKwh: Double, val solarKwp: Double, val pvRatio: Double)
@Serializable class Msg(val message: String)

/** HTTP port from the PORT environment variable; 8080 when unset or blank. A set but invalid value fails fast. */
fun portFrom(value: String?): Int {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return 8080
    return v.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("PORT must be a number between 1 and 65535, got '$v'")
}

private val PORT = portFrom(System.getenv("PORT"))
private val BASE_URL = "http://localhost:$PORT" // used in password-reset links
private const val COOKIE = "sid"
private val emailRe = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$")

private fun badRequest(msg: String): Nothing = throw HttpError(HttpStatusCode.BadRequest, msg)

private fun checkPassword(pw: String) { if (pw.length < 8 || pw.length > 200) badRequest("Паролата трябва да е между 8 и 200 символа") }

fun main() {
    initDb("./data/ilios")
    embeddedServer(CIO, port = PORT, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(Koin) { modules(appModule) }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, Msg(e.message ?: "Грешка")) }
        exception<kotlinx.serialization.SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, Msg("Невалидна заявка")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled", e)
            call.respond(HttpStatusCode.InternalServerError, Msg("Вътрешна грешка"))
        }
    }
    val auth by inject<AuthService>()
    val weather by inject<WeatherService>()
    val mailer by inject<Mailer>()

    fun ApplicationCall.userId(): Long =
        request.cookies[COOKIE]?.let(auth::userFor) ?: throw HttpError(HttpStatusCode.Unauthorized, "Не сте влезли в профила си")

    fun ApplicationCall.startSession(token: String) = response.cookies.append(
        Cookie(COOKIE, token, httpOnly = true, path = "/", maxAge = 14 * 24 * 3600, extensions = mapOf("SameSite" to "Lax")),
    )

    launch { weather.refreshLoop() }

    routing {
        staticResources("/", "static")

        route("/api") {
            post("/register") {
                val c = call.receive<Credentials>()
                val email = c.email.trim().lowercase()
                if (!emailRe.matches(email)) badRequest("Невалиден имейл")
                checkPassword(c.password)
                auth.register(email, c.password) ?: badRequest("Този имейл вече е регистриран")
                call.startSession(auth.login(email, c.password)!!)
                call.respond(HttpStatusCode.Created, Msg("Регистрацията е успешна"))
            }
            post("/login") {
                val c = call.receive<Credentials>()
                val token = auth.login(c.email.trim().lowercase(), c.password)
                    ?: throw HttpError(HttpStatusCode.Unauthorized, "Грешен имейл или парола")
                call.startSession(token)
                call.respond(Msg("OK"))
            }
            post("/logout") {
                call.request.cookies[COOKIE]?.let(auth::logout)
                call.response.cookies.append(Cookie(COOKIE, "", path = "/", maxAge = 0))
                call.respond(Msg("OK"))
            }
            post("/forgot") {
                val email = call.receive<EmailReq>().email.trim().lowercase()
                auth.createReset(email)?.let { token ->
                    val link = "$BASE_URL/#reset=$token"
                    if (mailer.enabled) {
                        // never reveal failures to the requester (would leak whether the address exists)
                        runCatching { mailer.send(email, "Слънце – възстановяване на парола", "Задайте нова парола (връзката е валидна 1 час):\n\n$link\n\nАко не сте поискали това, игнорирайте писмото.") }
                            .onFailure { call.application.environment.log.error("Reset mail failed: ${it.message}") }
                    } else println("PASSWORD RESET for $email: $link (SMTP not configured)")
                }
                call.respond(Msg("Ако адресът е регистриран, изпратихме връзка за възстановяване на паролата."))
            }
            post("/reset") {
                val r = call.receive<ResetReq>()
                checkPassword(r.password)
                if (!auth.reset(r.token, r.password)) badRequest("Връзката за възстановяване е невалидна или е изтекла")
                call.respond(Msg("Паролата е сменена"))
            }

            get("/me") {
                val id = call.userId()
                call.respond(Me(auth.email(id)))
            }

            get("/geocode") {
                call.userId()
                val q = call.request.queryParameters["q"]?.trim().orEmpty()
                if (q.length < 2) badRequest("Въведете поне 2 символа")
                call.respond(weather.geocode(q))
            }

            get("/locations") {
                val id = call.userId()
                call.respond(transaction {
                    Locations.selectAll().where { Locations.userId eq id }.orderBy(Locations.id).map {
                        LocationOut(it[Locations.id], it[Locations.name], it[Locations.country], it[Locations.solar], it[Locations.batteryKwh], it[Locations.solarKwp], it[Locations.pvRatio])
                    }
                })
            }
            post("/locations") {
                val id = call.userId()
                val l = call.receive<NewLocation>()
                val name = l.name.trim()
                if (name.isEmpty() || name.length > 120 || l.lat !in -90.0..90.0 || l.lon !in -180.0..180.0) badRequest("Невалидно място")
                val tz = l.tz?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: "UTC"
                val n = transaction { Locations.selectAll().where { Locations.userId eq id }.count() }
                if (n >= 20) badRequest("Най-много 20 места")
                val newId = transaction {
                    Locations.insert {
                        it[userId] = id; it[Locations.name] = name; it[country] = l.country?.take(80)
                        it[lat] = l.lat; it[lon] = l.lon; it[Locations.tz] = tz
                    }[Locations.id]
                }
                // fill the database right away; if Open-Meteo is unreachable the background loop retries
                runCatching { weather.refresh(Loc(newId, name, l.lat, l.lon, tz, false, 10.0, 5.0, 0.75)) }
                    .onFailure { call.application.environment.log.warn("Initial fetch failed for $name: ${it.message}") }
                call.respond(HttpStatusCode.Created, LocationOut(newId, name, l.country, false, 10.0, 5.0, 0.75))
            }
            put("/locations/{id}") {
                val uid = call.userId()
                val lid = call.parameters["id"]?.toLongOrNull() ?: badRequest("Невалиден идентификатор")
                val p = call.receive<PlaceSettings>()
                if (p.batteryKwh !in 0.0..1000.0 || p.solarKwp !in 0.0..1000.0 || p.pvRatio !in 0.1..1.0) badRequest("Стойността е извън допустимите граници")
                transaction {
                    Locations.update({ (Locations.id eq lid) and (Locations.userId eq uid) }) {
                        it[solar] = p.solar; it[batteryKwh] = p.batteryKwh; it[solarKwp] = p.solarKwp; it[pvRatio] = p.pvRatio
                    }
                }
                call.respond(Msg("Запазено"))
            }
            delete("/locations/{id}") {
                val uid = call.userId()
                val lid = call.parameters["id"]?.toLongOrNull() ?: badRequest("Невалиден идентификатор")
                transaction { Locations.deleteWhere { (Locations.id eq lid) and (userId eq uid) } }
                call.respond(Msg("Изтрито"))
            }

            get("/report") {
                val uid = call.userId()
                val locs = transaction { Locations.selectAll().where { Locations.userId eq uid }.orderBy(Locations.id).map(::locOf) }
                val reports = locs.map { loc ->
                    val days = weather.days(loc)
                    if (days.isEmpty()) {
                        Report(loc.id, loc.name, error = "Данните за времето се зареждат – опитайте отново след малко")
                    } else {
                        val today = LocalDate.now(ZoneId.of(loc.tz))
                        buildReport(loc, days, today, weather.intraday(loc, today))
                    }
                }
                call.respond(reports)
            }
        }
    }
}
