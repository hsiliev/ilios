package ilios

import ilios.auth.AuthService
import ilios.auth.Mailer
import ilios.db.initDb
import ilios.report.NightPlanner
import ilios.solax.SolaxService
import ilios.weather.WeatherService
import ilios.web.Msg
import ilios.web.authRoutes
import ilios.web.locationRoutes
import ilios.web.reportRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin

private val json = Json { ignoreUnknownKeys = true }

private val appModule = module {
    single { json }
    single { newHttpClient(get()) }
    single { AuthService() }
    single { Mailer() }
    single { WeatherService(get()) }
    single { SolaxService(get()) }
    single { NightPlanner(get(), get()) }
}

fun main() {
    initDb("./data/ilios")
    embeddedServer(CIO, port = PORT, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(Koin) { modules(appModule) }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, Msg(e.message ?: "Грешка")) }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, Msg("Невалидна заявка"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled", e)
            call.respond(HttpStatusCode.InternalServerError, Msg("Вътрешна грешка"))
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.apply {
            append("X-Content-Type-Options", "nosniff")
            append("X-Frame-Options", "DENY")
            append("Referrer-Policy", "no-referrer")
            if (HTTPS) append("Strict-Transport-Security", "max-age=31536000")
        }
    }
    val auth by inject<AuthService>()
    val weather by inject<WeatherService>()
    val solax by inject<SolaxService>()
    val mailer by inject<Mailer>()
    val planner by inject<NightPlanner>()

    launch { weather.refreshLoop() }
    launch { solax.syncLoop() }
    launch { solax.batteryLoop() }
    launch { planner.loop() }

    routing {
        staticResources("/", "static")
        route("/api") {
            authRoutes(auth, mailer)
            locationRoutes(auth, weather, solax)
            reportRoutes(auth, weather, solax)
        }
    }
}
