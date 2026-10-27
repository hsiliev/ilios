package ilios.solax.api

import ilios.SLOT_MINUTES
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// Response shapes of the SolaX Cloud API and readers of its history points.
/** What every SolaX answer carries: the result code (see [OK]) and a message. */
internal interface SolaxReply {
    val code: Int
    val message: String?
}

@Serializable internal class TokenResp(val code: Int = -1, val message: String? = null, val result: TokenResult? = null)

@Serializable internal class TokenResult(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable internal class PlantsResp(
    override val code: Int = -1,
    override val message: String? = null,
    val result: PlantsResult? = null,
) : SolaxReply

@Serializable internal class PlantsResult(val records: List<PlantRec> = emptyList(), val pages: Int = 1)

@Serializable internal class PlantRec(
    val plantId: JsonPrimitive,
    val plantName: String? = null,
    val pvCapacity: Double? = null, // installed PV power, kWp
    val batteryCapacity: Double? = null, // installed battery capacity, kWh
)

@Serializable internal class ControlResp(override val code: Int = -1, override val message: String? = null) : SolaxReply

@Serializable internal class StatResp(
    override val code: Int = -1,
    override val message: String? = null,
    val result: StatResult? = null,
) : SolaxReply

@Serializable internal class StatResult(val plantEnergyStatDataList: List<StatRow> = emptyList())

@Serializable internal class StatRow(
    val date: String,
    val pvGeneration: Double? = null,
    val inverterACOutputEnergy: Double? = null,
    val loadConsumption: Double? = null,
)

@Serializable internal class DevicesResp(
    override val code: Int = -1,
    override val message: String? = null,
    val result: DevicesResult? = null,
) : SolaxReply

@Serializable internal class DevicesResult(val records: List<DeviceRec> = emptyList(), val pages: Int = 1)

@Serializable internal class DeviceRec(val deviceSn: String? = null, val ratedCapacity: JsonPrimitive? = null)

@Serializable internal class RealtimeResp(
    override val code: Int = -1,
    override val message: String? = null,
    val result: List<BatteryRec>? = null,
) : SolaxReply

@Serializable internal class HistResp(
    override val code: Int = -1,
    override val message: String? = null,
    val result: List<HistPoint>? = null,
) : SolaxReply

@Serializable internal class HistPoint(
    val plantLocalTime: JsonPrimitive? = null,
    val dataTime: JsonPrimitive? = null,
    val batterySOC: Double? = null,
    val batteryRemainings: Double? = null,
)

@Serializable internal class BatteryRec(
    val batterySOC: Double? = null,
    val batteryRemainings: Double? = null,
    val chargeDischargePower: Double? = null,
)

/**
 * The local date-time of a history point: SolaX's plant-local time when given, else its data time (epoch millis or
 * UTC text).
 */
internal fun momentOf(p: JsonObject, zone: ZoneId): LocalDateTime? {
    fun text(s: String?) = s?.trim()?.replace(' ', 'T')?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    text(p["plantLocalTime"]?.jsonPrimitive?.contentOrNull)?.let { return it }
    val dt = p["dataTime"]?.jsonPrimitive?.contentOrNull ?: return null
    dt.toLongOrNull()?.let { return LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    return text(dt)?.atZone(ZoneOffset.UTC)?.withZoneSameInstant(zone)?.toLocalDateTime()
}

/**
 * Panel power (W) of an inverter history point: its total PV input, else the sum of the MPPT trackers' power; null
 * when it has none.
 */
internal fun pvPowerOf(p: JsonObject): Double? {
    fun num(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    num(p["MPPTTotalInputPower"])?.let { return maxOf(0.0, it) }
    val trackers = (p["mpptMap"] as? JsonObject)?.filterKeys {
        it.matches(Regex("MPPT\\d+Power", RegexOption.IGNORE_CASE))
    }?.values?.mapNotNull { num(it) }.orEmpty()
    return trackers.takeIf { it.isNotEmpty() }?.sumOf { maxOf(0.0, it) }
}

/**
 * Power drawn from the grid (W, positive = import) of an inverter history point: SolaX's `gridPower` is negative
 * while importing. Null without it.
 */
internal fun gridPowerOf(p: JsonObject): Double? =
    (p["gridPower"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { -it }

/** The 5-minute slot ("HH:mm", minutes rounded down to a multiple of 5) a history moment falls into. */
internal fun LocalDateTime.slot5() = "%02d:%02d".format(hour, minute / SLOT_MINUTES * SLOT_MINUTES)

/** The number under [key] of a history point, or null when it is missing or not a number. */
internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
