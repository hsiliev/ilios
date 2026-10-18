package ilios

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.time.Duration.Companion.minutes

// SolaX Cloud developer API (https://developer.solaxcloud.com): the API has call-count and rate limits, so there is no polling.
// Produced energy of a past day never changes, so each day is fetched once (on connecting, then the new day every morning);
// the battery charge is read hourly and cached in the database. Everything is served from the database.
private const val RETRY_AFTER_MS = 60L * 60 * 1000 // ordinary failures: try again in an hour
private const val QUOTA_RETRY_MS = 6L * 60 * 60 * 1000 // the call allowance is used up: it will not recover within the hour
private const val AUTH_RETRY_MS = 12L * 60 * 60 * 1000 // wrong credentials or no access: retrying only burns calls until the user fixes them
/** The battery charge is read once a day at this local time in Sofia (not in the place's time zone). */
private val BATTERY_ZONE = ZoneId.of("Europe/Sofia")
/** A day's production is final only after the cloud has processed the night, so yesterday is fetched from this local time. */
private val PRODUCTION_FINAL_FROM = LocalTime.of(2, 0)
private const val PLANT_REFRESH_MS = 7L * 24 * 60 * 60 * 1000 // panel power and battery capacity rarely change
private val CHECK_EVERY = 10.minutes

private const val OK = 10000
private const val TOKEN_REJECTED = 10402
private val QUOTA = mapOf(
    10405 to "Лимитът на заявките към Solax е изчерпан – ще опитам отново по-късно",
    10406 to "Достигнат е лимитът на честота на заявките към Solax – ще опитам отново по-късно",
)
private val NO_ACCESS = setOf(10403, 10500, 10505, 10506)
private fun quotaError(code: Int) = SolaxError(QUOTA.getValue(code), if (code == 10405) QUOTA_RETRY_MS else RETRY_AFTER_MS)

/** SolaX API host of the account's region (see "My Account" in the developer portal); EU by default, override with SOLAX_URL. */
fun solaxBaseUrl(value: String? = null): String {
    val v = value?.trim().orEmpty().ifEmpty { "https://openapi-eu.solaxcloud.com" }
    val uri = runCatching { URI(v) }.getOrNull()
    require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "SOLAX_URL must be an http(s) URL, got '$v'" }
    return v.trimEnd('/')
}

/** The newest day whose production is final at [local] time: yesterday from 02:00, otherwise the day before. */
fun lastProductionDay(local: ZonedDateTime): LocalDate =
    local.toLocalDate().minusDays(if (local.toLocalTime() >= PRODUCTION_FINAL_FROM) 1 else 2)

/** The start of the current hour; a battery reading older than this is due, so the charge is read hourly. */
fun lastBatterySlot(now: Instant): Instant = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)

@Serializable private class TokenResp(val code: Int = -1, val message: String? = null, val result: TokenResult? = null)
@Serializable private class TokenResult(@SerialName("access_token") val accessToken: String, @SerialName("expires_in") val expiresIn: Long = 0)
@Serializable private class PlantsResp(val code: Int = -1, val message: String? = null, val result: PlantsResult? = null)
@Serializable private class PlantsResult(val records: List<PlantRec> = emptyList(), val pages: Int = 1)
@Serializable private class PlantRec(
    val plantId: JsonPrimitive, val plantName: String? = null,
    val pvCapacity: Double? = null, // installed PV power, kWp
    val batteryCapacity: Double? = null, // installed battery capacity, kWh
)
@Serializable private class StatResp(val code: Int = -1, val message: String? = null, val result: StatResult? = null)
@Serializable private class StatResult(val plantEnergyStatDataList: List<StatRow> = emptyList())
@Serializable private class StatRow(val date: String, val pvGeneration: Double? = null, val inverterACOutputEnergy: Double? = null, val loadConsumption: Double? = null)
@Serializable private class DevicesResp(val code: Int = -1, val message: String? = null, val result: DevicesResult? = null)
@Serializable private class DevicesResult(val records: List<DeviceRec> = emptyList(), val pages: Int = 1)
@Serializable private class DeviceRec(val deviceSn: String? = null, val ratedCapacity: JsonPrimitive? = null)
@Serializable private class RealtimeResp(val code: Int = -1, val message: String? = null, val result: List<BatteryRec>? = null)
@Serializable private class HistResp(val code: Int = -1, val message: String? = null, val result: List<HistPoint>? = null)
@Serializable private class HistPoint(val plantLocalTime: JsonPrimitive? = null, val dataTime: JsonPrimitive? = null, val batterySOC: Double? = null, val batteryRemainings: Double? = null)
@Serializable private class BatteryRec(val batterySOC: Double? = null, val batteryRemainings: Double? = null, val chargeDischargePower: Double? = null)

