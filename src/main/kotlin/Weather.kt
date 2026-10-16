package ilios

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import java.time.ZoneId
import java.time.ZonedDateTime

private const val GEOCODE_CACHE_MS = 30L * 24 * 60 * 60 * 1000
private val json = Json { ignoreUnknownKeys = true }
private const val REFRESH_AFTER_MS = 3L * 60 * 60 * 1000 // data older than this is refetched
private val CHECK_EVERY = 10.minutes
const val PAST_DAYS = 14
const val FUTURE_DAYS = 14

@Serializable
class GeoHit(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
    val timezone: String? = null,
)

@Serializable
private class GeoResp(val results: List<GeoHit> = emptyList())

@Serializable
private class ForecastResp(val timezone: String = "UTC", val daily: DailyResp)

@Serializable
private class DailyResp(
    val time: List<String>,
    @SerialName("temperature_2m_max") val tMax: List<Double?>,
    @SerialName("temperature_2m_min") val tMin: List<Double?>,
    @SerialName("precipitation_sum") val rain: List<Double?>,
    @SerialName("shortwave_radiation_sum") val radiation: List<Double?>,
)

@Serializable
private class IntraResp(val timezone: String = "UTC", @SerialName("minutely_15") val m: IntraArrays)

@Serializable
private class IntraArrays(
    val time: List<String>,
    @SerialName("temperature_2m") val temp: List<Double?>,
    val precipitation: List<Double?>,
    @SerialName("weather_code") val code: List<Int?>,
    @SerialName("cloud_cover") val cloud: List<Double?>,
    @SerialName("wind_speed_10m") val wind: List<Double?>,
    @SerialName("wind_gusts_10m") val gust: List<Double?>,
)

class Day(val date: LocalDate, val tMax: Double?, val tMin: Double?, val rainMm: Double, val radiationMj: Double)

class Loc(val id: Long, val name: String, val lat: Double, val lon: Double, val tz: String, val solar: Boolean)

fun locOf(r: ResultRow) = Loc(
    r[Locations.id], r[Locations.name], r[Locations.lat], r[Locations.lon], r[Locations.tz], r[Locations.solar],
)

fun newHttpClient(json: Json) = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) { requestTimeoutMillis = 15_000 }
}

/**
 * Reads weather only from the database. A background loop (see [refreshLoop]) fills it from Open-Meteo:
 * on start-up and then whenever a place's data is older than three hours. Places are also fetched when added.
 */
