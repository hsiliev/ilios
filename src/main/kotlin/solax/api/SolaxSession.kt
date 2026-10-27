@file:Suppress("StringLiteralDuplication") // repeated property and column names read better than constants

package ilios.solax.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val MAX_PLANT_PAGES = 5
private const val MAX_DEVICE_PAGES = 3
private const val MAX_SERIALS = 10 // the API accepts at most 10 serials per call
private const val HISTORY_WINDOW_HOURS = 12L // the API's limit per history request
private const val HISTORY_STEP_MINUTES = 5
private const val BATTERY_DAY_STEP_MINUTES = 30
private const val DEVICE_INVERTER = 1
private const val DEVICE_BATTERY = 2
private const val BUSINESS_RESIDENTIAL = 1
private const val BUSINESS_COMMERCIAL = 4
private const val PUSH_POWER_END_MOTION = 160 // after the timer the inverter leaves remote control by itself

/**
 * The SolaX Cloud endpoints the app uses, for one place and one sync run (see [SolaxTransport] for the token and the
 * result codes).
 */
internal class SolaxSession(http: HttpClient, baseUrl: String, locationId: Long, row: ResultRow) {
    private val api = SolaxTransport(http, baseUrl, locationId, row)

    /**
     * All plants of the account (each record carries the installed PV power and battery capacity). SolaX needs the
     * business
     * type (1 residential, 4 commercial & industrial) with every call, so both are tried until one is remembered; the
     * result is read page by page (at most five pages).
     */
    suspend fun plants(knownType: Int?): Pair<Int, List<PlantRec>> =
        overBusinessTypes(knownType, accept = { it.isNotEmpty() }) { type ->
            pages(MAX_PLANT_PAGES) { page ->
                val result = api.get<PlantsResp>(
                    "plant/page_plant_info",
                    "businessType" to type,
                    "pageNo" to page,
                ).reply.result
                result?.records.orEmpty() to (result?.pages ?: 1)
            }.flatten()
        } ?: throw SolaxError("В акаунта в SolaX не са намерени централи")

    /** Serial numbers of the plant's inverters (deviceType 1); batteries are then read through them. */
    suspend fun inverters(plantId: String, type: Int): List<String> {
        val reply = api.get<DevicesResp>(
            "device/page_device_info",
            "plantId" to plantId,
            "deviceType" to DEVICE_INVERTER,
            "businessType" to type,
        ).reply
        return reply.result?.records.orEmpty().mapNotNull { it.deviceSn }.take(MAX_SERIALS)
    }

    /**
     * Battery real-time data of the given inverters (deviceType 2 with requestSnType 1 = "batteries under this
     * inverter").
     */
    suspend fun batteries(inverterSns: List<String>, type: Int): List<BatteryRec> = api.get<RealtimeResp>(
        "device/realtime_data",
        "snList" to inverterSns.joinToString(","),
        "deviceType" to DEVICE_BATTERY,
        "requestSnType" to 1,
        "businessType" to type,
    ).reply.result.orEmpty()

