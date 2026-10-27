package ilios.web

import ilios.HttpError
import ilios.MILLIS
import ilios.auth.AuthService
import ilios.badRequest
import ilios.db.Loc
import ilios.db.Locations
import ilios.db.MAX_MAX_CHARGE_W
import ilios.db.MAX_RESERVE
import ilios.db.MIN_MAX_CHARGE_W
import ilios.db.locOf
import ilios.report.TARIFFS
import ilios.solax.SolaxConnection
import ilios.solax.SolaxInfo
import ilios.solax.SolaxRepository
import ilios.solax.SolaxService
import ilios.weather.WeatherService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneId

private const val MAX_LAT = 90.0
private const val MAX_LON = 180.0

private const val MAX_PLACES = 20
private const val MAX_NAME = 120
private const val MAX_COUNTRY = 80

private fun locationOut(r: ResultRow, info: SolaxInfo?) = LocationOut(
    r[Locations.id], r[Locations.name], r[Locations.country], r[Locations.solar], r[Locations.supplier],
    r[Locations.reservedPct], r[Locations.allowControl], r[Locations.maxChargeW],
    info?.clientId.orEmpty(), info?.plantId.orEmpty(), info != null, info?.status.orEmpty(),
    info?.batteryStatus.orEmpty(), info?.batteryInfo.orEmpty(), info?.plants.orEmpty(),
)

/** The signed-in user's places in their own order. */
internal fun userPlaces(userId: Long): List<ResultRow> = transaction {
    Locations.selectAll().where { Locations.userId eq userId }
        .orderBy(Locations.position to SortOrder.ASC, Locations.id to SortOrder.ASC)
        .toList()
}

private fun checkSettings(p: PlaceSettings) {
    if (p.supplier != null && p.supplier !in TARIFFS) badRequest("Непознат доставчик")
    if (p.reservedPct != null &&
        p.reservedPct !in 0..MAX_RESERVE
    ) {
        badRequest("Резервът трябва да е между 0 и $MAX_RESERVE %")
    }
    if (p.maxChargeW != null && p.maxChargeW !in MIN_MAX_CHARGE_W..MAX_MAX_CHARGE_W) {
        badRequest(
            "Мощността на зареждане трябва да е между ${MIN_MAX_CHARGE_W / MILLIS.toDouble()} " +
                "и ${MAX_MAX_CHARGE_W / MILLIS} kW",
        )
    }
}

private fun NewLocation.isValid() =
    name.isNotEmpty() && name.length <= MAX_NAME && lat in -MAX_LAT..MAX_LAT && lon in -MAX_LON..MAX_LON

/** Place search and the user's places: list, add, reorder, change settings, refresh the SolaX data, delete. */
fun Route.locationRoutes(auth: AuthService, weather: WeatherService, solax: SolaxService) {
    get("/geocode") {
        call.userId(auth)
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        if (q.length < 2) badRequest("Въведете поне 2 символа")
        call.respond(weather.geocode(q))
    }
    get("/locations") {
        val rows = userPlaces(call.userId(auth))
        val infos = SolaxRepository.infos(rows.map { it[Locations.id] })
        call.respond(rows.map { locationOut(it, infos[it[Locations.id]]) })
    }
    post("/locations") { call.addPlace(call.userId(auth), weather) }
    put("/locations/order") { call.reorderPlaces(call.userId(auth)) }
    put("/locations/{id}") { call.changePlace(call.userId(auth), solax) }
    post("/locations/{id}/refresh") { call.refreshPlace(call.userId(auth), solax) }
    delete("/locations/{id}") {
        val uid = call.userId(auth)
        val lid = call.idParameter()
        transaction { Locations.deleteWhere { Locations.id eq lid and (userId eq uid) } }
        call.respond(Msg("Изтрито"))
    }
}

private suspend fun ApplicationCall.addPlace(uid: Long, weather: WeatherService) {
    val l = receive<NewLocation>().let { NewLocation(it.name.trim(), it.country, it.lat, it.lon, it.tz) }
    if (!l.isValid()) badRequest("Невалидно място")
    if (transaction { Locations.selectAll().where { Locations.userId eq uid }.count() } >=
        MAX_PLACES
    ) {
        badRequest("Най-много $MAX_PLACES места")
    }
    val tz = l.tz?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: "UTC"
    val newId = transaction {
        val next =
            (
                Locations.selectAll().where {
                    Locations.userId eq uid
                }.maxOfOrNull { it[Locations.position] } ?: -1
                ) + 1 // after the last
        Locations.insert {
            it[userId] = uid
            it[name] = l.name
            it[country] = l.country?.take(MAX_COUNTRY)
            it[lat] = l.lat
            it[lon] = l.lon
            it[Locations.tz] = tz
            it[position] = next
        }[Locations.id]
    }
    // fill the database right away; if Open-Meteo is unreachable the background loop retries
    runCatching { weather.refresh(Loc(newId, l.name, l.lat, l.lon, tz, solar = false)) }
        .onFailure { application.environment.log.warn("Initial fetch failed for ${l.name}: ${it.message}") }
    respond(HttpStatusCode.Created, LocationOut(newId, l.name, l.country, solar = false))
}

/** The whole list of the user's place ids in the new order. */
private suspend fun ApplicationCall.reorderPlaces(uid: Long) {
    val ids = receive<PlaceOrder>().ids
    transaction {
        val own = Locations.selectAll().where { Locations.userId eq uid }.map { it[Locations.id] }
        if (ids.size != own.size || ids.toSet() != own.toSet()) badRequest("Невалиден ред на местата")
        ids.forEachIndexed { i, lid ->
            Locations.update({ Locations.id eq lid and (Locations.userId eq uid) }) {
                it[position] =
                    i
            }
        }
    }
    respond(Msg("ok"))
}

private suspend fun ApplicationCall.changePlace(uid: Long, solax: SolaxService) {
    val lid = idParameter()
    val p = receive<PlaceSettings>()
    checkSettings(p)
    val updated = transaction {
        Locations.update({ Locations.id eq lid and (Locations.userId eq uid) }) {
            it[solar] = p.solar
            if (p.supplier != null) it[supplier] = p.supplier
            if (p.reservedPct != null) it[reservedPct] = p.reservedPct
            if (p.allowControl != null) it[allowControl] = p.allowControl
            if (p.maxChargeW != null) it[maxChargeW] = p.maxChargeW
        }
    }
    if (updated == 0) throw HttpError(HttpStatusCode.NotFound, "Няма такова място")
    val changed = SolaxConnection.configure(lid, p.solaxClientId, p.solaxClientSecret, p.solaxPlantId)
    val row = transaction { Locations.selectAll().where { Locations.id eq lid }.single() }
    if (changed && p.solar) { // neither call throws; the outcomes end up in the status texts
        solax.sync(locOf(row))
        solax.readBatteryIfDue(locOf(row)) // first reading right after connecting, then hourly
    }
    respond(locationOut(row, SolaxRepository.infos(listOf(lid))[lid]))
}

private suspend fun ApplicationCall.refreshPlace(uid: Long, solax: SolaxService) {
    val lid = idParameter()
    val row =
        transaction {
            Locations.selectAll().where { Locations.id eq lid and (Locations.userId eq uid) }.singleOrNull()
        }
            ?: throw HttpError(HttpStatusCode.NotFound, "Няма такова място")
    if (row[Locations.solar]) solax.refresh(locOf(row))
    respond(locationOut(row, SolaxRepository.infos(listOf(lid))[lid]))
}
