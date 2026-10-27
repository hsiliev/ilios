package ilios.weather

import ilios.db.Daily
import ilios.db.GeocodeCache
import ilios.db.Intraday15
import ilios.db.Loc
import ilios.db.Locations
import ilios.db.locOf
import ilios.historyStart
import ilios.solax.CHECK_EVERY
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

private const val INTRADAY_STEP_MINUTES = 15
private val WMO_SNOW = 71..77
private val WMO_SNOW_SHOWERS = 85..86
private val WMO_RAIN = 51..67
private val WMO_RAIN_SHOWERS = 80..82
private const val WMO_THUNDER = 95
private const val WIND_GUST_KMH = 50
private const val WIND_KMH = 30
private const val CLOUDY_PERCENT = 75
private const val GEOCODE_CACHE_MS = 30L * 24 * 60 * 60 * 1000
private val json = Json { ignoreUnknownKeys = true }
private const val REFRESH_AFTER_MS = 60 * 60 * 1000 // data older than this is refetched
private val CHECK_EVERY = 10.minutes
const val PAST_DAYS = 14
const val FUTURE_DAYS = 14

/**
 * Reads weather only from the database. A background loop (see [refreshLoop]) fills it from Open-Meteo:
 * on start-up and then whenever a place's data is older than an hour. Places are also fetched when added.
 */
