package ilios.web

import ilios.auth.AuthService
import ilios.db.Loc
import ilios.db.locOf
import ilios.report.Report
import ilios.report.buildReport
import ilios.report.nightPlans
import ilios.solax.SolaxReadings
import ilios.solax.SolaxRepository
import ilios.solax.SolaxService
import ilios.weather.WeatherService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import java.time.ZoneId

/** The report of every place of the user, worked out from the database only. */
fun Route.reportRoutes(auth: AuthService, weather: WeatherService, solax: SolaxService) {
    get("/report") {
        val uid = call.userId(auth)
        solax.viewed()
        call.respond(userPlaces(uid).map { reportOf(locOf(it), weather) })
    }
}

private fun reportOf(loc: Loc, weather: WeatherService): Report {
    val days = weather.days(loc)
    if (days.isEmpty()) {
        return Report(
            loc.id,
            loc.name,
            country = loc.country,
            error = "Данните за времето се зареждат – опитайте отново след малко",
        )
    }
    val today = LocalDate.now(ZoneId.of(loc.tz))
    val sx = SolaxReadings

    /** SolaX data only exists for solar places. */
    fun <T> solar(none: T, read: () -> T) = if (loc.solar) read() else none
    return buildReport(
        loc, days, today, weather.intraday(loc, today),
        produced = solar(emptyMap()) { sx.produced(loc.id) },
        plant = solar(null) { SolaxRepository.plant(loc.id) },
        ac = solar(emptyMap()) { sx.producedAc(loc.id) },
        maxSoc = solar(emptyMap()) { sx.batteryDays(loc.id) },
        batteryToday = solar(emptyList()) { sx.today(loc.id, today) },
        batteryPrev = solar(emptyList()) { sx.today(loc.id, today.minusDays(1)) },
        load = solar(emptyMap()) { sx.consumption(loc.id) },
        dischargeProfile = solar(emptyList()) { sx.dischargeProfile(loc.id, today) },
        loadByDay = solar(emptyMap()) { sx.loadByDay(loc.id, today) },
        plans = solar(emptyMap()) { nightPlans(loc.id) },
        badCloud = solar(emptyMap()) { sx.badProduced(loc.id) },
    )
}