/** The local date-time of a history point: SolaX's plant-local time when given, else its data time (epoch millis or UTC text). */
private fun momentOf(p: JsonObject, zone: ZoneId): LocalDateTime? {
    fun text(s: String?) = s?.trim()?.replace(' ', 'T')?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    text(p["plantLocalTime"]?.jsonPrimitive?.contentOrNull)?.let { return it }
    val dt = p["dataTime"]?.jsonPrimitive?.contentOrNull ?: return null
    dt.toLongOrNull()?.let { return LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    return text(dt)?.atZone(ZoneOffset.UTC)?.withZoneSameInstant(zone)?.toLocalDateTime()
}

/** Panel power (W) of an inverter history point: its total PV input, else the sum of the MPPT trackers' power; null when it has none. */
private fun pvPowerOf(p: JsonObject): Double? {
    fun num(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    num(p["MPPTTotalInputPower"])?.let { return maxOf(0.0, it) }
    val trackers = (p["mpptMap"] as? JsonObject)?.filterKeys { it.matches(Regex("MPPT\\d+Power", RegexOption.IGNORE_CASE)) }?.values?.mapNotNull { num(it) }.orEmpty()
    return trackers.takeIf { it.isNotEmpty() }?.sumOf { maxOf(0.0, it) }
}

/** A failure with a message that is safe to show the user (never contains credentials). */
private class SolaxError(message: String, val retryMs: Long = RETRY_AFTER_MS) : Exception(message)
private class TokenRejected : Exception()

/** A plant of the SolaX account, as offered in the settings dropdown. */
@Serializable
class PlantOption(val id: String, val name: String)

private val plantsJson = Json { ignoreUnknownKeys = true }
private fun decodePlants(s: String?): List<PlantOption> =
    if (s.isNullOrBlank()) emptyList() else runCatching { plantsJson.decodeFromString(ListSerializer(PlantOption.serializer()), s) }.getOrDefault(emptyList())

/** Installed PV power (kWp) and battery capacity (kWh) of the connected plant, as reported by SolaX. */
class SolaxPlant(
    val kwp: Double?,
    /** The battery capacity the app works with: the devices' hardware rated capacity. */
    val batteryKwh: Double?,
    val soc: Double? = null, val remainingKwh: Double? = null, val powerW: Double? = null, val readAt: Long? = null,
    val deviceKwh: Double? = null,
)

/** One past day of battery history: the highest charge reached. */
class BatteryDayResult(val maxSoc: Double, val raw: String? = null)

/** What the settings page may see about a place's connection. The secret itself is never exposed. */
class SolaxInfo(
    val clientId: String, val plantId: String?, val status: String, val batteryStatus: String, val plants: List<PlantOption>,
    val batteryInfo: String = "", // the battery capacity figure from SolaX
)

/** Battery capacity figure from device rated capacity. */
private fun capacityText(p: SolaxPlant?): String {
    if (p == null) return ""
    val bg = java.util.Locale.forLanguageTag("bg")
    fun kwh(v: Double) = "%.1f".format(bg, v) + " kWh"
    return p.deviceKwh?.let { "Капацитет на батерията: ${kwh(it)}" } ?: ""
}

class SolaxService(private val http: HttpClient, private val baseUrl: String = solaxBaseUrl()) {
    private val lock = Mutex()
    private val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    // ---- database-only reads and configuration ----

    fun produced(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId }.associate { LocalDate.parse(it[Produced.day]) to it[Produced.kwh] }
    }

    fun plant(locationId: Long): SolaxPlant? = transaction {
        Solax.selectAll().where { Solax.locationId eq locationId }.singleOrNull()
            ?.let { r ->
                val device = r[Solax.batteryDeviceKwh]?.takeIf { it > 0 }
                SolaxPlant(
                    r[Solax.pvKwp]?.takeIf { it > 0 }, device,
                    r[Solax.soc], r[Solax.batteryRemainingKwh], r[Solax.batteryPowerW], r[Solax.batteryReadAt],
                    deviceKwh = device,
                )
            }
    }

    /** Inverter AC output per day (the SolaX app's "Inverter Output"); unlike [produced] it includes battery discharge and excludes PV energy stored in the battery. */
    fun producedAc(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId }
            .mapNotNull { r -> r[Produced.acKwh]?.let { LocalDate.parse(r[Produced.day]) to it } }.toMap()
    }

    /** The home's total consumption per day (heat pump included), as SolaX reports it. */
    fun consumption(locationId: Long): Map<LocalDate, Double> = transaction {
        Produced.selectAll().where { Produced.locationId eq locationId }
            .mapNotNull { r -> r[Produced.loadKwh]?.let { LocalDate.parse(r[Produced.day]) to it } }.toMap()
    }

    /** The highest battery charge (%) each past day reached, where SolaX's history was fetched. */
    fun batteryDays(locationId: Long): Map<LocalDate, Double> = transaction {
        BatteryDays.selectAll().where { BatteryDays.locationId eq locationId }.associate { LocalDate.parse(it[BatteryDays.day]) to it[BatteryDays.maxSoc] }
    }

    /** Today's cached 5-minute battery charge and panel power. */
    fun today(locationId: Long, day: LocalDate): List<TodayPoint> = transaction {
        SolarToday.selectAll().where { (SolarToday.locationId eq locationId) and (SolarToday.day eq day.toString()) }
            .orderBy(SolarToday.t).map { TodayPoint(it[SolarToday.t], it[SolarToday.soc], it[SolarToday.pvW], it[SolarToday.batW]) }
    }

    fun infos(locationIds: List<Long>): Map<Long, SolaxInfo> = transaction {
        Solax.selectAll().where { Solax.locationId inList locationIds }
            .associate {
                val id = it[Solax.locationId]
                id to SolaxInfo(
                    it[Solax.clientId], it[Solax.plantId], it[Solax.status], it[Solax.batteryStatus].orEmpty(), decodePlants(it[Solax.plants]),
                    capacityText(plant(id)),
                )
            }
    }

    /** Forgets everything derived from the selected plant (not its status or retry gate). */
    private fun UpdateBuilder<*>.clearPlantData() {
        this[Solax.plantName] = null; this[Solax.pvKwp] = null; this[Solax.batteryKwh] = null; this[Solax.batteryDeviceKwh] = null; this[Solax.diagDevices] = null; this[Solax.diagHistory] = null; this[Solax.producedThrough] = null
        this[Solax.batteryDaysThrough] = null; this[Solax.historyTryAt] = null
        this[Solax.plantReadAt] = null; this[Solax.inverterSns] = null; this[Solax.soc] = null; this[Solax.batteryRemainingKwh] = null
        this[Solax.batteryPowerW] = null; this[Solax.batteryReadAt] = null; this[Solax.batteryTryAt] = null; this[Solax.batteryStatus] = null
    }

    /** Like [clearPlantData], and makes the next sync run at once. */
    private fun UpdateBuilder<*>.resetPlantData() {
        clearPlantData(); this[Solax.dueAt] = 0; this[Solax.status] = "Изчаква синхронизация…"
    }

    /** The data stored for a plant belongs to that plant only: when the place switches plants it must go. */
    private fun wipePlantHistory(locationId: Long) = transaction {
        Produced.deleteWhere { Produced.locationId eq locationId }
        BatteryDays.deleteWhere { BatteryDays.locationId eq locationId }
        SolarToday.deleteWhere { SolarToday.locationId eq locationId }
        Solax.update({ Solax.locationId eq locationId }) { it.clearPlantData() }
        Unit
    }

    /**
     * Saves a place's SolaX settings. A blank client ID disconnects; a blank secret keeps the stored one. [plantId] is the
     * dropdown's selection and only matters while the credentials stay the same (new credentials start again with the first
     * plant). Returns true when the connection changed and should be synced now.
     */
    fun configure(locationId: Long, clientId: String, secret: String, plantId: String): Boolean {
        val id = clientId.trim(); val sec = secret.trim(); val plant = plantId.trim().ifEmpty { null }
        if (id.length > 100 || sec.length > 200 || (plant?.length ?: 0) > 40) throw HttpError(HttpStatusCode.BadRequest, "Стойността е твърде дълга")
        return transaction {
            val row = Solax.selectAll().where { Solax.locationId eq locationId }.singleOrNull()
            when {
                id.isEmpty() -> { if (row != null) Solax.deleteWhere { Solax.locationId eq locationId }; false }
                row == null -> {
                    if (sec.isEmpty()) throw HttpError(HttpStatusCode.BadRequest, "Въведете Solax Client Secret")
                    Solax.insert {
                        it[Solax.locationId] = locationId; it[Solax.clientId] = id; it[Solax.clientSecret] = sec
                        it[Solax.status] = "Изчаква първа синхронизация…"
                    }
                    true
                }
                id != row[Solax.clientId] || sec.isNotEmpty() -> {
                    // New credentials: the account's plant list must be read again, but what is stored for the selected plant is
                    // kept, because most often it is the same plant (a rotated secret) and re-fetching it all would cost many calls.
                    // If the new account turns out to have a different plant, the next sync wipes the old plant's data.
                    Solax.update({ Solax.locationId eq locationId }) {
                        it[Solax.clientId] = id; if (sec.isNotEmpty()) it[Solax.clientSecret] = sec
                        it[Solax.plants] = null; it[Solax.businessType] = null
                        it[Solax.token] = null; it[Solax.tokenExpires] = 0
                        it[Solax.plantReadAt] = null; it[Solax.dueAt] = 0; it[Solax.batteryTryAt] = null; it[Solax.historyTryAt] = null
                        it[Solax.status] = "Изчаква синхронизация…"
                    }
                    true
                }
                plant != null && plant != row[Solax.plantId] -> { // another plant chosen in the dropdown
                    val offered = decodePlants(row[Solax.plants])
                    if (offered.isNotEmpty() && offered.none { it.id == plant }) throw HttpError(HttpStatusCode.BadRequest, "Невалидна централа")
                    Produced.deleteWhere { Produced.locationId eq locationId }
                    BatteryDays.deleteWhere { BatteryDays.locationId eq locationId }
                    Solax.update({ Solax.locationId eq locationId }) { it[Solax.plantId] = plant; it.resetPlantData() }
                    true
                }
                else -> false
            }
        }
    }

    // ---- sync (the only code that talks to SolaX) ----

    /** Opens every retry gate of one place and re-reads everything at once (the settings page's refresh button). */
    suspend fun refresh(loc: Loc) {
        transaction {
            Solax.update({ Solax.locationId eq loc.id }) {
                it[Solax.plantReadAt] = null; it[Solax.dueAt] = 0; it[Solax.batteryTryAt] = null; it[Solax.batteryReadAt] = null; it[Solax.historyTryAt] = null
            }
        }
        sync(loc); readBatteryIfDue(loc); batteryHistoryIfDue(loc)
    }

    /** Runs until cancelled: syncs solar places that are due. */
    suspend fun syncLoop() {
        while (true) {
            try {
                val places = transaction { (Locations innerJoin Solax).selectAll().where { Locations.solar eq true }.map(::locOf) }
                for (loc in places) { syncIfDue(loc); readBatteryIfDue(loc); batteryHistoryIfDue(loc) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("SolaX loop error: ${e.message}")
            }
            delay(CHECK_EVERY)
        }
    }

    /** Syncs production when a new final day is available (or the connection is new) and no failure back-off is running. */
    suspend fun syncIfDue(loc: Loc, now: Instant = Instant.now()) {
        val row = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.singleOrNull() } ?: return
        val through = row[Solax.producedThrough]?.let(LocalDate::parse)
        val newest = lastProductionDay(now.atZone(ZoneId.of(loc.tz)))
        if (row[Solax.dueAt] <= now.toEpochMilli() && (through == null || through < newest)) sync(loc, now)
    }

    /**
     * Fetches the production of the days not fetched yet (up to the last final day) and refreshes the plant record when it is
     * old. Never throws (except cancellation); the outcome goes to the status text.
     */
    suspend fun sync(loc: Loc, now: Instant = Instant.now()) = lock.withLock {
        val row = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.singleOrNull() } ?: return@withLock
        try {
            val summary = fetch(loc, row, now)
            finish(loc.id, "Свързано – $summary", 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SolaxError) {
            finish(loc.id, e.message ?: "Грешка от Solax", now.toEpochMilli() + e.retryMs)
        } catch (e: Exception) {
            System.err.println("SolaX request failed for place ${loc.id}: ${e::class.simpleName}")
            finish(loc.id, "Няма връзка със Solax – ще опитам отново по-късно", now.toEpochMilli() + RETRY_AFTER_MS)
        }
    }

    private fun finish(locationId: Long, status: String, dueAt: Long) = transaction {
        Solax.update({ Solax.locationId eq locationId }) { it[Solax.status] = status.take(400); it[Solax.dueAt] = dueAt }
        Unit
    }

    private suspend fun fetch(loc: Loc, row: ResultRow, now: Instant): String {
        val session = Session(loc.id, row)
        // The plant record (name, PV power, battery capacity) is read when the place is first connected and then about
        // weekly: it hardly ever changes and every call counts against SolaX's quota.
        val known = row[Solax.plantId]; val knownType = row[Solax.businessType]
        val cachedFresh = known != null && knownType != null && row[Solax.plants] != null && (row[Solax.pvKwp] ?: 0.0) > 0 &&
            (row[Solax.batteryDeviceKwh] != null || row[Solax.diagDevices] != null) &&
            (row[Solax.plantReadAt] ?: 0L) > now.toEpochMilli() - (if ((row[Solax.batteryDeviceKwh] ?: 0.0) > 0) PLANT_REFRESH_MS else RETRY_AFTER_MS) // no capacity yet: look again hourly
        val plantId: String; val plantName: String?; val pvKwp: Double?; var type: Int
        var deviceKwh: Double? = row[Solax.batteryDeviceKwh]
        var diagDevices: String? = row[Solax.diagDevices]
        var wiped = false
        if (cachedFresh) {
            plantId = known; plantName = row[Solax.plantName]; pvKwp = row[Solax.pvKwp]; type = knownType
        } else {
            val (found, all) = session.plants(knownType)
            type = found
            val options = all.map { PlantOption(it.plantId.content, it.plantName ?: it.plantId.content) }
            // the list is saved first so the dropdown works even if the selected plant turns out to be missing
            transaction {
                Solax.update({ Solax.locationId eq loc.id }) {
                    it[Solax.plants] = plantsJson.encodeToString(ListSerializer(PlantOption.serializer()), options); it[Solax.businessType] = found
                }
            }
            val listWasLoaded = row[Solax.plants] != null
            val match = known?.let { k -> all.firstOrNull { it.plantId.content == k } }
            val chosen = when {
                match != null -> match // same plant as before: everything stored for it stays valid
                known == null -> all.first() // the first plant is the default
                !listWasLoaded -> { // new credentials led to an account without the old plant: start over with its first plant
                    wipePlantHistory(loc.id); wiped = true; all.first()
                }
                else -> throw SolaxError("Централа $known не е намерена в акаунта в Solax – изберете друга от списъка")
            }
            plantId = chosen.plantId.content; plantName = chosen.plantName; pvKwp = chosen.pvCapacity
            // the batteries' own rated capacity (from the device list):
            val devices = try { session.batteryCapacity(plantId, found) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            deviceKwh = devices?.first
            diagDevices = if (devices?.first == null) (devices?.second ?: "заявката не успя") else null
        }

        // Production of a past day never changes, so each day is fetched once: from the day after the last fetched one (or
        // the start of the charts' window on a new connection) up to the last final day. One call per month involved.
        val local = now.atZone(ZoneId.of(loc.tz))
        val end = lastProductionDay(local)
        val windowStart = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7)
        val fetchedThrough = if (wiped) null else row[Solax.producedThrough]?.let(LocalDate::parse)
        val start = maxOf(windowStart, fetchedThrough?.plusDays(1) ?: windowStart)
        val rows = ArrayList<StatRow>()
        if (start <= end) {
            for (month in generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(end) }) {
                val (usedType, list) = session.statistics(plantId, type, month.toString())
                type = usedType; rows += list
            }
        }
        val fresh = rows.mapNotNull { r -> r.pvGeneration?.let { Triple(LocalDate.parse(r.date), it, r.inverterACOutputEnergy to r.loadConsumption) } }.filter { it.first in start..end }
        transaction {
            if (start <= end) {
                Produced.deleteWhere { (Produced.locationId eq loc.id) and (Produced.day greaterEq start.toString()) and (Produced.day lessEq end.toString()) }
                Produced.batchInsert(fresh) { (d, kwh, acLoad) ->
                    val (ac, load) = acLoad
                    this[Produced.locationId] = loc.id; this[Produced.day] = d.toString(); this[Produced.kwh] = kwh; this[Produced.acKwh] = ac; this[Produced.loadKwh] = load
                }
            }
            Solax.update({ Solax.locationId eq loc.id }) {
                it[Solax.plantId] = plantId; it[Solax.plantName] = plantName; it[Solax.businessType] = type
                if (!cachedFresh) {
                    it[Solax.pvKwp] = pvKwp; it[Solax.batteryDeviceKwh] = deviceKwh; it[Solax.diagDevices] = diagDevices?.take(1500); it[Solax.plantReadAt] = now.toEpochMilli()
                }
                if (start <= end) it[Solax.producedThrough] = end.toString()
            }
        }
        val bg = java.util.Locale.forLanguageTag("bg")
        val specs = listOfNotNull(
            pvKwp?.takeIf { it > 0 }?.let { "панели ${"%.1f".format(bg, it)} kWp" },
            deviceKwh?.takeIf { it > 0 }?.let { "батерия ${"%.1f".format(bg, it)} kWh" },
        )
        val through = if (start <= end) end else row[Solax.producedThrough]?.let(LocalDate::parse) ?: end
        return "централа „${plantName ?: plantId}“" + (if (specs.isEmpty()) "" else " (${specs.joinToString(", ")})") +
            ", произведена енергия до ${"%02d.%02d".format(through.dayOfMonth, through.monthValue)}"
    }

    /** No battery (or no data): forget any old reading. A non-null [readAt] marks "asked then", so the next attempt waits for the next slot. */
    private fun clearBattery(locationId: Long, readAt: Long?) = transaction {
        Solax.update({ Solax.locationId eq locationId }) {
            it[Solax.soc] = null; it[Solax.batteryRemainingKwh] = null; it[Solax.batteryPowerW] = null
            it[Solax.batteryReadAt] = readAt
        }
        Unit
    }

    /**
     * Reads the battery charge hourly: when a reading is older than the start of the current hour (or there is none yet, e.g.
     * right after connecting). Failures are retried at most hourly. Never throws (except cancellation); the outcome goes to
     * [Solax.batteryStatus].
     */
    suspend fun readBatteryIfDue(loc: Loc, now: Instant = Instant.now()) = lock.withLock {
        val row = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.singleOrNull() } ?: return@withLock
        val plantId = row[Solax.plantId]; val type = row[Solax.businessType]
        if (plantId == null || type == null) return@withLock // the production sync has to find the plant first
        if (!hasBattery(row)) { // no battery: nothing to read
            if (row[Solax.soc] != null || row[Solax.batteryStatus] != null) {
                clearBattery(loc.id, readAt = null); setBatteryStatus(loc.id, null)
            }
            return@withLock
        }
        val readAt = row[Solax.batteryReadAt]; val tryAt = row[Solax.batteryTryAt]
        val due = (readAt == null || readAt < lastBatterySlot(now).toEpochMilli()) &&
            (tryAt == null || tryAt <= now.toEpochMilli()) // tryAt = the earliest time of the next attempt
        if (!due) return@withLock
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.batteryTryAt] = now.toEpochMilli() + RETRY_AFTER_MS } }
        try {
            val soc = readBattery(loc.id, row, Session(loc.id, row), plantId, type, now.toEpochMilli())
            val at = now.atZone(BATTERY_ZONE).format(stamp)
            setBatteryStatus(loc.id, if (soc == null) "Solax не върна данни за батерията" else "Заряд на батерията: ${"%.0f".format(java.util.Locale.forLanguageTag("bg"), soc)}% към $at (София)")
            cacheToday(loc, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SolaxError) {
            setBatteryStatus(loc.id, e.message)
            transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.batteryTryAt] = now.toEpochMilli() + e.retryMs } }
        } catch (e: Exception) {
            System.err.println("SolaX battery read failed for place ${loc.id}: ${e::class.simpleName}")
            setBatteryStatus(loc.id, "Зарядът на батерията не може да се прочете – ще опитам отново по-късно")
        }
    }

    /**
     * Caches today's battery charge and panel power at the finest step SolaX's history allows (5 minutes, windows of at most
     * 12 hours, so up to three calls per kind). The battery and inverter histories are merged by time and replace what was
     * stored. Never throws (except cancellation); a failure keeps the previous data.
     */
    private suspend fun cacheToday(loc: Loc, now: Instant) {
        try {
            val row = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.singleOrNull() } ?: return
            val plantId = row[Solax.plantId] ?: return; val type = row[Solax.businessType] ?: return
            val session = Session(loc.id, row)
            val sns = inverterSerials(loc.id, row, session, plantId, type)
            if (sns.isEmpty()) return
            val zone = ZoneId.of(loc.tz)
            val day = now.atZone(zone).toLocalDate()
            val from = day.atStartOfDay(zone).toInstant()
            fun slot(m: LocalDateTime) = "%02d:%02d".format(m.hour, m.minute / 5 * 5)
            val soc = HashMap<String, MutableList<Double>>(); val pv = HashMap<String, MutableList<Double>>(); val bat = HashMap<String, MutableList<Double>>()
            for (p in session.history(sns, type, 2, from, now)) {
                val m = momentOf(p, zone)?.takeIf { it.toLocalDate() == day } ?: continue
                p["batterySOC"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { soc.getOrPut(slot(m)) { ArrayList() } += it }
                p["chargeDischargePower"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { bat.getOrPut(slot(m)) { ArrayList() } += it }
            }
            try { // the panel bars are optional: without them the charge curve is still worth keeping
                for (p in session.history(sns, type, 1, from, now)) {
                    val m = momentOf(p, zone)?.takeIf { it.toLocalDate() == day } ?: continue
                    pvPowerOf(p)?.let { pv.getOrPut(slot(m)) { ArrayList() } += it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("SolaX inverter history failed for place ${loc.id}: ${(e as? SolaxError)?.message ?: e::class.simpleName}")
            }
            val slots = (soc.keys + pv.keys + bat.keys).sorted()
            if (slots.isEmpty()) return
            transaction {
                SolarToday.deleteWhere { SolarToday.locationId eq loc.id }
                SolarToday.batchInsert(slots) { t ->
                    this[SolarToday.locationId] = loc.id; this[SolarToday.day] = day.toString(); this[SolarToday.t] = t
                    this[SolarToday.soc] = soc[t]?.average(); this[SolarToday.pvW] = pv[t]?.sum() // several inverters add up
                    this[SolarToday.batW] = bat[t]?.sum() // several batteries add up
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("SolaX today's history failed for place ${loc.id}: ${(e as? SolaxError)?.message ?: e::class.simpleName}")
        }
    }

    private fun setBatteryStatus(locationId: Long, text: String?) = transaction {
        Solax.update({ Solax.locationId eq locationId }) { it[Solax.batteryStatus] = text?.take(200) }
        Unit
    }

    private fun hasBattery(row: ResultRow) = (row[Solax.batteryDeviceKwh] ?: 0.0) > 0

    /** Serial numbers of the plant's inverters, looked up once and cached. */
    private suspend fun inverterSerials(locationId: Long, row: ResultRow, session: Session, plantId: String, type: Int): List<String> {
        val cached = row[Solax.inverterSns]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        return cached.ifEmpty {
            session.inverters(plantId, type).also { found ->
                transaction { Solax.update({ Solax.locationId eq locationId }) { it[Solax.inverterSns] = found.joinToString(",").ifEmpty { null } } }
            }
        }
    }

    /**
     * Finds out, one past day at a time, how full the battery got: the day's battery history (SolaX allows at most 12 hours
     * per request, so two requests) gives the highest charge. Each day is fetched once; a backlog (a new connection, or the
     * app being off) is worked off one day per call so the burst stays small, and failures are retried at most hourly. Never
     * throws (except cancellation).
     */
    suspend fun batteryHistoryIfDue(loc: Loc, now: Instant = Instant.now()) = lock.withLock {
        val row = transaction { Solax.selectAll().where { Solax.locationId eq loc.id }.singleOrNull() } ?: return@withLock
        val plantId = row[Solax.plantId]; val type = row[Solax.businessType]
        if (plantId == null || type == null || !hasBattery(row)) return@withLock // no plant yet, or no battery
        val tried = row[Solax.historyTryAt]
        if (tried != null && tried > now.toEpochMilli()) return@withLock // tried = the earliest time of the next attempt
        val zone = ZoneId.of(loc.tz)
        val local = now.atZone(zone)
        val end = lastProductionDay(local)
        val windowStart = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7)
        val day = maxOf(windowStart, row[Solax.batteryDaysThrough]?.let(LocalDate::parse)?.plusDays(1) ?: windowStart)
        if (day > end) return@withLock
        transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.historyTryAt] = now.toEpochMilli() + RETRY_AFTER_MS } }
        try {
            val session = Session(loc.id, row)
            val sns = inverterSerials(loc.id, row, session, plantId, type)
            val result = if (sns.isEmpty()) null else session.batteryDay(sns, type, day, zone)
            transaction {
                BatteryDays.deleteWhere { (BatteryDays.locationId eq loc.id) and (BatteryDays.day eq day.toString()) }
                if (result != null) BatteryDays.insert {
                    it[BatteryDays.locationId] = loc.id; it[BatteryDays.day] = day.toString()
                    it[BatteryDays.maxSoc] = result.maxSoc
                }
                Solax.update({ Solax.locationId eq loc.id }) { it[Solax.batteryDaysThrough] = day.toString(); it[Solax.historyTryAt] = null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SolaxError) {
            System.err.println("SolaX battery history failed for place ${loc.id}: ${e.message}")
            transaction { Solax.update({ Solax.locationId eq loc.id }) { it[Solax.historyTryAt] = now.toEpochMilli() + e.retryMs } }
        } catch (e: Exception) {
            System.err.println("SolaX battery history failed for place ${loc.id}: ${(e as? SolaxError)?.message ?: e::class.simpleName}")
        }
    }

    /**
     * Reads the battery's state of charge through the plant's inverters (serials are looked up once and cached) and stores it.
     * Several batteries are averaged for the percentage and summed for energy and power. Returns the percentage, or null when
     * SolaX returned no battery data.
     */
    private suspend fun readBattery(locationId: Long, row: ResultRow, session: Session, plantId: String, type: Int, at: Long): Double? {
        val sns = inverterSerials(locationId, row, session, plantId, type)
        if (sns.isEmpty()) { clearBattery(locationId, at); return null }
        val batteries = session.batteries(sns, type)
        val socs = batteries.mapNotNull { it.batterySOC }
        if (socs.isEmpty()) { clearBattery(locationId, at); return null }
        val soc = socs.average()
        val remaining = batteries.mapNotNull { it.batteryRemainings }.takeIf { it.isNotEmpty() }?.sum()
        val power = batteries.mapNotNull { it.chargeDischargePower }.takeIf { it.isNotEmpty() }?.sum()
        transaction {
            Solax.update({ Solax.locationId eq locationId }) {
                it[Solax.soc] = soc; it[Solax.batteryRemainingKwh] = remaining; it[Solax.batteryPowerW] = power
                it[Solax.batteryReadAt] = at
            }
        }
        return soc
    }

    /** One sync run for one place: holds the access token and handles its renewal. */
    private inner class Session(private val locationId: Long, private val row: ResultRow) {
        private var token: String? = row[Solax.token]?.takeIf { row[Solax.tokenExpires] > System.currentTimeMillis() }

        private suspend fun newToken(): String {
            val r = http.submitForm("$baseUrl/openapi/auth/oauth/token", parameters {
                append("client_id", row[Solax.clientId]); append("client_secret", row[Solax.clientSecret]); append("grant_type", "client_credentials")
            }).body<TokenResp>()
            val result = r.result
            if (r.code != 0 || result == null) {
                if (r.code in QUOTA) throw quotaError(r.code)
                throw SolaxError("Solax отказа достъпа – проверете Client ID и Secret", AUTH_RETRY_MS)
            }
            val expires = System.currentTimeMillis() + maxOf(0L, result.expiresIn - 3600) * 1000
            transaction { Solax.update({ Solax.locationId eq locationId }) { it[Solax.token] = result.accessToken; it[Solax.tokenExpires] = expires } }
            token = result.accessToken
            return result.accessToken
        }

        /** Runs an authenticated call; if SolaX rejects the cached token, gets a fresh one and retries once. */
        private suspend fun <T> authed(call: suspend (String) -> T): T {
            try {
                return call(token ?: newToken())
            } catch (e: TokenRejected) {
                try { return call(newToken()) } catch (e2: TokenRejected) { throw SolaxError("Solax отхвърли достъпа – проверете Client ID и Secret", AUTH_RETRY_MS) }
            }
        }

        /**
         * All plants of the account (each record carries the installed PV power and battery capacity). SolaX needs the business
         * type (1 residential, 4 commercial & industrial) with every call, so both are tried until one is remembered; the
         * result is read page by page (at most five pages).
         */
        suspend fun plants(knownType: Int?): Pair<Int, List<PlantRec>> {
            for (type in if (knownType != null) listOf(knownType) else listOf(1, 4)) {
                val plants = try {
                    val all = ArrayList<PlantRec>()
                    var page = 1
                    do {
                        val r = authed { t ->
                            val r = http.get("$baseUrl/openapi/v2/plant/page_plant_info") {
                                header("Authorization", "bearer $t"); parameter("businessType", type); parameter("pageNo", page)
                            }.body<PlantsResp>()
                            check(r.code, r.message); r.result
                        }
                        all += r?.records.orEmpty()
                        val pages = r?.pages ?: 1
                        page++
                    } while (page <= minOf(pages, 5))
                    all
                } catch (e: SolaxError) {
                    if (knownType != null || type == 4) throw e else continue // a wrong business type may come back as an error
                }
                if (plants.isNotEmpty()) return type to plants
            }
            throw SolaxError("В акаунта в Solax не са намерени централи")
        }

        /** Serial numbers of the plant's inverters (deviceType 1); batteries are then read through them. */
        suspend fun inverters(plantId: String, type: Int): List<String> = authed { t ->
            val r = http.get("$baseUrl/openapi/v2/device/page_device_info") {
                header("Authorization", "bearer $t")
                parameter("plantId", plantId); parameter("deviceType", 1); parameter("businessType", type)
            }.body<DevicesResp>()
            check(r.code, r.message)
            r.result?.records.orEmpty().mapNotNull { it.deviceSn }.take(10) // the API accepts at most 10 serials per call
        }

        /** Battery real-time data of the given inverters (deviceType 2 with requestSnType 1 = "batteries under this inverter"). */
        suspend fun batteries(inverterSns: List<String>, type: Int): List<BatteryRec> = authed { t ->
            val r = http.get("$baseUrl/openapi/v2/device/realtime_data") {
                header("Authorization", "bearer $t")
                parameter("snList", inverterSns.joinToString(",")); parameter("deviceType", 2)
                parameter("requestSnType", 1); parameter("businessType", type)
            }.body<RealtimeResp>()
            check(r.code, r.message)
            r.result.orEmpty()
        }

        /**
         * The history points of [deviceType] (1 inverter, 2 battery under the inverters) between [from] and [to] at 5-minute
         * steps, in windows of at most 12 hours (the API's limit).
         */
        suspend fun history(inverterSns: List<String>, type: Int, deviceType: Int, from: Instant, to: Instant): List<JsonObject> {
            val out = ArrayList<JsonObject>()
            var start = from
            while (start < to) {
                val end = minOf(start.plus(12, ChronoUnit.HOURS), to)
                out += authed { t ->
                    val text = http.get("$baseUrl/openapi/v2/device/history_data") {
                        header("Authorization", "bearer $t")
                        parameter("snList", inverterSns.joinToString(",")); parameter("deviceType", deviceType)
                        if (deviceType == 2) parameter("requestSnType", 1)
                        parameter("businessType", type); parameter("startTime", start.toEpochMilli()); parameter("endTime", end.toEpochMilli())
                        parameter("timeInterval", 5)
                    }.bodyAsText()
                    val r = plantsJson.decodeFromString<HistResp>(text)
                    check(r.code, r.message)
                    plantsJson.parseToJsonElement(text).jsonObject["result"]?.takeIf { it is JsonArray }?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
                }
                start = end
            }
            return out
        }

        /**
         * What the battery history of one local calendar [day] tells: the highest charge reached. The history is read in windows
         * of at most 12 hours (the API's limit); several batteries at the same moment are averaged for charge. Null without
         * any charge data.
         */
        suspend fun batteryDay(inverterSns: List<String>, type: Int, day: LocalDate, zone: ZoneId): BatteryDayResult? {
            val stop = day.plusDays(1).atStartOfDay(zone).toInstant()
            var from = day.atStartOfDay(zone).toInstant()
            val byMoment = HashMap<String, MutableList<HistPoint>>()
            var raw: String? = null
            while (from < stop) {
                val to = minOf(from.plus(12, ChronoUnit.HOURS), stop)
                val (points, text) = authed { t ->
                    val text = http.get("$baseUrl/openapi/v2/device/history_data") {
                        header("Authorization", "bearer $t")
                        parameter("snList", inverterSns.joinToString(",")); parameter("deviceType", 2); parameter("requestSnType", 1)
                        parameter("businessType", type); parameter("startTime", from.toEpochMilli()); parameter("endTime", to.toEpochMilli())
                        parameter("timeInterval", 30)
                    }.bodyAsText()
                    val r = plantsJson.decodeFromString<HistResp>(text)
                    check(r.code, r.message); r.result.orEmpty() to text
                }
                if (raw == null && points.isNotEmpty()) raw = text // one sample of the real answer, in case it lacks what we need
                for (p in points) {
                    if (p.batterySOC == null) continue
                    val moment = p.plantLocalTime?.contentOrNull ?: p.dataTime?.contentOrNull ?: continue
                    byMoment.getOrPut(moment) { ArrayList() } += p
                }
                from = to
            }
            if (byMoment.isEmpty()) return null
            val maxSoc = byMoment.values.maxOf { ps -> ps.mapNotNull { it.batterySOC }.average() }
            return BatteryDayResult(maxSoc, raw)
        }

        /**
         * The batteries' own rated capacity in kWh (device list, deviceType 2; Wh for residential plants, kWh for commercial),
         * summed over all batteries of the plant, with the raw first page of the answer (kept for diagnosis when there is no
         * capacity in it). The capacity is null when SolaX lists none.
         */
        suspend fun batteryCapacity(plantId: String, type: Int): Pair<Double?, String>? {
            var total = 0.0
            var page = 1
            var firstRaw = ""
            do {
                val (r, text) = authed { t ->
                    val text = http.get("$baseUrl/openapi/v2/device/page_device_info") {
                        header("Authorization", "bearer $t")
                        parameter("plantId", plantId); parameter("deviceType", 2); parameter("businessType", type); parameter("pageNo", page)
                    }.bodyAsText()
                    val r = plantsJson.decodeFromString<DevicesResp>(text)
                    check(r.code, r.message); r.result to text
                }
                if (page == 1) firstRaw = text
                for (d in r?.records.orEmpty()) {
                    val raw = d.ratedCapacity?.contentOrNull?.toDoubleOrNull() ?: continue
                    total += if (type == 1) raw / 1000 else raw
                }
                val pages = r?.pages ?: 1
                page++
            } while (page <= minOf(pages, 3))
            return total.takeIf { it > 0 } to firstRaw
        }

        suspend fun statistics(plantId: String, knownType: Int?, month: String): Pair<Int, List<StatRow>> {
            for (type in if (knownType != null) listOf(knownType) else listOf(1, 4)) {
                try {
                    val list = authed { t ->
                        val r = http.post("$baseUrl/openapi/v2/plant/energy/get_stat_data") {
                            header("Authorization", "bearer $t"); contentType(ContentType.Application.Json)
                            setBody(buildJsonObject { put("plantId", plantId); put("dateType", "2"); put("date", month); put("businessType", type.toString()) }.toString())
                        }.body<StatResp>()
                        check(r.code, r.message); r.result?.plantEnergyStatDataList.orEmpty()
                    }
                    return type to list
                } catch (e: SolaxError) {
                    if (knownType != null || type == 4) throw e
                }
            }
            throw SolaxError("Solax не върна данни")
        }
    }

    private fun check(code: Int, message: String?) {
        when {
            code == OK -> return
            code == TOKEN_REJECTED -> throw TokenRejected()
            code in QUOTA -> throw quotaError(code)
            code in NO_ACCESS -> throw SolaxError("Нямате достъп до тази централа в Solax (код $code)", AUTH_RETRY_MS)
            else -> throw SolaxError("Solax върна грешка $code" + (message?.let { ": ${it.take(120)}" } ?: ""))
        }
    }
}