class WeatherService(
    private val http: HttpClient,
    private val forecastUrl: String = "https://api.open-meteo.com",
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com",
) {
    private val lock = Mutex() // one refresh at a time

    /**
     * Place search. Results hardly ever change, so they are kept in the database and a repeated search makes no
     * request.
     */
    suspend fun geocode(query: String, now: Instant = Instant.now()): List<GeoHit> {
        val key = query.trim().lowercase().take(120)
        transaction { GeocodeCache.selectAll().where { GeocodeCache.query eq key }.singleOrNull() }
            ?.takeIf { it[GeocodeCache.fetchedAt] > now.toEpochMilli() - GEOCODE_CACHE_MS }
            ?.let { cached ->
                runCatching {
                    return json.decodeFromString(ListSerializer(GeoHit.serializer()), cached[GeocodeCache.json])
                }
            }
        val hits = http.get("$geocodingUrl/v1/search") {
            parameter("name", query)
            parameter("count", 8)
            parameter("language", "bg")
            parameter("format", "json")
        }.body<GeoResp>().results
        val stored = json.encodeToString(ListSerializer(GeoHit.serializer()), hits)
        transaction {
            GeocodeCache.deleteWhere { GeocodeCache.query eq key }
            GeocodeCache.insert {
                it[GeocodeCache.query] = key
                it[GeocodeCache.json] = stored
                it[GeocodeCache.fetchedAt] = now.toEpochMilli()
            }
        }
        return hits
    }

    // ---- reads (database only) ----

    fun days(loc: Loc): List<Day> = transaction {
        Daily.selectAll().where { Daily.locationId eq loc.id }.map {
            Day(
                LocalDate.parse(it[Daily.day]),
                it[Daily.tMax],
                it[Daily.tMin],
                it[Daily.rainMm] ?: 0.0,
                it[Daily.radiationMj] ?: 0.0,
            )
        }.sortedBy { it.date }
    }

    private fun steps(loc: Loc, day: LocalDate): List<IntraPoint> = transaction {
        Intraday15.selectAll().where { Intraday15.locationId eq loc.id and (Intraday15.day eq day.toString()) }
            .orderBy(Intraday15.t)
            .map {
                IntraPoint(
                    it[Intraday15.t],
                    it[Intraday15.temp],
                    it[Intraday15.rainMm],
                    it[Intraday15.event],
                    it[Intraday15.radiationWm2],
                )
            }
    }

    /**
     * Today's 15-minute steps (and yesterday's and tomorrow's, for the 24-hour window), or null if none are stored
     * for today.
     */
    fun intraday(loc: Loc, today: LocalDate): Intraday? {
        val points = steps(loc, today)
        if (points.isEmpty()) return null
        val now = ZonedDateTime.now(ZoneId.of(loc.tz)).toLocalTime().withSecond(0).withNano(0)
        return Intraday(
            now.toString(),
            INTRADAY_STEP_MINUTES,
            points,
            steps(loc, today.minusDays(1)),
            steps(loc, today.plusDays(1)),
        )
    }

    // ---- refresh (the only code that talks to the forecast API) ----

    /** Runs until cancelled: refetches places whose data is missing or older than an hour. */
    suspend fun refreshLoop() {
        while (true) {
            try {
                for (loc in transaction { Locations.selectAll().map(::locOf) }) refreshIfStale(loc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Refresh loop error: ${e.message}")
            }
            delay(CHECK_EVERY)
        }
    }

    private suspend fun refreshIfStale(loc: Loc) {
        if (!isStale(loc)) return
        try {
            refresh(loc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Open-Meteo refresh failed for ${loc.name}: ${e.message}")
        }
    }

    /**
     * Stale when the daily data is missing or old, or today's 15-minute steps are missing (e.g. a failed or never-run
     * fetch).
     */
    private fun isStale(loc: Loc): Boolean {
        val newest = transaction {
            Daily.select(Daily.fetchedAt.max()).where { Daily.locationId eq loc.id }.single()[Daily.fetchedAt.max()]
        }
        if (newest == null || newest < System.currentTimeMillis() - REFRESH_AFTER_MS) return true
        val today = LocalDate.now(ZoneId.of(loc.tz)).toString()
        return transaction {
            Intraday15.selectAll().where {
                Intraday15.locationId eq loc.id and
                    (Intraday15.day eq today)
            }.empty()
        }
    }

    /**
     * Fetches daily data and today's 15-minute steps. Daily rows before today are kept; today and later are replaced.
     */
    suspend fun refresh(loc: Loc) = lock.withLock {
        // Past days are stored once and never replaced, so ask for them only when some are missing (a new place, or a
        // gap):
        // otherwise today and the next two weeks are enough, which also costs less of Open-Meteo's weighted call
        // allowance.
        val zone = ZoneId.of(loc.tz)
        val today0 = LocalDate.now(zone)
        val windowStart = historyStart(today0)
        val stored =
            transaction { Daily.select(Daily.day).where { Daily.locationId eq loc.id }.map { it[Daily.day] }.toSet() }
        val firstMissing = generateSequence(windowStart) { it.plusDays(1) }.takeWhile { it < today0 }.firstOrNull {
            it.toString() !in
                stored
        }
        val pastDays = if (firstMissing ==
            null
        ) {
            0
        } else {
            (today0.toEpochDay() - firstMissing.toEpochDay()).toInt().coerceIn(1, PAST_DAYS)
        }
        val r = http.get("$forecastUrl/v1/forecast") {
            parameter("latitude", loc.lat)
            parameter("longitude", loc.lon)
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
            Daily.deleteWhere { locationId eq loc.id and (day greaterEq today) }
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

    /**
     * 15-minute steps are Open-Meteo's finest resolution (interpolated from hourly data outside its high-resolution
     * regions).
     */
    private suspend fun refreshIntraday(loc: Loc) {
        val r = http.get("$forecastUrl/v1/forecast") {
            parameter("latitude", loc.lat)
            parameter("longitude", loc.lon)
            parameter(
                "minutely_15",
                "temperature_2m,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m," +
                    "shortwave_radiation",
            )
            parameter("forecast_days", 3)
            parameter("timezone", "auto") // a few days so "today" survives past midnight until the next refresh
        }.body<IntraResp>()
        val m = r.m
        transaction {
            // yesterday's rows stay (the 24-hour window looks back), the fetched days are replaced and older ones
            // dropped
            val first = m.time.firstOrNull()?.substringBefore('T') ?: return@transaction
            Intraday15.deleteWhere {
                locationId eq loc.id and
                    (day greaterEq first or (day less LocalDate.parse(first).minusDays(1).toString()))
            }
            Intraday15.batchInsert(m.time.indices) { i ->
                val code = m.code.getOrNull(i) ?: 0
                this[Intraday15.locationId] = loc.id
                this[Intraday15.day] = m.time[i].substringBefore('T')
                this[Intraday15.t] = m.time[i].substringAfter('T')
                this[Intraday15.temp] = m.temp.getOrNull(i)
                this[Intraday15.rainMm] = m.precipitation.getOrNull(i) ?: 0.0
                this[Intraday15.radiationWm2] = m.rad.getOrNull(i)
                this[Intraday15.event] =
                    eventOf(code, m.gust.getOrNull(i) ?: 0.0, m.wind.getOrNull(i) ?: 0.0, m.cloud.getOrNull(i) ?: 0.0)
            }
        }
    }
}

/**
 * The weather event of a step, from the WMO weather [code], wind gusts and speed (km/h) and cloud cover (%); null
 * when nothing stands out.
 */
internal fun eventOf(code: Int, gust: Double, wind: Double, cloud: Double): String? = when {
    code >= WMO_THUNDER -> "thunder"
    code in WMO_SNOW || code in WMO_SNOW_SHOWERS -> "snow"
    code in WMO_RAIN || code in WMO_RAIN_SHOWERS -> "rain"
    gust >= WIND_GUST_KMH || wind >= WIND_KMH -> "wind"
    cloud >= CLOUDY_PERCENT -> "cloud"
    else -> null
}