    /**
     * The history points of [deviceType] (1 inverter, 2 battery under the inverters) between [from] and [to] at
     * 5-minute
     * steps, in windows of at most 12 hours (the API's limit).
     */
    suspend fun history(
        inverterSns: List<String>,
        type: Int,
        deviceType: Int,
        from: Instant,
        to: Instant,
    ): List<JsonObject> = windows(from, to).flatMap { (start, end) ->
        val text = historyPage(inverterSns, type, deviceType, start, end, HISTORY_STEP_MINUTES).text
        plantsJson.parseToJsonElement(text).jsonObject["result"]?.takeIf {
            it is JsonArray
        }?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    /**
     * What the battery history of one local calendar [day] tells: the highest charge reached. The history is read in
     * windows
     * of at most 12 hours (the API's limit); several batteries at the same moment are averaged for charge. Null without
     * any charge data.
     */
    suspend fun batteryDay(inverterSns: List<String>, type: Int, day: LocalDate, zone: ZoneId): BatteryDayResult? {
        val answers = windows(
            day.atStartOfDay(zone).toInstant(),
            day.plusDays(1).atStartOfDay(zone).toInstant(),
        ).map { (start, end) ->
            historyPage(inverterSns, type, DEVICE_BATTERY, start, end, BATTERY_DAY_STEP_MINUTES)
        }
        val raw = answers.firstOrNull {
            !it.reply.result.isNullOrEmpty()
        }?.text // one sample of the real answer, in case it lacks what we need
        val byMoment = answers.flatMap { it.reply.result.orEmpty() }
            .filter { it.batterySOC != null }
            .groupBy { it.plantLocalTime?.contentOrNull ?: it.dataTime?.contentOrNull }
            .filterKeys { it != null }
        if (byMoment.isEmpty()) return null
        return BatteryDayResult(byMoment.values.maxOf { ps -> ps.mapNotNull { it.batterySOC }.average() }, raw)
    }

    /**
     * The batteries' own rated capacity in kWh (device list, deviceType 2; Wh for residential plants, kWh for
     * commercial),
     * summed over all batteries of the plant, with the raw first page of the answer (kept for diagnosis when there
     * is no
     * capacity in it). The capacity is null when SolaX lists none.
     */
    suspend fun batteryCapacity(plantId: String, type: Int): Pair<Double?, String> {
        val answers = pages(MAX_DEVICE_PAGES) { page ->
            val answer = api.get<DevicesResp>(
                "device/page_device_info",
                "plantId" to plantId,
                "deviceType" to DEVICE_BATTERY,
                "businessType" to type,
                "pageNo" to page,
            )
            answer to (answer.reply.result?.pages ?: 1)
        }
        val total = answers.flatMap { it.reply.result?.records.orEmpty() }
            .mapNotNull { it.ratedCapacity?.contentOrNull?.toDoubleOrNull() }
            .sumOf { if (type == BUSINESS_RESIDENTIAL) it / 1000 else it }
        return total.takeIf { it > 0 } to answers.first().text
    }

    /**
     * Push Power: a negative `batteryPower` charges the battery by [powerW]; after [seconds] remote control ends
     * (`nextMotion` 160).
     */
    suspend fun pushPower(sns: List<String>, type: Int, powerW: Int, seconds: Int) =
        remoteCommand("push_power/positive_or_negative_mode", sns, type) {
            put("batteryPower", -powerW)
            put("timeOfDuration", seconds)
            put("nextMotion", PUSH_POWER_END_MOTION)
        }

    suspend fun exitRemote(sns: List<String>, type: Int) = remoteCommand("exit_vpp_mode", sns, type)

    /** The daily production figures of [month] ("yyyy-MM"); returns the business type SolaX answered for with them. */
    suspend fun statistics(plantId: String, knownType: Int?, month: String): Pair<Int, List<StatRow>> =
        overBusinessTypes(knownType) { type ->
            val body = buildJsonObject {
                put("plantId", plantId)
                put("dateType", "2")
                put("date", month)
                put("businessType", type.toString())
            }
            api.post<StatResp>("plant/energy/get_stat_data", body).reply.result?.plantEnergyStatDataList.orEmpty()
        } ?: throw SolaxError("SolaX не върна данни")

    /**
     * Remote-control command [path] (under `inverter_vpp_mode/`) for [sns]. Only the acceptance is known at once; the
     * inverter acts about 5 minutes later in the cloud's data.
     */
    private suspend fun remoteCommand(
        path: String,
        sns: List<String>,
        type: Int,
        extra: JsonObjectBuilder.() -> Unit = {},
    ) {
        if (sns.isEmpty()) throw SolaxError("В SolaX не е намерен инвертор")
        val body = buildJsonObject {
            put("snList", JsonArray(sns.map { JsonPrimitive(it) }))
            put("businessType", type)
            extra()
        }
        api.post<ControlResp>("device/inverter_vpp_mode/$path", body)
    }

    private suspend fun historyPage(
        sns: List<String>,
        type: Int,
        deviceType: Int,
        from: Instant,
        to: Instant,
        stepMinutes: Int,
    ): Answer<HistResp> {
        val params = mutableListOf<Pair<String, Any>>(
            "snList" to sns.joinToString(","),
            "deviceType" to deviceType,
            "businessType" to type,
            "startTime" to from.toEpochMilli(),
            "endTime" to to.toEpochMilli(),
            "timeInterval" to stepMinutes,
        )
        if (deviceType == DEVICE_BATTERY) params += "requestSnType" to 1
        return api.get<HistResp>("device/history_data", *params.toTypedArray())
    }

    /**
     * Tries the business types in turn (just [knownType] when it is known): a wrong one may come back as an error,
     * which is then ignored
     * unless it was the last. Returns the first type whose result [accept]s, with that result; null when none did.
     */
    private suspend fun <R> overBusinessTypes(
        knownType: Int?,
        accept: (R) -> Boolean = { true },
        read: suspend (Int) -> R,
    ): Pair<Int, R>? {
        for (type in if (knownType != null) listOf(knownType) else listOf(BUSINESS_RESIDENTIAL, BUSINESS_COMMERCIAL)) {
            val result = try {
                read(type)
            } catch (e: SolaxError) {
                if (knownType != null || type == BUSINESS_COMMERCIAL) throw e else null
            }
            if (result != null && accept(result)) return type to result
        }
        return null
    }

    /**
     * Reads page after page until the page count the answers report (at most [limit]) is reached; [read] returns a
     * page's items and that count.
     */
    private suspend fun <T> pages(limit: Int, read: suspend (page: Int) -> Pair<T, Int>): List<T> {
        val items = ArrayList<T>()
        var page = 1
        do {
            val (item, total) = read(page)
            items += item
            page++
        } while (page <= minOf(total, limit))
        return items
    }

    /** [from] to [to] cut into windows of at most 12 hours. */
    private fun windows(from: Instant, to: Instant): List<Pair<Instant, Instant>> = generateSequence(from) {
        it.plus(HISTORY_WINDOW_HOURS, ChronoUnit.HOURS)
    }.takeWhile {
        it <
            to
    }
        .map { it to minOf(it.plus(HISTORY_WINDOW_HOURS, ChronoUnit.HOURS), to) }
        .toList()
}