class WeatherService(
    private val http: HttpClient,
    private val forecastUrl: String = "https://api.open-meteo.com",
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com",
) {
    private val lock = Mutex() // one refresh at a time

    /** Place search. Results hardly ever change, so they are kept in the database and a repeated search makes no request. */
    suspend fun geocode(query: String, now: Instant = Instant.now()): List<GeoHit> {
        val key = query.trim().lowercase().take(120)
        transaction { GeocodeCache.selectAll().where { GeocodeCache.query eq key }.singleOrNull() }
            ?.takeIf { it[GeocodeCache.fetchedAt] > now.toEpochMilli() - GEOCODE_CACHE_MS }
            ?.let { cached -> runCatching { return json.decodeFromString(ListSerializer(GeoHit.serializer()), cached[GeocodeCache.json]) } }
        val hits = http.get("$geocodingUrl/v1/search") {
            parameter("name", query); parameter("count", 8); parameter("language", "bg"); parameter("format", "json")
        }.body<GeoResp>().results
        val stored = json.encodeToString(ListSerializer(GeoHit.serializer()), hits)
        transaction {
            GeocodeCache.deleteWhere { GeocodeCache.query eq key }
            GeocodeCache.insert { it[GeocodeCache.query] = key; it[GeocodeCache.json] = stored; it[GeocodeCache.fetchedAt] = now.toEpochMilli() }
        }
        return hits
    }

    // ---- reads (database only) ----

    fun days(loc: Loc): List<Day> = transaction {
        Daily.selectAll().where { Daily.locationId eq loc.id }.map {
            Day(LocalDate.parse(it[Daily.day]), it[Daily.tMax], it[Daily.tMin], it[Daily.rainMm] ?: 0.0, it[Daily.radiationMj] ?: 0.0)
        }.sortedBy { it.date }
    }

    /** Today's 15-minute steps, or null if none are stored. */
    fun intraday(loc: Loc, today: LocalDate): Intraday? {
        val points = transaction {
            Intraday15.selectAll().where { (Intraday15.locationId eq loc.id) and (Intraday15.day eq today.toString()) }
                .orderBy(Intraday15.t).map { IntraPoint(it[Intraday15.t], it[Intraday15.temp], it[Intraday15.rainMm], it[Intraday15.event]) }
        }
        if (points.isEmpty()) return null
        val now = ZonedDateTime.now(ZoneId.of(loc.tz)).toLocalTime().withSecond(0).withNano(0)
        return Intraday(now.toString(), 15, points)
    }

    // ---- refresh (the only code that talks to the forecast API) ----

    /** Runs until cancelled: refetches places whose data is missing or older than three hours. */
    suspend fun refreshLoop() {
        while (true) {
            try {
                for (loc in transaction { Locations.selectAll().map(::locOf) }) {
                    if (!isStale(loc)) continue
                    try {
                        refresh(loc)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        System.err.println("Open-Meteo refresh failed for ${loc.name}: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Refresh loop error: ${e.message}")
            }
            delay(CHECK_EVERY)
        }
    }

    /** Stale when the daily data is missing or old, or today's 15-minute steps are missing (e.g. a failed or never-run fetch). */
    private fun isStale(loc: Loc): Boolean {
        val newest = transaction {
            Daily.select(Daily.fetchedAt.max()).where { Daily.locationId eq loc.id }.single()[Daily.fetchedAt.max()]
        }
        if (newest == null || newest < System.currentTimeMillis() - REFRESH_AFTER_MS) return true
        val today = LocalDate.now(ZoneId.of(loc.tz)).toString()
        return transaction { Intraday15.selectAll().where { (Intraday15.locationId eq loc.id) and (Intraday15.day eq today) }.empty() }
    }

    /** Fetches daily data and today's 15-minute steps. Daily rows before today are kept; today and later are replaced. */
    suspend fun refresh(loc: Loc) = lock.withLock {
        // Past days are stored once and never replaced, so ask for them only when some are missing (a new place, or a gap):
        // otherwise today and the next two weeks are enough, which also costs less of Open-Meteo's weighted call allowance.
        val zone = ZoneId.of(loc.tz)
        val today0 = LocalDate.now(zone)
        val windowStart = today0.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7)
        val stored = transaction { Daily.select(Daily.day).where { Daily.locationId eq loc.id }.map { it[Daily.day] }.toSet() }
        val firstMissing = generateSequence(windowStart) { it.plusDays(1) }.takeWhile { it < today0 }.firstOrNull { it.toString() !in stored }
        val pastDays = if (firstMissing == null) 0 else (today0.toEpochDay() - firstMissing.toEpochDay()).toInt().coerceIn(1, PAST_DAYS)
        val r = http.get("$forecastUrl/v1/forecast") {
            parameter("latitude", loc.lat); parameter("longitude", loc.lon)
            parameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum,shortwave_radiation_sum")
            if (pastDays > 0) parameter("past_days", pastDays)
            parameter("forecast_days", FUTURE_DAYS)
            parameter("timezone", "auto")
        }.body<ForecastResp>()
        val d = r.daily
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.of(r.timezone)).toString()
        transaction {
            val have = Daily.select(Daily.day).where { Daily.locationId eq loc.id }.map { it[Daily.day] }.toSet()
            Daily.deleteWhere { (locationId eq loc.id) and (day greaterEq today) }
            Daily.batchInsert(d.time.indices.filter { d.time[it] >= today || d.time[it] !in have }) { i ->
                this[Daily.locationId] = loc.id
                this[Daily.day] = d.time[i]
                this[Daily.tMax] = d.tMax.getOrNull(i)
                this[Daily.tMin] = d.tMin.getOrNull(i)
                this[Daily.rainMm] = d.rain.getOrNull(i)
                this[Daily.radiationMj] = d.radiation.getOrNull(i)
                this[Daily.fetchedAt] = now
            }
            Locations.update({ Locations.id eq loc.id }) { it[tz] = r.timezone }
        }
        try {
            refreshIntraday(loc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Open-Meteo 15-minute refresh failed for ${loc.name}: ${e.message}")
        }
    }

    /** 15-minute steps are Open-Meteo's finest resolution (interpolated from hourly data outside its high-resolution regions). */
    private suspend fun refreshIntraday(loc: Loc) {
        val r = http.get("$forecastUrl/v1/forecast") {
            parameter("latitude", loc.lat); parameter("longitude", loc.lon)
            parameter("minutely_15", "temperature_2m,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m")
            parameter("forecast_days", 3); parameter("timezone", "auto") // a few days so "today" survives past midnight until the next refresh
        }.body<IntraResp>()
        val m = r.m
        transaction {
            Intraday15.deleteWhere { locationId eq loc.id }
            Intraday15.batchInsert(m.time.indices) { i ->
                val code = m.code.getOrNull(i) ?: 0
                this[Intraday15.locationId] = loc.id
                this[Intraday15.day] = m.time[i].substringBefore('T')
                this[Intraday15.t] = m.time[i].substringAfter('T')
                this[Intraday15.temp] = m.temp.getOrNull(i)
                this[Intraday15.rainMm] = m.precipitation.getOrNull(i) ?: 0.0
                this[Intraday15.event] = when {
                    code >= 95 -> "thunder"
                    code in 71..77 || code == 85 || code == 86 -> "snow"
                    code in 51..67 || code in 80..82 -> "rain"
                    (m.gust.getOrNull(i) ?: 0.0) >= 50 || (m.wind.getOrNull(i) ?: 0.0) >= 30 -> "wind"
                    (m.cloud.getOrNull(i) ?: 0.0) >= 75 -> "cloud"
                    else -> null
                }
            }
        }
    }
}
